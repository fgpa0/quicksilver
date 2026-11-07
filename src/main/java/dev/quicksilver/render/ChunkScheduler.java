package dev.quicksilver.render;

import dev.quicksilver.QsConfig;
import dev.quicksilver.Quicksilver;
import dev.quicksilver.mixin.render.WorldRendererAccessor;
import dev.quicksilver.world.ChunkLoadManager;
import java.util.Arrays;
import java.util.List;
import net.minecraft.class_66;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Living;
import net.minecraft.level.Level;
import net.minecraft.level.chunk.ChunkCache;
import net.minecraft.level.source.LevelSource;

/**
 * Replacement for WorldRenderer.updateRenderers (method_1549).
 * <p>
 * Vanilla rebuilds every dirty section within 16 blocks plus two more per frame synchronously, which causes long
 * frames whenever many sections become dirty at once (world load, moving, sunrise/sunset relighting, explosions).
 * Here dirty sections are prioritised (visible + near first), snapshotted on the main thread, meshed on worker
 * threads, and uploaded within a per-frame time budget. Sections next to the player that are visible are still
 * rebuilt immediately so block edits never show holes.
 */
public final class ChunkScheduler {
    private ChunkScheduler() {}

    private static long frameStart;
    private static long frameSpent;
    private static int lastFrameId = -1;
    private static int frameId;
    private static long seq;
    private static class_66[] scratch = new class_66[256];
    private static float[] scratchKey = new float[256];
    static int orderLen, cursor, sortedSize, urgentScanned, sortGeneration = -1;
    private static final int BUCKETS = 1024;
    private static int[] bucketCount, sortBucket = new int[256];
    private static class_66[] sortTmp = new class_66[256];
    private static float[] sortTmpKey = new float[256];
    static long lastSortNs;
    private static double sortX, sortY, sortZ;
    private static boolean movedSinceSort, nearWaiting;
    /** Squared distance (blocks) within which a section waiting for a chunk is retried soon instead of at the next 2 s sort. */
    private static final float NEAR_WAIT_D2 = 48 * 48;

    // stats (shown on F3)
    public static int statUploads, statSubmitted, statSync, statDeferredBlocks, statFailures;
    public static int statQueued;
    public static long statEmptySkipped;
    public static long totalUploads, totalMainNs, totalScanNs, totalSnapshotNs, totalUploadNs;
    public static long statUploadNs, statSnapshotNs;
    public static double avgBuildMs;

    private static long lastFrameNs;
    /** Exponential moving average of the frame time in ms. */
    public static double avgFrameMs = 16;

    public static void newFrame() {
        frameId++;
        long now = System.nanoTime();
        if (lastFrameNs != 0) {
            double ms = (now - lastFrameNs) / 1e6;
            if (ms < 1000) avgFrameMs = avgFrameMs * 0.9 + ms * 0.1;
        }
        lastFrameNs = now;
    }

    public static double avgFrameMs() {
        return avgFrameMs;
    }

    /** A per-frame budget that scales up when frames are long anyway (keeps throughput at low FPS). */
    public static long budgetNs(float configuredMs, double fractionOfFrame, float capMs) {
        double ms = Math.max(configuredMs, Math.min(capMs, avgFrameMs * fractionOfFrame));
        return (long) (ms * 1_000_000L);
    }

    public static int renderRadiusChunks(Minecraft mc) {
        if (mc.worldRenderer == null) return 8;
        return Math.max(2, ((WorldRendererAccessor) mc.worldRenderer).qs$sizeX() / 2);
    }

    public static boolean threaded() {
        return QsConfig.threadedChunkBuilds;
    }

    public static void onReload() {
        Arrays.fill(scratch, 0, orderLen, null);
        orderLen = cursor = sortedSize = urgentScanned = 0;
        LightRebuilds.clear();
        MeshWorkers.newGeneration();
        dev.quicksilver.render.vbo.VboRenderer.reset();
    }

    /** A section got new coordinates (section grid re-centred on the player). */
    public static void sectionMoved() {
        movedSinceSort = true;
    }

    /** @return true when there is nothing more to do this frame (stops vanilla's "fill the frame" loop). */
    public static boolean update(WorldRenderer wr, Living viewer) {
        long t0 = System.nanoTime();
        try {
            return updateImpl(wr, viewer);
        } finally {
            totalMainNs += System.nanoTime() - t0;
        }
    }

    private static boolean updateImpl(WorldRenderer wr, Living viewer) {
        WorldRendererAccessor acc = (WorldRendererAccessor) wr;
        Level level = acc.qs$level();
        if (level == null || viewer == null) return true;
        if (frameId != lastFrameId) {
            lastFrameId = frameId;
            frameStart = System.nanoTime();
            frameSpent = 0;
            statUploads = statSubmitted = statSync = 0;
            statUploadNs = statSnapshotNs = 0;
        }
        long callStart = System.nanoTime();
        LightRebuilds.flush(wr);
        long budget = budgetNs(QsConfig.chunkUploadBudgetMs, 0.12, 10f);
        boolean threaded = threaded();
        if (threaded) MeshWorkers.ensureStarted();

        // 1. integrate finished meshes
        int generation = MeshWorkers.generation();
        if (threaded) {
            BuildResult r;
            while ((r = MeshWorkers.RESULTS.peek()) != null) {
                if (statUploads > 0 && spent(callStart) >= budget) break;
                MeshWorkers.RESULTS.poll();
                apply(wr, r, generation);
            }
        }

        // 2. schedule dirty sections
        long callStartScan = System.nanoTime();
        List<class_66> dirty = acc.qs$dirty();
        int n = dirty.size();
        boolean moreWork = false;
        if (n > 0) {
            // Sorting tens of thousands of dirty sections every frame (32 chunks) cost several ms per frame. The
            // priority order is now rebuilt only every 2 s or when the camera moved; in between, sections are
            // taken from the cached order (cursor), and new urgent ones (block edits nearby) are checked separately.
            long nowNs = callStartScan;
            double vx = viewer.x, vy = viewer.y, vz = viewer.z;
            double mdx = vx - sortX, mdy = vy - sortY, mdz = vz - sortZ;
            // Also resort soon (100 ms) when sections were repositioned since the sort (their keys are from where they
            // were before: right after a world load or dimension change that is every section around the player) or
            // when a section near the player was skipped because a neighbouring chunk wasn't ready yet.
            boolean resort = nowNs - lastSortNs > 2_000_000_000L || mdx * mdx + mdy * mdy + mdz * mdz > 64
                    || sortGeneration != MeshWorkers.generation() || n < sortedSize
                    || ((movedSinceSort || nearWaiting) && nowNs - lastSortNs > 100_000_000L);
            if (!resort && cursor >= orderLen && n > sortedSize) {
                // cached order used up: queue only what was dirtied since (no rescan of the whole list)
                if (scratch.length < n - sortedSize) {
                    scratch = new class_66[(n - sortedSize) * 2];
                    scratchKey = new float[(n - sortedSize) * 2];
                }
                Arrays.fill(scratch, 0, orderLen, null);
                int m = 0;
                for (int i = sortedSize; i < n; i++) {
                    class_66 s = dirty.get(i);
                    if (s == null || !s.field_249) continue;
                    float key = s.method_299(viewer);
                    if (!s.field_243) key *= 4f;
                    if (!((QsSection) s).qs$initialized()) key *= 0.5f;
                    scratch[m] = s;
                    scratchKey[m] = key;
                    m++;
                }
                sortByKey(m);
                orderLen = m;
                cursor = 0;
                sortedSize = n;
                urgentScanned = n;
            }
            if (resort) {
                // drop everything that is no longer dirty from the list
                int w = 0;
                for (int i = 0; i < n; i++) {
                    class_66 s = dirty.get(i);
                    if (s != null && s.field_249) dirty.set(w++, s);
                }
                for (int i = n - 1; i >= w; i--) dirty.remove(i);
                n = w;
                if (scratch.length < n) {
                    scratch = new class_66[n * 2];
                    scratchKey = new float[n * 2];
                }
                Arrays.fill(scratch, 0, orderLen, null);
                int m = 0;
                for (int i = 0; i < n; i++) {
                    class_66 s = dirty.get(i);
                    float key = s.method_299(viewer);
                    if (!s.field_243) key *= 4f; // outside the frustum
                    if (!((QsSection) s).qs$initialized()) key *= 0.5f; // never built yet -> visible hole
                    scratch[m] = s;
                    scratchKey[m] = key;
                    m++;
                }
                sortByKey(m);
                orderLen = m;
                cursor = 0;
                sortedSize = n;
                lastSortNs = nowNs;
                sortX = vx;
                sortY = vy;
                sortZ = vz;
                sortGeneration = MeshWorkers.generation();
                urgentScanned = n;
                movedSinceSort = nearWaiting = false;
            }
            totalScanNs += System.nanoTime() - callStartScan;
            boolean[] safe = threaded ? BlockSafety.table() : null;
            boolean vbo = dev.quicksilver.render.vbo.VboRenderer.active();
            int maxInFlight = MeshWorkers.threadCount() * 3 + 2;
            int urgentLeft = 4;
            float urgentDist = QsConfig.urgentRebuildDistance;
            float urgentDist2 = urgentDist * urgentDist;
            boolean checkAvail = ChunkLoadManager.active(level);
            LevelSource cache = level.getCache();
            // newly dirtied since the last sort: only urgent ones (near and visible) are handled before the next sort
            for (int i = Math.max(urgentScanned, sortedSize); i < n && urgentLeft > 0; i++) {
                urgentScanned = i + 1; // each new entry is looked at once
                class_66 s = dirty.get(i);
                if (s == null || !s.field_249 || !s.field_243) continue;
                if (s.method_299(viewer) > urgentDist2) continue;
                if (checkAvail && !available(cache, s)) continue;
                QsSection qs = (QsSection) s;
                if (qs.qs$inFlight()) qs.qs$bumpEpoch();
                syncBuild(s, qs, level, safe, generation, vbo);
                urgentLeft--;
            }
            while (cursor < orderLen) {
                class_66 s = scratch[cursor];
                if (s == null || !s.field_249) {
                    cursor++;
                    continue;
                }
                QsSection qs = (QsSection) s;
                if (checkAvail && !available(cache, s)) {
                    if (s.method_299(viewer) < NEAR_WAIT_D2) nearWaiting = true;
                    cursor++; // retried at the next sort
                    continue;
                }
                float d2 = s.method_299(viewer);
                boolean urgent = urgentLeft > 0 && s.field_243 && d2 <= urgentDist2;
                if (urgent || qs.qs$forceSync() || !threaded) {
                    if (!urgent && statSync > 0 && spent(callStart) >= budget) {
                        moreWork = true;
                        break;
                    }
                    if (qs.qs$inFlight()) qs.qs$bumpEpoch(); // discard the stale async build
                    syncBuild(s, qs, level, safe, generation, vbo);
                    if (urgent) urgentLeft--;
                    cursor++;
                    continue;
                }
                if (qs.qs$inFlight()) {
                    cursor++; // resubmitted after the running build lands (next sort)
                    continue;
                }
                if (MeshWorkers.QUEUE.size() + MeshWorkers.BUSY.get() >= maxInFlight) {
                    moreWork = true;
                    break;
                }
                long t0 = System.nanoTime();
                if (SectionSnapshot.isAir(level, s.field_231, s.field_232, s.field_233)) {
                    // nothing to mesh: finish it right here (no snapshot, worker or GPU upload)
                    s.field_249 = false;
                    BuildTask et = new BuildTask(s, qs.qs$epoch(), generation,
                            SectionSnapshot.emptySection(s.field_231, s.field_232, s.field_233), safe, 0, seq++);
                    et.vbo = vbo;
                    et.ext = vbo && dev.quicksilver.shader.ShaderBridge.extended();
                    MeshUploader.upload(new BuildResult(et));
                    totalUploads++;
                    statEmptySkipped++;
                    totalSnapshotNs += System.nanoTime() - t0;
                    cursor++;
                    continue;
                }
                SectionSnapshot snap = new SectionSnapshot(level, s.field_231, s.field_232, s.field_233);
                long sn = System.nanoTime() - t0;
                statSnapshotNs += sn;
                totalSnapshotNs += sn;
                qs.qs$setInFlight(true);
                s.field_249 = false;
                BuildTask task = new BuildTask(s, qs.qs$epoch(), generation, snap, safe, scratchKey[cursor], seq++);
                task.vbo = vbo;
                markFormat(task);
                MeshWorkers.QUEUE.add(task);
                statSubmitted++;
                cursor++;
                if (spent(callStart) >= budget) {
                    moreWork = true;
                    break;
                }
            }
        }
        statQueued = dirty.size() + MeshWorkers.QUEUE.size() + MeshWorkers.BUSY.get();
        frameSpent += System.nanoTime() - callStart;
        if (moreWork) return true;
        return MeshWorkers.RESULTS.isEmpty() || frameSpent >= budget;
    }

    /** Extended (shader) format and smooth lighting flag for a VBO build. */
    private static void markFormat(BuildTask task) {
        task.ext = task.vbo && dev.quicksilver.shader.ShaderBridge.extended();
        task.sky = task.vbo && !task.ext && dev.quicksilver.render.vbo.TerrainShader.enabled();
        if (task.ext || task.sky) {
            net.minecraft.client.Minecraft mc = dev.quicksilver.client.RenderDistance.MinecraftHolder.get();
            task.smooth = mc == null || mc.options.ao;
        }
    }

    private static void syncBuild(class_66 s, QsSection qs, Level level, boolean[] safe, int generation, boolean vbo) {
        if (vbo) {
            buildOnMainThread(s, qs, level, safe, generation);
        } else {
            s.method_296();
            if (QsConfig.caveCulling) qs.qs$setVisGraph(CaveCuller.graphFromWorld(level, s.field_231, s.field_232, s.field_233));
        }
        s.field_249 = false;
        qs.qs$setForceSync(false);
        statSync++;
    }

    private static final MeshCapture MAIN_BUILD_CAPTURE = new MeshCapture();

    /** Synchronous build through the same snapshot mesher (VBO mode has no display lists to fall back to). */
    private static void buildOnMainThread(class_66 s, QsSection qs, Level level, boolean[] safe, int generation) {
        SectionSnapshot snap = new SectionSnapshot(level, s.field_231, s.field_232, s.field_233);
        BuildTask task = new BuildTask(s, qs.qs$epoch(), generation, snap, safe, 0, seq++);
        task.vbo = true;
        markFormat(task);
        BuildResult r;
        try {
            r = SectionMesher.build(task, net.minecraft.client.render.Tessellator.INSTANCE, MAIN_BUILD_CAPTURE);
        } catch (Throwable t) {
            statFailures++;
            if (statFailures < 10) Quicksilver.LOGGER.warn("Main-thread chunk build failed", t);
            qs.qs$setForceSync(false);
            net.minecraft.client.render.Tessellator tess = net.minecraft.client.render.Tessellator.INSTANCE;
            ((QsTessellator) tess).qs$setCapture(MAIN_BUILD_CAPTURE);
            try {
                tess.draw();
            } catch (Throwable ignored) {
            }
            ((QsTessellator) tess).qs$setCapture(null);
            tess.setOffset(0, 0, 0);
            return;
        }
        MeshUploader.upload(r);
    }

    private static long spent(long callStart) {
        return frameSpent + (System.nanoTime() - callStart);
    }

    private static void apply(WorldRenderer wr, BuildResult r, int generation) {
        class_66 s = r.task.section;
        QsSection qs = (QsSection) s;
        if (r.task.generation != generation || r.task.epoch != qs.qs$epoch()) return;
        qs.qs$setInFlight(false);
        if (r.error != null) {
            statFailures++;
            if (statFailures < 10) Quicksilver.LOGGER.warn("Async chunk build failed, rebuilding on the main thread", r.error);
            qs.qs$setForceSync(true);
            markDirty(wr, s);
            return;
        }
        long t0 = System.nanoTime();
        MeshUploader.upload(r);
        long un = System.nanoTime() - t0;
        statUploadNs += un;
        totalUploadNs += un;
        statUploads++;
        totalUploads++;
        avgBuildMs = avgBuildMs * 0.95 + (r.buildNs / 1e6) * 0.05;
    }

    private static void markDirty(WorldRenderer wr, class_66 s) {
        if (!s.field_249) {
            ((WorldRendererAccessor) wr).qs$dirty().add(s);
            s.method_305();
        }
    }

    /** True if every chunk the section's mesh depends on is loaded (or outside the cache window). */
    static boolean available(LevelSource cache, class_66 s) {
        int minX = (s.field_231 - 1) >> 4, maxX = (s.field_231 + 16) >> 4;
        int minZ = (s.field_233 - 1) >> 4, maxZ = (s.field_233 + 16) >> 4;
        ChunkCache cc = cache instanceof ChunkCache c ? c : null;
        // a decoration still running in the background will change blocks in these chunks
        if (dev.quicksilver.world.AsyncDecoration.pendingIn(minX, maxX, minZ, maxZ)) return false;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!cache.isChunkLoaded(x, z) && (cc == null || cc.method_1243(x, z))) return false;
            }
        }
        return true;
    }

    private static void sortByKey(int m) {
        // insertion sort for small n, otherwise index sort
        if (m < 32) {
            for (int i = 1; i < m; i++) {
                float k = scratchKey[i];
                class_66 v = scratch[i];
                int j = i - 1;
                while (j >= 0 && scratchKey[j] > k) {
                    scratchKey[j + 1] = scratchKey[j];
                    scratch[j + 1] = scratch[j];
                    j--;
                }
                scratchKey[j + 1] = k;
                scratch[j + 1] = v;
            }
            return;
        }
        // Bucket (counting) sort by distance in 4-block steps: linear time, no allocation. Exact order inside a
        // bucket doesn't matter for scheduling.
        if (bucketCount == null) bucketCount = new int[BUCKETS + 1];
        else Arrays.fill(bucketCount, 0);
        if (sortTmp.length < m) {
            sortTmp = new class_66[m * 2];
            sortTmpKey = new float[m * 2];
            sortBucket = new int[m * 2];
        }
        for (int i = 0; i < m; i++) {
            int bk = (int) (Math.sqrt(scratchKey[i]) * 0.25);
            if (bk >= BUCKETS) bk = BUCKETS - 1;
            sortBucket[i] = bk;
            bucketCount[bk + 1]++;
        }
        for (int i = 0; i < BUCKETS; i++) bucketCount[i + 1] += bucketCount[i];
        for (int i = 0; i < m; i++) {
            int pos = bucketCount[sortBucket[i]]++;
            sortTmp[pos] = scratch[i];
            sortTmpKey[pos] = scratchKey[i];
        }
        System.arraycopy(sortTmp, 0, scratch, 0, m);
        System.arraycopy(sortTmpKey, 0, scratchKey, 0, m);
        Arrays.fill(sortTmp, 0, m, null);
    }
}
