package dev.quicksilver.mixin.shader;

import dev.quicksilver.shader.render.Hooks;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.EntityBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "method_1921(Lnet/minecraft/entity/EntityBase;F)V", at = @At("HEAD"))
    private void qsh$entity(EntityBase e, float delta, CallbackInfo ci) {
        Hooks.entity(e, delta);
    }

    @Inject(method = "method_1921(Lnet/minecraft/entity/EntityBase;F)V", at = @At("RETURN"))
    private void qsh$entityDone(EntityBase e, float delta, CallbackInfo ci) {
        Hooks.entityDone(e);
    }
}
