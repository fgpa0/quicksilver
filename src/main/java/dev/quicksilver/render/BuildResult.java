package dev.quicksilver.render;

public final class BuildResult {
    public final BuildTask task;
    public final PassResult[] passes = new PassResult[2];
    public boolean lit;
    public long visGraph = VisGraph.ALL;
    public Throwable error;
    public long buildNs;

    public BuildResult(BuildTask task) {
        this.task = task;
    }

    public static final class PassResult {
        public boolean started;
        public boolean rendered;
        public MeshCapture mesh;
        public dev.quicksilver.render.vbo.CompactMesh compact;
        /** Blocks that must be rendered on the main thread: packed local coordinates (x | y << 8 | z << 16, 0..15). */
        public int[] deferred;
        public int deferredCount;
    }
}
