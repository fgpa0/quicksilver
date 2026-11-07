package dev.quicksilver.mixin.shader;

import dev.quicksilver.shader.gui.ShaderPackScreen;
import net.minecraft.client.gui.screen.ScreenBase;
import net.minecraft.client.gui.screen.menu.VideoSettings;
import net.minecraft.client.gui.widgets.Button;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** "Shaders..." button in Video Settings. */
@Mixin(VideoSettings.class)
public abstract class VideoSettingsMixin extends ScreenBase {
    private static final int SHADERS_ID = 4747;

    @SuppressWarnings("unchecked")
    @Inject(method = "init()V", at = @At("RETURN"))
    private void qsh$addButton(CallbackInfo ci) {
        this.buttons.add(new Button(SHADERS_ID, this.width / 2 - 155, this.height / 6 + 24 * 5, 150, 20, "Shaders..."));
    }

    @Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
    private void qsh$click(Button b, CallbackInfo ci) {
        if (b.id == SHADERS_ID) {
            this.minecraft.openScreen(new ShaderPackScreen(this));
            ci.cancel();
        }
    }
}
