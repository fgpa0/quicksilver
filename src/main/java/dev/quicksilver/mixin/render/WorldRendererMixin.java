package dev.quicksilver.mixin.render;

import dev.quicksilver.QsConfig;
import dev.quicksilver.io.RegionIO;
import dev.quicksilver.render.ChunkScheduler;
import dev.quicksilver.render.MeshWorkers;
import dev.quicksilver.world.ChunkLoadManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Living;
import net.minecraft.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "method_1549(Lnet/minecraft/entity/Living;Z)Z", at = @At("HEAD"), cancellable = true)
    private void qs$updateRenderers(Living viewer, boolean all, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(ChunkScheduler.update((WorldRenderer) (Object) this, viewer));
    }

    @Inject(method = "method_1537()V", at = @At("HEAD"))
    private void qs$reload(CallbackInfo ci) {
        ChunkScheduler.onReload();
        qs$builtDistance = dev.quicksilver.client.RenderDistance.chunks();
    }

    @org.spongepowered.asm.mixin.Unique private int qs$builtDistance = -1;

    /** Section grid size follows the render distance in chunks: diameter = 2 * chunks + 1 sections. */
    @org.spongepowered.asm.mixin.injection.ModifyConstant(method = "method_1537()V", constant = @org.spongepowered.asm.mixin.injection.Constant(intValue = 64))
    private int qs$noShift(int v) {
        return 4096;
    }

    @org.spongepowered.asm.mixin.injection.ModifyConstant(method = "method_1537()V", constant = @org.spongepowered.asm.mixin.injection.Constant(intValue = 400))
    private int qs$distanceBlocks(int v) {
        return dev.quicksilver.client.RenderDistance.chunks() * 32;
    }

    @Inject(method = "method_1548(Lnet/minecraft/entity/Living;ID)I", at = @At("HEAD"))
    private void qs$applyDistance(Living viewer, int pass, double delta, CallbackInfoReturnable<Integer> cir) {
        if (pass == 0 && qs$builtDistance != -1 && qs$builtDistance != dev.quicksilver.client.RenderDistance.chunks()
                && !org.lwjgl.input.Mouse.isButtonDown(0)) {
            ((WorldRenderer) (Object) this).method_1537();
        }
    }

    @Inject(method = "method_1546(Lnet/minecraft/level/Level;)V", at = @At("HEAD"))
    private void qs$setLevel(Level level, CallbackInfo ci) {
        ChunkScheduler.onReload();
    }

    @Inject(method = "method_1548(Lnet/minecraft/entity/Living;ID)I", at = @At("HEAD"))
    private void qs$cull(Living viewer, int pass, double delta, CallbackInfoReturnable<Integer> cir) {
        if (pass != 0) return;
        double x = viewer.prevRenderX + (viewer.x - viewer.prevRenderX) * delta;
        double y = viewer.prevRenderY + (viewer.y - viewer.prevRenderY) * delta;
        double z = viewer.prevRenderZ + (viewer.z - viewer.prevRenderZ) * delta;
        dev.quicksilver.render.CaveCuller.update((WorldRenderer) (Object) this, x, y, z);
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "method_1548(Lnet/minecraft/entity/Living;ID)I",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/options/GameOptions;advancedOpengl:Z"))
    private boolean qs$noOcclusionQueries(net.minecraft.client.options.GameOptions options) {
        if (QsConfig.disableOcclusionQueries || QsConfig.caveCulling) return false;
        return options.advancedOpengl;
    }

    @org.spongepowered.asm.mixin.Shadow private net.minecraft.client.Minecraft client;

    @org.spongepowered.asm.mixin.Unique private float qs$savedFogStart = -1, qs$savedFogEnd;

    /**
     * The sky plane is fogged with the same linear fog as the terrain, which is what blends the horizon into the sky.
     * Vanilla's fog is at most 256 blocks; with a longer fog distance that blend squeezes into a thin band and the
     * horizon looks like a hard line. The sky keeps the vanilla-sized blend however far the terrain fog is.
     */
    @Inject(method = "renderSky(F)V", at = @At("HEAD"))
    private void qs$skyFogStart(float delta, CallbackInfo ci) {
        qs$savedFogStart = -1;
        if (org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_FOG_MODE) != org.lwjgl.opengl.GL11.GL_LINEAR) return;
        float end = org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_FOG_END);
        if (end <= 256f) return;
        qs$savedFogStart = org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_FOG_START);
        qs$savedFogEnd = end;
        org.lwjgl.opengl.GL11.glFogf(org.lwjgl.opengl.GL11.GL_FOG_START, 64f);
        org.lwjgl.opengl.GL11.glFogf(org.lwjgl.opengl.GL11.GL_FOG_END, 256f);
    }

    @Inject(method = "renderSky(F)V", at = @At("RETURN"))
    private void qs$skyFogEnd(float delta, CallbackInfo ci) {
        if (qs$savedFogStart < 0) return;
        org.lwjgl.opengl.GL11.glFogf(org.lwjgl.opengl.GL11.GL_FOG_START, qs$savedFogStart);
        org.lwjgl.opengl.GL11.glFogf(org.lwjgl.opengl.GL11.GL_FOG_END, qs$savedFogEnd);
        qs$savedFogStart = -1;
    }

    /** VBO renderer: draw a whole pass with one multi-draw per region. */
    @Inject(method = "method_1542(IIID)I", at = @At("HEAD"), cancellable = true)
    private void qs$vboPass(int from, int to, int pass, double delta, CallbackInfoReturnable<Integer> cir) {
        if (!dev.quicksilver.render.vbo.VboRenderer.active()) return;
        Living v = client.viewEntity;
        double x = v.prevRenderX + (v.x - v.prevRenderX) * delta;
        double y = v.prevRenderY + (v.y - v.prevRenderY) * delta;
        double z = v.prevRenderZ + (v.z - v.prevRenderZ) * delta;
        int drawn = dev.quicksilver.render.vbo.VboRenderer.render(pass, x, y, z);
        if (pass == 0 && client.options.debugHud) {
            // same breakdown as vanilla's F3 (only computed while the F3 screen is open: it walks every section) "C: rendered/total. F: frustum, O: occluded, E: empty"
            int t = 0, e = 0, f = 0, o = 0, r = 0;
            if (field_1809 != null) for (net.minecraft.class_66 c : field_1809) {
                t++;
                if (c.field_244[0]) e++;
                else if (!c.field_243) f++;
                else if (!dev.quicksilver.render.CaveCuller.isVisible(c)) o++;
                else r++;
            }
            field_1787 = t;
            field_1788 = f;
            field_1789 = o;
            field_1790 = r;
            field_1791 = e;
        }
        cir.setReturnValue(drawn);
    }

    @org.spongepowered.asm.mixin.Shadow private int field_1804;

    /**
     * @author Quicksilver
     * @reason batch light-only rebuilds of distant sections (see LightRebuilds)
     */
    @org.spongepowered.asm.mixin.Overwrite
    public void method_1149(int x, int y, int z) {
        Living v = client.viewEntity;
        if (v == null) {
            ((WorldRenderer) (Object) this).method_1543(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);
            return;
        }
        dev.quicksilver.render.LightRebuilds.lightChanged((WorldRenderer) (Object) this, v.x, v.y, v.z, x, y, z);
    }

    /** Hierarchical (region, then section) frustum culling instead of testing every section. */
    @Inject(method = "method_1550(Lnet/minecraft/class_68;F)V", at = @At("HEAD"), cancellable = true)
    private void qs$fastFrustum(net.minecraft.class_68 frustum, float delta, CallbackInfo ci) {
        if (field_1809 == null || !dev.quicksilver.render.FastFrustum.load(frustum)) return;
        dev.quicksilver.render.FastFrustum.update(field_1809);
        field_1804++;
        ci.cancel();
    }

    @org.spongepowered.asm.mixin.Shadow private int field_1790;
    @org.spongepowered.asm.mixin.Shadow private int field_1787;
    @org.spongepowered.asm.mixin.Shadow private int field_1788;
    @org.spongepowered.asm.mixin.Shadow private int field_1789;
    @org.spongepowered.asm.mixin.Shadow private int field_1791;
    @org.spongepowered.asm.mixin.Shadow(aliases = "field_1809") private net.minecraft.class_66[] field_1809;

    @Inject(method = "method_1540(ID)V", at = @At("HEAD"), cancellable = true)
    private void qs$vboRedraw(int pass, double delta, CallbackInfo ci) {
        if (!dev.quicksilver.render.vbo.VboRenderer.active()) return;
        Living v = client.viewEntity;
        double x = v.prevRenderX + (v.x - v.prevRenderX) * delta;
        double y = v.prevRenderY + (v.y - v.prevRenderY) * delta;
        double z = v.prevRenderZ + (v.z - v.prevRenderZ) * delta;
        dev.quicksilver.render.vbo.VboRenderer.render(pass, x, y, z);
        ci.cancel();
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "method_1542(IIID)I",
            at = @At(value = "FIELD", target = "Lnet/minecraft/class_66;field_243:Z"))
    private boolean qs$visible(net.minecraft.class_66 section) {
        return section.field_243 && dev.quicksilver.render.CaveCuller.isVisible(section);
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "method_1544(Lnet/minecraft/util/maths/Vec3f;Lnet/minecraft/class_68;F)V",
            at = @At(value = "INVOKE", ordinal = 1,
                    target = "Lnet/minecraft/client/render/entity/EntityRenderDispatcher;method_1921(Lnet/minecraft/entity/EntityBase;F)V"))
    private void qs$cullEntity(net.minecraft.client.render.entity.EntityRenderDispatcher d, net.minecraft.entity.EntityBase e, float delta) {
        net.minecraft.util.maths.Box b = e.boundingBox;
        if (b == null || dev.quicksilver.render.CaveCuller.isBoxVisible(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)) {
            d.method_1921(e, delta);
        }
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "method_1544(Lnet/minecraft/util/maths/Vec3f;Lnet/minecraft/class_68;F)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/entity/TileEntityRenderDispatcher;method_1278(Lnet/minecraft/tileentity/TileEntityBase;F)V"))
    private void qs$cullTileEntity(net.minecraft.client.render.entity.TileEntityRenderDispatcher d, net.minecraft.tileentity.TileEntityBase te, float delta) {
        if (dev.quicksilver.render.CaveCuller.isBlockVisible(te.x, te.y, te.z)) d.method_1278(te, delta);
    }

    /** The distance-sorted section array is only used by vanilla's display-list path; sorting ~34k sections every 4 blocks is a hitch. */
    @org.spongepowered.asm.mixin.injection.Redirect(method = "method_1548(Lnet/minecraft/entity/Living;ID)I",
            at = @At(value = "INVOKE", target = "Ljava/util/Arrays;sort([Ljava/lang/Object;Ljava/util/Comparator;)V", remap = false))
    private void qs$skipSort(Object[] a, java.util.Comparator c) {
        if (!dev.quicksilver.render.vbo.VboRenderer.active()) java.util.Arrays.sort(a, c);
    }

    /**
     * Vanilla rebuilds every lit section when the sky darkens or brightens (time of day). Meshes in the shader format
     * store raw sky light (shaders apply the time of day themselves), so there is nothing to rebuild.
     */
    @Inject(method = "method_1148()V", at = @At("HEAD"), cancellable = true)
    private void qs$skyDarkening(CallbackInfo ci) {
        if (dev.quicksilver.render.vbo.VboRenderer.active()
                && (dev.quicksilver.shader.ShaderBridge.extended() || dev.quicksilver.render.vbo.TerrainShader.enabled())) ci.cancel();
    }

    @Inject(method = "getDebugSecondLine()Ljava/lang/String;", at = @At("RETURN"), cancellable = true)
    private void qs$debug(CallbackInfoReturnable<String> cir) {
        if (!QsConfig.debugInfo) return;
        cir.setReturnValue(cir.getReturnValue() + String.format(" | QS mesh: %d thr, q %d, up %d, sync %d, %.1fms/build | cull %d/%d | vbo %d/%d draws, %dk verts, %dMB | load: -%d, io w%d p%d",
                MeshWorkers.threadCount(), ChunkScheduler.statQueued, ChunkScheduler.statUploads, ChunkScheduler.statSync,
                ChunkScheduler.avgBuildMs, dev.quicksilver.render.CaveCuller.statVisible, dev.quicksilver.render.CaveCuller.statTotal,
                dev.quicksilver.render.vbo.VboRenderer.statDraws, dev.quicksilver.render.vbo.VboRenderer.statRegions, dev.quicksilver.render.vbo.VboRenderer.statVertices / 1000,
                dev.quicksilver.render.vbo.VboRenderer.memoryBytes() >> 20, ChunkLoadManager.missingInRadius, RegionIO.queuedWrites(), RegionIO.prefetchedCount())
                + " | vsync " + dev.quicksilver.client.MonitorSync.describe());
    }
}
