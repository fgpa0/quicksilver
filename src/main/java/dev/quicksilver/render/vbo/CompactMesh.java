package dev.quicksilver.render.vbo;

import dev.quicksilver.render.MeshCapture;
import java.util.Arrays;

/**
 * Chunk geometry in the VBO format: quads, 4 ints (16 bytes) per vertex - position (3 shorts in 1/256 block units
 * relative to the render region origin, offset by {@link #POS_BIAS}, plus 2 padding bytes), texture uv (2 shorts in
 * 1/32768 units) and colour (4 bytes) - sorted into 7 buckets by facing direction
 * so that whole groups of faces pointing away from the camera can be skipped.
 * Buckets: 0 down(-Y) 1 up(+Y) 2 north(-Z) 3 south(+Z) 4 west(-X) 5 east(+X) 6 any direction.
 */
public final class CompactMesh {
    public static final int INTS_PER_VERTEX = 4;
    /**
     * Extended (shader) format, 44 bytes: position (3 floats, relative to the region origin), uv (2 floats), colour
     * (4 bytes, ambient occlusion in alpha), light map (2 shorts: block light * 16, sky light * 16), normal (3 bytes +
     * pad), mc_Entity (2 unsigned shorts), mc_midTexCoord (2 unsigned shorts, normalized), at_tangent (4 bytes).
     */
    public static final int INTS_PER_VERTEX_EXT = 11;
    public static final int BYTES_PER_VERTEX_EXT = INTS_PER_VERTEX_EXT * 4;

    /** Bytes per vertex of the format currently in use. */
    public static int currentStride() {
        return dev.quicksilver.shader.ShaderBridge.extended() ? BYTES_PER_VERTEX_EXT : BYTES_PER_VERTEX;
    }

    /** True if this mesh is in the extended format. */
    public final boolean ext;
    private final int ipv;

    public CompactMesh() {
        this(false);
    }

    public CompactMesh(boolean ext) {
        this.ext = ext;
        this.ipv = ext ? INTS_PER_VERTEX_EXT : INTS_PER_VERTEX;
    }

    public int intsPerVertex() {
        return ipv;
    }
    /** position units per block */
    public static final float POS_SCALE = 256f;
    /** added to positions so that -64..192 blocks fit into a signed short */
    public static final int POS_BIAS = 16384;
    public static final float UV_SCALE = 32768f;
    public static final int BYTES_PER_VERTEX = INTS_PER_VERTEX * 4;
    public static final int BUCKETS = 7;

    public final int[][] data = new int[BUCKETS][];
    public final int[] vertices = new int[BUCKETS];

    public int totalVertices() {
        int n = 0;
        for (int v : vertices) n += v;
        return n;
    }

    public boolean isEmpty() {
        return totalVertices() == 0;
    }

    /** Per-thread growable scratch buffers; meshes are built there and copied out once at their exact size. */
    private static final ThreadLocal<int[][]> SCRATCH = ThreadLocal.withInitial(() -> new int[BUCKETS][]);
    private int[][] work;

    private static final ThreadLocal<int[][]> SCRATCH_EXT = ThreadLocal.withInitial(() -> new int[BUCKETS][]);

    private void ensure(int bucket, int moreVertices) {
        int need = (vertices[bucket] + moreVertices) * ipv;
        int[] d = work[bucket];
        if (d == null) work[bucket] = new int[Math.max(need, 4096 * ipv)];
        else if (d.length < need) work[bucket] = Arrays.copyOf(d, Math.max(need, d.length * 2));
    }

    /**
     * Append tessellator output (8 ints per vertex, positions relative to the section origin).
     * @param ox,oy,oz offset from the section origin to the region origin (added to every position)
     */
    public void append(MeshCapture m, float ox, float oy, float oz) {
        if (m == null || m.vertexCount == 0) return;
        if (ext) throw new IllegalStateException("extended mesh needs appendExt");
        beginWork();
        try {
            int[] src = m.data;
            for (int s = 0; s < m.segmentCount; s++) {
                int mode = m.segments[s * 3], first = m.segments[s * 3 + 1], count = m.segments[s * 3 + 2];
                if (mode == 4) { // GL_TRIANGLES: quads were split as v0 v1 v2 v0 v2 v3
                    for (int q = 0; q + 6 <= count; q += 6) addQuad(src, first + q, true, ox, oy, oz, m.hasColour);
                } else if (mode == 7) { // GL_QUADS
                    for (int q = 0; q + 4 <= count; q += 4) addQuad(src, first + q, false, ox, oy, oz, m.hasColour);
                }
            }
        } finally {
            endWork();
        }
    }

    private void beginWork() {
        work = (ext ? SCRATCH_EXT : SCRATCH).get();
        for (int b = 0; b < BUCKETS; b++) {
            if (vertices[b] > 0) {
                ensure(b, 0);
                System.arraycopy(data[b], 0, work[b], 0, vertices[b] * ipv);
            }
        }
    }

    private void endWork() {
        for (int b = 0; b < BUCKETS; b++) data[b] = vertices[b] == 0 ? null : Arrays.copyOf(work[b], vertices[b] * ipv);
        work = null;
    }

    private static int bucketOf(float nx, float ny, float nz) {
        float anx = Math.abs(nx), any = Math.abs(ny), anz = Math.abs(nz);
        float len = anx + any + anz;
        if (len > 1e-12f) {
            float eps = len * 1e-4f;
            if (any < eps && anz < eps) return nx < 0 ? 4 : 5;
            if (anx < eps && anz < eps) return ny < 0 ? 0 : 1;
            if (anx < eps && any < eps) return nz < 0 ? 2 : 3;
        }
        return 6;
    }

    // ------------------------------------------------------------------ extended format

    /** Light and occlusion lookups for the extended format (world block coordinates). */
    public interface LightSource {
        int sky(int x, int y, int z);

        int block(int x, int y, int z);

        /** full opaque cube (casts ambient occlusion, its own light is meaningless) */
        boolean opaque(int x, int y, int z);

        /**
         * Everything the plain-terrain lighting needs about a cell: sky | block << 4 | (blocks grass ? 512 : 0) |
         * id << 10 (light as {@link #sky}/{@link #block}, raw without time of day).
         */
        default int cellV(int x, int y, int z) {
            return packed(x, y, z);
        }

        /** brightness by light level (the dimension's table) */
        default float[] table() {
            return null;
        }

        /** sky | block << 4 | (opaque ? 256 : 0) in one lookup */
        default int packed(int x, int y, int z) {
            return sky(x, y, z) | block(x, y, z) << 4 | (opaque(x, y, z) ? 256 : 0);
        }
    }

    /**
     * Append tessellator output in the extended format.
     * @param ox,oy,oz offset from the section origin to the region origin
     * @param sx,sy,sz section origin in world coordinates (tessellator positions are relative to it)
     * @param runs vertex ranges per block: [startVertex, mc_Entity.x] pairs, sorted (vertices before the first run get 0)
     * @param smooth smooth lighting (vanilla-style ambient occlusion and interpolated light)
     * @param shade keep directional shading in the colour; else it is divided out
     * @param separateAo ambient occlusion goes into colour alpha instead of rgb
     */
    public void appendExt(MeshCapture m, float ox, float oy, float oz, int sx, int sy, int sz, int[] runs, int runCount,
                          LightSource light, boolean smooth, boolean shade, boolean separateAo) {
        if (m == null || m.vertexCount == 0) return;
        beginWork();
        try {
            int[] src = m.data;
            int run = 0;
            for (int s = 0; s < m.segmentCount; s++) {
                int mode = m.segments[s * 3], first = m.segments[s * 3 + 1], count = m.segments[s * 3 + 2];
                int step = mode == 4 ? 6 : 4;
                if (mode != 4 && mode != 7) continue;
                for (int q = 0; q + step <= count; q += step) {
                    int v = first + q;
                    while (run + 1 < runCount && runs[(run + 1) * 2] <= v) run++;
                    int id = runCount > 0 && runs[run * 2] <= v ? runs[run * 2 + 1] : 0;
                    addQuadExt(src, v, mode == 4, ox, oy, oz, sx, sy, sz, m.hasColour, id, light, smooth, shade, separateAo);
                }
            }
        } finally {
            endWork();
        }
    }

    private final float[] qp = new float[12], quv = new float[8];

    private void addQuadExt(int[] src, int v, boolean triangles, float ox, float oy, float oz, int sx, int sy, int sz,
                            boolean colour, int entityId, LightSource light, boolean smooth, boolean shade, boolean separateAo) {
        int[] idx = {v, v + 1, v + 2, triangles ? v + 5 : v + 3};
        float[] p = qp, t = quv;
        for (int k = 0; k < 4; k++) {
            int s = idx[k] * 8;
            p[k * 3] = Float.intBitsToFloat(src[s]);
            p[k * 3 + 1] = Float.intBitsToFloat(src[s + 1]);
            p[k * 3 + 2] = Float.intBitsToFloat(src[s + 2]);
            t[k * 2] = Float.intBitsToFloat(src[s + 3]);
            t[k * 2 + 1] = Float.intBitsToFloat(src[s + 4]);
        }
        float e1x = p[3] - p[0], e1y = p[4] - p[1], e1z = p[5] - p[2];
        float e2x = p[6] - p[0], e2y = p[7] - p[1], e2z = p[8] - p[2];
        float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-12f) {
            // degenerate first triangle: try the other one
            float f1x = p[9] - p[0], f1y = p[10] - p[1], f1z = p[11] - p[2];
            nx = e2y * f1z - e2z * f1y;
            ny = e2z * f1x - e2x * f1z;
            nz = e2x * f1y - e2y * f1x;
            nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
        if (nl > 1e-12f) {
            nx /= nl;
            ny /= nl;
            nz /= nl;
        } else {
            ny = 1;
        }
        int bucket = bucketOf(nx, ny, nz);
        // tangent from the uv gradient
        float du1 = t[2] - t[0], dv1 = t[3] - t[1], du2 = t[4] - t[0], dv2 = t[5] - t[1];
        float det = du1 * dv2 - du2 * dv1;
        float tx, ty, tz, tw;
        if (Math.abs(det) > 1e-12f) {
            float r = 1f / det;
            tx = (e1x * dv2 - e2x * dv1) * r;
            ty = (e1y * dv2 - e2y * dv1) * r;
            tz = (e1z * dv2 - e2z * dv1) * r;
            float bx = (e2x * du1 - e1x * du2) * r, by = (e2y * du1 - e1y * du2) * r, bz = (e2z * du1 - e1z * du2) * r;
            // orthogonalize against the normal
            float d = tx * nx + ty * ny + tz * nz;
            tx -= nx * d;
            ty -= ny * d;
            tz -= nz * d;
            float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tl > 1e-12f) {
                tx /= tl;
                ty /= tl;
                tz /= tl;
            } else {
                tx = 1;
                ty = 0;
                tz = 0;
            }
            float cx = ny * tz - nz * ty, cy = nz * tx - nx * tz, cz = nx * ty - ny * tx;
            tw = (cx * bx + cy * by + cz * bz) < 0 ? -1 : 1;
        } else {
            tx = 1;
            ty = 0;
            tz = 0;
            tw = 1;
        }
        int normalPacked = (Math.round(nx * 127) & 255) | (Math.round(ny * 127) & 255) << 8 | (Math.round(nz * 127) & 255) << 16;
        int tangentPacked = (Math.round(tx * 127) & 255) | (Math.round(ty * 127) & 255) << 8 | (Math.round(tz * 127) & 255) << 16 | (Math.round(tw * 127) & 255) << 24;
        float mu = (t[0] + t[2] + t[4] + t[6]) * 0.25f, mv = (t[1] + t[3] + t[5] + t[7]) * 0.25f;
        int mid = clampU16(mu * 65535f) | clampU16(mv * 65535f) << 16;
        // directional shade vanilla multiplied into axis-aligned faces
        float shadeDiv = 1f;
        if (!shade) {
            switch (bucket) {
                case 0 -> shadeDiv = 0.5f;
                case 2, 3 -> shadeDiv = 0.8f;
                case 4, 5 -> shadeDiv = 0.6f;
                default -> shadeDiv = 1f;
            }
        }
        // face center, for the block the face belongs to
        float cxw = (p[0] + p[3] + p[6] + p[9]) * 0.25f + sx, cyw = (p[1] + p[4] + p[7] + p[10]) * 0.25f + sy, czw = (p[2] + p[5] + p[8] + p[11]) * 0.25f + sz;
        ensure(bucket, 4);
        int[] dst = work[bucket];
        int o = vertices[bucket] * INTS_PER_VERTEX_EXT;
        for (int k = 0; k < 4; k++) {
            int s = idx[k] * 8;
            float px = p[k * 3], py = p[k * 3 + 1], pz = p[k * 3 + 2];
            dst[o] = Float.floatToRawIntBits(px + ox);
            dst[o + 1] = Float.floatToRawIntBits(py + oy);
            dst[o + 2] = Float.floatToRawIntBits(pz + oz);
            dst[o + 3] = src[s + 3];
            dst[o + 4] = src[s + 4];
            int c = colour ? src[s + 5] : -1;
            // light and ambient occlusion of this corner
            computeLight(light, bucket, px + sx, py + sy, pz + sz, cxw, cyw, czw, nx, ny, nz, smooth);
            float ao = lAo;
            int r = c & 255, g = (c >> 8) & 255, b = (c >> 16) & 255, a = (c >>> 24) & 255;
            if (shadeDiv != 1f) {
                r = Math.min(255, Math.round(r / shadeDiv));
                g = Math.min(255, Math.round(g / shadeDiv));
                b = Math.min(255, Math.round(b / shadeDiv));
            }
            if (separateAo) {
                a = Math.round(a * ao);
            } else {
                r = Math.round(r * ao);
                g = Math.round(g * ao);
                b = Math.round(b * ao);
            }
            dst[o + 5] = r | g << 8 | b << 16 | a << 24;
            dst[o + 6] = (Math.round(lBlock * 16f) & 0xFFFF) | (Math.round(lSky * 16f) & 0xFFFF) << 16;
            dst[o + 7] = normalPacked;
            dst[o + 8] = entityId & 0xFFFF;
            dst[o + 9] = mid;
            dst[o + 10] = tangentPacked;
            o += INTS_PER_VERTEX_EXT;
        }
        vertices[bucket] += 4;
    }

    private static int clampU16(float f) {
        int i = Math.round(f);
        return i < 0 ? 0 : (i > 65535 ? 65535 : i);
    }

    // results of computeLight
    private float lSky, lBlock, lAo;

    /**
     * Light of a vertex like vanilla's smooth lighting: the average of the four blocks in front of the face that touch
     * the vertex (opaque ones take the light of the face's own block), plus ambient occlusion from the opaque ones
     * (0.2 each, as in later versions). Faces that aren't axis-aligned use the light of the block they're in.
     */
    private void computeLight(LightSource L, int bucket, float vx, float vy, float vz, float cx, float cy, float cz,
                              float nx, float ny, float nz, boolean smooth) {
        if (bucket == 6) {
            int c = L.packed(fl(cx), fl(cy), fl(cz));
            lSky = c & 15;
            lBlock = (c >> 4) & 15;
            lAo = 1f;
            return;
        }
        int axis = bucket >> 1; // 0 y, 1 z, 2 x
        int sign = (bucket & 1) == 0 ? -1 : 1;
        // block layer in front of the face (the block itself for faces inside it, like slab tops)
        float fa = axis == 0 ? vy : (axis == 1 ? vz : vx);
        int layer = fl(fa + sign * 0.001f);
        float t1v, t2v, c1v, c2v;
        if (axis == 0) { // y face: tangents x, z
            t1v = vx; t2v = vz; c1v = cx; c2v = cz;
        } else if (axis == 1) { // z face: x, y
            t1v = vx; t2v = vy; c1v = cx; c2v = cy;
        } else { // x face: z, y
            t1v = vz; t2v = vy; c1v = cz; c2v = cy;
        }
        int f1 = fl(c1v), f2 = fl(c2v);
        int c = sample(L, axis, layer, f1, f2);
        int cSky = c & 15, cBlock = (c >> 4) & 15;
        if (!smooth) {
            lSky = cSky;
            lBlock = cBlock;
            lAo = 1f;
            return;
        }
        int r1 = Math.round(t1v), r2 = Math.round(t2v);
        int e1 = f1 == r1 ? r1 - 1 : (f1 == r1 - 1 ? r1 : f1);
        int e2 = f2 == r2 ? r2 - 1 : (f2 == r2 - 1 ? r2 : f2);
        boolean has1 = e1 != f1, has2 = e2 != f2;
        if (!has1 && !has2) {
            lSky = cSky;
            lBlock = cBlock;
            lAo = 1f;
            return;
        }
        float sky = cSky, blk = cBlock;
        float ao = (c & 256) != 0 ? 0.2f : 1f;
        int n = 1;
        boolean o1 = false, o2 = false;
        if (has1) {
            int s1 = sample(L, axis, layer, e1, f2);
            o1 = (s1 & 256) != 0;
            sky += o1 ? cSky : (s1 & 15);
            blk += o1 ? cBlock : ((s1 >> 4) & 15);
            ao += o1 ? 0.2f : 1f;
            n++;
        }
        if (has2) {
            int s2 = sample(L, axis, layer, f1, e2);
            o2 = (s2 & 256) != 0;
            sky += o2 ? cSky : (s2 & 15);
            blk += o2 ? cBlock : ((s2 >> 4) & 15);
            ao += o2 ? 0.2f : 1f;
            n++;
        }
        if (has1 && has2) {
            boolean od;
            int sd = 0;
            if (o1 && o2) od = true;
            else {
                sd = sample(L, axis, layer, e1, e2);
                od = (sd & 256) != 0;
            }
            sky += od ? cSky : (sd & 15);
            blk += od ? cBlock : ((sd >> 4) & 15);
            ao += od ? 0.2f : 1f;
            n++;
        }
        lSky = sky / n;
        lBlock = blk / n;
        lAo = ao / n;
    }

    private static int sample(LightSource L, int axis, int layer, int a, int b) {
        return switch (axis) {
            case 0 -> L.packed(a, layer, b);
            case 1 -> L.packed(a, b, layer);
            default -> L.packed(layer, b, a);
        };
    }

    private static int fl(float f) {
        int i = (int) f;
        return f < i ? i - 1 : i;
    }





    // ------------------------------------------------------------------ plain terrain with GPU time of day

    /**
     * Append tessellator output of a snapshot built in shader mode (block renderers saw full brightness, so the colours
     * carry only tint and directional shading). Each vertex gets two bytes: effective block light and sky light
     * (level * 17) such that the GPU's table brightness of them reproduces vanilla's averaged vertex brightness.
     */
    public void appendSky(MeshCapture m, float ox, float oy, float oz, int sx, int sy, int sz, LightSource light, boolean smooth) {
        if (m == null || m.vertexCount == 0) return;
        if (ext) throw new IllegalStateException("extended mesh needs appendExt");
        beginWork();
        try {
            int[] src = m.data;
            float[] table = light.table();
            for (int s = 0; s < m.segmentCount; s++) {
                int mode = m.segments[s * 3], first = m.segments[s * 3 + 1], count = m.segments[s * 3 + 2];
                if (mode == 4) {
                    for (int q = 0; q + 6 <= count; q += 6) addQuadSky(src, first + q, true, ox, oy, oz, sx, sy, sz, m.hasColour, light, table, smooth);
                } else if (mode == 7) {
                    for (int q = 0; q + 4 <= count; q += 4) addQuadSky(src, first + q, false, ox, oy, oz, sx, sy, sz, m.hasColour, light, table, smooth);
                }
            }
        } finally {
            endWork();
        }
    }

    private final float[] qlB = new float[4], qlS = new float[4];

    private void addQuadSky(int[] src, int v, boolean triangles, float ox, float oy, float oz, int sx, int sy, int sz,
                            boolean colour, LightSource light, float[] table, boolean smooth) {
        int a = v, b = v + 1, c = v + 2, d = triangles ? v + 5 : v + 3;
        int[] idx = {a, b, c, d};
        float ax = Float.intBitsToFloat(src[a * 8]), ay = Float.intBitsToFloat(src[a * 8 + 1]), az = Float.intBitsToFloat(src[a * 8 + 2]);
        float bx = Float.intBitsToFloat(src[b * 8]) - ax, by = Float.intBitsToFloat(src[b * 8 + 1]) - ay, bz = Float.intBitsToFloat(src[b * 8 + 2]) - az;
        float cx = Float.intBitsToFloat(src[c * 8]) - ax, cy = Float.intBitsToFloat(src[c * 8 + 1]) - ay, cz = Float.intBitsToFloat(src[c * 8 + 2]) - az;
        float nx = by * cz - bz * cy, ny = bz * cx - bx * cz, nz = bx * cy - by * cx;
        int bucket = bucketOf(nx, ny, nz);
        float[] p = qp;
        for (int k = 0; k < 4; k++) {
            int s = idx[k] * 8;
            p[k * 3] = Float.intBitsToFloat(src[s]) + sx;
            p[k * 3 + 1] = Float.intBitsToFloat(src[s + 1]) + sy;
            p[k * 3 + 2] = Float.intBitsToFloat(src[s + 2]) + sz;
        }
        skyLightQuad(light, table, bucket, p, smooth);
        ensure(bucket, 4);
        int[] dst = work[bucket];
        int o = vertices[bucket] * INTS_PER_VERTEX;
        for (int k = 0; k < 4; k++) {
            int s = idx[k] * 8;
            int x = pos(Float.intBitsToFloat(src[s]) + ox);
            int y = pos(Float.intBitsToFloat(src[s + 1]) + oy);
            int z = pos(Float.intBitsToFloat(src[s + 2]) + oz);
            int lb = Math.round(qlB[k] * 17f), ls = Math.round(qlS[k] * 17f);
            dst[o] = (x & 0xFFFF) | y << 16;
            dst[o + 1] = (z & 0xFFFF) | (lb & 255) << 16 | (ls & 255) << 24;
            dst[o + 2] = (uv(Float.intBitsToFloat(src[s + 3])) & 0xFFFF) | uv(Float.intBitsToFloat(src[s + 4])) << 16;
            dst[o + 3] = colour ? src[s + 5] : -1;
            o += INTS_PER_VERTEX;
        }
        vertices[bucket] += 4;
    }

    private static final byte[] RENDER_TYPE = new byte[256];

    static {
        Arrays.fill(RENDER_TYPE, (byte) -1);
    }

    private static int renderType(int id) {
        int t = RENDER_TYPE[id];
        if (t < 0) {
            net.minecraft.block.BlockBase b = net.minecraft.block.BlockBase.BY_ID[id];
            t = b == null ? 0 : b.getRenderType();
            RENDER_TYPE[id] = (byte) t;
        }
        return t;
    }

    // brightness -> level lookup of the table in use (the average of table brightnesses has to be inverted)
    private static volatile float[] lutTable;
    private static volatile float[] lut;
    private static final int LUT_SIZE = 1024;

    private static float[] lutFor(float[] t) {
        float[] l = lut;
        if (lutTable == t && l != null) return l;
        l = new float[LUT_SIZE + 1];
        int i = 0;
        for (int k = 0; k <= LUT_SIZE; k++) {
            float v = k / (float) LUT_SIZE;
            while (i < 15 && v > t[i + 1]) i++;
            float lev;
            if (v <= t[0]) lev = 0;
            else if (i >= 15 && v >= t[15]) lev = 15;
            else {
                float dd = t[i + 1] - t[i];
                lev = dd <= 0 ? i + 1 : i + (v - t[i]) / dd;
            }
            l[k] = lev;
        }
        lut = l;
        lutTable = t;
        return l;
    }

    private static float levelOf(float[] l, float v) {
        float f = v * LUT_SIZE;
        if (f <= 0) return l[0];
        if (f >= LUT_SIZE) return l[LUT_SIZE];
        int i = (int) f;
        float fr = f - i;
        return l[i] + (l[i + 1] - l[i]) * fr;
    }

    private final int[] grid = new int[9];

    /**
     * Effective block and sky light levels of the four vertices of a quad (results in qlB, qlS): vanilla averages the
     * brightness of the four cells in front of the face that touch a vertex, so the table brightness of each channel's
     * average is inverted back to a level.
     * @param p world positions of the corners (12 floats)
     */
    private void skyLightQuad(LightSource L, float[] T, int bucket, float[] p, boolean smooth) {
        float cx = (p[0] + p[3] + p[6] + p[9]) * 0.25f, cy = (p[1] + p[4] + p[7] + p[10]) * 0.25f, cz = (p[2] + p[5] + p[8] + p[11]) * 0.25f;
        if (bucket == 6) {
            int ix = fl(cx), iy = fl(cy), iz = fl(cz);
            int cell = L.cellV(ix, iy, iz);
            int cid = cell >> 10 & 255;
            if (renderType(cid) == 4) {
                // sloped fluid surfaces: the brighter of the cell and the one above, like Fluid.getBrightness
                int up = L.cellV(ix, iy + 1, iz);
                lBlock = Math.max((cell >> 4) & 15, (up >> 4) & 15);
                lSky = Math.max(cell & 15, up & 15);
                fill4();
                return;
            }
            flat(cell, net.minecraft.block.BlockBase.EMITTANCE[cid], renderType(cid) == 2);
            return;
        }
        int axis = bucket >> 1; // 0 y, 1 z, 2 x
        int sign = (bucket & 1) == 0 ? -1 : 1;
        int ox = fl(cx - (axis == 2 ? sign * 0.001f : 0)), oy = fl(cy - (axis == 0 ? sign * 0.001f : 0)), oz = fl(cz - (axis == 1 ? sign * 0.001f : 0));
        int oc = L.cellV(ox, oy, oz);
        int id = oc >> 10 & 255;
        int emit = net.minecraft.block.BlockBase.EMITTANCE[id];
        int rt = renderType(id);
        int lx = ox + (axis == 2 ? sign : 0), ly = oy + (axis == 0 ? sign : 0), lz = oz + (axis == 1 ? sign : 0);
        boolean standard = rt == 0 || rt == 10 || rt == 11 || rt == 16 || rt == 17;
        if (!standard) {
            boolean own;
            switch (rt) {
                case 1, 2, 3, 5, 6, 8, 9, 12 -> own = true;
                case 4 -> own = bucket == 1;
                case 13 -> own = false;
                default -> {
                    float plane = axis == 0 ? cy : (axis == 1 ? cz : cx);
                    int cell0 = axis == 0 ? oy : (axis == 1 ? oz : ox);
                    own = Math.abs(plane - (cell0 + (sign > 0 ? 1 : 0))) > 0.002f;
                }
            }
            int cell = own ? oc : L.cellV(lx, ly, lz);
            if (rt == 4) {
                // fluids take the brighter of the cell and the one above it, and no emission of their own
                int up = L.cellV(own ? ox : lx, (own ? oy : ly) + 1, own ? oz : lz);
                lBlock = Math.max((cell >> 4) & 15, (up >> 4) & 15);
                lSky = Math.max(cell & 15, up & 15);
                fill4();
                return;
            }
            flat(cell, emit, rt == 2);
            return;
        }
        if (!smooth) {
            flat(L.cellV(lx, ly, lz), emit, false);
            return;
        }
        // tangent axes: y faces x,z; z faces x,y; x faces z,y
        int t1x, t1y, t1z, t2x, t2y, t2z;
        int o1, o2, i1, i2; // origin cell coordinates along the tangents, vertex coordinate offsets in p
        if (axis == 0) {
            t1x = 1; t1y = 0; t1z = 0; t2x = 0; t2y = 0; t2z = 1; o1 = ox; o2 = oz; i1 = 0; i2 = 2;
        } else if (axis == 1) {
            t1x = 1; t1y = 0; t1z = 0; t2x = 0; t2y = 1; t2z = 0; o1 = ox; o2 = oy; i1 = 0; i2 = 1;
        } else {
            t1x = 0; t1y = 0; t1z = 1; t2x = 0; t2y = 1; t2z = 0; o1 = oz; o2 = oy; i1 = 2; i2 = 1;
        }
        int[] g = grid;
        for (int da = -1; da <= 1; da++) {
            for (int db = -1; db <= 1; db++) {
                g[(da + 1) * 3 + db + 1] = L.cellV(lx + da * t1x + db * t2x, ly + da * t1y + db * t2y, lz + da * t1z + db * t2z);
            }
        }
        float[] lut = lutFor(T);
        int centre = g[4];
        for (int k = 0; k < 4; k++) {
            int s1 = p[k * 3 + i1] - o1 > 0.5f ? 1 : -1, s2 = p[k * 3 + i2] - o2 > 0.5f ? 1 : -1;
            int e1 = g[(s1 + 1) * 3 + 1], e2 = g[3 + s2 + 1];
            int dg = ((e1 & e2) & 512) != 0 ? e1 : g[(s1 + 1) * 3 + s2 + 1];
            float tb = 0, ts = 0;
            for (int j = 0; j < 4; j++) {
                int cell = j == 0 ? centre : (j == 1 ? e1 : (j == 2 ? e2 : dg));
                int blk = (cell >> 4) & 15;
                if (blk < emit) blk = emit;
                tb += T[blk];
                ts += T[cell & 15];
            }
            qlB[k] = levelOf(lut, tb * 0.25f);
            qlS[k] = levelOf(lut, ts * 0.25f);
        }
    }

    private void fill4() {
        for (int k = 0; k < 4; k++) {
            qlB[k] = lBlock;
            qlS[k] = lSky;
        }
    }

    private void flat(int cell, int emit, boolean fullBright) {
        int blk = (cell >> 4) & 15;
        if (blk < emit) blk = emit;
        lBlock = fullBright ? 15 : blk;
        lSky = cell & 15;
        fill4();
    }

    private void addQuad(int[] src, int v, boolean triangles, float ox, float oy, float oz, boolean colour) {
        // corner indices of the quad in the source
        int a = v, b = v + 1, c = v + 2, d = triangles ? v + 5 : v + 3;
        float ax = Float.intBitsToFloat(src[a * 8]), ay = Float.intBitsToFloat(src[a * 8 + 1]), az = Float.intBitsToFloat(src[a * 8 + 2]);
        float bx = Float.intBitsToFloat(src[b * 8]) - ax, by = Float.intBitsToFloat(src[b * 8 + 1]) - ay, bz = Float.intBitsToFloat(src[b * 8 + 2]) - az;
        float cx = Float.intBitsToFloat(src[c * 8]) - ax, cy = Float.intBitsToFloat(src[c * 8 + 1]) - ay, cz = Float.intBitsToFloat(src[c * 8 + 2]) - az;
        float nx = by * cz - bz * cy, ny = bz * cx - bx * cz, nz = bx * cy - by * cx;
        int bucket = bucketOf(nx, ny, nz);
        // stored as GL_QUADS: 4 vertices per face instead of 6 (the driver splits them for free)
        ensure(bucket, 4);
        int[] dst = work[bucket];
        int o = vertices[bucket] * INTS_PER_VERTEX;
        o = put(dst, o, src, a, ox, oy, oz, colour);
        o = put(dst, o, src, b, ox, oy, oz, colour);
        o = put(dst, o, src, c, ox, oy, oz, colour);
        put(dst, o, src, d, ox, oy, oz, colour);
        vertices[bucket] += 4;
    }

    private static int put(int[] dst, int o, int[] src, int v, float ox, float oy, float oz, boolean colour) {
        int s = v * 8;
        int x = pos(Float.intBitsToFloat(src[s]) + ox);
        int y = pos(Float.intBitsToFloat(src[s + 1]) + oy);
        int z = pos(Float.intBitsToFloat(src[s + 2]) + oz);
        int u = uv(Float.intBitsToFloat(src[s + 3]));
        int w = uv(Float.intBitsToFloat(src[s + 4]));
        dst[o] = (x & 0xFFFF) | y << 16;
        dst[o + 1] = z & 0xFFFF;
        dst[o + 2] = (u & 0xFFFF) | w << 16;
        dst[o + 3] = colour ? src[s + 5] : -1;
        return o + 4;
    }

    private static int pos(float p) {
        int i = Math.round(p * POS_SCALE) - POS_BIAS;
        return i < -32768 ? -32768 : (i > 32767 ? 32767 : i);
    }

    private static int uv(float t) {
        int i = Math.round(t * UV_SCALE);
        return i < -32768 ? -32768 : (i > 32767 ? 32767 : i);
    }
}
