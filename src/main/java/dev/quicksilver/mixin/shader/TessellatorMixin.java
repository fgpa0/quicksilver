package dev.quicksilver.mixin.shader;

import dev.quicksilver.shader.render.Hooks;
import net.minecraft.client.render.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shader packs need normals; vanilla only sends them for entity models. Compute face normals for other quads. */
@Mixin(value = Tessellator.class, priority = 900)
public abstract class TessellatorMixin {
    @Shadow private int[] bufferArray;
    @Shadow private int vertexCount;
    @Shadow private boolean hasNormals;
    @Shadow private int drawingMode;
    @Shadow private boolean drawing;
    @Shadow private static boolean useTriangles;
    @Shadow private int normal;

    @Inject(method = "draw()V", at = @At("HEAD"))
    private void qsh$normals(CallbackInfo ci) {
        if (!Hooks.fixNormals() || !drawing || vertexCount < 3) return;
        if (((dev.quicksilver.render.QsTessellator) (Object) this).qs$getCapture() != null) return;
        boolean tri = drawingMode == 4 || (drawingMode == 7 && useTriangles);
        int[] b = bufferArray;
        if (hasNormals) {
            // quads split into triangles (v0 v1 v2 v0 v2 v3): vanilla doesn't copy the normal into the two duplicated vertices
            if (drawingMode == 7 && useTriangles) {
                for (int v = 0; v + 6 <= vertexCount; v += 6) {
                    int n = b[v * 8 + 6];
                    b[(v + 3) * 8 + 6] = n;
                    b[(v + 4) * 8 + 6] = b[(v + 2) * 8 + 6];
                }
            }
            return;
        }
        int step;
        if (tri) step = 3;
        else if (drawingMode == 7) step = 4;
        else return;
        for (int v = 0; v + step <= vertexCount; v += step) {
            int o = v * 8;
            float ax = Float.intBitsToFloat(b[o]), ay = Float.intBitsToFloat(b[o + 1]), az = Float.intBitsToFloat(b[o + 2]);
            float e1x = Float.intBitsToFloat(b[o + 8]) - ax, e1y = Float.intBitsToFloat(b[o + 9]) - ay, e1z = Float.intBitsToFloat(b[o + 10]) - az;
            float e2x = Float.intBitsToFloat(b[o + 16]) - ax, e2y = Float.intBitsToFloat(b[o + 17]) - ay, e2z = Float.intBitsToFloat(b[o + 18]) - az;
            float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
            float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l < 1e-12f) {
                nx = 0;
                ny = 1;
                nz = 0;
            } else {
                nx /= l;
                ny /= l;
                nz /= l;
            }
            int packed = (Math.round(nx * 127) & 255) | (Math.round(ny * 127) & 255) << 8 | (Math.round(nz * 127) & 255) << 16;
            for (int k = 0; k < step; k++) b[o + k * 8 + 6] = packed;
        }
        hasNormals = true;
    }

    /** Vanilla packs x as (byte) (x * 128), so +1 wraps to -1: entity faces pointing +X get inverted normals. */
    @Inject(method = "setNormal(FFF)V", at = @At("HEAD"), cancellable = true)
    private void qsh$setNormal(float x, float y, float z, CallbackInfo ci) {
        if (!Hooks.fixNormals()) return;
        hasNormals = true;
        normal = ((byte) (int) (x * 127f) & 255) | ((byte) (int) (y * 127f) & 255) << 8 | ((byte) (int) (z * 127f) & 255) << 16;
        ci.cancel();
    }
}
