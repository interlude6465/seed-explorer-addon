/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.structures;

import me.seedexplorer.addon.events.SeedChangeEvent;
import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import me.seedexplorer.addon.workers.WorkerManager;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.orbit.EventHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches predicted structure locations in 64x64 chunk "regions" to keep
 * map movement fluid. When the viewport moves, missed regions are computed
 * on a background thread and cached for instant re-rendering.
 */
public class StructureCache {
    private static final StructureCache INSTANCE = new StructureCache();

    // Cache keyed by region coordinates (64x64 chunk regions)
    // A region key is (rx << 32) | (rz & 0xFFFFFFFF)
    private final Map<Long, List<GeneratedStructure>> cache = new ConcurrentHashMap<>();
    private final Map<Long, List<GeneratedStructure>> cacheWithTreasure = new ConcurrentHashMap<>();
    private final Map<Long, List<GeneratedStructure>> cacheWithMineshafts = new ConcurrentHashMap<>();
    private final Map<Long, List<GeneratedStructure>> cacheWithTreasureAndMineshafts = new ConcurrentHashMap<>();
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingWithTreasure = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingWithMineshafts = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingWithTreasureAndMineshafts = ConcurrentHashMap.newKeySet();

    private int lastDimension = 0;
    private volatile int generation = 0;

    private StructureCache() {
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public static StructureCache get() {
        return INSTANCE;
    }

    @EventHandler
    private void onSeedChanged(SeedChangeEvent event) {
        clear();
    }

    /**
     * Queues a specific 64x64 chunk region for background caching.
     *
     * @param regionKey packed (rx << 32 | rz) region coordinates
     */
    public void requestRegion(long regionKey, int dimension) {
        requestRegion(regionKey, dimension, false);
    }

    public void requestRegion(long regionKey, int dimension, boolean includeBuriedTreasure) {
        requestRegion(regionKey, dimension, includeBuriedTreasure, false);
    }

    public void requestRegion(long regionKey, int dimension, boolean includeBuriedTreasure, boolean includeMineshafts) {
        Map<Long, List<GeneratedStructure>> targetCache = cacheFor(includeBuriedTreasure, includeMineshafts);
        Set<Long> targetPending = pendingFor(includeBuriedTreasure, includeMineshafts);
        if (targetCache.containsKey(regionKey) || !targetPending.add(regionKey)) return;
        int requestedGeneration = generation;

        int rx = (int) (regionKey >> 32);
        int rz = (int) (regionKey & 0xFFFFFFFFL);

        // Convert cache region to structure region coordinates
        // Our cache regions are 64x64 chunks; structure regions vary by type
        // We need to cover all structure regions that overlap this cache region
        int chunkStartX = rx * 64;
        int chunkStartZ = rz * 64;
        int chunkEndX = chunkStartX + 63;
        int chunkEndZ = chunkStartZ + 63;

        if (!WorkerManager.get().submit(() -> {
            try {
                List<GeneratedStructure> structures = computeStructures(chunkStartX, chunkStartZ, chunkEndX, chunkEndZ, dimension, includeBuriedTreasure, includeMineshafts);
                if (generation == requestedGeneration && lastDimension == dimension) {
                    targetCache.put(regionKey, structures);
                }
            } finally {
                targetPending.remove(regionKey);
            }
        })) {
            targetPending.remove(regionKey);
        }
    }

    /**
     * Computes all structure positions overlapping the given chunk range.
     */
    private List<GeneratedStructure> computeStructures(int csx, int csz, int cex, int cez, int dimension, boolean includeBuriedTreasure) {
        return computeStructures(csx, csz, cex, cez, dimension, includeBuriedTreasure, false);
    }

    private List<GeneratedStructure> computeStructures(int csx, int csz, int cex, int cez, int dimension, boolean includeBuriedTreasure, boolean includeMineshafts) {
        List<GeneratedStructure> results = new ArrayList<>();
        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) return results;

        return VanillaStructurePredictor.predictDimension(seed, dimension, csx, csz, cex, cez, includeBuriedTreasure, includeMineshafts);
    }

    /**
     * Gets all cached structures within the given chunk bounds.
     * Requests missing overlapping regions in the background.
     */
    public List<GeneratedStructure> getStructures(int chunkMinX, int chunkMinZ,
                                                   int chunkMaxX, int chunkMaxZ,
                                                   int dimension) {
        return getStructures(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, dimension, false);
    }

    public List<GeneratedStructure> getStructures(int chunkMinX, int chunkMinZ,
                                                   int chunkMaxX, int chunkMaxZ,
                                                   int dimension,
                                                   boolean includeBuriedTreasure) {
        return getStructures(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, dimension, includeBuriedTreasure, false);
    }

    public List<GeneratedStructure> getStructures(int chunkMinX, int chunkMinZ,
                                                   int chunkMaxX, int chunkMaxZ,
                                                   int dimension,
                                                   boolean includeBuriedTreasure,
                                                   boolean includeMineshafts) {
        if (lastDimension != dimension) {
            clear();
            lastDimension = dimension;
        }

        Map<Long, List<GeneratedStructure>> targetCache = cacheFor(includeBuriedTreasure, includeMineshafts);

        // Determine which 64x64 cache regions overlap the viewport
        int rMinX = Math.floorDiv(chunkMinX, 64);
        int rMinZ = Math.floorDiv(chunkMinZ, 64);
        int rMaxX = Math.floorDiv(chunkMaxX, 64);
        int rMaxZ = Math.floorDiv(chunkMaxZ, 64);

        // Ensure all needed regions are cached
        for (int rz = rMinZ; rz <= rMaxZ; rz++) {
            for (int rx = rMinX; rx <= rMaxX; rx++) {
                long key = ((long) rx << 32) | (rz & 0xFFFFFFFFL);
                requestRegion(key, dimension, includeBuriedTreasure, includeMineshafts);
            }
        }

        // Collect all structures within the requested bounds
        List<GeneratedStructure> results = new ArrayList<>();
        for (int rz = rMinZ; rz <= rMaxZ; rz++) {
            for (int rx = rMinX; rx <= rMaxX; rx++) {
                long key = ((long) rx << 32) | (rz & 0xFFFFFFFFL);
                List<GeneratedStructure> regionStructs = targetCache.get(key);
                if (regionStructs == null) continue;

                for (GeneratedStructure gs : regionStructs) {
                    int gcsx = gs.x >> 4;
                    int gcsz = gs.z >> 4;
                    if (gcsx >= chunkMinX && gcsx <= chunkMaxX && gcsz >= chunkMinZ && gcsz <= chunkMaxZ) {
                        results.add(gs);
                    }
                }
            }
        }

        return results;
    }

    private Map<Long, List<GeneratedStructure>> cacheFor(boolean includeBuriedTreasure, boolean includeMineshafts) {
        if (includeBuriedTreasure && includeMineshafts) return cacheWithTreasureAndMineshafts;
        if (includeBuriedTreasure) return cacheWithTreasure;
        if (includeMineshafts) return cacheWithMineshafts;
        return cache;
    }

    private Set<Long> pendingFor(boolean includeBuriedTreasure, boolean includeMineshafts) {
        if (includeBuriedTreasure && includeMineshafts) return pendingWithTreasureAndMineshafts;
        if (includeBuriedTreasure) return pendingWithTreasure;
        if (includeMineshafts) return pendingWithMineshafts;
        return pending;
    }

    /**
     * Clears all cached structures, for example when the seed changes.
     */
    public void clear() {
        generation++;
        cache.clear();
        cacheWithTreasure.clear();
        cacheWithMineshafts.clear();
        cacheWithTreasureAndMineshafts.clear();
        pending.clear();
        pendingWithTreasure.clear();
        pendingWithMineshafts.clear();
        pendingWithTreasureAndMineshafts.clear();
    }
}
