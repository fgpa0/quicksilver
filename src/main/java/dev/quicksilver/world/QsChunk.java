package dev.quicksilver.world;

/** Extra per-chunk state (mixed into Chunk). */
public interface QsChunk {
    /** Number of randomly ticking blocks in a 16-block-high section (recounted lazily after changes). */
    int qs$randomTickCount(int section);

    /** Incremented by every block or metadata write; lets background decoration detect that its snapshot went stale. */
    int qs$mod();

    /** Records writes into the journal (shadow chunks of background decoration); null stops recording. */
    void qs$setJournal(DecoJournal journal, int index);

    /** Applies one journal entry the way vanilla's write would, minus the block hooks (already run on the shadow). */
    void qs$replay(int entry);

    /** Repositions a pooled shadow chunk. */
    void qs$setPos(int x, int z);
}
