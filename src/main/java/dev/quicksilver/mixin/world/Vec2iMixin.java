package dev.quicksilver.mixin.world;

import net.minecraft.util.maths.Vec2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Vec2i.class)
public abstract class Vec2iMixin {
    @Shadow @Final public int x;
    @Shadow @Final public int z;

    /**
     * @author Quicksilver
     * @reason vanilla's hash collides for every chunk on the same x^z diagonal inside HashMap/HashSet
     */
    @Overwrite
    public int hashCode() {
        int h = x * 0x9E3779B9 + z;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return h;
    }
}
