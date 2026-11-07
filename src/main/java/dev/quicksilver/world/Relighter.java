package dev.quicksilver.world;

import dev.quicksilver.QsConfig;
import java.util.ArrayDeque;
import java.util.HashSet;
import net.minecraft.level.Level;
import net.minecraft.level.LightType;
import net.minecraft.level.chunk.Chunk;
import net.minecraft.level.source.LevelSource;

/**
 * Fixes the vanilla "pitch black shadows under trees and overhangs" bug.
 * <p>
 * Sky light only shines straight down when a chunk is generated; light spreading sideways under overhangs and leaves
 * comes from light updates that vanilla queues while building the heightmap and while placing trees. Those updates
 * are dropped (or skipped when processed) whenever the chunk itself or one of its neighbours isn't loaded yet, which
 * is always the case at the edge of the loaded area - so the dark spots stay until a block nearby changes.
 * <p>
 * Here every chunk that enters the cache is re-checked once its 3x3 neighbourhood is loaded and decorated: the same
 * column-to-column comparisons vanilla does are queued again, now at a time they can actually be processed.
 * Columns whose light is already right are cheap (nothing changes, nothing spreads).
 */
public final class Relighter {
    private Relighter() {}

    private static final HashSet<Long> PENDING = new HashSet<>();
    private static Level currentLevel;
    public static int statRelit;
    public static long totalNs;

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Chunks that are ready (neighbourhood loaded and decorated), in the order they became ready. */
    private static final ArrayDeque<Long> READY = new ArrayDeque<>();
    private static long lastSweep;

    /** Game thread: a chunk was added to the singleplayer chunk cache (decoration triggered by it already done). */
    public static void onChunkLoaded(Level level, int x, int z) {
        if (!QsConfig.fixGenerationLighting || level == null || level.dimension.halvesMapping) return;
        if (level != currentLevel) {
            currentLevel = level;
            PENDING.clear();
            READY.clear();
        }
        PENDING.add(key(x, z));
        // Loading this chunk can complete the neighbourhood (loaded within 1, decorated within 2) of chunks nearby.
        LevelSource cache = level.getCache();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Long k = key(x + dx, z + dz);
                if (PENDING.contains(k) && ready(cache, x + dx, z + dz)) {
                    PENDING.remove(k);
                    READY.add(k);
                }
            }
        }
    }

    /** Game thread: the decoration of chunk (x, z) finished later than its loading (background decoration). */
    public static void onDecorated(Level level, int x, int z) {
        if (!QsConfig.fixGenerationLighting || level == null || level != currentLevel) return;
        LevelSource cache = level.getCache();
        // readiness of a chunk depends on its own and its -x/-z neighbours' decorated flags: (x..x+1, z..z+1) are affected
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                Long k = key(x + dx, z + dz);
                if (PENDING.contains(k) && ready(cache, x + dx, z + dz)) {
                    PENDING.remove(k);
                    READY.add(k);
                }
            }
        }
    }

    public static int pending() {
        return PENDING.size() + READY.size();
    }

    /** Game thread, once per frame. */
    public static void frame(Level level, long budgetNs) {
        if (level != currentLevel) return;
        LevelSource cache = level.getCache();
        long start = System.nanoTime();
        if (start - lastSweep > 5_000_000_000L) {
            // forget chunks that were unloaded before becoming ready
            lastSweep = start;
            PENDING.removeIf(k -> !cache.isChunkLoaded((int) (k >> 32), (int) (long) k));
        }
        while (!READY.isEmpty()) {
            if (System.nanoTime() - start > budgetNs) break;
            Long k = READY.poll();
            int x = (int) (k >> 32), z = (int) (long) k;
            if (!cache.isChunkLoaded(x, z)) continue;
            relight(level, cache.getChunk(x, z));
            statRelit++;
        }
        totalNs += System.nanoTime() - start;
    }

    private static boolean ready(LevelSource cache, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!cache.isChunkLoaded(x + dx, z + dz)) return false;
            }
        }
        // trees and other features reaching into this chunk come from it and its -x / -z neighbours
        return cache.getChunk(x, z).decorated && cache.getChunk(x - 1, z).decorated
                && cache.getChunk(x, z - 1).decorated && cache.getChunk(x - 1, z - 1).decorated;
    }

    private static void relight(Level level, Chunk c) {
        int bx = c.x << 4, bz = c.z << 4;
        // Light-emitting blocks placed by terrain generation (lava pools, ...) start without block light; vanilla only
        // lights them when some nearby block update happens to touch them. Light every emitter once here.
        byte[] tiles = c.tiles;
        if (tiles != null && tiles.length == 32768) {
            int[] emit = net.minecraft.block.BlockBase.EMITTANCE;
            for (int i = 0; i < 32768; i++) {
                int id = tiles[i] & 255;
                if (id != 0 && emit[id] > 0) {
                    int x = bx + (i >> 11), z = bz + ((i >> 7) & 15), y = i & 127;
                    if (c.method_864(LightType.field_2758, x & 15, y, z & 15) != emit[id]) {
                        level.method_166(LightType.field_2758, x, y, z, x, y, z);
                    }
                }
            }
        }
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int h = c.getHeight(lx, lz);
                int x = bx + lx, z = bz + lz;
                compare(level, x - 1, z, h);
                compare(level, x + 1, z, h);
                compare(level, x, z - 1, h);
                compare(level, x, z + 1, h);
            }
        }
    }

    /** Same as Chunk.method_888: light the neighbour column between the two heights. */
    private static void compare(Level level, int x, int z, int h) {
        int nh = level.getChunkFromCache(x >> 4, z >> 4).getHeight(x & 15, z & 15);
        if (nh > h) level.method_166(LightType.field_2757, x, h, z, x, nh, z);
        else if (nh < h) level.method_166(LightType.field_2757, x, nh, z, x, h, z);
    }
}
