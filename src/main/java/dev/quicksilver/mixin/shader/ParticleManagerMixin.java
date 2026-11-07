package dev.quicksilver.mixin.shader;

import dev.quicksilver.shader.render.Hooks;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.entity.EntityBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {
    @Inject(method = "method_327(Lnet/minecraft/entity/EntityBase;F)V", at = @At("HEAD"))
    private void qsh$lit(EntityBase viewer, float delta, CallbackInfo ci) {
        if (Hooks.p() == null) return;
        Hooks.begin("gbuffers_textured_lit");
        Hooks.setLightAt(viewer.level, viewer.x, viewer.y, viewer.z);
    }

    @Inject(method = "method_327(Lnet/minecraft/entity/EntityBase;F)V", at = @At("RETURN"))
    private void qsh$litEnd(EntityBase viewer, float delta, CallbackInfo ci) {
        Hooks.end();
    }

    @Inject(method = "method_324(Lnet/minecraft/entity/EntityBase;F)V", at = @At("HEAD"))
    private void qsh$unlit(EntityBase viewer, float delta, CallbackInfo ci) {
        if (Hooks.p() == null) return;
        Hooks.begin("gbuffers_textured_lit");
        Hooks.setLightAt(viewer.level, viewer.x, viewer.y, viewer.z);
    }

    @Inject(method = "method_324(Lnet/minecraft/entity/EntityBase;F)V", at = @At("RETURN"))
    private void qsh$unlitEnd(EntityBase viewer, float delta, CallbackInfo ci) {
        Hooks.end();
    }
}
