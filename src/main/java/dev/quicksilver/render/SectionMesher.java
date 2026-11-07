package dev.quicksilver.render;

import net.minecraft.block.BlockBase;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.block.BlockRenderer;

/** Builds the vertex data of one section from a snapshot. Runs on worker threads. */
public final class SectionMesher {
    private SectionMesher() {}

    public static BuildResult build(BuildTask task, Tessellator tess, MeshCapture capture) {
        long t0 = System.nanoTime();
        BuildResult result = new BuildResult(task);
        SectionSnapshot snap = task.snapshot;
        boolean[] safe = task.safe;
        BlockBase[] byId = BlockBase.BY_ID;
        int x0 = snap.x0, y0 = snap.y0, z0 = snap.z0;
        QsTessellator qt = (QsTessellator) tess;
        snap.ensureCopied();
        if (!snap.empty) snap.prepareBiomes();
        dev.quicksilver.shader.ShaderBridge.Provider shader = task.ext ? dev.quicksilver.shader.ShaderBridge.provider() : null;
        if (task.ext && shader == null) return result; // shaders were switched off meanwhile: this result is discarded anyway
        snap.shaderMode = task.ext || task.sky;
        BlockRenderer renderer = new BlockRenderer(snap);
        Runs runs = task.ext ? RUNS.get() : null;
        try {
            for (int pass = 0; pass < 2; pass++) {
                BuildResult.PassResult pr = new BuildResult.PassResult();
                result.passes[pass] = pr;
                boolean other = false;
                if (snap.empty) break;
                capture.reset();
                qt.qs$setCapture(capture);
                tess.start();
                tess.setOffset(-x0, -y0, -z0);
                int[] deferred = null;
                int deferredCount = 0;
                if (runs != null) runs.count = 0;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int id = snap.rawId(x + 1, y + 1, z + 1);
                            if (id == 0) continue;
                            pr.started = true;
                            BlockBase block = byId[id];
                            if (block == null) continue;
                            int rp = block.getRenderPass();
                            if (rp != pass) {
                                other = true;
                            } else if (id < safe.length && safe[id]) {
                                if (runs != null) {
                                    int before = capture.vertexCount + qt.qs$vertexCount();
                                    pr.rendered |= renderer.render(block, x0 + x, y0 + y, z0 + z);
                                    if (capture.vertexCount + qt.qs$vertexCount() > before) runs.add(before, blockId(shader, snap, id, x0 + x, y0 + y, z0 + z));
                                } else {
                                    pr.rendered |= renderer.render(block, x0 + x, y0 + y, z0 + z);
                                }
                            } else {
                                if (deferred == null) deferred = new int[64];
                                else if (deferredCount == deferred.length) deferred = java.util.Arrays.copyOf(deferred, deferredCount * 2);
                                deferred[deferredCount++] = x | y << 8 | z << 16;
                            }
                        }
                    }
                }
                tess.draw();
                tess.setOffset(0, 0, 0);
                if (task.vbo) {
                    if (!capture.isEmpty()) {
                        float ox = x0 - dev.quicksilver.render.vbo.VboRenderer.regionOrigin(x0);
                        float oy = y0 - dev.quicksilver.render.vbo.VboRenderer.regionOrigin(y0);
                        float oz = z0 - dev.quicksilver.render.vbo.VboRenderer.regionOrigin(z0);
                        pr.compact = new dev.quicksilver.render.vbo.CompactMesh(task.ext);
                        if (task.ext) {
                            pr.compact.appendExt(capture, ox, oy, oz, x0, y0, z0, runs.data, runs.count, snap, task.smooth,
                                    shader.oldLighting(), shader.separateAo());
                        } else if (task.sky) {
                            pr.compact.appendSky(capture, ox, oy, oz, x0, y0, z0, snap, task.smooth);
                        } else {
                            pr.compact.append(capture, ox, oy, oz);
                        }
                    }
                } else {
                    pr.mesh = capture.isEmpty() ? null : capture.copy();
                }
                pr.deferred = deferred;
                pr.deferredCount = deferredCount;
                if (!other) break;
            }
        } finally {
            qt.qs$setCapture(null);
        }
        result.lit = snap.litSeen;
        if (dev.quicksilver.QsConfig.caveCulling) result.visGraph = snap.computeVisGraph();
        result.buildNs = System.nanoTime() - t0;
        return result;
    }

    /** mc_Entity.x of the block at x, y, z. */
    public static int blockId(dev.quicksilver.shader.ShaderBridge.Provider shader, SectionSnapshot snap, int id, int x, int y, int z) {
        boolean snow = false;
        if (id == 2 || id == 3) {
            int above = snap.getTileId(x, y + 1, z);
            snow = above == 78 || above == 80;
        }
        return shader.blockId(id, snap.getTileMeta(x, y, z), snow);
    }

    /** Vertex ranges per block id: pairs of (first vertex, mc_Entity.x). */
    public static final class Runs {
        public int[] data = new int[256];
        public int count;

        public void add(int start, int id) {
            if (count > 0 && data[(count - 1) * 2 + 1] == id) return;
            if (count * 2 + 2 > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            data[count * 2] = start;
            data[count * 2 + 1] = id;
            count++;
        }
    }

    private static final ThreadLocal<Runs> RUNS = ThreadLocal.withInitial(Runs::new);
}
