/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.ore;

import me.seedexplorer.addon.events.SeedChangeEvent;
import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.workers.WorkerManager;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.orbit.EventHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches predicted ore patch locations in modest chunk regions to keep
 * map movement fluid. Mirrors the StructureCache pattern for performant
 * seed-based ore prediction.
 */
public class OreCache {
    private static final OreCache INSTANCE = new OreCache();
    private static final int REGION_CHUNKS = 16;

    // Cache keyed by region coordinates.
    // A region key is (rx << 32) | (rz & 0xFFFFFFFF)
    private final Map<Long, List<OrePatch>> cache = new ConcurrentHashMap<>();
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();

    private int lastDimension = 0;
    private volatile int generation = 0;

    private OreCache() {
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public static OreCache get() {
        return INSTANCE;
    }

    @EventHandler
    private void onSeedChanged(SeedChangeEvent event) {
        clear();
    }

    /**
     * Queues a specific REGION_CHUNKS x REGION_CHUNKS chunk region for
     * background caching.
     *
     * @param regionKey packed (rx << 32 | rz) region coordinates
     */
    public void requestRegion(long regionKey, int dimension) {
        if (cache.containsKey(regionKey) || !pending.add(regionKey)) return;
        int requestedGeneration = generation;

        int rx = (int) (regionKey >> 32);
        int rz = (int) (regionKey & 0xFFFFFFFFL);

        int chunkStartX = rx * REGION_CHUNKS;
        int chunkStartZ = rz * REGION_CHUNKS;
        int chunkEndX = chunkStartX + REGION_CHUNKS - 1;
        int chunkEndZ = chunkStartZ + REGION_CHUNKS - 1;

        if (!WorkerManager.get().submit(() -> {
            try {
                List<OrePatch> patches = OrePredictor.predictInChunkRange(
                    chunkStartX, chunkStartZ, chunkEndX, chunkEndZ, dimension
                );
                if (generation == requestedGeneration && lastDimension == dimension) {
                    cache.put(regionKey, patches);
                }
            } finally {
                pending.remove(regionKey);
            }
        })) {
            pending.remove(regionKey);
        }
    }

    /**
     * Gets all cached ore patches within the given chunk bounds.
     * Requests missing overlapping regions in the background.
     */
    public List<OrePatch> getOres(int chunkMinX, int chunkMinZ,
                                  int chunkMaxX, int chunkMaxZ,
                                  int dimension) {
        if (lastDimension != dimension) {
            clear();
            lastDimension = dimension;
        }

        // Determine which cache regions overlap the viewport
        int rMinX = Math.floorDiv(chunkMinX, REGION_CHUNKS);
        int rMinZ = Math.floorDiv(chunkMinZ, REGION_CHUNKS);
        int rMaxX = Math.floorDiv(chunkMaxX, REGION_CHUNKS);
        int rMaxZ = Math.floorDiv(chunkMaxZ, REGION_CHUNKS);

        // Ensure all needed regions are cached
        for (int rz = rMinZ; rz <= rMaxZ; rz++) {
            for (int rx = rMinX; rx <= rMaxX; rx++) {
                long key = ((long) rx << 32) | (rz & 0xFFFFFFFFL);
                requestRegion(key, dimension);
            }
        }

        // Collect all ore patches within the requested bounds
        List<OrePatch> results = new ArrayList<>();
        for (int rz = rMinZ; rz <= rMaxZ; rz++) {
            for (int rx = rMinX; rx <= rMaxX; rx++) {
                long key = ((long) rx << 32) | (rz & 0xFFFFFFFFL);
                List<OrePatch> regionPatches = cache.get(key);
                if (regionPatches == null) continue;

                for (OrePatch patch : regionPatches) {
                    int pcsx = patch.x >> 4;
                    int pcsz = patch.z >> 4;
                    if (pcsx >= chunkMinX && pcsx <= chunkMaxX && pcsz >= chunkMinZ && pcsz <= chunkMaxZ) {
                        if (SeedManager.get().isClearedOre(patch, dimension)) continue;
                        results.add(patch);
                    }
                }
            }
        }

        return results;
    }

    /**
     * Clears all cached ore patches, for example when the seed changes.
     */
    public void clear() {
        generation++;
        cache.clear();
        pending.clear();
    }
}
