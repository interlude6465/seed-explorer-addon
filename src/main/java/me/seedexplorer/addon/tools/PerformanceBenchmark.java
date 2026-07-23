package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.BundledLootTableLoader;
import me.seedexplorer.addon.loot.LootTableDef;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.loot.VanillaLootTables;
import me.seedexplorer.addon.worldgen.GeneratedTerrainHeightmap;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PerformanceBenchmark {
    private static final int RUNS = 5;
    private static final long SEED = 42L;
    private static final long PYRAMID_SEED = 4717879387438598985L;
    private static final long STRONGHOLD_SEED = 0L;

    public static void main(String[] args) {
        PrintStream out = System.out;
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            run(out);
        } catch (Throwable t) {
            out.println("benchmark_failed=" + t.getClass().getName() + " " + t.getMessage());
            t.printStackTrace(out);
            System.exit(1);
        }
    }

    private static void run(PrintStream out) {
        ChunkPos pyramidPos = discoverPyramidChunk(PYRAMID_SEED);
        ChunkPos strongholdPos = discoverStrongholdChunk(STRONGHOLD_SEED);

        LootTableDef desertTable = BundledLootTableLoader.parseFromBundled(
            "minecraft:chests/desert_pyramid", "Desert Pyramid");

        out.println("=== Seed Explorer Addon - Performance Benchmark ===");
        out.println("Runs per measurement: " + RUNS);
        out.println("Context:");
        out.println("  desert_pyramid seed=" + PYRAMID_SEED + " chunk=" + pyramidPos.x() + "," + pyramidPos.z());
        out.println("  stronghold     seed=" + STRONGHOLD_SEED + " chunk=" + strongholdPos.x() + "," + strongholdPos.z());
        out.println("  desert_pyramid table loaded: " + (desertTable != null));
        out.println();

        out.printf("%-60s %12s %12s%n", "Benchmark", "Mean (ms)", "StdDev (ms)");
        out.println(String.join("", Collections.nCopies(86, "-")));

        benchmark(out, "TerrainHeightmap.generate() - 1 chunk", () -> {
            var th = new GeneratedTerrainHeightmap(SEED, 0, List.of());
            th.getChunk(new ChunkPos(0, 0));
        });

        benchmark(out, "TerrainHeightmap.generate() - 4 chunks", () -> {
            var th = new GeneratedTerrainHeightmap(SEED, 0, List.of());
            for (int dx = 0; dx < 2; dx++)
                for (int dz = 0; dz < 2; dz++)
                    th.getChunk(new ChunkPos(dx, dz));
        });

        benchmark(out, "TerrainHeightmap.generate() - 16 chunks", () -> {
            var th = new GeneratedTerrainHeightmap(SEED, 0, List.of());
            for (int dx = 0; dx < 4; dx++)
                for (int dz = 0; dz < 4; dz++)
                    th.getChunk(new ChunkPos(dx, dz));
        });

        benchmark(out, "VanillaLootStructureSimulator - desert_pyramid", () ->
            VanillaLootStructureSimulator.simulate(
                PYRAMID_SEED, BuiltinStructures.DESERT_PYRAMID, pyramidPos.x(), pyramidPos.z()));

        benchmark(out, "VanillaLootStructureSimulator - stronghold", () ->
            VanillaLootStructureSimulator.simulate(
                STRONGHOLD_SEED, BuiltinStructures.STRONGHOLD, strongholdPos.x(), strongholdPos.z()));

        if (desertTable != null) {
            benchmark(out, "LootTableSimulator - desert_pyramid x1000", () -> {
                for (int i = 0; i < 1000; i++) {
                    LootTableSimulator.simulate(desertTable, i, 64);
                }
            });
        }

        benchmark(out, "BundledLootTableLoader.loadAll()", () -> {
            VanillaLootTables.resetForTest();
            BundledLootTableLoader.loadAll();
        });

        out.println(String.join("", Collections.nCopies(86, "-")));
        out.println("Benchmark complete.");
    }

    private static void benchmark(PrintStream out, String name, Runnable task) {
        task.run();

        List<Long> times = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            task.run();
            times.add(System.nanoTime() - start);
        }

        double meanNs = times.stream().mapToLong(Long::longValue).average().orElse(0);
        double meanMs = meanNs / 1_000_000.0;

        double variance = times.stream()
            .mapToDouble(t -> { double d = t / 1_000_000.0 - meanMs; return d * d; })
            .average().orElse(0);
        double stddevMs = Math.sqrt(variance);

        out.printf("%-60s %12.3f %12.3f%n", name, meanMs, stddevMs);
        out.flush();
    }

    private static ChunkPos discoverPyramidChunk(long seed) {
        Holder<StructureSet> holder = WorldgenEngine.vanillaLookup()
            .lookupOrThrow(Registries.STRUCTURE_SET)
            .getOrThrow(BuiltinStructureSets.DESERT_PYRAMIDS);
        RandomSpreadStructurePlacement placement =
            (RandomSpreadStructurePlacement) holder.value().placement();
        int spacing = placement.spacing();
        for (int r = 0; r <= 32; r++) {
            for (int rx = -r; rx <= r; rx++) {
                for (int rz = -r; rz <= r; rz++) {
                    if (Math.max(Math.abs(rx), Math.abs(rz)) != r) continue;
                    ChunkPos cp = placement.getPotentialStructureChunk(
                        seed, rx * spacing, rz * spacing);
                    var start = WorldgenEngine.generateStructureStart(
                        seed, 0, BuiltinStructures.DESERT_PYRAMID, cp);
                    if (start.isValid()) return cp;
                }
            }
        }
        throw new RuntimeException("No valid desert pyramid for seed " + seed);
    }

    private static ChunkPos discoverStrongholdChunk(long seed) {
        List<ChunkPos> positions = WorldgenEngine.ringPositions(
            seed, 0, BuiltinStructureSets.STRONGHOLDS);
        if (positions == null || positions.isEmpty()) {
            throw new RuntimeException("No stronghold ring positions for seed " + seed);
        }
        for (ChunkPos pos : positions) {
            var start = WorldgenEngine.generateStructureStart(
                seed, 0, BuiltinStructures.STRONGHOLD, pos);
            if (start.isValid()) return pos;
        }
        throw new RuntimeException("No valid stronghold start for seed " + seed);
    }
}
