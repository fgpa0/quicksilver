package dev.quicksilver.shader.render;

import dev.quicksilver.shader.pack.BetaNames;
import dev.quicksilver.shader.pipeline.Pipeline;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.entity.EntityBase;
import net.minecraft.entity.EntityRegistry;

/** entityId uniform values (entity.properties) per entity class. */
final class EntityIds {
    private EntityIds() {}

    private static final Map<Class<?>, Integer> CACHE = new IdentityHashMap<>();
    private static Pipeline cachedFor;

    static int of(EntityBase e, Pipeline p) {
        if (cachedFor != p) {
            CACHE.clear();
            cachedFor = p;
        }
        Integer v = CACHE.get(e.getClass());
        if (v != null) return v;
        String[] names;
        if (e instanceof net.minecraft.entity.player.PlayerBase) names = new String[]{"player"};
        else if (e instanceof net.minecraft.entity.Lightning) names = new String[]{"lightning_bolt"};
        else {
            String id = EntityRegistry.getStringId(e);
            names = id == null ? null : BetaNames.ENTITIES.get(id);
            if (names == null && id != null) names = new String[]{id.toLowerCase()};
        }
        int r = p.pack.ids.entity(names);
        CACHE.put(e.getClass(), r);
        return r;
    }
}
