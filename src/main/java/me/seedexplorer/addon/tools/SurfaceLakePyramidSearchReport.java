package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.worldgen.GeneratedTerrainHeightmap;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Searches for a desert pyramid whose pre-structure surface-lava-lake pass changes its footprint. */
public final class SurfaceLakePyramidSearchReport {
    private static final PrintStream OUT = System.out;
    private static final String SURFACE_LAKE = "minecraft:lake_lava_surface";

    private SurfaceLakePyramidSearchReport() {
    }

    public static void main(String[] args) {
        long firstSeed = args.length > 0 ? Long.parseLong(args[0]) : 1L;
        int seedCount = args.length > 1 ? Integer.parseInt(args[1]) : 500;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        try {
            search(firstSeed, seedCount);
        } catch (Throwable throwable) {
            throwable.printStackTrace(OUT);
            System.exit(2);
        }
    }

    private static void search(long firstSeed, int seedCount) {
        Holder<StructureSet> holder = WorldgenEngine.vanillaLookup()
            .lookupOrThrow(Registries.STRUCTURE_SET)
            .getOrThrow(BuiltinStructureSets.DESERT_PYRAMIDS);
        RandomSpreadStructurePlacement placement =
            (RandomSpreadStructurePlacement) holder.value().placement();
        int spacing = placement.spacing();
        int rarityCandidates = 0;
        int placedLakes = 0;

        for (int offset = 0; offset < seedCount; offset++) {
            long seed = firstSeed + offset;
            StructureStart pyramid = firstPyramid(seed, placement, spacing);
            if (pyramid == null) continue;

            int lakeIndex = surfaceLakeIndex(seed);
            Set<ChunkPos> possibleLakeChunks = surroundingChunks(pyramid);
            List<ChunkPos> rarityHits = possibleLakeChunks.stream()
                .filter(chunk -> passesSurfaceLakeRarity(seed, chunk, lakeIndex))
                .sorted(Comparator.comparingInt(ChunkPos::z)
                    .thenComparingInt(ChunkPos::x))
                .toList();
            if (rarityHits.isEmpty()) continue;
            rarityCandidates++;

            GeneratedTerrainHeightmap terrain =
                new GeneratedTerrainHeightmap(seed, 0, List.of());
            var box = pyramid.getBoundingBox();
            Map<Long, Integer> before = new HashMap<>();
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    before.put(columnKey(x, z), terrain.firstFreeHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
                }
            }

            for (ChunkPos chunk : rarityHits) {
                terrain.applyHeightAffectingDecorationBefore(chunk,
                    pyramid.getStructure().step().ordinal());
            }
            boolean lakePlaced = terrain.decorationTrace().stream()
                .anyMatch(trace -> trace.featureId().equals(SURFACE_LAKE)
                    && trace.placed() && trace.blockWrites() > 0);
            if (lakePlaced) placedLakes++;
            if (rarityCandidates <= 3) {
                OUT.println("rarity_candidate seed=" + seed
                    + " structure_chunk=" + pyramid.getChunkPos().x() + ","
                    + pyramid.getChunkPos().z() + " chunks=" + rarityHits);
                terrain.decorationTrace().forEach(trace -> OUT.println(
                    "  trace chunk=" + trace.chunkX() + "," + trace.chunkZ()
                        + " placed=" + trace.placed()
                        + " writes=" + trace.blockWrites()
                        + " bounds=" + trace.writeBounds()));
            }

            int changedColumns = 0;
            int beforeMin = Integer.MAX_VALUE;
            int afterMin = Integer.MAX_VALUE;
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    int oldHeight = before.get(columnKey(x, z));
                    int newHeight = terrain.firstFreeHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    beforeMin = Math.min(beforeMin, oldHeight);
                    afterMin = Math.min(afterMin, newHeight);
                    if (oldHeight != newHeight) changedColumns++;
                }
            }

            if (changedColumns > 0) {
                OUT.println("surface_lake_pyramid_found=true seed=" + seed
                    + " structure_chunk=" + pyramid.getChunkPos().x() + ","
                    + pyramid.getChunkPos().z()
                    + " bbox=" + box
                    + " lake_index=" + lakeIndex
                    + " rarity_chunks=" + rarityHits
                    + " changed_columns=" + changedColumns
                    + " footprint_min_before=" + beforeMin
                    + " footprint_min_after=" + afterMin);
                terrain.decorationTrace().forEach(trace -> OUT.println(
                    "  feature chunk=" + trace.chunkX() + "," + trace.chunkZ()
                        + " placed=" + trace.placed()
                        + " writes=" + trace.blockWrites()
                        + " bounds=" + trace.writeBounds()));
                return;
            }

            if (offset % 50 == 0) {
                OUT.println("progress seeds_tested=" + (offset + 1)
                    + " rarity_candidates=" + rarityCandidates
                    + " placed_lakes=" + placedLakes);
            }
        }

        OUT.println("surface_lake_pyramid_found=false seeds_tested=" + seedCount
            + " rarity_candidates=" + rarityCandidates
            + " placed_lakes=" + placedLakes);
        System.exit(3);
    }

    private static StructureStart firstPyramid(long seed,
                                                RandomSpreadStructurePlacement placement,
                                                int spacing) {
        for (int radius = 0; radius <= 32; radius++) {
            for (int rz = -radius; rz <= radius; rz++) {
                for (int rx = -radius; rx <= radius; rx++) {
                    if (Math.max(Math.abs(rx), Math.abs(rz)) != radius) continue;
                    ChunkPos chunk = placement.getPotentialStructureChunk(
                        seed, rx * spacing, rz * spacing);
                    if (!WorldgenEngine.getBiome(seed, 0,
                        chunk.getMiddleBlockX(), 64, chunk.getMiddleBlockZ())
                        .id().equals("minecraft:desert")) {
                        continue;
                    }
                    StructureStart start = WorldgenEngine.generateStructureStart(
                        seed, 0, BuiltinStructures.DESERT_PYRAMID, chunk);
                    if (start.isValid()) return start;
                }
            }
        }
        return null;
    }

    private static int surfaceLakeIndex(long seed) {
        FeatureSorter.StepFeatureData lakes = WorldgenEngine.featureSteps(seed, 0).get(1);
        var registry = WorldgenEngine.offlineRegistryAccess()
            .lookupOrThrow(Registries.PLACED_FEATURE);
        for (int index = 0; index < lakes.features().size(); index++) {
            PlacedFeature feature = lakes.features().get(index);
            if (SURFACE_LAKE.equals(String.valueOf(registry.getKey(feature)))) return index;
        }
        throw new IllegalStateException("Surface lava lake is absent from LAKES step");
    }

    private static boolean passesSurfaceLakeRarity(long seed, ChunkPos chunk,
                                                   int featureIndex) {
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long decorationSeed = random.setDecorationSeed(
            seed, chunk.getMinBlockX(), chunk.getMinBlockZ());
        random.setFeatureSeed(decorationSeed, featureIndex, 1);
        return random.nextFloat() < 1.0F / 200.0F;
    }

    private static Set<ChunkPos> surroundingChunks(StructureStart pyramid) {
        Set<ChunkPos> result = new LinkedHashSet<>();
        List<ChunkPos> footprint = pyramid.getBoundingBox().intersectingChunks().toList();
        for (ChunkPos chunk : footprint) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    result.add(new ChunkPos(chunk.x() + dx, chunk.z() + dz));
                }
            }
        }
        return result;
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
