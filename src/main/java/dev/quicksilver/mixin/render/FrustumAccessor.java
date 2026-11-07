package dev.quicksilver.mixin.render;

import net.minecraft.class_573;
import net.minecraft.class_84;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(class_573.class)
public interface FrustumAccessor {
    @Accessor("field_2465")
    class_84 qs$data();

    @Accessor("field_2466")
    double qs$x();

    @Accessor("field_2467")
    double qs$y();

    @Accessor("field_2468")
    double qs$z();
}
