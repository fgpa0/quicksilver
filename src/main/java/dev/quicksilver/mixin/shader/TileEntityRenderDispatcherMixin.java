package dev.quicksilver.mixin.shader;

import dev.quicksilver.shader.pipeline.Pipeline;
import dev.quicksilver.shader.render.Hooks;
import net.minecraft.client.render.entity.TileEntityRenderDispatcher;
import net.minecraft.tileentity.TileEntityBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TileEntityRenderDispatcher.class)
public abstract class TileEntityRenderDispatcherMixin {
    @Inject(method = "method_1278(Lnet/minecraft/tileentity/TileEntityBase;F)V", at = @At("HEAD"))
    private void qsh$tileEntity(TileEntityBase te, float delta, CallbackInfo ci) {
        Pipeline p = Hooks.p();
        if (p == null) return;
        Hooks.push("gbuffers_block");
        Hooks.setLightAt(te.level, te.x + 0.5, te.y + 0.5, te.z + 0.5);
        int id = te.level.getTileId(te.x, te.y, te.z);
        p.frame_blockEntityId(p.pack.ids.block(id, te.level.getTileMeta(te.x, te.y, te.z), false));
        p.refreshUniforms();
    }

    @Inject(method = "method_1278(Lnet/minecraft/tileentity/TileEntityBase;F)V", at = @At("RETURN"))
    private void qsh$tileEntityDone(TileEntityBase te, float delta, CallbackInfo ci) {
        Pipeline p = Hooks.p();
        if (p == null) return;
        p.frame_blockEntityId(-1);
        Hooks.pop();
    }
}
