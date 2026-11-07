package dev.quicksilver.shader.pipeline;

import dev.quicksilver.shader.Shaders;
import dev.quicksilver.shader.pack.ShaderPack;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.Random;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/** Small textures: noise, default normal/specular maps, the light map, images from the pack. */
public final class Textures {
    private Textures() {}

    public static int create(int w, int h, ByteBuffer rgba, boolean linear, boolean repeat) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, repeat ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, repeat ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return t;
    }

    public static int solid(int r, int g, int b, int a) {
        ByteBuffer buf = BufferUtils.createByteBuffer(4);
        buf.put((byte) r).put((byte) g).put((byte) b).put((byte) a).flip();
        return create(1, 1, buf, false, true);
    }

    /** Random noise like OptiFine's default noisetex. */
    public static int noise(int size) {
        size = Math.max(1, Math.min(4096, size));
        ByteBuffer buf = BufferUtils.createByteBuffer(size * size * 4);
        Random r = new Random(0);
        for (int i = 0; i < size * size; i++) {
            buf.put((byte) r.nextInt(256)).put((byte) r.nextInt(256)).put((byte) r.nextInt(256)).put((byte) 255);
        }
        buf.flip();
        return create(size, size, buf, true, true);
    }

    /** Loads an image from the pack; returns 0 if missing or unreadable. */
    public static int fromPack(ShaderPack pack, String path, boolean linear, boolean repeat) {
        byte[] data = pack.readBytes(path);
        if (data == null) {
            Shaders.LOGGER.warn("Pack texture not found: {}", path);
            return 0;
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) return 0;
            int w = img.getWidth(), h = img.getHeight();
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
            for (int p : px) buf.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
            buf.flip();
            int t = create(w, h, buf, linear, repeat);
            return t;
        } catch (Exception e) {
            Shaders.LOGGER.warn("Could not load pack texture " + path, e);
            return 0;
        }
    }

    // ------------------------------------------------------------------ light map

    private static ByteBuffer lightmapBuf;

    /**
     * 16x16 light map (x = block light, y = sky light) like vanilla 1.7.10's, from the time of day: shaders mostly
     * compute their own lighting from the coordinates, but some sample it.
     */
    public static void updateLightmap(int tex, float skyBrightness, float[] lightTable, float gamma) {
        if (lightmapBuf == null) lightmapBuf = BufferUtils.createByteBuffer(16 * 16 * 4);
        lightmapBuf.clear();
        for (int sky = 0; sky < 16; sky++) {
            for (int blk = 0; blk < 16; blk++) {
                float s = lightTable[sky] * skyBrightness;
                float b = lightTable[blk];
                float r = Math.min(1f, s * 0.95f + 0.05f + b * 1.0f);
                float g = Math.min(1f, s * 0.95f + 0.05f + b * 0.85f);
                float bl = Math.min(1f, s + 0.05f + b * 0.6f);
                r = r + (1 - (1 - r) * (1 - r) * (1 - r) * (1 - r)) * gamma - r * gamma;
                lightmapBuf.put((byte) (Math.min(1f, r) * 255)).put((byte) (Math.min(1f, g) * 255)).put((byte) (Math.min(1f, bl) * 255)).put((byte) 255);
            }
        }
        lightmapBuf.flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 16, 16, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, lightmapBuf);
    }

    public static int createLightmap() {
        ByteBuffer buf = BufferUtils.createByteBuffer(16 * 16 * 4);
        for (int i = 0; i < 256; i++) buf.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
        buf.flip();
        int t = create(16, 16, buf, true, false);
        return t;
    }

    /** Sampler objects: linear, nearest and depth compare (sampler2DShadow). */
    public static int sampler(boolean compare, boolean linear) {
        int s = org.lwjgl.opengl.GL33.glGenSamplers();
        int f = linear ? GL11.GL_LINEAR : GL11.GL_NEAREST;
        org.lwjgl.opengl.GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_MIN_FILTER, f);
        org.lwjgl.opengl.GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_MAG_FILTER, f);
        org.lwjgl.opengl.GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        org.lwjgl.opengl.GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        if (compare) {
            org.lwjgl.opengl.GL33.glSamplerParameteri(s, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
            org.lwjgl.opengl.GL33.glSamplerParameteri(s, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        }
        return s;
    }
}
