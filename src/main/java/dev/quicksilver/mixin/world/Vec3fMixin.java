package dev.quicksilver.mixin.world;

import dev.quicksilver.Quicksilver;
import java.util.List;
import net.minecraft.util.maths.Vec3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Vec3f.class)
public abstract class Vec3fMixin {
    @Shadow private static List field_1588;
    @Shadow private static int field_1589;

    @Shadow public static Vec3f method_1293(double x, double y, double z) {
        throw new AssertionError();
    }

    @Shadow protected abstract Vec3f method_1304(double x, double y, double z);

    /**
     * @author Quicksilver
     * @reason the per-frame vector pool is not thread safe; background threads get fresh vectors
     */
    @Overwrite
    public static Vec3f from(double x, double y, double z) {
        if (!Quicksilver.isClientThread()) return method_1293(x, y, z);
        if (field_1589 >= field_1588.size()) field_1588.add(method_1293(0.0, 0.0, 0.0));
        return ((Vec3fMixin) field_1588.get(field_1589++)).method_1304(x, y, z);
    }
}
