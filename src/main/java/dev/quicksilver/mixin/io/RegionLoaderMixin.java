package dev.quicksilver.mixin.io;

import dev.quicksilver.QsConfig;
import dev.quicksilver.io.RegionIO;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import net.minecraft.level.storage.RegionLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RegionLoader.class)
public abstract class RegionLoaderMixin {
    @Inject(method = "method_1215(Ljava/io/File;II)Ljava/io/DataInputStream;", at = @At("HEAD"), cancellable = true)
    private static void qs$read(File dir, int x, int z, CallbackInfoReturnable<DataInputStream> cir) {
        if (RegionIO.isIoThread()) return;
        cir.setReturnValue(RegionIO.openRead(dir, x, z));
    }

    @Inject(method = "method_1216(Ljava/io/File;II)Ljava/io/DataOutputStream;", at = @At("HEAD"), cancellable = true)
    private static void qs$write(File dir, int x, int z, CallbackInfoReturnable<DataOutputStream> cir) {
        if (QsConfig.asyncChunkSaving && !RegionIO.isIoThread()) {
            cir.setReturnValue(RegionIO.openWrite(dir, x, z));
        }
    }

    @Inject(method = "clearCache()V", at = @At("HEAD"))
    private static void qs$flushBeforeClose(CallbackInfo ci) {
        RegionIO.flush(60_000);
        RegionIO.clearReadCache();
    }
}
