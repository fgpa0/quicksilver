package dev.quicksilver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Properties;

/**
 * Simple properties-backed configuration (config/quicksilver.properties).
 * Every public static non-final field is an option; missing keys are written back with defaults.
 */
public final class QsConfig {
    private QsConfig() {}

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD)
    public @interface Desc {
        String value();
    }

    /** Slider range for numeric options in the config screen. */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD)
    public @interface Range {
        double min();

        double max();

        double step() default 1;
    }

    /** Int option shown as a button cycling through these labels (value = index). */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD)
    public @interface Choices {
        String[] value();
    }

    /** Option only takes effect after restarting the game. */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD)
    public @interface Restart {
    }

    // ---- view ----
    @Desc("Render distance in chunks (vanilla Far is about 12). Also adjustable with the slider in Video Settings.")
    @Range(min = 2, max = 48)
    public static int renderDistance = 12;

    @Desc("Fog distance in chunks; 0 follows the render distance. Also adjustable with the slider in Video Settings.")
    @Range(min = 0, max = 48)
    public static int fogDistance = 0;

    // ---- chunk rendering ----
    @Desc("Draw terrain from GPU vertex buffers with one multi-draw call per 128-block region and skip faces pointing away from you. Off = vanilla display lists.")
    public static boolean vboRenderer = true;
    @Desc("Apply the time of day to terrain on the GPU (small shader) instead of baking it into the meshes, so sunrise and sunset don't rebuild every lit section. Needs the VBO renderer; falls back automatically if the GPU can't compile the shader.")
    @Restart
    public static boolean skyShader = true;
    @Desc("Newly appearing terrain fades in over this many milliseconds instead of popping in (0 = off).")
    @Range(min = 0, max = 1000, step = 50)
    public static int chunkFadeInMs = 300;
    @Desc("Build chunk meshes on worker threads (vanilla blocks); unsafe/modded blocks still render on the main thread.")
    public static boolean threadedChunkBuilds = true;
    @Desc("Number of mesh worker threads; 0 = automatic (cores - 1, max 6).")
    @Range(min = 0, max = 16)
    @Restart
    public static int chunkBuildThreads = 0;
    @Desc("Main-thread milliseconds per frame spent uploading finished meshes / doing main-thread builds.")
    @Range(min = 0.5, max = 16, step = 0.5)
    public static float chunkUploadBudgetMs = 3.0f;
    @Desc("Sections closer than this (in blocks) that are visible are always rebuilt in the same frame (avoids holes when editing blocks).")
    @Range(min = 0, max = 64, step = 4)
    public static int urgentRebuildDistance = 24;
    @Desc("Sections farther than 48 blocks whose only change is lighting are rebuilt at most once per this many milliseconds (0 = rebuild immediately like vanilla). Cuts the rebuild storm while lighting settles after loading terrain.")
    @Range(min = 0, max = 2000, step = 100)
    public static int lightRebuildDelayMs = 500;
    @Desc("Cache grass/leaf biome colours per section instead of sampling noise per block.")
    public static boolean fastBiomeColours = true;

    // ---- chunk loading / world ----
    @Desc("Load and generate chunks progressively (nearest first, time budgeted) instead of 361 at once on the tick thread.")
    public static boolean progressiveChunkLoading = true;
    @Desc("Main-thread milliseconds per frame spent loading/generating chunks. At least one chunk is always processed.")
    @Range(min = 0.5, max = 16, step = 0.5)
    public static float chunkLoadBudgetMs = 2.5f;
    @Desc("Share of main-thread time (0.05-1) for loading and decorating chunks more than 8 chunks away. Far terrain is spread over many frames (about one chunk per frame at most) instead of filling the whole budget above, which removes the stutter when you cross a chunk border at large render distances. Load order and world generation are unchanged. 1 = off (always use the full budget).")
    @Range(min = 0.05, max = 1, step = 0.05)
    public static float farChunkLoadShare = 0.25f;

    @Desc("Unload chunks far away from the player in singleplayer (vanilla keeps every visited chunk loaded and ticking).")
    public static boolean unloadDistantChunks = true;
    @Desc("Generate overworld terrain on background threads.")
    public static boolean asyncWorldGen = true;
    @Desc("Decorate chunks (ore, trees, lakes, flowers...) on background threads instead of the game thread. Each chunk is decorated on a copy of its four chunks and the result is applied on the game thread; results are identical to vanilla, and anything unusual (dungeons, mobs, features reaching outside the four chunks, chunks changed in the meantime) falls back to vanilla decoration on the game thread.")
    public static boolean asyncDecoration = true;
    @Desc("Number of decoration threads; 0 = automatic.")
    @Range(min = 0, max = 4)
    @Restart
    public static int decorationThreads = 0;
    @Desc("Number of world generation threads; 0 = automatic.")
    @Range(min = 0, max = 8)
    @Restart
    public static int worldGenThreads = 0;
    @Desc("Compress and write chunks on a background thread.")
    public static boolean asyncChunkSaving = true;
    @Desc("Read and decompress chunks from disk ahead of time on background threads.")
    public static boolean chunkPrefetch = true;
    @Desc("Max lighting updates processed per frame (vanilla: 500). The time budget below is what normally limits it; a backlog that is worked off promptly is much smaller in total than one that piles up.")
    @Range(min = 500, max = 20000, step = 500)
    public static int lightUpdatesPerFrame = 5000;
    @Desc("Max time (ms) per frame for lighting updates; lighting also never takes more than ~20% of a frame (min 0.5 ms). 0 = only use the count limit.")
    @Range(min = 0, max = 16, step = 0.5)
    public static float lightBudgetMs = 3.0f;

    @Desc("Process lighting updates with a faster implementation of vanilla's algorithm (same results).")
    public static boolean fastLighting = true;
    @Desc("Fix vanilla's pitch black shadows under trees and overhangs in freshly generated terrain by redoing the sideways sky light once a chunk's neighbours are loaded.")
    public static boolean fixGenerationLighting = true;

    // ---- frame pacing ----
    @Desc("Skip the two glGetError() calls per frame (they can force a CPU/GPU sync).")
    public static boolean skipGlErrorChecks = true;
    @Desc("Frame rate cap; 0 = uncapped (or vanilla limiter).")
    @Range(min = 0, max = 360, step = 5)
    public static int maxFps = 0;
    @Desc("Off: no sync. On: the driver's vertical sync (on some systems it follows the wrong monitor). Match Monitor: frames are paced to the refresh rate of the monitor the game window is on, detected automatically and updated when the window moves (no driver sync, works with any GPU and with G-Sync/FreeSync).")
    @Choices({"Off", "On", "Match Monitor"})
    public static int vsyncMode = 0;

    // ---- misc rendering ----
    @Desc("Disable vanilla \"Advanced OpenGL\" hardware occlusion queries (they stall the pipeline); the CPU occlusion culler replaces them.")
    public static boolean disableOcclusionQueries = true;
    @Desc("Cull chunk sections that can't be seen through caves/walls using a per-section visibility graph.")
    public static boolean caveCulling = true;
    @Desc("Show extra profiling info on the F3 screen.")
    public static boolean debugInfo = true;

    @Desc("Internal: used to update old config files to new defaults. Don't edit.")
    public static int configVersion = 2;

    private static File file;

    /** Called by the config screen after an option changed. */
    public static void onChanged(String name) {
        try {
            if (name.equals("vsyncMode")) dev.quicksilver.client.MonitorSync.apply();
            if (name.equals("renderDistance")) dev.quicksilver.client.RenderDistance.set(renderDistance);
            if (name.equals("vboRenderer") || name.equals("threadedChunkBuilds")) {
                net.minecraft.client.Minecraft mc = dev.quicksilver.client.RenderDistance.MinecraftHolder.get();
                if (mc != null && mc.worldRenderer != null) mc.worldRenderer.method_1537();
            }
        } catch (Throwable ignored) {
        }
    }

    public static void load(File configDir) {
        file = new File(configDir, "quicksilver.properties");
        Properties p = new Properties();
        if (file.exists()) {
            try (InputStream in = new FileInputStream(file)) {
                p.load(in);
            } catch (Exception e) {
                Quicksilver.LOGGER.warn("Failed to read " + file, e);
            }
        }
        boolean missing = false;
        for (Field f : QsConfig.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (!Modifier.isStatic(m) || Modifier.isFinal(m) || !Modifier.isPublic(m)) continue;
            String v = p.getProperty(f.getName());
            if (v == null) {
                missing = true;
                continue;
            }
            try {
                v = v.trim();
                if (f.getType() == boolean.class) f.setBoolean(null, Boolean.parseBoolean(v));
                else if (f.getType() == int.class) f.setInt(null, Integer.parseInt(v));
                else if (f.getType() == float.class) f.setFloat(null, Float.parseFloat(v));
            } catch (Exception e) {
                Quicksilver.LOGGER.warn("Bad value for " + f.getName() + ": " + v);
            }
        }
        // migrate: defaults that changed between versions are updated if the user never changed them
        int fileVersion = 1;
        try {
            if (p.getProperty("configVersion") != null) fileVersion = Integer.parseInt(p.getProperty("configVersion").trim());
        } catch (NumberFormatException ignored) {
        }
        if (file.exists() && fileVersion < 2) {
            if (lightUpdatesPerFrame == 500) lightUpdatesPerFrame = 5000;
            configVersion = 2;
            missing = true;
        }
        if (missing) save();
    }

    public static void save() {
        if (file == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("# Quicksilver configuration. Restart the game after editing.\n");
        for (Field f : QsConfig.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (!Modifier.isStatic(m) || Modifier.isFinal(m) || !Modifier.isPublic(m)) continue;
            try {
                Desc d = f.getAnnotation(Desc.class);
                sb.append("\n");
                if (d != null) sb.append("# ").append(d.value()).append("\n");
                sb.append(f.getName()).append('=').append(f.get(null)).append("\n");
            } catch (Exception ignored) {
            }
        }
        try {
            file.getParentFile().mkdirs();
            java.nio.file.Files.writeString(file.toPath(), sb.toString());
        } catch (Exception e) {
            Quicksilver.LOGGER.warn("Failed to write " + file, e);
        }
    }
}
