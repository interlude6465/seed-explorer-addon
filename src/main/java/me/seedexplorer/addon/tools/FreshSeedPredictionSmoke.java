package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.resources.ResourceKey;

import java.io.PrintStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Fresh-seed smoke coverage for the product prediction path. This is not an
 * oracle validator; it catches crashes, empty validated-structure simulations,
 * bad dimension routing, and accidental exposure of unsupported loot.
 */
public final class FreshSeedPredictionSmoke {
    private static final PrintStream OUT = System.out;
    private static final long[] DEFAULT_SEEDS = {
        2026071501L,
        -918273645546372819L,
        8675309L,
        314159265358979323L,
        -4444444444444444444L
    };
    private static final int SEARCH_RADIUS_CHUNKS = 768;
    private static final int MAX_CANDIDATES_PER_TYPE = 25;

    private static final Map<StructureType, ResourceKey<Structure>> VALIDATED_KEYS =
        new EnumMap<>(StructureType.class);

    static {
        VALIDATED_KEYS.put(StructureType.DESERT_PYRAMID, BuiltinStructures.DESERT_PYRAMID);
        VALIDATED_KEYS.put(StructureType.STRONGHOLD, BuiltinStructures.STRONGHOLD);
        VALIDATED_KEYS.put(StructureType.SHIPWRECK, BuiltinStructures.SHIPWRECK);
        VALIDATED_KEYS.put(StructureType.OUTPOST, BuiltinStructures.PILLAGER_OUTPOST);
        VALIDATED_KEYS.put(StructureType.JUNGLE_TEMPLE, BuiltinStructures.JUNGLE_TEMPLE);
        VALIDATED_KEYS.put(StructureType.TREASURE, BuiltinStructures.BURIED_TREASURE);
    }

    private FreshSeedPredictionSmoke() {
    }

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            long[] seeds = args.length == 0 ? DEFAULT_SEEDS : parseSeeds(args);
            int failures = run(seeds);
            OUT.println("fresh_seed_prediction_smoke_pass=" + (failures == 0)
                + " failures=" + failures);
            if (failures != 0) System.exit(2);
        } catch (Throwable t) {
            OUT.println("fresh_seed_prediction_smoke_failed=" + t.getClass().getName()
                + " " + t.getMessage());
            t.printStackTrace(OUT);
            System.exit(1);
        }
    }

    private static int run(long[] seeds) {
        int failures = 0;
        for (long seed : seeds) {
            OUT.println("seed=" + seed);
            List<GeneratedStructure> overworld = VanillaStructurePredictor.predictDimension(
                seed, 0, -SEARCH_RADIUS_CHUNKS, -SEARCH_RADIUS_CHUNKS,
                SEARCH_RADIUS_CHUNKS, SEARCH_RADIUS_CHUNKS, true, true);
            List<GeneratedStructure> nether = VanillaStructurePredictor.predictDimension(
                seed, -1, -256, -256, 256, 256, false, false);
            List<GeneratedStructure> end = VanillaStructurePredictor.predictDimension(
                seed, 1, -256, -256, 256, 256, false, false);
            OUT.println("  structure_counts overworld=" + overworld.size()
                + " nether=" + nether.size() + " end=" + end.size());

            for (StructureType type : VALIDATED_KEYS.keySet()) {
                List<GeneratedStructure> candidates = first(overworld, type, MAX_CANDIDATES_PER_TYPE);
                if (candidates.isEmpty()) {
                    OUT.println("  skip " + type.name() + " not_found_in_search_radius");
                    continue;
                }
                int rejected = 0;
                boolean found = false;
                for (GeneratedStructure structure : candidates) {
                    var containers = VanillaLootStructureSimulator.simulate(
                        seed, type.dimension, VALIDATED_KEYS.get(type),
                        structure.startChunkX, structure.startChunkZ);
                    if (containers.isEmpty()) {
                        rejected++;
                        continue;
                    }
                    OUT.println("  validated_path " + type.name()
                        + " at=" + structure.x + "," + structure.z
                        + " start_chunk=" + structure.startChunkX + "," + structure.startChunkZ
                        + " containers=" + containers.size()
                        + " rejected_before_match=" + rejected);
                    found = true;
                    break;
                }
                if (!found) {
                    OUT.println("  failure " + type.name()
                        + " no_simulatable_candidate candidates_checked=" + candidates.size());
                    failures++;
                }
            }
        }
        return failures;
    }

    private static List<GeneratedStructure> first(List<GeneratedStructure> structures, StructureType type, int limit) {
        return structures.stream()
            .filter(s -> s.type == type)
            .limit(limit)
            .toList();
    }

    private static long[] parseSeeds(String[] args) {
        long[] result = new long[args.length];
        for (int i = 0; i < args.length; i++) result[i] = Long.parseLong(args[i]);
        return result;
    }
}
