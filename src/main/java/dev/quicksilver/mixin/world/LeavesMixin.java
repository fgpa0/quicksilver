package dev.quicksilver.mixin.world;

import net.minecraft.block.Leaves;
import net.minecraft.level.Level;
import net.minecraft.level.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Leaf decay checks (random ticks on leaves placed by world generation) read the 9x9x9 blocks around the leaf one
 * Level.getTileId at a time, each with bounds checks and a chunk lookup. A fresh forest triggers thousands of these
 * per second for minutes after loading. The chunk lookups are done once per check here instead; the algorithm and
 * its result are unchanged.
 */
@Mixin(Leaves.class)
public abstract class LeavesMixin {
    @Unique private static Level qs$level;
    @Unique private static final Chunk[] qs$chunks = new Chunk[4];
    @Unique private static final int[] qs$cx = new int[4], qs$cz = new int[4];
    @Unique private static int qs$count;

    /** First call of every decay check: forget the chunks of the previous check. */
    @Redirect(method = "onScheduledTick(Lnet/minecraft/level/Level;IIILjava/util/Random;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/level/Level;getTileMeta(III)I", ordinal = 0))
    private int qs$startCheck(Level level, int x, int y, int z) {
        if (!dev.quicksilver.Quicksilver.isClientThread()) return level.getTileMeta(x, y, z);
        qs$level = level;
        for (int i = 0; i < qs$count; i++) qs$chunks[i] = null;
        qs$count = 0;
        return level.getTileMeta(x, y, z);
    }

    @Redirect(method = "onScheduledTick(Lnet/minecraft/level/Level;IIILjava/util/Random;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/level/Level;getTileId(III)I"))
    private int qs$fastTileId(Level level, int x, int y, int z) {
        if (level != qs$level || !dev.quicksilver.Quicksilver.isClientThread() || y < 0 || y >= 128 || x < -32000000 || z < -32000000 || x >= 32000000 || z >= 32000000) {
            return level.getTileId(x, y, z);
        }
        int cx = x >> 4, cz = z >> 4;
        Chunk c = null;
        for (int i = 0; i < qs$count; i++) {
            if (qs$cx[i] == cx && qs$cz[i] == cz) {
                c = qs$chunks[i];
                break;
            }
        }
        if (c == null) {
            if (qs$count == qs$chunks.length) return level.getTileId(x, y, z);
            c = level.getChunkFromCache(cx, cz);
            qs$cx[qs$count] = cx;
            qs$cz[qs$count] = cz;
            qs$chunks[qs$count++] = c;
        }
        return c.getTileId(x & 15, y, z & 15);
    }
}
