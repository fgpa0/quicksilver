package dev.quicksilver.render;

import dev.quicksilver.QsConfig;
import dev.quicksilver.mixin.render.WorldRendererAccessor;
import net.minecraft.block.BlockBase;
import net.minecraft.class_66;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.level.Level;
import net.minecraft.level.chunk.Chunk;
import net.minecraft.util.maths.MathHelper;

/**
 * CPU occlusion culling: a breadth-first search from the camera's section through the section grid, only crossing
 * from one face of a section to another when the section's visibility graph says they are connected through
 * non-opaque blocks, and never walking back towards the camera. Sections not reached are hidden. Replaces vanilla's
 * hardware occlusion queries (which stall the CPU waiting for the GPU and flicker).
 */
public final class CaveCuller {
    private CaveCuller() {}

    private static final int[][] DIRS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    private static int frame = 1;
    private static boolean active;
    public static int statVisible, statTotal;

    /** Blocks that fully block sight: dynamic isFullOpaque() (leaves depend on fancy graphics) and a plain cube. */
    public static volatile boolean[] OPAQUE = new boolean[256];
    private static volatile boolean opaqueReady;

    /** The table is filled on first use: before, every block counted as see-through and every mesh got an "all connected" graph. */
    public static boolean[] opaqueTable() {
        if (!opaqueReady) {
            synchronized (CaveCuller.class) {
                if (!opaqueReady) {
                    refreshOpaque();
                    opaqueReady = true;
                }
            }
        }
        return OPAQUE;
    }

    private static void refreshOpaque() {
        BlockBase[] byId = BlockBase.BY_ID;
        boolean[] t = new boolean[byId.length];
        for (int i = 1; i < byId.length; i++) {
            BlockBase b = byId[i];
            try {
                t[i] = b != null && b.isFullOpaque() && b.isFullCube() && b.getRenderType() == 0
                        && b.getRenderPass() == 0 && !(b instanceof net.minecraft.block.Leaves);
            } catch (Throwable e) {
                t[i] = false;
            }
        }
        OPAQUE = t;
    }

    public static boolean active() {
        return active;
    }

    /**
     * Flat mirror of the section grid, indexed like WorldRenderer's section array ((z * sy + y) * sx + x, x and z
     * wrapped). POS holds the section coordinates each slot currently has (or NONE) and GRAPH its visibility graph;
     * both are kept up to date as sections move or get new graphs, so the flood fill never has to dereference a
     * section object. MARK/ENTRY are the flood fill's per-slot state for the current result.
     */
    private static final long NONE = Long.MIN_VALUE;
    private static long[] POS = new long[0], GRAPH = new long[0];
    private static int[] MARK = new int[0];
    private static byte[] ENTRY = new byte[0];
    private static int[] qIdx = new int[1024], qX = new int[1024], qY = new int[1024], qZ = new int[1024];
    private static byte[] qFace = new byte[1024];
    private static class_66[] mirrored;
    private static int mSx, mSy, mSz;
    private static long lastFullSyncNs;

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0xFFFFFF) << 32) | ((long) (y & 0xFF) << 24) | (z & 0xFFFFFF);
    }

    private static int wrap(int v, int size) {
        int r = v % size;
        return r < 0 ? r + size : r;
    }

    private static int slot(int x, int y, int z) {
        return (wrap(z, mSz) * mSy + y) * mSx + wrap(x, mSx);
    }

    /** Copy one section's position and graph into the mirror (main thread). */
    private static void mirror(class_66 s) {
        if (mirrored == null || s == null) return;
        int x = s.field_231 >> 4, y = s.field_232 >> 4, z = s.field_233 >> 4;
        if (y < 0 || y >= mSy) return;
        int i = slot(x, y, z);
        if (i < 0 || i >= POS.length || mirrored[i] != s) return; // not in the mirrored array (renderer was replaced)
        POS[i] = pack(x, y, z);
        GRAPH[i] = ((QsSection) s).qs$visGraph();
        ((QsSection) s).qs$setSlot(i);
    }

    /** Rebuild the whole mirror (new renderer, or a periodic safety resync). */
    private static void fullSync(class_66[] sections, int sx, int sy, int sz) {
        int n = sections.length;
        if (POS.length != n) {
            POS = new long[n];
            GRAPH = new long[n];
            MARK = new int[n];
            ENTRY = new byte[n];
        }
        mirrored = sections;
        mSx = sx;
        mSy = sy;
        mSz = sz;
        java.util.Arrays.fill(POS, NONE);
        for (int i = 0; i < n; i++) {
            class_66 s = sections[i];
            if (s == null) continue;
            int x = s.field_231 >> 4, y = s.field_232 >> 4, z = s.field_233 >> 4;
            // only where the renderer's own lookup would find it
            if (y < 0 || y >= sy || slot(x, y, z) != i) continue;
            POS[i] = pack(x, y, z);
            GRAPH[i] = ((QsSection) s).qs$visGraph();
            ((QsSection) s).qs$setSlot(i);
        }
        lastFullSyncNs = System.nanoTime();
    }

    public static boolean isVisible(class_66 s) {
        if (!active) return true;
        int i = ((QsSection) s).qs$slot();
        return i >= 0 && i < MARK.length && MARK[i] == frame;
    }

    /** Bumped whenever any section's visibility graph or position changes. */
    public static int graphVersion;
    private static boolean urgentChange;
    private static long lastComputeNs;

    /**
     * A section's visibility graph changed (called after the new graph is stored). While flying, sections are
     * rebuilt constantly (thousands per second at 32 chunks), so changes far from the camera are picked up at most
     * every 250 ms; changes within two sections of the camera (digging, building) still apply immediately.
     */
    public static void graphChanged(class_66 s) {
        graphVersion++;
        mirror(s);
        int dx = (s.field_231 >> 4) - lastCx, dy = (s.field_232 >> 4) - lastCy, dz = (s.field_233 >> 4) - lastCz;
        if (dx >= -2 && dx <= 2 && dy >= -2 && dy <= 2 && dz >= -2 && dz <= 2) urgentChange = true;
    }

    /** A section was repositioned (after its new coordinates are set). */
    public static void sectionMoved(class_66 s) {
        graphVersion++;
        mirror(s);
    }

    private static int lastGraphVersion = -1, lastCx, lastCy, lastCz;
    private static class_66[] lastArray;

    /** Called once per frame before the opaque pass is drawn. */
    public static long totalNs;

    public static void update(WorldRenderer wr, double camX, double camY, double camZ) {
        long qsT0 = System.nanoTime();
        try {
            updateImpl(wr, camX, camY, camZ);
        } finally {
            totalNs += System.nanoTime() - qsT0;
        }
    }

    private static void updateImpl(WorldRenderer wr, double camX, double camY, double camZ) {
        active = false;
        if (!QsConfig.caveCulling) return;
        opaqueTable();
        if ((frame & 63) == 0 || OPAQUE.length != BlockBase.BY_ID.length) refreshOpaque();
        WorldRendererAccessor acc = (WorldRendererAccessor) wr;
        class_66[] sections = acc.qs$sections();
        if (sections == null || sections.length == 0) return;
        int sx = acc.qs$sizeX(), sy = acc.qs$sizeY(), sz = acc.qs$sizeZ();
        if (sx * sy * sz != sections.length) return;
        int cx = MathHelper.floor(camX) >> 4, cy = MathHelper.floor(camY) >> 4, cz = MathHelper.floor(camZ) >> 4;
        if (cy < 0 || cy >= sy) return; // camera above/below the world: no culling this frame
        // camera inside an opaque block (spectator-style noclip): the flood fill would hide everything, so don't cull
        net.minecraft.client.Minecraft mc = dev.quicksilver.client.RenderDistance.MinecraftHolder.get();
        if (mc != null && mc.level != null) {
            int id = mc.level.getTileId(MathHelper.floor(camX), MathHelper.floor(camY), MathHelper.floor(camZ));
            if (id > 0 && id < OPAQUE.length && OPAQUE[id]) return;
        }
        boolean newArray = sections != mirrored || sx != mSx || sy != mSy || sz != mSz;
        long now = System.nanoTime();
        if (newArray || now - lastFullSyncNs > 2_000_000_000L) fullSync(sections, sx, sy, sz);
        int startSlot = slot(cx, cy, cz);
        long startPos = pack(cx, cy, cz);
        if (POS[startSlot] != startPos) return;
        // the result only depends on the camera section and the graphs: reuse it while nothing changed
        boolean sameCamera = cx == lastCx && cy == lastCy && cz == lastCz && sections == lastArray && !newArray;
        if (sameCamera && (graphVersion == lastGraphVersion || (!urgentChange && now - lastComputeNs < 250_000_000L))) {
            active = true;
            return;
        }
        urgentChange = false;
        lastComputeNs = now;
        lastGraphVersion = graphVersion;
        lastCx = cx;
        lastCy = cy;
        lastCz = cz;
        lastArray = sections;
        frame++;
        final int f0 = frame;
        final long[] pos = POS, graphs = GRAPH;
        final int[] mark = MARK;
        final byte[] entryMask = ENTRY;
        int n = sections.length;
        if (qIdx.length < n * 2) {
            int len = Math.max(n * 2, 1024);
            qIdx = new int[len];
            qX = new int[len];
            qY = new int[len];
            qZ = new int[len];
            qFace = new byte[len];
        }
        int[] qi = qIdx, qx = qX, qy = qY, qz = qZ;
        byte[] qf = qFace;
        int head = 0, tail = 0;
        mark[startSlot] = f0;
        entryMask[startSlot] = 0;
        qi[0] = startSlot;
        qx[0] = cx;
        qy[0] = cy;
        qz[0] = cz;
        qf[0] = -1;
        tail = 1;
        int visible = 1;
        while (head < tail) {
            int si = qi[head], scx = qx[head], scy = qy[head], scz = qz[head], entry = qf[head];
            head++;
            long graph = graphs[si];
            for (int f = 0; f < 6; f++) {
                int ddx = 0, ddy = 0, ddz = 0;
                switch (f) {
                    // never step back towards the camera (same rule as vanilla-style (s - cam) * d < 0)
                    case 0 -> { if (scy > cy) continue; ddy = -1; }
                    case 1 -> { if (scy < cy) continue; ddy = 1; }
                    case 2 -> { if (scz > cz) continue; ddz = -1; }
                    case 3 -> { if (scz < cz) continue; ddz = 1; }
                    case 4 -> { if (scx > cx) continue; ddx = -1; }
                    default -> { if (scx < cx) continue; ddx = 1; }
                }
                if (entry >= 0 && !VisGraph.connected(graph, entry, f)) continue;
                int nx = scx + ddx, ny = scy + ddy, nz = scz + ddz;
                if (ny < 0 || ny >= sy) continue;
                int ni = slot(nx, ny, nz);
                if (pos[ni] != pack(nx, ny, nz)) continue;
                int in = f ^ 1; // entered through the opposite face
                int mask;
                if (mark[ni] != f0) {
                    mark[ni] = f0;
                    mask = 0;
                    visible++;
                } else {
                    mask = entryMask[ni];
                }
                // a section can be entered through several faces; each one may open different exits
                if ((mask & (1 << in)) != 0) continue;
                entryMask[ni] = (byte) (mask | (1 << in));
                if (tail >= qi.length) { // can't happen (each slot is queued at most 6 times... grow anyway)
                    int len = qi.length * 2;
                    qIdx = qi = java.util.Arrays.copyOf(qi, len);
                    qX = qx = java.util.Arrays.copyOf(qx, len);
                    qY = qy = java.util.Arrays.copyOf(qy, len);
                    qZ = qz = java.util.Arrays.copyOf(qz, len);
                    qFace = qf = java.util.Arrays.copyOf(qf, len);
                }
                qi[tail] = ni;
                qx[tail] = nx;
                qy[tail] = ny;
                qz[tail] = nz;
                qf[tail++] = (byte) in;
            }
        }
        statVisible = visible;
        statTotal = n;
        active = true;
    }

    /** Is the block position inside a section that was found visible this frame? Unknown positions count as visible. */
    public static boolean isBlockVisible(int x, int y, int z) {
        if (!active || mirrored == null) return true;
        int sy = y >> 4;
        if (sy < 0 || sy >= mSy) return true;
        int i = slot(x >> 4, sy, z >> 4);
        return POS[i] != pack(x >> 4, sy, z >> 4) || MARK[i] == frame;
    }

    public static boolean isBoxVisible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!active || mirrored == null) return true;
        int x0 = MathHelper.floor(minX) >> 4, x1 = MathHelper.floor(maxX) >> 4;
        int y0 = Math.max(0, MathHelper.floor(minY) >> 4), y1 = Math.min(mSy - 1, MathHelper.floor(maxY) >> 4);
        int z0 = MathHelper.floor(minZ) >> 4, z1 = MathHelper.floor(maxZ) >> 4;
        if (y0 > y1) return true;
        if ((x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 27) return true; // huge entity: don't bother
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int i = slot(x, y, z);
                    if (POS[i] != pack(x, y, z) || MARK[i] == frame) return true;
                }
            }
        }
        return false;
    }

    /** Visibility graph computed directly from the world (main thread, after a vanilla synchronous build). */
    public static long graphFromWorld(Level level, int x0, int y0, int z0) {
        boolean[] full = opaqueTable();
        boolean[] opaque = new boolean[4096];
        int count = 0;
        Chunk c = level.getChunkFromCache(x0 >> 4, z0 >> 4);
        for (int y = 0; y < 16; y++) {
            int wy = y0 + y;
            if (wy < 0 || wy >= 128) continue;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (full[c.getTileId(x, wy, z)]) {
                        opaque[x | z << 4 | y << 8] = true;
                        count++;
                    }
                }
            }
        }
        return VisGraph.compute(opaque, count);
    }
}
