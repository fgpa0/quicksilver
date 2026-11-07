package dev.quicksilver.render;

/**
 * Face-to-face visibility of a 16^3 section (Tommaso Checchi's "advanced cave culling" algorithm): flood-fill the
 * non-opaque cells and record which pairs of faces are connected through air/transparent blocks.
 * Faces: 0 -Y, 1 +Y, 2 -Z, 3 +Z, 4 -X, 5 +X. Result: bit (a * 6 + b) set when face a sees face b.
 */
public final class VisGraph {
    private VisGraph() {}

    public static final long ALL = (1L << 36) - 1;

    private static final ThreadLocal<int[]> QUEUE = ThreadLocal.withInitial(() -> new int[4096]);
    private static final ThreadLocal<boolean[]> SEEN = ThreadLocal.withInitial(() -> new boolean[4096]);

    /** @param opaque 4096 cells, index x | z << 4 | y << 8 */
    public static long compute(boolean[] opaque, int opaqueCount) {
        if (opaqueCount < 256) return ALL; // can't separate two faces with fewer than 256 blocks
        if (opaqueCount == 4096) return 0L;
        boolean[] seen = SEEN.get();
        System.arraycopy(opaque, 0, seen, 0, 4096);
        int[] q = QUEUE.get();
        long result = 0;
        // only start flood fills from the boundary: interior pockets can't connect faces
        for (int i = 0; i < 4096; i++) {
            int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
            if (x != 0 && x != 15 && y != 0 && y != 15 && z != 0 && z != 15) continue;
            if (seen[i]) continue;
            int faces = 0;
            int head = 0, tail = 0;
            q[tail++] = i;
            seen[i] = true;
            while (head < tail) {
                int c = q[head++];
                int cx = c & 15, cz = (c >> 4) & 15, cy = c >> 8;
                if (cy == 0) faces |= 1;
                if (cy == 15) faces |= 2;
                if (cz == 0) faces |= 4;
                if (cz == 15) faces |= 8;
                if (cx == 0) faces |= 16;
                if (cx == 15) faces |= 32;
                if (cx > 0 && !seen[c - 1]) { seen[c - 1] = true; q[tail++] = c - 1; }
                if (cx < 15 && !seen[c + 1]) { seen[c + 1] = true; q[tail++] = c + 1; }
                if (cz > 0 && !seen[c - 16]) { seen[c - 16] = true; q[tail++] = c - 16; }
                if (cz < 15 && !seen[c + 16]) { seen[c + 16] = true; q[tail++] = c + 16; }
                if (cy > 0 && !seen[c - 256]) { seen[c - 256] = true; q[tail++] = c - 256; }
                if (cy < 15 && !seen[c + 256]) { seen[c + 256] = true; q[tail++] = c + 256; }
            }
            for (int a = 0; a < 6; a++) {
                if ((faces & (1 << a)) == 0) continue;
                for (int b = 0; b < 6; b++) {
                    if ((faces & (1 << b)) != 0) result |= 1L << (a * 6 + b);
                }
            }
            if (result == ALL) return ALL;
        }
        return result;
    }

    public static boolean connected(long graph, int a, int b) {
        return (graph & (1L << (a * 6 + b))) != 0;
    }
}
