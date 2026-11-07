package dev.quicksilver.world;

import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.level.Level;
import net.minecraft.level.LightType;

/** Keeps every read and write of a level's vanilla lighting queue on the thread that owns that level. */
public final class DeferredLighting {
    private volatile Thread owner = Thread.currentThread();
    private final ConcurrentLinkedQueue<Update> pending = new ConcurrentLinkedQueue<>();

    private record Update(LightType type, int x0, int y0, int z0, int x1, int y1, int z1, boolean merge) {}

    public void adopt(Thread t) {
        owner = t;
    }

    public boolean isOwnerThread() {
        return Thread.currentThread() == owner;
    }

    public void enqueue(LightType type, int x0, int y0, int z0, int x1, int y1, int z1, boolean merge) {
        pending.add(new Update(type, x0, y0, z0, x1, y1, z1, merge));
    }

    /** Replay through vanilla so dimension, loaded-chunk and area-merging checks still apply. */
    public void drain(Level level, int max) {
        if (!isOwnerThread()) return;
        for (int i = 0; i < max; i++) {
            Update u = pending.poll();
            if (u == null) break;
            level.method_167(u.type, u.x0, u.y0, u.z0, u.x1, u.y1, u.z1, u.merge);
        }
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }
}
