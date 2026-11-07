package dev.quicksilver.shader.pipeline;

import java.util.HashMap;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

/** Texture format names usable in "const int colortexNFormat = NAME;". */
public final class Formats {
    private Formats() {}

    /** internal format, pixel format, pixel type */
    public record Format(int internal, int format, int type) {}

    private static final Map<String, Format> MAP = new HashMap<>();

    private static void f(String n, int internal, int format, int type) {
        MAP.put(n, new Format(internal, format, type));
    }

    static {
        int UB = GL11.GL_UNSIGNED_BYTE, B = GL11.GL_BYTE, US = GL11.GL_UNSIGNED_SHORT, S = GL11.GL_SHORT, F = GL11.GL_FLOAT,
                HF = GL30.GL_HALF_FLOAT, I = GL11.GL_INT, UI = GL11.GL_UNSIGNED_INT;
        int RED = GL11.GL_RED, RG = GL30.GL_RG, RGB = GL11.GL_RGB, RGBA = GL11.GL_RGBA;
        int REDI = GL30.GL_RED_INTEGER, RGI = GL30.GL_RG_INTEGER, RGBI = GL30.GL_RGB_INTEGER, RGBAI = GL30.GL_RGBA_INTEGER;
        f("R8", GL30.GL_R8, RED, UB); f("RG8", GL30.GL_RG8, RG, UB); f("RGB8", GL11.GL_RGB8, RGB, UB); f("RGBA8", GL11.GL_RGBA8, RGBA, UB);
        f("R8_SNORM", GL31.GL_R8_SNORM, RED, B); f("RG8_SNORM", GL31.GL_RG8_SNORM, RG, B); f("RGB8_SNORM", GL31.GL_RGB8_SNORM, RGB, B); f("RGBA8_SNORM", GL31.GL_RGBA8_SNORM, RGBA, B);
        f("R16", GL30.GL_R16, RED, US); f("RG16", GL30.GL_RG16, RG, US); f("RGB16", GL11.GL_RGB16, RGB, US); f("RGBA16", GL11.GL_RGBA16, RGBA, US);
        f("R16_SNORM", GL31.GL_R16_SNORM, RED, S); f("RG16_SNORM", GL31.GL_RG16_SNORM, RG, S); f("RGB16_SNORM", GL31.GL_RGB16_SNORM, RGB, S); f("RGBA16_SNORM", GL31.GL_RGBA16_SNORM, RGBA, S);
        f("R16F", GL30.GL_R16F, RED, HF); f("RG16F", GL30.GL_RG16F, RG, HF); f("RGB16F", GL30.GL_RGB16F, RGB, HF); f("RGBA16F", GL30.GL_RGBA16F, RGBA, HF);
        f("R32F", GL30.GL_R32F, RED, F); f("RG32F", GL30.GL_RG32F, RG, F); f("RGB32F", GL30.GL_RGB32F, RGB, F); f("RGBA32F", GL30.GL_RGBA32F, RGBA, F);
        f("R32I", GL30.GL_R32I, REDI, I); f("RG32I", GL30.GL_RG32I, RGI, I); f("RGB32I", GL30.GL_RGB32I, RGBI, I); f("RGBA32I", GL30.GL_RGBA32I, RGBAI, I);
        f("R32UI", GL30.GL_R32UI, REDI, UI); f("RG32UI", GL30.GL_RG32UI, RGI, UI); f("RGB32UI", GL30.GL_RGB32UI, RGBI, UI); f("RGBA32UI", GL30.GL_RGBA32UI, RGBAI, UI);
        f("R16I", GL30.GL_R16I, REDI, S); f("RG16I", GL30.GL_RG16I, RGI, S); f("RGBA16I", GL30.GL_RGBA16I, RGBAI, S);
        f("R16UI", GL30.GL_R16UI, REDI, US); f("RG16UI", GL30.GL_RG16UI, RGI, US); f("RGBA16UI", GL30.GL_RGBA16UI, RGBAI, US);
        f("R8I", GL30.GL_R8I, REDI, B); f("RGBA8I", GL30.GL_RGBA8I, RGBAI, B); f("R8UI", GL30.GL_R8UI, REDI, UB); f("RGBA8UI", GL30.GL_RGBA8UI, RGBAI, UB);
        f("R11F_G11F_B10F", GL30.GL_R11F_G11F_B10F, RGB, F);
        f("RGB9_E5", GL30.GL_RGB9_E5, RGB, F);
        f("RGB10_A2", GL11.GL_RGB10_A2, RGBA, GL11.GL_UNSIGNED_INT);
        f("RGB5_A1", GL11.GL_RGB5_A1, RGBA, UB);
        f("R3_G3_B2", GL11.GL_R3_G3_B2, RGB, UB);
        f("RGBA2", GL11.GL_RGBA2, RGBA, UB);
        f("RGB10_A2UI", 0x906F, RGBAI, GL11.GL_UNSIGNED_INT);
    }

    public static final Format DEFAULT = MAP.get("RGBA8");

    public static Format get(String name) {
        if (name == null) return null;
        return MAP.get(name.trim().toUpperCase());
    }

    public static boolean isInteger(Format f) {
        return f.format == GL30.GL_RED_INTEGER || f.format == GL30.GL_RG_INTEGER || f.format == GL30.GL_RGB_INTEGER || f.format == GL30.GL_RGBA_INTEGER;
    }
}
