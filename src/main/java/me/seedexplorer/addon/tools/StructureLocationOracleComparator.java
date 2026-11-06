package me.seedexplorer.addon.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compares Paper /locate results with independently predicted map locations. */
public final class StructureLocationOracleComparator {
    private StructureLocationOracleComparator() {
    }

    public static Result compare(Path oraclePath, List<String> expectedStructureIds,
                                 String configuredDimension) throws Exception {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(oraclePath)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }

        long seed = root.get("seed").getAsLong();
        JsonArray entries = root.has("structures") && root.get("structures").isJsonArray()
            ? root.getAsJsonArray("structures") : new JsonArray();
        Map<String, JsonObject> locatedById = new HashMap<>();
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            String id = string(entry, "structureId");
            if (id != null) locatedById.put(id, entry);
        }

        int matched = 0;
        List<String> differences = new ArrayList<>();
        for (String structureId : expectedStructureIds) {
            JsonObject located = locatedById.get(structureId);
            if (located == null) {
                differences.add("Paper oracle has no locate result for " + structureId);
                continue;
            }
            if (located.has("found") && !located.get("found").getAsBoolean()) {
                differences.add("Paper did not locate " + structureId);
                continue;
            }
            if (!located.has("x") || located.get("x").isJsonNull()
                || !located.has("z") || located.get("z").isJsonNull()) {
                differences.add("Paper locate result has no position for " + structureId);
                continue;
            }

            String dimensionId = string(located, "dimension");
            if (dimensionId == null) dimensionId = configuredDimension;
            int x = located.get("x").getAsInt();
            int z = located.get("z").getAsInt();
            if (predictsLocation(seed, dimension(dimensionId), structureId, x, z)) {
                matched++;
            } else {
                differences.add("missing predicted " + structureId + " location at " + x + "," + z);
            }
        }
        return new Result(matched, expectedStructureIds.size(), List.copyOf(differences));
    }

    static boolean predictsLocation(long seed, int dimension, String structureId,
                                    int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        StructureType expectedType = structureType(structureId, dimension);
        return VanillaStructurePredictor.predictStructure(
                seed, dimension, structureId,
                chunkX - 1, chunkZ - 1, chunkX + 1, chunkZ + 1)
            .stream()
            .filter(candidate -> candidate.type == expectedType)
            .filter(candidate -> variantMatches(structureId, candidate))
            .anyMatch(candidate -> candidate.x == blockX && candidate.z == blockZ);
    }

    private static boolean variantMatches(String structureId, GeneratedStructure candidate) {
        return switch (structureId) {
            case "minecraft:village_plains", "minecraft:village_desert",
                 "minecraft:village_savanna", "minecraft:village_snowy",
                 "minecraft:village_taiga", "minecraft:shipwreck_beached",
                 "minecraft:ruined_portal_desert", "minecraft:ruined_portal_jungle",
                 "minecraft:ruined_portal_swamp", "minecraft:ruined_portal_mountain",
                 "minecraft:ruined_portal_ocean", "minecraft:ruined_portal_nether",
                 "minecraft:ocean_ruin_warm", "minecraft:ocean_ruin_cold",
                 "minecraft:mineshaft", "minecraft:mineshaft_mesa" ->
                structureId.equals(candidate.variant);
            default -> true;
        };
    }

    private static StructureType structureType(String structureId, int dimension) {
        if (structureId.startsWith("minecraft:village")) return StructureType.VILLAGE;
        if (structureId.startsWith("minecraft:shipwreck")) return StructureType.SHIPWRECK;
        if (structureId.equals("minecraft:ruined_portal_nether")) return StructureType.NETHER_RUINED_PORTAL;
        if (structureId.startsWith("minecraft:ruined_portal")) return StructureType.RUINED_PORTAL;
        if (structureId.startsWith("minecraft:ocean_ruin")) return StructureType.OCEAN_RUIN;
        if (structureId.startsWith("minecraft:mineshaft")) return StructureType.MINESHAFT;
        return switch (structureId) {
            case "minecraft:desert_pyramid" -> StructureType.DESERT_PYRAMID;
            case "minecraft:jungle_pyramid" -> StructureType.JUNGLE_TEMPLE;
            case "minecraft:swamp_hut" -> StructureType.WITCH_HUT;
            case "minecraft:stronghold" -> StructureType.STRONGHOLD;
            case "minecraft:pillager_outpost" -> StructureType.OUTPOST;
            case "minecraft:buried_treasure" -> StructureType.TREASURE;
            case "minecraft:bastion_remnant" -> StructureType.BASTION;
            case "minecraft:fortress" -> StructureType.FORTRESS;
            case "minecraft:nether_fossil" -> StructureType.NETHER_FOSSIL;
            case "minecraft:end_city" -> StructureType.END_CITY;
            case "minecraft:ancient_city" -> StructureType.ANCIENT_CITY;
            case "minecraft:trial_chambers" -> StructureType.TRIAL_CHAMBER;
            case "minecraft:igloo" -> StructureType.IGLOO;
            case "minecraft:monument" -> StructureType.MONUMENT;
            case "minecraft:mansion" -> StructureType.MANSION;
            case "minecraft:trail_ruins" -> StructureType.TRAIL_RUINS;
            default -> throw new IllegalArgumentException(
                "Unsupported oracle structure location in dimension " + dimension + ": " + structureId);
        };
    }

    private static int dimension(String id) {
        return switch (id) {
            case "minecraft:the_nether" -> -1;
            case "minecraft:the_end" -> 1;
            default -> 0;
        };
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
            ? object.get(key).getAsString() : null;
    }

    public record Result(int matchedLocations, int oracleLocations, List<String> differences) {
        public boolean exactMatch() {
            return differences.isEmpty() && matchedLocations == oracleLocations;
        }

        public String summary() {
            return "location_oracle_match=" + exactMatch()
                + " matched_locations=" + matchedLocations
                + " oracle_locations=" + oracleLocations
                + " differences=" + differences.size();
        }
    }
}
