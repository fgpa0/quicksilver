package dev.quicksilver.client;

import dev.quicksilver.QsConfig;
import dev.quicksilver.Quicksilver;
import dev.quicksilver.render.ChunkScheduler;
import dev.quicksilver.world.LightBudget;
import dev.quicksilver.world.Relighter;

/**
 * Every 10 s (when "F3 Debug Info" is on) writes a line to the game log with where main-thread time went, per frame on
 * average, plus the worst frame. Lets frame-time problems on real hardware be diagnosed from latest.log.
 */
public final class PerfLog {
    private PerfLog() {}

    public static long textureNs, tickNs, swapNs, loaderNs, frames, worstNs;
    private static long lastFrame, periodStart;
    private static long bTick, bTex, bLoad, bSwap, bCull, bFrustum, bDraw;
    private static long sched0, scan0, snap0, upload0, light0, relight0, ups0;
    private static long tickStart, swapStart;
    // per-frame snapshot of the cumulative counters, for the hitch report
    private static long fTick, fTex, fLoad, fLight, fRelight, fSched, fUp, fSwap, fGrowNs, fGrow, fUploads, fLoaded;
    private static int hitchesLogged;
    private static long hitchWindow;
    public static long chunksLoadedTotal;

    // ---- decoration timing (game thread; decoration can nest when a feature reaches into an unloaded chunk) ----
    public static long decorateNs, decorations;
    private static int decorateDepth;
    private static long decorateStartNs;

    public static void decorateStart() {
        if (!Quicksilver.isClientThread()) return;
        if (decorateDepth++ == 0) decorateStartNs = System.nanoTime();
        decorations++;
    }

    public static void decorateEnd() {
        if (!Quicksilver.isClientThread() || decorateDepth == 0) return;
        if (--decorateDepth == 0) decorateNs += System.nanoTime() - decorateStartNs;
    }

    public static long renderNs;
    private static long renderStartNs;

    public static void renderStart() {
        renderStartNs = System.nanoTime();
    }

    public static void renderEnd() {
        if (renderStartNs != 0) renderNs += System.nanoTime() - renderStartNs;
    }

    // ---- per-frame breakdown, to explain the slowest 1% of frames of each 10 s window ----
    private static final int CAP = 1 << 15;
    private static final float[] F_TOTAL = new float[CAP], F_LOAD = new float[CAP], F_DECO = new float[CAP],
            F_LIGHT = new float[CAP], F_RELIGHT = new float[CAP], F_TICK = new float[CAP], F_SCHED = new float[CAP],
            F_RENDER = new float[CAP], F_VBO = new float[CAP], F_FRUSTUM = new float[CAP], F_CULL = new float[CAP], F_SWAP = new float[CAP],
            F_VBUILD = new float[CAP], F_VUP = new float[CAP], F_VFADE = new float[CAP];
    private static final short[] F_CHUNKS = new short[CAP], F_DECOS = new short[CAP];
    private static int fCount;
    private static long fDeco, fDecos, fRender, fVbo, fFrustum, fCull, fVBuild, fVUp, fVFade;

    public static void tickStart() {
        tickStart = System.nanoTime();
    }

    public static void tickEnd() {
        if (tickStart != 0) tickNs += System.nanoTime() - tickStart;
    }

    public static void swapStart() {
        swapStart = System.nanoTime();
    }

    public static void swapEnd() {
        long now = System.nanoTime();
        if (swapStart != 0) swapNs += now - swapStart;
        if (lastFrame != 0) {
            long ft = now - lastFrame;
            if (ft > worstNs) worstNs = ft;
            if (ft > 40_000_000L && QsConfig.debugInfo) hitch(ft, now);
            if (QsConfig.debugInfo && fCount < CAP) {
                int i = fCount++;
                F_TOTAL[i] = ft / 1e6f;
                F_LOAD[i] = (loaderNs - fLoad) / 1e6f;
                F_DECO[i] = (decorateNs - fDeco) / 1e6f;
                F_LIGHT[i] = (LightBudget.totalNs - fLight) / 1e6f;
                F_RELIGHT[i] = (Relighter.totalNs - fRelight) / 1e6f;
                F_TICK[i] = (tickNs - fTick) / 1e6f;
                F_SCHED[i] = (ChunkScheduler.totalMainNs - fSched) / 1e6f;
                F_CHUNKS[i] = (short) Math.min(Short.MAX_VALUE, chunksLoadedTotal - fLoaded);
                F_DECOS[i] = (short) Math.min(Short.MAX_VALUE, decorations - fDecos);
                F_RENDER[i] = (renderNs - fRender) / 1e6f;
                F_VBO[i] = (dev.quicksilver.render.vbo.VboRenderer.totalNs - fVbo) / 1e6f;
                F_FRUSTUM[i] = (dev.quicksilver.render.FastFrustum.totalNs - fFrustum) / 1e6f;
                F_VBUILD[i] = (dev.quicksilver.render.vbo.VboRenderer.buildNs - fVBuild) / 1e6f;
                F_VUP[i] = (dev.quicksilver.render.vbo.VboRenderer.cmdUploadNs - fVUp) / 1e6f;
                F_VFADE[i] = (dev.quicksilver.render.vbo.VboRenderer.fadeNs - fVFade) / 1e6f;
                F_CULL[i] = (dev.quicksilver.render.CaveCuller.totalNs - fCull) / 1e6f;
                F_SWAP[i] = (swapNs - fSwap) / 1e6f;
            }
        }
        fDeco = decorateNs;
        fDecos = decorations;
        fRender = renderNs;
        fVbo = dev.quicksilver.render.vbo.VboRenderer.totalNs;
        fVBuild = dev.quicksilver.render.vbo.VboRenderer.buildNs;
        fVUp = dev.quicksilver.render.vbo.VboRenderer.cmdUploadNs;
        fVFade = dev.quicksilver.render.vbo.VboRenderer.fadeNs;
        fFrustum = dev.quicksilver.render.FastFrustum.totalNs;
        fCull = dev.quicksilver.render.CaveCuller.totalNs;
        fTick = tickNs;
        fTex = textureNs;
        fLoad = loaderNs;
        fLight = LightBudget.totalNs;
        fRelight = Relighter.totalNs;
        fSched = ChunkScheduler.totalMainNs;
        fUp = ChunkScheduler.totalUploadNs;
        fSwap = swapNs;
        fGrowNs = dev.quicksilver.render.vbo.VboRegion.growNs;
        fGrow = dev.quicksilver.render.vbo.VboRegion.growCount;
        fUploads = ChunkScheduler.totalUploads;
        fLoaded = chunksLoadedTotal;
        lastFrame = now;
        frames++;
        if (periodStart == 0) {
            reset(now);
            return;
        }
        if (now - periodStart >= 10_000_000_000L) {
            if (QsConfig.debugInfo && frames > 0) {
                double f = frames;
                double sec = (now - periodStart) / 1e9;
                Quicksilver.LOGGER.info(String.format(
                        "[perf] %.0f fps, worst %.1fms | per frame ms: tick %.2f (animated textures %.2f), chunk load %.2f, light %.2f, relight %.2f, mesh sched %.2f (scan %.2f snapshot %.2f upload %.2f), swap/gpu wait %.2f, cave cull %.2f, frustum %.2f, terrain draw calls %.2f | rebuilds %.0f/s, dirty %d",
                        f / sec, worstNs / 1e6, (tickNs - bTick) / 1e6 / f, (textureNs - bTex) / 1e6 / f, (loaderNs - bLoad) / 1e6 / f, (LightBudget.totalNs - light0) / 1e6 / f,
                        (Relighter.totalNs - relight0) / 1e6 / f, (ChunkScheduler.totalMainNs - sched0) / 1e6 / f,
                        (ChunkScheduler.totalScanNs - scan0) / 1e6 / f, (ChunkScheduler.totalSnapshotNs - snap0) / 1e6 / f,
                        (ChunkScheduler.totalUploadNs - upload0) / 1e6 / f, (swapNs - bSwap) / 1e6 / f, (dev.quicksilver.render.CaveCuller.totalNs - bCull) / 1e6 / f,
                        (dev.quicksilver.render.FastFrustum.totalNs - bFrustum) / 1e6 / f, (dev.quicksilver.render.vbo.VboRenderer.totalNs - bDraw) / 1e6 / f, (ChunkScheduler.totalUploads - ups0) / sec,
                        ChunkScheduler.statQueued));
                slowFrames();
            }
            reset(now);
        }
    }

    /** Log what a long frame spent its time on (at most 10 per minute). */
    private static void hitch(long ft, long now) {
        if (now - hitchWindow > 60_000_000_000L) {
            hitchWindow = now;
            hitchesLogged = 0;
        }
        if (hitchesLogged++ >= 10) return;
        double tick = (tickNs - fTick) / 1e6, tex = (textureNs - fTex) / 1e6, load = (loaderNs - fLoad) / 1e6;
        double light = (LightBudget.totalNs - fLight) / 1e6, relight = (Relighter.totalNs - fRelight) / 1e6;
        double sched = (ChunkScheduler.totalMainNs - fSched) / 1e6, up = (ChunkScheduler.totalUploadNs - fUp) / 1e6;
        double swap = (swapNs - fSwap) / 1e6, grow = (dev.quicksilver.render.vbo.VboRegion.growNs - fGrowNs) / 1e6;
        double rest = ft / 1e6 - tick - load - light - relight - sched - swap;
        Quicksilver.LOGGER.info(String.format(
                "[hitch] %.1fms frame: tick %.1f (textures %.1f), chunk load %.1f (%d chunks), light %.1f, relight %.1f, mesh sched %.1f (upload %.1f, %d sections, vbo grow %.1f x%d), swap %.1f, other (rendering etc.) %.1f",
                ft / 1e6, tick, tex, load, chunksLoadedTotal - fLoaded, light, relight, sched, up, ChunkScheduler.totalUploads - fUploads,
                grow, dev.quicksilver.render.vbo.VboRegion.growCount - fGrow, swap, rest));
    }

    /**
     * The slowest 1% of this window's frames and what they spent their time on (averages over those frames), next to
     * the same averages over all frames. Tells chunk loading/decoration, lighting and ticking apart regardless of
     * how an external benchmark groups them.
     */
    private static void slowFrames() {
        int n = fCount;
        if (n < 200) return;
        float[] sorted = java.util.Arrays.copyOf(F_TOTAL, n);
        java.util.Arrays.sort(sorted);
        float p50 = sorted[n / 2], p99 = sorted[(int) (n * 0.99)], p999 = sorted[Math.min(n - 1, (int) (n * 0.999))];
        // columns: 0 frame, 1 tick, 2 render, 3 vbo draw, 4 frustum, 5 cave cull, 6 mesh sched, 7 chunk load,
        // 8 decorate, 9 light, 10 relight, 11 swap, 12 chunks, 13 decorations
        final int C = 17;
        double[] slow = new double[C], slowLoad = new double[C], slowNoLoad = new double[C], all = new double[C];
        int k = 0, kl = 0, kn = 0;
        double[] row = new double[C];
        for (int i = 0; i < n; i++) {
            row[0] = F_TOTAL[i]; row[1] = F_TICK[i]; row[2] = F_RENDER[i]; row[3] = F_VBO[i]; row[4] = F_FRUSTUM[i];
            row[5] = F_CULL[i]; row[6] = F_SCHED[i]; row[7] = F_LOAD[i]; row[8] = F_DECO[i]; row[9] = F_LIGHT[i];
            row[10] = F_RELIGHT[i]; row[11] = F_SWAP[i]; row[12] = F_CHUNKS[i]; row[13] = F_DECOS[i]; row[14] = F_VBUILD[i]; row[15] = F_VUP[i]; row[16] = F_VFADE[i];
            for (int j = 0; j < C; j++) all[j] += row[j];
            if (F_TOTAL[i] >= p99) {
                k++;
                for (int j = 0; j < C; j++) slow[j] += row[j];
                if (F_CHUNKS[i] > 0) {
                    kl++;
                    for (int j = 0; j < C; j++) slowLoad[j] += row[j];
                } else {
                    kn++;
                    for (int j = 0; j < C; j++) slowNoLoad[j] += row[j];
                }
            }
        }
        for (int j = 0; j < C; j++) {
            all[j] /= n;
            if (k > 0) slow[j] /= k;
            if (kl > 0) slowLoad[j] /= kl;
            if (kn > 0) slowNoLoad[j] /= kn;
        }
        Quicksilver.LOGGER.info(String.format("[slow] frames p50 %.2f p99 %.2f p99.9 %.2f ms | far chunk deferrals %d | %s",
                p50, p99, p999, dev.quicksilver.world.ChunkLoadManager.statFarDeferred, dev.quicksilver.world.AsyncDecoration.stats()));
        Quicksilver.LOGGER.info("[slow]  slowest 1% (" + k + "): " + row(slow));
        Quicksilver.LOGGER.info("[slow]   with chunk load (" + kl + "): " + row(slowLoad));
        Quicksilver.LOGGER.info("[slow]   without (" + kn + "): " + row(slowNoLoad));
        Quicksilver.LOGGER.info("[slow]  all frames: " + row(all));
    }

    private static String row(double[] r) {
        double other = r[0] - r[1] - r[2] - r[7] - r[9] - r[10] - r[11];
        return String.format("frame %.2f = tick %.2f + render %.2f (terrain draw %.2f [command build %.2f, command upload %.2f, fading %.2f], frustum %.2f, cave cull %.2f, mesh sched %.2f) + chunk load %.2f (decorate %.2f, %.2f chunks) + light %.2f + relight %.2f + swap %.2f + other %.2f",
                r[0], r[1], r[2], r[3], r[14], r[15], r[16], r[4], r[5], r[6], r[7], r[8], r[12], r[9], r[10], r[11], other);
    }

    private static void reset(long now) {
        fCount = 0;
        periodStart = now;
        frames = 0;
        worstNs = 0;
        bTick = tickNs;
        bTex = textureNs;
        bLoad = loaderNs;
        bSwap = swapNs;
        bCull = dev.quicksilver.render.CaveCuller.totalNs;
        bFrustum = dev.quicksilver.render.FastFrustum.totalNs;
        bDraw = dev.quicksilver.render.vbo.VboRenderer.totalNs;
        sched0 = ChunkScheduler.totalMainNs;
        scan0 = ChunkScheduler.totalScanNs;
        snap0 = ChunkScheduler.totalSnapshotNs;
        upload0 = ChunkScheduler.totalUploadNs;
        light0 = LightBudget.totalNs;
        relight0 = Relighter.totalNs;
        ups0 = ChunkScheduler.totalUploads;
    }
}
