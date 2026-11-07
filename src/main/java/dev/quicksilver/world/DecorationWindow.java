package dev.quicksilver.world;

import dev.quicksilver.Quicksilver;
import net.minecraft.level.Level;
import net.minecraft.level.chunk.Chunk;

/**
 * A tiny chunk cache in front of Level.getTileId / Level.setTileInChunk, live only while a chunk is being
 * decorated on the game thread.
 *
 * <p>Decoration is thousands of single block reads and writes spread over the four chunks around the one being
 * decorated, and each one walks Level -> getChunkFromCache -> ServerChunkCache.getChunk. Nothing loads or unloads
 * chunks while decoration runs, so the chunks found during it stay valid for its duration.
 */
public final class DecorationWindow {
    private DecorationWindow() {}

    private static Level active;
    private static final Chunk[] CACHE = new Chunk[4];

    public static void open(Level level) {
        active = level;
        CACHE[0] = CACHE[1] = CACHE[2] = CACHE[3] = null;
    }

    public static void close() {
        if (active == null) return;
        active = null;
        CACHE[0] = CACHE[1] = CACHE[2] = CACHE[3] = null;
    }

    /** Replaces the getChunkFromCache call inside Level.getTileId and Level.setTileInChunk. */
    public static Chunk chunk(Level level, int cx, int cz) {
        if (level != active || !Quicksilver.isClientThread()) return level.getChunkFromCache(cx, cz);
        int slot = (cx * 3 + cz) & 3;
        Chunk c = CACHE[slot];
        if (c != null && c.x == cx && c.z == cz) return c;
        c = level.getChunkFromCache(cx, cz);
        CACHE[slot] = c;
        return c;
    }
}
