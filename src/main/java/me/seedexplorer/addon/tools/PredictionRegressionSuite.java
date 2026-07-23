package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.worldgen.GeneratedTerrainHeightmap;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Fail-fast, version-pinned regression suite for every strict real-server loot
 * oracle currently accepted by the product, plus the controlled terrain case
 * that exposed missing vanilla carver tags.
 */
public final class PredictionRegressionSuite {
    private static final PrintStream OUT = System.out;
    private static final String MINECRAFT_VERSION = "26.1.2";
    private static final int EXPECTED_CARVER_REPLACEABLES = 52;
    private static final String TERRAIN_HEIGHT_SHA256 =
        "0cc0e2ff0a42b5daeed1649bb54888a1823c5b6d8f013de767fb9b2da1feb2d8";

    private PredictionRegressionSuite() {
    }

    public static void main(String[] args) throws Exception {
        Path projectRoot = args.length > 0 ? Path.of(args[0]) : Path.of(".");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();
        String runtimeVersion = SharedConstants.getCurrentVersion().name();
        if (!runtimeVersion.equals(MINECRAFT_VERSION)) {
            failures.add("Minecraft version expected=" + MINECRAFT_VERSION
                + " actual=" + runtimeVersion);
        }

        int replaceableCount = 0;
        for (var ignored : WorldgenEngine.offlineRegistryAccess()
            .lookupOrThrow(Registries.BLOCK)
            .getTagOrEmpty(BlockTags.OVERWORLD_CARVER_REPLACEABLES)) {
            replaceableCount++;
        }
        OUT.println("carver_replaceables=" + replaceableCount);
        if (replaceableCount != EXPECTED_CARVER_REPLACEABLES) {
            failures.add("#minecraft:overworld_carver_replaceables expected="
                + EXPECTED_CARVER_REPLACEABLES + " actual=" + replaceableCount);
        }

        GeneratedTerrainHeightmap terrain =
            new GeneratedTerrainHeightmap(123456789L, 0, List.of());
        MessageDigest terrainDigest = MessageDigest.getInstance("SHA-256");
        for (int x = -2512; x <= -2481; x++) {
            for (int z = -2000; z <= -1969; z++) {
                updateDigest(terrainDigest, terrain.firstFreeHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
            }
        }
        String actualTerrainHash = HexFormat.of().formatHex(terrainDigest.digest());
        OUT.println("terrain_height_sha256=" + actualTerrainHash);
        if (!actualTerrainHash.equals(TERRAIN_HEIGHT_SHA256)) {
            failures.add("controlled terrain hash expected=" + TERRAIN_HEIGHT_SHA256
                + " actual=" + actualTerrainHash);
        }
        int criticalHeight = terrain.firstFreeHeight(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, -2511, -1980);
        OUT.println("critical_carved_height=" + criticalHeight);
        if (criticalHeight != 67) {
            failures.add("critical carved height expected=67 actual=" + criticalHeight);
        }

        validateOracle(projectRoot, 4717879387438598985L, -21, -370,
            "lootprobe-desert-seed-v2.json",
            "265da951f6c392f0402527db5d96a5cfddeadef373a2cd59993926d7a4594393", failures);
        validateOracle(projectRoot, 42L, -86, -110,
            "lootprobe-seed42-validated.json",
            "674ece00b6784b85d120fa958b3d47dfc4652d6bc1135f1fb73bba5959423996", failures);
        validateOracle(projectRoot, 123456789L, -157, -125,
            "lootprobe-seed123456789.json",
            "e3962de589e40f0d24e58c4e7a873b5642f2ddece8b77ebe9386dc9d3155287a", failures);
        validateOracle(projectRoot, 98765432123456789L, -119, 150,
            "lootprobe-seed98765432123456789.json",
            "48b8a0bc7cb5f3bdda74e82bbd96f5021b40869b5ceef8039e3bf0b2132c9225", failures);
        validateStronghold(projectRoot, failures);
        validateShipwreck(projectRoot, failures);
        validateStructureChests(projectRoot, 0L, 36, 103,
            "lootprobe-outpost-seed0.json",
            "1895495474ebfef6c77fa101046929bda465d96a4bb3b09074c3af46a97ecc7b",
            net.minecraft.world.level.levelgen.structure.BuiltinStructures.PILLAGER_OUTPOST,
            "minecraft:chests/pillager_outpost", "outpost", 9, failures);
        validateBuriedTreasure(projectRoot, failures);
        validateJungleTemple(projectRoot, failures);
        validateBastion(projectRoot, failures);

        OUT.println("prediction_regression_pass=" + failures.isEmpty()
            + " failures=" + failures.size());
        failures.forEach(failure -> OUT.println("  " + failure));
        if (!failures.isEmpty()) System.exit(2);
    }

    private static void validateOracle(Path projectRoot, long seed,
                                       int chunkX, int chunkZ, String fileName,
                                       String expectedSha256,
                                       List<String> failures) throws Exception {
        Path oracle = projectRoot.resolve("validation").resolve("oracles").resolve(fileName);
        if (!Files.isRegularFile(oracle)) {
            failures.add("missing oracle file " + oracle.toAbsolutePath());
            return;
        }
        String actualSha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(oracle)));
        if (!actualSha256.equals(expectedSha256)) {
            failures.add(fileName + " SHA-256 expected=" + expectedSha256
                + " actual=" + actualSha256
                + " (oracle was changed; recapture from Paper and review before accepting it)");
            return;
        }
        LootProbeOracleValidator.Validation validation =
            LootProbeOracleValidator.validate(seed, chunkX, chunkZ, oracle);
        OUT.println("oracle=" + fileName + " " + validation.summary());
        for (String difference : validation.differences()) {
            failures.add(fileName + ": " + difference);
        }
    }

    private static void validateStronghold(Path projectRoot, List<String> failures) throws Exception {
        Path oracle = projectRoot.resolve("validation").resolve("oracles")
            .resolve("lootprobe-stronghold-seed0.json");
        String expectedSha256 = "1fc4b08d71ea352b7ab6ec0e8d5a60ad2284f6f92bd8084452212b0746798155";
        String actualSha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(oracle)));
        if (!actualSha256.equals(expectedSha256)) {
            failures.add("stronghold oracle SHA-256 mismatch expected=" + expectedSha256
                + " actual=" + actualSha256);
            return;
        }

        JsonObject root;
        try (var reader = Files.newBufferedReader(oracle)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }

        // Read oracle chests
        Map<String, Long> oracleMap = new HashMap<>();
        JsonObject scan = root.getAsJsonObject("regionScan");
        for (JsonElement se : scan.getAsJsonArray("structures")) {
            JsonObject struct = se.getAsJsonObject();
            for (JsonElement ce : struct.getAsJsonArray("chests")) {
                JsonObject chest = ce.getAsJsonObject();
                String table = chest.get("lootTable").getAsString();
                if (table != null && table.contains("stronghold")) {
                    String pos = chest.get("x").getAsInt() + "," + chest.get("y").getAsInt()
                        + "," + chest.get("z").getAsInt();
                    oracleMap.put(pos, chest.get("lootTableSeed").getAsLong());
                }
            }
        }

        // Run simulation
        var simulated = VanillaLootStructureSimulator.simulate(
            0L, net.minecraft.world.level.levelgen.structure.BuiltinStructures.STRONGHOLD, 125, 57);

        int matches = 0;
        int total = 0;
        for (var chest : simulated) {
            String pos = chest.x() + "," + chest.y() + "," + chest.z();
            Long oracleSeed = oracleMap.get(pos);
            total++;
            if (oracleSeed != null && oracleSeed == chest.lootSeed()) {
                matches++;
            } else {
                failures.add("stronghold: chest at " + pos
                    + " seed mismatch sim=" + chest.lootSeed()
                    + " oracle=" + (oracleSeed != null ? oracleSeed : "MISSING"));
            }
        }
        OUT.println("oracle=stronghold simulated_chests=" + simulated.size()
            + " oracle_chests=" + oracleMap.size() + " seed_matches=" + matches + "/" + total);
        if (matches != total || total != oracleMap.size()) {
            failures.add("stronghold: " + matches + "/" + total + " seeds match, oracle has "
                + oracleMap.size() + " chests");
        }
    }

    private static void validateStructureChests(Path projectRoot, long seed,
                                                   int chunkX, int chunkZ,
                                                   String fileName, String expectedSha256,
                                                   net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.structure.Structure> structureKey,
                                                   String lootTablePrefix, String label,
                                                   int decorationIndex,
                                                   List<String> failures) throws Exception {
        Path oracle = projectRoot.resolve("validation").resolve("oracles").resolve(fileName);
        if (!Files.isRegularFile(oracle)) {
            failures.add("missing oracle file " + oracle.toAbsolutePath());
            return;
        }
        String actualSha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(oracle)));
        if (!actualSha256.equals(expectedSha256)) {
            failures.add(fileName + " SHA-256 expected=" + expectedSha256
                + " actual=" + actualSha256);
            return;
        }

        JsonObject root;
        try (var reader = Files.newBufferedReader(oracle)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }

        Map<String, Long> oracleMap = new HashMap<>();
        JsonObject scan = root.getAsJsonObject("regionScan");
        for (JsonElement se : scan.getAsJsonArray("structures")) {
            JsonObject struct = se.getAsJsonObject();
            for (JsonElement ce : struct.getAsJsonArray("chests")) {
                JsonObject chest = ce.getAsJsonObject();
                String table = chest.get("lootTable").isJsonNull() ? null : chest.get("lootTable").getAsString();
                if (table != null && table.equals(lootTablePrefix)) {
                    String pos = chest.get("x").getAsInt() + "," + chest.get("y").getAsInt()
                        + "," + chest.get("z").getAsInt();
                    oracleMap.put(pos, chest.get("lootTableSeed").getAsLong());
                }
            }
        }

        var simulated = VanillaLootStructureSimulator.simulate(seed, structureKey, chunkX, chunkZ, decorationIndex);

        int matches = 0;
        int total = 0;
        for (var chest : simulated) {
            if (!chest.lootTableId().equals(lootTablePrefix)) continue;
            String pos = chest.x() + "," + chest.y() + "," + chest.z();
            Long oracleSeed = oracleMap.get(pos);
            total++;
            if (oracleSeed != null && oracleSeed == chest.lootSeed()) {
                matches++;
            } else {
                failures.add(label + ": chest at " + pos
                    + " seed mismatch sim=" + chest.lootSeed()
                    + " oracle=" + (oracleSeed != null ? oracleSeed : "MISSING"));
            }
        }
        OUT.println("oracle=" + label + " simulated_chests=" + simulated.stream().filter(c -> c.lootTableId().equals(lootTablePrefix)).count()
            + " oracle_chests=" + oracleMap.size() + " seed_matches=" + matches + "/" + total);
        if (matches != total || total != oracleMap.size()) {
            failures.add(label + ": " + matches + "/" + total + " seeds match, oracle has "
                + oracleMap.size() + " chests");
        }
    }

    private static void validateShipwreck(Path projectRoot, List<String> failures) throws Exception {
        Path oracle = projectRoot.resolve("validation").resolve("oracles")
            .resolve("lootprobe-shipwreck-seed0.json");
        String expectedSha256 = "9c2b6f494ce3d6ca4c900e5f7a4e2083665e300642016c4fb5baa96b11142596";
        String actualSha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(oracle)));
        if (!actualSha256.equals(expectedSha256)) {
            failures.add("shipwreck oracle SHA-256 mismatch expected=" + expectedSha256 + " actual=" + actualSha256);
            return;
        }
        JsonObject root;
        try (var reader = Files.newBufferedReader(oracle)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        // Oracle shipwreck at chunk (1,-44): 3 chests with shipwreck_* loot
        long[][] expected = {
            {20, 40, -696, 4669758454572194363L},
            {21, 40, -686, -7834760092081062974L},
            {22, 42, -680, 5201495591131938868L}
        };
        var simulated = VanillaLootStructureSimulator.simulate(
            0L, net.minecraft.world.level.levelgen.structure.BuiltinStructures.SHIPWRECK, 1, -44);
        int matches = 0;
        for (var chest : simulated) {
            for (long[] exp : expected) {
                if (chest.x() == (int) exp[0] && chest.y() == (int) exp[1] && chest.z() == (int) exp[2]
                    && chest.lootSeed() == exp[3]) {
                    matches++;
                    break;
                }
            }
        }
        OUT.println("oracle=shipwreck simulated_chests=" + simulated.size()
            + " oracle_chests=" + expected.length + " seed_matches=" + matches + "/" + expected.length);
        if (matches != expected.length || simulated.size() != expected.length) {
            failures.add("shipwreck: " + matches + "/" + expected.length + " seeds match, simulated has "
                + simulated.size() + " chests");
        }
    }

    private static void validateBuriedTreasure(Path projectRoot, List<String> failures) throws Exception {
        Path oracle = projectRoot.resolve("validation").resolve("oracles")
            .resolve("lootprobe-treasure-seed0.json");
        String expectedSha256 = "72efa23610d5eae789dbf0af272dffd0349a360210da91989f9f3ef946839917";
        String actualSha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(oracle)));
        if (!actualSha256.equals(expectedSha256)) {
            failures.add("buried_treasure oracle SHA-256 mismatch expected=" + expectedSha256 + " actual=" + actualSha256);
            return;
        }
        // Oracle buried_treasure at seed 0, chunk (0,-22): single chest at the
        // structure center. The piece scans the OCEAN_FLOOR_WG column downward
        // for a sandstone/stone floor before placing the chest; terrain prefill
        // is required for that scan to find a floor.
        long[] expected = {9, 59, -343, -2156648588641602659L};
        var simulated = VanillaLootStructureSimulator.simulate(
            0L, BuiltinStructures.BURIED_TREASURE, 0, -22);
        int matches = 0;
        for (var chest : simulated) {
            if (chest.x() == (int) expected[0] && chest.y() == (int) expected[1]
                && chest.z() == (int) expected[2] && chest.lootSeed() == expected[3]
                && chest.lootTableId().equals("minecraft:chests/buried_treasure")) {
                matches++;
            }
        }
        OUT.println("oracle=buried_treasure simulated_chests=" + simulated.size()
            + " oracle_chests=" + 1 + " seed_matches=" + matches + "/1");
        if (matches != 1) {
            failures.add("buried_treasure: expected chest (9,59,-343) seed=" + expected[3]
                + " not matched; simulated=" + simulated.size() + " chests"
                + (simulated.isEmpty() ? "" : " first=" + simulated.get(0).x() + ","
                    + simulated.get(0).y() + "," + simulated.get(0).z()
                    + " seed=" + simulated.get(0).lootSeed()));
        }
    }

    private static void validateJungleTemple(Path projectRoot, List<String> failures) throws Exception {
        Path oracle = projectRoot.resolve("validation").resolve("oracles")
            .resolve("lootprobe-jungle-temple-seed-2843430517209339837.json");
        String expectedSha256 = "20e59932339b2f00c8b5c293d68be73dd6ce2963760c5150cb8a423e85579872";
        String actualSha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(oracle)));
        if (!actualSha256.equals(expectedSha256)) {
            failures.add("jungle_temple oracle SHA-256 mismatch expected=" + expectedSha256 + " actual=" + actualSha256);
            return;
        }
        JsonObject root;
        try (var reader = Files.newBufferedReader(oracle)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        // Oracle jungle temple at seed=-2843430517209339837 chunk=(-241,16), index=4
        // 2 chests (jungle_temple) + 2 dispensers (jungle_temple_dispenser) = 4 containers
        long[][] expected = {
            {-3853, 67, 269, -3874679863732177412L},
            {-3848, 66, 267, -273967534976193826L},
            {-3847, 66, 260, -7374455106697323249L},
            {-3847, 67, 267, -7886631764632902857L}
        };
        var simulated = VanillaLootStructureSimulator.simulate(
            -2843430517209339837L, 0,
            BuiltinStructures.JUNGLE_TEMPLE, -241, 16, 4);
        int matches = 0;
        for (var chest : simulated) {
            for (long[] exp : expected) {
                if (chest.x() == (int) exp[0] && chest.y() == (int) exp[1] && chest.z() == (int) exp[2]
                    && chest.lootSeed() == exp[3]) {
                    matches++;
                    break;
                }
            }
        }
        OUT.println("oracle=jungle_temple simulated_chests=" + simulated.size()
            + " oracle_chests=" + expected.length + " seed_matches=" + matches + "/" + expected.length);
        if (matches != expected.length || simulated.size() != expected.length) {
            failures.add("jungle_temple: " + matches + "/" + expected.length + " seeds match, simulated has "
                + simulated.size() + " chests");
        }
    }

    private static void validateBastion(Path projectRoot, List<String> failures) throws Exception {
        // Oracle: Paper 26.1.2 scan of seed -5674700730434827097, bastion at (-176,-208).
        // Decoration index 0 verified 2026-07-18 — exact 64-bit seed match for all 6 chests.
        Path oracle = projectRoot.resolve("validation").resolve("oracles")
            .resolve("lootprobe-bastion-seed-5674700730434827097.json");
        String expectedSha256 = "8a408b9ae4a3c0a84113cd82b4dccebd814efe8e5ea73b80839c4455bec9392a";
        String actualSha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(oracle)));
        if (!actualSha256.equals(expectedSha256)) {
            failures.add("bastion oracle SHA-256 mismatch expected=" + expectedSha256
                + " actual=" + actualSha256);
            return;
        }

        // Ground-truth loot seeds from the oracle (position → seed).
        Map<String, Long> oracleMap = new HashMap<>();
        JsonObject root;
        try (var reader = Files.newBufferedReader(oracle)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        for (var se : root.getAsJsonObject("regionScan").getAsJsonArray("structures")) {
            for (var ce : se.getAsJsonObject().getAsJsonArray("chests")) {
                JsonObject c = ce.getAsJsonObject();
                String table = c.get("lootTable").isJsonNull() ? null : c.get("lootTable").getAsString();
                if (table != null && table.startsWith("minecraft:chests/bastion")) {
                    String pos = c.get("x").getAsInt() + "," + c.get("y").getAsInt()
                        + "," + c.get("z").getAsInt();
                    oracleMap.put(pos, c.get("lootTableSeed").getAsLong());
                }
            }
        }

        // Simulate: seed=-5674700730434827097, Nether (dim=-1), start chunk (-11,-13), index 0.
        var simulated = VanillaLootStructureSimulator.simulate(
            -5674700730434827097L, -1,
            BuiltinStructures.BASTION_REMNANT, -11, -13, 0);

        int matches = 0;
        int total = 0;
        for (var chest : simulated) {
            if (!chest.lootTableId().startsWith("minecraft:chests/bastion")) continue;
            String pos = chest.x() + "," + chest.y() + "," + chest.z();
            Long oracleSeed = oracleMap.get(pos);
            total++;
            if (oracleSeed != null && oracleSeed == chest.lootSeed()) {
                matches++;
            } else {
                failures.add("bastion: chest at " + pos
                    + " seed mismatch sim=" + chest.lootSeed()
                    + " oracle=" + (oracleSeed != null ? oracleSeed : "MISSING"));
            }
        }
        OUT.println("oracle=bastion simulated_chests=" + simulated.size()
            + " oracle_chests=" + oracleMap.size()
            + " seed_matches=" + matches + "/" + total);
        if (matches != total || total != oracleMap.size()) {
            failures.add("bastion: " + matches + "/" + total
                + " seeds match, oracle has " + oracleMap.size() + " chests");
        }
    }

    private static void updateDigest(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
