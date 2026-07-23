/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.structures;

import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;

import java.util.ArrayList;
import java.util.List;

/**
 * Predicts structure positions for the waypoint tooling.
 *
 * <p>This delegates to {@link VanillaStructurePredictor}, which drives the real
 * Minecraft structure-set placement data (spacing, separation, salt, spread type
 * and biome/weighted selection) for every dimension. An earlier hand-rolled
 * re-implementation lived here, but it diverged from vanilla for the Nether and
 * End (fortress/bastion double placement, triangular End City spread, missing
 * End Gateway rarity), so it has been removed in favour of the shared engine.
 */
public class StructurePredictor {
    /**
     * Block size of one scan "region". Chosen to roughly match the original per-type
     * region scale (~32 chunks) so a given radius covers a comparable area.
     */
    private static final int REGION_CHUNKS = 32;

    /**
     * Predicts all structure positions for the given scan-region range and dimension.
     *
     * <p>Buried treasure is intentionally excluded: it is a per-chunk structure, so scanning
     * it over a multi-region radius would probe every chunk and stall. Use the map's treasure
     * overlay for that instead.
     *
     * @param regionMinX Minimum region X coordinate (inclusive), in 32-chunk regions
     * @param regionMinZ Minimum region Z coordinate (inclusive), in 32-chunk regions
     * @param regionMaxX Maximum region X coordinate (inclusive), in 32-chunk regions
     * @param regionMaxZ Maximum region Z coordinate (inclusive), in 32-chunk regions
     * @param dimension  0=Overworld, -1=Nether, 1=End
     * @return List of predicted GeneratedStructures (all supported types for the dimension)
     */
    public static List<GeneratedStructure> predict(int regionMinX, int regionMinZ,
                                                    int regionMaxX, int regionMaxZ,
                                                    int dimension) {
        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) return new ArrayList<>();

        int chunkMinX = regionMinX * REGION_CHUNKS;
        int chunkMinZ = regionMinZ * REGION_CHUNKS;
        int chunkMaxX = regionMaxX * REGION_CHUNKS + (REGION_CHUNKS - 1);
        int chunkMaxZ = regionMaxZ * REGION_CHUNKS + (REGION_CHUNKS - 1);

        return VanillaStructurePredictor.predictDimension(seed, dimension, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, false);
    }

    /**
     * Gets the region coordinate for a given block position and structure type.
     */
    public static int getRegionForBlock(int blockX, StructureType type) {
        return Math.floorDiv(blockX, type.regionSize * 16);
    }

    /**
     * Returns the region key for a given chunk position (used by StructureCache).
     * Regions are 64x64 chunks for caching purposes.
     */
    public static long getRegionKey(int chunkX, int chunkZ) {
        int rx = Math.floorDiv(chunkX, 64);
        int rz = Math.floorDiv(chunkZ, 64);
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }
}
