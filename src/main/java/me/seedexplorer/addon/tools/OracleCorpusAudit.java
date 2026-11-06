package me.seedexplorer.addon.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.PrintStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Audits independent Paper oracle captures without running or modifying the predictor. */
public final class OracleCorpusAudit {
    private static final PrintStream OUT = System.out;
    private static final Pattern NBT_ENCHANTMENTS =
        Pattern.compile("enchants=\\{([^}]*)}");

    private OracleCorpusAudit() {
    }

    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Path.of(args[0]) : Path.of("validation");
        Path oracleDir = root.resolve("oracles");
        if (!Files.isDirectory(oracleDir)) throw new IllegalArgumentException("Missing oracle directory: " + oracleDir);

        Map<String, Stats> byStructure = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        int files = 0;
        for (Path file : Files.newDirectoryStream(oracleDir, "*.json")) {
            files++;
            auditFile(file, byStructure, failures);
        }

        OUT.println("oracle_corpus_files=" + files);
        for (Map.Entry<String, Stats> entry : byStructure.entrySet()) {
            Stats stats = entry.getValue();
            OUT.println("structure=" + entry.getKey()
                + " captures=" + stats.captures
                + " starts=" + stats.starts
                + " containers=" + stats.containers
                + " loot_seeded=" + stats.lootSeeded
                + " enchanted_items=" + stats.enchantedItems
                + " variants=" + stats.variants);
        }
        for (String failure : failures) OUT.println("failure=" + failure);
        OUT.println("oracle_corpus_audit_pass=" + failures.isEmpty() + " failures=" + failures.size());
        if (!failures.isEmpty()) System.exit(2);
    }

    private static void auditFile(Path file, Map<String, Stats> byStructure,
                                  List<String> failures) {
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject scan = object(root, "regionScan");
            if (scan == null) {
                failures.add(file.getFileName() + ": missing regionScan");
                return;
            }
            String version = string(root, "mcVersion");
            if (version == null || version.isBlank()) failures.add(file.getFileName() + ": missing mcVersion");
            JsonArray structures = array(scan, "structures");
            if (structures == null || structures.isEmpty()) {
                OUT.println("oracle=" + file.getFileName() + " skipped=no_region_structures");
                return;
            }

            Set<String> filePositions = new HashSet<>();
            for (JsonElement element : structures) {
                if (!element.isJsonObject()) continue;
                JsonObject structure = element.getAsJsonObject();
                String id = string(structure, "id");
                if (id == null) {
                    failures.add(file.getFileName() + ": structure missing id");
                    continue;
                }
                Stats stats = byStructure.computeIfAbsent(id, ignored -> new Stats());
                stats.captures++;
                stats.starts++;
                String variant = string(structure, "variant");
                if (variant != null && !variant.isBlank()) stats.variants.add(variant);

                JsonArray chests = array(structure, "chests");
                if (chests == null) continue;
                for (JsonElement chestElement : chests) {
                    if (!chestElement.isJsonObject()) continue;
                    JsonObject chest = chestElement.getAsJsonObject();
                    String position = string(chest, "x") + "," + string(chest, "y") + "," + string(chest, "z");
                    if (!filePositions.add(id + "|" + position)) {
                        failures.add(file.getFileName() + ": duplicate container " + id + " at " + position);
                    }
                    stats.containers++;
                    String lootTable = string(chest, "lootTable");
                    if (lootTable != null && !lootTable.isBlank()) {
                        if (!chest.has("lootTableSeed") || chest.get("lootTableSeed").isJsonNull()) {
                            failures.add(file.getFileName() + ": loot table without seed at " + position);
                        } else {
                            stats.lootSeeded++;
                        }
                    }
                    JsonArray items = array(chest, "items");
                    if (items == null) continue;
                    for (JsonElement itemElement : items) {
                        if (!itemElement.isJsonObject()) continue;
                        JsonObject item = itemElement.getAsJsonObject();
                        JsonArray enchantments = array(item, "enchantments");
                        JsonArray levels = array(item, "enchantmentLevels");
                        if ((enchantments != null && !enchantments.isEmpty())
                            || (levels != null && !levels.isEmpty())
                            || hasSerializedEnchantments(item)) {
                            stats.enchantedItems++;
                        }
                    }
                }
            }
        } catch (Exception exception) {
            failures.add(file.getFileName() + ": parse error " + exception.getClass().getSimpleName()
                + ": " + exception.getMessage());
        }
    }

    private static JsonObject object(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null;
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : null;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    private static boolean hasSerializedEnchantments(JsonObject item) {
        String nbt = string(item, "nbt");
        if (nbt == null) return false;
        Matcher matcher = NBT_ENCHANTMENTS.matcher(nbt);
        return matcher.find() && !matcher.group(1).isBlank();
    }

    private static final class Stats {
        private int captures;
        private int starts;
        private int containers;
        private int lootSeeded;
        private int enchantedItems;
        private final Set<String> variants = new HashSet<>();
    }
}
