package dev.quicksilver.mixin.world;

import dev.quicksilver.world.DecorationWindow;
import net.minecraft.level.Level;
import net.minecraft.level.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Routes the two hottest block accessors through the decoration chunk window (a no-op the rest of the time). */
@Mixin(Level.class)
public abstract class LevelBlockAccessMixin {
    @Redirect(method = "getTileId(III)I",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/level/Level;getChunkFromCache(II)Lnet/minecraft/level/chunk/Chunk;"))
    private Chunk qs$readChunk(Level self, int cx, int cz) {
        return DecorationWindow.chunk(self, cx, cz);
    }

    @Redirect(method = "setTileInChunk(IIII)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/level/Level;getChunkFromCache(II)Lnet/minecraft/level/chunk/Chunk;"))
    private Chunk qs$writeChunk(Level self, int cx, int cz) {
        return DecorationWindow.chunk(self, cx, cz);
    }
}
