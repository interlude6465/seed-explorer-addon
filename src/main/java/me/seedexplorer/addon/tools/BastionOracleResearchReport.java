package me.seedexplorer.addon.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Research-only bastion oracle comparison. This intentionally does not feed the
 * product validation set because bastions are jigsaw structures and offline
 * placement parity is not established.
 */
public final class BastionOracleResearchReport {
    private static final PrintStream OUT = System.out;
    private static final String EXPECTED_SHA256 =
        "8a408b9ae4a3c0a84113cd82b4dccebd814efe8e5ea73b80839c4455bec9392a";

    private BastionOracleResearchReport() {
    }

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            Path projectRoot = args.length > 0 ? Path.of(args[0]) : Path.of(".");
            Path oracle = projectRoot.resolve("validation").resolve("oracles")
                .resolve("lootprobe-bastion-seed-5674700730434827097.json");
            run(oracle);
        } catch (Throwable t) {
            OUT.println("bastion_research_failed=" + t.getClass().getName() + " " + t.getMessage());
            t.printStackTrace(OUT);
            System.exit(1);
        }
    }

    private static void run(Path oracle) throws Exception {
        if (!Files.isRegularFile(oracle)) {
            throw new IllegalStateException("Missing bastion oracle: " + oracle.toAbsolutePath());
        }
        String actualSha256 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(oracle)));
        OUT.println("oracle_sha256=" + actualSha256);
        OUT.println("oracle_sha256_pinned=" + actualSha256.equals(EXPECTED_SHA256));

        JsonObject root;
        try (var reader = Files.newBufferedReader(oracle)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }

        long seed = root.get("seed").getAsLong();
        JsonObject located = root.getAsJsonArray("structures").get(0).getAsJsonObject();
        int blockX = located.get("x").getAsInt();
        int blockZ = located.get("z").getAsInt();
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);

        Map<String, Long> oracleChests = readOracleChests(root);
        var simulated = VanillaLootStructureSimulator.simulate(
            seed, -1, BuiltinStructures.BASTION_REMNANT, chunkX, chunkZ);

        int seedMatches = 0;
        for (var chest : simulated) {
            Long oracleSeed = oracleChests.get(chest.x() + "," + chest.y() + "," + chest.z());
            if (oracleSeed != null && oracleSeed == chest.lootSeed()) seedMatches++;
        }

        OUT.println("result=RESEARCH_ONLY bastion_not_product_validated");
        OUT.println("seed=" + seed + " located_block=" + blockX + "," + blockZ
            + " simulated_start_chunk=" + chunkX + "," + chunkZ);
        OUT.println("oracle_chests=" + oracleChests.size()
            + " simulated_chests=" + simulated.size()
            + " seed_matches=" + seedMatches + "/" + simulated.size());
        if (simulated.isEmpty()) {
            OUT.println("limitation=jigsaw_template_placement_not_captured_offline");
        }
    }

    private static Map<String, Long> readOracleChests(JsonObject root) {
        Map<String, Long> result = new HashMap<>();
        JsonObject scan = root.getAsJsonObject("regionScan");
        for (JsonElement structureElement : scan.getAsJsonArray("structures")) {
            JsonObject structure = structureElement.getAsJsonObject();
            for (JsonElement chestElement : structure.getAsJsonArray("chests")) {
                JsonObject chest = chestElement.getAsJsonObject();
                String table = chest.get("lootTable").isJsonNull() ? null : chest.get("lootTable").getAsString();
                if (table == null || !table.startsWith("minecraft:chests/bastion_")) continue;
                String pos = chest.get("x").getAsInt() + "," + chest.get("y").getAsInt()
                    + "," + chest.get("z").getAsInt();
                result.put(pos, chest.get("lootTableSeed").getAsLong());
            }
        }
        return Map.copyOf(result);
    }
}
