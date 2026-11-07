package dev.quicksilver.mixin.io;

import java.io.File;
import net.minecraft.level.dimension.DimensionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DimensionFile.class)
public interface DimensionFileAccessor {
    @Accessor("parentFolder")
    File qs$folder();
}
