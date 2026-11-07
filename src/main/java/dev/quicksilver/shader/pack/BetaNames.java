package dev.quicksilver.shader.pack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Names of Beta 1.7.3 blocks, items and entities as shader packs know them: the 1.7-1.12 ("legacy") registry names
 * with 1.8-1.12 block state properties, and the 1.13+ flattened names. Block and item ids are the same as in 1.7.10.
 */
public final class BetaNames {
    private BetaNames() {}

    /** One name a block state can be matched by. */
    public record Name(String name, Map<String, String> props) {}

    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "silver", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle"};

    private static Map<String, String> p(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static String wood(int meta) {
        return WOODS[Math.min(meta & 3, 3)];
    }

    /** All names (legacy and modern) of block id:meta. snowAbove: a snow layer/block is directly above. */
    public static List<Name> block(int id, int meta, boolean snowAbove) {
        List<Name> n = new ArrayList<>();
        String snowy = snowAbove ? "true" : "false";
        switch (id) {
            case 1 -> { n.add(new Name("stone", p("variant", "stone"))); }
            case 2 -> { n.add(new Name("grass", p("snowy", snowy))); n.add(new Name("grass_block", p("snowy", snowy))); }
            case 3 -> { n.add(new Name("dirt", p("variant", "dirt", "snowy", snowy))); }
            case 4 -> n.add(new Name("cobblestone", p()));
            case 5 -> { n.add(new Name("planks", p("variant", "oak"))); n.add(new Name("oak_planks", p())); }
            case 6 -> { n.add(new Name("sapling", p("type", wood(meta), "stage", "0"))); n.add(new Name(wood(meta) + "_sapling", p("stage", "0"))); }
            case 7 -> n.add(new Name("bedrock", p()));
            case 8 -> { n.add(new Name("flowing_water", p("level", "" + meta))); n.add(new Name("water", p("level", "" + meta))); }
            case 9 -> n.add(new Name("water", p("level", "" + meta)));
            case 10 -> { n.add(new Name("flowing_lava", p("level", "" + meta))); n.add(new Name("lava", p("level", "" + meta))); }
            case 11 -> n.add(new Name("lava", p("level", "" + meta)));
            case 12 -> n.add(new Name("sand", p("variant", "sand")));
            case 13 -> n.add(new Name("gravel", p()));
            case 14 -> n.add(new Name("gold_ore", p()));
            case 15 -> n.add(new Name("iron_ore", p()));
            case 16 -> n.add(new Name("coal_ore", p()));
            case 17 -> { n.add(new Name("log", p("variant", wood(meta), "axis", "y"))); n.add(new Name(wood(meta) + "_log", p("axis", "y"))); }
            case 18 -> {
                n.add(new Name("leaves", p("variant", wood(meta), "decayable", "true", "check_decay", "false", "waterlogged", "false", "persistent", "false")));
                n.add(new Name(wood(meta) + "_leaves", p("waterlogged", "false", "persistent", "false")));
            }
            case 19 -> n.add(new Name("sponge", p("wet", "false")));
            case 20 -> n.add(new Name("glass", p()));
            case 21 -> n.add(new Name("lapis_ore", p()));
            case 22 -> n.add(new Name("lapis_block", p()));
            case 23 -> n.add(new Name("dispenser", p()));
            case 24 -> n.add(new Name("sandstone", p("type", "sandstone")));
            case 25 -> { n.add(new Name("noteblock", p())); n.add(new Name("note_block", p())); }
            case 26 -> { String half = (meta & 8) != 0 ? "head" : "foot"; n.add(new Name("bed", p("part", half))); n.add(new Name("red_bed", p("part", half))); }
            case 27 -> { n.add(new Name("golden_rail", p())); n.add(new Name("powered_rail", p())); }
            case 28 -> n.add(new Name("detector_rail", p()));
            case 29 -> n.add(new Name("sticky_piston", p("extended", (meta & 8) != 0 ? "true" : "false")));
            case 30 -> { n.add(new Name("web", p())); n.add(new Name("cobweb", p())); }
            case 31 -> {
                String t = meta == 0 ? "dead_bush" : meta == 2 ? "fern" : "tall_grass";
                n.add(new Name("tallgrass", p("type", t)));
                n.add(new Name(meta == 0 ? "dead_bush" : meta == 2 ? "fern" : "short_grass", p()));
                if (meta == 1) n.add(new Name("grass", p()));
            }
            case 32 -> { n.add(new Name("deadbush", p())); n.add(new Name("dead_bush", p())); }
            case 33 -> n.add(new Name("piston", p("extended", (meta & 8) != 0 ? "true" : "false")));
            case 34 -> n.add(new Name("piston_head", p("type", (meta & 8) != 0 ? "sticky" : "normal")));
            case 35 -> { n.add(new Name("wool", p("color", COLORS[meta & 15]))); n.add(new Name(COLORS[meta & 15].replace("silver", "light_gray") + "_wool", p())); }
            case 36 -> { n.add(new Name("piston_extension", p())); n.add(new Name("moving_piston", p())); }
            case 37 -> { n.add(new Name("yellow_flower", p("type", "dandelion"))); n.add(new Name("dandelion", p())); }
            case 38 -> { n.add(new Name("red_flower", p("type", "poppy"))); n.add(new Name("poppy", p())); }
            case 39 -> n.add(new Name("brown_mushroom", p()));
            case 40 -> n.add(new Name("red_mushroom", p()));
            case 41 -> n.add(new Name("gold_block", p()));
            case 42 -> n.add(new Name("iron_block", p()));
            case 43, 44 -> {
                String v = switch (meta & 7) { case 1 -> "sand"; case 2 -> "wood"; case 3 -> "cobblestone"; default -> "stone"; };
                String modern = switch (meta & 7) { case 1 -> "sandstone_slab"; case 2 -> "petrified_oak_slab"; case 3 -> "cobblestone_slab"; default -> "smooth_stone_slab"; };
                if (id == 43) {
                    n.add(new Name("double_stone_slab", p("variant", v, "seamless", "false")));
                    n.add(new Name(modern, p("type", "double", "waterlogged", "false")));
                } else {
                    n.add(new Name("stone_slab", p("variant", v, "half", "bottom")));
                    n.add(new Name(modern, p("type", "bottom", "waterlogged", "false")));
                }
            }
            case 45 -> { n.add(new Name("brick_block", p())); n.add(new Name("bricks", p())); }
            case 46 -> n.add(new Name("tnt", p("explode", "false")));
            case 47 -> n.add(new Name("bookshelf", p()));
            case 48 -> n.add(new Name("mossy_cobblestone", p()));
            case 49 -> n.add(new Name("obsidian", p()));
            case 50 -> { n.add(new Name("torch", p())); n.add(new Name(meta >= 1 && meta <= 4 ? "wall_torch" : "torch", p())); }
            case 51 -> n.add(new Name("fire", p("age", "" + meta)));
            case 52 -> { n.add(new Name("mob_spawner", p())); n.add(new Name("spawner", p())); }
            case 53 -> n.add(new Name("oak_stairs", p("waterlogged", "false")));
            case 54 -> n.add(new Name("chest", p("waterlogged", "false")));
            case 55 -> n.add(new Name("redstone_wire", p("power", "" + meta)));
            case 56 -> n.add(new Name("diamond_ore", p()));
            case 57 -> n.add(new Name("diamond_block", p()));
            case 58 -> n.add(new Name("crafting_table", p()));
            case 59 -> n.add(new Name("wheat", p("age", "" + meta)));
            case 60 -> n.add(new Name("farmland", p("moisture", "" + meta)));
            case 61 -> n.add(new Name("furnace", p("lit", "false")));
            case 62 -> { n.add(new Name("lit_furnace", p())); n.add(new Name("furnace", p("lit", "true"))); }
            case 63 -> { n.add(new Name("standing_sign", p())); n.add(new Name("oak_sign", p())); }
            case 64 -> { String h = (meta & 8) != 0 ? "upper" : "lower"; n.add(new Name("wooden_door", p("half", h))); n.add(new Name("oak_door", p("half", h))); }
            case 65 -> n.add(new Name("ladder", p("waterlogged", "false")));
            case 66 -> n.add(new Name("rail", p()));
            case 67 -> { n.add(new Name("stone_stairs", p())); n.add(new Name("cobblestone_stairs", p("waterlogged", "false"))); }
            case 68 -> { n.add(new Name("wall_sign", p())); n.add(new Name("oak_wall_sign", p())); }
            case 69 -> n.add(new Name("lever", p("powered", (meta & 8) != 0 ? "true" : "false")));
            case 70 -> n.add(new Name("stone_pressure_plate", p("powered", meta != 0 ? "true" : "false")));
            case 71 -> n.add(new Name("iron_door", p("half", (meta & 8) != 0 ? "upper" : "lower")));
            case 72 -> { n.add(new Name("wooden_pressure_plate", p("powered", meta != 0 ? "true" : "false"))); n.add(new Name("oak_pressure_plate", p())); }
            case 73 -> n.add(new Name("redstone_ore", p("lit", "false")));
            case 74 -> { n.add(new Name("lit_redstone_ore", p())); n.add(new Name("redstone_ore", p("lit", "true"))); }
            case 75 -> { n.add(new Name("unlit_redstone_torch", p())); n.add(new Name(meta >= 1 && meta <= 4 ? "redstone_wall_torch" : "redstone_torch", p("lit", "false"))); }
            case 76 -> n.add(new Name(meta >= 1 && meta <= 4 ? "redstone_wall_torch" : "redstone_torch", p("lit", "true")));
            case 77 -> n.add(new Name("stone_button", p()));
            case 78 -> { n.add(new Name("snow_layer", p("layers", "" + ((meta & 7) + 1)))); n.add(new Name("snow", p("layers", "" + ((meta & 7) + 1)))); }
            case 79 -> n.add(new Name("ice", p()));
            case 80 -> { n.add(new Name("snow", p())); n.add(new Name("snow_block", p())); }
            case 81 -> n.add(new Name("cactus", p("age", "" + meta)));
            case 82 -> n.add(new Name("clay", p()));
            case 83 -> { n.add(new Name("reeds", p("age", "" + meta))); n.add(new Name("sugar_cane", p())); }
            case 84 -> n.add(new Name("jukebox", p()));
            case 85 -> { n.add(new Name("fence", p())); n.add(new Name("oak_fence", p("waterlogged", "false"))); }
            case 86 -> { n.add(new Name("pumpkin", p())); n.add(new Name("carved_pumpkin", p())); }
            case 87 -> n.add(new Name("netherrack", p()));
            case 88 -> n.add(new Name("soul_sand", p()));
            case 89 -> n.add(new Name("glowstone", p()));
            case 90 -> { n.add(new Name("portal", p())); n.add(new Name("nether_portal", p())); }
            case 91 -> { n.add(new Name("lit_pumpkin", p())); n.add(new Name("jack_o_lantern", p())); }
            case 92 -> n.add(new Name("cake", p("bites", "" + meta)));
            case 93 -> { n.add(new Name("unpowered_repeater", p())); n.add(new Name("repeater", p("powered", "false"))); }
            case 94 -> { n.add(new Name("powered_repeater", p())); n.add(new Name("repeater", p("powered", "true"))); }
            case 95 -> n.add(new Name("chest", p()));
            case 96 -> { n.add(new Name("trapdoor", p("open", (meta & 4) != 0 ? "true" : "false"))); n.add(new Name("oak_trapdoor", p("open", (meta & 4) != 0 ? "true" : "false"))); }
            default -> {
            }
        }
        return n;
    }

    /** Ids of Beta items (256+), 1.7-1.12 / 1.13+ names. */
    public static final Map<Integer, String[]> ITEMS = new HashMap<>();

    private static void item(int id, String... names) {
        ITEMS.put(id, names);
    }

    static {
        item(256, "iron_shovel"); item(257, "iron_pickaxe"); item(258, "iron_axe"); item(259, "flint_and_steel"); item(260, "apple");
        item(261, "bow"); item(262, "arrow"); item(263, "coal"); item(264, "diamond"); item(265, "iron_ingot"); item(266, "gold_ingot");
        item(267, "iron_sword"); item(268, "wooden_sword"); item(269, "wooden_shovel"); item(270, "wooden_pickaxe"); item(271, "wooden_axe");
        item(272, "stone_sword"); item(273, "stone_shovel"); item(274, "stone_pickaxe"); item(275, "stone_axe"); item(276, "diamond_sword");
        item(277, "diamond_shovel"); item(278, "diamond_pickaxe"); item(279, "diamond_axe"); item(280, "stick"); item(281, "bowl");
        item(282, "mushroom_stew"); item(283, "golden_sword"); item(284, "golden_shovel"); item(285, "golden_pickaxe"); item(286, "golden_axe");
        item(287, "string"); item(288, "feather"); item(289, "gunpowder"); item(290, "wooden_hoe"); item(291, "stone_hoe"); item(292, "iron_hoe");
        item(293, "diamond_hoe"); item(294, "golden_hoe"); item(295, "wheat_seeds"); item(296, "wheat"); item(297, "bread");
        item(298, "leather_helmet"); item(299, "leather_chestplate"); item(300, "leather_leggings"); item(301, "leather_boots");
        item(302, "chainmail_helmet"); item(303, "chainmail_chestplate"); item(304, "chainmail_leggings"); item(305, "chainmail_boots");
        item(306, "iron_helmet"); item(307, "iron_chestplate"); item(308, "iron_leggings"); item(309, "iron_boots");
        item(310, "diamond_helmet"); item(311, "diamond_chestplate"); item(312, "diamond_leggings"); item(313, "diamond_boots");
        item(314, "golden_helmet"); item(315, "golden_chestplate"); item(316, "golden_leggings"); item(317, "golden_boots");
        item(318, "flint"); item(319, "porkchop"); item(320, "cooked_porkchop"); item(321, "painting"); item(322, "golden_apple");
        item(323, "sign", "oak_sign"); item(324, "wooden_door", "oak_door"); item(325, "bucket"); item(326, "water_bucket"); item(327, "lava_bucket");
        item(328, "minecart"); item(329, "saddle"); item(330, "iron_door"); item(331, "redstone"); item(332, "snowball"); item(333, "boat", "oak_boat");
        item(334, "leather"); item(335, "milk_bucket"); item(336, "brick"); item(337, "clay_ball"); item(338, "reeds", "sugar_cane");
        item(339, "paper"); item(340, "book"); item(341, "slime_ball"); item(342, "chest_minecart"); item(343, "furnace_minecart");
        item(344, "egg"); item(345, "compass"); item(346, "fishing_rod"); item(347, "clock"); item(348, "glowstone_dust");
        item(349, "fish", "cod"); item(350, "cooked_fish", "cooked_cod"); item(351, "dye"); item(352, "bone"); item(353, "sugar");
        item(354, "cake"); item(355, "bed", "red_bed"); item(356, "repeater"); item(357, "cookie"); item(358, "filled_map");
        item(359, "shears"); item(2256, "record_13", "music_disc_13"); item(2257, "record_cat", "music_disc_cat");
    }

    /** Beta entity registry ids (EntityRegistry string ids) to shader pack names. */
    public static final Map<String, String[]> ENTITIES = new HashMap<>();

    static {
        ENTITIES.put("Arrow", new String[]{"arrow"});
        ENTITIES.put("Snowball", new String[]{"snowball"});
        ENTITIES.put("Item", new String[]{"item"});
        ENTITIES.put("Painting", new String[]{"painting"});
        ENTITIES.put("Creeper", new String[]{"creeper"});
        ENTITIES.put("Skeleton", new String[]{"skeleton"});
        ENTITIES.put("Spider", new String[]{"spider"});
        ENTITIES.put("Giant", new String[]{"giant"});
        ENTITIES.put("Zombie", new String[]{"zombie"});
        ENTITIES.put("Slime", new String[]{"slime"});
        ENTITIES.put("Ghast", new String[]{"ghast"});
        ENTITIES.put("PigZombie", new String[]{"zombie_pigman", "zombified_piglin"});
        ENTITIES.put("Pig", new String[]{"pig"});
        ENTITIES.put("Sheep", new String[]{"sheep"});
        ENTITIES.put("Cow", new String[]{"cow"});
        ENTITIES.put("Chicken", new String[]{"chicken"});
        ENTITIES.put("Squid", new String[]{"squid"});
        ENTITIES.put("Wolf", new String[]{"wolf"});
        ENTITIES.put("PrimedTnt", new String[]{"tnt"});
        ENTITIES.put("FallingSand", new String[]{"falling_block"});
        ENTITIES.put("Minecart", new String[]{"minecart"});
        ENTITIES.put("Boat", new String[]{"boat"});
        ENTITIES.put("Monster", new String[]{"monster"});
        ENTITIES.put("Mob", new String[]{"mob"});
    }
}
