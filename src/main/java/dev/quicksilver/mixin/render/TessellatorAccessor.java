package dev.quicksilver.mixin.render;

import net.minecraft.client.render.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Tessellator.class)
public interface TessellatorAccessor {
    @Invoker("<init>")
    static Tessellator qs$create(int bufferSize) {
        throw new AssertionError();
    }
}
