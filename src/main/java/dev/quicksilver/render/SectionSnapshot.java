package dev.quicksilver.render;

import dev.quicksilver.QsConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.BlockBase;
import net.minecraft.block.material.Material;
import net.minecraft.client.render.entity.TileEntityRenderDispatcher;
import net.minecraft.level.BlockView;
import net.minecraft.level.Level;
import net.minecraft.level.LightType;
import net.minecraft.level.chunk.Chunk;
import net.minecraft.level.gen.BiomeSource;
import net.minecraft.tileentity.TileEntityBase;

/**
 * Immutable copy of the blocks, metadata and light of a 16x16x16 chunk section plus a one block border.
 * Created on the main thread; read by mesh worker threads through the {@link BlockView} interface.
 */
public final class SectionSnapshot implements BlockView, dev.quicksilver.render.vbo.CompactMesh.LightSource {
    public static final int S = 18;
    private static final int S2 = S * S;

    public final int x0, y0, z0; // section origin (not including the border)
    private final int bx, by, bz; // border origin = origin - 1
    private byte[] ids, metas, sky, block;
    /** Recycled block/light arrays (4 x 5.8 KB per snapshot, thousands of snapshots per minute while moving). */
    private static final java.util.concurrent.ConcurrentLinkedQueue<byte[][]> POOL = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger POOLED = new java.util.concurrent.atomic.AtomicInteger();
    private byte[][] arrays;
    private final int skyDarken;
    private final float[] lightTable;
    private final BiomeSource biomes;
    private final BiomeSource levelBiomes;
    private Map<Long, TileEntityBase> tileEntities;
    /** Tile entities inside the section that have a special renderer (vanilla collects these during the build). */
    public final List<TileEntityBase> renderedTileEntities = new ArrayList<>();
    /** True if any non-zero sky light was sampled (vanilla's Chunk.field_953 "lit" flag). */
    public boolean litSeen;
    public boolean empty;
    /**
     * Shader (extended format) meshing: block renderers see full brightness everywhere, so vertex colours carry only
     * tint and shading; light and occlusion are computed per vertex by {@link dev.quicksilver.render.vbo.CompactMesh}.
     */
    public boolean shaderMode;

    private static final int SLAB = BlockBase.STONE_SLAB.id;
    private static final int FARMLAND = BlockBase.FARMLAND.id;
    private static final int WSTAIRS = BlockBase.WOOD_STAIRS.id;
    private static final int CSTAIRS = BlockBase.COBBLESTONE_STAIRS.id;

    /** Snapshot of a section known to contain only air (no data copied). */
    private SectionSnapshot(int x0, int y0, int z0) {
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.bx = x0 - 1;
        this.by = y0 - 1;
        this.bz = z0 - 1;
        this.skyDarken = 0;
        this.lightTable = null;
        this.biomes = null;
        this.levelBiomes = null;
        this.empty = true;
    }

    public static SectionSnapshot emptySection(int x0, int y0, int z0) {
        return new SectionSnapshot(x0, y0, z0);
    }

    /**
     * True if the 16x16x16 section contains only air. Its mesh is empty no matter what is around it, so it doesn't need
     * a snapshot or a worker (about a third of all sections: the sky above the terrain).
     */
    public static boolean isAir(Level level, int x0, int y0, int z0) {
        if (y0 < 0 || y0 + 16 > 128) return false;
        Chunk c = level.getChunkFromCache(x0 >> 4, z0 >> 4);
        if (c == null || c.getClass() != Chunk.class || c.tiles == null || c.tiles.length != 32768) return false;
        byte[] t = c.tiles;
        for (int xz = 0; xz < 256; xz++) {
            int base = (xz >> 4) << 11 | (xz & 15) << 7 | y0;
            for (int y = 0; y < 16; y++) if (t[base + y] != 0) return false;
        }
        return true;
    }

    public SectionSnapshot(Level level, int x0, int y0, int z0) {
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.bx = x0 - 1;
        this.by = y0 - 1;
        this.bz = z0 - 1;
        this.skyDarken = level.field_202;
        this.lightTable = level.dimension.lightTable;
        this.levelBiomes = level.getBiomeSource();
        this.biomes = new BiomeColumnCache(level, bx, bz);
        byte[][] a = POOL.poll();
        if (a != null) POOLED.decrementAndGet();
        else a = new byte[][]{new byte[S * S * S], new byte[S * S * S], new byte[S * S * S], new byte[S * S * S]};
        arrays = a;
        ids = a[0];
        metas = a[1];
        sky = a[2];
        block = a[3];
        this.level = level;
        // The 3x3 chunks the section and its border touch are looked up here on the game thread; copying
        // their data is left to the mesh worker when they are plain vanilla chunks (it only reads the arrays; a block
        // change racing with the copy marks the section dirty again anyway).
        int minCx = bx >> 4, minCz = bz >> 4;
        boolean allRaw = true;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Chunk c = level.getChunkFromCache(minCx + i, minCz + j);
                src[i * 3 + j] = c;
                if (c.getClass() != Chunk.class || c.tiles == null || c.field_957 == null || c.field_958 == null || c.field_959 == null) allRaw = false;
            }
        }
        if (allRaw) {
            pendingCopy = true;
        } else {
            copy();
            resolveTileEntities();
        }
    }

    private final Chunk[] src = new Chunk[9];
    private Level level;
    private volatile boolean pendingCopy;
    private boolean tileEntitiesResolved;

    /** Mesh worker: copy the block/light data now (no-op if already copied). */
    public void ensureCopied() {
        if (pendingCopy) {
            copy();
            pendingCopy = false;
        }
    }

    private void copy() {
        boolean any = false;
        int minCx = bx >> 4, maxCx = (bx + S - 1) >> 4;
        int minCz = bz >> 4, maxCz = (bz + S - 1) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                Chunk c = src[(cx - minCx) * 3 + (cz - minCz)];
                int xs = Math.max(bx, cx << 4), xe = Math.min(bx + S - 1, (cx << 4) + 15);
                int zs = Math.max(bz, cz << 4), ze = Math.min(bz + S - 1, (cz << 4) + 15);
                boolean raw = c.getClass() == Chunk.class && c.tiles != null && c.field_957 != null && c.field_958 != null && c.field_959 != null;
                byte[] tiles = raw ? c.tiles : null;
                byte[] metaN = raw ? c.field_957.field_2103 : null;
                byte[] skyN = raw ? c.field_958.field_2103 : null;
                byte[] blkN = raw ? c.field_959.field_2103 : null;
                for (int x = xs; x <= xe; x++) {
                    int lx = x & 15;
                    for (int z = zs; z <= ze; z++) {
                        int lz = z & 15;
                        int col = (x - bx) * S2 + (z - bz) * S; // index base (y innermost)
                        int chunkCol = lx << 11 | lz << 7;
                        for (int dy = 0; dy < S; dy++) {
                            int y = by + dy;
                            int i = col + dy;
                            if (y < 0 || y >= 128) {
                                // light is handled in light(); arrays may be recycled, so clear the rest
                                ids[i] = 0;
                                metas[i] = 0;
                                continue;
                            }
                            if (raw) {
                                int ci = chunkCol | y;
                                byte id = tiles[ci];
                                ids[i] = id;
                                int half = ci >> 1;
                                if ((ci & 1) == 0) {
                                    metas[i] = (byte) (metaN[half] & 15);
                                    sky[i] = (byte) (skyN[half] & 15);
                                    block[i] = (byte) (blkN[half] & 15);
                                } else {
                                    metas[i] = (byte) ((metaN[half] >> 4) & 15);
                                    sky[i] = (byte) ((skyN[half] >> 4) & 15);
                                    block[i] = (byte) ((blkN[half] >> 4) & 15);
                                }
                                if (id != 0) any = true;
                            } else {
                                int id = c.getTileId(lx, y, lz);
                                ids[i] = (byte) id;
                                metas[i] = (byte) c.method_875(lx, y, lz);
                                sky[i] = (byte) c.method_864(LightType.field_2757, lx, y, lz);
                                block[i] = (byte) c.method_864(LightType.field_2758, lx, y, lz);
                                if (id != 0) any = true;
                            }
                        }
                    }
                }
            }
        }
        this.empty = !any;
    }

    /** Game thread (upload): find the tile entities of the section, exactly like vanilla does while building. */
    public void resolveTileEntities() {
        if (tileEntitiesResolved || ids == null) return;
        tileEntitiesResolved = true;
        ensureCopied();
        if (empty) return;
        for (int dx = 1; dx <= 16; dx++) {
            for (int dz = 1; dz <= 16; dz++) {
                int col = dx * S2 + dz * S;
                for (int dy = 1; dy <= 16; dy++) {
                    int id = ids[col + dy] & 255;
                    if (id != 0 && BlockBase.HAS_TILE_ENTITY[id]) {
                        int x = bx + dx, y = by + dy, z = bz + dz;
                        Chunk c = level.getChunkFromCache(x >> 4, z >> 4);
                        TileEntityBase te = c.method_882(x & 15, y, z & 15);
                        if (te != null) {
                            if (tileEntities == null) tileEntities = new HashMap<>();
                            tileEntities.put(pack(x, y, z), te);
                            if (TileEntityRenderDispatcher.INSTANCE.hasCustomRenderer(te)) renderedTileEntities.add(te);
                        }
                    }
                }
            }
        }
    }

    /** Hand the arrays back for reuse. The snapshot must not be used afterwards (main thread, after upload). */
    public void release() {
        byte[][] a = arrays;
        if (a == null) return;
        arrays = null;
        if (POOLED.get() < 256) {
            POOLED.incrementAndGet();
            POOL.offer(a);
        }
    }

    /** Must be called on the meshing thread before meshing. */
    public void prepareBiomes() {
        if (QsConfig.fastBiomeColours && biomes instanceof BiomeColumnCache c) c.fill();
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    private int index(int x, int y, int z) {
        int dx = x - bx, dy = y - by, dz = z - bz;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= S || dy >= S || dz >= S) return -1;
        return dx * S2 + dz * S + dy;
    }

    @Override
    public int getTileId(int x, int y, int z) {
        int i = index(x, y, z);
        return i < 0 ? 0 : ids[i] & 255;
    }

    @Override
    public int getTileMeta(int x, int y, int z) {
        int i = index(x, y, z);
        return i < 0 ? 0 : metas[i];
    }

    @Override
    public TileEntityBase getTileEntity(int x, int y, int z) {
        return tileEntities == null ? null : tileEntities.get(pack(x, y, z));
    }

    /** Port of WorldPopulationRegion.method_142 working on the copied data. */
    public int light(int x, int y, int z, boolean neighbours) {
        if (x < -32000000 || z < -32000000 || x >= 32000000 || z > 32000000) return 15;
        if (neighbours) {
            int id = getTileId(x, y, z);
            if (id == SLAB || id == FARMLAND || id == WSTAIRS || id == CSTAIRS) {
                int l = light(x, y + 1, z, false);
                int a = light(x + 1, y, z, false);
                int b = light(x - 1, y, z, false);
                int c = light(x, y, z + 1, false);
                int d = light(x, y, z - 1, false);
                if (a > l) l = a;
                if (b > l) l = b;
                if (c > l) l = c;
                if (d > l) l = d;
                return l;
            }
        }
        if (y < 0) return 0;
        if (y >= 128) {
            int v = 15 - skyDarken;
            return v < 0 ? 0 : v;
        }
        int dx = clamp(x - bx), dy = clamp(y - by), dz = clamp(z - bz);
        int i = dx * S2 + dz * S + dy;
        int s = sky[i];
        if (s > 0) litSeen = true;
        s -= skyDarken;
        int b = block[i];
        return b > s ? b : s;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v >= S ? S - 1 : v);
    }

    @Override
    public float method_1784(int x, int y, int z, int min) {
        if (shaderMode) return 1f;
        int l = light(x, y, z, true);
        if (l < min) l = min;
        return lightTable[l];
    }

    @Override
    public float getBrightness(int x, int y, int z) {
        if (shaderMode) return 1f;
        return lightTable[light(x, y, z, true)];
    }

    // ---- raw light for the extended format (sky light without the time-of-day darkening)

    private int rawLight(byte[] arr, int x, int y, int z, boolean sky) {
        if (y >= 128) return sky ? 15 : 0;
        if (y < 0) return 0;
        int dx = clamp(x - bx), dy = clamp(y - by), dz = clamp(z - bz);
        return arr[dx * S2 + dz * S + dy];
    }

    private int lightOf(byte[] arr, int x, int y, int z, boolean sky) {
        int id = getTileId(x, y, z);
        if (id == SLAB || id == FARMLAND || id == WSTAIRS || id == CSTAIRS) {
            int l = rawLight(arr, x, y + 1, z, sky);
            l = Math.max(l, rawLight(arr, x + 1, y, z, sky));
            l = Math.max(l, rawLight(arr, x - 1, y, z, sky));
            l = Math.max(l, rawLight(arr, x, y, z + 1, sky));
            l = Math.max(l, rawLight(arr, x, y, z - 1, sky));
            return l;
        }
        return rawLight(arr, x, y, z, sky);
    }

    @Override
    public int sky(int x, int y, int z) {
        return lightOf(sky, x, y, z, true);
    }

    @Override
    public int block(int x, int y, int z) {
        return lightOf(block, x, y, z, false);
    }

    @Override
    public int packed(int x, int y, int z) {
        int dx = x - bx, dy = y - by, dz = z - bz;
        if (y < 0 || y >= 128 || dx < 0 || dy < 0 || dz < 0 || dx >= S || dy >= S || dz >= S) {
            return sky(x, y, z) | block(x, y, z) << 4 | (opaque(x, y, z) ? 256 : 0);
        }
        int i = dx * S2 + dz * S + dy;
        int id = ids[i] & 255;
        if (id == SLAB || id == FARMLAND || id == WSTAIRS || id == CSTAIRS) {
            return sky(x, y, z) | block(x, y, z) << 4 | (BlockBase.FULL_OPAQUE[id] ? 256 : 0);
        }
        return sky[i] | block[i] << 4 | (BlockBase.FULL_OPAQUE[id] ? 256 : 0);
    }

    @Override
    public int cellV(int x, int y, int z) {
        if (y >= 128) return 15;
        if (y < 0) return 0;
        int dx = clamp(x - bx), dy = clamp(y - by), dz = clamp(z - bz);
        int i = dx * S2 + dz * S + dy;
        int id = ids[i] & 255;
        int sk, bl;
        if (id == SLAB || id == FARMLAND || id == WSTAIRS || id == CSTAIRS) {
            sk = sky(x, y, z);
            bl = block(x, y, z);
        } else {
            sk = sky[i];
            bl = block[i];
        }
        return sk | bl << 4 | (BlockBase.ALLOWS_GRASS_UNDER[id] ? 0 : 512) | id << 10;
    }

    @Override
    public float[] table() {
        return lightTable;
    }

    @Override
    public boolean opaque(int x, int y, int z) {
        return BlockBase.FULL_OPAQUE[getTileId(x, y, z)];
    }

    @Override
    public Material getMaterial(int x, int y, int z) {
        int id = getTileId(x, y, z);
        if (id == 0) return Material.AIR;
        BlockBase b = BlockBase.BY_ID[id];
        return b == null ? Material.AIR : b.material;
    }

    @Override
    public boolean isFullOpaque(int x, int y, int z) {
        BlockBase b = BlockBase.BY_ID[getTileId(x, y, z)];
        return b != null && b.isFullOpaque();
    }

    @Override
    public boolean canSuffocate(int x, int y, int z) {
        BlockBase b = BlockBase.BY_ID[getTileId(x, y, z)];
        return b != null && b.material.blocksMovement() && b.isFullCube();
    }

    @Override
    public BiomeSource getBiomeSource() {
        return biomes != null ? biomes : levelBiomes;
    }

    /** Computes the section's face visibility graph from the copied block ids. */
    public long computeVisGraph() {
        if (empty) return VisGraph.ALL;
        boolean[] full = CaveCuller.opaqueTable();
        boolean[] opaque = OPAQUE_SCRATCH.get();
        java.util.Arrays.fill(opaque, false);
        int count = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (full[rawId(x + 1, y + 1, z + 1)]) {
                        opaque[x | z << 4 | y << 8] = true;
                        count++;
                    }
                }
            }
        }
        return VisGraph.compute(opaque, count);
    }

    private static final ThreadLocal<boolean[]> OPAQUE_SCRATCH = ThreadLocal.withInitial(() -> new boolean[4096]);

    /** Fast raw id access for the mesher (index relative to the border origin). */
    public int rawId(int dx, int dy, int dz) {
        return ids[dx * S2 + dz * S + dy] & 255;
    }
}
