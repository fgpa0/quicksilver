package dev.quicksilver.mixin.shader;

import dev.quicksilver.shader.render.Hooks;
import net.minecraft.class_556;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(class_556.class)
public abstract class HandRendererMixin {
    /** Screen overlays (fire, inside a block, water) would land in the gbuffers; shader packs draw their own. */
    @Inject(method = "method_1864(F)V", at = @At("HEAD"), cancellable = true)
    private void qsh$overlays(float delta, CallbackInfo ci) {
        if (Hooks.p() != null) ci.cancel();
    }
}
