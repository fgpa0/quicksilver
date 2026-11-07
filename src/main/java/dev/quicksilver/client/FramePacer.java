package dev.quicksilver.client;

import dev.quicksilver.QsConfig;
import java.util.concurrent.locks.LockSupport;

/** Precise frame limiter (sleep most of the wait, spin the last ~1 ms). */
public final class FramePacer {
    private FramePacer() {}

    private static long next;

    public static void pace() {
        int fps = QsConfig.maxFps;
        long interval = fps > 0 ? 1_000_000_000L / fps : 0;
        long monitor = MonitorSync.intervalNs(); // VSync "Match Monitor"
        if (monitor > interval) interval = monitor;
        if (interval <= 0) {
            next = 0;
            return;
        }
        long now = System.nanoTime();
        if (next == 0 || now - next > interval * 4) next = now; // too far behind: resync instead of bursting
        long target = next + interval;
        long remaining;
        while ((remaining = target - System.nanoTime()) > 0) {
            if (remaining > 1_500_000L) LockSupport.parkNanos(remaining - 1_000_000L);
            else Thread.onSpinWait();
        }
        next = target;
    }
}
