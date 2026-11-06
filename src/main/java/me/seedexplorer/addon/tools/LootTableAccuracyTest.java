package me.seedexplorer.addon.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.LootTableDef;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.loot.VanillaLootTables;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public final class LootTableAccuracyTest {
    private static final PrintStream OUT = System.out;

    private LootTableAccuracyTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: LootTableAccuracyTest <oracle.json>");
        }

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        VanillaLootTables.loadAllFromBundled();

        Path oraclePath = Path.of(args[0]);
        JsonObject root;
        try (var reader = Files.newBufferedReader(oraclePath)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }

        OUT.println("loot_table_accuracy_test oracle=" + oraclePath.getFileName());
        OUT.println("mc_version=" + root.get("mcVersion").getAsString());

        JsonObject scan = root.has("regionScan") && root.get("regionScan").isJsonObject()
            ? root.getAsJsonObject("regionScan") : root;
        JsonArray structures = scan.has("structures") && scan.get("structures").isJsonArray()
            ? scan.getAsJsonArray("structures") : new JsonArray();

        // Structure-aware comparison handles double chests correctly: Paper
        // exposes both block positions while their opened inventory contains
        // one independent loot roll from each half. Keep the old table-only
        // path below for legacy captures without region structure metadata.
        if (!structures.isEmpty()) {
            Set<String> structureIds = new HashSet<>();
            for (JsonElement element : structures) {
                if (element.isJsonObject() && element.getAsJsonObject().has("id")) {
                    structureIds.add(element.getAsJsonObject().get("id").getAsString());
                }
            }
            int failures = 0;
            for (String structureId : structureIds) {
                OracleCaseComparator.Result result = OracleCaseComparator.compare(
                    oraclePath, structureId, null);
                OUT.println("structure=" + structureId + " " + result.summary());
                for (String difference : result.differences()) OUT.println("  " + difference);
                if (!result.exactMatch()) failures++;
            }
            if (failures > 0) System.exit(2);
            return;
        }

        int totalChests = 0;
        int containersWithoutLootTables = 0;
        int testedLootTables = 0;
        int exactItemMatches = 0;
        int itemMismatches = 0;
        int missingTables = 0;

        for (JsonElement se : structures) {
            JsonObject struct = se.getAsJsonObject();
            JsonArray chests = struct.has("chests") && struct.get("chests").isJsonArray()
                ? struct.getAsJsonArray("chests") : new JsonArray();

            for (JsonElement ce : chests) {
                JsonObject chest = ce.getAsJsonObject();
                totalChests++;

                String tableId = string(chest, "lootTable");
                if (tableId == null || tableId.isBlank()) {
                    containersWithoutLootTables++;
                    continue;
                }

                testedLootTables++;
                long seed = chest.has("lootTableSeed") && !chest.get("lootTableSeed").isJsonNull()
                    ? chest.get("lootTableSeed").getAsLong() : 0;

                LootTableDef table = VanillaLootTables.get(tableId);
                if (table == null) {
                    OUT.println("MISSING_TABLE table=" + tableId + " chest=" + chest);
                    missingTables++;
                    continue;
                }

                Map<String, Integer> oracleItems = readOracleItems(chest);
                java.util.List<ItemLoot> vanilla = LootTableSimulator.simulateVanilla(tableId, seed);
                java.util.List<ItemLoot> predicted = vanilla != null
                    ? vanilla
                    : LootTableSimulator.simulate(table, seed, 64);
                Map<String, Integer> predictedItems = new HashMap<>();
                for (ItemLoot item : predicted) {
                    predictedItems.merge(item.itemId(), item.minCount(), Integer::sum);
                }

                if (oracleItems.equals(predictedItems)) {
                    exactItemMatches++;
                } else {
                    itemMismatches++;
                    OUT.println("ITEM_MISMATCH table=" + tableId + " seed=" + seed
                        + " oracle=" + oracleItems + " predicted=" + predictedItems);
                }
            }
        }

        OUT.println("results total_chests=" + totalChests
            + " containers_without_loot_tables=" + containersWithoutLootTables
            + " tested_loot_tables=" + testedLootTables
            + " exact_matches=" + exactItemMatches
            + " mismatches=" + itemMismatches
            + " missing_tables=" + missingTables);

        if (itemMismatches > 0 || missingTables > 0) {
            System.exit(2);
        }
    }

    private static Map<String, Integer> readOracleItems(JsonObject chest) {
        Map<String, Integer> items = new HashMap<>();
        if (!chest.has("items") || !chest.get("items").isJsonArray()) return items;
        for (JsonElement ie : chest.getAsJsonArray("items")) {
            JsonObject item = ie.getAsJsonObject();
            String id = string(item, "itemId");
            int count = item.has("count") ? item.get("count").getAsInt() : 1;
            if (id != null && !id.equals("minecraft:air")) {
                items.merge(id, count, Integer::sum);
            }
        }
        return items;
    }

    private static String string(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }
}
