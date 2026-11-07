package dev.quicksilver.world;

import dev.quicksilver.QsConfig;
import dev.quicksilver.Quicksilver;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.level.Level;
import net.minecraft.level.chunk.Chunk;
import net.minecraft.level.gen.BiomeSource;
import net.minecraft.level.source.LevelSource;
import net.minecraft.level.source.OverworldLevelSource;

/**
 * Generates the raw terrain of chunks (noise, surface, caves - everything except decoration, which touches the
 * world) on background threads with private generator and biome source instances. The result is bit-for-bit what
 * the main thread would have generated, because overworld terrain only depends on the seed and chunk position.
 */
public final class AsyncWorldGen {
    private AsyncWorldGen() {}

    private static ExecutorService pool;
    private static final Map<Long, Chunk> READY = new ConcurrentHashMap<>();
    private static final Map<Long, double[]> TEMPERATURES = new ConcurrentHashMap<>();
    private static final Set<Long> PENDING = ConcurrentHashMap.newKeySet();
    private static volatile Level currentLevel;
    private static volatile int epoch;
    public static int statGenerated, statUsed;

    static final class GenThread extends Thread {
        final Map<Level, OverworldLevelSource> generators = new WeakHashMap<>();
        final Map<Level, BiomeSource> biomes = new WeakHashMap<>();
        /** Separate instance: BiomeSource keeps the last sampled noise in fields that terrain shaping reads. */
        final Map<Level, BiomeSource> climate = new WeakHashMap<>();

        GenThread(Runnable r, int i) {
            super(r, "Quicksilver Worldgen " + i);
            setDaemon(true);
            setPriority(Thread.NORM_PRIORITY - 1);
        }
    }

    private static synchronized ExecutorService pool() {
        if (pool == null) {
            int n = QsConfig.worldGenThreads > 0 ? QsConfig.worldGenThreads
                    : Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 4));
            final int[] idx = {0};
            pool = Executors.newFixedThreadPool(n, r -> new GenThread(r, idx[0]++));
            Quicksilver.LOGGER.info("Started {} async world generation threads", n);
        }
        return pool;
    }

    public static boolean supported(Level level, LevelSource mainGenerator) {
        return QsConfig.asyncWorldGen && mainGenerator != null
                && mainGenerator.getClass() == OverworldLevelSource.class;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static void checkLevel(Level level) {
        if (level != currentLevel) {
            currentLevel = level;
            epoch++;
            READY.clear();
            TEMPERATURES.clear();
            PENDING.clear();
        }
    }

    public static boolean isReady(Level level, int x, int z) {
        return level == currentLevel && READY.containsKey(key(x, z));
    }

    public static boolean isPending(Level level, int x, int z) {
        return level == currentLevel && PENDING.contains(key(x, z));
    }

    public static int pending() {
        return PENDING.size();
    }

    /** Main thread: queue background generation of a chunk known to be absent from disk. */
    public static void request(Level level, int x, int z) {
        checkLevel(level);
        long k = key(x, z);
        if (READY.containsKey(k) || PENDING.size() >= 24 || !PENDING.add(k)) return;
        final int ep = epoch;
        pool().execute(() -> {
            try {
                if (ep != epoch) return;
                GenThread t = (GenThread) Thread.currentThread();
                OverworldLevelSource g = t.generators.get(level);
                if (g == null) {
                    g = new OverworldLevelSource(level, level.getSeed());
                    t.generators.put(level, g);
                }
                Chunk c = g.getChunk(x, z);
                // Decoration (on the game thread) needs a 16x16 temperature sample offset by 8 blocks; it is a
                // pure function of the seed and position, so compute it here while these threads are idle anyway.
                double[] temps = null;
                try {
                    BiomeSource cl = t.climate.get(level);
                    if (cl == null) {
                        cl = new BiomeSource(level);
                        t.climate.put(level, cl);
                    }
                    temps = cl.getTemperatures(new double[256], (x << 4) + 8, (z << 4) + 8, 16, 16);
                } catch (Throwable ignored) {
                    // fall back to computing it on the game thread
                }
                if (ep == epoch) {
                    READY.put(k, c);
                    if (temps != null && temps.length == 256) TEMPERATURES.put(k, temps);
                    statGenerated++;
                }
            } catch (Throwable e) {
                Quicksilver.LOGGER.warn("Async generation of chunk {},{} failed; it will be generated on the main thread", x, z, e);
            } finally {
                PENDING.remove(k);
            }
        });
    }

    /** Main thread, from the chunk cache: take a pre-generated chunk instead of generating synchronously. */
    public static Chunk take(Level level, int x, int z) {
        if (level != currentLevel) return null;
        Chunk c = READY.remove(key(x, z));
        if (c != null) {
            statUsed++;
            // Heightmap/skylight generation queues border light fixes into the level; those were dropped on the
            // generator thread, so redo it here on the game thread exactly like vanilla generation would.
            // (not needed when the relighter is on: it redoes these fixes once the neighbours exist)
            if (!dev.quicksilver.QsConfig.fixGenerationLighting) c.generateHeightmap();
        }
        return c;
    }

    /**
     * Game thread, from chunk decoration: the temperature samples for this chunk, if a generator thread already
     * computed them. Same inputs and same noise, so the values are identical to computing them here.
     */
    public static double[] takeTemperatures(int x, int z, int sx, int sz) {
        if (sx != 16 || sz != 16 || TEMPERATURES.isEmpty()) return null;
        int cx = (x - 8) >> 4, cz = (z - 8) >> 4;
        if ((cx << 4) + 8 != x || (cz << 4) + 8 != z) return null;
        return TEMPERATURES.remove(key(cx, cz));
    }

    /** Drop pre-generated chunks far from the player (they would be regenerated identically if needed). */
    public static void trim(int cx, int cz, int keep) {
        if (TEMPERATURES.size() >= 64)
            TEMPERATURES.keySet().removeIf(k -> Math.max(Math.abs((int) (k >> 32) - cx), Math.abs((int) (long) k - cz)) > keep);
        if (READY.size() < 64) return;
        READY.keySet().removeIf(k -> Math.max(Math.abs((int) (k >> 32) - cx), Math.abs((int) (long) k - cz)) > keep);
    }

    public static boolean isGenThread() {
        return Thread.currentThread() instanceof GenThread;
    }

    /** Biome source to use for terrain shaping: a private instance on generator threads. */
    public static BiomeSource biomeSource(Level level) {
        Thread t = Thread.currentThread();
        if (t instanceof GenThread g) {
            BiomeSource b = g.biomes.get(level);
            if (b == null) {
                b = new BiomeSource(level);
                g.biomes.put(level, b);
            }
            return b;
        }
        return level.getBiomeSource();
    }
}
