package dev.quicksilver.render;

import dev.quicksilver.Quicksilver;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.minecraft.block.BlockBase;
import net.minecraft.level.BlockView;

/**
 * Decides which blocks may be meshed on worker threads. Vanilla blocks mutate their shared bounding box while
 * rendering if they override updateBoundingBox or use a renderer that calls setBoundingBox; those (and all modded
 * blocks) are rendered on the main thread instead.
 */
public final class BlockSafety {
    private BlockSafety() {}

    private static boolean[] safe;
    private static BlockBase[] snapshotOf;
    /** Render types whose vanilla renderer never touches the block's bounds. */
    private static final boolean[] SAFE_TYPES = new boolean[32];

    static {
        for (int t : new int[] {0, 1, 2, 3, 4, 5, 6, 8, 9, 13, 14, 15}) SAFE_TYPES[t] = true;
    }

    public static boolean[] table() {
        BlockBase[] byId = BlockBase.BY_ID;
        if (safe == null || snapshotOf == null || !sameContents(byId)) compute(byId);
        return safe;
    }

    private static boolean sameContents(BlockBase[] byId) {
        if (snapshotOf.length != byId.length) return false;
        for (int i = 0; i < byId.length; i++) if (snapshotOf[i] != byId[i]) return false;
        return true;
    }

    private static void compute(BlockBase[] byId) {
        String boundsName;
        try {
            MappingResolver r = FabricLoader.getInstance().getMappingResolver();
            boundsName = r.mapMethodName("intermediary", "net.minecraft.class_17", "method_1616", "(Lnet/minecraft/class_14;III)V");
        } catch (Throwable t) {
            boundsName = "method_1616";
        }
        boolean[] s = new boolean[byId.length];
        int unsafe = 0;
        for (int id = 1; id < byId.length; id++) {
            BlockBase b = byId[id];
            if (b == null) continue;
            boolean ok;
            try {
                int type = b.getRenderType();
                ok = type >= 0 && type < SAFE_TYPES.length && SAFE_TYPES[type]
                        && b.getClass().getName().startsWith("net.minecraft.")
                        && !overrides(b.getClass(), boundsName);
            } catch (Throwable t) {
                ok = false;
            }
            s[id] = ok;
            if (!ok) unsafe++;
        }
        safe = s;
        snapshotOf = byId.clone();
        Quicksilver.LOGGER.info("Threaded meshing: {} block types rendered on the main thread", unsafe);
    }

    private static boolean overrides(Class<?> c, String name) {
        while (c != null && c != BlockBase.class) {
            try {
                Method m = c.getDeclaredMethod(name, BlockView.class, int.class, int.class, int.class);
                if (m != null) return true;
            } catch (NoSuchMethodException ignored) {
            }
            c = c.getSuperclass();
        }
        return false;
    }
}
