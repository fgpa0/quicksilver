package dev.quicksilver.world;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import net.minecraft.util.maths.Vec2i;

/**
 * Open-addressing hash map for chunk caches. Vanilla keys chunks by {@code Vec2i.hash(x, z)} (or Vec2i whose
 * hashCode is the same), which packs x into the high and z into the low 16 bits; java.util.HashMap folds those
 * halves together with XOR, so every chunk on the same "x ^ z" diagonal lands in the same bucket and lookups
 * degrade into tree searches. This map mixes the hash properly (murmur3 finalizer) and probes linearly.
 * <p>
 * Written only by the game thread. Other mods sometimes read chunks from their own threads (e.g. Entity Culling),
 * so keys and values live interleaved in one array that is swapped atomically on resize: a concurrent reader can
 * at worst miss an entry that is being moved, never see a mismatched key/value or an index out of bounds.
 */
public final class FastChunkMap extends AbstractMap<Object, Object> {
    /** key at 2i, value at 2i + 1 */
    private volatile Object[] table;
    private int size;
    private int resizeAt;

    public FastChunkMap() {
        table = new Object[2048];
        resizeAt = 512;
    }

    private static int rawHash(Object k) {
        if (k instanceof Integer i) return i;
        if (k instanceof Vec2i v) return Vec2i.hash(v.x, v.z);
        return k.hashCode();
    }

    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    private static boolean eq(Object a, Object b) {
        if (a == b) return true;
        if (a instanceof Integer x && b instanceof Integer y) return x.intValue() == y.intValue();
        if (a instanceof Vec2i x && b instanceof Vec2i y) return x.x == y.x && x.z == y.z;
        return a.equals(b);
    }

    /** @return slot index (even) or -1 */
    private static int find(Object[] t, Object key) {
        int mask = (t.length >> 1) - 1;
        int i = mix(rawHash(key)) & mask;
        for (int n = 0; n <= mask; n++) {
            Object k = t[i << 1];
            if (k == null) return -1;
            if (eq(k, key)) return i << 1;
            i = (i + 1) & mask;
        }
        return -1;
    }

    @Override
    public Object get(Object key) {
        if (key == null) return null;
        Object[] t = table;
        int s = find(t, key);
        return s < 0 ? null : t[s + 1];
    }

    /** Boxing-free lookup for maps keyed by Integer (Vec2i.hash). */
    public Object getInt(int key) {
        Object[] t = table;
        int mask = (t.length >> 1) - 1;
        int i = mix(key) & mask;
        for (int n = 0; n <= mask; n++) {
            Object k = t[i << 1];
            if (k == null) return null;
            if (k instanceof Integer v && v.intValue() == key) return t[(i << 1) + 1];
            i = (i + 1) & mask;
        }
        return null;
    }

    @Override
    public boolean containsKey(Object key) {
        return key != null && find(table, key) >= 0;
    }

    @Override
    public Object put(Object key, Object value) {
        Object[] t = table;
        int mask = (t.length >> 1) - 1;
        int i = mix(rawHash(key)) & mask;
        Object k;
        while ((k = t[i << 1]) != null) {
            if (eq(k, key)) {
                Object old = t[(i << 1) + 1];
                t[(i << 1) + 1] = value;
                return old;
            }
            i = (i + 1) & mask;
        }
        // value first, then key: a reader that sees the key also sees the value
        t[(i << 1) + 1] = value;
        t[i << 1] = key;
        if (++size > resizeAt) rehash(t.length);
        return null;
    }

    private void rehash(int newSlots) {
        Object[] old = table;
        Object[] t = new Object[newSlots * 2];
        int mask = newSlots - 1;
        for (int j = 0; j < old.length; j += 2) {
            Object k = old[j];
            if (k == null) continue;
            int i = mix(rawHash(k)) & mask;
            while (t[i << 1] != null) i = (i + 1) & mask;
            t[(i << 1) + 1] = old[j + 1];
            t[i << 1] = k;
        }
        resizeAt = newSlots / 2;
        table = t;
    }

    @Override
    public Object remove(Object key) {
        if (key == null) return null;
        Object[] t = table;
        int s = find(t, key);
        if (s < 0) return null;
        Object old = t[s + 1];
        removeAt(t, s >> 1);
        return old;
    }

    private void removeAt(Object[] t, int i) {
        size--;
        int mask = (t.length >> 1) - 1;
        int j = i;
        while (true) {
            j = (j + 1) & mask;
            Object k = t[j << 1];
            if (k == null) break;
            int home = mix(rawHash(k)) & mask;
            if (i <= j ? (home <= i || home > j) : (home <= i && home > j)) {
                t[(i << 1) + 1] = t[(j << 1) + 1];
                t[i << 1] = k;
                i = j;
            }
        }
        t[i << 1] = null;
        t[(i << 1) + 1] = null;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void clear() {
        table = new Object[table.length];
        size = 0;
    }

    @Override
    public Set<Entry<Object, Object>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Entry<Object, Object>> iterator() {
                // snapshot iterator: safe against modification, removal goes through the map
                Object[] t = table;
                Object[] ks = new Object[t.length >> 1];
                Object[] vs = new Object[t.length >> 1];
                int n = 0;
                for (int j = 0; j < t.length; j += 2) {
                    if (t[j] != null) {
                        ks[n] = t[j];
                        vs[n++] = t[j + 1];
                    }
                }
                final int count = n;
                return new Iterator<>() {
                    int idx;

                    @Override
                    public boolean hasNext() {
                        return idx < count;
                    }

                    @Override
                    public Entry<Object, Object> next() {
                        if (idx >= count) throw new NoSuchElementException();
                        Entry<Object, Object> e = new SimpleEntry<>(ks[idx], vs[idx]);
                        idx++;
                        return e;
                    }

                    @Override
                    public void remove() {
                        FastChunkMap.this.remove(ks[idx - 1]);
                    }
                };
            }

            @Override
            public int size() {
                return size;
            }
        };
    }
}
