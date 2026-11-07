package dev.quicksilver.shader.pipeline;

import dev.quicksilver.shader.Shaders;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GLContext;

/**
 * Optional GPU timing of the frame's stages (-Dquicksilver.shaders.gpuTimers=true). Consecutive stages are measured with
 * timer queries, results are read a few frames late so nothing stalls. Logged every 10 seconds.
 */
final class GpuTimer {
    private GpuTimer() {}

    static final int SHADOW = 0, OPAQUE = 1, DEFERRED = 2, TRANSLUCENT = 3, COMPOSITE = 4, FINAL = 5;
    private static final String[] NAMES = {"shadow", "opaque scene", "deferred", "translucent+hand", "composite", "final"};
    static final boolean ENABLED = Boolean.getBoolean("quicksilver.shaders.gpuTimers");
    private static final int TIME_ELAPSED = 0x88BF;

    private static boolean supported;
    private static boolean checked;
    private static int active = -1;
    private static int activeQuery;
    private static final int[] pendingQ = new int[256];
    private static final int[] pendingId = new int[256];
    private static int pendingCount;
    private static final int[] free = new int[256];
    private static int freeCount;
    private static final double[] sumMs = new double[NAMES.length];
    private static int frames;
    private static long since;

    private static boolean ok() {
        if (!ENABLED) return false;
        if (!checked) {
            checked = true;
            supported = GLContext.getCapabilities().GL_ARB_timer_query;
            if (!supported) Shaders.LOGGER.warn("GPU timers requested but ARB_timer_query is not available");
        }
        return supported;
    }

    static void switchTo(int id) {
        if (!ok()) return;
        stopActive();
        activeQuery = freeCount > 0 ? free[--freeCount] : GL15.glGenQueries();
        GL15.glBeginQuery(TIME_ELAPSED, activeQuery);
        active = id;
    }

    private static void stopActive() {
        if (active < 0) return;
        GL15.glEndQuery(TIME_ELAPSED);
        if (pendingCount < pendingQ.length) {
            pendingQ[pendingCount] = activeQuery;
            pendingId[pendingCount++] = active;
        }
        active = -1;
    }

    /** End of the frame's timed section: collect finished results. */
    static void endFrame() {
        if (!ok()) return;
        stopActive();
        frames++;
        int keep = 0;
        for (int i = 0; i < pendingCount; i++) {
            int q = pendingQ[i];
            if (GL15.glGetQueryObjectui(q, GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                long ns = ARBTimerQuery.glGetQueryObjectui64(q, GL15.GL_QUERY_RESULT);
                sumMs[pendingId[i]] += ns / 1e6;
                if (freeCount < free.length) free[freeCount++] = q;
            } else {
                pendingQ[keep] = q;
                pendingId[keep++] = pendingId[i];
            }
        }
        pendingCount = keep;
        long now = System.nanoTime();
        if (since == 0) since = now;
        if (now - since > 10_000_000_000L) {
            StringBuilder sb = new StringBuilder("[gpu] ");
            double total = 0;
            for (int i = 0; i < NAMES.length; i++) {
                double ms = sumMs[i] / Math.max(1, frames);
                total += ms;
                sb.append(NAMES[i]).append(' ').append(String.format("%.2f", ms)).append(" ms, ");
                sumMs[i] = 0;
            }
            sb.append("sum ").append(String.format("%.2f", total)).append(" ms/frame over ").append(frames).append(" frames");
            Shaders.LOGGER.info(sb.toString());
            since = now;
            frames = 0;
        }
    }
}
