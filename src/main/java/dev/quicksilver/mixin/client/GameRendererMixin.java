package dev.quicksilver.mixin.client;

import dev.quicksilver.client.RenderDistance;
import net.minecraft.sortme.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow private float field_2350;

    /** Fog and far plane follow the render distance in chunks instead of the 4 vanilla steps. */
    @Inject(method = "method_1840(FI)V", at = @At(value = "FIELD", target = "Lnet/minecraft/sortme/GameRenderer;field_2350:F", opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void qs$fogDistance(float delta, int pass, CallbackInfo ci) {
        this.field_2350 = RenderDistance.fogDistance();
    }
}
