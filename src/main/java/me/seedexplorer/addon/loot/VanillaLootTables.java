package me.seedexplorer.addon.loot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VanillaLootTables {
    private static final Map<String, LootTableDef> TABLES = new HashMap<>();

    static {
        register("minecraft:chests/simple_dungeon",
            "Simple Dungeon",
            List.of(
                new LootPool(1, 3, List.of(
                    ItemLoot.of("minecraft:saddle", 1, 1, 10),
                    ItemLoot.of("minecraft:golden_apple", 1, 1, 10),
                    ItemLoot.of("minecraft:enchanted_golden_apple", 1, 1, 2),
                    ItemLoot.of("minecraft:music_disc_cat", 1, 1, 2),
                    ItemLoot.of("minecraft:music_disc_blocks", 1, 1, 2),
                    ItemLoot.of("minecraft:music_disc_chirp", 1, 1, 2),
                    ItemLoot.of("minecraft:music_disc_far", 1, 1, 2),
                    ItemLoot.of("minecraft:music_disc_mall", 1, 1, 2),
                    ItemLoot.of("minecraft:music_disc_mellohi", 1, 1, 2),
                    ItemLoot.of("minecraft:music_disc_stal", 1, 1, 2),
                    ItemLoot.of("minecraft:music_disc_strad", 1, 1, 2),
                    ItemLoot.of("minecraft:music_disc_ward", 1, 1, 2),
                    ItemLoot.of("minecraft:music_disc_11", 1, 1, 2),
                    ItemLoot.of("minecraft:music_disc_wait", 1, 1, 2),
                    ItemLoot.of("minecraft:name_tag", 1, 1, 10),
                    ItemLoot.of("minecraft:iron_horse_armor", 1, 1, 5),
                    ItemLoot.of("minecraft:golden_horse_armor", 1, 1, 3),
                    ItemLoot.of("minecraft:diamond_horse_armor", 1, 1, 1),
                    ItemLoot.of("minecraft:book", 1, 5, 10),
                    ItemLoot.of("minecraft:paper", 2, 8, 10),
                    ItemLoot.of("minecraft:empty_map", 1, 1, 2),
                    ItemLoot.of("minecraft:bucket", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_bars", 1, 4, 10),
                    ItemLoot.of("minecraft:iron_ingot", 1, 5, 5),
                    ItemLoot.of("minecraft:gold_ingot", 1, 3, 5),
                    ItemLoot.of("minecraft:bread", 1, 2, 10),
                    ItemLoot.of("minecraft:wheat", 1, 4, 10),
                    ItemLoot.of("minecraft:redstone", 1, 5, 5),
                    ItemLoot.of("minecraft:coal", 1, 4, 10),
                    ItemLoot.of("minecraft:rotten_flesh", 1, 4, 10),
                    ItemLoot.of("minecraft:bone", 1, 4, 10),
                    ItemLoot.of("minecraft:gunpowder", 1, 4, 10),
                    ItemLoot.of("minecraft:string", 1, 4, 10),
                    ItemLoot.of("minecraft:spider_eye", 1, 2, 5),
                    ItemLoot.of("minecraft:rail", 1, 8, 10),
                    ItemLoot.of("minecraft:powered_rail", 1, 4, 5),
                    ItemLoot.of("minecraft:detector_rail", 1, 4, 5),
                    ItemLoot.of("minecraft:activator_rail", 1, 4, 5)
                )),
                new LootPool(1, 4, List.of(
                    ItemLoot.of("minecraft:bone", 1, 8, 10),
                    ItemLoot.of("minecraft:gunpowder", 1, 8, 10),
                    ItemLoot.of("minecraft:rotten_flesh", 1, 8, 10),
                    ItemLoot.of("minecraft:string", 1, 8, 10),
                    ItemLoot.of("minecraft:coal", 1, 8, 10),
                    ItemLoot.of("minecraft:bread", 1, 8, 10),
                    ItemLoot.of("minecraft:wheat", 1, 8, 10),
                    ItemLoot.of("minecraft:redstone", 1, 8, 5),
                    ItemLoot.of("minecraft:iron_ingot", 1, 4, 5),
                    ItemLoot.of("minecraft:gold_ingot", 1, 4, 5)
                ))
            )
        );

        register("minecraft:chests/desert_pyramid",
            "Desert Pyramid",
            List.of(
                new LootPool(2, 4, List.of(
                    ItemLoot.of("minecraft:diamond", 1, 3, 5),
                    ItemLoot.of("minecraft:iron_ingot", 1, 5, 15),
                    ItemLoot.of("minecraft:gold_ingot", 2, 7, 15),
                    ItemLoot.of("minecraft:emerald", 1, 3, 15),
                    ItemLoot.of("minecraft:bone", 4, 6, 25),
                    ItemLoot.of("minecraft:spider_eye", 1, 3, 25),
                    ItemLoot.of("minecraft:rotten_flesh", 3, 7, 25),
                    ItemLoot.of("minecraft:leather", 1, 5, 20),
                    ItemLoot.of("minecraft:copper_horse_armor", 1, 1, 15),
                    ItemLoot.of("minecraft:iron_horse_armor", 1, 1, 15),
                    ItemLoot.of("minecraft:golden_horse_armor", 1, 1, 10),
                    ItemLoot.of("minecraft:diamond_horse_armor", 1, 1, 5),
                    ItemLoot.randomlyEnchantedBook(20),
                    ItemLoot.of("minecraft:golden_apple", 1, 1, 20),
                    ItemLoot.of("minecraft:enchanted_golden_apple", 1, 1, 2),
                    ItemLoot.empty(15)
                )),
                new LootPool(4, 4, List.of(
                    ItemLoot.of("minecraft:bone", 1, 8, 10),
                    ItemLoot.of("minecraft:gunpowder", 1, 8, 10),
                    ItemLoot.of("minecraft:rotten_flesh", 1, 8, 10),
                    ItemLoot.of("minecraft:string", 1, 8, 10),
                    ItemLoot.of("minecraft:sand", 1, 8, 10)
                )),
                new LootPool(1, 1, List.of(
                    ItemLoot.empty(6),
                    ItemLoot.of("minecraft:dune_armor_trim_smithing_template", 2, 2, 1)
                ))
            )
        );

        register("minecraft:chests/stronghold_corridor",
            "Stronghold Corridor",
            List.of(
                new LootPool(1, 3, List.of(
                    ItemLoot.of("minecraft:ender_pearl", 1, 1, 10),
                    ItemLoot.of("minecraft:iron_ingot", 1, 5, 15),
                    ItemLoot.of("minecraft:gold_ingot", 1, 3, 10),
                    ItemLoot.of("minecraft:redstone", 4, 9, 10),
                    ItemLoot.of("minecraft:bread", 1, 3, 15),
                    ItemLoot.of("minecraft:apple", 1, 3, 15),
                    ItemLoot.of("minecraft:iron_pickaxe", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_sword", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_chestplate", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_helmet", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_leggings", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_boots", 1, 1, 5),
                    ItemLoot.of("minecraft:golden_pickaxe", 1, 1, 3),
                    ItemLoot.of("minecraft:golden_sword", 1, 1, 3),
                    ItemLoot.of("minecraft:golden_chestplate", 1, 1, 3),
                    ItemLoot.of("minecraft:golden_helmet", 1, 1, 3),
                    ItemLoot.of("minecraft:golden_leggings", 1, 1, 3),
                    ItemLoot.of("minecraft:golden_boots", 1, 1, 3),
                    ItemLoot.of("minecraft:enchanted_book", 1, 1, 3)
                ))
            )
        );

        register("minecraft:chests/stronghold_library",
            "Stronghold Library",
            List.of(
                new LootPool(2, 5, List.of(
                    ItemLoot.of("minecraft:book", 1, 10, 50),
                    ItemLoot.of("minecraft:paper", 2, 10, 50),
                    ItemLoot.of("minecraft:empty_map", 1, 1, 10),
                    ItemLoot.of("minecraft:compass", 1, 1, 5),
                    ItemLoot.of("minecraft:map", 1, 1, 20)
                )),
                new LootPool(1, 2, List.of(
                    ItemLoot.of("minecraft:book", 1, 4, 20),
                    ItemLoot.of("minecraft:enchanted_book", 1, 1, 15),
                    ItemLoot.of("minecraft:paper", 1, 4, 20),
                    ItemLoot.of("minecraft:empty_map", 1, 1, 5),
                    ItemLoot.of("minecraft:compass", 1, 1, 5),
                    ItemLoot.of("minecraft:map", 1, 1, 5)
                ))
            )
        );

        register("minecraft:chests/stronghold_storeroom",
            "Stronghold Storeroom",
            List.of(
                new LootPool(3, 6, List.of(
                    ItemLoot.of("minecraft:iron_ingot", 1, 5, 15),
                    ItemLoot.of("minecraft:gold_ingot", 1, 3, 10),
                    ItemLoot.of("minecraft:redstone", 4, 9, 10),
                    ItemLoot.of("minecraft:coal", 3, 8, 15),
                    ItemLoot.of("minecraft:bread", 1, 3, 15),
                    ItemLoot.of("minecraft:apple", 1, 3, 15),
                    ItemLoot.of("minecraft:iron_pickaxe", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_sword", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_chestplate", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_helmet", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_leggings", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_boots", 1, 1, 5),
                    ItemLoot.of("minecraft:golden_pickaxe", 1, 1, 5),
                    ItemLoot.of("minecraft:golden_sword", 1, 1, 5),
                    ItemLoot.of("minecraft:golden_chestplate", 1, 1, 5),
                    ItemLoot.of("minecraft:golden_helmet", 1, 1, 5),
                    ItemLoot.of("minecraft:golden_leggings", 1, 1, 5),
                    ItemLoot.of("minecraft:golden_boots", 1, 1, 5),
                    ItemLoot.of("minecraft:diamond", 1, 3, 3),
                    ItemLoot.of("minecraft:emerald", 1, 3, 5),
                    ItemLoot.of("minecraft:iron_block", 1, 3, 3),
                    ItemLoot.of("minecraft:gold_block", 1, 3, 2),
                    ItemLoot.of("minecraft:iron_horse_armor", 1, 1, 2),
                    ItemLoot.of("minecraft:golden_horse_armor", 1, 1, 1),
                    ItemLoot.of("minecraft:diamond_horse_armor", 1, 1, 1),
                    ItemLoot.of("minecraft:enchanted_book", 1, 1, 3)
                ))
            )
        );

        register("minecraft:chests/stronghold_crossing",
            "Stronghold Crossing",
            List.of(
                new LootPool(1, 3, List.of(
                    ItemLoot.of("minecraft:iron_ingot", 1, 5, 15),
                    ItemLoot.of("minecraft:gold_ingot", 1, 3, 10),
                    ItemLoot.of("minecraft:redstone", 4, 9, 10),
                    ItemLoot.of("minecraft:coal", 3, 8, 15),
                    ItemLoot.of("minecraft:bread", 1, 3, 15),
                    ItemLoot.of("minecraft:apple", 1, 3, 15),
                    ItemLoot.of("minecraft:ender_pearl", 1, 1, 10),
                    ItemLoot.of("minecraft:iron_pickaxe", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_sword", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_chestplate", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_helmet", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_leggings", 1, 1, 5),
                    ItemLoot.of("minecraft:iron_boots", 1, 1, 5),
                    ItemLoot.of("minecraft:golden_pickaxe", 1, 1, 3),
                    ItemLoot.of("minecraft:golden_sword", 1, 1, 3),
                    ItemLoot.of("minecraft:enchanted_book", 1, 1, 3),
                    ItemLoot.of("minecraft:diamond", 1, 3, 3)
                ))
            )
        );
    }

    private static void register(String id, String displayName, List<LootPool> pools) {
        TABLES.put(id, new LootTableDef(id, displayName, pools));
    }

    public static void register(LootTableDef table) {
        TABLES.put(table.tableId(), table);
    }

    public static LootTableDef get(String id) {
        return TABLES.get(id);
    }

    public static boolean has(String id) {
        return TABLES.containsKey(id);
    }

    public static void loadAllFromBundled() {
        BundledLootTableLoader.loadAll();
    }

    public static void loadAllForProfile(BundledLootTableLoader.VersionProfile profile) {
        BundledLootTableLoader.loadAllForProfile(profile);
    }

    /** Clears all registered tables (for cross-version test isolation). */
    public static void resetForTest() {
        TABLES.clear();
    }
}
