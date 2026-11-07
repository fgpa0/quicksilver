package dev.quicksilver.mixin.shader;

import dev.quicksilver.shader.render.Hooks;
import net.minecraft.client.Minecraft;
import net.minecraft.sortme.GameRenderer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow private Minecraft minecraft;
    @Unique private float qsh$fogR, qsh$fogG, qsh$fogB;

    @Inject(method = "delta(FJ)V", at = @At("HEAD"))
    private void qsh$beginFrame(float delta, long until, CallbackInfo ci) {
        Hooks.beginFrame();
    }

    @Redirect(method = "method_1852(F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glClearColor(FFFF)V", remap = false))
    private void qsh$fogColor(float r, float g, float b, float a) {
        qsh$fogR = r;
        qsh$fogG = g;
        qsh$fogB = b;
        Hooks.fogColor(r, g, b);
        GL11.glClearColor(r, g, b, a);
    }

    @Redirect(method = "delta(FJ)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glClear(I)V", ordinal = 0, remap = false))
    private void qsh$clear(int mask) {
        if (!Hooks.clear(qsh$fogR, qsh$fogG, qsh$fogB)) GL11.glClear(mask);
    }

    @Inject(method = "delta(FJ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/sortme/GameRenderer;method_1840(FI)V", shift = At.Shift.AFTER))
    private void qsh$afterCamera(float delta, long until, CallbackInfo ci) {
        Hooks.afterCamera(delta);
    }

    /** Depth is not cleared for the hand with shaders: its depth is scaled into 0.44-0.56 instead (MC_HAND_DEPTH). */
    @Redirect(method = "delta(FJ)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glClear(I)V", ordinal = 1, remap = false))
    private void qsh$handClear(int mask) {
        if (Hooks.p() == null) GL11.glClear(mask);
    }

    @Inject(method = "delta(FJ)V", at = @At("RETURN"))
    private void qsh$endFrame(float delta, long until, CallbackInfo ci) {
        Hooks.endFrame();
    }

    @Inject(method = "method_1847(F)V", at = @At("HEAD"))
    private void qsh$weather(float delta, CallbackInfo ci) {
        Hooks.begin("gbuffers_weather");
    }

    @Inject(method = "method_1847(F)V", at = @At("RETURN"))
    private void qsh$weatherEnd(float delta, CallbackInfo ci) {
        Hooks.end();
    }

    @Unique private boolean qsh$hand;

    @Inject(method = "method_1845(FI)V", at = @At("HEAD"))
    private void qsh$hand(float delta, int pass, CallbackInfo ci) {
        qsh$hand = Hooks.p() != null;
        if (!qsh$hand) return;
        Hooks.beforeHand();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        java.nio.FloatBuffer proj = org.lwjgl.BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, proj);
        GL11.glLoadIdentity();
        GL11.glScalef(1f, 1f, 0.125f);
        GL11.glMultMatrix(proj);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        Hooks.setLightAt(minecraft.level, minecraft.player.x, minecraft.player.y, minecraft.player.z);
        Hooks.begin("gbuffers_hand");
    }

    @Inject(method = "method_1845(FI)V", at = @At("RETURN"))
    private void qsh$handEnd(float delta, int pass, CallbackInfo ci) {
        if (!qsh$hand) return;
        qsh$hand = false;
        Hooks.end();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }
}
