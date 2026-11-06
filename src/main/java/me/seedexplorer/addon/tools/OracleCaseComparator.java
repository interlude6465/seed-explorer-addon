package me.seedexplorer.addon.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.loot.VanillaLootTables;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.io.PrintStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compares one fresh Paper oracle case with the offline structure and loot simulator. */
public final class OracleCaseComparator {
    private static final PrintStream OUT = System.out;
    private static final Pattern NBT_ENCHANTMENTS = Pattern.compile("enchants=\\{([^}]*)}");

    private OracleCaseComparator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException(
                "Usage: OracleCaseComparator <oracle.json> <structure-id> [decoration-index]");
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        VanillaLootTables.loadAllFromBundled();

        Path oraclePath = Path.of(args[0]);
        String structureId = args[1];
        Integer explicitIndex = args.length == 3 ? Integer.parseInt(args[2]) : null;
        Result result = compare(oraclePath, structureId, explicitIndex);
        OUT.println(result.summary());
        for (String difference : result.differences()) OUT.println("  " + difference);
        if (!result.exactMatch()) System.exit(2);
    }

    public static Result compare(Path oraclePath, String structureId, Integer explicitIndex) throws Exception {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(oraclePath)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        long seed = root.get("seed").getAsLong();
        JsonObject scan = root.getAsJsonObject("regionScan");
        JsonArray structures = scan.getAsJsonArray("structures");
        List<JsonObject> targets = new ArrayList<>();
        for (JsonElement element : structures) {
            JsonObject candidate = element.getAsJsonObject();
            if (structureId.equals(candidate.get("id").getAsString())) {
                targets.add(candidate);
            }
        }
        if (targets.isEmpty()) {
            return new Result(0, 0, 0, 0, 0,
                List.of("oracle has no structure " + structureId));
        }

        ResourceKey<Structure> key = structureKey(structureId);
        int dimension = dimension(scan.get("dimension").getAsString());
        Map<String, OracleContainer> oracle = new HashMap<>();
        List<VanillaLootStructureSimulator.SimulatedContainer> simulated = new ArrayList<>();
        List<String> differences = new ArrayList<>();
        Set<String> simulatedStarts = new HashSet<>();
        int predictedStructureLocations = 0;
        for (JsonObject target : targets) {
            int targetX = target.get("x").getAsInt();
            int targetZ = target.get("z").getAsInt();
            if (StructureLocationOracleComparator.predictsLocation(
                seed, dimension, structureId, targetX, targetZ)) {
                predictedStructureLocations++;
            } else {
                differences.add("missing predicted structure location at " + targetX + "," + targetZ);
            }

            int chunkX = Math.floorDiv(targetX, 16);
            int chunkZ = Math.floorDiv(targetZ, 16);
            simulatedStarts.add(chunkX + "," + chunkZ);
            List<VanillaLootStructureSimulator.SimulatedContainer> targetSimulation = explicitIndex == null
                ? VanillaLootStructureSimulator.simulate(seed, dimension, key, chunkX, chunkZ)
                : VanillaLootStructureSimulator.simulate(seed, dimension, key, chunkX, chunkZ, explicitIndex);
            simulated.addAll(targetSimulation);
            oracle.putAll(readContainers(target, structureId));
        }

        int centerChunkX = Math.floorDiv(scan.get("centerX").getAsInt(), 16);
        int centerChunkZ = Math.floorDiv(scan.get("centerZ").getAsInt(), 16);
        int scanChunkRadius = Math.ceilDiv(scan.get("radius").getAsInt(), 16);
        int minScanChunkX = centerChunkX - scanChunkRadius;
        int maxScanChunkX = centerChunkX + scanChunkRadius;
        int minScanChunkZ = centerChunkZ - scanChunkRadius;
        int maxScanChunkZ = centerChunkZ + scanChunkRadius;

        Set<String> missingOraclePositions = new HashSet<>(oracle.keySet());
        for (VanillaLootStructureSimulator.SimulatedContainer container : simulated) {
            if (matchesLootTable(structureId, container.lootTableId())
                && withinScan(container, minScanChunkX, maxScanChunkX,
                    minScanChunkZ, maxScanChunkZ)) {
                missingOraclePositions.remove(position(container.x(), container.y(), container.z()));
            }
        }
        if (!missingOraclePositions.isEmpty()) {
            int startMargin = 32;
            List<GeneratedStructure> nearby = VanillaStructurePredictor.predictStructure(
                seed, dimension, structureId,
                minScanChunkX - startMargin, minScanChunkZ - startMargin,
                maxScanChunkX + startMargin, maxScanChunkZ + startMargin);
            for (GeneratedStructure candidate : nearby) {
                String startPosition = candidate.startChunkX + "," + candidate.startChunkZ;
                if (simulatedStarts.contains(startPosition)) continue;
                List<VanillaLootStructureSimulator.SimulatedContainer> candidateSimulation = explicitIndex == null
                    ? VanillaLootStructureSimulator.simulate(seed, dimension, key,
                        candidate.startChunkX, candidate.startChunkZ)
                    : VanillaLootStructureSimulator.simulate(seed, dimension, key,
                        candidate.startChunkX, candidate.startChunkZ, explicitIndex);
                boolean contributes = candidateSimulation.stream()
                    .filter(container -> matchesLootTable(structureId, container.lootTableId()))
                    .filter(container -> withinScan(container, minScanChunkX, maxScanChunkX,
                        minScanChunkZ, maxScanChunkZ))
                    .map(container -> position(container.x(), container.y(), container.z()))
                    .anyMatch(missingOraclePositions::contains);
                if (!contributes) continue;
                simulatedStarts.add(startPosition);
                simulated.addAll(candidateSimulation);
                for (VanillaLootStructureSimulator.SimulatedContainer container : candidateSimulation) {
                    missingOraclePositions.remove(position(container.x(), container.y(), container.z()));
                }
                if (missingOraclePositions.isEmpty()) break;
            }
        }

        Map<String, VanillaLootStructureSimulator.SimulatedContainer> predicted = new HashMap<>();
        int oracleEnchantments = 0;
        for (OracleContainer container : oracle.values()) {
            oracleEnchantments += container.enchantmentEntries;
        }

        for (VanillaLootStructureSimulator.SimulatedContainer container : simulated) {
            if (!matchesLootTable(structureId, container.lootTableId())) continue;
            if (!withinScan(container, minScanChunkX, maxScanChunkX,
                minScanChunkZ, maxScanChunkZ)) continue;
            predicted.put(position(container.x(), container.y(), container.z()), container);
        }
        Set<String> positions = new HashSet<>();
        positions.addAll(oracle.keySet());
        positions.addAll(predicted.keySet());
        for (String position : positions) {
            OracleContainer expected = oracle.get(position);
            VanillaLootStructureSimulator.SimulatedContainer actual = predicted.get(position);
            if (expected == null) {
                differences.add("unexpected predicted container at " + position);
                continue;
            }
            if (actual == null) {
                differences.add("missing predicted container at " + position);
                continue;
            }
            if (!expected.lootTable.equals(actual.lootTableId())) {
                differences.add(position + " loot table predicted=" + actual.lootTableId()
                    + " oracle=" + expected.lootTable);
            }
            if (expected.lootSeed == null) {
                differences.add(position + " oracle has no loot-table seed");
            } else if (expected.lootSeed.longValue() != actual.lootSeed()) {
                differences.add(position + " loot seed predicted=" + actual.lootSeed()
                    + " oracle=" + expected.lootSeed);
            }
            Map<String, Integer> predictedItems = predictItems(actual, simulated);
            if (!expected.items.equals(predictedItems)) {
                differences.add(position + " item totals predicted=" + predictedItems
                    + " oracle=" + expected.items);
            }
            if (!expected.enchantments.isEmpty()) {
                Map<String, Integer> predictedEnchantments = predictEnchantments(actual, simulated);
                if (!expected.enchantments.equals(predictedEnchantments)) {
                    differences.add(position + " enchantments predicted=" + predictedEnchantments
                        + " oracle=" + expected.enchantments);
                }
            }
        }
        return new Result(predictedStructureLocations, targets.size(), predicted.size(), oracle.size(),
            oracleEnchantments, List.copyOf(differences));
    }

    private static Map<String, OracleContainer> readContainers(JsonObject structure, String structureId) {
        Map<String, OracleContainer> result = new HashMap<>();
        JsonArray chests = structure.has("chests") ? structure.getAsJsonArray("chests") : new JsonArray();
        for (JsonElement element : chests) {
            JsonObject chest = element.getAsJsonObject();
            String table = string(chest, "lootTable");
            if (table == null || !matchesLootTable(structureId, table)) continue;
            String position = position(chest.get("x").getAsInt(), chest.get("y").getAsInt(), chest.get("z").getAsInt());
            Long lootSeed = chest.has("lootTableSeed") && !chest.get("lootTableSeed").isJsonNull()
                ? chest.get("lootTableSeed").getAsLong() : null;
            int enchantmentEntries = 0;
            Map<String, Integer> items = new HashMap<>();
            Map<String, Integer> enchantmentsById = new HashMap<>();
            JsonArray itemArray = chest.has("items") ? chest.getAsJsonArray("items") : new JsonArray();
            for (JsonElement itemElement : itemArray) {
                JsonObject item = itemElement.getAsJsonObject();
                String itemId = string(item, "itemId");
                if (itemId == null || "minecraft:air".equals(itemId)) continue;
                items.merge(itemId, item.get("count").getAsInt(), Integer::sum);
                JsonArray enchantments = item.has("enchantments") ? item.getAsJsonArray("enchantments") : new JsonArray();
                JsonArray levels = item.has("enchantmentLevels") ? item.getAsJsonArray("enchantmentLevels") : new JsonArray();
                for (Map.Entry<String, Integer> entry : parseEnchantments(item).entrySet()) {
                    enchantmentsById.merge(entry.getKey(), entry.getValue(), Integer::sum);
                    enchantmentEntries++;
                }
            }
            result.put(position, new OracleContainer(table, lootSeed, Map.copyOf(items), enchantmentEntries,
                Map.copyOf(enchantmentsById)));
        }
        return result;
    }

    private static Map<String, Integer> predictItems(VanillaLootStructureSimulator.SimulatedContainer target,
                                                      List<VanillaLootStructureSimulator.SimulatedContainer> all) {
        Map<String, Integer> result = new HashMap<>();
        for (VanillaLootStructureSimulator.SimulatedContainer member : sharedChestMembers(target, all)) {
            result.putAll(mergeCounts(result, predictItems(member.lootTableId(), member.lootSeed())));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Integer> predictItems(String tableId, long seed) {
        return aggregate(simulateItems(tableId, seed));
    }

    private static List<ItemLoot> simulateItems(String tableId, long seed) {
        List<ItemLoot> vanilla = LootTableSimulator.simulateVanilla(tableId, seed);
        if (vanilla != null) return vanilla;
        var table = VanillaLootTables.get(tableId);
        if (table == null) return List.of();
        return LootTableSimulator.simulate(table, seed, 64);
    }

    private static Map<String, Integer> predictEnchantments(
        VanillaLootStructureSimulator.SimulatedContainer target,
        List<VanillaLootStructureSimulator.SimulatedContainer> all) {
        Map<String, Integer> result = new HashMap<>();
        for (VanillaLootStructureSimulator.SimulatedContainer member : sharedChestMembers(target, all)) {
            for (ItemLoot item : simulateItems(member.lootTableId(), member.lootSeed())) {
                for (Map.Entry<String, Integer> entry : parseSummary(item.enchantmentSummary()).entrySet()) {
                    result.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, Integer> mergeCounts(Map<String, Integer> left, Map<String, Integer> right) {
        Map<String, Integer> merged = new HashMap<>(left);
        right.forEach((id, count) -> merged.merge(id, count, Integer::sum));
        return merged;
    }

    /** Paper exposes both halves of a double chest and the combined inventory
     * contains one loot roll from each half. */
    private static List<VanillaLootStructureSimulator.SimulatedContainer> sharedChestMembers(
        VanillaLootStructureSimulator.SimulatedContainer target,
        List<VanillaLootStructureSimulator.SimulatedContainer> all) {
        List<VanillaLootStructureSimulator.SimulatedContainer> members = new ArrayList<>();
        members.add(target);
        for (VanillaLootStructureSimulator.SimulatedContainer candidate : all) {
            if (candidate == target) continue;
            if (!candidate.lootTableId().equals(target.lootTableId())) continue;
            if (candidate.y() != target.y()) continue;
            boolean adjacent = (candidate.x() == target.x() && Math.abs(candidate.z() - target.z()) == 1)
                || (candidate.z() == target.z() && Math.abs(candidate.x() - target.x()) == 1);
            if (!adjacent) continue;
            members.add(candidate);
        }
        return members;
    }

    private static Map<String, Integer> aggregate(List<ItemLoot> items) {
        Map<String, Integer> result = new HashMap<>();
        for (ItemLoot item : items) {
            result.merge(item.itemId(), item.minCount(), Integer::sum);
        }
        return Map.copyOf(result);
    }

    private static Map<String, Integer> parseEnchantments(JsonObject item) {
        Map<String, Integer> result = new HashMap<>();
        if (item.has("enchantments") && item.get("enchantments").isJsonArray()) {
            JsonArray ids = item.getAsJsonArray("enchantments");
            JsonArray levels = item.has("enchantmentLevels") && item.get("enchantmentLevels").isJsonArray()
                ? item.getAsJsonArray("enchantmentLevels") : new JsonArray();
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i).getAsString();
                int level = i < levels.size() ? levels.get(i).getAsInt() : 1;
                result.merge(id, level, Integer::sum);
            }
        }
        String nbt = string(item, "nbt");
        if (nbt != null) {
            Matcher matcher = NBT_ENCHANTMENTS.matcher(nbt);
            if (matcher.find()) {
                for (String entry : matcher.group(1).split(",")) {
                    String[] parts = entry.trim().split("=");
                    if (parts.length == 2) {
                        try {
                            result.merge(parts[0].trim(), Integer.parseInt(parts[1].trim()), Integer::sum);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }
        return result;
    }

    private static Map<String, Integer> parseSummary(String summary) {
        Map<String, Integer> result = new HashMap<>();
        if (summary == null || summary.isBlank()) return result;
        for (String part : summary.split(",")) {
            String trimmed = part.trim();
            int split = trimmed.lastIndexOf(' ');
            if (split <= 0 || split == trimmed.length() - 1) continue;
            String path = trimmed.substring(0, split).trim().toLowerCase().replace(' ', '_');
            int level = romanLevel(trimmed.substring(split + 1));
            if (level > 0) result.merge("minecraft:" + path, level, Integer::sum);
        }
        return result;
    }

    private static int romanLevel(String value) {
        return switch (value) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            case "VI" -> 6;
            case "VII" -> 7;
            case "VIII" -> 8;
            case "IX" -> 9;
            case "X" -> 10;
            default -> {
                try { yield Integer.parseInt(value); }
                catch (NumberFormatException ignored) { yield 0; }
            }
        };
    }

    private static ResourceKey<Structure> structureKey(String id) {
        return switch (id) {
            case "minecraft:desert_pyramid" -> BuiltinStructures.DESERT_PYRAMID;
            case "minecraft:jungle_pyramid" -> BuiltinStructures.JUNGLE_TEMPLE;
            case "minecraft:stronghold" -> BuiltinStructures.STRONGHOLD;
            case "minecraft:shipwreck" -> BuiltinStructures.SHIPWRECK;
            case "minecraft:pillager_outpost" -> BuiltinStructures.PILLAGER_OUTPOST;
            case "minecraft:buried_treasure" -> BuiltinStructures.BURIED_TREASURE;
            case "minecraft:bastion_remnant" -> BuiltinStructures.BASTION_REMNANT;
            case "minecraft:mineshaft" -> BuiltinStructures.MINESHAFT;
            case "minecraft:fortress" -> BuiltinStructures.FORTRESS;
            case "minecraft:end_city" -> BuiltinStructures.END_CITY;
            case "minecraft:ancient_city" -> BuiltinStructures.ANCIENT_CITY;
            case "minecraft:trial_chambers" -> BuiltinStructures.TRIAL_CHAMBERS;
            case "minecraft:mansion" -> BuiltinStructures.WOODLAND_MANSION;
            case "minecraft:ruined_portal" -> BuiltinStructures.RUINED_PORTAL_STANDARD;
            default -> throw new IllegalArgumentException("Unsupported oracle structure: " + id);
        };
    }

    private static boolean matchesLootTable(String structureId, String tableId) {
        return switch (structureId) {
            case "minecraft:desert_pyramid" -> tableId.startsWith("minecraft:chests/desert_pyramid");
            case "minecraft:jungle_pyramid" -> tableId.startsWith("minecraft:chests/jungle_temple");
            case "minecraft:stronghold" -> tableId.startsWith("minecraft:chests/stronghold_");
            case "minecraft:shipwreck" -> tableId.startsWith("minecraft:chests/shipwreck_");
            case "minecraft:pillager_outpost" -> tableId.startsWith("minecraft:chests/pillager_outpost");
            case "minecraft:buried_treasure" -> tableId.startsWith("minecraft:chests/buried_treasure");
            case "minecraft:bastion_remnant" -> tableId.startsWith("minecraft:chests/bastion_");
            case "minecraft:mineshaft" -> tableId.startsWith("minecraft:chests/abandoned_mineshaft");
            case "minecraft:fortress" -> tableId.startsWith("minecraft:chests/nether_bridge");
            case "minecraft:end_city" -> tableId.startsWith("minecraft:chests/end_city_treasure");
            case "minecraft:ancient_city" -> tableId.startsWith("minecraft:chests/ancient_city");
            case "minecraft:trial_chambers" -> tableId.startsWith("minecraft:chests/trial_chambers/")
                || tableId.startsWith("minecraft:dispensers/trial_chambers/")
                || tableId.startsWith("minecraft:pots/trial_chambers/");
            case "minecraft:mansion" -> tableId.startsWith("minecraft:chests/woodland_mansion");
            default -> tableId.startsWith("minecraft:chests/");
        };
    }

    private static int dimension(String id) {
        return switch (id) {
            case "minecraft:the_nether" -> -1;
            case "minecraft:the_end" -> 1;
            default -> 0;
        };
    }

    private static String position(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static boolean withinScan(VanillaLootStructureSimulator.SimulatedContainer container,
                                      int minChunkX, int maxChunkX,
                                      int minChunkZ, int maxChunkZ) {
        int chunkX = Math.floorDiv(container.x(), 16);
        int chunkZ = Math.floorDiv(container.z(), 16);
        return chunkX >= minChunkX && chunkX <= maxChunkX
            && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    public record Result(int predictedStructureLocations, int oracleStructureLocations,
                         int predictedContainers, int oracleContainers, int oracleEnchantments,
                         List<String> differences) {
        public Result(int predictedContainers, int oracleContainers, List<String> differences) {
            this(0, 0, predictedContainers, oracleContainers, 0, differences);
        }

        public boolean exactMatch() {
            return differences.isEmpty();
        }

        public String summary() {
            return "oracle_case_match=" + exactMatch()
                + " predicted_structure_locations=" + predictedStructureLocations
                + " oracle_structure_locations=" + oracleStructureLocations
                + " predicted_containers=" + predictedContainers
                + " oracle_containers=" + oracleContainers
                + " oracle_enchantment_entries=" + oracleEnchantments
                + " differences=" + differences.size();
        }
    }

    private record OracleContainer(String lootTable, Long lootSeed, Map<String, Integer> items,
                                   int enchantmentEntries, Map<String, Integer> enchantments) {
    }
}
