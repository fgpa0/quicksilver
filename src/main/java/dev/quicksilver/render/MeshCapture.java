package dev.quicksilver.render;

import java.util.Arrays;

/**
 * Vertex data recorded by a capturing tessellator: 8 ints per vertex in the vanilla tessellator layout
 * (x, y, z, u, v, colour, normal, unused).
 */
public final class MeshCapture {
    public int[] data = new int[8 * 1024];
    public int intCount;
    public int vertexCount;
    public boolean hasTexture, hasColour, hasNormals;
    // draw segments: mode, first vertex, count
    public int[] segments = new int[12];
    public int segmentCount;

    public void reset() {
        intCount = 0;
        vertexCount = 0;
        segmentCount = 0;
        hasTexture = hasColour = hasNormals = false;
    }

    public boolean isEmpty() {
        return vertexCount == 0;
    }

    public void append(int[] src, int ints, int vertices, int glMode, boolean tex, boolean col, boolean norm) {
        if (intCount + ints > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 2, intCount + ints));
        }
        System.arraycopy(src, 0, data, intCount, ints);
        // merge with previous segment if same mode
        if (segmentCount > 0 && segments[(segmentCount - 1) * 3] == glMode) {
            segments[(segmentCount - 1) * 3 + 2] += vertices;
        } else {
            if ((segmentCount + 1) * 3 > segments.length) segments = Arrays.copyOf(segments, segments.length * 2);
            segments[segmentCount * 3] = glMode;
            segments[segmentCount * 3 + 1] = vertexCount;
            segments[segmentCount * 3 + 2] = vertices;
            segmentCount++;
        }
        intCount += ints;
        vertexCount += vertices;
        hasTexture |= tex;
        hasColour |= col;
        hasNormals |= norm;
    }

    /** Copy out a compact version (the capture object itself is reused by the worker). */
    public MeshCapture copy() {
        MeshCapture c = new MeshCapture();
        c.data = Arrays.copyOf(data, intCount);
        c.intCount = intCount;
        c.vertexCount = vertexCount;
        c.hasTexture = hasTexture;
        c.hasColour = hasColour;
        c.hasNormals = hasNormals;
        c.segments = Arrays.copyOf(segments, segmentCount * 3);
        c.segmentCount = segmentCount;
        return c;
    }
}
