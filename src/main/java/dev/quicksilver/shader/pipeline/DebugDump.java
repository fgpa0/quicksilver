package dev.quicksilver.shader.pipeline;

import dev.quicksilver.shader.Shaders;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Debugging: writes every buffer of a frame to PNG files (shader-debug/). Triggered by -Dquicksilver.shaders.dumpFrame=N. */
final class DebugDump {
    private DebugDump() {}

    static final int DUMP_FRAME = Integer.getInteger("quicksilver.shaders.dumpFrame", -1);
    private static int frames;

    static boolean due() {
        if (Boolean.getBoolean("quicksilver.shaders.dumpNow")) {
            System.clearProperty("quicksilver.shaders.dumpNow");
            return true;
        }
        if (DUMP_FRAME < 0) return false;
        return ++frames == DUMP_FRAME;
    }

    static void dump(Pipeline p, String stage) {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("shader-debug");
            Files.createDirectories(dir);
            RenderTargets t = p.targets;
            for (int i = 0; i < PipelineSettings.COLOR_BUFFERS; i++) {
                if (!p.settings.used[i]) continue;
                write(dir.resolve(stage + "-colortex" + i + ".png"), t.readTexture(i), t.bufferWidth(i), t.bufferHeight(i), false);
            }
            write(dir.resolve(stage + "-depthtex0.png"), t.depthTex0, t.width, t.height, true);
            if (p.shadow != null) {
                write(dir.resolve(stage + "-shadowtex0.png"), p.shadow.depth0, p.shadow.size, p.shadow.size, true);
                write(dir.resolve(stage + "-shadowtex1.png"), p.shadow.depth1, p.shadow.size, p.shadow.size, true);
                for (int i = 0; i < 2; i++) if (p.shadow.color[i] != 0) write(dir.resolve(stage + "-shadowcolor" + i + ".png"), p.shadow.color[i], p.shadow.size, p.shadow.size, false);
            }
            Shaders.LOGGER.info("Dumped buffers ({}) to {}", stage, dir);
        } catch (Exception e) {
            Shaders.LOGGER.warn("Buffer dump failed", e);
        }
    }

    private static void write(Path file, int tex, int w, int h, boolean depth) throws Exception {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        FloatBuffer fb = BufferUtils.createFloatBuffer(w * h * 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        if (depth) GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, fb);
        else GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, fb);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        if (depth) {
            for (int i = 0; i < w * h; i++) {
                float d = fb.get(i);
                if (d < 1f) {
                    min = Math.min(min, d);
                    max = Math.max(max, d);
                }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x);
                int r, g, b;
                if (depth) {
                    float d = fb.get(i);
                    float n = d >= 1f ? 1f : (max > min ? (d - min) / (max - min) : 0f);
                    r = g = b = (int) (Math.max(0, Math.min(1, n)) * 255);
                } else {
                    r = (int) (clamp(fb.get(i * 4)) * 255);
                    g = (int) (clamp(fb.get(i * 4 + 1)) * 255);
                    b = (int) (clamp(fb.get(i * 4 + 2)) * 255);
                }
                img.setRGB(x, h - 1 - y, r << 16 | g << 8 | b);
            }
        }
        ImageIO.write(img, "png", file.toFile());
        if (depth) Shaders.LOGGER.info("{}: depth range {} .. {}", file.getFileName(), min, max);
    }

    private static float clamp(float f) {
        return f != f ? 0 : Math.max(0, Math.min(1, f));
    }
}
