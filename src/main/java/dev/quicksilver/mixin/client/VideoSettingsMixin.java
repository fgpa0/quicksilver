package dev.quicksilver.mixin.client;

import dev.quicksilver.client.FogSlider;
import dev.quicksilver.gui.QsConfigScreen;
import net.minecraft.client.gui.screen.ScreenBase;
import net.minecraft.client.gui.screen.menu.VideoSettings;
import net.minecraft.client.gui.widgets.Button;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fog distance slider and the "Quicksilver..." settings button in the free row below the vanilla options. */
@Mixin(VideoSettings.class)
public abstract class VideoSettingsMixin extends ScreenBase {
    private static final int SETTINGS_ID = 4746;

    @Inject(method = "init()V", at = @At("RETURN"))
    @SuppressWarnings("unchecked")
    private void qs$addButtons(CallbackInfo ci) {
        this.buttons.add(new FogSlider(this.width / 2 - 155, this.height / 6 + 24 * 4));
        this.buttons.add(new Button(SETTINGS_ID, this.width / 2 + 5, this.height / 6 + 24 * 4, 150, 20, "Quicksilver..."));
    }

    @Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
    private void qs$click(Button b, CallbackInfo ci) {
        if (b.id == SETTINGS_ID) {
            this.minecraft.openScreen(new QsConfigScreen(this));
            ci.cancel();
        }
    }
}
