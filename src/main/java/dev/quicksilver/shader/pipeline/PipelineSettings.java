package dev.quicksilver.shader.pipeline;

import dev.quicksilver.shader.pack.Directives;
import dev.quicksilver.shader.pack.PropertiesFile;
import java.util.Map;

/** Pipeline-wide settings collected from the const declarations of all programs and from shaders.properties. */
public final class PipelineSettings {
    public static final int COLOR_BUFFERS = 16;
    private static final String[] LEGACY = {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"};

    public final Formats.Format[] format = new Formats.Format[COLOR_BUFFERS];
    public final boolean[] clear = new boolean[COLOR_BUFFERS];
    public final float[][] clearColor = new float[COLOR_BUFFERS][];
    public final boolean[] mipmap = new boolean[COLOR_BUFFERS];
    public final float[] scaleX = new float[COLOR_BUFFERS], scaleY = new float[COLOR_BUFFERS];
    /** buffer is referenced by some program (sampled or drawn) */
    public final boolean[] used = new boolean[COLOR_BUFFERS];

    public int shadowMapResolution = 1024;
    public float shadowDistance = 160;
    public float shadowDistanceRenderMul = -1;
    public float shadowIntervalSize = 2;
    public float sunPathRotation = 0;
    public boolean shadowHardwareFiltering = false;
    public final boolean[] shadowHardwareFilteringN = new boolean[2];
    public final Formats.Format[] shadowColorFormat = new Formats.Format[2];
    public final boolean[] shadowColorClear = {true, true};
    public final float[][] shadowColorClearColor = new float[2][];
    public boolean shadowUsed;
    public final boolean[] shadowColorUsed = new boolean[2];
    public float entityShadowDistanceMul = 1;

    public int noiseTextureResolution = 256;
    public float wetnessHalflife = 600, drynessHalflife = 200, eyeBrightnessHalflife = 10, centerDepthHalflife = 1;
    public float ambientOcclusionLevel = 1;

    public PipelineSettings() {
        for (int i = 0; i < COLOR_BUFFERS; i++) {
            clear[i] = true;
            scaleX[i] = scaleY[i] = 1;
        }
    }

    /** Applies the const declarations of one program source. */
    public void apply(Map<String, String> c) {
        for (int i = 0; i < COLOR_BUFFERS; i++) {
            String n = "colortex" + i;
            String fmt = c.get(n + "Format");
            if (fmt == null && i < 8) fmt = c.get(LEGACY[i] + "Format");
            if (fmt != null && Formats.get(fmt) != null) format[i] = Formats.get(fmt);
            String cl = c.get(n + "Clear");
            if (cl == null && i < 8) cl = c.get(LEGACY[i] + "Clear");
            if (cl != null) clear[i] = Directives.parseBool(cl, true);
            String cc = c.get(n + "ClearColor");
            if (cc == null && i < 8) cc = c.get(LEGACY[i] + "ClearColor");
            if (cc != null) clearColor[i] = Directives.parseVec4(cc);
            String mm = c.get(n + "MipmapEnabled");
            if (mm == null && i < 8) mm = c.get(LEGACY[i] + "MipmapEnabled");
            if (mm != null) mipmap[i] = Directives.parseBool(mm, false);
        }
        for (int i = 0; i < 2; i++) {
            String fmt = c.get("shadowcolor" + i + "Format");
            if (fmt == null && i == 0) fmt = c.get("shadowcolorFormat");
            if (fmt != null && Formats.get(fmt) != null) shadowColorFormat[i] = Formats.get(fmt);
            String cl = c.get("shadowcolor" + i + "Clear");
            if (cl != null) shadowColorClear[i] = Directives.parseBool(cl, true);
            String cc = c.get("shadowcolor" + i + "ClearColor");
            if (cc != null) shadowColorClearColor[i] = Directives.parseVec4(cc);
            String hf = c.get("shadowtex" + i + "HardwareFiltering");
            if (hf != null) shadowHardwareFilteringN[i] = Directives.parseBool(hf, false);
        }
        if (c.containsKey("shadowMapResolution")) shadowMapResolution = (int) Directives.parseFloat(c.get("shadowMapResolution"), shadowMapResolution);
        if (c.containsKey("shadowDistance")) shadowDistance = Directives.parseFloat(c.get("shadowDistance"), shadowDistance);
        if (c.containsKey("shadowDistanceRenderMul")) shadowDistanceRenderMul = Directives.parseFloat(c.get("shadowDistanceRenderMul"), shadowDistanceRenderMul);
        if (c.containsKey("shadowIntervalSize")) shadowIntervalSize = Directives.parseFloat(c.get("shadowIntervalSize"), shadowIntervalSize);
        if (c.containsKey("sunPathRotation")) sunPathRotation = Directives.parseFloat(c.get("sunPathRotation"), sunPathRotation);
        if (c.containsKey("shadowHardwareFiltering")) shadowHardwareFiltering = Directives.parseBool(c.get("shadowHardwareFiltering"), false);
        if (c.containsKey("shadowHardwareFiltering0")) shadowHardwareFilteringN[0] = Directives.parseBool(c.get("shadowHardwareFiltering0"), false);
        if (c.containsKey("shadowHardwareFiltering1")) shadowHardwareFilteringN[1] = Directives.parseBool(c.get("shadowHardwareFiltering1"), false);
        if (c.containsKey("entityShadowDistanceMul")) entityShadowDistanceMul = Directives.parseFloat(c.get("entityShadowDistanceMul"), 1);
        if (c.containsKey("noiseTextureResolution")) noiseTextureResolution = (int) Directives.parseFloat(c.get("noiseTextureResolution"), 256);
        if (c.containsKey("wetnessHalflife")) wetnessHalflife = Directives.parseFloat(c.get("wetnessHalflife"), wetnessHalflife);
        if (c.containsKey("drynessHalflife")) drynessHalflife = Directives.parseFloat(c.get("drynessHalflife"), drynessHalflife);
        if (c.containsKey("eyeBrightnessHalflife")) eyeBrightnessHalflife = Directives.parseFloat(c.get("eyeBrightnessHalflife"), eyeBrightnessHalflife);
        if (c.containsKey("centerDepthHalflife")) centerDepthHalflife = Directives.parseFloat(c.get("centerDepthHalflife"), centerDepthHalflife);
        if (c.containsKey("ambientOcclusionLevel")) ambientOcclusionLevel = Directives.parseFloat(c.get("ambientOcclusionLevel"), 1);
    }

    /** size.buffer.colortexN = w h (absolute pixels if > 1? OptiFine: scale factors if fractional). */
    public void applyProperties(PropertiesFile p) {
        for (int i = 0; i < COLOR_BUFFERS; i++) {
            String v = p.get("size.buffer.colortex" + i);
            if (v == null && i < 8) v = p.get("size.buffer." + LEGACY[i]);
            if (v == null) continue;
            String[] parts = v.trim().split("\\s+");
            if (parts.length < 2) continue;
            float sx = Directives.parseFloat(parts[0], 1), sy = Directives.parseFloat(parts[1], 1);
            if (sx > 0 && sx <= 16 && sy > 0 && sy <= 16) {
                scaleX[i] = sx;
                scaleY[i] = sy;
            }
        }
    }

    /** Index of a sampler/buffer name ("colortex3", "gaux1", ...) or -1. */
    public static int bufferIndex(String name) {
        if (name.startsWith("colortex")) {
            try {
                int i = Integer.parseInt(name.substring(8));
                return i >= 0 && i < COLOR_BUFFERS ? i : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        for (int i = 0; i < LEGACY.length; i++) if (LEGACY[i].equals(name)) return i;
        return -1;
    }
}
