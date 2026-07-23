package me.seedexplorer.addon.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Audits the existing mineshaft oracle capture for whether it contains actual
 * abandoned-mineshaft minecart-chest evidence. LootProbe's current capture mode
 * records block containers, so this should remain a blocker report.
 */
public final class MineshaftOracleAudit {
    private static final PrintStream OUT = System.out;

    private MineshaftOracleAudit() {
    }

    public static void main(String[] args) {
        try {
            Path projectRoot = args.length > 0 ? Path.of(args[0]) : Path.of(".");
            Path oracle = projectRoot.resolve("validation").resolve("oracles")
                .resolve("lootprobe-mineshaft-seed0.json");
            run(oracle);
        } catch (Throwable t) {
            OUT.println("mineshaft_oracle_audit_failed=" + t.getClass().getName() + " " + t.getMessage());
            t.printStackTrace(OUT);
            System.exit(1);
        }
    }

    private static void run(Path oracle) throws Exception {
        if (!Files.isRegularFile(oracle)) {
            throw new IllegalStateException("Missing mineshaft oracle: " + oracle.toAbsolutePath());
        }

        JsonObject root;
        try (var reader = Files.newBufferedReader(oracle)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }

        int totalContainers = 0;
        int abandonedMineshaftTables = 0;
        int minecartBlockIds = 0;
        Map<String, Integer> tableCounts = new HashMap<>();

        JsonObject scan = root.getAsJsonObject("regionScan");
        for (JsonElement structureElement : scan.getAsJsonArray("structures")) {
            JsonObject structure = structureElement.getAsJsonObject();
            if (!structure.has("chests") || !structure.get("chests").isJsonArray()) continue;
            for (JsonElement chestElement : structure.getAsJsonArray("chests")) {
                JsonObject chest = chestElement.getAsJsonObject();
                totalContainers++;
                String blockId = string(chest, "blockId");
                String table = string(chest, "lootTable");
                if (blockId != null && blockId.contains("minecart")) minecartBlockIds++;
                if (table != null) tableCounts.merge(table, 1, Integer::sum);
                if ("minecraft:chests/abandoned_mineshaft".equals(table)) abandonedMineshaftTables++;
            }
        }

        OUT.println("result=BLOCKED mineshaft_oracle_has_no_minecart_chest_ground_truth");
        OUT.println("containers=" + totalContainers);
        OUT.println("abandoned_mineshaft_loot_tables=" + abandonedMineshaftTables);
        OUT.println("minecart_block_ids=" + minecartBlockIds);
        tableCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> OUT.println("table_count " + e.getKey() + "=" + e.getValue()));
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }
}
