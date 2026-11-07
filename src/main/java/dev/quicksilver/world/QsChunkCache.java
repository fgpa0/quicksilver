package dev.quicksilver.world;

public interface QsChunkCache {
    void qs$invalidateLast();

    /** The chunk if it is loaded; never loads or generates. */
    net.minecraft.level.chunk.Chunk qs$peek(int x, int z);
}
