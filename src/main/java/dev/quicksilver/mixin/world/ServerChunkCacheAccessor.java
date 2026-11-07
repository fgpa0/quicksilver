package dev.quicksilver.mixin.world;

import java.util.List;
import java.util.Map;
import net.minecraft.level.chunk.Chunk;
import net.minecraft.level.chunk.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {
    @Accessor("serverChunkCache")
    Map<Object, Object> qs$map();

    @Accessor("field_1230")
    List<Chunk> qs$loaded();

    @Accessor("levelSource")
    net.minecraft.level.source.LevelSource qs$generator();

    @Invoker("method_1050")
    void qs$save(Chunk chunk);

    @Invoker("method_1049")
    void qs$saveExtra(Chunk chunk);
}
