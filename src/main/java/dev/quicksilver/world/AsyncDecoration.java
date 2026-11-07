package dev.quicksilver.world;

import dev.quicksilver.QsConfig;
import dev.quicksilver.Quicksilver;
import dev.quicksilver.client.PerfLog;
import dev.quicksilver.mixin.world.LevelAccessor;
import dev.quicksilver.mixin.world.ServerChunkCacheAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.LinkedBlockingQueue;
import net.minecraft.entity.EntityBase;
import net.minecraft.level.Level;
import net.minecraft.level.chunk.Chunk;
import net.minecraft.level.chunk.ServerChunkCache;
import net.minecraft.level.dimension.Dimension;
import net.minecraft.level.dimension.Overworld;
import net.minecraft.level.source.LevelSource;
import net.minecraft.level.source.OverworldLevelSource;
import net.minecraft.tileentity.TileEntityBase;

/**
 * Runs vanilla's chunk decoration (ore, trees, lakes, flowers... about two thirds of what the game thread spends on
 * loading a chunk) on background threads, with the same results as running it on the game thread.
 *
 * <p>How it stays exact: decoration of chunk (x, z) only reads and writes the four chunks x..x+1, z..z+1 and is a
 * pure function of the world seed, the position and the blocks in those chunks. When the loader asks for it, the
 * game thread copies those four chunks (blocks, metadata, light, heightmap) into a slot, and a slot thread runs the
 * real decorate method against a private "shadow" Level whose chunk source serves the copies. Every block write
 * made to the copies is journaled. Back on the game thread the journal is replayed onto the real chunks, in order,
 * with the same heightmap and light-queue updates the vanilla write would make. Blocks' placed/removed hooks already
 * ran on the shadow, so only the scheduled ticks they asked for are replayed on top.
 *
 * <p>Everything else falls back to vanilla decoration on the game thread, at the same point in the load order:
 * anything the shadow cannot reproduce (touching a chunk outside the four, spawning an entity, creating a tile
 * entity such as a dungeon chest, any exception), and any case where one of the four real chunks changed after the
 * copy was taken (checked with per-chunk write counters). Windows that overlap are never in flight together and
 * are applied in load order.
 */
public final class AsyncDecoration {
    private AsyncDecoration() {}

    /**
     * Debug (-Dquicksilver.verifyDecoration=N): 1 = decorate every chunk on the game thread as vanilla and compare
     * with the background result; 2 = sampled (every 32nd chunk against vanilla, every 8th applied result against
     * the copy it came from). Mismatches are logged as [decorate-verify].
     */
    private static final int VERIFY = Integer.getInteger("quicksilver.verifyDecoration", 0);

    public static int statVerifySkipped, statAsync, statFallback, statDropped, statAborted, statVerified, statMismatch, statMismatchNoLava;

    private static final ArrayList<Job> INFLIGHT = new ArrayList<>();
    private static final ArrayList<Job> DEFERRED = new ArrayList<>();
    private static final HashSet<Long> QUEUED = new HashSet<>();
    private static Slot[] slots = new Slot[0];
    private static Level slotsLevel;
    private static int sync, verifySeq;

    private static final class Abort extends RuntimeException {
        Abort() {
            super(null, null, false, false);
        }
    }

    private static final Abort ABORT = new Abort();

    private static final class Job {
        final ServerChunkCache cache;
        final Level level;
        final int x, z;
        final Chunk[] live = new Chunk[4];
        final int[] mods = new int[4];
        final byte[][] tiles = new byte[4][];
        int mask, skyDarken;
        Slot slot;
        volatile int state; // 0 queued/running, 1 done, 2 aborted or failed

        Job(ServerChunkCache cache, Level level, int x, int z) {
            this.cache = cache;
            this.level = level;
            this.x = x;
            this.z = z;
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // ---- decoration thread side ----

    private static final class Slot extends Thread {
        final Level live;
        final LinkedBlockingQueue<Job> queue = new LinkedBlockingQueue<>();
        volatile boolean ready, dead;
        boolean busy; // game thread only
        ShadowLevel shadow;
        OverworldLevelSource generator;
        final Chunk[] chunks = new Chunk[4];
        final DecoJournal journal = new DecoJournal();
        boolean warned;
        // scratch state of FlowingFluid (a block object shared by all threads), per decoration thread
        final int[] fluidCount = new int[2]; // per fluid object (water, lava), like the fields they replace
        final boolean[][] fluidFlow = new boolean[2][4];
        final int[][] fluidDist = new int[2][4];

        Slot(Level live, int index) {
            super("Quicksilver Decoration " + index);
            this.live = live;
            setDaemon(true);
            setPriority(Thread.NORM_PRIORITY - 1);
        }

        @Override
        public void run() {
            try {
                shadow = new ShadowLevel(live, new Overworld());
                generator = new OverworldLevelSource(shadow, shadow.getSeed());
                for (int i = 0; i < 4; i++) {
                    Chunk c = new Chunk(shadow, 0, 0);
                    c.tiles = new byte[32768];
                    c.field_957 = new net.minecraft.class_257(32768);
                    c.field_958 = new net.minecraft.class_257(32768);
                    c.field_959 = new net.minecraft.class_257(32768);
                    if (c.field_957.field_2103.length != 16384) throw new IllegalStateException("unexpected nibble array size");
                    ((QsChunk) c).qs$setJournal(journal, i);
                    chunks[i] = c;
                }
                ready = true;
                while (!dead) process(queue.take());
            } catch (InterruptedException ignored) {
            } catch (Throwable t) {
                Quicksilver.LOGGER.warn("Decoration thread failed to start; decorating on the game thread", t);
            } finally {
                dead = true;
            }
        }

        private void process(Job j) {
            ShadowLevel s = shadow;
            ShadowSource src = s.source;
            src.win = chunks;
            src.wx = j.x;
            src.wz = j.z;
            src.mask = j.mask;
            s.field_197 = false;
            s.field_202 = j.skyDarken;
            s.tickCount = 0;
            s.reset();
            for (Chunk c : chunks) c.field_964.clear();
            journal.clear();
            try {
                generator.decorate(src, j.x, j.z);
                j.state = 1;
            } catch (Abort a) {
                j.state = 2;
            } catch (Throwable t) {
                j.state = 2;
                if (!warned) {
                    warned = true;
                    Quicksilver.LOGGER.warn("Background decoration of chunk {},{} failed; using vanilla decoration for it", j.x, j.z, t);
                }
            } finally {
                s.field_197 = false;
                s.reset();
            }
        }
    }

    /** Serves the four copied chunks; touching anything else aborts the job (vanilla would load that chunk). */
    private static final class ShadowSource implements LevelSource {
        Chunk[] win;
        int wx, wz, mask;

        @Override
        public boolean isChunkLoaded(int cx, int cz) {
            int dx = cx - wx, dz = cz - wz;
            if (dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1) return true;
            if (dx >= -1 && dx <= 2 && dz >= -1 && dz <= 2) return (mask >> ((dz + 1) * 4 + dx + 1) & 1) != 0;
            throw ABORT;
        }

        @Override
        public Chunk getChunk(int cx, int cz) {
            int dx = cx - wx, dz = cz - wz;
            if (dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1) return win[dx + 2 * dz];
            throw ABORT;
        }

        @Override
        public Chunk loadChunk(int cx, int cz) {
            return getChunk(cx, cz);
        }

        @Override
        public void decorate(LevelSource source, int cx, int cz) {
        }

        @Override
        public boolean deleteCacheCauseClientCantHandleThis(boolean flag, net.minecraft.util.ProgressListener listener) {
            return true;
        }

        @Override
        public boolean method_1801() {
            return false;
        }

        @Override
        public boolean method_1805() {
            return false;
        }

        @Override
        public String toString() {
            return "QuicksilverShadowSource";
        }
    }

    /** A private level for one decoration thread: real Level code on the copied chunks, with side effects recorded. */
    private static final class ShadowLevel extends Level {
        ShadowSource source;
        int[] ticks = new int[320];
        int tickCount;

        ShadowLevel(Level live, Dimension dimension) {
            super(live, dimension);
            source = (ShadowSource) this.cache;
        }

        @Override
        protected LevelSource getChunkCache() {
            return new ShadowSource();
        }

        void reset() {
            ((LevelAccessor) (Object) this).qs$lightingUpdates().clear();
            this.entities.clear();
            this.tileEntities.clear();
            ((LevelAccessor) (Object) this).qs$tickTree().clear();
            ((LevelAccessor) (Object) this).qs$tickSet().clear();
        }

        /** Scheduled ticks are replayed on the real level; instant ticks (springs) run here like in vanilla. */
        @Override
        public void method_216(int x, int y, int z, int id, int delay) {
            if (this.field_197) {
                super.method_216(x, y, z, id, delay);
                return;
            }
            if (tickCount + 5 > ticks.length) ticks = Arrays.copyOf(ticks, ticks.length * 2);
            ticks[tickCount++] = x;
            ticks[tickCount++] = y;
            ticks[tickCount++] = z;
            ticks[tickCount++] = id;
            ticks[tickCount++] = delay;
        }

        @Override
        public boolean spawnEntity(EntityBase entity) {
            throw ABORT;
        }

        @Override
        public void setTileEntity(int x, int y, int z, TileEntityBase entity) {
            throw ABORT;
        }
    }

    /** Flowing water is id 8 and flowing lava id 10: each keeps its own scratch state, as the two block objects do. */
    public static int[] fluidCount() {
        return Thread.currentThread() instanceof Slot s ? s.fluidCount : null;
    }

    public static boolean[] fluidFlow(int id) {
        return Thread.currentThread() instanceof Slot s ? s.fluidFlow[id >> 1 & 1] : null;
    }

    public static int[] fluidDist(int id) {
        return Thread.currentThread() instanceof Slot s ? s.fluidDist[id >> 1 & 1] : null;
    }

    public static boolean onDecorationThread() {
        return Thread.currentThread() instanceof Slot;
    }

    // ---- game thread side ----

    private static boolean eligible(ServerChunkCache cache, Level level) {
        if (cache.getClass() != ServerChunkCache.class || level.isServerSide || level.dimension.getClass() != Overworld.class) return false;
        LevelSource g = ((ServerChunkCacheAccessor) cache).qs$generator();
        return g != null && g.getClass() == OverworldLevelSource.class;
    }

    private static boolean ensureSlots(Level level) {
        if (slotsLevel != level) {
            reset();
            slotsLevel = level;
            int n = QsConfig.decorationThreads > 0 ? QsConfig.decorationThreads
                    : Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 4));
            slots = new Slot[n];
            for (int i = 0; i < n; i++) {
                slots[i] = new Slot(level, i);
                slots[i].start();
            }
            Quicksilver.LOGGER.info("Started {} background decoration threads", n);
        }
        return true;
    }

    private static Slot freeSlot() {
        for (Slot s : slots) if (s.ready && !s.dead && !s.busy) return s;
        return null;
    }

    /** Drops everything (world change or shutdown). */
    public static void reset() {
        for (Slot s : slots) {
            s.dead = true;
            s.interrupt();
        }
        slots = new Slot[0];
        slotsLevel = null;
        INFLIGHT.clear();
        DEFERRED.clear();
        QUEUED.clear();
    }

    public static int backlog() {
        return INFLIGHT.size() + DEFERRED.size();
    }

    /**
     * True if a decoration that will still change blocks in these chunks (inclusive) is queued or running. Meshes
     * of sections near them must wait, exactly like they would wait for the synchronous decoration in vanilla.
     */
    public static boolean pendingIn(int minX, int maxX, int minZ, int maxZ) {
        if (INFLIGHT.isEmpty() && DEFERRED.isEmpty()) return false;
        for (int i = 0, n = INFLIGHT.size(); i < n; i++) {
            Job j = INFLIGHT.get(i);
            if (j.x <= maxX && j.x + 1 >= minX && j.z <= maxZ && j.z + 1 >= minZ) return true;
        }
        for (int i = 0, n = DEFERRED.size(); i < n; i++) {
            Job j = DEFERRED.get(i);
            if (j.x <= maxX && j.x + 1 >= minX && j.z <= maxZ && j.z + 1 >= minZ) return true;
        }
        return false;
    }

    private static boolean overlapsInflight(Job j) {
        for (int i = 0, n = INFLIGHT.size(); i < n; i++) {
            Job o = INFLIGHT.get(i);
            if (Math.abs(o.x - j.x) <= 1 && Math.abs(o.z - j.z) <= 1) return true;
        }
        return false;
    }

    /**
     * From ServerChunkCache.decorate while the chunk loader runs. Returns true when the decoration was taken over
     * (queued or already pending), in which case the vanilla call is cancelled; false means "decorate now".
     */
    public static boolean intercept(ServerChunkCache cache, Level level, int x, int z) {
        if (sync > 0 || !ChunkLoadManager.inLoader || !QsConfig.asyncDecoration) return false;
        if (!Quicksilver.isClientThread() || !eligible(cache, level)) return false;
        long k = key(x, z);
        if (QUEUED.contains(k)) return true;
        ensureSlots(level);
        if (!anyReady()) return false; // decoration threads still starting: decorate normally meanwhile
        Job j = new Job(cache, level, x, z);
        Slot slot = freeSlot();
        if (slot != null && !overlapsAny(j)) {
            if (!start(j, slot)) return false;
            QUEUED.add(k);
            return true;
        }
        QUEUED.add(k);
        DEFERRED.add(j);
        return true;
    }

    private static boolean overlapsAny(Job j) {
        if (overlapsInflight(j)) return true;
        for (int i = 0, n = DEFERRED.size(); i < n; i++) {
            Job o = DEFERRED.get(i);
            if (Math.abs(o.x - j.x) <= 1 && Math.abs(o.z - j.z) <= 1) return true;
        }
        return false;
    }

    private static boolean anyReady() {
        for (Slot s : slots) if (s.ready && !s.dead) return true;
        return false;
    }

    /** Copies the four chunks into the slot and hands the job to its thread. False if the chunks aren't usable. */
    private static boolean start(Job j, Slot slot) {
        QsChunkCache qc = (QsChunkCache) j.cache;
        for (int i = 0; i < 4; i++) {
            Chunk c = qc.qs$peek(j.x + (i & 1), j.z + (i >> 1));
            if (c == null || c.tiles == null || c.tiles.length != 32768 || c.heightmap == null || c.heightmap.length != 256
                    || !nibbles(c)) return false;
            j.live[i] = c;
        }
        if (j.live[0].decorated) return false;
        for (int i = 0; i < 4; i++) {
            Chunk c = j.live[i], s = slot.chunks[i];
            ((QsChunk) s).qs$setPos(j.x + (i & 1), j.z + (i >> 1));
            System.arraycopy(c.tiles, 0, s.tiles, 0, 32768);
            System.arraycopy(c.field_957.field_2103, 0, s.field_957.field_2103, 0, 16384);
            System.arraycopy(c.field_958.field_2103, 0, s.field_958.field_2103, 0, 16384);
            System.arraycopy(c.field_959.field_2103, 0, s.field_959.field_2103, 0, 16384);
            System.arraycopy(c.heightmap, 0, s.heightmap, 0, 256);
            s.decorated = c.decorated;
            s.field_955 = c.field_955;
            s.field_961 = c.field_961;
            s.field_967 = false;
            s.field_968 = c.field_968;
            s.field_969 = c.field_969;
            j.mods[i] = ((QsChunk) c).qs$mod();
            j.tiles[i] = c.tiles;
        }
        j.mask = loadedMask(j);
        j.skyDarken = j.level.field_202;
        j.slot = slot;
        slot.busy = true;
        INFLIGHT.add(j);
        slot.queue.add(j);
        return true;
    }

    private static int loadedMask(Job j) {
        QsChunkCache qc = (QsChunkCache) j.cache;
        int mask = 0;
        for (int dz = -1; dz <= 2; dz++) {
            for (int dx = -1; dx <= 2; dx++) {
                if (qc.qs$peek(j.x + dx, j.z + dz) != null) mask |= 1 << ((dz + 1) * 4 + dx + 1);
            }
        }
        return mask;
    }

    private static boolean nibbles(Chunk c) {
        return c.field_957 != null && c.field_958 != null && c.field_959 != null
                && c.field_957.field_2103.length == 16384 && c.field_958.field_2103.length == 16384 && c.field_959.field_2103.length == 16384;
    }

    /** Called once per frame by the chunk loader: applies finished decorations (at least one, then within the budget). */
    public static void drain(Level level, long budgetNs) {
        if (level != slotsLevel) {
            if (slotsLevel != null) reset();
            return;
        }
        if (INFLIGHT.isEmpty() && DEFERRED.isEmpty()) return;
        long t0 = System.nanoTime();
        int applied = 0;
        for (int i = 0; i < INFLIGHT.size(); ) {
            Job j = INFLIGHT.get(i);
            if (j.state == 0) {
                i++;
                continue;
            }
            if (applied > 0 && System.nanoTime() - t0 >= budgetNs) break;
            INFLIGHT.remove(i);
            j.slot.busy = false;
            QUEUED.remove(key(j.x, j.z));
            applied++;
            try {
                apply(j);
            } catch (Throwable t) {
                Quicksilver.LOGGER.warn("Applying decoration of chunk {},{} failed", j.x, j.z, t);
            }
        }
        startDeferred();
    }

    private static void startDeferred() {
        if (DEFERRED.isEmpty()) return;
        ArrayList<Job> kept = null;
        for (int i = 0; i < DEFERRED.size(); i++) {
            Job j = DEFERRED.get(i);
            boolean blocked = overlapsInflight(j);
            if (!blocked && kept != null) {
                for (Job o : kept) {
                    if (Math.abs(o.x - j.x) <= 1 && Math.abs(o.z - j.z) <= 1) {
                        blocked = true;
                        break;
                    }
                }
            }
            Slot slot = blocked ? null : freeSlot();
            if (slot != null) {
                if (!start(j, slot)) {
                    QUEUED.remove(key(j.x, j.z));
                    statDropped++;
                }
                continue;
            }
            if (kept == null) kept = new ArrayList<>();
            kept.add(j);
        }
        DEFERRED.clear();
        if (kept != null) DEFERRED.addAll(kept);
    }

    private static void apply(Job j) {
        QsChunkCache qc = (QsChunkCache) j.cache;
        boolean unchanged = true;
        for (int i = 0; i < 4; i++) {
            Chunk c = qc.qs$peek(j.x + (i & 1), j.z + (i >> 1));
            if (c == null) {
                statDropped++; // unloaded in the meantime; it is decorated again when it is next loaded
                return;
            }
            if (c != j.live[i] || ((QsChunk) c).qs$mod() != j.mods[i] || c.tiles != j.tiles[i]) unchanged = false;
        }
        if (j.live[0].decorated) {
            statDropped++;
            return;
        }
        int verify = VERIFY;
        if (j.state != 1) {
            statAborted++;
            vanilla(j);
            return;
        }
        if (!unchanged) {
            statFallback++;
            vanilla(j);
            return;
        }
        int seq = ++verifySeq;
        if (verify == 1 || (verify == 2 && seq % 32 == 0)) {
            // Instant liquid flow (springs) only runs into chunks that are loaded; the copy was decorated with the
            // loaded set of submit time, which is what vanilla would have seen. Only compare if it is unchanged.
            boolean sameLoaded = loadedMask(j) == j.mask;
            vanilla(j);
            if (sameLoaded) compare(j, "vanilla");
            else statVerifySkipped++;
            return;
        }
        replay(j);
        statAsync++;
        if (verify == 2 && seq % 8 == 0) compare(j, "replay");
    }

    /** Vanilla decoration on the game thread, at this point of the load order. */
    private static void vanilla(Job j) {
        sync++;
        try {
            j.cache.decorate(j.cache, j.x, j.z);
        } finally {
            sync--;
        }
        Relighter.onDecorated(j.level, j.x, j.z);
    }

    private static void replay(Job j) {
        PerfLog.decorateStart();
        try {
            Slot slot = j.slot;
            int[] d = slot.journal.data;
            int n = slot.journal.n;
            QsChunk[] q = new QsChunk[4];
            for (int i = 0; i < 4; i++) q[i] = (QsChunk) j.live[i];
            for (int k = 0; k < n; k++) q[d[k] >>> 27 & 3].qs$replay(d[k]);
            int[] t = slot.shadow.ticks;
            for (int k = 0, tn = slot.shadow.tickCount; k < tn; k += 5) j.level.method_216(t[k], t[k + 1], t[k + 2], t[k + 3], t[k + 4]);
            j.live[0].decorated = true; // what ServerChunkCache.decorate does around levelSource.decorate
            j.live[0].method_885();
        } finally {
            PerfLog.decorateEnd();
        }
        Relighter.onDecorated(j.level, j.x, j.z);
    }

    private static void compare(Job j, String what) {
        Slot slot = j.slot;
        boolean bad = false;
        boolean lavaWin = false;
        for (int i = 0; i < 4 && !lavaWin; i++) {
            byte[] lt = j.live[i].tiles, st = slot.chunks[i].tiles;
            for (int k = 0; k < 32768; k++) {
                int p = lt[k] & 255, q = st[k] & 255;
                if (p == 10 || p == 11 || q == 10 || q == 11) {
                    lavaWin = true;
                    break;
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            Chunk live = j.live[i], shadow = slot.chunks[i];
            int a = Arrays.mismatch(live.tiles, shadow.tiles);
            int b = Arrays.mismatch(live.field_957.field_2103, shadow.field_957.field_2103);
            int c = Arrays.mismatch(live.heightmap, shadow.heightmap);
            if (a >= 0 || b >= 0 || c >= 0) {
                bad = true;
                int count = 0, lava = 0;
                for (int k = 0; k < 32768; k++) {
                    if (live.tiles[k] != shadow.tiles[k]) {
                        count++;
                        int p = live.tiles[k] & 255, q = shadow.tiles[k] & 255;
                        if (p == 10 || p == 11 || p == 49 || q == 10 || q == 11 || q == 49) lava++;
                    }
                }
                Quicksilver.LOGGER.warn("[decorate-verify] {} MISMATCH decorating {},{} in chunk {},{}: {} blocks differ ({} involve lava, which flows randomly in vanilla too) (first index {} = world {},{},{}: real {} shadow {}), metadata first diff {}, heightmap first diff {}, lava in window: {}",
                        what, j.x, j.z, live.x, live.z, count, lava, a, a >= 0 ? live.x * 16 + (a >> 11) : 0, a >= 0 ? a & 127 : 0, a >= 0 ? live.z * 16 + (a >> 7 & 15) : 0, a >= 0 ? live.tiles[a] & 255 : -1, a >= 0 ? shadow.tiles[a] & 255 : -1, b, c, lavaWin);
            }
        }
        statVerified++;
        if (bad) {
            statMismatch++;
            if (!lavaWin) statMismatchNoLava++;
        }
        if (statVerified % 100 == 0) {
            Quicksilver.LOGGER.info("[decorate-verify] {}: {} decorations compared, {} mismatched ({} of them in windows without any lava; {} skipped because the loaded chunks changed; {} aborted, {} stale, {} dropped)",
                    what, statVerified, statMismatch, statMismatchNoLava, statVerifySkipped, statAborted, statFallback, statDropped);
        }
    }

    /** One line for the periodic perf log. */
    public static String stats() {
        return "async decorations " + statAsync + ", vanilla fallbacks " + (statAborted + statFallback) + " (aborted " + statAborted
                + ", stale " + statFallback + "), dropped " + statDropped + ", backlog " + backlog();
    }
}
