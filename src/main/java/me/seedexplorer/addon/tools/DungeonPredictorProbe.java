package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.structures.DungeonPredictor;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Parallel/caching regression for the production dungeon scanner. */
public final class DungeonPredictorProbe {
    private DungeonPredictorProbe() {
    }

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            WorldgenEngine.offlineRegistryAccess();
            long seed = args.length > 0 ? Long.parseLong(args[0]) : -5330673357612662400L;
            int chunkX = args.length > 1 ? Integer.parseInt(args[1]) : -38;
            int chunkZ = args.length > 2 ? Integer.parseInt(args[2]) : -18;

            DungeonPredictor.ScanResult scan = DungeonPredictor.scanAround(seed, chunkX, chunkZ,
                1, 0, null, () -> false, ignored -> {});
            var exactChunk = scan.dungeons().stream()
                .filter(dungeon -> dungeon.structure().startChunkX == chunkX
                    && dungeon.structure().startChunkZ == chunkZ)
                .toList();
            check(exactChunk.size() == 2, "expected two mineshaft-opened dungeons, got " + exactChunk.size());
            check(exactChunk.stream().anyMatch(dungeon -> dungeon.structure().x == -606
                    && dungeon.structure().y == -20 && dungeon.structure().z == -275
                    && dungeon.structure().variant.equals("minecraft:skeleton")),
                "missing skeleton dungeon");
            check(exactChunk.stream().anyMatch(dungeon -> dungeon.structure().x == -599
                    && dungeon.structure().y == -26 && dungeon.structure().z == -282
                    && dungeon.structure().variant.equals("minecraft:zombie")),
                "missing zombie dungeon");
            Set<Long> seeds = exactChunk.stream().flatMap(dungeon -> dungeon.simulated().containers().stream())
                .map(container -> container.lootSeed()).collect(java.util.stream.Collectors.toSet());
            check(seeds.equals(Set.of(-8144953013160125626L, 3527612760189953148L,
                8433468082816297385L)), "loot seed mismatch: " + seeds);
            check(DungeonPredictor.scanChunk(seed, chunkX, chunkZ).size() == 2,
                "cached chunk lookup changed the result");
            report("dungeon_predictor_probe=PASS checked=" + scan.checkedChunks());
        } catch (Throwable throwable) {
            report("dungeon_predictor_probe=FAIL " + throwable.getClass().getName()
                + ": " + throwable.getMessage());
            throwable.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void report(String line) {
        System.err.println(line);
        try {
            Files.createDirectories(Path.of("build"));
            Files.writeString(Path.of("build", "dungeon-predictor-probe.txt"), line + System.lineSeparator());
        } catch (Exception ignored) {
        }
    }
}
