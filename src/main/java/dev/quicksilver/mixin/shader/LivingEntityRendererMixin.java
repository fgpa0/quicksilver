package dev.quicksilver.mixin.shader;

import dev.quicksilver.shader.pipeline.Pipeline;
import dev.quicksilver.shader.render.Hooks;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelBase;
import net.minecraft.entity.Living;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hurt flash and creeper flash: vanilla draws the model a second time, blended, which would overwrite the gbuffers.
 * With shaders the colour goes into the entityColor uniform instead (like OptiFine).
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Shadow
    protected int method_817(Living e, float brightness, float delta) {
        return 0;
    }

    @Inject(method = "method_822(Lnet/minecraft/entity/Living;DDDFF)V", at = @At("HEAD"))
    private void qsh$entityColor(Living e, double x, double y, double z, float yaw, float delta, CallbackInfo ci) {
        Pipeline p = Hooks.p();
        if (p == null) return;
        if (e.hurtTime > 0 || e.deathTime > 0) p.frame_entityColor(1f, 0f, 0f, 0.3f);
        else {
            int c = method_817(e, 1f, delta);
            int a = c >>> 24;
            if (a > 0) p.frame_entityColor((c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, a / 255f);
            else p.frame_entityColor(0, 0, 0, 0);
        }
        p.refreshUniforms();
    }

    @Inject(method = "method_822(Lnet/minecraft/entity/Living;DDDFF)V", at = @At("RETURN"))
    private void qsh$entityColorEnd(Living e, double x, double y, double z, float yaw, float delta, CallbackInfo ci) {
        Pipeline p = Hooks.p();
        if (p == null) return;
        p.frame_entityColor(0, 0, 0, 0);
    }

    private static final String RENDER = "Lnet/minecraft/client/render/entity/model/EntityModelBase;render(FFFFFF)V";

    @Redirect(method = "method_822(Lnet/minecraft/entity/Living;DDDFF)V", at = @At(value = "INVOKE", target = RENDER, ordinal = 2))
    private void qsh$skipOverlay1(EntityModelBase m, float a, float b, float c, float d, float e, float f) {
        if (Hooks.p() == null) m.render(a, b, c, d, e, f);
    }

    @Redirect(method = "method_822(Lnet/minecraft/entity/Living;DDDFF)V", at = @At(value = "INVOKE", target = RENDER, ordinal = 3))
    private void qsh$skipOverlay2(EntityModelBase m, float a, float b, float c, float d, float e, float f) {
        if (Hooks.p() == null) m.render(a, b, c, d, e, f);
    }

    @Redirect(method = "method_822(Lnet/minecraft/entity/Living;DDDFF)V", at = @At(value = "INVOKE", target = RENDER, ordinal = 4))
    private void qsh$skipOverlay3(EntityModelBase m, float a, float b, float c, float d, float e, float f) {
        if (Hooks.p() == null) m.render(a, b, c, d, e, f);
    }

    @Redirect(method = "method_822(Lnet/minecraft/entity/Living;DDDFF)V", at = @At(value = "INVOKE", target = RENDER, ordinal = 5))
    private void qsh$skipOverlay4(EntityModelBase m, float a, float b, float c, float d, float e, float f) {
        if (Hooks.p() == null) m.render(a, b, c, d, e, f);
    }
}
