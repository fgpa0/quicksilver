package dev.quicksilver.mixin.io;

import dev.quicksilver.io.RegionIO;
import java.io.DataInput;
import net.minecraft.util.io.CompoundTag;
import net.minecraft.util.io.NBTIO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NBTIO.class)
public abstract class NBTIOMixin {
    @Inject(method = "readTag(Ljava/io/DataInput;)Lnet/minecraft/util/io/CompoundTag;", at = @At("HEAD"), cancellable = true)
    private static void qs$prefetched(DataInput in, CallbackInfoReturnable<CompoundTag> cir) {
        if (in instanceof RegionIO.PrefetchedChunkStream p) {
            cir.setReturnValue(p.tag);
        }
    }
}
