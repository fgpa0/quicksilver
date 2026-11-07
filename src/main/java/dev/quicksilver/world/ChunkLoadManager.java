package dev.quicksilver.world;

import dev.quicksilver.QsConfig;
import dev.quicksilver.io.RegionIO;
import dev.quicksilver.render.ChunkScheduler;
import java.io.File;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Living;
import net.minecraft.level.Level;
import dev.quicksilver.mixin.world.ServerChunkCacheAccessor;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.player.PlayerBase;
import net.minecraft.level.chunk.Chunk;
import net.minecraft.level.chunk.ChunkCache;
import net.minecraft.level.chunk.ServerChunkCache;
import net.minecraft.level.source.LevelSource;
import net.minecraft.util.maths.MathHelper;
import net.minecraft.util.maths.Vec2i;

/**
 * Singleplayer chunk loading done right: instead of the tick thread synchronously loading/generating the whole
 * 19x19 random-tick area (and the renderer loading whatever it touches), chunks are loaded nearest-first with a
 * per-frame time budget. Chunks on disk are prefetched (read + inflated + parsed) on background threads.
 */
public final class ChunkLoadManager {
    private ChunkLoadManager() {}

    /** Chunk offsets within radius 16, sorted by distance. Encoded as (dx, dz) pairs. */
    private static final int[] OFFSETS;
    private static final int MAX_R = 50;

    static {
        int n = (2 * MAX_R + 1) * (2 * MAX_R + 1);
        long[] tmp = new long[n];
        int i = 0;
        for (int dx = -MAX_R; dx <= MAX_R; dx++) {
            for (int dz = -MAX_R; dz <= MAX_R; dz++) {
                long d = dx * dx + dz * dz;
                tmp[i++] = (d << 32) | ((long) (dx + 64) << 16) | (dz + 64);
            }
        }
        Arrays.sort(tmp);
        OFFSETS = new int[n * 2];
        for (int j = 0; j < n; j++) {
            OFFSETS[j * 2] = (int) ((tmp[j] >> 16) & 0xFFFF) - 64;
            OFFSETS[j * 2 + 1] = (int) (tmp[j] & 0xFFFF) - 64;
        }
    }

    public static int loadedThisFrame;
    public static boolean inLoader;
    public static int syncLoadsOutsideLoader;
    private static final java.util.Map<String, Integer> TRACES = new java.util.HashMap<>();
    private static final boolean TRACE = Boolean.getBoolean("quicksilver.traceLoads");

    public static void traceLoad(int x, int z) {
        if (inLoader) return;
        syncLoadsOutsideLoader++;
        if (!TRACE) return;
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < Math.min(st.length, 16); i++) sb.append(st[i].getClassName().replace("net.minecraft.", "")).append('.').append(st[i].getMethodName()).append(" < ");
        String k = sb.toString();
        Integer c = TRACES.merge(k, 1, Integer::sum);
        if (c == 1 || c == 10 || c == 100) dev.quicksilver.Quicksilver.LOGGER.info("sync chunk load #{} via {}", c, k);
    }
    public static int missingInRadius;
    public static long lastFrameNs;
    private static int prefetchCx = Integer.MIN_VALUE, prefetchCz = Integer.MIN_VALUE;
    private static File prefetchDir;
    private static int prefetchIndex;

    public static boolean active(Level level) {
        if (!QsConfig.progressiveChunkLoading || level == null) return false;
        LevelSource c = level.getCache();
        return c instanceof ServerChunkCache || c instanceof ChunkCache;
    }

    /** Radius (in chunks) that must be loaded around the player. */
    public static int radius(Minecraft mc) {
        int render = ChunkScheduler.renderRadiusChunks(mc);
        return Math.min(49, Math.max(10, render + 1));
    }

    private static long lastLog;
    private static final boolean DEBUG_LOG = Boolean.getBoolean("quicksilver.debugLog");
    public static int statLoadedNow;

    /** Called once per frame on the main thread, before lighting and rendering. */
    public static void frame(Minecraft mc) {
        if (DEBUG_LOG && System.currentTimeMillis() - lastLog > 5000) {
            lastLog = System.currentTimeMillis();
            dev.quicksilver.Quicksilver.LOGGER.info("[stats] loaded {} (sync outside loader {}), missing {}, gen async {} used {} pending {}, io prefetch hits {} sync reads {} writes {}, unloaded {}, uploads {} sync {} fail {}",
                    statLoadedNow, syncLoadsOutsideLoader, missingInRadius, AsyncWorldGen.statGenerated, AsyncWorldGen.statUsed, AsyncWorldGen.pending(),
                    RegionIO.statPrefetchHits.get(), RegionIO.statSyncReads.get(), RegionIO.statWrites.get(), unloadedTotal,
                    ChunkScheduler.statUploads, ChunkScheduler.statSync, ChunkScheduler.statFailures);
            dev.quicksilver.Quicksilver.LOGGER.info("[stats] vbo regions {} draws {} sections {} verts {} mem {}MB, cull {}/{}",
                    dev.quicksilver.render.vbo.VboRenderer.statRegions, dev.quicksilver.render.vbo.VboRenderer.statDraws,
                    dev.quicksilver.render.vbo.VboRenderer.statSections, dev.quicksilver.render.vbo.VboRenderer.statVertices,
                    dev.quicksilver.render.vbo.VboRenderer.memoryBytes() >> 20, dev.quicksilver.render.CaveCuller.statVisible, dev.quicksilver.render.CaveCuller.statTotal);
        }
        DecorationWindow.close(); // only reached if a decoration threw; normally already closed
        loadedThisFrame = 0;
        Level level = mc.level;
        long drainStart = System.nanoTime();
        AsyncDecoration.drain(level, ChunkScheduler.budgetNs(QsConfig.chunkLoadBudgetMs, 0.15, 12f));
        if (!active(level)) return;
        Living p = mc.viewEntity != null ? mc.viewEntity : mc.player;
        if (p == null) return;
        LevelSource cache = level.getCache();
        int cx = MathHelper.floor(p.x) >> 4;
        int cz = MathHelper.floor(p.z) >> 4;
        // the (old style) cache window is centred on the render entity; keep it consistent with the renderer
        if (cache instanceof ChunkCache cc) cc.method_1242(cx, cz);
        int r = radius(mc);
        int r2 = r * r + r; // round-ish disc, slightly generous
        long start = drainStart; // decorations applied above count against the same budget
        long budget = ChunkScheduler.budgetNs(QsConfig.chunkLoadBudgetMs, 0.15, 12f);
        int missing = 0;
        File dir = QsConfig.chunkPrefetch ? RegionIO.lastReadDir : null;
        LevelSource generator = cache instanceof ServerChunkCache ? ((ServerChunkCacheAccessor) cache).qs$generator() : null;
        boolean asyncGen = dir != null && AsyncWorldGen.supported(level, generator);
        // Everything around the player is loaded and nothing moved: rescanning ~4000 chunk slots every frame cost a third
        // of the main thread at 700+ FPS. Recheck every 15 ms, or at once when the player changes chunk.
        // Far chunks (beyond FAR_D2) earn main-thread time at farChunkLoadShare of wall time, capped at about one
        // chunk's worth: crossing a chunk border at render distance 32 needs ~65 new chunks, which the full budget used
        // to load in a burst of ~20 consecutive 5-6 ms frames. Paced, they arrive one every few frames instead.
        boolean pace = QsConfig.farChunkLoadShare < 0.999f;
        if (pace) {
            // Credit per frame from the smoothed frame time, not this frame's duration: otherwise a frame that is
            // already slow (GPU wait, tick) earns the most credit and then also gets a chunk load on top.
            double frameMs = Math.min(50.0, ChunkScheduler.avgFrameMs());
            farCreditNs = Math.min(FAR_CREDIT_CAP_NS, farCreditNs + (long) (frameMs * 1e6 * Math.max(0.05f, QsConfig.farChunkLoadShare)));
        } else {
            farCreditNs = FAR_CREDIT_CAP_NS;
        }
        boolean skipScan = level == scanLevel && cx == scanCx && cz == scanCz && r == scanR && scanComplete
                && start - scanNs < 15_000_000L;
        if (!skipScan) {
            for (int i = 0; i < OFFSETS.length; i += 2) {
                int dx = OFFSETS[i], dz = OFFSETS[i + 1];
                int d2 = dx * dx + dz * dz;
                if (Math.max(Math.abs(dx), Math.abs(dz)) > r) continue; // square: the section grid is square
                int x = cx + dx, z = cz + dz;
                if (cache.isChunkLoaded(x, z)) continue;
                missing++;
                if (missing > 384) break; // far enough ahead; keep per-frame scanning cheap at large distances
                boolean overBudget = loadedThisFrame > 0 && System.nanoTime() - start >= budget;
                boolean far = pace && d2 > FAR_D2;
                if (far && farCreditNs <= 0) {
                    overBudget = true; // keep scanning only to queue background reads/generation
                    statFarDeferred++;
                }
                if (overBudget && dir == null) break;
                // Decide whether loading this chunk now is cheap, or whether to let background threads prepare it.
                boolean loadNow;
                if (d2 <= 5 || dir == null) {
                    loadNow = true; // right next to the player: needed immediately
                } else if (AsyncWorldGen.isReady(level, x, z)) {
                    loadNow = true;
                } else {
                    RegionIO.State st = RegionIO.state(dir, x, z);
                    switch (st) {
                        case READY, PENDING_WRITE -> loadNow = true;
                        case ABSENT -> {
                            if (asyncGen) {
                                if (!AsyncWorldGen.isPending(level, x, z)) AsyncWorldGen.request(level, x, z);
                                loadNow = false;
                            } else {
                                loadNow = true;
                            }
                        }
                        case IN_FLIGHT -> loadNow = false;
                        default -> {
                            if (RegionIO.inFlight() < 24) RegionIO.prefetch(dir, x, z);
                            loadNow = false;
                        }
                    }
                }
                if (loadNow && !overBudget && AsyncDecoration.backlog() < 12) {
                    long t0 = far ? System.nanoTime() : 0;
                    inLoader = true;
                    try {
                        level.getChunkFromCache(x, z);
                    } finally {
                        inLoader = false;
                    }
                    if (far) farCreditNs -= System.nanoTime() - t0;
                    loadedThisFrame++;
                    dev.quicksilver.client.PerfLog.chunksLoadedTotal++;
                    statLoadedNow++;
                }
            }
            scanLevel = level;
            scanCx = cx;
            scanCz = cz;
            scanR = r;
            scanNs = start;
            scanComplete = missing == 0;
        }
        if (asyncGen) AsyncWorldGen.trim(cx, cz, r + 4);
        missingInRadius = missing - loadedThisFrame;
        lastFrameNs = System.nanoTime() - start;
        if (QsConfig.chunkPrefetch) schedulePrefetch(cache, cx, cz, r);
        if (QsConfig.unloadDistantChunks && cache instanceof ServerChunkCache) unloadFar(level, (ServerChunkCacheAccessor) cache, r);
    }

    /** Squared distance (in chunks) beyond which loading is paced by farChunkLoadShare. */
    private static final int FAR_D2 = 8 * 8;
    private static final long FAR_CREDIT_CAP_NS = 1_500_000L;
    private static long farCreditNs;
    public static int statFarDeferred;

    private static Level scanLevel;
    private static int scanCx, scanCz, scanR;
    private static long scanNs;
    private static boolean scanComplete;

    private static int unloadCursor;
    public static int unloadedTotal;

    /**
     * Singleplayer never unloads chunks in vanilla Beta: everything you ever visited stays in memory and keeps
     * ticking (entities, furnaces...). Unload chunks far from every player, a couple per frame, saving them
     * through the normal (now asynchronous) path.
     */
    private static void unloadFar(Level level, ServerChunkCacheAccessor cache, int r) {
        List<Chunk> loaded = cache.qs$loaded();
        int n = loaded.size();
        if (n == 0) return;
        int keep = r + 3;
        List<?> players = level.players;
        if (players.isEmpty()) return;
        int scan = Math.min(n, 64);
        int unloaded = 0;
        long start = System.nanoTime();
        for (int i = 0; i < scan && unloaded < 4; i++) {
            if (unloadCursor >= loaded.size()) unloadCursor = 0;
            Chunk c = loaded.get(unloadCursor);
            boolean far = true;
            for (Object o : players) {
                PlayerBase p = (PlayerBase) o;
                int px = MathHelper.floor(p.x) >> 4, pz = MathHelper.floor(p.z) >> 4;
                if (Math.max(Math.abs(c.x - px), Math.abs(c.z - pz)) <= keep) {
                    far = false;
                    break;
                }
            }
            if (!far) {
                unloadCursor++;
                continue;
            }
            ((QsChunkCache) cache).qs$invalidateLast();
            c.method_883();
            cache.qs$save(c);
            cache.qs$saveExtra(c);
            cache.qs$map().remove(Vec2i.hash(c.x, c.z));
            loaded.remove(unloadCursor);
            unloaded++;
            unloadedTotal++;
            if (System.nanoTime() - start > 1_000_000L) break;
        }
    }

    private static void schedulePrefetch(LevelSource cache, int cx, int cz, int r) {
        File dir = RegionIO.lastReadDir;
        if (dir == null) return;
        if (cx != prefetchCx || cz != prefetchCz || dir != prefetchDir) {
            prefetchCx = cx;
            prefetchCz = cz;
            prefetchDir = dir;
            prefetchIndex = 0;
            RegionIO.trim(cx, cz, r + 4, 384);
        }
        int pr = r + 2;
        int pr2 = pr * pr + pr;
        int queued = 0;
        while (prefetchIndex < OFFSETS.length && RegionIO.inFlight() < 12 && queued < 16) {
            int dx = OFFSETS[prefetchIndex], dz = OFFSETS[prefetchIndex + 1];
            prefetchIndex += 2;
            if (Math.max(Math.abs(dx), Math.abs(dz)) > pr) continue;
            int x = cx + dx, z = cz + dz;
            if (cache.isChunkLoaded(x, z) || RegionIO.isKnown(dir, x, z)) continue;
            RegionIO.prefetch(dir, x, z);
            queued++;
        }
    }

    private static boolean neighbourhoodLoaded(LevelSource cache, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!cache.isChunkLoaded(x + dx, z + dz)) return false;
            }
        }
        return true;
    }

    /** Removes chunk positions that are not loaded from a vanilla "active chunks" set (random ticks, mob spawning). */
    public static void filterLoaded(Level level, Set<?> chunkSet) {
        if (!active(level)) return;
        LevelSource cache = level.getCache();
        for (Iterator<?> it = chunkSet.iterator(); it.hasNext(); ) {
            Vec2i v = (Vec2i) it.next();
            // block ticks (grass spread, fluids, spawning checks) reach into neighbouring chunks
            if (!neighbourhoodLoaded(cache, v.x, v.z)) it.remove();
        }
    }
}
