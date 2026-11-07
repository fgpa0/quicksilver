package dev.quicksilver.mixin.world;

import dev.quicksilver.world.AsyncDecoration;
import net.minecraft.block.FlowingFluid;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Flowing water and lava keep scratch state (a counter and two small arrays) in fields of the block object, which is
 * shared by every thread. Background decoration runs liquid flow for springs on its own threads, so on those threads
 * the scratch state lives in the decoration thread instead; everywhere else the original fields are used.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    @Shadow int field_1232;
    @Shadow boolean[] field_1233;
    @Shadow int[] field_1234;

    @Redirect(method = {"onScheduledTick(Lnet/minecraft/level/Level;IIILjava/util/Random;)V", "method_1053(Lnet/minecraft/level/Level;IIII)I"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/block/FlowingFluid;field_1232:I", opcode = Opcodes.GETFIELD))
    private int qs$getCount(FlowingFluid self) {
        int[] s = AsyncDecoration.fluidCount();
        return s != null ? s[self.id >> 1 & 1] : this.field_1232;
    }

    @Redirect(method = {"onScheduledTick(Lnet/minecraft/level/Level;IIILjava/util/Random;)V", "method_1053(Lnet/minecraft/level/Level;IIII)I"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/block/FlowingFluid;field_1232:I", opcode = Opcodes.PUTFIELD))
    private void qs$setCount(FlowingFluid self, int value) {
        int[] s = AsyncDecoration.fluidCount();
        if (s != null) s[self.id >> 1 & 1] = value;
        else this.field_1232 = value;
    }

    @Redirect(method = "method_1056(Lnet/minecraft/level/Level;III)[Z",
            at = @At(value = "FIELD", target = "Lnet/minecraft/block/FlowingFluid;field_1233:[Z", opcode = Opcodes.GETFIELD))
    private boolean[] qs$getFlow(FlowingFluid self) {
        boolean[] a = AsyncDecoration.fluidFlow(self.id);
        return a != null ? a : this.field_1233;
    }

    @Redirect(method = "method_1056(Lnet/minecraft/level/Level;III)[Z",
            at = @At(value = "FIELD", target = "Lnet/minecraft/block/FlowingFluid;field_1234:[I", opcode = Opcodes.GETFIELD))
    private int[] qs$getDist(FlowingFluid self) {
        int[] a = AsyncDecoration.fluidDist(self.id);
        return a != null ? a : this.field_1234;
    }
}
