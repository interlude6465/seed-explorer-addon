package me.seedexplorer.addon.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BundledLootTableLoader {
    private static final String LOOT_TABLE_DIR;
    private static final VersionProfile RUNTIME_PROFILE;
    private static final ThreadLocal<VersionProfile> PARSE_PROFILE = new ThreadLocal<>();
    private static final ThreadLocal<String> PARSE_TABLE_DIR = new ThreadLocal<>();

    /**
     * A version profile records version-specific configuration for loot table
     * loading and structure prediction. Each target Minecraft version that the
     * prediction engine supports should have a corresponding profile.
     */
    public record VersionProfile(
        String minecraftVersion,
        String lootTablePathPrefix,
        Map<String, Integer> structureIndexOverrides
    ) {
        public static VersionProfile forVersion(String version) {
            for (VersionProfile profile : SUPPORTED_VERSIONS) {
                if (profile.minecraftVersion.equals(version)) return profile;
            }
            int bestIdx = 0;
            for (int i = 1; i < SUPPORTED_VERSIONS.length; i++) {
                if (compareVersions(version, SUPPORTED_VERSIONS[i].minecraftVersion) >= 0) {
                    bestIdx = i;
                }
            }
            return SUPPORTED_VERSIONS[bestIdx];
        }

        public int major() { return parseComponent(minecraftVersion, 0); }
        public int minor() { return parseComponent(minecraftVersion, 1); }
        public int patch() { return parseComponent(minecraftVersion, 2); }

        private static int parseComponent(String v, int idx) {
            String[] parts = v.split("\\.");
            if (idx >= parts.length) return 0;
            try { return Integer.parseInt(parts[idx]); } catch (NumberFormatException e) { return 0; }
        }

        /**
         * Returns the overridden decoration index for a structure key, or -1
         * if no override exists (fall back to STRUCTURE_TYPE registry lookup).
         */
        public int decorationIndexOverride(String structureKey) {
            Integer idx = structureIndexOverrides.get(structureKey);
            return idx != null ? idx : -1;
        }
    }

    private static final VersionProfile[] SUPPORTED_VERSIONS = {
        new VersionProfile("1.19.4", "loot_tables", indexOverrides_1_19_4()),
        new VersionProfile("1.20.4", "loot_tables", indexOverrides_1_20_4()),
        new VersionProfile("1.21", "loot_tables", indexOverrides_1_21()),
        new VersionProfile("1.21.4", "loot_tables", indexOverrides_1_21_4()),
        new VersionProfile("1.21.5", "loot_tables", indexOverrides_1_21_5()),
        new VersionProfile("1.21.11", "loot_table", indexOverrides_1_21_11()),
        new VersionProfile("26.1.2", "loot_table", indexOverrides_26_1_2()),
        new VersionProfile("26.2", "loot_table", indexOverrides_26_1_2()),
    };

    static {
        String dir = "loot_table";
        VersionProfile detected = null;
        try {
            String version = SharedConstants.getCurrentVersion().name();
            if (version != null) {
                detected = VersionProfile.forVersion(version);
                dir = detected.lootTablePathPrefix();
            }
        } catch (Throwable ignored) {
        }
        LOOT_TABLE_DIR = dir;
        RUNTIME_PROFILE = detected != null ? detected : new VersionProfile("26.1.2", "loot_table", Map.of());
    }

    private static Map<String, Integer> indexOverrides_1_21() {
        Map<String, Integer> m = new HashMap<>();
        m.put("minecraft:desert_pyramid", 1);
        m.put("minecraft:stronghold", 13);
        // Shared structure types whose runtime decoration index is known to differ from the
        // STRUCTURE_TYPE-registry fallback.  These are the same as 26.1.2 unless we have
        // version-specific evidence otherwise.
        m.put("minecraft:fortress", 1);
        m.put("minecraft:bastion_remnant", 0);
        m.put("minecraft:woodland_mansion", 5);
        m.put("minecraft:igloo", 3);
        return Map.copyOf(m);
    }

    private static Map<String, Integer> indexOverrides_1_21_5() {
        return indexOverrides_1_21();
    }

    private static Map<String, Integer> indexOverrides_1_21_11() {
        return indexOverrides_1_21();
    }

    private static Map<String, Integer> indexOverrides_1_19_4() {
        // Same structure as 1.21 for the known overrides.
        return indexOverrides_1_21();
    }

    private static Map<String, Integer> indexOverrides_1_20_4() {
        return indexOverrides_1_21();
    }

    private static Map<String, Integer> indexOverrides_1_21_4() {
        return indexOverrides_1_21();
    }

    private static Map<String, Integer> indexOverrides_26_1_2() {
        Map<String, Integer> m = new HashMap<>();
        m.put("minecraft:fortress", 1);
        m.put("minecraft:stronghold", 19);
        m.put("minecraft:shipwreck", 17);
        // Verified 2026-07-18 against pinned bastion oracle (seed -5674700730434827097):
        // decoration index 0 reproduces the exact 64-bit loot seed for every bastion chest.
        // Without this override the STRUCTURE_TYPE-registry fallback picked a non-zero index,
        // shifting all bastion loot seeds.
        m.put("minecraft:bastion_remnant", 0);
        // Verified 2026-07-18 via LocateAndIndexSweep against seed -1124201383388859604 in-game log:
        // index 5 reproduces the exact chest loot at woodland mansion (-2486,73,807).
        m.put("minecraft:woodland_mansion", 5);
        // Verified 2026-07-18 via LocateAndIndexSweep against seed -1124201383388859604 in-game log:
        // index 3 reproduces igloo chest at (-270,49,295); index 4 reproduces trial chamber entrance chest.
        m.put("minecraft:igloo", 3);
        m.put("minecraft:trial_chambers", 4);
        return Map.copyOf(m);
    }

    /** Returns the runtime version profile detected from SharedConstants. */
    public static VersionProfile runtimeProfile() {
        return RUNTIME_PROFILE;
    }

    /** Returns all defined version profiles. */
    public static VersionProfile[] supportedVersions() {
        return SUPPORTED_VERSIONS.clone();
    }

    private BundledLootTableLoader() {
    }

    /**
     * Compares two semver-like version strings (major.minor.patch).
     * Returns negative if a < b, zero if equal, positive if a > b.
     */
    public static int compareVersions(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            int numA = i < partsA.length ? tryParseInt(partsA[i]) : 0;
            int numB = i < partsB.length ? tryParseInt(partsB[i]) : 0;
            if (numA != numB) return Integer.compare(numA, numB);
        }
        return 0;
    }

    private static int tryParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void loadAll() {
        loadAll(LOOT_TABLE_DIR);
    }

    public static void loadAll(String tableDir) {
        register(tableDir, "minecraft:chests/abandoned_mineshaft", "Abandoned Mineshaft");
        register(tableDir, "minecraft:chests/ancient_city", "Ancient City");
        register(tableDir, "minecraft:chests/ancient_city_ice_box", "Ancient City Ice Box");
        register(tableDir, "minecraft:chests/bastion_bridge", "Bastion Bridge");
        register(tableDir, "minecraft:chests/bastion_hoglin_stable", "Bastion Hoglin Stable");
        register(tableDir, "minecraft:chests/bastion_other", "Bastion Other");
        register(tableDir, "minecraft:chests/bastion_treasure", "Bastion Treasure");
        register(tableDir, "minecraft:chests/buried_treasure", "Buried Treasure");
        register(tableDir, "minecraft:chests/desert_pyramid", "Desert Pyramid");
        register(tableDir, "minecraft:chests/end_city_treasure", "End City Treasure");
        register(tableDir, "minecraft:chests/igloo_chest", "Igloo Chest");
        register(tableDir, "minecraft:chests/jungle_temple", "Jungle Temple");
        register(tableDir, "minecraft:chests/jungle_temple_dispenser", "Jungle Temple Dispenser");
        register(tableDir, "minecraft:chests/nether_bridge", "Nether Fortress");
        register(tableDir, "minecraft:chests/pillager_outpost", "Pillager Outpost");
        register(tableDir, "minecraft:chests/ruined_portal", "Ruined Portal");
        register(tableDir, "minecraft:chests/shipwreck_map", "Shipwreck Map");
        register(tableDir, "minecraft:chests/shipwreck_supply", "Shipwreck Supply");
        register(tableDir, "minecraft:chests/shipwreck_treasure", "Shipwreck Treasure");
        register(tableDir, "minecraft:chests/simple_dungeon", "Simple Dungeon");
        register(tableDir, "minecraft:chests/stronghold_corridor", "Stronghold Corridor");
        register(tableDir, "minecraft:chests/stronghold_crossing", "Stronghold Crossing");
        register(tableDir, "minecraft:chests/stronghold_library", "Stronghold Library");
        register(tableDir, "minecraft:chests/stronghold_storeroom", "Stronghold Storeroom");
        register(tableDir, "minecraft:chests/underwater_ruin_big", "Underwater Ruin (Big)");
        register(tableDir, "minecraft:chests/underwater_ruin_small", "Underwater Ruin (Small)");
        register(tableDir, "minecraft:chests/woodland_mansion", "Woodland Mansion");
        register(tableDir, "minecraft:chests/spawn_bonus_chest", "Bonus Chest");
        register(tableDir, "minecraft:chests/trial_chambers/corridor", "Trial Chambers Corridor");
        register(tableDir, "minecraft:chests/trial_chambers/reward", "Trial Chambers Reward");
        register(tableDir, "minecraft:chests/trial_chambers/reward_common", "Trial Chambers Reward (Common)");
        register(tableDir, "minecraft:chests/trial_chambers/reward_rare", "Trial Chambers Reward (Rare)");
        register(tableDir, "minecraft:chests/trial_chambers/reward_unique", "Trial Chambers Reward (Unique)");
        register(tableDir, "minecraft:chests/trial_chambers/reward_ominous", "Trial Chambers Ominous Reward");
        register(tableDir, "minecraft:chests/trial_chambers/reward_ominous_common", "Trial Chambers Ominous (Common)");
        register(tableDir, "minecraft:chests/trial_chambers/reward_ominous_unique", "Trial Chambers Ominous (Unique)");
        register(tableDir, "minecraft:chests/trial_chambers/entrance", "Trial Chambers Entrance");
        register(tableDir, "minecraft:chests/trial_chambers/supply", "Trial Chambers Supply");
        register(tableDir, "minecraft:chests/trial_chambers/intersection", "Trial Chambers Intersection");
        register(tableDir, "minecraft:chests/trial_chambers/intersection_barrel", "Trial Chambers Intersection Barrel");
        register(tableDir, "minecraft:chests/trial_chambers/reward_ominous_rare", "Trial Chambers Ominous (Rare)");
        register(tableDir, "minecraft:dispensers/trial_chambers/chamber", "Trial Chambers Chamber Dispenser");
        register(tableDir, "minecraft:dispensers/trial_chambers/corridor", "Trial Chambers Corridor Dispenser");
        register(tableDir, "minecraft:dispensers/trial_chambers/water", "Trial Chambers Water Dispenser");
        register(tableDir, "minecraft:pots/trial_chambers/corridor", "Trial Chambers Corridor Pot");
        register(tableDir, "minecraft:chests/village/village_armorer", "Village Armorer");
        register(tableDir, "minecraft:chests/village/village_butcher", "Village Butcher");
        register(tableDir, "minecraft:chests/village/village_cartographer", "Village Cartographer");
        register(tableDir, "minecraft:chests/village/village_desert_house", "Village Desert House");
        register(tableDir, "minecraft:chests/village/village_fisher", "Village Fisher");
        register(tableDir, "minecraft:chests/village/village_fletcher", "Village Fletcher");
        register(tableDir, "minecraft:chests/village/village_mason", "Village Mason");
        register(tableDir, "minecraft:chests/village/village_plains_house", "Village Plains House");
        register(tableDir, "minecraft:chests/village/village_savanna_house", "Village Savanna House");
        register(tableDir, "minecraft:chests/village/village_shepherd", "Village Shepherd");
        register(tableDir, "minecraft:chests/village/village_snowy_house", "Village Snowy House");
        register(tableDir, "minecraft:chests/village/village_taiga_house", "Village Taiga House");
        register(tableDir, "minecraft:chests/village/village_tannery", "Village Tannery");
        register(tableDir, "minecraft:chests/village/village_temple", "Village Temple");
        register(tableDir, "minecraft:chests/village/village_toolsmith", "Village Toolsmith");
        register(tableDir, "minecraft:chests/village/village_weaponsmith", "Village Weaponsmith");
    }

    private static void register(String tableId, String displayName) {
        register(LOOT_TABLE_DIR, tableId, displayName);
    }

    private static void register(String tableDir, String tableId, String displayName) {
        LootTableDef parsed = parseFromBundled(tableDir, tableId, displayName);
        if (parsed != null) {
            VanillaLootTables.register(parsed);
        }
    }

    public static LootTableDef parseFromBundled(String tableId, String displayName) {
        return parseFromBundled(LOOT_TABLE_DIR, tableId, displayName);
    }

    public static LootTableDef parseFromBundled(String tableDir, String tableId, String displayName) {
        String resourcePath = "/data/minecraft/" + tableDir + "/" + tableId.substring("minecraft:".length()) + ".json";
        try (InputStream in = BundledLootTableLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
            JsonArray poolsArray = root.getAsJsonArray("pools");
            if (poolsArray == null || poolsArray.isEmpty()) return null;

            List<LootPool> pools = new ArrayList<>();
            for (JsonElement poolElement : poolsArray) {
                JsonObject poolObj = poolElement.getAsJsonObject();
                double[] rolls = parseRolls(poolObj.get("rolls"));
                int minRolls = (int) rolls[0];
                int maxRolls = (int) rolls[1];

                // Parse conditions (only random_chance is supported)
                float chance = 1.0f;
                JsonArray conditions = poolObj.getAsJsonArray("conditions");
                if (conditions != null) {
                    for (JsonElement condElement : conditions) {
                        JsonObject condObj = condElement.getAsJsonObject();
                        if ("minecraft:random_chance".equals(string(condObj, "condition"))) {
                            chance = (float) condObj.get("chance").getAsDouble();
                        }
                    }
                }

                JsonArray entriesArray = poolObj.getAsJsonArray("entries");
                if (entriesArray == null || entriesArray.isEmpty()) continue;

                List<ItemLoot> entries = new ArrayList<>();
                for (JsonElement entryElement : entriesArray) {
                    ItemLoot item = parseEntry(entryElement.getAsJsonObject());
                    if (item != null) entries.add(item);
                }
                if (!entries.isEmpty()) {
                    pools.add(new LootPool(minRolls, maxRolls, List.copyOf(entries), chance));
                }
            }
            if (pools.isEmpty()) return null;
            return new LootTableDef(tableId, displayName, List.copyOf(pools));
        } catch (Exception e) {
            return null;
        }
    }

    private static double[] parseRolls(JsonElement rollsElement) {
        if (rollsElement == null) return new double[]{1, 1};
        if (rollsElement.isJsonPrimitive()) {
            double v = rollsElement.getAsDouble();
            return new double[]{v, v};
        }
        JsonObject rollsObj = rollsElement.getAsJsonObject();
        double min = rollsObj.has("min") ? rollsObj.get("min").getAsDouble() : 1;
        double max = rollsObj.has("max") ? rollsObj.get("max").getAsDouble() : min;
        return new double[]{min, max};
    }

    private static LootTableDef parseTable(String tableId) {
        String tableDir = PARSE_TABLE_DIR.get();
        return parseFromBundled(tableDir == null ? LOOT_TABLE_DIR : tableDir, tableId, tableId);
    }

    private static LootTableDef parseTable(String tableDir, String tableId) {
        return parseFromBundled(tableDir, tableId, tableId);
    }

    /**
     * Load all tables using the provided version profile's path prefix.
     */
    public static void loadAllForProfile(VersionProfile profile) {
        VersionProfile previousProfile = PARSE_PROFILE.get();
        String previousDir = PARSE_TABLE_DIR.get();
        PARSE_PROFILE.set(profile);
        PARSE_TABLE_DIR.set(profile.lootTablePathPrefix());
        try {
            loadAll(profile.lootTablePathPrefix());
        } finally {
            PARSE_PROFILE.set(previousProfile);
            PARSE_TABLE_DIR.set(previousDir);
        }
    }

    public static LootTableDef parseFromBundled(VersionProfile profile, String tableId, String displayName) {
        VersionProfile previousProfile = PARSE_PROFILE.get();
        String previousDir = PARSE_TABLE_DIR.get();
        PARSE_PROFILE.set(profile);
        PARSE_TABLE_DIR.set(profile.lootTablePathPrefix());
        try {
            return parseFromBundled(profile.lootTablePathPrefix(), tableId, displayName);
        } finally {
            PARSE_PROFILE.set(previousProfile);
            PARSE_TABLE_DIR.set(previousDir);
        }
    }

    /**
     * Parse an entry from a loot table, using the version profile for
     * version-specific function name resolution.
     */
    private static ItemLoot parseEntry(JsonObject entryObj) {
        VersionProfile profile = PARSE_PROFILE.get();
        return parseEntry(entryObj, profile == null ? RUNTIME_PROFILE : profile);
    }

    private static ItemLoot parseEntry(JsonObject entryObj, VersionProfile profile) {
        String type = string(entryObj, "type");
        if ("minecraft:empty".equals(type)) {
            return ItemLoot.empty(weight(entryObj));
        }
        if ("minecraft:loot_table".equals(type)) {
            String refTableId = string(entryObj, "value");
            if (refTableId == null) return null;
            LootTableDef refTable = VanillaLootTables.get(refTableId);
            if (refTable == null) {
                refTable = parseTable(refTableId);
                if (refTable != null) VanillaLootTables.register(refTable);
            }
            return ItemLoot.tableReference(refTableId, weight(entryObj));
        }
        if (!"minecraft:item".equals(type)) return null;

        String name = string(entryObj, "name");
        if (name == null) return null;

        int w = weight(entryObj);
        boolean randomEnchant = false;
        String randomEnchantOption = null;
        String fixedEnchantId = null;
        int fixedEnchantLevel = 0;
        boolean levelsEnchant = false;
        boolean explorationMap = false;
        boolean hasDamage = false;
        double minEnchantLevel = 0;
        double maxEnchantLevel = 0;
        double minCount = 1;
        double maxCount = 1;

        JsonArray functions = entryObj.getAsJsonArray("functions");
        if (functions != null) {
            for (JsonElement funcElement : functions) {
                JsonObject funcObj = funcElement.getAsJsonObject();
                String funcType = string(funcObj, "function");
                if (isSetCountFunction(funcType)) {
                    JsonElement countElement = funcObj.get("count");
                    if (countElement == null) countElement = funcObj.get("amount");
                    double[] countRange = parseRolls(countElement);
                    minCount = countRange[0];
                    maxCount = countRange[1];
                } else if (isEnchantRandomlyFunction(funcType, profile)) {
                    randomEnchant = true;
                    String option = enchantOptions(funcObj.get("options"));
                    if (option != null && !option.startsWith("#")) {
                        randomEnchantOption = option;
                    }
                } else if (isEnchantWithLevelsFunction(funcType)) {
                    double[] levelRange = parseRolls(funcObj.get("levels"));
                    minEnchantLevel = levelRange[0];
                    maxEnchantLevel = levelRange[1];
                    levelsEnchant = true;
                } else if (isSetEnchantmentsFunction(funcType)) {
                    JsonObject enchants = funcObj.getAsJsonObject("enchantments");
                    if (enchants != null && !enchants.entrySet().isEmpty()) {
                        var entry = enchants.entrySet().iterator().next();
                        fixedEnchantId = entry.getKey();
                        fixedEnchantLevel = (int) parseRolls(entry.getValue())[0];
                    }
                } else if ("minecraft:exploration_map".equals(funcType)) {
                    explorationMap = true;
                } else if ("minecraft:set_damage".equals(funcType)) {
                    // Vanilla's SetItemDamageFunction.run() calls random.nextFloat() to pick a
                    // durability fraction. That RNG call must be consumed by the simulator even
                    // though predicted loot doesn't display durability — without it the stream
                    // drifts for every gear entry that follows in the same chest roll.
                    hasDamage = true;
                }
            }
        }

        if (explorationMap && "minecraft:map".equals(name)) {
            name = "minecraft:filled_map";
        }

        if (randomEnchant && "minecraft:book".equals(name)) {
            ItemLoot book = randomEnchantOption == null
                ? ItemLoot.randomlyEnchantedBook(w)
                : ItemLoot.randomlyEnchantedBook(w, randomEnchantOption);
            return hasDamage ? book.withDamageRoll() : book;
        }
        ItemLoot item = ItemLoot.of(name, (int) minCount, (int) maxCount, w);
        if (levelsEnchant) {
            item = item.withLevelsEnchantment((int) minEnchantLevel, (int) maxEnchantLevel);
        }
        if (fixedEnchantId != null) {
            item = item.withFixedEnchantment(fixedEnchantId, fixedEnchantLevel);
        }
        // enchant_randomly on non-book items: the simulator must consume the same RNG calls
        // vanilla makes (one nextInt for enchantment selection, one for level) so the stream
        // stays in sync with the real game. Without this, every gear entry that rolls an
        // enchantment shifts all subsequent loot seeds in the chest (visible as bastion_treasure
        // items being completely wrong despite a correct 64-bit loot seed).
        if (randomEnchant) {
            item = item.withRandomEnchantment(randomEnchantOption);
        }
        if (hasDamage) {
            item = item.withDamageRoll();
        }
        return item;
    }

    private static boolean isSetCountFunction(String funcType) {
        return "minecraft:set_count".equals(funcType)
            || "minecraft:set_count".equals(funcType);
    }

    /**
     * The function name for random enchanting changed between versions.
     * 1.21+ uses "minecraft:enchant_randomly" while 26.1.2 uses the same.
     * Older trial chambers builds may use alternative names.
     */
    private static boolean isEnchantRandomlyFunction(String funcType, VersionProfile profile) {
        if ("minecraft:enchant_randomly".equals(funcType)) return true;
        return false;
    }

    private static boolean isEnchantWithLevelsFunction(String funcType) {
        return "minecraft:enchant_with_levels".equals(funcType);
    }

    private static boolean isSetEnchantmentsFunction(String funcType) {
        return "minecraft:set_enchantments".equals(funcType);
    }

    private static String enchantOptions(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive()) return element.getAsString();
        if (element.isJsonArray()) {
            List<String> ids = new ArrayList<>();
            for (JsonElement option : element.getAsJsonArray()) {
                if (option != null && !option.isJsonNull()) ids.add(option.getAsString());
            }
            return ids.isEmpty() ? null : String.join(",", ids);
        }
        return null;
    }

    private static int weight(JsonObject entry) {
        return entry.has("weight") ? entry.get("weight").getAsInt() : 1;
    }

    private static String string(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }
}
