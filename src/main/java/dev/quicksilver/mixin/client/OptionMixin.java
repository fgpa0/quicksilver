package dev.quicksilver.mixin.client;

import net.minecraft.client.options.Option;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Option.class)
public abstract class OptionMixin {
    /** Render distance becomes a slider (2..48 chunks). */
    @Inject(method = "isSlider()Z", at = @At("HEAD"), cancellable = true)
    private void qs$renderDistanceSlider(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this == Option.field_1101) cir.setReturnValue(true);
    }
}
