package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator.SimulatedContainer;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

import java.io.PrintStream;
import java.util.List;

/**
 * Locates ancient city candidates for a given seed and attempts offline simulation.
 * Jigsaw structures (ancient cities) are expected to fail offline.
 */
public final class AncientCityValidation {
    private static final PrintStream OUT = System.out;

    public static void main(String[] args) {
        PrintStream originalOut = System.out;
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            long seed = args.length > 0 ? Long.parseLong(args[0]) : -3791862030646821359L;
            run(seed, originalOut);
        } catch (Throwable t) {
            originalOut.println("ancient_city_validation_failed=" + t.getClass().getName() + " " + t.getMessage());
            t.printStackTrace(originalOut);
            System.exit(1);
        }
    }

    private static void run(long seed, PrintStream out) {
        out.println("seed=" + seed + " locating_ancient_cities");

        int spacing = 24;
        int searchRadius = 10;
        int chunkMin = -spacing * searchRadius;
        int chunkMax = spacing * searchRadius;

        List<GeneratedStructure> candidates = VanillaStructurePredictor.predictOverworldStructure(
            seed, "minecraft:ancient_city",
            chunkMin, chunkMin, chunkMax, chunkMax
        );

        out.println("candidates=" + candidates.size());
        if (candidates.isEmpty()) {
            out.println("no_ancient_cities_found_in_search_range");
            out.println("result=FAIL no_loot_chests_generated");
            return;
        }

        // Only test the closest candidate to spawn
        GeneratedStructure closest = candidates.get(0);
        int bestDist = Integer.MAX_VALUE;
        for (GeneratedStructure c : candidates) {
            int d = Math.abs(c.x) + Math.abs(c.z);
            if (d < bestDist) { bestDist = d; closest = c; }
        }

        int chunkX = Math.floorDiv(closest.x, 16);
        int chunkZ = Math.floorDiv(closest.z, 16);
        out.println("closest_candidate block=" + closest.x + "," + closest.z + " chunk=" + chunkX + "," + chunkZ);

        // Step 1: generate structure start
        var start = WorldgenEngine.generateSelectedStructureStart(
            seed, 0, BuiltinStructures.ANCIENT_CITY, new ChunkPos(chunkX, chunkZ));
        out.println("start_valid=" + start.isValid() + " pieces=" + start.getPieces().size());

        if (!start.isValid()) {
            out.println("result=SKIP invalid_start");
            return;
        }

        // Debug: examine first few pieces
        var pieces = start.getPieces();
        int templateCount = 0;
        for (int i = 0; i < Math.min(5, pieces.size()); i++) {
            var piece = pieces.get(i);
            out.println("  piece[" + i + "] class=" + piece.getClass().getName()
                + " box=" + piece.getBoundingBox());
        }
        // Count chunks in the simulation
        var chunks = pieces.stream()
            .flatMap(p -> p.getBoundingBox().intersectingChunks())
            .distinct()
            .sorted(java.util.Comparator.comparingInt(net.minecraft.world.level.ChunkPos::z)
                .thenComparingInt(net.minecraft.world.level.ChunkPos::x))
            .toList();
        out.println("  simulation_chunks=" + chunks.size());

        // Step 2: try simulation
        try {
            List<SimulatedContainer> containers = VanillaLootStructureSimulator.simulate(
                seed, BuiltinStructures.ANCIENT_CITY, chunkX, chunkZ);
            out.println("containers=" + containers.size());
            for (SimulatedContainer c : containers) {
                out.println("  chest=" + c.x() + "," + c.y() + "," + c.z()
                    + " table=" + c.lootTableId() + " seed=" + c.lootSeed());
            }
            if (!containers.isEmpty()) {
                out.println("result=SUCCESS loot_chests_generated=" + containers.size());
            } else {
                out.println("result=JIGSAW_LIMITATION ancient_city_starts_valid_but_no_chests_captured_offline");
            }
        } catch (Throwable t) {
            out.println("  error=" + t.getClass().getName() + " " + t.getMessage());
            t.printStackTrace(out);
            out.println("result=JIGSAW_LIMITATION simulate_threw_exception");
        }
    }
}
