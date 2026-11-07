package dev.quicksilver.mixin.world;

import dev.quicksilver.world.FastChunkMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.level.chunk.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin implements dev.quicksilver.world.QsChunkCache {
    @Shadow private Map serverChunkCache;
    @Shadow private net.minecraft.level.Level level;
    @Shadow private net.minecraft.level.chunk.Chunk field_1226;

    @org.spongepowered.asm.mixin.injection.Redirect(method = "loadChunk(II)Lnet/minecraft/level/chunk/Chunk;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/level/source/LevelSource;getChunk(II)Lnet/minecraft/level/chunk/Chunk;"))
    private net.minecraft.level.chunk.Chunk qs$pregenerated(net.minecraft.level.source.LevelSource source, int x, int z) {
        net.minecraft.level.chunk.Chunk c = dev.quicksilver.world.AsyncWorldGen.take(level, x, z);
        return c != null ? c : source.getChunk(x, z);
    }

    @Inject(method = "loadChunk(II)Lnet/minecraft/level/chunk/Chunk;", at = @At("HEAD"), cancellable = true)
    private void qs$traceSyncLoads(int x, int z, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.level.chunk.Chunk> cir) {
        if (!dev.quicksilver.Quicksilver.isClientThread()) {
            // another mod's background thread asked for a chunk that isn't loaded: never load/generate off-thread
            cir.setReturnValue(field_1226);
            return;
        }
        java.util.Arrays.fill(qs$cache, null);
        dev.quicksilver.world.ChunkLoadManager.traceLoad(x, z);
    }

    @Inject(method = "loadChunk(II)Lnet/minecraft/level/chunk/Chunk;", at = @At("RETURN"))
    private void qs$queueRelight(int x, int z, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.level.chunk.Chunk> cir) {
        if (dev.quicksilver.Quicksilver.isClientThread() && cir.getReturnValue() != field_1226) dev.quicksilver.world.Relighter.onChunkLoaded(level, x, z);
    }


    @Shadow public abstract net.minecraft.level.chunk.Chunk loadChunk(int x, int z);

    /**
     * Decoration is thousands of block accesses in four chunks; keep them resolved while it runs. While the chunk
     * loader is running it is handed to a background thread instead (see AsyncDecoration), which cancels this call.
     */
    @Inject(method = "decorate(Lnet/minecraft/level/source/LevelSource;II)V", at = @At("HEAD"), cancellable = true)
    private void qs$decorateStart(net.minecraft.level.source.LevelSource source, int x, int z, CallbackInfo ci) {
        if (dev.quicksilver.world.AsyncDecoration.intercept((net.minecraft.level.chunk.ServerChunkCache) (Object) this, level, x, z)) {
            qs$skipEnd = true;
            ci.cancel();
            return;
        }
        dev.quicksilver.world.DecorationWindow.open(level);
        dev.quicksilver.client.PerfLog.decorateStart();
    }

    @Inject(method = "decorate(Lnet/minecraft/level/source/LevelSource;II)V", at = @At("RETURN"))
    private void qs$decorateEnd(net.minecraft.level.source.LevelSource source, int x, int z, CallbackInfo ci) {
        if (qs$skipEnd) {
            qs$skipEnd = false;
            return;
        }
        dev.quicksilver.world.DecorationWindow.close();
        dev.quicksilver.client.PerfLog.decorateEnd();
    }

    @org.spongepowered.asm.mixin.Unique private boolean qs$skipEnd;

    @Override
    public net.minecraft.level.chunk.Chunk qs$peek(int x, int z) {
        return qs$lookup(x, z);
    }

    /**
     * @author Quicksilver
     * @reason a one-entry cache in front of the chunk map (every block access goes through here)
     */
    @org.spongepowered.asm.mixin.Overwrite
    public net.minecraft.level.chunk.Chunk getChunk(int x, int z) {
        if (!dev.quicksilver.Quicksilver.isClientThread()) {
            net.minecraft.level.chunk.Chunk o = qs$lookup(x, z);
            return o != null ? o : field_1226;
        }
        // small direct-mapped cache: features like ore veins hop between up to 4 chunks block by block
        int slot = (x * 3 + z) & 7;
        net.minecraft.level.chunk.Chunk c = qs$cache[slot];
        if (c != null && c.x == x && c.z == z) return c;
        c = qs$lookup(x, z);
        if (c == null) c = loadChunk(x, z);
        if (c != null && c.x == x && c.z == z && c != field_1226) qs$cache[slot] = c;
        return c;
    }

    @org.spongepowered.asm.mixin.Unique private final net.minecraft.level.chunk.Chunk[] qs$cache = new net.minecraft.level.chunk.Chunk[8];


    @org.spongepowered.asm.mixin.Unique
    private net.minecraft.level.chunk.Chunk qs$lookup(int x, int z) {
        int key = net.minecraft.util.maths.Vec2i.hash(x, z);
        Map m = serverChunkCache;
        if (m instanceof FastChunkMap f) return (net.minecraft.level.chunk.Chunk) f.getInt(key);
        return (net.minecraft.level.chunk.Chunk) m.get(key);
    }

    /**
     * @author Quicksilver
     * @reason boxing-free lookup with a small cache (lighting calls this ~8 times per block)
     */
    @org.spongepowered.asm.mixin.Overwrite
    public boolean isChunkLoaded(int x, int z) {
        net.minecraft.level.chunk.Chunk c = qs$cache[(x * 3 + z) & 7];
        if (c != null && c.x == x && c.z == z) return true;
        return qs$lookup(x, z) != null;
    }

    @Inject(method = "method_1801()Z", at = @At("HEAD"))
    private void qs$invalidateOnDrop(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        java.util.Arrays.fill(qs$cache, null);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void qs$fastMap(CallbackInfo ci) {
        if (serverChunkCache != null && serverChunkCache.getClass() == HashMap.class) {
            FastChunkMap m = new FastChunkMap();
            m.putAll(serverChunkCache);
            serverChunkCache = m;
        }
    }

    @Override
    public void qs$invalidateLast() {
        java.util.Arrays.fill(qs$cache, null);
    }
}
