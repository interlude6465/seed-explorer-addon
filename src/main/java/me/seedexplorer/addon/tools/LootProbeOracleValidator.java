package me.seedexplorer.addon.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.LootTableDef;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.loot.VanillaLootTables;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.io.IOException;
import java.io.Reader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LootProbeOracleValidator {
    private static final String DESERT_TABLE = "minecraft:chests/desert_pyramid";
    private static final PrintStream OUTPUT = System.out;

    private LootProbeOracleValidator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                "Usage: LootProbeOracleValidator <seed> <structure-chunk-x> <structure-chunk-z> <lootprobe.json>");
        }

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        long seed = Long.parseLong(args[0]);
        int chunkX = Integer.parseInt(args[1]);
        int chunkZ = Integer.parseInt(args[2]);
        Validation validation = validate(seed, chunkX, chunkZ, Path.of(args[3]));

        OUTPUT.println(validation.summary());
        for (String difference : validation.differences()) OUTPUT.println("  " + difference);
        if (!validation.exactMatch()) System.exit(2);
    }

    public static Validation validate(long seed, int chunkX, int chunkZ, Path oraclePath) throws IOException {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(oraclePath)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }

        List<String> differences = new ArrayList<>();
        if (root.has("seed") && root.get("seed").getAsLong() != seed) {
            differences.add("world seed: predicted=" + seed + " oracle=" + root.get("seed").getAsLong());
        }
        if (root.has("mcVersion")) {
            String oracleVersion = root.get("mcVersion").getAsString();
            String runtimeVersion = SharedConstants.getCurrentVersion().name();
            if (!oracleVersion.equals(runtimeVersion)) {
                differences.add("Minecraft version: predictor=" + runtimeVersion + " oracle=" + oracleVersion);
            }
        }

        List<OracleChest> oracleList = readDesertChests(root);
        List<VanillaLootStructureSimulator.SimulatedContainer> simulated =
            VanillaLootStructureSimulator.desertPyramid(seed, chunkX, chunkZ);

        List<OracleChest> unmatchedOracle = new ArrayList<>(oracleList);
        for (VanillaLootStructureSimulator.SimulatedContainer expected : simulated) {
            OracleChest actual = null;
            for (int i = 0; i < unmatchedOracle.size(); i++) {
                OracleChest candidate = unmatchedOracle.get(i);
                if (candidate.x() == expected.x() && candidate.y() == expected.y()
                    && candidate.z() == expected.z()) {
                    actual = unmatchedOracle.remove(i);
                    break;
                }
            }
            String pos = expected.x() + "," + expected.y() + "," + expected.z();
            if (actual == null) {
                differences.add("missing oracle chest at " + pos);
                continue;
            }
            if (!expected.lootTableId().equals(actual.lootTable())) {
                differences.add(pos + " loot table: predicted=" + expected.lootTableId()
                    + " oracle=" + actual.lootTable());
            }
            if (actual.lootSeed() == null) {
                differences.add(pos + " oracle did not expose a loot seed");
            } else if (expected.lootSeed() != actual.lootSeed()) {
                differences.add(pos + " loot seed: predicted=" + expected.lootSeed()
                    + " oracle=" + actual.lootSeed());
            }

            LootTableDef table = VanillaLootTables.get(expected.lootTableId());
            if (table != null && actual.lootSeed() != null) {
                Map<String, Integer> expectedItems = aggregate(
                    LootTableSimulator.simulate(table, expected.lootSeed(), 64));
                if (!expectedItems.equals(actual.items())) {
                    differences.add(pos + " item totals: predicted=" + expectedItems
                        + " oracle=" + actual.items());
                }
            }
        }
        for (OracleChest extra : unmatchedOracle) {
            differences.add("unexpected oracle desert chest at " + extra.x() + "," + extra.y() + "," + extra.z());
        }

        return new Validation(simulated.size(), oracleList.size(), List.copyOf(differences));
    }

    private static List<OracleChest> readDesertChests(JsonObject root) {
        JsonObject scan = root.has("regionScan") && root.get("regionScan").isJsonObject()
            ? root.getAsJsonObject("regionScan") : root;
        JsonArray structures = scan.has("structures") && scan.get("structures").isJsonArray()
            ? scan.getAsJsonArray("structures") : new JsonArray();
        List<OracleChest> result = new ArrayList<>();

        for (JsonElement structureElement : structures) {
            if (!structureElement.isJsonObject()) continue;
            JsonObject structure = structureElement.getAsJsonObject();
            if (!structure.has("chests") || !structure.get("chests").isJsonArray()) continue;
            for (JsonElement chestElement : structure.getAsJsonArray("chests")) {
                if (!chestElement.isJsonObject()) continue;
                JsonObject chest = chestElement.getAsJsonObject();
                String table = string(chest, "lootTable");
                if (!DESERT_TABLE.equals(table)) continue;
                int x = integer(chest, "x");
                int y = integer(chest, "y");
                int z = integer(chest, "z");
                Long lootSeed = chest.has("lootTableSeed") && !chest.get("lootTableSeed").isJsonNull()
                    ? chest.get("lootTableSeed").getAsLong() : null;
                result.add(new OracleChest(x, y, z, table, lootSeed, readItems(chest)));
            }
        }
        return result;
    }

    private static Map<String, Integer> readItems(JsonObject chest) {
        Map<String, Integer> result = new HashMap<>();
        if (!chest.has("items") || !chest.get("items").isJsonArray()) return Map.of();
        for (JsonElement element : chest.getAsJsonArray("items")) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String id = string(item, "itemId");
            if (id == null || id.equals("minecraft:air")) continue;
            result.merge(id, integer(item, "count"), Integer::sum);
        }
        return Map.copyOf(result);
    }

    private static Map<String, Integer> aggregate(List<ItemLoot> items) {
        Map<String, Integer> result = new HashMap<>();
        for (ItemLoot item : items) result.merge(item.itemId(), item.minCount(), Integer::sum);
        return Map.copyOf(result);
    }

    private static int integer(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsInt() : 0;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    public record Validation(int predictedChestCount, int oracleChestCount, List<String> differences) {
        public boolean exactMatch() {
            return differences.isEmpty();
        }

        public String summary() {
            return "loot_oracle_match=" + exactMatch() + " predicted_chests=" + predictedChestCount
                + " oracle_chests=" + oracleChestCount + " differences=" + differences.size();
        }
    }

    private record OracleChest(int x, int y, int z, String lootTable, Long lootSeed, Map<String, Integer> items) {
    }
}
