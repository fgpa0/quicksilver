package dev.quicksilver.mixin.worldgen;

import dev.quicksilver.world.AsyncDecoration;
import dev.quicksilver.world.AsyncWorldGen;
import net.minecraft.level.Level;
import net.minecraft.level.gen.BiomeSource;
import net.minecraft.level.source.OverworldLevelSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(OverworldLevelSource.class)
public abstract class OverworldLevelSourceMixin {
    @Redirect(method = {"getChunk(II)Lnet/minecraft/level/chunk/Chunk;", "calculateNoise([DIIIIII)[D"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/level/Level;getBiomeSource()Lnet/minecraft/level/gen/BiomeSource;"))
    private BiomeSource qs$threadBiomes(Level level) {
        return AsyncWorldGen.biomeSource(level);
    }

    /** Snow and ice placement needs a 16x16 temperature sample; a generator thread usually has it ready. */
    @Redirect(method = "decorate(Lnet/minecraft/level/source/LevelSource;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/level/gen/BiomeSource;getTemperatures([DIIII)[D"))
    private double[] qs$decorationTemperatures(BiomeSource source, double[] out, int x, int z, int sx, int sz) {
        double[] ready = AsyncWorldGen.takeTemperatures(x, z, sx, sz);
        return ready != null ? ready : source.getTemperatures(out, x, z, sx, sz);
    }

    /** Sand.fallInstantly is a static flag; a decoration thread must not set it for the game thread's sand. */
    @Redirect(method = "decorate(Lnet/minecraft/level/source/LevelSource;II)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/block/Sand;fallInstantly:Z", opcode = org.objectweb.asm.Opcodes.PUTSTATIC))
    private void qs$setFallInstantly(boolean value) {
        if (!AsyncDecoration.onDecorationThread()) net.minecraft.block.Sand.fallInstantly = value;
    }
}
