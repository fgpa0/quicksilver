package dev.quicksilver.render;

public interface QsTessellator {
    void qs$setCapture(MeshCapture capture);

    MeshCapture qs$getCapture();

    /** Vertices added since the last draw/flush. */
    int qs$vertexCount();
}
