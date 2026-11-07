package dev.quicksilver.world;

import dev.quicksilver.mixin.world.LevelAccessor;
import java.util.List;
import net.minecraft.block.BlockBase;
import net.minecraft.class_417;
import net.minecraft.level.Level;
import net.minecraft.sortme.LevelListener;
import net.minecraft.level.LightType;
import net.minecraft.level.chunk.Chunk;
import net.minecraft.level.source.LevelSource;

/**
 * Drop-in replacement for one lighting update (class_417.method_1402) that reads and writes the chunk arrays
 * directly. Vanilla looks the chunk up (with bounds and "is loaded" checks) for each of the 7 light values and the
 * block id it reads per block; here the chunks around the update are looked up once. Same algorithm, same order of
 * evaluation, same results; falls back to vanilla for anything unusual (modded chunk classes).
 */
public final class FastLight {
    private FastLight() {}

    private static final int[] OPACITY = BlockBase.LIGHT_OPACITY;
    private static final int[] EMITTANCE = BlockBase.EMITTANCE;

    // chunk window covering the update area plus one block in x/z
    private static Chunk[] window = new Chunk[64];
    private static int winX, winZ, winW, winH;

    public static long statFast, statFallback;

    /** @return false if the update must be done by vanilla code. */
    public static boolean process(Level level, class_417 u) {
        int x0 = u.field_1674, x1 = u.field_1677, z0 = u.field_1676, z1 = u.field_1679;
        int sx = x1 - x0 + 1, sy = u.field_1678 - u.field_1675 + 1, sz = z1 - z0 + 1;
        if ((long) sx * sy * sz > 32768) return false; // vanilla prints "Light too large" and skips
        if (sx <= 0 || sy <= 0 || sz <= 0) return true; // vanilla loops wouldn't run
        LevelSource cache = level.getCache();
        winX = (x0 - 1) >> 4;
        winZ = (z0 - 1) >> 4;
        winW = ((x1 + 1) >> 4) - winX + 1;
        winH = ((z1 + 1) >> 4) - winZ + 1;
        int n = winW * winH;
        if (window.length < n) window = new Chunk[Integer.highestOneBit(n) << 1];
        for (int i = 0; i < winW; i++) {
            for (int j = 0; j < winH; j++) {
                int cx = winX + i, cz = winZ + j;
                Chunk c = null;
                if (cache.isChunkLoaded(cx, cz)) {
                    c = cache.getChunk(cx, cz);
                    if (c.getClass() != Chunk.class || c.tiles == null || c.field_958 == null || c.field_959 == null || c.heightmap == null
                            || c.tiles.length != 32768) {
                        clearWindow(n);
                        statFallback++;
                        return false;
                    }
                }
                window[i * winH + j] = c;
            }
        }
        boolean sky = u.field_1673 == LightType.field_2757;
        boolean noSkyNeighbours = level.dimension.halvesMapping && sky; // method_165 does nothing then
        List<?> listeners = ((LevelAccessor) level).qs$listeners();
        queue = ((LevelAccessor) level).qs$lightingUpdates();
        long lastNotified = Long.MIN_VALUE;
        // The renderer is normally the only listener: then changed sections are collected and marked once each.
        net.minecraft.client.render.WorldRenderer wr = null;
        boolean batch = listeners.size() == 1 && listeners.get(0) instanceof net.minecraft.client.render.WorldRenderer;
        if (batch) wr = (net.minecraft.client.render.WorldRenderer) listeners.get(0);
        touchedCount = 0;
        lastTouched = Long.MIN_VALUE;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                // method_153(x, 0, z, 1): the chunks of x-1..x+1, z-1..z+1 must be loaded (and the column's chunk not empty)
                Chunk c = chunk(x, z);
                if (c == null || chunk(x - 1, z - 1) == null || chunk(x + 1, z - 1) == null || chunk(x - 1, z + 1) == null
                        || chunk(x + 1, z + 1) == null) continue;
                if (u.field_1675 < 0) u.field_1675 = 0;
                if (u.field_1678 >= 128) u.field_1678 = 127;
                Chunk cW = chunk(x - 1, z), cE = chunk(x + 1, z), cN = chunk(x, z - 1), cS = chunk(x, z + 1);
                int lx = x & 15, lz = z & 15;
                int col = lx << 11 | lz << 7;
                int colW = ((x - 1) & 15) << 11 | lz << 7, colE = ((x + 1) & 15) << 11 | lz << 7;
                int colN = lx << 11 | ((z - 1) & 15) << 7, colS = lx << 11 | ((z + 1) & 15) << 7;
                byte[] arr = sky ? c.field_958.field_2103 : c.field_959.field_2103;
                byte[] aW = sky ? cW.field_958.field_2103 : cW.field_959.field_2103;
                byte[] aE = sky ? cE.field_958.field_2103 : cE.field_959.field_2103;
                byte[] aN = sky ? cN.field_958.field_2103 : cN.field_959.field_2103;
                byte[] aS = sky ? cS.field_958.field_2103 : cS.field_959.field_2103;
                int height = c.heightmap[lz << 4 | lx] & 255;
                byte[] tiles = c.tiles;
                for (int y = u.field_1675; y <= u.field_1678; y++) {
                    int idx = col | y;
                    int current = get(arr, idx);
                    int id = tiles[idx] & 255;
                    int opacity = OPACITY[id];
                    if (opacity == 0) opacity = 1;
                    int source = sky ? (y >= height ? 15 : 0) : EMITTANCE[id];
                    int value;
                    if (opacity >= 15 && source == 0) {
                        value = 0;
                    } else {
                        int ym = y > 0 ? y - 1 : 0, yp = y < 127 ? y + 1 : 127; // Level.method_164 clamps y
                        value = get(aW, colW | y);
                        int v = get(aE, colE | y);
                        if (v > value) value = v;
                        v = get(arr, col | ym);
                        if (v > value) value = v;
                        v = get(arr, col | yp);
                        if (v > value) value = v;
                        v = get(aN, colN | y);
                        if (v > value) value = v;
                        v = get(aS, colS | y);
                        if (v > value) value = v;
                        value -= opacity;
                        if (value < 0) value = 0;
                        if (source > value) value = source;
                    }
                    if (current != value) {
                        // Level.method_205
                        set(arr, idx, value);
                        c.field_967 = true;
                        // Listeners mark the 3x3x3 neighbourhood for re-rendering. For a block inside a section that is
                        // exactly the section itself, so a repeat for the same section within this update is a no-op.
                        if (batch) {
                            // collect the affected sections (3x3x3 block neighbourhood); notified once at the end
                            int sx0 = (x - 1) >> 4, sx1 = (x + 1) >> 4, sy0 = (y - 1) >> 4, sy1 = (y + 1) >> 4, sz0 = (z - 1) >> 4, sz1 = (z + 1) >> 4;
                            if (sx0 == sx1 && sy0 == sy1 && sz0 == sz1) {
                                touch(sx0, sy0, sz0);
                            } else {
                                for (int a = sx0; a <= sx1; a++) for (int b = sy0; b <= sy1; b++) for (int d = sz0; d <= sz1; d++) touch(a, b, d);
                            }
                        } else {
                            int ly = y & 15;
                            boolean interior = lx != 0 && lx != 15 && lz != 0 && lz != 15 && ly != 0 && ly != 15;
                            long key = interior ? ((long) (x >> 4) << 40) ^ ((long) (z >> 4) << 8) ^ (y >> 4) : Long.MIN_VALUE;
                            if (!interior || key != lastNotified) {
                                for (int i = 0, ln = listeners.size(); i < ln; i++) ((LevelListener) listeners.get(i)).method_1149(x, y, z);
                                lastNotified = key;
                            }
                        }
                        if (noSkyNeighbours) continue;
                        int spread = value - 1;
                        if (spread < 0) spread = 0;
                        neighbour(level, sky, x - 1, y, z, spread);
                        neighbour(level, sky, x, y - 1, z, spread);
                        neighbour(level, sky, x, y, z - 1, spread);
                        if (x + 1 >= u.field_1677) neighbour(level, sky, x + 1, y, z, spread);
                        if (y + 1 >= u.field_1678) neighbour(level, sky, x, y + 1, z, spread);
                        if (z + 1 >= u.field_1679) neighbour(level, sky, x, y, z + 1, spread);
                    }
                }
            }
        }
        clearWindow(n);
        if (batch && touchedCount > 0) {
            for (int i = 0; i < touchedCount; i++) {
                long k = touched[i];
                dev.quicksilver.render.LightRebuilds.sectionLightChanged(wr, (int) (k >> 42), (int) (k << 22 >> 43), (int) (k << 43 >> 43));
            }
        }
        statFast++;
        return true;
    }

    /** Level.method_165: queue a single-block update if the neighbour's light doesn't match what it should be. */
    private static void neighbour(Level level, boolean sky, int x, int y, int z, int value) {
        if (y < 0 || y >= 128) return; // isTileLoaded
        Chunk c = chunk(x, z);
        if (c == null) {
            // outside the window (can't happen for x/z +-1 of a processed column, but stay exact)
            level.method_165(sky ? LightType.field_2757 : LightType.field_2758, x, y, z, value);
            return;
        }
        int idx = (x & 15) << 11 | (z & 15) << 7 | y;
        if (sky) {
            if (y >= (c.heightmap[(z & 15) << 4 | (x & 15)] & 255)) value = 15;
        } else {
            int e = EMITTANCE[c.tiles[idx] & 255];
            if (e > value) value = e;
        }
        int current = get(sky ? c.field_958.field_2103 : c.field_959.field_2103, idx);
        if (current != value) enqueue(sky ? LightType.field_2757 : LightType.field_2758, x, y, z);
    }

    private static long[] touched = new long[64];
    private static int touchedCount;
    private static long lastTouched;

    /** key: x (22 bits) << 42 | y (21 bits) << 21 | z (21 bits), each sign-extended on decode */
    private static void touch(int cx, int cy, int cz) {
        long k = ((long) cx << 42) | (((long) cy & 0x1FFFFF) << 21) | ((long) cz & 0x1FFFFF);
        if (k == lastTouched) return;
        lastTouched = k;
        for (int i = touchedCount - 1, stop = Math.max(0, touchedCount - 8); i >= stop; i--) if (touched[i] == k) return;
        if (touchedCount == touched.length) {
            // dedupe fully before growing
            long[] t = java.util.Arrays.copyOf(touched, touchedCount);
            java.util.Arrays.sort(t);
            int w = 0;
            for (int i = 0; i < t.length; i++) if (i == 0 || t[i] != t[i - 1]) t[w++] = t[i];
            touchedCount = w;
            if (w > touched.length / 2) touched = java.util.Arrays.copyOf(touched, touched.length * 2);
            System.arraycopy(t, 0, touched, 0, w);
        }
        touched[touchedCount++] = k;
    }

    private static List<Object> queue;

    /**
     * Level.method_166/167 for a single block whose chunk is known to be loaded (it is in the window): try to merge
     * into one of the last five queued updates, else append. Same result, without the bounds/loaded checks and chunk
     * lookups vanilla repeats for every call.
     */
    private static void enqueue(LightType type, int x, int y, int z) {
        List<Object> q = queue;
        int size = q.size();
        int k = size < 5 ? size : 5;
        for (int i = 0; i < k; i++) {
            class_417 e = (class_417) q.get(size - i - 1);
            if (e.field_1673 == type && e.method_1401(x, y, z, x, y, z)) return;
        }
        q.add(new class_417(type, x, y, z, x, y, z));
        if (q.size() > 1000000) {
            System.out.println("More than 1000000 updates, aborting lighting updates");
            q.clear();
        }
    }

    private static Chunk chunk(int x, int z) {
        int i = (x >> 4) - winX, j = (z >> 4) - winZ;
        if (i < 0 || j < 0 || i >= winW || j >= winH) return null;
        return window[i * winH + j];
    }

    private static int get(byte[] a, int idx) {
        int b = a[idx >> 1];
        return (idx & 1) == 0 ? b & 15 : (b >> 4) & 15;
    }

    private static void set(byte[] a, int idx, int v) {
        int h = idx >> 1;
        if ((idx & 1) == 0) a[h] = (byte) (a[h] & 240 | v & 15);
        else a[h] = (byte) (a[h] & 15 | (v & 15) << 4);
    }

    private static void clearWindow(int n) {
        for (int i = 0; i < n; i++) window[i] = null;
    }
}
