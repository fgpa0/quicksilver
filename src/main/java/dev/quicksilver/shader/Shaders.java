package dev.quicksilver.shader;

import dev.quicksilver.shader.pack.LoadedPack;
import dev.quicksilver.shader.pack.ShaderPack;
import dev.quicksilver.shader.pipeline.Pipeline;
import dev.quicksilver.shader.ShaderBridge;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/** Shader packs (OptiFine format, 1.7.10-era features) drawn on top of Quicksilver's VBO terrain renderer. */
public final class Shaders {
    private Shaders() {}

    public static final Logger LOGGER = LogManager.getLogger("Quicksilver Shaders");
    private static ShaderConfig config;
    private static Pipeline pipeline;
    private static LoadedPack loaded;
    private static boolean reloadRequested = true;
    private static String failedPack;
    /** last error shown in the shader screen */
    public static String lastError;

    public static ShaderConfig config() {
        if (config == null) config = ShaderConfig.load(FabricLoader.getInstance().getConfigDir().resolve("quicksilver-shaders.properties"));
        return config;
    }

    public static Minecraft mc() {
        return dev.quicksilver.client.RenderDistance.MinecraftHolder.get();
    }

    public static boolean verbose() {
        return config().verbose;
    }

    public static Path packsDir() {
        Path p = FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
        try {
            Files.createDirectories(p);
        } catch (Exception ignored) {
        }
        return p;
    }

    /** The active pipeline, or null when shaders are off. */
    public static Pipeline pipeline() {
        return pipeline;
    }

    public static boolean active() {
        return pipeline != null;
    }

    public static LoadedPack loadedPack() {
        return loaded;
    }

    /** Apply a changed pack or changed options at the next frame. */
    public static void requestReload() {
        reloadRequested = true;
        failedPack = null;
    }

    /** Called at the start of every world frame (GL thread): (re)creates the pipeline when needed. */
    public static void tick() {
        Minecraft mc = mc();
        if (mc == null || mc.level == null) return;
        int dim = mc.level.dimension.id;
        if (pipeline != null && pipeline.dimension != dim) reloadRequested = true;
        if (!reloadRequested) return;
        reloadRequested = false;
        boolean wasExtended = ShaderBridge.extended();
        destroyPipeline();
        String name = config().pack;
        if (name != null && !name.isEmpty() && !name.equals(failedPack)) {
            try {
                create(name, dim);
            } catch (Throwable t) {
                LOGGER.error("Could not load shader pack " + name, t);
                lastError = "Could not load " + name + ": " + t;
                destroyPipeline();
            }
            if (pipeline == null) failedPack = name;
        }
        boolean nowExtended = pipeline != null;
        ShaderBridge.setProvider(nowExtended ? PROVIDER : null);
        if (wasExtended != nowExtended && mc.worldRenderer != null) mc.worldRenderer.method_1537();
    }

    private static void create(String name, int dim) throws Exception {
        long t0 = System.nanoTime();
        ShaderPack sp = ShaderPack.open(packsDir(), name);
        if (sp == null) {
            lastError = "Shader pack not found: " + name;
            LOGGER.warn(lastError);
            return;
        }
        LoadedPack lp = new LoadedPack(sp, standardDefines());
        lp.load(settingsFile(name));
        Pipeline p = new Pipeline(lp, dim);
        if (!p.create()) {
            p.destroy();
            sp.close();
            lastError = "Shader pack " + name + " failed to load (see log)";
            return;
        }
        loaded = lp;
        pipeline = p;
        lastError = null;
        LOGGER.info("Loaded shader pack {} in {} ms", name, (System.nanoTime() - t0) / 1_000_000);
    }

    public static Path settingsFile(String packName) {
        return packsDir().resolve(packName + ".txt");
    }

    private static void destroyPipeline() {
        if (pipeline != null) {
            try {
                pipeline.destroy();
            } catch (Throwable t) {
                LOGGER.warn("Error destroying pipeline", t);
            }
            pipeline = null;
        }
        if (loaded != null) {
            loaded.pack.close();
            loaded = null;
        }
    }

    /** Turns shaders off after a fatal error during rendering. */
    public static void fail(String where, Throwable t) {
        LOGGER.error("Shader error in " + where + ", turning shaders off", t);
        lastError = "Error in " + where + ": " + t;
        failedPack = config().pack;
        destroyPipeline();
        ShaderBridge.setProvider(null);
        Minecraft mc = mc();
        if (mc != null && mc.worldRenderer != null) mc.worldRenderer.method_1537();
    }

    private static final ShaderBridge.Provider PROVIDER = new ShaderBridge.Provider() {
        @Override
        public int blockId(int id, int meta, boolean snowAbove) {
            LoadedPack lp = loaded;
            return lp == null ? id : lp.ids.block(id, meta, snowAbove);
        }

        @Override
        public boolean oldLighting() {
            Pipeline p = pipeline;
            return p != null && p.oldLighting();
        }

        @Override
        public boolean separateAo() {
            Pipeline p = pipeline;
            return p == null || p.separateAo();
        }
    };

    // ------------------------------------------------------------------ standard macros

    private static List<String> standardDefines;

    static List<String> standardDefines() {
        if (standardDefines != null) return standardDefines;
        List<String> d = new ArrayList<>();
        d.add("#define MC_VERSION 10710");
        String ver = GL11.glGetString(GL11.GL_VERSION);
        int glVersion = parseVersion(ver);
        String glsl = GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION);
        int glslVersion = parseVersion(glsl);
        d.add("#define MC_GL_VERSION " + glVersion);
        d.add("#define MC_GLSL_VERSION " + glslVersion);
        String os = System.getProperty("os.name", "").toLowerCase();
        d.add(os.contains("win") ? "#define MC_OS_WINDOWS" : os.contains("mac") ? "#define MC_OS_MAC" : os.contains("linux") ? "#define MC_OS_LINUX" : "#define MC_OS_OTHER");
        String vendor = String.valueOf(GL11.glGetString(GL11.GL_VENDOR)).toLowerCase();
        String renderer = String.valueOf(GL11.glGetString(GL11.GL_RENDERER)).toLowerCase();
        if (vendor.contains("nvidia")) d.add("#define MC_GL_VENDOR_NVIDIA");
        else if (vendor.contains("ati") || vendor.contains("amd")) d.add("#define MC_GL_VENDOR_AMD");
        else if (vendor.contains("intel")) d.add("#define MC_GL_VENDOR_INTEL");
        else if (vendor.contains("mesa") || renderer.contains("mesa") || renderer.contains("llvmpipe")) d.add("#define MC_GL_VENDOR_MESA");
        else if (vendor.contains("x.org")) d.add("#define MC_GL_VENDOR_XORG");
        else d.add("#define MC_GL_VENDOR_OTHER");
        if (renderer.contains("geforce") || renderer.contains("rtx") || renderer.contains("gtx")) d.add("#define MC_GL_RENDERER_GEFORCE");
        else if (renderer.contains("quadro")) d.add("#define MC_GL_RENDERER_QUADRO");
        else if (renderer.contains("radeon")) d.add("#define MC_GL_RENDERER_RADEON");
        else if (renderer.contains("intel")) d.add("#define MC_GL_RENDERER_INTEL");
        else if (renderer.contains("mesa") || renderer.contains("llvmpipe")) d.add("#define MC_GL_RENDERER_MESA");
        else d.add("#define MC_GL_RENDERER_OTHER");
        d.add("#define MC_RENDER_QUALITY 1.0");
        d.add("#define MC_SHADOW_QUALITY 1.0");
        d.add("#define MC_HAND_DEPTH 0.125");
        String[] stages = {"NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID", "TERRAIN_SOLID", "TERRAIN_CUTOUT_MIPPED",
                "TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG", "HAND_SOLID", "TERRAIN_TRANSLUCENT",
                "TRIPWIRE", "PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER", "HAND_TRANSLUCENT"};
        for (int i = 0; i < stages.length; i++) d.add("#define MC_RENDER_STAGE_" + stages[i] + " " + i);
        d.add("#define QUICKSILVER");
        LOGGER.info("GL {} / GLSL {} ({}, {})", ver, glsl, vendor, renderer);
        standardDefines = d;
        return d;
    }

    private static int parseVersion(String s) {
        if (s == null) return 120;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)").matcher(s);
        if (!m.find()) return 120;
        int major = Integer.parseInt(m.group(1)), minor = Integer.parseInt(m.group(2));
        if (minor >= 10) minor /= 10;
        return major * 100 + minor * 10;
    }

    public static void dumpSource(String path, String source) {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("shader-debug");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(path.replace('/', '_')), source, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
