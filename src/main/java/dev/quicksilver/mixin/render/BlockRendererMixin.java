package dev.quicksilver.mixin.render;

import dev.quicksilver.render.MeshWorkers;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.block.BlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Block rendering on mesh worker threads must write into the worker's own tessellator. */
@Mixin(BlockRenderer.class)
public abstract class BlockRendererMixin {
    @Redirect(method = "*", require = 1, allow = 1000,
            at = @At(value = "FIELD", opcode = org.objectweb.asm.Opcodes.GETSTATIC,
                    target = "Lnet/minecraft/client/render/Tessellator;INSTANCE:Lnet/minecraft/client/render/Tessellator;"))
    private Tessellator qs$threadTessellator() {
        return MeshWorkers.tessellator();
    }
}
