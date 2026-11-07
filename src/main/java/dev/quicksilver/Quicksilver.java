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

    /**
     * True for the level the client is playing in (or before one exists). Other levels, such as one another mod
     * loads in the background, are left to vanilla: Quicksilver's loading, generation and relighting queues are
     * built around the rendered level and the player in it.
     */
    public static boolean isRenderedLevel(net.minecraft.level.Level level) {
        net.minecraft.client.Minecraft mc = dev.quicksilver.client.RenderDistance.MinecraftHolder.get();
        return mc == null || level == mc.level;
    }

    /** True if region files in this folder belong to the rendered level (or it can't be told). */
    public static boolean isRenderedRegionDir(java.io.File dir) {
        net.minecraft.client.Minecraft mc = dev.quicksilver.client.RenderDistance.MinecraftHolder.get();
        if (mc == null || mc.level == null) return true;
        Object data = ((dev.quicksilver.mixin.world.LevelAccessor) mc.level).qs$dimensionData();
        if (!(data instanceof net.minecraft.level.dimension.DimensionFile)) return true;
        java.io.File expected = ((dev.quicksilver.mixin.io.DimensionFileAccessor) data).qs$folder();
        if (mc.level.dimension.id == -1) expected = new java.io.File(expected, "DIM-1");
        return expected.equals(dir);
    }

    public static int workerThreads() {
        int n = QsConfig.chunkBuildThreads;
        if (n <= 0) n = Math.min(6, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        return n;
    }
}
