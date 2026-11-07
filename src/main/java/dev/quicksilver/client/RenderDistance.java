package dev.quicksilver.client;

import dev.quicksilver.QsConfig;
import net.minecraft.client.Minecraft;

/**
 * Render distance in chunks (2..48). Vanilla's 4-step setting (Far/Normal/Short/Tiny) is kept in sync as a
 * "bucket" because a few vanilla effects (sky, fog colour) look at it.
 */
public final class RenderDistance {
    private RenderDistance() {}

    public static final int MIN = 2, MAX = 48;

    public static int chunks() {
        return Math.max(MIN, Math.min(MAX, QsConfig.renderDistance));
    }

    public static int vanillaBucket(int chunks) {
        if (chunks >= 12) return 0;
        if (chunks >= 6) return 1;
        if (chunks >= 4) return 2;
        return 3;
    }

    public static void set(int chunks) {
        QsConfig.renderDistance = Math.max(MIN, Math.min(MAX, chunks));
        Minecraft mc = MinecraftHolder.get();
        if (mc != null && mc.options != null) mc.options.viewDistance = vanillaBucket(QsConfig.renderDistance);
    }

    /** Fog / far-plane distance in blocks; 256 at 12 chunks like vanilla Far. */
    public static float fogDistance() {
        int c = QsConfig.fogDistance > 0 ? QsConfig.fogDistance : chunks();
        return 256.0F * c / 12.0F;
    }

    public static final class MinecraftHolder {
        private static Minecraft mc;

        public static void set(Minecraft m) {
            mc = m;
        }

        public static Minecraft get() {
            return mc;
        }
    }
}
