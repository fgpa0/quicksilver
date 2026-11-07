package dev.quicksilver.shader.pipeline;

import dev.quicksilver.shader.Shaders;
import dev.quicksilver.shader.util.Mat4;
import dev.quicksilver.shader.ShaderBridge;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/** Renders the shadow map: terrain (opaque, then translucent) seen from the sun or moon. */
final class ShadowPass {
    private ShadowPass() {}

    static int lastSections, lastEntities;

    /** Entities near the camera (OptiFine renders all within the shadow distance). */
    private static void renderEntities(Pipeline p, Minecraft mc, float delta, FrameUniforms f) {
        net.minecraft.client.render.entity.EntityRenderDispatcher d = net.minecraft.client.render.entity.EntityRenderDispatcher.INSTANCE;
        net.minecraft.client.render.entity.EntityRenderDispatcher.field_2490 = f.camX;
        net.minecraft.client.render.entity.EntityRenderDispatcher.field_2491 = f.camY;
        net.minecraft.client.render.entity.EntityRenderDispatcher.field_2492 = f.camZ;
        d.method_1917(mc.level, mc.textureManager, mc.textRenderer, mc.viewEntity, mc.options, delta);
        double range = p.settings.shadowDistance * Math.min(1f, p.settings.entityShadowDistanceMul);
        double r2 = range * range;
        boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        int count = 0;
        dev.quicksilver.shader.render.Hooks.inShadow = true;
        try {
            java.util.List<?> list = mc.level.getEntities();
            for (int i = 0; i < list.size(); i++) {
                net.minecraft.entity.EntityBase e = (net.minecraft.entity.EntityBase) list.get(i);
                if (e == mc.viewEntity && !p.shadowPlayer) continue;
                if (e != mc.viewEntity && !p.shadowEntitiesOther) continue;
                double dx = e.x - f.camX, dy = e.y - f.camY, dz = e.z - f.camZ;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                d.method_1921(e, delta);
                count++;
            }
        } catch (Throwable t) {
            Shaders.LOGGER.debug("shadow entity rendering", t);
        } finally {
            dev.quicksilver.shader.render.Hooks.inShadow = false;
        }
        if (lighting) GL11.glEnable(GL11.GL_LIGHTING);
        else GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1, 1, 1, 1);
        lastEntities = count;
    }
    static long lastNs;

    static void render(Pipeline p, float delta) {
        if (!ShaderBridge.vboActive()) return;
        long t0 = System.nanoTime();
        FrameUniforms f = p.frame;
        f.computeShadowMatrices();
        Minecraft mc = Shaders.mc();
        float[] m = f.shadowMv;
        float d = p.settings.shadowDistance;
        float renderDist = p.settings.shadowDistanceRenderMul > 0 ? d * p.settings.shadowDistanceRenderMul : d;
        float limit = Math.min(d, renderDist);
        ShaderBridge.BoxTest test = (minX, minY, minZ, maxX, maxY, maxZ) -> {
            // project the box into shadow view space (x, y); the orthographic frustum is |x|, |y| <= distance
            float lox = Float.MAX_VALUE, hix = -Float.MAX_VALUE, loy = Float.MAX_VALUE, hiy = -Float.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                double x = (i & 1) == 0 ? minX : maxX, y = (i & 2) == 0 ? minY : maxY, z = (i & 4) == 0 ? minZ : maxZ;
                float sx = (float) (m[0] * x + m[4] * y + m[8] * z + m[12]);
                float sy = (float) (m[1] * x + m[5] * y + m[9] * z + m[13]);
                if (sx < lox) lox = sx;
                if (sx > hix) hix = sx;
                if (sy < loy) loy = sy;
                if (sy > hiy) hiy = sy;
            }
            return hix >= -limit && lox <= limit && hiy >= -limit && loy <= limit;
        };

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadMatrix(Mat4.buffer(f.shadowProj));
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadMatrix(Mat4.buffer(f.shadowMv));
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);

        p.shadow.bindAndClear(p.shadowProgram().drawBuffers);
        p.useShadowProgram();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mc.textureManager.getTextureId("/terrain.png"));
        int n = ShaderBridge.drawShadowTerrain(0, f.camX, f.camY, f.camZ, test);
        if (p.shadowEntities && !Boolean.getBoolean("quicksilver.shaders.noShadowEntities")) renderEntities(p, mc, delta, f);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mc.textureManager.getTextureId("/terrain.png"));
        p.shadow.copyDepth();
        p.shadow.attach(p.shadowProgram().drawBuffers);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        n += ShaderBridge.drawShadowTerrain(1, f.camX, f.camY, f.camZ, test);
        GL11.glDisable(GL11.GL_BLEND);
        p.endShadowProgram();

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
        lastSections = n;
        lastNs = System.nanoTime() - t0;
    }
}
