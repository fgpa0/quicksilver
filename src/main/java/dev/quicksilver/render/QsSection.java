package dev.quicksilver.render;

/** Extra per-section state added to vanilla's chunk renderer (class_66). */
public interface QsSection {
    int qs$epoch();

    void qs$bumpEpoch();

    boolean qs$inFlight();

    void qs$setInFlight(boolean v);

    boolean qs$forceSync();

    void qs$setForceSync(boolean v);

    int qs$listBase();

    void qs$setInitialized(boolean v);

    boolean qs$initialized();

    java.util.List qs$globalTileEntities();

    int qs$slot();

    void qs$setSlot(int i);

    long qs$visGraph();

    void qs$setVisGraph(long g);

    int qs$visibleFrame();

    void qs$setVisibleFrame(int f);

    int qs$entryMask();

    void qs$setEntryMask(int m);

    dev.quicksilver.render.vbo.SectionAlloc qs$alloc(int pass);

    void qs$setAlloc(int pass, dev.quicksilver.render.vbo.SectionAlloc a);

    long qs$lightPendingSince();

    void qs$setLightPendingSince(long t);

    Object qs$frustumGroup();

    void qs$setFrustumGroup(Object g, int index);

    int qs$frustumIndex();

    long qs$fadeStart();

    void qs$setFadeStart(long t);
}
