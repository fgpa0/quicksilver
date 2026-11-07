package dev.quicksilver.mixin.io;

import dev.quicksilver.QsConfig;
import dev.quicksilver.io.RegionIO;
import java.io.File;
import net.minecraft.level.Level;
import net.minecraft.level.LevelManager;
import net.minecraft.level.chunk.Chunk;
import net.minecraft.level.chunk.LevelChunkLoader;
import net.minecraft.util.io.AbstractTag;
import net.minecraft.util.io.CompoundTag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chunk saving with the game thread doing only the part that must happen there: building the chunk's NBT (entities
 * and tile entities are live objects) with private copies of its block and light arrays. Turning that into bytes,
 * compressing and writing it happen on the IO thread. Vanilla also re-read session.lock from disk for every chunk and
 * asked the region file for its size afterwards, which could wait for the IO thread's write to the same file.
 */
@Mixin(LevelChunkLoader.class)
public abstract class LevelChunkLoaderMixin {
    @Shadow @Final private File file;

    @Unique private static long qs$lastLockCheck;
    @Unique private static final String[] QS_ARRAYS = {"Blocks", "Data", "SkyLight", "BlockLight", "HeightMap"};

    @Inject(method = "saveChunk(Lnet/minecraft/level/Level;Lnet/minecraft/level/chunk/Chunk;)V", at = @At("HEAD"), cancellable = true)
    private void qs$saveAsync(Level level, Chunk chunk, CallbackInfo ci) {
        if (!QsConfig.asyncChunkSaving || RegionIO.isIoThread()) return;
        long now = System.nanoTime();
        if (now - qs$lastLockCheck > 1_000_000_000L) {
            level.checkSessionLock(); // vanilla does this per chunk; once a second is plenty
            qs$lastLockCheck = now;
        }
        CompoundTag root = new CompoundTag();
        CompoundTag tag = new CompoundTag();
        root.put("Level", (AbstractTag) tag);
        LevelManager.method_1480(chunk, level, tag);
        // the tag references the chunk's live arrays: give the IO thread its own copies
        for (String k : QS_ARRAYS) {
            byte[] a = tag.getByteArray(k);
            if (a != null && a.length > 0) tag.put(k, a.clone());
        }
        RegionIO.submitChunkTag(this.file, chunk.x, chunk.z, root, level.getProperties());
        ci.cancel();
    }
}
