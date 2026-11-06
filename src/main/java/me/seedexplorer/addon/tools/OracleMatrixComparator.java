package me.seedexplorer.addon.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.seedexplorer.addon.loot.VanillaLootTables;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.io.PrintStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs every configured Paper oracle case through the exact comparator in one JVM. */
public final class OracleMatrixComparator {
    private static final PrintStream OUT = System.out;

    private OracleMatrixComparator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException(
                "Usage: OracleMatrixComparator <cases.json> <oracle-root> [max-cases|locations]");
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        VanillaLootTables.loadAllFromBundled();

        Path casesPath = Path.of(args[0]);
        Path oracleRoot = Path.of(args[1]);
        boolean locationsOnly = args.length == 3 && args[2].equalsIgnoreCase("locations");
        int maxCases = args.length == 3 && !locationsOnly ? Integer.parseInt(args[2]) : 0;
        JsonObject manifest;
        try (Reader reader = Files.newBufferedReader(casesPath)) {
            manifest = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonArray cases = manifest.has("cases") ? manifest.getAsJsonArray("cases") : new JsonArray();
        List<String> failures = new ArrayList<>();
        int tested = 0;
        for (JsonElement element : cases) {
            if (maxCases > 0 && tested >= maxCases) break;
            JsonObject config = element.getAsJsonObject();
            String name = config.get("name").getAsString();
            boolean locationOnly = config.has("locationOnly")
                && config.get("locationOnly").getAsBoolean();
            if (locationsOnly && !locationOnly) continue;
            Path oracle = oracleRoot.resolve(name).resolve(name + ".json");
            if (!Files.isRegularFile(oracle)) {
                failures.add(name + ": missing oracle " + oracle);
                continue;
            }

            if (config.has("locationOnly") && config.get("locationOnly").getAsBoolean()) {
                List<String> structures = configuredStructures(config);
                StructureLocationOracleComparator.Result result =
                    StructureLocationOracleComparator.compare(
                        oracle, structures, config.get("dimension").getAsString());
                OUT.println("case=" + name + " " + result.summary());
                for (String difference : result.differences()) {
                    OUT.println("  " + difference);
                }
                if (!result.exactMatch()) failures.add(name + ": " + result.differences());
                tested++;
                continue;
            }

            String structure = config.get("structure").getAsString();
            OracleCaseComparator.Result result = OracleCaseComparator.compare(oracle, structure, null);
            OUT.println("case=" + name + " " + result.summary());
            for (String difference : result.differences()) {
                OUT.println("  " + difference);
            }
            if (!result.exactMatch()) failures.add(name + ": " + result.differences());
            tested++;
        }
        OUT.println("oracle_matrix_compare_pass=" + failures.isEmpty()
            + " cases=" + tested + " failures=" + failures.size());
        for (String failure : failures) OUT.println("failure=" + failure);
        if (!failures.isEmpty()) System.exit(2);
    }

    private static List<String> configuredStructures(JsonObject config) {
        List<String> result = new ArrayList<>();
        if (config.has("structures") && config.get("structures").isJsonArray()) {
            for (JsonElement element : config.getAsJsonArray("structures")) {
                result.add(element.getAsString());
            }
        } else if (config.has("structure")) {
            result.add(config.get("structure").getAsString());
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                "Location-only oracle case has no structures: " + config.get("name"));
        }
        return List.copyOf(result);
    }
}
