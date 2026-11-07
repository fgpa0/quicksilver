package dev.quicksilver.mixin.world;

import net.minecraft.block.Sand;
import net.minecraft.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sand and gravel schedule a "should I fall?" check whenever they are placed. World generation places them by the
 * hundred thousand (gravel veins, beaches), almost always on solid ground, which floods the scheduled-tick queue: the
 * game only works through 1000 of those per tick, so water and lava placed by the player waited behind them for
 * minutes. A check is only scheduled now when the block below would actually let it fall; if that support is removed
 * later, the neighbour update schedules the check exactly as before.
 */
@Mixin(Sand.class)
public abstract class SandMixin {
    /** Decoration runs Sand with fallInstantly set; on a decoration thread that must not leak to the game thread's sand. */
    @Redirect(method = "method_436(Lnet/minecraft/level/Level;III)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/block/Sand;fallInstantly:Z", opcode = org.objectweb.asm.Opcodes.GETSTATIC))
    private boolean qs$fallInstantly() {
        return Sand.fallInstantly || dev.quicksilver.world.AsyncDecoration.onDecorationThread();
    }

    @Redirect(method = "onBlockPlaced(Lnet/minecraft/level/Level;III)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/level/Level;method_216(IIIII)V"))
    private void qs$scheduleOnlyIfUnsupported(Level level, int x, int y, int z, int id, int delay) {
        if (y > 0 && !Sand.method_435(level, x, y - 1, z)) return; // standing on something solid: the check would do nothing
        level.method_216(x, y, z, id, delay);
    }
}
