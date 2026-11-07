package dev.quicksilver.mixin.render;

import dev.quicksilver.render.MeshCapture;
import dev.quicksilver.render.QsTessellator;
import net.minecraft.client.render.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets worker-thread tessellators record their vertex data into a {@link MeshCapture} instead of issuing GL calls.
 */
@Mixin(Tessellator.class)
public abstract class TessellatorMixin implements QsTessellator {
    @Shadow private int[] bufferArray;
    @Shadow private int vertexCount;
    @Shadow private boolean hasColour;
    @Shadow private boolean hasTexture;
    @Shadow private boolean hasNormals;
    @Shadow private int field_2068;
    @Shadow private int drawingMode;
    @Shadow private boolean drawing;
    @Shadow private static boolean useTriangles;

    @Shadow protected abstract void clear();

    @Unique private MeshCapture qs$capture;

    @Override
    public void qs$setCapture(MeshCapture capture) {
        this.qs$capture = capture;
    }

    @Override
    public MeshCapture qs$getCapture() {
        return qs$capture;
    }

    @Override
    public int qs$vertexCount() {
        return this.vertexCount;
    }

    @Inject(method = "draw()V", at = @At("HEAD"), cancellable = true)
    private void qs$captureDraw(CallbackInfo ci) {
        MeshCapture capture = this.qs$capture;
        if (capture == null) return;
        ci.cancel();
        if (!this.drawing) throw new IllegalStateException("Not tesselating!");
        this.drawing = false;
        if (this.vertexCount > 0) {
            capture.append(this.bufferArray, this.field_2068, this.vertexCount, (this.drawingMode == 7 && useTriangles) ? 4 : this.drawingMode, this.hasTexture, this.hasColour, this.hasNormals);
        }
        this.clear();
    }
}
