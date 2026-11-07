package dev.quicksilver.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.block.BlockBase;
import net.minecraft.class_66;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.block.BlockRenderer;
import org.lwjgl.opengl.GL11;

/** Turns finished build results into display lists. Main (GL) thread only. */
public final class MeshUploader {
    private MeshUploader() {}

    private static ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.nativeOrder());
    private static IntBuffer ints = buffer.asIntBuffer();
    private static FloatBuffer floats = buffer.asFloatBuffer();

    public static void upload(BuildResult r) {
        class_66 s = r.task.section;
        QsSection qs = (QsSection) s;
        SectionSnapshot snap = r.task.snapshot;
        snap.resolveTileEntities();
        class_66.field_230++;
        s.field_244[0] = true;
        s.field_244[1] = true;
        int listBase = qs.qs$listBase();
        BlockRenderer mainRenderer = null;
        if (r.task.vbo) {
            uploadVbo(r, s, snap);
        } else for (int pass = 0; pass < 2; pass++) {
            BuildResult.PassResult pr = r.passes[pass];
            if (pr == null) break;
            if (!pr.started) continue;
            boolean rendered = pr.rendered;
            GL11.glNewList(listBase + pass, GL11.GL_COMPILE);
            GL11.glPushMatrix();
            GL11.glTranslatef((float) s.field_240, (float) s.field_241, (float) s.field_242);
            float scale = 1.000001F;
            GL11.glTranslatef((float) (-s.field_236) / 2.0F, (float) (-s.field_235) / 2.0F, (float) (-s.field_236) / 2.0F);
            GL11.glScalef(scale, scale, scale);
            GL11.glTranslatef((float) s.field_236 / 2.0F, (float) s.field_235 / 2.0F, (float) s.field_236 / 2.0F);
            if (pr.mesh != null) draw(pr.mesh);
            if (pr.deferredCount > 0) {
                if (mainRenderer == null) mainRenderer = new BlockRenderer(snap);
                Tessellator t = Tessellator.INSTANCE;
                t.start();
                t.setOffset(-s.field_231, -s.field_232, -s.field_233);
                BlockBase[] byId = BlockBase.BY_ID;
                for (int i = 0; i < pr.deferredCount; i++) {
                    int p = pr.deferred[i];
                    int x = snap.x0 + (p & 255), y = snap.y0 + ((p >> 8) & 255), z = snap.z0 + ((p >> 16) & 255);
                    BlockBase b = byId[snap.getTileId(x, y, z)];
                    if (b != null) rendered |= mainRenderer.render(b, x, y, z);
                }
                t.draw();
                t.setOffset(0.0, 0.0, 0.0);
            }
            GL11.glPopMatrix();
            GL11.glEndList();
            if (rendered) s.field_244[pass] = false;
        }
        finish(r, s, qs, snap);
        snap.release();
    }

    private static final MeshCapture MAIN_CAPTURE = new MeshCapture();
    private static final SectionMesher.Runs DEFERRED_RUNS = new SectionMesher.Runs();

    private static void uploadVbo(BuildResult r, class_66 s, SectionSnapshot snap) {
        BlockRenderer mainRenderer = null;
        float ox = s.field_231 - dev.quicksilver.render.vbo.VboRenderer.regionOrigin(s.field_231);
        float oy = s.field_232 - dev.quicksilver.render.vbo.VboRenderer.regionOrigin(s.field_232);
        float oz = s.field_233 - dev.quicksilver.render.vbo.VboRenderer.regionOrigin(s.field_233);
        for (int pass = 0; pass < 2; pass++) {
            BuildResult.PassResult pr = r.passes[pass];
            if (pr == null || !pr.started) {
                dev.quicksilver.render.vbo.VboRenderer.upload(s, pass, null);
                continue;
            }
            boolean rendered = pr.rendered;
            dev.quicksilver.render.vbo.CompactMesh cm = pr.compact;
            if (pr.deferredCount > 0) {
                if (mainRenderer == null) mainRenderer = new BlockRenderer(snap);
                Tessellator t = Tessellator.INSTANCE;
                QsTessellator qt = (QsTessellator) t;
                dev.quicksilver.shader.ShaderBridge.Provider shader = r.task.ext ? dev.quicksilver.shader.ShaderBridge.provider() : null;
                if (r.task.ext && shader == null) continue;
                DEFERRED_RUNS.count = 0;
                MAIN_CAPTURE.reset();
                qt.qs$setCapture(MAIN_CAPTURE);
                try {
                    t.start();
                    t.setOffset(-s.field_231, -s.field_232, -s.field_233);
                    BlockBase[] byId = BlockBase.BY_ID;
                    for (int i = 0; i < pr.deferredCount; i++) {
                        int p = pr.deferred[i];
                        int x = snap.x0 + (p & 255), y = snap.y0 + ((p >> 8) & 255), z = snap.z0 + ((p >> 16) & 255);
                        int id = snap.getTileId(x, y, z);
                        BlockBase b = byId[id];
                        if (b == null) continue;
                        if (shader != null) {
                            int before = MAIN_CAPTURE.vertexCount + qt.qs$vertexCount();
                            rendered |= mainRenderer.render(b, x, y, z);
                            if (MAIN_CAPTURE.vertexCount + qt.qs$vertexCount() > before) DEFERRED_RUNS.add(before, SectionMesher.blockId(shader, snap, id, x, y, z));
                        } else {
                            rendered |= mainRenderer.render(b, x, y, z);
                        }
                    }
                    t.draw();
                } finally {
                    qt.qs$setCapture(null);
                    t.setOffset(0.0, 0.0, 0.0);
                }
                if (!MAIN_CAPTURE.isEmpty()) {
                    if (cm == null) cm = new dev.quicksilver.render.vbo.CompactMesh(r.task.ext);
                    if (shader != null) {
                        cm.appendExt(MAIN_CAPTURE, ox, oy, oz, s.field_231, s.field_232, s.field_233, DEFERRED_RUNS.data, DEFERRED_RUNS.count,
                                snap, r.task.smooth, shader.oldLighting(), shader.separateAo());
                    } else if (r.task.sky) {
                        cm.appendSky(MAIN_CAPTURE, ox, oy, oz, s.field_231, s.field_232, s.field_233, snap, r.task.smooth);
                    } else {
                        cm.append(MAIN_CAPTURE, ox, oy, oz);
                    }
                }
            }
            dev.quicksilver.render.vbo.VboRenderer.upload(s, pass, cm);
            if (rendered) s.field_244[pass] = false;
        }
    }

    private static void finish(BuildResult r, class_66 s, QsSection qs, SectionSnapshot snap) {
        // first mesh of a section that just came into view (new terrain): fade it in
        if (!qs.qs$initialized() && dev.quicksilver.QsConfig.chunkFadeInMs > 0) qs.qs$setFadeStart(System.nanoTime() | 1);
        // tile entity bookkeeping, identical to vanilla
        @SuppressWarnings("unchecked")
        List<Object> own = s.field_224;
        @SuppressWarnings("unchecked")
        List<Object> global = qs.qs$globalTileEntities();
        if (own.isEmpty() && snap.renderedTileEntities.isEmpty()) {
            // nothing to add or remove (most sections)
            s.field_223 = r.lit || snap.litSeen;
            qs.qs$setInitialized(true);
            qs.qs$setVisGraph(r.visGraph);
            return;
        }
        Set<Object> old = new HashSet<>(own);
        own.clear();
        own.addAll(snap.renderedTileEntities);
        Set<Object> added = new HashSet<>(own);
        added.removeAll(old);
        global.addAll(added);
        old.removeAll(own);
        global.removeAll(old);
        s.field_223 = r.lit || snap.litSeen;
        qs.qs$setInitialized(true);
        qs.qs$setVisGraph(r.visGraph);
    }

    private static void ensureCapacity(int bytes) {
        if (buffer.capacity() < bytes) {
            int cap = buffer.capacity();
            while (cap < bytes) cap *= 2;
            buffer = ByteBuffer.allocateDirect(cap).order(ByteOrder.nativeOrder());
            ints = buffer.asIntBuffer();
            floats = buffer.asFloatBuffer();
        }
    }

    /** Same client-array draw as Tessellator.draw(), from captured data. */
    public static void draw(MeshCapture m) {
        ensureCapacity(m.intCount * 4);
        ints.clear();
        ints.put(m.data, 0, m.intCount);
        buffer.position(0);
        buffer.limit(m.intCount * 4);
        if (m.hasTexture) {
            floats.position(3);
            GL11.glTexCoordPointer(2, 32, floats);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        if (m.hasColour) {
            buffer.position(20);
            GL11.glColorPointer(4, true, 32, buffer);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        }
        if (m.hasNormals) {
            buffer.position(24);
            GL11.glNormalPointer(32, buffer);
            GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
        }
        floats.position(0);
        GL11.glVertexPointer(3, 32, floats);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        for (int i = 0; i < m.segmentCount; i++) {
            GL11.glDrawArrays(m.segments[i * 3], m.segments[i * 3 + 1], m.segments[i * 3 + 2]);
        }
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        if (m.hasTexture) GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        if (m.hasColour) GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        if (m.hasNormals) GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        buffer.clear();
    }
}
