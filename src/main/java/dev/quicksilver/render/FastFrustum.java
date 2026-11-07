package dev.quicksilver.render;

import dev.quicksilver.mixin.render.FrustumAccessor;
import net.minecraft.class_573;
import net.minecraft.class_66;
import net.minecraft.class_68;

/**
 * Replacement for WorldRenderer.method_1550 (per-frame frustum update of every chunk section). Vanilla tests each
 * invisible section every frame with up to 48 double-precision dot products, and only re-tests visible ones every
 * 16th frame (so sections behind the camera keep being drawn for a while). This tests whole 128-block regions first
 * and only tests individual sections in regions that straddle the frustum edge - exactly, every frame.
 */
public final class FastFrustum {
    private FastFrustum() {}

    public static final int OUTSIDE = 0, PARTIAL = 1, INSIDE = 2;
    private static final float[] P = new float[24]; // 6 planes: a b c d
    private static double camX, camY, camZ;
    private static boolean valid;
    public static int statRegionTests, statSectionTests;

    /** @return false if the frustum isn't the vanilla implementation (then vanilla code must run). */
    public static boolean load(class_68 f) {
        if (!(f instanceof class_573)) {
            valid = false;
            return false;
        }
        FrustumAccessor a = (FrustumAccessor) f;
        float[][] m = a.qs$data().field_285;
        for (int i = 0; i < 6; i++) {
            P[i * 4] = m[i][0];
            P[i * 4 + 1] = m[i][1];
            P[i * 4 + 2] = m[i][2];
            P[i * 4 + 3] = m[i][3];
        }
        camX = a.qs$x();
        camY = a.qs$y();
        camZ = a.qs$z();
        valid = true;
        return true;
    }

    public static boolean valid() {
        return valid;
    }

    /** Classify a world-space box. Matches vanilla's test: a box is visible unless all corners are behind one plane. */
    public static int test(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        float x0 = (float) (minX - camX), y0 = (float) (minY - camY), z0 = (float) (minZ - camZ);
        float x1 = (float) (maxX - camX), y1 = (float) (maxY - camY), z1 = (float) (maxZ - camZ);
        boolean inside = true;
        float[] p = P;
        for (int i = 0; i < 24; i += 4) {
            float a = p[i], b = p[i + 1], c = p[i + 2], d = p[i + 3];
            // farthest corner along the normal
            float px = a > 0 ? x1 : x0, py = b > 0 ? y1 : y0, pz = c > 0 ? z1 : z0;
            if (a * px + b * py + c * pz + d <= 0) return OUTSIDE;
            float nx = a > 0 ? x0 : x1, ny = b > 0 ? y0 : y1, nz = c > 0 ? z0 : z1;
            if (a * nx + b * ny + c * nz + d <= 0) inside = false;
        }
        return inside ? INSIDE : PARTIAL;
    }

    /** Region (8x8x8 sections, 128 blocks) visibility, including the 6 block margin vanilla adds to section boxes. */
    public static int testRegion(int ox, int oy, int oz) {
        return test(ox - 6, oy - 6, oz - 6, ox + 134, oy + 134, oz + 134);
    }

    public static long totalNs;

        public static void update(class_66[] sections) {
        long qsT0 = System.nanoTime();
        try {
            updateImpl(sections);
        } finally {
            totalNs += System.nanoTime() - qsT0;
        }
    }

    /** Sections of one 128-block region, so that a region whose visibility didn't change is skipped entirely. */
    private static final class Group {
        final int ox, oy, oz;
        class_66[] members = new class_66[64];
        int size;
        int lastState = -1;
        boolean dirty = true;

        Group(int ox, int oy, int oz) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
        }

        void add(class_66 s) {
            if (size == members.length) members = java.util.Arrays.copyOf(members, size * 2);
            members[size] = s;
            ((QsSection) s).qs$setFrustumGroup(this, size);
            size++;
            dirty = true;
        }

        void remove(class_66 s) {
            int i = ((QsSection) s).qs$frustumIndex();
            if (i < 0 || i >= size || members[i] != s) return;
            class_66 last = members[--size];
            members[i] = last;
            members[size] = null;
            if (last != s) ((QsSection) last).qs$setFrustumGroup(this, i);
            ((QsSection) s).qs$setFrustumGroup(null, -1);
        }
    }

    private static final java.util.HashMap<Long, Group> GROUPS = new java.util.HashMap<>();
    private static Group[] groupList = new Group[0];
    private static boolean groupsChanged = true;
    private static class_66[] groupedArray;

    private static long key(int rx, int ry, int rz) {
        return ((long) rx & 0x1FFFFF) << 42 | ((long) ry & 0x1FFFFF) << 21 | ((long) rz & 0x1FFFFF);
    }

    private static void place(class_66 s) {
        QsSection q = (QsSection) s;
        int rx = s.field_231 >> 7, ry = s.field_232 >> 7, rz = s.field_233 >> 7;
        Object cur = q.qs$frustumGroup();
        if (cur instanceof Group g && g.ox == rx << 7 && g.oy == ry << 7 && g.oz == rz << 7) {
            g.dirty = true; // reset sections lost their flag
            return;
        }
        if (cur instanceof Group g) g.remove(s);
        Group g = GROUPS.get(key(rx, ry, rz));
        if (g == null) {
            g = new Group(rx << 7, ry << 7, rz << 7);
            GROUPS.put(key(rx, ry, rz), g);
            groupsChanged = true;
        }
        g.add(s);
    }

    /** Called when a section was repositioned. */
    public static void sectionMoved(class_66 s) {
        if (groupedArray != null) place(s);
    }

    private static void updateImpl(class_66[] sections) {
        if (sections != groupedArray) {
            // new renderer (reload / render distance change): regroup everything
            for (Group g : GROUPS.values()) for (int i = 0; i < g.size; i++) ((QsSection) g.members[i]).qs$setFrustumGroup(null, -1);
            GROUPS.clear();
            groupedArray = sections;
            for (class_66 s : sections) place(s);
            groupsChanged = true;
        }
        if (groupsChanged) {
            GROUPS.values().removeIf(g -> g.size == 0);
            groupList = GROUPS.values().toArray(new Group[0]);
            groupsChanged = false;
        }
        int regionTests = 0, sectionTests = 0;
        for (Group g : groupList) {
            if (g.size == 0) continue;
            int state = testRegion(g.ox, g.oy, g.oz);
            regionTests++;
            if (state == g.lastState && state != PARTIAL && !g.dirty) continue; // nothing changed for its sections
            g.lastState = state;
            g.dirty = false;
            class_66[] m = g.members;
            if (state == OUTSIDE) {
                for (int i = 0; i < g.size; i++) m[i].field_243 = false;
            } else if (state == INSIDE) {
                for (int i = 0; i < g.size; i++) m[i].field_243 = true;
            } else {
                for (int i = 0; i < g.size; i++) {
                    class_66 s = m[i];
                    int x = s.field_231, y = s.field_232, z = s.field_233;
                    sectionTests++;
                    s.field_243 = test(x - 6, y - 6, z - 6, x + s.field_234 + 6, y + s.field_235 + 6, z + s.field_236 + 6) != OUTSIDE;
                }
            }
        }
        statRegionTests = regionTests;
        statSectionTests = sectionTests;
    }
}
