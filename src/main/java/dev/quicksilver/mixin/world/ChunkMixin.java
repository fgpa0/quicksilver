package dev.quicksilver.mixin.world;

import dev.quicksilver.Quicksilver;
import net.minecraft.level.Level;
import net.minecraft.level.LightType;
import net.minecraft.level.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Chunk.class)
public abstract class ChunkMixin implements dev.quicksilver.world.QsChunk {
    @org.spongepowered.asm.mixin.Shadow public byte[] tiles;
    @org.spongepowered.asm.mixin.Unique private final short[] qs$tickCounts = new short[8];
    @org.spongepowered.asm.mixin.Unique private int qs$dirtySections = 0xFF;
    @org.spongepowered.asm.mixin.Unique private byte[] qs$countedTiles;

    @Override
    public int qs$randomTickCount(int section) {
        byte[] t = tiles;
        if (t == null || t.length != 32768) return 1; // unusual chunk: always do the vanilla work
        if (t != qs$countedTiles) { // new block array (chunk loaded from disk / replaced)
            qs$countedTiles = t;
            qs$dirtySections = 0xFF;
        }
        if ((qs$dirtySections >> section & 1) != 0) {
            boolean[] ticks = net.minecraft.block.BlockBase.TICKS_RANDOMLY;
            int n = 0, y0 = section << 4;
            for (int xz = 0; xz < 256; xz++) {
                int base = (xz >> 4) << 11 | (xz & 15) << 7 | y0;
                for (int y = 0; y < 16; y++) if (ticks[t[base + y] & 255]) n++;
            }
            qs$tickCounts[section] = (short) n;
            qs$dirtySections &= ~(1 << section);
        }
        return qs$tickCounts[section];
    }

    @org.spongepowered.asm.mixin.Shadow public net.minecraft.class_257 field_957;
    @org.spongepowered.asm.mixin.Shadow public byte[] heightmap;
    @org.spongepowered.asm.mixin.Shadow public Level level;
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Mutable public int x;
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Mutable public int z;
    @org.spongepowered.asm.mixin.Shadow public void method_876(int lx, int y, int lz, int meta) {}

    @org.spongepowered.asm.mixin.Unique private int qs$mod;
    @org.spongepowered.asm.mixin.Unique private dev.quicksilver.world.DecoJournal qs$journal;
    @org.spongepowered.asm.mixin.Unique private int qs$jIndex;

    @Override public int qs$mod() { return qs$mod; }

    @Override
    public void qs$setJournal(dev.quicksilver.world.DecoJournal journal, int index) {
        qs$journal = journal;
        qs$jIndex = index;
    }

    @Override
    public void qs$setPos(int nx, int nz) {
        this.x = nx;
        this.z = nz;
        qs$dirtySections = 0xFF;
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "method_876(IIII)V", at = @At("HEAD"))
    private void qs$metaWritten(int lx, int y, int lz, int meta, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        qs$mod++;
        if (qs$journal != null) qs$journal.add(dev.quicksilver.world.DecoJournal.pack(2, qs$jIndex, meta, 0, y, lx, lz));
    }

    /**
     * Replays a write recorded on a shadow chunk: the same block, metadata, heightmap and light-queue changes as
     * setTileWithMetadata / method_860, but not the onBlockRemoved / onBlockPlaced hooks, which already ran against
     * the shadow (their writes are in the journal, their scheduled ticks are replayed separately).
     */
    @Override
    public void qs$replay(int e) {
        int kind = e >>> 29, meta = e >>> 23 & 15, id = e >>> 15 & 255, y = e >>> 8 & 127, lx = e >>> 4 & 15, lz = e & 15;
        if (kind == 2) {
            method_876(lx, y, lz, meta);
            return;
        }
        boolean withMeta = kind == 1;
        if (!withMeta) meta = 0;
        byte b = (byte) id;
        int height = this.heightmap[lz << 4 | lx] & 255;
        int old = this.tiles[lx << 11 | lz << 7 | y] & 255;
        if (withMeta ? (old == id && this.field_957.method_1703(lx, y, lz) == meta) : old == id) return;
        qs$mod++;
        qs$dirtySections |= 1 << (y >> 4);
        boolean sameLight = qs$sameLight(old, id);
        int wx = this.x * 16 + lx;
        int wz = this.z * 16 + lz;
        this.tiles[lx << 11 | lz << 7 | y] = b;
        this.field_957.method_1704(lx, y, lz, meta);
        if (!sameLight) {
            if (!withMeta || !this.level.dimension.halvesMapping) {
                if (net.minecraft.block.BlockBase.LIGHT_OPACITY[id] != 0) {
                    if (y >= height) this.method_889(lx, y + 1, lz);
                } else if (y == height - 1) {
                    this.method_889(lx, y, lz);
                }
                this.level.method_166(LightType.field_2757, wx, y, wz, wx, y, wz);
            }
            this.level.method_166(LightType.field_2758, wx, y, wz, wx, y, wz);
            this.method_887(lx, lz);
        }
        this.field_967 = true;
    }
    @org.spongepowered.asm.mixin.Shadow public boolean field_967;
    @org.spongepowered.asm.mixin.Shadow private void method_887(int lx, int lz) {}
    @org.spongepowered.asm.mixin.Shadow private void method_889(int lx, int y, int lz) {}

    /**
     * True when replacing block a with block b can't change any light value (same opacity and brightness, e.g. stone
     * to ore during world generation). Vanilla still queues two lighting updates plus up to four column checks per
     * block in that case; they find nothing to do. World generation places hundreds of thousands of such blocks.
     */
    @org.spongepowered.asm.mixin.Unique
    private static final boolean QS_NO_SKIP = Boolean.getBoolean("quicksilver.noSameLightSkip");

    @org.spongepowered.asm.mixin.Unique
    private static boolean qs$sameLight(int a, int b) {
        return !QS_NO_SKIP && dev.quicksilver.QsConfig.fastLighting && dev.quicksilver.QsConfig.fixGenerationLighting
                && net.minecraft.block.BlockBase.LIGHT_OPACITY[a] == net.minecraft.block.BlockBase.LIGHT_OPACITY[b]
                && net.minecraft.block.BlockBase.EMITTANCE[a] == net.minecraft.block.BlockBase.EMITTANCE[b];
    }

    /**
     * @author Quicksilver
     * @reason vanilla code, except that lighting work is skipped when the change can't affect light, and the
     * random-tick section counts are kept up to date
     */
    @org.spongepowered.asm.mixin.Overwrite
    public boolean setTileWithMetadata(int lx, int y, int lz, int id, int meta) {
        byte b = (byte) id;
        int height = this.heightmap[lz << 4 | lx] & 255;
        int old = this.tiles[lx << 11 | lz << 7 | y] & 255;
        if (old == id && this.field_957.method_1703(lx, y, lz) == meta) return false;
        qs$mod++;
        if (qs$journal != null) qs$journal.add(dev.quicksilver.world.DecoJournal.pack(1, qs$jIndex, meta, id, y, lx, lz));
        if ((y & ~127) == 0) qs$dirtySections |= 1 << (y >> 4);
        boolean sameLight = qs$sameLight(old, id & 255);
        int wx = this.x * 16 + lx;
        int wz = this.z * 16 + lz;
        this.tiles[lx << 11 | lz << 7 | y] = (byte) (b & 255);
        if (old != 0 && !this.level.isServerSide) net.minecraft.block.BlockBase.BY_ID[old].onBlockRemoved(this.level, wx, y, wz);
        this.field_957.method_1704(lx, y, lz, meta);
        if (!sameLight) {
            if (!this.level.dimension.halvesMapping) {
                if (net.minecraft.block.BlockBase.LIGHT_OPACITY[b & 255] != 0) {
                    if (y >= height) this.method_889(lx, y + 1, lz);
                } else if (y == height - 1) {
                    this.method_889(lx, y, lz);
                }
                this.level.method_166(LightType.field_2757, wx, y, wz, wx, y, wz);
            }
            this.level.method_166(LightType.field_2758, wx, y, wz, wx, y, wz);
            this.method_887(lx, lz);
        }
        this.field_957.method_1704(lx, y, lz, meta);
        if (id != 0) net.minecraft.block.BlockBase.BY_ID[id].onBlockPlaced(this.level, wx, y, wz);
        this.field_967 = true;
        return true;
    }

    /**
     * @author Quicksilver
     * @reason see setTileWithMetadata
     */
    @org.spongepowered.asm.mixin.Overwrite
    public boolean method_860(int lx, int y, int lz, int id) {
        byte b = (byte) id;
        int height = this.heightmap[lz << 4 | lx] & 255;
        int old = this.tiles[lx << 11 | lz << 7 | y] & 255;
        if (old == id) return false;
        qs$mod++;
        if (qs$journal != null) qs$journal.add(dev.quicksilver.world.DecoJournal.pack(0, qs$jIndex, 0, id, y, lx, lz));
        if ((y & ~127) == 0) qs$dirtySections |= 1 << (y >> 4);
        boolean sameLight = qs$sameLight(old, id & 255);
        int wx = this.x * 16 + lx;
        int wz = this.z * 16 + lz;
        this.tiles[lx << 11 | lz << 7 | y] = (byte) (b & 255);
        if (old != 0) net.minecraft.block.BlockBase.BY_ID[old].onBlockRemoved(this.level, wx, y, wz);
        this.field_957.method_1704(lx, y, lz, 0);
        if (!sameLight) {
            if (net.minecraft.block.BlockBase.LIGHT_OPACITY[b & 255] != 0) {
                if (y >= height) this.method_889(lx, y + 1, lz);
            } else if (y == height - 1) {
                this.method_889(lx, y, lz);
            }
            this.level.method_166(LightType.field_2757, wx, y, wz, wx, y, wz);
            this.level.method_166(LightType.field_2758, wx, y, wz, wx, y, wz);
            this.method_887(lx, lz);
        }
        if (id != 0 && !this.level.isServerSide) net.minecraft.block.BlockBase.BY_ID[id].onBlockPlaced(this.level, wx, y, wz);
        this.field_967 = true;
        return true;
    }

    /**
     * Heightmap generation queues sky light fixes for the chunk borders into the level's lighting queue, which only
     * the game thread may touch. Chunks generated on the async worldgen threads skip that here; their heightmap is
     * regenerated on the game thread when the chunk is taken (see AsyncWorldGen.take), which queues the fixes then.
     */
    @Redirect(method = "method_888(III)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/level/Level;method_166(Lnet/minecraft/level/LightType;IIIIII)V"))
    private void qs$queueLightOnGameThreadOnly(Level level, LightType type, int x0, int y0, int z0, int x1, int y1, int z1) {
        if (Quicksilver.isClientThread()) level.method_166(type, x0, y0, z0, x1, y1, z1);
    }
}
