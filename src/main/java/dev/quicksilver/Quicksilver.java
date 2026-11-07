package dev.quicksilver;

import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class Quicksilver {
    public static final String MOD_ID = "quicksilver";
    public static final Logger LOGGER = LogManager.getLogger("Quicksilver");

    private static boolean configLoaded;
    private static volatile Thread clientThread;

    /** Remember the game (render/tick) thread. */
    public static void setClientThread(Thread t) {
        clientThread = t;
    }

    /** True on the game thread (or before it is known); false on Quicksilver worker threads etc. */
    public static boolean isClientThread() {
        Thread c = clientThread;
        return c == null || c == Thread.currentThread();
    }

    private Quicksilver() {}

    public static synchronized void ensureConfig() {
        if (configLoaded) return;
        configLoaded = true;
        QsConfig.load(FabricLoader.getInstance().getConfigDir().toFile());
        LOGGER.info("Quicksilver loaded (threaded builds: {}, async IO: {}, progressive loading: {})",
                QsConfig.threadedChunkBuilds, QsConfig.asyncChunkSaving, QsConfig.progressiveChunkLoading);
    }

    public static int workerThreads() {
        int n = QsConfig.chunkBuildThreads;
        if (n <= 0) n = Math.min(6, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        return n;
    }
}
