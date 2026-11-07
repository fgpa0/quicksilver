package dev.quicksilver.shader.pipeline;

import dev.quicksilver.shader.Shaders;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * The colour buffers (colortex0-15, each with two textures for ping-pong between composite passes) and depth textures
 * (depthtex0 = everything, depthtex1 = before translucents, depthtex2 = before the hand).
 * Which texture of a buffer is current persists across frames, so buffers that aren't cleared keep their content
 * without copying.
 */
public final class RenderTargets {
    public final PipelineSettings settings;
    public int width, height;
    final int[][] tex = new int[PipelineSettings.COLOR_BUFFERS][2];
    /** index of the current ("main") texture of each buffer */
    final int[] cur = new int[PipelineSettings.COLOR_BUFFERS];
    public int depthTex0, depthTex1, depthTex2;
    private int clearFbo, copyFboRead, copyFboDraw;
    /** bumped whenever buffer textures are re-created or flipped (FBOs re-attach) */
    int version;
    final boolean[] mipDirty = new boolean[PipelineSettings.COLOR_BUFFERS];

    public RenderTargets(PipelineSettings settings) {
        this.settings = settings;
    }

    public int bufferWidth(int i) {
        return Math.max(1, (int) (width * settings.scaleX[i]));
    }

    public int bufferHeight(int i) {
        return Math.max(1, (int) (height * settings.scaleY[i]));
    }

    public void ensureSize(int w, int h) {
        if (w == width && h == height && depthTex0 != 0) return;
        destroy();
        width = w;
        height = h;
        for (int i = 0; i < PipelineSettings.COLOR_BUFFERS; i++) {
            if (!settings.used[i]) continue;
            Formats.Format f = settings.format[i] != null ? settings.format[i] : Formats.DEFAULT;
            for (int k = 0; k < 2; k++) tex[i][k] = createColor(f, bufferWidth(i), bufferHeight(i), settings.mipmap[i]);
            cur[i] = 0;
        }
        depthTex0 = createDepth(w, h);
        depthTex1 = createDepth(w, h);
        depthTex2 = createDepth(w, h);
        clearFbo = GL30.glGenFramebuffers();
        copyFboRead = GL30.glGenFramebuffers();
        copyFboDraw = GL30.glGenFramebuffers();
        version++;
        // fresh buffers start out cleared
        for (int i = 0; i < PipelineSettings.COLOR_BUFFERS; i++) {
            if (!settings.used[i]) continue;
            for (int k = 0; k < 2; k++) clearTexture(tex[i][k], i, defaultClear(i));
        }
        Shaders.LOGGER.info("Render targets {}x{}", w, h);
    }

    static int createColor(Formats.Format f, int w, int h, boolean mipmap) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        boolean integer = Formats.isInteger(f);
        int filter = integer ? GL11.GL_NEAREST : GL11.GL_LINEAR;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, mipmap && !integer ? GL11.GL_LINEAR_MIPMAP_LINEAR : filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, f.internal(), w, h, 0, f.format(), f.type(), (ByteBuffer) null);
        if (mipmap && !integer) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return t;
    }

    static int createDepth(int w, int h) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_DEPTH_TEXTURE_MODE, GL11.GL_LUMINANCE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, w, h, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (FloatBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return t;
    }

    float[] defaultClear(int i) {
        if (settings.clearColor[i] != null) return settings.clearColor[i];
        return i == 1 ? ONE : ZERO;
    }

    private static final float[] ZERO = {0, 0, 0, 0}, ONE = {1, 1, 1, 1};
    private final FloatBuffer clearBuf = BufferUtils.createFloatBuffer(4);

    void clearTexture(int texture, int buffer, float[] color) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, clearFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glViewport(0, 0, bufferWidth(buffer), bufferHeight(buffer));
        clearBuf.clear();
        clearBuf.put(color).flip();
        Formats.Format f = settings.format[buffer] != null ? settings.format[buffer] : Formats.DEFAULT;
        if (Formats.isInteger(f)) {
            java.nio.IntBuffer ib = BufferUtils.createIntBuffer(4);
            ib.put(new int[]{(int) color[0], (int) color[1], (int) color[2], (int) color[3]}).flip();
            GL30.glClearBuffer(GL11.GL_COLOR, 0, ib);
        } else {
            GL30.glClearBuffer(GL11.GL_COLOR, 0, clearBuf);
        }
    }

    /**
     * Frame start: clear the buffers that want it (colortex0 with the fog colour unless set), and the depth.
     */
    public void clearForFrame(float[] fogColor) {
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glColorMask(true, true, true, true);
        for (int i = 0; i < PipelineSettings.COLOR_BUFFERS; i++) {
            if (!settings.used[i] || !settings.clear[i]) continue;
            float[] c = settings.clearColor[i] != null ? settings.clearColor[i] : (i == 0 ? fogColor : defaultClear(i));
            clearTexture(tex[i][cur[i]], i, c);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, clearFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex0, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glViewport(0, 0, width, height);
        GL11.glDepthMask(true);
        GL11.glClearDepth(1.0);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
        if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
    }

    /** Texture the gbuffers and composite passes read for buffer i. */
    public int readTexture(int i) {
        return tex[i][cur[i]];
    }

    /** Texture a composite pass writes for buffer i (the other one). */
    public int altTexture(int i) {
        return tex[i][cur[i] ^ 1];
    }

    public void flip(int i) {
        cur[i] ^= 1;
        version++;
    }

    /** Copies depthtex0 into depthtex1 or depthtex2. */
    public void copyDepth(int dst) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, copyFboRead);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex0, 0);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFboDraw);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, dst, 0);
        GL11.glReadBuffer(GL11.GL_NONE);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    public void destroy() {
        for (int i = 0; i < PipelineSettings.COLOR_BUFFERS; i++) {
            for (int k = 0; k < 2; k++) {
                if (tex[i][k] != 0) GL11.glDeleteTextures(tex[i][k]);
                tex[i][k] = 0;
            }
        }
        if (depthTex0 != 0) {
            GL11.glDeleteTextures(depthTex0);
            GL11.glDeleteTextures(depthTex1);
            GL11.glDeleteTextures(depthTex2);
            GL30.glDeleteFramebuffers(clearFbo);
            GL30.glDeleteFramebuffers(copyFboRead);
            GL30.glDeleteFramebuffers(copyFboDraw);
        }
        depthTex0 = depthTex1 = depthTex2 = 0;
        width = height = 0;
    }
}
