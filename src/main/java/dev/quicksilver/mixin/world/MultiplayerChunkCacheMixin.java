package dev.quicksilver.mixin.world;

import dev.quicksilver.world.FastChunkMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.level.chunk.MultiplayerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiplayerChunkCache.class)
public abstract class MultiplayerChunkCacheMixin {
    @Shadow private Map multiplayerChunkCache;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void qs$fastMap(CallbackInfo ci) {
        if (multiplayerChunkCache != null && multiplayerChunkCache.getClass() == HashMap.class) {
            FastChunkMap m = new FastChunkMap();
            m.putAll(multiplayerChunkCache);
            multiplayerChunkCache = m;
        }
    }
}
