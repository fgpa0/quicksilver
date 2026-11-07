package dev.quicksilver.render;

import dev.quicksilver.QsConfig;
import dev.quicksilver.mixin.render.WorldRendererAccessor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.class_66;
import net.minecraft.client.render.WorldRenderer;

/**
 * Light-only section rebuilds, batched.
 * <p>
 * Vanilla marks the 3x3x3 block neighbourhood of every changed light value for rebuilding. While a lighting backlog
 * settles (after loading terrain) the same section is touched again and again, and gets re-meshed each time - most
 * of these are invisible one-level tweaks far away. Sections near the camera are still marked immediately; farther
 * ones are collected and rebuilt once their first light change is {@link QsConfig#lightRebuildDelayMs} old, so a
 * burst of changes costs one rebuild instead of dozens. Block changes are not affected.
 */
public final class LightRebuilds {
    private LightRebuilds() {}

    private static final List<class_66> PENDING = new ArrayList<>();
    private static final double NEAR = 48;
    public static long statImmediate, statDeferred, statFlushed;

    // last section looked at: consecutive light changes almost always hit the same one
    private static class_66 lastSection;
    private static int lastCx = Integer.MIN_VALUE, lastCy, lastCz;

    /** Replacement for WorldRenderer.method_1149 (a light value changed at x, y, z). */
    public static void lightChanged(WorldRenderer wr, double camX, double camY, double camZ, int x, int y, int z) {
        int lx = x & 15, ly = y & 15, lz = z & 15;
        if (lx != 0 && lx != 15 && ly != 0 && ly != 15 && lz != 0 && lz != 15) {
            // interior block: only its own section is affected
            int cx = x >> 4, cy = y >> 4, cz = z >> 4;
            class_66 s;
            class_66 c = lastSection;
            if (c != null && cx == lastCx && cy == lastCy && cz == lastCz
                    && c.field_231 == cx << 4 && c.field_232 == cy << 4 && c.field_233 == cz << 4) { // not repositioned since
                s = c;
            } else {
                WorldRendererAccessor acc = (WorldRendererAccessor) wr;
                class_66[] grid = acc.qs$sections();
                if (grid == null) return;
                int sx = acc.qs$sizeX(), sy = acc.qs$sizeY(), sz = acc.qs$sizeZ();
                s = grid[(Math.floorMod(cz, sz) * sy + Math.floorMod(cy, sy)) * sx + Math.floorMod(cx, sx)];
                lastSection = s;
                lastCx = cx;
                lastCy = cy;
                lastCz = cz;
            }
            mark(wr, s, camX, camY, camZ);
            return;
        }
        WorldRendererAccessor acc = (WorldRendererAccessor) wr;
        class_66[] grid = acc.qs$sections();
        if (grid == null) return;
        int sx = acc.qs$sizeX(), sy = acc.qs$sizeY(), sz = acc.qs$sizeZ();
        int x0 = (x - 1) >> 4, y0 = (y - 1) >> 4, z0 = (z - 1) >> 4;
        int x1 = (x + 1) >> 4, y1 = (y + 1) >> 4, z1 = (z + 1) >> 4;
        for (int cx = x0; cx <= x1; cx++) {
            int gx = Math.floorMod(cx, sx);
            for (int cy = y0; cy <= y1; cy++) {
                int gy = Math.floorMod(cy, sy);
                for (int cz = z0; cz <= z1; cz++) {
                    mark(wr, grid[(Math.floorMod(cz, sz) * sy + gy) * sx + gx], camX, camY, camZ);
                }
            }
        }
    }

    /** A light value changed somewhere in section (cx, cy, cz) or next to it (batched by FastLight). */
    public static void sectionLightChanged(WorldRenderer wr, int cx, int cy, int cz) {
        WorldRendererAccessor acc = (WorldRendererAccessor) wr;
        class_66[] grid = acc.qs$sections();
        if (grid == null) return;
        int sx = acc.qs$sizeX(), sy = acc.qs$sizeY(), sz = acc.qs$sizeZ();
        class_66 s = grid[(Math.floorMod(cz, sz) * sy + Math.floorMod(cy, sy)) * sx + Math.floorMod(cx, sx)];
        net.minecraft.client.Minecraft mc = dev.quicksilver.client.RenderDistance.MinecraftHolder.get();
        net.minecraft.entity.Living v = mc != null ? mc.viewEntity : null;
        if (v == null) {
            if (!s.field_249) {
                acc.qs$dirty().add(s);
                s.method_305();
            }
            return;
        }
        mark(wr, s, v.x, v.y, v.z);
    }

    private static void mark(WorldRenderer wr, class_66 s, double camX, double camY, double camZ) {
        if (s.field_249) return; // already waiting for a rebuild
        QsSection q = (QsSection) s;
        if (q.qs$lightPendingSince() != 0) return; // already batched
        double dx = s.field_245 - camX, dy = s.field_246 - camY, dz = s.field_247 - camZ;
        if (QsConfig.lightRebuildDelayMs <= 0 || dx * dx + dy * dy + dz * dz < NEAR * NEAR) {
            ((WorldRendererAccessor) wr).qs$dirty().add(s);
            s.method_305();
            statImmediate++;
            return;
        }
        q.qs$setLightPendingSince(System.nanoTime() | 1);
        PENDING.add(s);
        statDeferred++;
    }

    /** Once per frame: hand light-only rebuilds that have waited long enough to the scheduler. */
    public static void flush(WorldRenderer wr) {
        if (PENDING.isEmpty()) return;
        long now = System.nanoTime();
        long delay = (long) QsConfig.lightRebuildDelayMs * 1_000_000L;
        List<class_66> dirty = ((WorldRendererAccessor) wr).qs$dirty();
        int w = 0;
        for (int i = 0, n = PENDING.size(); i < n; i++) {
            class_66 s = PENDING.get(i);
            QsSection q = (QsSection) s;
            long since = q.qs$lightPendingSince();
            if (since == 0) continue; // cleared elsewhere
            if (s.field_249) { // got dirty for another reason meanwhile: that rebuild includes the light
                q.qs$setLightPendingSince(0);
                continue;
            }
            if (now - since >= delay) {
                q.qs$setLightPendingSince(0);
                dirty.add(s);
                s.method_305();
                statFlushed++;
                continue;
            }
            PENDING.set(w++, s);
        }
        PENDING.subList(w, PENDING.size()).clear();
    }

    /** Renderer reload / world change. */
    public static void clear() {
        lastSection = null;
        lastCx = Integer.MIN_VALUE;
        for (class_66 s : PENDING) ((QsSection) s).qs$setLightPendingSince(0);
        PENDING.clear();
    }
}
