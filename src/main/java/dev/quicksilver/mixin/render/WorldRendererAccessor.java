package dev.quicksilver.mixin.render;

import java.util.List;
import net.minecraft.class_66;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldRenderer.class)
public interface WorldRendererAccessor {
    @Accessor("field_1807")
    List<class_66> qs$dirty();

    @Accessor("field_1809")
    class_66[] qs$sections();

    @Accessor("field_1808")
    class_66[] qs$sortedSections();

    @Accessor("field_1810")
    int qs$sizeX();

    @Accessor("field_1811")
    int qs$sizeY();

    @Accessor("field_1812")
    int qs$sizeZ();

    @Accessor("level")
    Level qs$level();

    @Accessor("client")
    Minecraft qs$client();
}
