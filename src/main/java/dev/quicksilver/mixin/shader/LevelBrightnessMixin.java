package dev.quicksilver.mixin.shader;

import dev.quicksilver.shader.render.Hooks;
import net.minecraft.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelBrightnessMixin {
    @Inject(method = "getBrightness(III)F", at = @At("HEAD"), cancellable = true)
    private void qsh$fullBright(int x, int y, int z, CallbackInfoReturnable<Float> cir) {
        if (Hooks.inFrame) cir.setReturnValue(1.0f);
    }
}
