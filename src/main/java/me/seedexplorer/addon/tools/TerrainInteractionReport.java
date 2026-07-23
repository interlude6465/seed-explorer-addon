package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.worldgen.WorldgenEngine;
import me.seedexplorer.addon.worldgen.GeneratedTerrainHeightmap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.PrintStream;

/** Headless diagnostics for overlapping structure terrain adaptation. */
public final class TerrainInteractionReport {
    private static final PrintStream OUT = System.out;

    private TerrainInteractionReport() {
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 123456789L;
        int pyramidChunkX = args.length > 1 ? Integer.parseInt(args[1]) : -157;
        int pyramidChunkZ = args.length > 2 ? Integer.parseInt(args[2]) : -125;
        int trialChunkX = args.length > 3 ? Integer.parseInt(args[3]) : -154;
        int trialChunkZ = args.length > 4 ? Integer.parseInt(args[4]) : -125;

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        try {
            StructureStart pyramid = report(seed, BuiltinStructures.DESERT_PYRAMID,
                new ChunkPos(pyramidChunkX, pyramidChunkZ));
            StructureStart trial = report(seed, BuiltinStructures.TRIAL_CHAMBERS,
                new ChunkPos(trialChunkX, trialChunkZ));
            reportFootprintHeights(seed, pyramid, trial);
        } catch (Throwable throwable) {
            throwable.printStackTrace(OUT);
            System.exit(2);
        }
    }

    private static StructureStart report(long seed,
                               net.minecraft.resources.ResourceKey<Structure> key,
                               ChunkPos chunk) {
        Structure structure = WorldgenEngine.vanillaLookup()
            .lookupOrThrow(Registries.STRUCTURE)
            .getOrThrow(key)
            .value();
        StructureStart start = WorldgenEngine.generateSelectedStructureStart(seed, 0, key, chunk);
        OUT.println("structure=" + key.identifier()
            + " chunk=" + chunk
            + " step=" + structure.step()
            + " terrain_adaptation=" + structure.terrainAdaptation()
            + " valid=" + start.isValid()
            + " pieces=" + start.getPieces().size()
            + " bounding_box=" + (start.isValid() ? start.getBoundingBox() : "n/a"));
        return start;
    }

    private static void reportFootprintHeights(long seed, StructureStart pyramid,
                                               StructureStart trial) {
        GeneratedTerrainHeightmap generated = new GeneratedTerrainHeightmap(
            seed, 0, trial.isValid() ? java.util.List.of(trial) : java.util.List.of());
        GeneratedTerrainHeightmap generatedWithoutStructures =
            new GeneratedTerrainHeightmap(seed, 0, java.util.List.of());
        var box = pyramid.getBoundingBox();
        int baseMin = Integer.MAX_VALUE;
        int generatedMin = Integer.MAX_VALUE;
        int generatedWithoutStructuresMin = Integer.MAX_VALUE;
        int noiseMin = Integer.MAX_VALUE;
        int surfaceMin = Integer.MAX_VALUE;
        int carversMin = Integer.MAX_VALUE;
        BlockPos baseAt = BlockPos.ZERO;
        BlockPos generatedAt = BlockPos.ZERO;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                int base = WorldgenEngine.generationHeight(
                    seed, 0, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int full = generated.firstFreeHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int withoutStructures = generatedWithoutStructures.firstFreeHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                noiseMin = Math.min(noiseMin, generated.stageHeight(
                    GeneratedTerrainHeightmap.TerrainStage.NOISE, x, z));
                surfaceMin = Math.min(surfaceMin, generated.stageHeight(
                    GeneratedTerrainHeightmap.TerrainStage.SURFACE, x, z));
                carversMin = Math.min(carversMin, generated.stageHeight(
                    GeneratedTerrainHeightmap.TerrainStage.CARVERS, x, z));
                if (base < baseMin) {
                    baseMin = base;
                    baseAt = new BlockPos(x, base, z);
                }
                if (full < generatedMin) {
                    generatedMin = full;
                    generatedAt = new BlockPos(x, full, z);
                }
                generatedWithoutStructuresMin = Math.min(
                    generatedWithoutStructuresMin, withoutStructures);
            }
        }
        OUT.println("pyramid_footprint base_height_min=" + baseMin + " at=" + baseAt
            + " generated_noise_carver_min=" + generatedMin + " at=" + generatedAt
            + " generated_without_structure_adaptation_min="
            + generatedWithoutStructuresMin);
        OUT.println("pyramid_footprint stage_min noise=" + noiseMin
            + " surface=" + surfaceMin + " carvers=" + carversMin);

        generated.applyDecorationBefore(
            pyramid.getChunkPos(), pyramid.getStructure().step().ordinal());
        int afterEarlierFeaturesMin = Integer.MAX_VALUE;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                afterEarlierFeaturesMin = Math.min(afterEarlierFeaturesMin,
                    generated.firstFreeHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
            }
        }
        OUT.println("pyramid_footprint after_earlier_features_min="
            + afterEarlierFeaturesMin);
        box.intersectingChunks()
            .sorted(java.util.Comparator.comparingInt(ChunkPos::z)
                .thenComparingInt(ChunkPos::x))
            .forEach(chunk -> generated.applyDecorationBefore(
                chunk, pyramid.getStructure().step().ordinal()));
        int afterAllFootprintChunkFeaturesMin = Integer.MAX_VALUE;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                afterAllFootprintChunkFeaturesMin = Math.min(
                    afterAllFootprintChunkFeaturesMin,
                    generated.firstFreeHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
            }
        }
        OUT.println("pyramid_footprint after_all_intersecting_chunk_earlier_features_min="
            + afterAllFootprintChunkFeaturesMin);
        int minChunkX = Math.floorDiv(box.minX(), 16) - 1;
        int maxChunkX = Math.floorDiv(box.maxX(), 16) + 1;
        int minChunkZ = Math.floorDiv(box.minZ(), 16) - 1;
        int maxChunkZ = Math.floorDiv(box.maxZ(), 16) + 1;
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                generated.applyDecorationBefore(new ChunkPos(chunkX, chunkZ),
                    pyramid.getStructure().step().ordinal());
            }
        }
        int afterNeighboringEarlierFeaturesMin = Integer.MAX_VALUE;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                afterNeighboringEarlierFeaturesMin = Math.min(
                    afterNeighboringEarlierFeaturesMin,
                    generated.firstFreeHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
            }
        }
        OUT.println("pyramid_footprint after_neighboring_chunk_earlier_features_min="
            + afterNeighboringEarlierFeaturesMin);
        for (GeneratedTerrainHeightmap.DecorationFeatureTrace trace
            : generated.decorationTrace()) {
            OUT.println("feature chunk=" + trace.chunkX() + "," + trace.chunkZ()
                + " step=" + trace.step() + " index=" + trace.index()
                + " id=" + trace.featureId() + " placed=" + trace.placed()
                + " block_writes=" + trace.blockWrites()
                + " bounds=" + trace.writeBounds());
        }
    }
}
