package dev.quicksilver.render.vbo;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.class_66;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;

/**
 * A render region (8x8x8 chunk sections) with one vertex buffer shared by all its sections. Space is handed out
 * with a first-fit free list; the buffer grows (GPU-side copy) when full.
 */
public final class VboRegion {
    public static final int SIZE_SECTIONS = 8;
    public static final int SIZE_BLOCKS = SIZE_SECTIONS * 16;

    public final long key;
    public final int originX, originY, originZ; // block coordinates
    int vbo;
    int capacity; // vertices
    int used;
    /** free blocks: offset -> length (vertices) */
    private final TreeMap<Integer, Integer> free = new TreeMap<>();
    /*
     * Per render pass, struct-of-arrays draw data of the sections in this region, iterated every frame:
     * dat[slot * STRIDE] = minX, minY, minZ, first[7], count[7].
     */
    static final int STRIDE = 3 + 2 * CompactMesh.BUCKETS;
    final class_66[][] sec = {new class_66[16], new class_66[16]};
    final SectionAlloc[][] own = {new SectionAlloc[16], new SectionAlloc[16]};
    final int[][] dat = {new int[16 * STRIDE], new int[16 * STRIDE]};
    final int[] count = new int[2];

    boolean isEmpty() {
        return count[0] == 0 && count[1] == 0;
    }

    void add(SectionAlloc a, int[] firsts, int[] counts) {
        int p = a.pass, slot = count[p]++;
        if (slot == sec[p].length) {
            sec[p] = java.util.Arrays.copyOf(sec[p], slot * 2);
            own[p] = java.util.Arrays.copyOf(own[p], slot * 2);
            dat[p] = java.util.Arrays.copyOf(dat[p], slot * 2 * STRIDE);
        }
        sec[p][slot] = a.section;
        own[p][slot] = a;
        a.slot = slot;
        int[] d = dat[p];
        int base = slot * STRIDE;
        d[base] = a.minX;
        d[base + 1] = a.minY;
        d[base + 2] = a.minZ;
        System.arraycopy(firsts, 0, d, base + 3, CompactMesh.BUCKETS);
        System.arraycopy(counts, 0, d, base + 3 + CompactMesh.BUCKETS, CompactMesh.BUCKETS);
    }

    private void removeSlot(SectionAlloc a) {
        int p = a.pass, slot = a.slot;
        if (slot < 0 || slot >= count[p] || own[p][slot] != a) return;
        int last = --count[p];
        if (slot != last) {
            sec[p][slot] = sec[p][last];
            SectionAlloc moved = own[p][last];
            own[p][slot] = moved;
            moved.slot = slot;
            System.arraycopy(dat[p], last * STRIDE, dat[p], slot * STRIDE, STRIDE);
        }
        sec[p][last] = null;
        own[p][last] = null;
        a.slot = -1;
    }

    /** bytes per vertex of this region's buffer (fixed at creation; all regions are dropped when the format changes) */
    final int stride = CompactMesh.currentStride();

    VboRegion(long key, int ox, int oy, int oz) {
        this.key = key;
        this.originX = ox;
        this.originY = oy;
        this.originZ = oz;
    }

    private void create(int vertices) {
        vbo = GL15.glGenBuffers();
        capacity = vertices;
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) vertices * stride, GL15.GL_DYNAMIC_DRAW);
        free.clear();
        free.put(0, vertices);
    }

    /** @return vertex offset of a block of the given size */
    int allocate(int vertices) {
        if (vbo == 0) create(Math.max(vertices * 2, 16384));
        for (Map.Entry<Integer, Integer> e : free.entrySet()) {
            if (e.getValue() >= vertices) {
                int off = e.getKey(), len = e.getValue();
                free.remove(off);
                if (len > vertices) free.put(off + vertices, len - vertices);
                used += vertices;
                return off;
            }
        }
        grow(Math.max(capacity * 2, capacity + vertices * 2));
        return allocate(vertices);
    }

    public static long growNs, growCount;

    private void grow(int newCapacity) {
        long qsT0 = System.nanoTime();
        try {
            growImpl(newCapacity);
        } finally {
            growNs += System.nanoTime() - qsT0;
            growCount++;
        }
    }

    private void growImpl(int newCapacity) {
        int old = vbo, oldCap = capacity;
        int nb = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, nb);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) newCapacity * stride, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, old);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, nb);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, 0, (long) oldCap * stride);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        GL15.glDeleteBuffers(old);
        vbo = nb;
        capacity = newCapacity;
        release(oldCap, newCapacity - oldCap);
    }

    void release(int off, int len) {
        if (len <= 0) return;
        // merge with neighbours
        Map.Entry<Integer, Integer> prev = free.floorEntry(off - 1);
        if (prev != null && prev.getKey() + prev.getValue() == off) {
            off = prev.getKey();
            len += prev.getValue();
            free.remove(prev.getKey());
        }
        Integer nextLen = free.get(off + len);
        if (nextLen != null) {
            free.remove(off + len);
            len += nextLen;
        }
        free.put(off, len);
    }

    void free(SectionAlloc a) {
        if (a.vertices > 0) {
            release(a.offset, a.vertices);
            used -= a.vertices;
        }
        removeSlot(a);
    }

    private static int staging;

    /**
     * Writes through a small orphaned staging buffer and a GPU-side copy: writing directly into a region buffer that
     * queued draws (or a pending grow copy) still use makes the driver wait for the GPU, which shows up as 40-60 ms
     * hitches once the GPU is busy (shaders).
     */
    void upload(int offset, ByteBuffer data) {
        if (staging == 0) staging = GL15.glGenBuffers();
        long bytes = data.remaining();
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, staging);
        GL15.glBufferData(GL31.GL_COPY_READ_BUFFER, bytes, GL15.GL_STREAM_DRAW);
        GL15.glBufferSubData(GL31.GL_COPY_READ_BUFFER, 0, data);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, vbo);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, (long) offset * stride, bytes);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
    }

    void delete() {
        if (vbo != 0) GL15.glDeleteBuffers(vbo);
        vbo = 0;
        capacity = 0;
        used = 0;
        free.clear();
        for (int p = 0; p < 2; p++) {
            java.util.Arrays.fill(sec[p], 0, count[p], null);
            java.util.Arrays.fill(own[p], 0, count[p], null);
            count[p] = 0;
        }
    }

    public long memoryBytes() {
        return (long) capacity * stride;
    }
}
