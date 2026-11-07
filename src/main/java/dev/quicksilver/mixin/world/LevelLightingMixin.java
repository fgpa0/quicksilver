package dev.quicksilver.mixin.world;

import dev.quicksilver.world.AsyncWorldGen;
import dev.quicksilver.world.DeferredLighting;
import dev.quicksilver.world.LightBudget;
import dev.quicksilver.world.LightingLevel;
import net.minecraft.level.Level;
import net.minecraft.level.LightType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelLightingMixin implements LightingLevel {
    @Unique private final DeferredLighting qs$deferredLighting = new DeferredLighting();

    @Override
    public DeferredLighting qs$deferredLighting() {
        return qs$deferredLighting;
    }

    /** Guard the shared queue itself; guarding one heightmap call site leaves other callers unprotected. */
    @Inject(method = "method_167(Lnet/minecraft/level/LightType;IIIIIIZ)V", at = @At("HEAD"), cancellable = true)
    private void qs$deferLighting(LightType type, int x0, int y0, int z0, int x1, int y1, int z1,
                                 boolean merge, CallbackInfo ci) {
        if (qs$deferredLighting.isOwnerThread()) return;
        // Raw terrain generation uses private chunks; its border fixes are redone by take() or the relighter.
        if (!AsyncWorldGen.isGenThread()) qs$deferredLighting.enqueue(type, x0, y0, z0, x1, y1, z1, merge);
        ci.cancel();
    }

    /** Loading screens and other levels also process lighting, outside Minecraft.run's redirect. */
    @Inject(method = "method_232()Z", at = @At("HEAD"), cancellable = true)
    private void qs$budgetAllLighting(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(LightBudget.run((Level) (Object) this));
    }
}
