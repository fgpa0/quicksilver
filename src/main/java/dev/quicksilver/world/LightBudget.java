package dev.quicksilver.world;

import dev.quicksilver.QsConfig;
import dev.quicksilver.mixin.world.LevelAccessor;
import java.util.List;
import net.minecraft.class_417;
import net.minecraft.level.Level;

/** Per-frame lighting update processing with a time budget (vanilla: always up to 500 area updates). */
public final class LightBudget {
    private LightBudget() {}

    public static int lastProcessed;
    public static int lastQueue;
    public static long totalProcessed, totalNs;
    private static final boolean DUPS = Boolean.getBoolean("quicksilver.lightDupStats");
    private static int frames;
    private static final int MAX_AREA = 512;
    private static final boolean VERIFY = Boolean.getBoolean("quicksilver.verifyLight");

    public static boolean run(Level level) {
        DeferredLighting deferred = ((LightingLevel) level).qs$deferredLighting();
        if (!deferred.isOwnerThread()) return true;
        LevelAccessor acc = (LevelAccessor) level;
        if (acc.qs$lightDepth() >= 50) return false;
        acc.qs$setLightDepth(acc.qs$lightDepth() + 1);
        long t0 = System.nanoTime();
        try {
            deferred.drain(level, 64);
            List<Object> q = acc.qs$lightingUpdates();
            int max = Math.max(1, QsConfig.lightUpdatesPerFrame);
            // at most the configured time, and at most ~20% of a frame (min 0.5 ms), so lighting can't eat high frame rates
            long budget = QsConfig.lightBudgetMs <= 0 ? 0
                    : (long) (Math.min(QsConfig.lightBudgetMs, Math.max(0.5, dev.quicksilver.render.ChunkScheduler.avgFrameMs() * 0.2)) * 1_000_000L);
            // Close to vanilla's 1M limit (where it throws the whole queue away and leaves wrongly lit areas behind
            // for good): catch up harder, a few slower frames are the lesser evil.
            if (q.size() > 300_000) {
                max *= 4;
                budget = Math.max(budget, 6_000_000L);
            }
            long start = budget > 0 ? System.nanoTime() : 0;
            int done = 0, visited = 0;
            while (!q.isEmpty()) {
                if (visited >= max) break;
                if (budget > 0 && visited > 0 && System.nanoTime() - start > budget) break;
                visited++;
                class_417 u = (class_417) q.remove(q.size() - 1);
                if (u == null) continue; // defensive: a corrupted entry must never crash the game
                int sx = u.field_1677 - u.field_1674 + 1, sy = u.field_1678 - u.field_1675 + 1, sz = u.field_1679 - u.field_1676 + 1;
                if (budget > 0 && (long) sx * sy * sz > MAX_AREA) {
                    // Split big areas in half along their longest side until a single step is ~0.5 ms or less, so the
                    // time budget can interrupt between steps (one 4096 cell area used to cost ~4 ms in one frame).
                    class_417 first, second;
                    if (sx >= sy && sx >= sz) {
                        int m = u.field_1674 + sx / 2;
                        first = new class_417(u.field_1673, u.field_1674, u.field_1675, u.field_1676, m - 1, u.field_1678, u.field_1679);
                        second = new class_417(u.field_1673, m, u.field_1675, u.field_1676, u.field_1677, u.field_1678, u.field_1679);
                    } else if (sy >= sz) {
                        int m = u.field_1675 + sy / 2;
                        first = new class_417(u.field_1673, u.field_1674, u.field_1675, u.field_1676, u.field_1677, m - 1, u.field_1679);
                        second = new class_417(u.field_1673, u.field_1674, m, u.field_1676, u.field_1677, u.field_1678, u.field_1679);
                    } else {
                        int m = u.field_1676 + sz / 2;
                        first = new class_417(u.field_1673, u.field_1674, u.field_1675, u.field_1676, u.field_1677, u.field_1678, m - 1);
                        second = new class_417(u.field_1673, u.field_1674, u.field_1675, m, u.field_1677, u.field_1678, u.field_1679);
                    }
                    q.add(second);
                    q.add(first); // the queue is a stack: first is processed next
                    continue;
                }
                if (VERIFY) FastLightVerify.check(level, u, q);
                else if (!QsConfig.fastLighting || !FastLight.process(level, u)) u.method_1402(level);
                done++;
            }
            if (DUPS && ++frames % 50 == 0 && q.size() > 1000 && q.size() < 300_000) {
                java.util.HashSet<Long> seen = new java.util.HashSet<>();
                int singles = 0, dups = 0, merged = 0;
                for (Object o : q) {
                    class_417 e = (class_417) o;
                    if (e == null) continue;
                    if (e.field_1674 == e.field_1677 && e.field_1675 == e.field_1678 && e.field_1676 == e.field_1679) {
                        singles++;
                        long k = ((long) e.field_1674 & 0x3FFFFFF) << 38 | ((long) e.field_1676 & 0x3FFFFFF) << 12 | (e.field_1675 & 0xFF) << 1 | e.field_1673.ordinal();
                        if (!seen.add(k)) dups++;
                    } else merged++;
                }
                dev.quicksilver.Quicksilver.LOGGER.info("light queue {}: {} single ({} duplicates), {} areas", q.size(), singles, dups, merged);
            }
            lastProcessed = done;
            totalProcessed += done;
            totalNs += System.nanoTime() - t0;
            lastQueue = q.size();
            return !q.isEmpty() || deferred.hasPending();
        } finally {
            acc.qs$setLightDepth(acc.qs$lightDepth() - 1);
        }
    }
}
