package dev.quicksilver.mixin.client;

import dev.quicksilver.QsConfig;
import dev.quicksilver.Quicksilver;
import dev.quicksilver.client.FramePacer;
import dev.quicksilver.io.RegionIO;
import dev.quicksilver.render.ChunkScheduler;
import dev.quicksilver.world.ChunkLoadManager;
import dev.quicksilver.world.LightBudget;
import net.minecraft.client.Minecraft;
import net.minecraft.level.Level;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow public Level level;

    @Inject(method = "init()V", at = @At("TAIL"))
    private void qs$init(CallbackInfo ci) {
        Quicksilver.setClientThread(Thread.currentThread());
        Quicksilver.ensureConfig();
        dev.quicksilver.client.RenderDistance.MinecraftHolder.set((Minecraft) (Object) this);
        dev.quicksilver.client.RenderDistance.set(QsConfig.renderDistance);
        try {
            dev.quicksilver.client.MonitorSync.apply();
        } catch (Throwable ignored) {
        }
    }

    /** Progressive chunk loading runs once per frame, right before lighting and rendering. */
    @Inject(method = "run()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/level/Level;method_232()Z"))
    private void qs$beforeLighting(CallbackInfo ci) {
        long lt0 = System.nanoTime();
        ChunkLoadManager.frame((Minecraft) (Object) this);
        dev.quicksilver.client.PerfLog.loaderNs += System.nanoTime() - lt0;
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.level != null) dev.quicksilver.world.Relighter.frame(mc.level,
                (long) (Math.min(1.0, Math.max(0.25, dev.quicksilver.render.ChunkScheduler.avgFrameMs() * 0.1)) * 1_000_000L));
    }

    @Inject(method = "run()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/sortme/GameRenderer;method_1844(F)V"))
    private void qs$renderStart(CallbackInfo ci) {
        dev.quicksilver.client.PerfLog.renderStart();
    }

    @Inject(method = "run()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/sortme/GameRenderer;method_1844(F)V", shift = At.Shift.AFTER))
    private void qs$renderEnd(CallbackInfo ci) {
        dev.quicksilver.client.PerfLog.renderEnd();
    }

    @Redirect(method = "run()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/level/Level;method_232()Z"))
    private boolean qs$budgetedLighting(Level level) {
        return LightBudget.run(level);
    }

    @Redirect(method = "run()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;printOpenGLError(Ljava/lang/String;)V"))
    private void qs$glErrors(Minecraft self, String where) {
        if (!QsConfig.skipGlErrorChecks) ((MinecraftInvoker) self).qs$printOpenGLError(where);
    }

    @Inject(method = "run()V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;update()V"))
    private void qs$beforeSwap(CallbackInfo ci) {
        FramePacer.pace();
        ChunkScheduler.newFrame();
        dev.quicksilver.client.PerfLog.swapStart();
    }

    @Inject(method = "run()V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;update()V", shift = At.Shift.AFTER))
    private void qs$afterSwap(CallbackInfo ci) {
        dev.quicksilver.client.PerfLog.swapEnd();
    }

    @Redirect(method = "tick()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/texture/TextureManager;tick()V"))
    private void qs$timedTextureTick(net.minecraft.client.texture.TextureManager tm) {
        long t0 = System.nanoTime();
        tm.tick();
        dev.quicksilver.client.PerfLog.textureNs += System.nanoTime() - t0;
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void qs$tickStart(CallbackInfo ci) {
        dev.quicksilver.client.PerfLog.tickStart();
    }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void qs$tickEnd(CallbackInfo ci) {
        dev.quicksilver.client.PerfLog.tickEnd();
    }

    /**
     * Vanilla tries to save the world when the window is closed, but its progress screen throws as soon as the game
     * is no longer "running", so nothing gets saved. Save singleplayer worlds here (without a progress screen) and
     * wait for the IO thread.
     */
    @Inject(method = "stop()V", at = @At("HEAD"))
    private void qs$saveOnStop(CallbackInfo ci) {
        try {
            if (this.level != null && this.level.getCache() != null && this.level.getCache().method_1805()) {
                this.level.saveLevel(true, null);
            }
        } catch (Throwable t) {
            Quicksilver.LOGGER.warn("Failed to save the world on exit", t);
        }
        RegionIO.flush(60_000);
    }
}
