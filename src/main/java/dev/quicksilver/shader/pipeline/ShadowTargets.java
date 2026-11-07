package dev.quicksilver.shader.pipeline;

import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Shadow map textures: shadowtex0 (all), shadowtex1 (without translucents), shadowcolor0/1. */
public final class ShadowTargets {
    public final PipelineSettings settings;
    public final int size;
    public int depth0, depth1;
    public final int[] color = new int[2];
    int fbo, copyRead, copyDraw;
    /** colour attachments currently bound to the fbo */
    private int[] attached = new int[0];

    public ShadowTargets(PipelineSettings settings) {
        this.settings = settings;
        this.size = Math.max(16, Math.min(16384, settings.shadowMapResolution));
        depth0 = RenderTargets.createDepth(size, size);
        depth1 = RenderTargets.createDepth(size, size);
        for (int i = 0; i < 2; i++) {
            if (!settings.shadowColorUsed[i]) continue;
            Formats.Format f = settings.shadowColorFormat[i] != null ? settings.shadowColorFormat[i] : Formats.DEFAULT;
            color[i] = RenderTargets.createColor(f, size, size, false);
        }
        fbo = GL30.glGenFramebuffers();
        copyRead = GL30.glGenFramebuffers();
        copyDraw = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth0, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private final FloatBuffer clearBuf = BufferUtils.createFloatBuffer(4);

    /** Binds the shadow framebuffer with the given shadowcolor buffers as draw buffers and clears it. */
    public void bindAndClear(int[] drawBuffers) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        attach(drawBuffers);
        GL11.glViewport(0, 0, size, size);
        GL11.glDepthMask(true);
        GL11.glColorMask(true, true, true, true);
        for (int k = 0; k < attached.length; k++) {
            int b = attached[k];
            if (!settings.shadowColorClear[b]) continue;
            float[] c = settings.shadowColorClearColor[b] != null ? settings.shadowColorClearColor[b] : new float[]{1, 1, 1, 1};
            clearBuf.clear();
            clearBuf.put(c).flip();
            GL30.glClearBuffer(GL11.GL_COLOR, k, clearBuf);
        }
        GL11.glClearDepth(1.0);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
    }

    public void attach(int[] drawBuffers) {
        int n = 0;
        for (int b : drawBuffers) if (b >= 0 && b < 2 && color[b] != 0) n++;
        int[] want = new int[n];
        n = 0;
        for (int b : drawBuffers) if (b >= 0 && b < 2 && color[b] != 0) want[n++] = b;
        if (!java.util.Arrays.equals(want, attached)) {
            for (int k = 0; k < Math.max(want.length, attached.length); k++) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + k, GL11.GL_TEXTURE_2D, k < want.length ? color[want[k]] : 0, 0);
            }
            attached = want;
        }
        if (want.length == 0) {
            GL11.glDrawBuffer(GL11.GL_NONE);
        } else {
            java.nio.IntBuffer ib = BufferUtils.createIntBuffer(want.length);
            for (int k = 0; k < want.length; k++) ib.put(GL30.GL_COLOR_ATTACHMENT0 + k);
            ib.flip();
            GL20.glDrawBuffers(ib);
        }
    }

    /** shadowtex1 = shadowtex0 (call between the opaque and translucent shadow passes). */
    public void copyDepth() {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, copyRead);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth0, 0);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyDraw);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth1, 0);
        GL11.glReadBuffer(GL11.GL_NONE);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL30.glBlitFramebuffer(0, 0, size, size, 0, 0, size, size, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
    }

    public void destroy() {
        GL11.glDeleteTextures(depth0);
        GL11.glDeleteTextures(depth1);
        for (int c : color) if (c != 0) GL11.glDeleteTextures(c);
        GL30.glDeleteFramebuffers(fbo);
        GL30.glDeleteFramebuffers(copyRead);
        GL30.glDeleteFramebuffers(copyDraw);
    }
}
