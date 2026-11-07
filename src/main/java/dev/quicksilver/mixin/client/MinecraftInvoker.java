package dev.quicksilver.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftInvoker {
    @Invoker("printOpenGLError")
    void qs$printOpenGLError(String where);
}
