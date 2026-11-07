package dev.quicksilver.mixin.world;

import java.util.List;
import java.util.Set;
import net.minecraft.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Level.class)
public interface LevelAccessor {
    @Accessor("dimensionData")
    net.minecraft.level.dimension.DimensionData qs$dimensionData();

    @Accessor("lightingUpdates")
    List<Object> qs$lightingUpdates();

    @Accessor("field_191")
    int qs$lightDepth();

    @Accessor("field_191")
    void qs$setLightDepth(int v);

    @Accessor("levelListeners")
    List<?> qs$listeners();

    @Accessor("field_194")
    Set<Object> qs$activeChunks();

    @Accessor("treeSet")
    java.util.TreeSet<Object> qs$tickTree();

    @Accessor("field_184")
    Set<Object> qs$tickSet();
}
