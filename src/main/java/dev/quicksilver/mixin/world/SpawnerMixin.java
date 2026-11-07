package dev.quicksilver.mixin.world;

import dev.quicksilver.world.ChunkLoadManager;
import java.util.Set;
import net.minecraft.level.Level;
import net.minecraft.sortme.LevelMonsterSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelMonsterSpawner.class)
public abstract class SpawnerMixin {
    @Shadow private static Set field_2409;

    /** Mob spawning should only consider loaded chunks (vanilla loads/generates the whole 17x17 area). */
    @Inject(method = "method_1870(Lnet/minecraft/level/Level;ZZ)I", at = @At(value = "INVOKE", target = "Lnet/minecraft/level/Level;getSpawnPosition()Lnet/minecraft/util/Vec3i;"))
    private static void qs$onlyLoaded(Level level, boolean hostile, boolean passive, CallbackInfoReturnable<Integer> cir) {
        ChunkLoadManager.filterLoaded(level, field_2409);
    }
}
