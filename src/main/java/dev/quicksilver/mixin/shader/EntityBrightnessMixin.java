package dev.quicksilver.mixin.shader;

import dev.quicksilver.shader.render.Hooks;
import net.minecraft.entity.EntityBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** With shaders, light comes from the light map coordinates; vertex colours must not be darkened by vanilla's brightness. */
@Mixin(EntityBase.class)
public abstract class EntityBrightnessMixin {
    @Inject(method = "getBrightnessAtEyes(F)F", at = @At("HEAD"), cancellable = true)
    private void qsh$fullBright(float delta, CallbackInfoReturnable<Float> cir) {
        if (Hooks.inFrame) cir.setReturnValue(1.0f);
    }
}
