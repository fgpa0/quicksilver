package dev.quicksilver.mixin.shader;

import dev.quicksilver.shader.pipeline.Pipeline;
import dev.quicksilver.shader.render.Hooks;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Living;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    // ---- sky: untextured parts use gbuffers_skybasic, sun and moon gbuffers_skytextured

    @Inject(method = "renderSky(F)V", at = @At("HEAD"))
    private void qsh$sky(float delta, CallbackInfo ci) {
        Hooks.begin("gbuffers_skybasic");
    }

    @Inject(method = "renderSky(F)V", at = @At("RETURN"))
    private void qsh$skyEnd(float delta, CallbackInfo ci) {
        Hooks.end();
    }

    @Redirect(method = "renderSky(F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V", remap = false))
    private void qsh$skyEnable(int cap) {
        GL11.glEnable(cap);
        if (cap == GL11.GL_TEXTURE_2D && Hooks.p() != null) Hooks.begin("gbuffers_skytextured");
    }

    @Redirect(method = "renderSky(F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V", remap = false))
    private void qsh$skyDisable(int cap) {
        GL11.glDisable(cap);
        if (cap == GL11.GL_TEXTURE_2D && Hooks.p() != null) Hooks.begin("gbuffers_skybasic");
    }

    /** With shaders the sun takes the modern east-west path (rotated like the shadow camera), tilted by sunPathRotation. */
    @Redirect(method = "renderSky(F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V", ordinal = 2, remap = false))
    private void qsh$sunPath(float angle, float x, float y, float z) {
        Pipeline p = Hooks.p();
        if (p != null) {
            GL11.glRotatef(-90f, 0f, 1f, 0f);
            GL11.glRotatef(p.settings.sunPathRotation, 0f, 0f, 1f);
        } else {
            GL11.glRotatef(angle, x, y, z);
        }
    }

    // ---- terrain

    @Inject(method = "method_1548(Lnet/minecraft/entity/Living;ID)I", at = @At("HEAD"))
    private void qsh$terrain(Living viewer, int pass, double delta, CallbackInfoReturnable<Integer> cir) {
        if (Hooks.p() == null) return;
        if (pass == 1) {
            Hooks.beforeTranslucent();
            Hooks.begin("gbuffers_water");
        } else {
            Hooks.begin("gbuffers_terrain");
        }
    }

    @Inject(method = "method_1548(Lnet/minecraft/entity/Living;ID)I", at = @At("RETURN"))
    private void qsh$terrainEnd(Living viewer, int pass, double delta, CallbackInfoReturnable<Integer> cir) {
        Hooks.end();
    }

    @Inject(method = "method_1540(ID)V", at = @At("HEAD"))
    private void qsh$water(int pass, double delta, CallbackInfo ci) {
        Hooks.begin(pass == 1 ? "gbuffers_water" : "gbuffers_terrain");
    }

    @Inject(method = "method_1540(ID)V", at = @At("RETURN"))
    private void qsh$waterEnd(int pass, double delta, CallbackInfo ci) {
        Hooks.end();
    }

    // ---- entities and block entities

    @Inject(method = "method_1544", at = @At("HEAD"))
    private void qsh$entities(CallbackInfo ci) {
        Hooks.begin("gbuffers_entities");
    }

    @Inject(method = "method_1544", at = @At("RETURN"))
    private void qsh$entitiesEnd(CallbackInfo ci) {
        Hooks.end();
    }

    // ---- clouds

    @Inject(method = "method_1552(F)V", at = @At("HEAD"), cancellable = true)
    private void qsh$clouds(float delta, CallbackInfo ci) {
        Pipeline p = Hooks.p();
        if (p == null) return;
        if (!p.vanillaClouds) {
            ci.cancel();
            return;
        }
        Hooks.begin("gbuffers_clouds");
    }

    @Inject(method = "method_1552(F)V", at = @At("RETURN"))
    private void qsh$cloudsEnd(float delta, CallbackInfo ci) {
        Hooks.end();
    }

    // ---- block breaking and selection outline

    @Inject(method = "method_1547", at = @At("HEAD"))
    private void qsh$breaking(CallbackInfo ci) {
        Hooks.begin("gbuffers_damagedblock");
    }

    @Inject(method = "method_1547", at = @At("RETURN"))
    private void qsh$breakingEnd(CallbackInfo ci) {
        Hooks.end();
    }

    @Inject(method = "method_1554", at = @At("HEAD"))
    private void qsh$outline(CallbackInfo ci) {
        Hooks.begin("gbuffers_basic");
    }

    @Inject(method = "method_1554", at = @At("RETURN"))
    private void qsh$outlineEnd(CallbackInfo ci) {
        Hooks.end();
    }
}
