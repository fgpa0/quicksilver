package dev.quicksilver.shader.pack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * block.properties / item.properties / entity.properties: the ids shaders see in mc_Entity.x, blockEntityId,
 * heldItemId and entityId.
 */
public final class IdMap {
    /** One "name[:prop=v1,v2...]" or numeric "id[:meta...]" entry. */
    private record Entry(int mapped, String name, int numericId, int[] metas, Map<String, String[]> props) {}

    private final List<Entry> blockEntries = new ArrayList<>();
    private final Map<String, Integer> itemNames = new HashMap<>();
    private final Map<Integer, Integer> itemIds = new HashMap<>();
    private final Map<String, Integer> entityNames = new HashMap<>();
    public boolean hasBlocks;

    /** id*16+meta -> mapped id; [1] = with snow above (grass/dirt "snowy") */
    private final int[][] blockTable = new int[2][256 * 16];

    public static IdMap load(PropertiesFile blocks, PropertiesFile items, PropertiesFile entities) {
        IdMap m = new IdMap();
        if (blocks != null) {
            for (Map.Entry<String, String> e : blocks.map.entrySet()) {
                if (!e.getKey().startsWith("block.")) continue;
                int mapped = parseInt(e.getKey().substring(6));
                if (mapped == Integer.MIN_VALUE) continue;
                for (String tok : e.getValue().trim().split("\\s+")) if (!tok.isEmpty()) m.blockEntries.add(parseEntry(mapped, tok));
                m.hasBlocks = true;
            }
        }
        if (items != null) {
            for (Map.Entry<String, String> e : items.map.entrySet()) {
                if (!e.getKey().startsWith("item.")) continue;
                int mapped = parseInt(e.getKey().substring(5));
                if (mapped == Integer.MIN_VALUE) continue;
                for (String tok : e.getValue().trim().split("\\s+")) {
                    if (tok.isEmpty()) continue;
                    String n = stripNamespace(tok.split(":")[0].equals("minecraft") ? tok : tok);
                    int num = parseInt(n);
                    if (num != Integer.MIN_VALUE) m.itemIds.putIfAbsent(num, mapped);
                    else m.itemNames.putIfAbsent(n, mapped);
                }
            }
        }
        if (entities != null) {
            for (Map.Entry<String, String> e : entities.map.entrySet()) {
                if (!e.getKey().startsWith("entity.")) continue;
                int mapped = parseInt(e.getKey().substring(7));
                if (mapped == Integer.MIN_VALUE) continue;
                for (String tok : e.getValue().trim().split("\\s+")) if (!tok.isEmpty()) m.entityNames.putIfAbsent(stripNamespace(tok), mapped);
            }
        }
        m.buildBlockTable();
        return m;
    }

    private static String stripNamespace(String s) {
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static Entry parseEntry(int mapped, String tok) {
        tok = stripNamespace(tok);
        String[] parts = tok.split(":");
        String name = parts[0];
        int numeric = parseInt(name);
        List<Integer> metas = new ArrayList<>();
        Map<String, String[]> props = new HashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            int eq = p.indexOf('=');
            if (eq > 0) props.put(p.substring(0, eq), p.substring(eq + 1).split(","));
            else {
                for (String mv : p.split(",")) {
                    if (mv.contains("-")) {
                        String[] r = mv.split("-");
                        int a = parseInt(r[0]), b = parseInt(r[1]);
                        if (a != Integer.MIN_VALUE && b != Integer.MIN_VALUE) for (int k = a; k <= b; k++) metas.add(k);
                    } else {
                        int v = parseInt(mv);
                        if (v != Integer.MIN_VALUE) metas.add(v);
                    }
                }
            }
        }
        return new Entry(mapped, numeric == Integer.MIN_VALUE ? name : null, numeric, metas.isEmpty() ? null : metas.stream().mapToInt(Integer::intValue).toArray(), props);
    }

    /** Properties a block doesn't have match only their "default" values (for example waterlogged=false). */
    private static boolean defaultValue(String v) {
        return v.equals("false") || v.equals("0") || v.equals("none") || v.equals("lower") || v.equals("bottom") || v.equals("normal");
    }

    private static boolean matches(Entry e, int id, int meta, List<BetaNames.Name> names) {
        if (e.name == null) {
            if (e.numericId != id) return false;
            if (e.metas != null && Arrays.stream(e.metas).noneMatch(x -> x == meta)) return false;
            return e.props.isEmpty();
        }
        for (BetaNames.Name n : names) {
            if (!n.name().equals(e.name)) continue;
            if (e.metas != null && Arrays.stream(e.metas).noneMatch(x -> x == meta)) continue;
            boolean ok = true;
            for (Map.Entry<String, String[]> pe : e.props.entrySet()) {
                String have = n.props().get(pe.getKey());
                boolean any = false;
                for (String want : pe.getValue()) {
                    if (have != null ? have.equals(want) : defaultValue(want)) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    ok = false;
                    break;
                }
            }
            if (ok) return true;
        }
        return false;
    }

    private void buildBlockTable() {
        for (int snow = 0; snow < 2; snow++) {
            int[] t = blockTable[snow];
            for (int id = 0; id < 256; id++) {
                for (int meta = 0; meta < 16; meta++) {
                    int v = hasBlocks ? 0 : id; // no block.properties: raw ids like 1.7.10 OptiFine
                    if (hasBlocks && id != 0) {
                        List<BetaNames.Name> names = BetaNames.block(id, meta, snow == 1);
                        for (Entry e : blockEntries) {
                            if (matches(e, id, meta, names)) {
                                v = e.mapped;
                                break;
                            }
                        }
                    }
                    t[id << 4 | meta] = v;
                }
            }
        }
    }

    /** mc_Entity.x of a block. */
    public int block(int id, int meta, boolean snowAbove) {
        return blockTable[snowAbove ? 1 : 0][(id & 255) << 4 | (meta & 15)];
    }

    public int[] blockTable(boolean snowAbove) {
        return blockTable[snowAbove ? 1 : 0];
    }

    /** heldItemId of an item or block item id (-1: nothing held). */
    public int item(int id, int meta) {
        if (id <= 0) return -1;
        Integer n = itemIds.get(id);
        if (n != null) return n;
        if (id < 256) {
            if (!hasBlocks && itemNames.isEmpty()) return id;
            for (BetaNames.Name name : BetaNames.block(id, meta, false)) {
                Integer v = itemNames.get(name.name());
                if (v != null) return v;
            }
            return itemNames.isEmpty() ? id : 0;
        }
        String[] names = BetaNames.ITEMS.get(id);
        if (names != null) for (String s : names) {
            Integer v = itemNames.get(s);
            if (v != null) return v;
        }
        return itemNames.isEmpty() ? id : 0;
    }

    /** entityId for an entity, by its pack names. */
    public int entity(String[] names) {
        if (names == null) return 0;
        for (String s : names) {
            Integer v = entityNames.get(s);
            if (v != null) return v;
        }
        return 0;
    }
}
