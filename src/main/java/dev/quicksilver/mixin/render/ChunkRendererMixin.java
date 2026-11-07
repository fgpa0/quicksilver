package dev.quicksilver.mixin.render;

import dev.quicksilver.render.QsSection;
import java.util.List;
import net.minecraft.class_66;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(class_66.class)
public abstract class ChunkRendererMixin implements QsSection {
    @Shadow private int field_225;
    @Shadow private boolean field_227;
    @Shadow private List field_228;

    @Unique private volatile int qs$epoch;
    @Unique private boolean qs$inFlight;
    @Unique private boolean qs$forceSync;
    @Unique private long qs$visGraph = dev.quicksilver.render.VisGraph.ALL;
    @Unique private int qs$visibleFrame;
    @Unique private int qs$slot = -1;
    @Override public int qs$slot() { return qs$slot; }
    @Override public void qs$setSlot(int i) { qs$slot = i; }
    @Unique private long qs$lightPendingSince;
    @Unique private long qs$fadeStart;

    @Override
    public long qs$fadeStart() {
        return qs$fadeStart;
    }

    @Override
    public void qs$setFadeStart(long t) {
        qs$fadeStart = t;
    }

    @Unique private Object qs$frustumGroup;
    @Unique private int qs$frustumIndex;

    @Override
    public Object qs$frustumGroup() {
        return qs$frustumGroup;
    }

    @Override
    public void qs$setFrustumGroup(Object g, int index) {
        qs$frustumGroup = g;
        qs$frustumIndex = index;
    }

    @Override
    public int qs$frustumIndex() {
        return qs$frustumIndex;
    }

    /** Keep the frustum culler's per-region grouping in sync when the section moves. */
    @Inject(method = "method_298(III)V", at = @At("RETURN"))
    private void qs$moved(int x, int y, int z, CallbackInfo ci) {
        dev.quicksilver.render.FastFrustum.sectionMoved((class_66) (Object) this);
        dev.quicksilver.render.CaveCuller.sectionMoved((class_66) (Object) this);
        dev.quicksilver.render.ChunkScheduler.sectionMoved();
    }

    @Override
    public long qs$lightPendingSince() {
        return qs$lightPendingSince;
    }

    @Override
    public void qs$setLightPendingSince(long t) {
        qs$lightPendingSince = t;
    }
    @Unique private int qs$entryMask;
    @Unique private final dev.quicksilver.render.vbo.SectionAlloc[] qs$allocs = new dev.quicksilver.render.vbo.SectionAlloc[2];

    @Override
    public dev.quicksilver.render.vbo.SectionAlloc qs$alloc(int pass) {
        return qs$allocs[pass];
    }

    @Override
    public void qs$setAlloc(int pass, dev.quicksilver.render.vbo.SectionAlloc a) {
        qs$allocs[pass] = a;
    }

    @Override
    public int qs$entryMask() {
        return qs$entryMask;
    }

    @Override
    public void qs$setEntryMask(int m) {
        qs$entryMask = m;
    }

    @Override
    public long qs$visGraph() {
        return qs$visGraph;
    }

    @Override
    public void qs$setVisGraph(long g) {
        if (qs$visGraph == g) return;
        qs$visGraph = g;
        dev.quicksilver.render.CaveCuller.graphChanged((class_66) (Object) this); // reads the new graph
    }

    @Override
    public int qs$visibleFrame() {
        return qs$visibleFrame;
    }

    @Override
    public void qs$setVisibleFrame(int f) {
        qs$visibleFrame = f;
    }

    /** method_301 resets the section (reposition / destroy): any in-flight build becomes stale. */
    @Inject(method = "method_301()V", at = @At("HEAD"))
    private void qs$reset(CallbackInfo ci) {
        qs$epoch++;
        qs$inFlight = false;
        qs$visGraph = dev.quicksilver.render.VisGraph.ALL;
        qs$fadeStart = 0;
        dev.quicksilver.render.CaveCuller.graphChanged((class_66) (Object) this);
        if (qs$allocs[0] != null || qs$allocs[1] != null) dev.quicksilver.render.vbo.VboRenderer.free((class_66) (Object) this);
    }

    /*
     * When a section is repositioned (player crossed a section border) vanilla recompiles a display list holding its
     * bounding box, used only by hardware occlusion queries. Hundreds of sections move at once, so skip it when
     * occlusion queries are off.
     */
    @org.spongepowered.asm.mixin.injection.Redirect(method = "method_298(III)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glNewList(II)V", remap = false))
    private void qs$skipBoxList(int list, int mode) {
        if (!dev.quicksilver.QsConfig.disableOcclusionQueries && !dev.quicksilver.QsConfig.caveCulling) org.lwjgl.opengl.GL11.glNewList(list, mode);
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "method_298(III)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/ItemRenderer;method_2024(Lnet/minecraft/util/maths/Box;)V"))
    private void qs$skipBoxDraw(net.minecraft.util.maths.Box box) {
        if (!dev.quicksilver.QsConfig.disableOcclusionQueries && !dev.quicksilver.QsConfig.caveCulling) net.minecraft.client.render.entity.ItemRenderer.method_2024(box);
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "method_298(III)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEndList()V", remap = false))
    private void qs$skipBoxEnd() {
        if (!dev.quicksilver.QsConfig.disableOcclusionQueries && !dev.quicksilver.QsConfig.caveCulling) org.lwjgl.opengl.GL11.glEndList();
    }

    @Override
    public int qs$epoch() {
        return qs$epoch;
    }

    @Override
    public void qs$bumpEpoch() {
        qs$epoch++;
        qs$inFlight = false;
    }

    @Override
    public boolean qs$inFlight() {
        return qs$inFlight;
    }

    @Override
    public void qs$setInFlight(boolean v) {
        qs$inFlight = v;
    }

    @Override
    public boolean qs$forceSync() {
        return qs$forceSync;
    }

    @Override
    public void qs$setForceSync(boolean v) {
        qs$forceSync = v;
    }

    @Override
    public int qs$listBase() {
        return field_225;
    }

    @Override
    public void qs$setInitialized(boolean v) {
        field_227 = v;
    }

    @Override
    public boolean qs$initialized() {
        return field_227;
    }

    @Override
    public List qs$globalTileEntities() {
        return field_228;
    }
}
