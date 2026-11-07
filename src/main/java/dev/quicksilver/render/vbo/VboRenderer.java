package dev.quicksilver.render.vbo;

import dev.quicksilver.QsConfig;
import dev.quicksilver.Quicksilver;
import dev.quicksilver.render.CaveCuller;
import dev.quicksilver.render.QsSection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.class_66;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLContext;

/**
 * Terrain renderer: every 8x8x8-section region keeps its chunk geometry in one vertex buffer, and each
 * render pass is drawn with one glMultiDrawArrays call per region (instead of one display list call per section).
 * Faces are grouped by direction, so groups that face away from the camera aren't drawn at all.
 */
public final class VboRenderer {
    private VboRenderer() {}

    private static Boolean supported;
    private static final Map<Long, VboRegion> REGIONS = new HashMap<>();
    private static ByteBuffer upload = BufferUtils.createByteBuffer(1 << 20);
    private static IntBuffer firsts = BufferUtils.createIntBuffer(4096);
    private static IntBuffer counts = BufferUtils.createIntBuffer(4096);
    private static VboRegion[] order = new VboRegion[64];
    private static double[] orderDist = new double[64];

    public static int statRegions, statDraws, statSections;
    /** Indexed triangles via glMultiDrawElementsIndirect (GL 4.3) with a shared quad index buffer; else GL_QUADS. */
    private static boolean indirect;
    private static int quadIndexBuffer;
    private static final int MAX_QUADS_PER_RANGE = 65536;
    private static IntBuffer commands = BufferUtils.createIntBuffer(4096 * 5);
    private static int indirectBuffer;
    private static VboRegion[] drawRegions = new VboRegion[64];
    private static int[] drawStart = new int[64], drawCount = new int[64];
    public static long statVertices;

    public static boolean active() {
        if (!QsConfig.vboRenderer || !dev.quicksilver.render.ChunkScheduler.threaded()) return false;
        if (supported == null) {
            try {
                ContextCapabilities c = GLContext.getCapabilities();
                supported = c.OpenGL15 && c.OpenGL14 && (c.OpenGL31 || c.GL_ARB_copy_buffer);
                indirect = supported && (c.OpenGL43 || c.GL_ARB_multi_draw_indirect) && !Boolean.getBoolean("quicksilver.noIndirect");
                if (indirect) {
                    createQuadIndices();
                    indirectBuffer = GL15.glGenBuffers();
                }
            } catch (Throwable t) {
                supported = false;
            }
            Quicksilver.LOGGER.info("VBO terrain renderer {}", supported ? "enabled" : "not supported by this GPU/driver, using display lists");
        }
        return supported;
    }

    private static void createQuadIndices() {
        IntBuffer idx = BufferUtils.createIntBuffer(MAX_QUADS_PER_RANGE * 6);
        for (int q = 0; q < MAX_QUADS_PER_RANGE; q++) {
            int v = q * 4;
            idx.put(v).put(v + 1).put(v + 2).put(v).put(v + 2).put(v + 3);
        }
        idx.flip();
        quadIndexBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, quadIndexBuffer);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, idx, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    private static long regionKey(int rx, int ry, int rz) {
        return ((long) rx & 0x1FFFFF) << 42 | ((long) ry & 0x1FFFFF) << 21 | ((long) rz & 0x1FFFFF);
    }

    /** Region origin (block coords) for a section origin. */
    public static int regionOrigin(int blockCoord) {
        return Math.floorDiv(blockCoord, VboRegion.SIZE_BLOCKS) * VboRegion.SIZE_BLOCKS;
    }

    private static VboRegion region(int sx, int sy, int sz) {
        int ox = regionOrigin(sx), oy = regionOrigin(sy), oz = regionOrigin(sz);
        long k = regionKey(ox >> 7, oy >> 7, oz >> 7);
        VboRegion r = REGIONS.get(k);
        if (r == null) {
            r = new VboRegion(k, ox, oy, oz);
            REGIONS.put(k, r);
        }
        return r;
    }

    /** Replace the geometry of one pass of a section. Main thread. */
    public static void upload(class_66 s, int pass, CompactMesh mesh) {
        QsSection qs = (QsSection) s;
        SectionAlloc old = qs.qs$alloc(pass);
        if (old != null) {
            old.region.free(old);
            qs.qs$setAlloc(pass, null);
        }
        if (mesh == null || mesh.isEmpty()) return;
        VboRegion r = region(s.field_231, s.field_232, s.field_233);
        if (mesh.intsPerVertex() * 4 != r.stride) return; // built for the other vertex format: a rebuild follows
        int total = mesh.totalVertices();
        SectionAlloc a = new SectionAlloc(s, pass, r);
        a.vertices = total;
        a.offset = r.allocate(total);
        int bytes = total * r.stride;
        if (upload.capacity() < bytes) upload = BufferUtils.createByteBuffer(Integer.highestOneBit(bytes) << 1);
        upload.clear();
        IntBuffer ib = upload.asIntBuffer();
        int cursor = a.offset;
        int[] bf = BUCKET_FIRST, bc = BUCKET_COUNT;
        for (int b = 0; b < CompactMesh.BUCKETS; b++) {
            int n = mesh.vertices[b];
            bf[b] = cursor;
            bc[b] = n;
            if (n > 0) ib.put(mesh.data[b], 0, n * mesh.intsPerVertex());
            cursor += n;
        }
        upload.position(0);
        upload.limit(bytes);
        r.upload(a.offset, upload);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        r.add(a, bf, bc);
        qs.qs$setAlloc(pass, a);
    }

    /** Section reset / destroyed. */
    public static void free(class_66 s) {
        QsSection qs = (QsSection) s;
        for (int p = 0; p < 2; p++) {
            SectionAlloc a = qs.qs$alloc(p);
            if (a != null) {
                a.region.free(a);
                qs.qs$setAlloc(p, null);
            }
        }
    }

    /** Drop everything (world change, renderer reload). */
    public static void reset() {
        for (VboRegion r : REGIONS.values()) r.delete();
        REGIONS.clear();
    }

    /**
     * Draw one render pass. Replaces WorldRenderer.method_1542.
     * @return number of sections drawn
     */
    private static final boolean NO_DRAW = Boolean.getBoolean("quicksilver.debugNoTerrainDraw");

    public static long totalNs, buildNs, cmdUploadNs, fadeNs;

        public static int render(int pass, double camX, double camY, double camZ) {
        frameNs = System.nanoTime();
        ext = dev.quicksilver.shader.ShaderBridge.extended();
        sky = !ext && TerrainShader.enabled();
        fadeMs = ext ? 0 : dev.quicksilver.QsConfig.chunkFadeInMs;
        fadingCount = 0;
        long qsT0 = System.nanoTime();
        try {
            return renderImpl(pass, camX, camY, camZ);
        } finally {
            totalNs += System.nanoTime() - qsT0;
        }
    }

    private static int renderImpl(int pass, double camX, double camY, double camZ) {
        if (NO_DRAW) return 0; // benchmarking: emulate an infinitely fast GPU
        int n = 0;
        if (order.length < REGIONS.size()) {
            order = new VboRegion[REGIONS.size() * 2];
            orderDist = new double[order.length];
        }
        for (VboRegion r : REGIONS.values()) {
            if (r.isEmpty()) continue;
            double dx = r.originX + 64 - camX, dy = r.originY + 64 - camY, dz = r.originZ + 64 - camZ;
            order[n] = r;
            orderDist[n++] = dx * dx + dy * dy + dz * dz;
        }
        // near to far (insertion sort: few regions)
        for (int i = 1; i < n; i++) {
            VboRegion r = order[i];
            double d = orderDist[i];
            int j = i - 1;
            while (j >= 0 && orderDist[j] > d) {
                order[j + 1] = order[j];
                orderDist[j + 1] = orderDist[j];
                j--;
            }
            order[j + 1] = r;
            orderDist[j + 1] = d;
        }
        long qsB0 = System.nanoTime();
        int drawnSections = 0, draws = 0;
        long verts = 0;
        boolean stateSet = false;
        int nDraw = 0;
        if (indirect) commands.clear();
        for (int i = 0; i < n; i++) {
            VboRegion r = order[i];
            if (dev.quicksilver.render.FastFrustum.valid()
                    && dev.quicksilver.render.FastFrustum.testRegion(r.originX, r.originY, r.originZ) == dev.quicksilver.render.FastFrustum.OUTSIDE) continue;
            int nr = 0;
            int[] rf = rangeFirst, rc = rangeCount;
            class_66[] secs = r.sec[pass];
            int[] dat = r.dat[pass];
            boolean cave = CaveCuller.active();
            for (int k = 0, sz = r.count[pass]; k < sz; k++) {
                class_66 s = secs[k];
                if (!s.field_243 || (cave && !CaveCuller.isVisible(s))) continue;
                long fs = fadeMs > 0 ? ((dev.quicksilver.render.QsSection) s).qs$fadeStart() : 0;
                if (fs != 0) {
                    float alpha = (frameNs - fs) / (fadeMs * 1e6f);
                    if (alpha < 1f) {
                        addFading(r, k, alpha);
                        continue;
                    }
                    ((dev.quicksilver.render.QsSection) s).qs$setFadeStart(0);
                }
                drawnSections++;
                int base = k * VboRegion.STRIDE;
                int minX = dat[base], minY = dat[base + 1], minZ = dat[base + 2];
                // direction culling: a face group is invisible if the camera is behind all of its planes
                for (int b = 0; b < CompactMesh.BUCKETS; b++) {
                    int c = dat[base + 3 + CompactMesh.BUCKETS + b];
                    if (c == 0) continue;
                    boolean vis;
                    switch (b) {
                        case 0 -> vis = camY < minY + 16 + 0.5;
                        case 1 -> vis = camY > minY - 0.5;
                        case 2 -> vis = camZ < minZ + 16 + 0.5;
                        case 3 -> vis = camZ > minZ - 0.5;
                        case 4 -> vis = camX < minX + 16 + 0.5;
                        case 5 -> vis = camX > minX - 0.5;
                        default -> vis = true;
                    }
                    if (!vis) continue;
                    int f = dat[base + 3 + b];
                    // merge with the previous range when contiguous
                    if (nr > 0 && rf[nr - 1] + rc[nr - 1] == f) {
                        rc[nr - 1] += c;
                    } else {
                        if (nr == rf.length) {
                            rangeFirst = rf = java.util.Arrays.copyOf(rf, nr * 2);
                            rangeCount = rc = java.util.Arrays.copyOf(rc, nr * 2);
                        }
                        rf[nr] = f;
                        rc[nr++] = c;
                    }
                    verts += c;
                }
            }
            if (nr == 0) continue;
            if (!indirect) {
                firsts.clear();
                counts.clear();
                if (firsts.capacity() < nr) {
                    firsts = BufferUtils.createIntBuffer(nr * 2);
                    counts = BufferUtils.createIntBuffer(nr * 2);
                }
                firsts.put(rf, 0, nr);
                counts.put(rc, 0, nr);
            }
            if (!indirect) {
                firsts.flip();
                counts.flip();
            }
            if (indirect) {
                int start = commands.position() / 5;
                for (int k = 0; k < nr; k++) {
                    int f = rf[k], c = rc[k];
                    while (c > 0) {
                        int part = Math.min(c, MAX_QUADS_PER_RANGE * 4);
                        if (commands.remaining() < 5) commands = grow(commands);
                        commands.put(part / 4 * 6).put(1).put(0).put(f).put(0);
                        f += part;
                        c -= part;
                    }
                }
                if (nDraw == drawRegions.length) {
                    drawRegions = Arrays.copyOf(drawRegions, nDraw * 2);
                    drawStart = Arrays.copyOf(drawStart, nDraw * 2);
                    drawCount = Arrays.copyOf(drawCount, nDraw * 2);
                }
                drawRegions[nDraw] = r;
                drawStart[nDraw] = start;
                drawCount[nDraw++] = commands.position() / 5 - start;
                continue;
            }
            if (!stateSet) {
                enableState();
                stateSet = true;
            }
            bindRegion(r, camX, camY, camZ);
            GL14.glMultiDrawArrays(GL11.GL_QUADS, firsts, counts);
            GL11.glPopMatrix();
            draws++;
        }
        buildNs += System.nanoTime() - qsB0;
        if (indirect && nDraw > 0) {
            // all commands of this pass go to the GPU in one (orphaned) upload, then one indirect draw per region
            commands.flip();
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
            long qsU0 = System.nanoTime();
            GL15.glBufferData(GL40.GL_DRAW_INDIRECT_BUFFER, commands, GL15.GL_STREAM_DRAW);
            cmdUploadNs += System.nanoTime() - qsU0;
            enableState();
            stateSet = true;
            for (int d = 0; d < nDraw; d++) {
                bindRegion(drawRegions[d], camX, camY, camZ);
                GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT, (long) drawStart[d] * 20L, drawCount[d], 0);
                GL11.glPopMatrix();
                drawRegions[d] = null;
                draws++;
            }
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        }
        if (fadingCount > 0) {
            if (!stateSet) {
                enableState();
                stateSet = true;
            }
            long qsF0 = System.nanoTime();
            drawFading(pass, camX, camY, camZ);
            fadeNs += System.nanoTime() - qsF0;
        }
        if (stateSet) disableState();
        if (pass == 0) {
            statRegions = n;
            statDraws = draws;
            statSections = drawnSections;
            statVertices = verts;
        }
        return drawnSections;
    }

    /** extended (shader) vertex format this frame */
    private static boolean ext;
    /** plain terrain drawn by the time-of-day program */
    private static boolean sky;

    private static void disableState() {
        if (ext) {
            GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
            GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
            org.lwjgl.opengl.GL20.glDisableVertexAttribArray(dev.quicksilver.shader.ShaderBridge.ATTRIB_ENTITY);
            org.lwjgl.opengl.GL20.glDisableVertexAttribArray(dev.quicksilver.shader.ShaderBridge.ATTRIB_MID_TEX);
            org.lwjgl.opengl.GL20.glDisableVertexAttribArray(dev.quicksilver.shader.ShaderBridge.ATTRIB_TANGENT);
        } else {
            if (sky) TerrainShader.end();
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
        }
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        if (indirect) GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    private static void enableState() {
        if (indirect) GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, quadIndexBuffer);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        if (ext) {
            GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
            GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
            org.lwjgl.opengl.GL20.glEnableVertexAttribArray(dev.quicksilver.shader.ShaderBridge.ATTRIB_ENTITY);
            org.lwjgl.opengl.GL20.glEnableVertexAttribArray(dev.quicksilver.shader.ShaderBridge.ATTRIB_MID_TEX);
            org.lwjgl.opengl.GL20.glEnableVertexAttribArray(dev.quicksilver.shader.ShaderBridge.ATTRIB_TANGENT);
            return;
        }
        // uvs are stored as shorts: scale them back on the texture matrix
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPushMatrix();
        GL11.glScalef(1f / CompactMesh.UV_SCALE, 1f / CompactMesh.UV_SCALE, 1f);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        if (sky) {
            net.minecraft.level.Level level = dev.quicksilver.client.RenderDistance.MinecraftHolder.get().level;
            TerrainShader.begin(level.dimension.lightTable, level.field_202);
        }
    }

    // ---- fade-in of new terrain

    private static long frameNs;
    private static int fadeMs;
    private static VboRegion[] fadingRegion = new VboRegion[64];
    private static int[] fadingSlot = new int[64];
    private static float[] fadingAlpha = new float[64];
    private static int fadingCount;
    private static final java.nio.FloatBuffer ENV_COLOR = BufferUtils.createFloatBuffer(4);

    private static void addFading(VboRegion r, int slot, float alpha) {
        if (fadingCount == fadingRegion.length) {
            fadingRegion = Arrays.copyOf(fadingRegion, fadingCount * 2);
            fadingSlot = Arrays.copyOf(fadingSlot, fadingCount * 2);
            fadingAlpha = Arrays.copyOf(fadingAlpha, fadingCount * 2);
        }
        fadingRegion[fadingCount] = r;
        fadingSlot[fadingCount] = slot;
        fadingAlpha[fadingCount++] = Math.max(0f, alpha);
    }

    /**
     * Sections that appeared recently are drawn after the rest, blended, with their alpha scaled by the fade progress
     * (texture env combine: colour = texture * vertex colour, alpha = texture alpha * constant).
     */
    private static void drawFading(int pass, double camX, double camY, double camZ) {
        if (indirect) GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        // 1. depth only, so that only the front-most surface of a fading section gets blended (no see-through caves)
        GL11.glColorMask(false, false, false, false);
        for (int i = 0; i < fadingCount; i++) drawFadingSection(i, pass, camX, camY, camZ, false);
        GL11.glColorMask(true, true, true, true);
        // 2. colour, blended with the fade progress as alpha, where the depth matches
        boolean blendWasOn = GL11.glIsEnabled(GL11.GL_BLEND);
        if (!blendWasOn) GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        if (!sky) {
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL13.GL_COMBINE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_RGB, GL11.GL_MODULATE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_RGB, GL11.GL_TEXTURE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE1_RGB, GL13.GL_PRIMARY_COLOR);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_ALPHA, GL11.GL_MODULATE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_ALPHA, GL11.GL_TEXTURE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE1_ALPHA, GL13.GL_CONSTANT);
        }
        for (int i = 0; i < fadingCount; i++) drawFadingSection(i, pass, camX, camY, camZ, true);
        if (sky) TerrainShader.alpha(1f);
        else GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glDepthMask(true);
        if (!blendWasOn) GL11.glDisable(GL11.GL_BLEND);
        if (indirect) GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, quadIndexBuffer);
        for (int i = 0; i < fadingCount; i++) fadingRegion[i] = null;
    }

    private static void drawFadingSection(int i, int pass, double camX, double camY, double camZ, boolean colour) {
        VboRegion r = fadingRegion[i];
        int k = fadingSlot[i];
        if (r == null || k >= r.count[pass]) return;
        int[] dat = r.dat[pass];
        int base = k * VboRegion.STRIDE;
        if (colour) {
            if (sky) {
                TerrainShader.alpha(fadingAlpha[i]);
            } else {
                ENV_COLOR.clear();
                ENV_COLOR.put(1f).put(1f).put(1f).put(fadingAlpha[i]).flip();
                GL11.glTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, ENV_COLOR);
            }
        }
        bindRegion(r, camX, camY, camZ);
        for (int b = 0; b < CompactMesh.BUCKETS; b++) {
            int c = dat[base + 3 + CompactMesh.BUCKETS + b];
            if (c > 0) GL11.glDrawArrays(GL11.GL_QUADS, dat[base + 3 + b], c);
        }
        GL11.glPopMatrix();
    }

    private static void bindRegion(VboRegion r, double camX, double camY, double camZ) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, r.vbo);
        if (ext) {
            int st = CompactMesh.BYTES_PER_VERTEX_EXT;
            GL11.glVertexPointer(3, GL11.GL_FLOAT, st, 0L);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, st, 12L);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, st, 20L);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
            GL11.glTexCoordPointer(2, GL11.GL_SHORT, st, 24L);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
            GL11.glNormalPointer(GL11.GL_BYTE, st, 28L);
            org.lwjgl.opengl.GL20.glVertexAttribPointer(dev.quicksilver.shader.ShaderBridge.ATTRIB_ENTITY, 2, GL11.GL_UNSIGNED_SHORT, false, st, 32L);
            org.lwjgl.opengl.GL20.glVertexAttribPointer(dev.quicksilver.shader.ShaderBridge.ATTRIB_MID_TEX, 2, GL11.GL_UNSIGNED_SHORT, true, st, 36L);
            org.lwjgl.opengl.GL20.glVertexAttribPointer(dev.quicksilver.shader.ShaderBridge.ATTRIB_TANGENT, 4, GL11.GL_BYTE, true, st, 40L);
            GL11.glPushMatrix();
            GL11.glTranslatef((float) (r.originX - camX), (float) (r.originY - camY), (float) (r.originZ - camZ));
            return;
        }
        GL11.glVertexPointer(3, GL11.GL_SHORT, CompactMesh.BYTES_PER_VERTEX, 0L);
        GL11.glTexCoordPointer(2, GL11.GL_SHORT, CompactMesh.BYTES_PER_VERTEX, 8L);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, CompactMesh.BYTES_PER_VERTEX, 12L);
        if (sky) org.lwjgl.opengl.GL20.glVertexAttribPointer(TerrainShader.ATTRIB_LIGHT, 2, GL11.GL_UNSIGNED_BYTE, false, CompactMesh.BYTES_PER_VERTEX, 6L);
        GL11.glPushMatrix();
        GL11.glTranslatef((float) (r.originX - camX), (float) (r.originY - camY), (float) (r.originZ - camZ));
        GL11.glScalef(1f / CompactMesh.POS_SCALE, 1f / CompactMesh.POS_SCALE, 1f / CompactMesh.POS_SCALE);
        GL11.glTranslatef(CompactMesh.POS_BIAS, CompactMesh.POS_BIAS, CompactMesh.POS_BIAS);
    }

    public static long shadowNs;

    /**
     * Draw one pass of all sections that pass the box test (camera-relative coordinates), with the current program and
     * matrices; for shadow maps. No cave/frustum culling of the camera view and no facing culling (the faces that
     * matter are the ones towards the light).
     */
    public static int renderShadow(int pass, double camX, double camY, double camZ, dev.quicksilver.shader.ShaderBridge.BoxTest test) {
        if (!active() || REGIONS.isEmpty()) return 0;
        long t0 = System.nanoTime();
        ext = dev.quicksilver.shader.ShaderBridge.extended();
        int drawn = 0;
        int nDraw = 0;
        if (indirect) commands.clear();
        boolean stateSet = false;
        for (VboRegion r : REGIONS.values()) {
            int cnt = r.count[pass];
            if (cnt == 0) continue;
            double rx = r.originX - camX, ry = r.originY - camY, rz = r.originZ - camZ;
            if (!test.visible(rx, ry, rz, rx + VboRegion.SIZE_BLOCKS, ry + VboRegion.SIZE_BLOCKS, rz + VboRegion.SIZE_BLOCKS)) continue;
            int[] dat = r.dat[pass];
            int nr = 0;
            int[] rf = rangeFirst, rc = rangeCount;
            for (int k = 0; k < cnt; k++) {
                int base = k * VboRegion.STRIDE;
                double sx = dat[base] - camX, sy = dat[base + 1] - camY, sz = dat[base + 2] - camZ;
                if (!test.visible(sx, sy, sz, sx + 16, sy + 16, sz + 16)) continue;
                drawn++;
                for (int b = 0; b < CompactMesh.BUCKETS; b++) {
                    int c = dat[base + 3 + CompactMesh.BUCKETS + b];
                    if (c == 0) continue;
                    int f = dat[base + 3 + b];
                    if (nr > 0 && rf[nr - 1] + rc[nr - 1] == f) {
                        rc[nr - 1] += c;
                    } else {
                        if (nr == rf.length) {
                            rangeFirst = rf = java.util.Arrays.copyOf(rf, nr * 2);
                            rangeCount = rc = java.util.Arrays.copyOf(rc, nr * 2);
                        }
                        rf[nr] = f;
                        rc[nr++] = c;
                    }
                }
            }
            if (nr == 0) continue;
            if (indirect) {
                int start = commands.position() / 5;
                for (int k = 0; k < nr; k++) {
                    int f = rf[k], c = rc[k];
                    while (c > 0) {
                        int part = Math.min(c, MAX_QUADS_PER_RANGE * 4);
                        if (commands.remaining() < 5) commands = grow(commands);
                        commands.put(part / 4 * 6).put(1).put(0).put(f).put(0);
                        f += part;
                        c -= part;
                    }
                }
                if (nDraw == drawRegions.length) {
                    drawRegions = Arrays.copyOf(drawRegions, nDraw * 2);
                    drawStart = Arrays.copyOf(drawStart, nDraw * 2);
                    drawCount = Arrays.copyOf(drawCount, nDraw * 2);
                }
                drawRegions[nDraw] = r;
                drawStart[nDraw] = start;
                drawCount[nDraw++] = commands.position() / 5 - start;
            } else {
                if (firsts.capacity() < nr) {
                    firsts = BufferUtils.createIntBuffer(nr * 2);
                    counts = BufferUtils.createIntBuffer(nr * 2);
                }
                firsts.clear();
                counts.clear();
                firsts.put(rf, 0, nr).flip();
                counts.put(rc, 0, nr).flip();
                if (!stateSet) {
                    enableState();
                    stateSet = true;
                }
                bindRegion(r, camX, camY, camZ);
                GL14.glMultiDrawArrays(GL11.GL_QUADS, firsts, counts);
                GL11.glPopMatrix();
            }
        }
        if (indirect && nDraw > 0) {
            commands.flip();
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
            GL15.glBufferData(GL40.GL_DRAW_INDIRECT_BUFFER, commands, GL15.GL_STREAM_DRAW);
            enableState();
            stateSet = true;
            for (int d = 0; d < nDraw; d++) {
                bindRegion(drawRegions[d], camX, camY, camZ);
                GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT, (long) drawStart[d] * 20L, drawCount[d], 0);
                GL11.glPopMatrix();
                drawRegions[d] = null;
            }
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        }
        if (stateSet) disableState();
        shadowNs += System.nanoTime() - t0;
        return drawn;
    }

    private static final int[] BUCKET_FIRST = new int[CompactMesh.BUCKETS], BUCKET_COUNT = new int[CompactMesh.BUCKETS];
    private static int[] rangeFirst = new int[1024], rangeCount = new int[1024];

    private static IntBuffer grow(IntBuffer b) {
        IntBuffer nb = BufferUtils.createIntBuffer(b.capacity() * 2);
        b.flip();
        nb.put(b);
        return nb;
    }

    public static long memoryBytes() {
        long m = 0;
        for (VboRegion r : REGIONS.values()) m += r.memoryBytes();
        return m;
    }
}
