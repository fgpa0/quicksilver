package dev.quicksilver.mixin.world;

import dev.quicksilver.Quicksilver;
import java.util.List;
import net.minecraft.util.maths.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Box.class)
public abstract class BoxMixin {
    @Shadow private static List uselessWasteOfMemory;
    @Shadow private static int thisIsAlwaysZero;

    @Shadow public static Box create(double a, double b, double c, double d, double e, double f) {
        throw new AssertionError();
    }

    @Shadow public abstract Box method_99(double a, double b, double c, double d, double e, double f);

    /**
     * @author Quicksilver
     * @reason the per-frame box pool is not thread safe; background threads get fresh boxes
     */
    @Overwrite
    public static Box createButWasteMemory(double a, double b, double c, double d, double e, double f) {
        if (!Quicksilver.isClientThread()) return create(a, b, c, d, e, f);
        if (thisIsAlwaysZero >= uselessWasteOfMemory.size()) uselessWasteOfMemory.add(create(0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        return ((Box) uselessWasteOfMemory.get(thisIsAlwaysZero++)).method_99(a, b, c, d, e, f);
    }
}
