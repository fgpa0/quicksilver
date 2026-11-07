package dev.quicksilver.mixin.client;

import dev.quicksilver.QsConfig;
import dev.quicksilver.client.RenderDistance;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.options.Option;
import net.minecraft.client.resource.language.TranslationStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameOptions.class)
public abstract class GameOptionsMixin {
    @Inject(method = "getFloatValue(Lnet/minecraft/client/options/Option;)F", at = @At("HEAD"), cancellable = true)
    private void qs$getRenderDistance(Option option, CallbackInfoReturnable<Float> cir) {
        if (option == Option.field_1101) {
            cir.setReturnValue((float) (RenderDistance.chunks() - RenderDistance.MIN) / (RenderDistance.MAX - RenderDistance.MIN));
        }
    }

    @Inject(method = "method_1228(Lnet/minecraft/client/options/Option;F)V", at = @At("HEAD"), cancellable = true)
    private void qs$setRenderDistance(Option option, float value, CallbackInfo ci) {
        if (option == Option.field_1101) {
            int chunks = RenderDistance.MIN + Math.round(value * (RenderDistance.MAX - RenderDistance.MIN));
            if (chunks != QsConfig.renderDistance) {
                RenderDistance.set(chunks);
                QsConfig.save();
            }
            ci.cancel();
        }
    }

    @Inject(method = "getTranslatedValue(Lnet/minecraft/client/options/Option;)Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void qs$renderDistanceLabel(Option option, CallbackInfoReturnable<String> cir) {
        if (option == Option.field_1101) {
            String name = TranslationStorage.getInstance().translate(option.getTranslationKey());
            cir.setReturnValue(name + ": " + RenderDistance.chunks() + " chunks");
        }
    }
}
