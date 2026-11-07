package dev.quicksilver.world;

/**
 * Block writes made to the four shadow chunks of one background decoration, in order. One int each:
 * kind (2 bits: 0 = setTile, 1 = setTileWithMetadata, 2 = metadata only) | chunk (2) | meta (4) | id (8) | y (7) | x (4) | z (4).
 */
public final class DecoJournal {
    public int[] data = new int[8192];
    public int n;

    public void add(int entry) {
        if (n == data.length) data = java.util.Arrays.copyOf(data, n * 2);
        data[n++] = entry;
    }

    public void clear() {
        n = 0;
    }

    public static int pack(int kind, int chunk, int meta, int id, int y, int lx, int lz) {
        return kind << 29 | chunk << 27 | (meta & 15) << 23 | (id & 255) << 15 | y << 8 | lx << 4 | lz;
    }
}
