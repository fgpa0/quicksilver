package dev.quicksilver.mixin.worldgen;

import java.util.Random;
import net.minecraft.block.BlockBase;
import net.minecraft.level.Level;
import net.minecraft.level.chunk.Chunk;
import net.minecraft.level.structure.Ore;
import net.minecraft.util.maths.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Ore, dirt and gravel veins: about two thirds of the cost of decorating a chunk, and decoration is the one part
 * of chunk loading Beta runs on the game thread.
 *
 * <p>Same vein shape, same order of evaluation, same results. The difference is the block access: vanilla calls
 * Level.getTileId and Level.setTileInChunk for every probed block, and each of those walks
 * Level -> getChunkFromCache -> ServerChunkCache.getChunk -> Chunk. Here the chunk is resolved only when the
 * vein crosses into another one (at most a few times per vein) and the block array is read directly.
 */
@Mixin(Ore.class)
public abstract class OreMixin {
    @Shadow private int field_283; // block id to place
    @Shadow private int field_284; // vein size

    /**
     * @author Quicksilver
     * @reason resolve the chunk once per vein segment instead of once per probed block (exact same output)
     */
    @Overwrite
    public boolean generate(Level level, Random rand, int x, int y, int z) {
        final int ore = field_283;
        final int size = field_284;
        final int stone = BlockBase.STONE.id;
        float angle = rand.nextFloat() * 3.1415927F;
        double xa = (double) ((float) (x + 8) + MathHelper.sin(angle) * (float) size / 8.0F);
        double xb = (double) ((float) (x + 8) - MathHelper.sin(angle) * (float) size / 8.0F);
        double za = (double) ((float) (z + 8) + MathHelper.cos(angle) * (float) size / 8.0F);
        double zb = (double) ((float) (z + 8) - MathHelper.cos(angle) * (float) size / 8.0F);
        double ya = (double) (y + rand.nextInt(3) + 2);
        double yb = (double) (y + rand.nextInt(3) + 2);

        Chunk cached = null;
        int cachedX = Integer.MIN_VALUE, cachedZ = Integer.MIN_VALUE;

        for (int i = 0; i <= size; i++) {
            double cx = xa + (xb - xa) * (double) i / (double) size;
            double cy = ya + (yb - ya) * (double) i / (double) size;
            double cz = za + (zb - za) * (double) i / (double) size;
            double scale = rand.nextDouble() * (double) size / 16.0D;
            // vanilla computes this same expression twice, once for the horizontal and once for the vertical
            // diameter, so both are bit-for-bit equal
            double w = (double) (MathHelper.sin((float) i * 3.1415927F / (float) size) + 1.0F) * scale + 1.0D;
            double halfW = w / 2.0D;
            int x0 = MathHelper.floor(cx - halfW);
            int y0 = MathHelper.floor(cy - halfW);
            int z0 = MathHelper.floor(cz - halfW);
            int x1 = MathHelper.floor(cx + halfW);
            int y1 = MathHelper.floor(cy + halfW);
            int z1 = MathHelper.floor(cz + halfW);

            for (int bx = x0; bx <= x1; bx++) {
                double fx = ((double) bx + 0.5D - cx) / halfW;
                double fx2 = fx * fx;
                if (fx2 >= 1.0D) continue;
                for (int by = y0; by <= y1; by++) {
                    double fy = ((double) by + 0.5D - cy) / halfW;
                    double fxy2 = fx2 + fy * fy;
                    if (fxy2 >= 1.0D) continue;
                    for (int bz = z0; bz <= z1; bz++) {
                        double fz = ((double) bz + 0.5D - cz) / halfW;
                        if (fxy2 + fz * fz >= 1.0D) continue;
                        // Level.getTileId / setTileInChunk report "air" and refuse to write outside these bounds
                        if (bx < -32000000 || bz < -32000000 || bx >= 32000000 || bz > 32000000) continue;
                        if (by < 0 || by >= 128) continue;
                        int ccx = bx >> 4, ccz = bz >> 4;
                        if (cached == null || ccx != cachedX || ccz != cachedZ) {
                            cached = level.getChunkFromCache(ccx, ccz);
                            cachedX = ccx;
                            cachedZ = ccz;
                        }
                        byte[] tiles = cached.tiles;
                        if (tiles != null && tiles.length == 32768) {
                            int lx = bx & 15, lz = bz & 15;
                            if ((tiles[lx << 11 | lz << 7 | by] & 255) == stone) cached.method_860(lx, by, lz, ore);
                        } else if (level.getTileId(bx, by, bz) == stone) {
                            // unusual chunk (another mod): let vanilla handle it
                            level.setTileInChunk(bx, by, bz, ore);
                        }
                    }
                }
            }
        }
        return true;
    }
}
