package dev.quicksilver.shader.render;

import dev.quicksilver.shader.Shaders;
import dev.quicksilver.shader.pipeline.Pipeline;
import dev.quicksilver.shader.pipeline.Program;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityBase;
import net.minecraft.level.Level;
import net.minecraft.level.LightType;
import net.minecraft.util.maths.MathHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/** Called from the render mixins: program switching per render phase, light map coordinates of entities. */
public final class Hooks {
    private Hooks() {}

    /** Inside a shader world frame (main thread): brightness lookups return full brightness. */
    public static boolean inFrame;
    /** rendering the shadow map: render phases must not switch programs */
    public static boolean inShadow;
    /** current phase program name (null = none) */
    private static String phase;
    private static int depth;

    /**
     * Entity models are compiled into display lists once, so normals must be right whenever shaders are wanted,
     * also before the first shader frame (title screen, pack still loading).
     */
    public static boolean fixNormals() {
        return inFrame || !Shaders.config().pack.isEmpty();
    }

    public static Pipeline p() {
        return inFrame ? Shaders.pipeline() : null;
    }

    // ------------------------------------------------------------------ frame

    public static void beginFrame() {
        inFrame = false;
        try {
            Shaders.tick();
        } catch (Throwable t) {
            Shaders.fail("pack loading", t);
            return;
        }
        Pipeline pl = Shaders.pipeline();
        Minecraft mc = Shaders.mc();
        if (pl == null || mc == null || mc.options.anaglyph3d) return;
        try {
            pl.beginFrame(mc.actualWidth, mc.actualHeight);
            inFrame = true;
            phase = null;
            depth = 0;
        } catch (Throwable t) {
            Shaders.fail("frame start", t);
        }
    }

    public static void fogColor(float r, float g, float b) {
        Pipeline pl = Shaders.pipeline();
        if (pl != null) pl.frame.setFog(r, g, b);
    }

    /** Replaces vanilla's glClear at the start of the frame; returns false to let vanilla clear. */
    public static boolean clear(float r, float g, float b) {
        Pipeline pl = p();
        if (pl == null) return false;
        pl.clear(r, g, b);
        return true;
    }

    public static void afterCamera(float delta) {
        Pipeline pl = p();
        if (pl == null) return;
        try {
            pl.afterCamera(delta);
            // light map coordinates: gl_TextureMatrix[1] maps 0..240 to the light map texel centres like vanilla 1.7
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glLoadIdentity();
            GL11.glScalef(1f / 256f, 1f / 256f, 1f / 256f);
            GL11.glTranslatef(8f, 8f, 8f);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            setLight(0, 15);
        } catch (Throwable t) {
            inFrame = false;
            Shaders.fail("shadow pass", t);
        }
    }

    public static void endFrame() {
        Pipeline pl = p();
        inFrame = false;
        if (pl == null) return;
        try {
            pl.endFrame();
        } catch (Throwable t) {
            Shaders.fail("composite", t);
        }
    }

    // ------------------------------------------------------------------ phases

    /** Switch to the gbuffers program for a render phase (nested phases are restored by endPhase). */
    public static void begin(String program) {
        if (inShadow) return;
        Pipeline pl = p();
        if (pl == null) return;
        depth++;
        if (program.equals(phase)) return;
        phase = program;
        pl.useGbuffers(pl.program(program));
    }

    private static final java.util.ArrayDeque<String> STACK = new java.util.ArrayDeque<>();

    public static void push(String program) {
        if (inShadow) return;
        if (p() == null) return;
        STACK.push(phase == null ? "" : phase);
        begin(program);
    }

    public static void pop() {
        if (inShadow) return;
        Pipeline pl = p();
        if (pl == null) return;
        String prev = STACK.isEmpty() ? "" : STACK.pop();
        depth = Math.max(0, depth - 1);
        if (prev.isEmpty()) {
            phase = null;
            pl.useGbuffers(null);
        } else if (!prev.equals(phase)) {
            phase = prev;
            pl.useGbuffers(pl.program(prev));
        }
    }

    public static void end() {
        if (inShadow) return;
        Pipeline pl = p();
        if (pl == null) return;
        depth = Math.max(0, depth - 1);
        phase = null;
        pl.useGbuffers(null);
    }

    public static boolean inPhase(String program) {
        return program.equals(phase);
    }

    public static void beforeTranslucent() {
        Pipeline pl = p();
        if (pl != null) pl.beforeTranslucent();
    }

    public static void beforeHand() {
        Pipeline pl = p();
        if (pl != null) pl.beforeHand();
    }

    // ------------------------------------------------------------------ lighting of entities etc.

    /** Light map coordinates for non-terrain geometry: (block, sky) light 0..15. */
    public static void setLight(int block, int sky) {
        GL13.glMultiTexCoord2f(GL13.GL_TEXTURE1, block * 16f, sky * 16f);
    }

    public static void setLightAt(Level level, double x, double y, double z) {
        int bx = MathHelper.floor(x), by = MathHelper.floor(y), bz = MathHelper.floor(z);
        if (by >= 128) {
            setLight(0, 15);
            return;
        }
        if (by < 0) by = 0;
        int sky = level.method_164(LightType.field_2757, bx, by, bz);
        int blk = level.method_164(LightType.field_2758, bx, by, bz);
        setLight(blk, sky);
    }

    public static void entity(EntityBase e, float delta) {
        Pipeline pl = p();
        if (pl == null) return;
        double x = e.prevRenderX + (e.x - e.prevRenderX) * delta;
        double y = e.prevRenderY + (e.y - e.prevRenderY) * delta + e.standingEyeHeight;
        double z = e.prevRenderZ + (e.z - e.prevRenderZ) * delta;
        setLightAt(e.level, x, y, z);
        boolean lightning = e instanceof net.minecraft.entity.Lightning;
        if (lightning) begin("gbuffers_lightning");
        pl.frame_entityId(EntityIds.of(e, pl));
        pl.refreshUniforms();
    }

    public static void entityDone(EntityBase e) {
        Pipeline pl = p();
        if (pl == null) return;
        if (e instanceof net.minecraft.entity.Lightning) begin("gbuffers_entities");
        pl.frame_entityId(-1);
    }

    public static Program current() {
        Pipeline pl = p();
        return pl == null ? null : pl.program(phase == null ? "" : phase);
    }
}
