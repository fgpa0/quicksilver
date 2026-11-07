package dev.quicksilver.io;

import dev.quicksilver.QsConfig;
import dev.quicksilver.Quicksilver;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.level.storage.RegionFile;
import net.minecraft.level.storage.RegionLoader;
import net.minecraft.util.io.CompoundTag;
import net.minecraft.util.io.NBTIO;

/**
 * Asynchronous region-file IO.
 * <ul>
 * <li>Writes: chunk NBT is serialized on the caller thread into memory (cheap), then deflated and written
 * on a dedicated IO thread. Until written, reads of that chunk are served from memory.</li>
 * <li>Reads: chunks around the player are read, inflated and NBT-parsed ahead of time on background
 * threads; the main thread then only has to turn the parsed tag into a Chunk.</li>
 * <li>All synchronous reads are buffered (vanilla inflates byte by byte).</li>
 * </ul>
 */
public final class RegionIO {
    private RegionIO() {}

    public record Key(File dir, int x, int z) {}

    /** Marker for "prefetched, but no data on disk". */
    private static final CompoundTag ABSENT = new CompoundTag();

    private static final Object LOCK = new Object();
    private static final Map<Key, byte[]> pendingWrites = new ConcurrentHashMap<>();
    private static final Map<Key, CompoundTag> prefetched = new ConcurrentHashMap<>();
    private static final Map<Key, Integer> versions = new ConcurrentHashMap<>();
    private static final Map<Key, Boolean> inFlightReads = new ConcurrentHashMap<>();
    private static final AtomicInteger queuedWrites = new AtomicInteger();

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Quicksilver IO Writer");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final ExecutorService READERS = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Quicksilver IO Reader");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static volatile Thread writerThread;

    public static volatile File lastReadDir;

    // stats
    public static final AtomicInteger statPrefetchHits = new AtomicInteger();
    public static final AtomicInteger statSyncReads = new AtomicInteger();
    public static final AtomicInteger statWrites = new AtomicInteger();

    static {
        WRITER.execute(() -> writerThread = Thread.currentThread());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> flush(30_000), "Quicksilver IO flush"));
    }

    public static boolean isIoThread() {
        Thread t = Thread.currentThread();
        return t == writerThread || t.getName().startsWith("Quicksilver IO");
    }

    // ------------------------------------------------------------------ writes

    public static DataOutputStream openWrite(File dir, int x, int z) {
        return new DataOutputStream(new PendingChunkOutput(new Key(dir, x, z)));
    }

    private static final class PendingChunkOutput extends ByteArrayOutputStream {
        private final Key key;
        private boolean closed;

        PendingChunkOutput(Key key) {
            super(0);
            byte[] b = BUFFERS.poll();
            this.buf = b != null ? b : new byte[256 * 1024];
            this.key = key;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            byte[] data = toByteArray();
            // serialization buffers are large (uncompressed chunk NBT); reuse them instead of regrowing per chunk
            if (BUFFERS.size() < 4) BUFFERS.offer(buf);
            buf = null;
            submitWrite(key, data);
        }
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<byte[]> BUFFERS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Chunk tags waiting to be serialized and written (see LevelChunkLoaderMixin). */
    private static final Map<Key, CompoundTag> pendingTags = new ConcurrentHashMap<>();

    public static void submitChunkTag(File dir, int x, int z, CompoundTag root, net.minecraft.level.LevelProperties props) {
        Key key = new Key(dir, x, z);
        synchronized (LOCK) {
            versions.merge(key, 1, Integer::sum);
            prefetched.remove(key);
            pendingTags.put(key, root);
        }
        queuedWrites.incrementAndGet();
        WRITER.execute(() -> {
            try {
                writeNow(key, serialize(root));
                if (props != null) {
                    try {
                        props.setSizeOnDisk(props.getSizeOnDisk() + RegionLoader.method_1214(dir, x, z));
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable t) {
                Quicksilver.LOGGER.error("Chunk save failed for " + key, t);
            } finally {
                pendingTags.remove(key, root);
                queuedWrites.decrementAndGet();
            }
        });
    }

    private static byte[] serialize(CompoundTag root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(96 * 1024);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            net.minecraft.util.io.NBTIO.writeTag(root, out);
        }
        return bytes.toByteArray();
    }

    private static void submitWrite(Key key, byte[] data) {
        synchronized (LOCK) {
            versions.merge(key, 1, Integer::sum);
            prefetched.remove(key);
            pendingWrites.put(key, data);
        }
        queuedWrites.incrementAndGet();
        WRITER.execute(() -> {
            try {
                writeNow(key, data);
            } finally {
                pendingWrites.remove(key, data);
                queuedWrites.decrementAndGet();
            }
        });
    }

    private static void writeNow(Key key, byte[] data) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                RegionFile region = RegionLoader.getRegion(key.dir, key.x, key.z);
                DataOutputStream out = region.getChunkDataOutputStream(key.x & 31, key.z & 31);
                if (out == null) return;
                out.write(data);
                out.close();
                statWrites.incrementAndGet();
                return;
            } catch (IOException e) {
                Quicksilver.LOGGER.warn("Chunk write failed for " + key + (attempt == 0 ? ", retrying" : ""), e);
            } catch (RuntimeException e) {
                Quicksilver.LOGGER.error("Chunk write failed for " + key, e);
                return;
            }
        }
    }

    /** Run an arbitrary file write on the IO thread (ordered with chunk writes; included in flush()). */
    public static void submitFileTask(String what, Runnable task) {
        queuedWrites.incrementAndGet();
        WRITER.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                Quicksilver.LOGGER.error("Background write failed: " + what, t);
            } finally {
                queuedWrites.decrementAndGet();
            }
        });
    }

    /** Blocks until every queued write has hit the region files. */
    public static void flush(long timeoutMs) {
        if (isIoThread()) return;
        if (queuedWrites.get() == 0) return;
        CountDownLatch latch = new CountDownLatch(1);
        try {
            WRITER.execute(latch::countDown);
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Quicksilver.LOGGER.error("Timed out waiting for {} chunk writes", queuedWrites.get());
            }
        } catch (Exception e) {
            Quicksilver.LOGGER.error("Chunk write flush interrupted", e);
        }
    }

    public static int queuedWrites() {
        return queuedWrites.get();
    }

    // ------------------------------------------------------------------ reads

    public static DataInputStream openRead(File dir, int x, int z) {
        Key key = new Key(dir, x, z);
        if (!isIoThread() && dev.quicksilver.Quicksilver.isRenderedRegionDir(dir)) lastReadDir = dir; // the prefetch hint follows the rendered level
        CompoundTag pendingTag = pendingTags.get(key);
        if (pendingTag != null) {
            // being saved right now: read back a serialized copy (the loaded chunk must not share arrays with it)
            try {
                return new DataInputStream(new ByteArrayInputStream(serialize(pendingTag)));
            } catch (IOException e) {
                Quicksilver.LOGGER.warn("Could not read back pending chunk " + key, e);
            }
        }
        byte[] pending = pendingWrites.get(key);
        if (pending != null) return new DataInputStream(new ByteArrayInputStream(pending));
        CompoundTag tag = prefetched.remove(key);
        if (tag != null) {
            statPrefetchHits.incrementAndGet();
            if (tag == ABSENT) return null;
            return new PrefetchedChunkStream(tag);
        }
        statSyncReads.incrementAndGet();
        RegionFile region = RegionLoader.getRegion(dir, x, z);
        DataInputStream in = region.getChunkDataInputStream(x & 31, z & 31);
        if (in == null) return null;
        return new DataInputStream(new BufferedInputStream(in, 16384));
    }

    /** A stream that carries an already parsed chunk tag; {@code NBTIO.readTag} is short-circuited for it. */
    public static final class PrefetchedChunkStream extends DataInputStream {
        public final CompoundTag tag;

        PrefetchedChunkStream(CompoundTag tag) {
            super(new ByteArrayInputStream(new byte[0]));
            this.tag = tag;
        }
    }

    public enum State { UNKNOWN, IN_FLIGHT, READY, ABSENT, PENDING_WRITE }

    public static State state(File dir, int x, int z) {
        Key k = new Key(dir, x, z);
        if (pendingWrites.containsKey(k) || pendingTags.containsKey(k)) return State.PENDING_WRITE;
        CompoundTag t = prefetched.get(k);
        if (t != null) return t == ABSENT ? State.ABSENT : State.READY;
        if (inFlightReads.containsKey(k)) return State.IN_FLIGHT;
        return State.UNKNOWN;
    }

    public static boolean isKnown(File dir, int x, int z) {
        Key k = new Key(dir, x, z);
        return prefetched.containsKey(k) || inFlightReads.containsKey(k) || pendingWrites.containsKey(k) || pendingTags.containsKey(k);
    }

    public static int inFlight() {
        return inFlightReads.size();
    }

    public static int prefetchedCount() {
        return prefetched.size();
    }

    /** Queue a background read of a chunk (no-op if already cached / queued). */
    public static void prefetch(File dir, int x, int z) {
        Key key = new Key(dir, x, z);
        if (prefetched.containsKey(key) || pendingWrites.containsKey(key) || pendingTags.containsKey(key)) return;
        if (inFlightReads.putIfAbsent(key, Boolean.TRUE) != null) return;
        final Integer version = versions.get(key);
        READERS.execute(() -> {
            try {
                CompoundTag tag = ABSENT;
                RegionFile region = RegionLoader.getRegion(dir, x, z);
                if (region.offsetHasData(x & 31, z & 31)) {
                    DataInputStream in = region.getChunkDataInputStream(x & 31, z & 31);
                    if (in != null) {
                        try (DataInputStream buffered = new DataInputStream(new BufferedInputStream(in, 16384))) {
                            tag = NBTIO.readTag(buffered);
                        }
                    }
                }
                synchronized (LOCK) {
                    if (java.util.Objects.equals(versions.get(key), version) && !pendingWrites.containsKey(key) && !pendingTags.containsKey(key)) {
                        prefetched.put(key, tag);
                    }
                }
            } catch (Throwable t) {
                // corrupt chunk or closed region: the main thread will do a normal synchronous read
            } finally {
                inFlightReads.remove(key);
            }
        });
    }

    /** Drop prefetched entries that are far away from the given chunk position. */
    public static void trim(int cx, int cz, int keepRadius, int max) {
        if (prefetched.size() <= max) return;
        prefetched.keySet().removeIf(k -> Math.max(Math.abs(k.x - cx), Math.abs(k.z - cz)) > keepRadius);
        if (prefetched.size() > max * 2) prefetched.clear();
    }

    /** Forget everything cached for reading (world change). Pending writes are kept and flushed. */
    public static void clearReadCache() {
        prefetched.clear();
    }

    public static boolean enabled() {
        return QsConfig.asyncChunkSaving;
    }
}
