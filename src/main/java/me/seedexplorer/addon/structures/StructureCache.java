/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.structures;

import me.seedexplorer.addon.events.SeedChangeEvent;
import me.seedexplorer.addon.debug.PredictionDebugLogger;
import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ChestLootPredictor;
import me.seedexplorer.addon.render.Terrain3DRenderer;
import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import me.seedexplorer.addon.workers.WorkerManager;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.orbit.EventHandler;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final Set<PersistenceKey> pendingPersistence = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_LOOT_FAILURES = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger PERSISTENCE_THREAD_IDS = new AtomicInteger();
    private static final int PERSISTENCE_QUEUE_CAPACITY = 256;

    /** Loot simulation is much slower than locating structures. Keeping it off the
     * shared map workers prevents four chest-heavy regions from freezing all later
     * icon requests for tens of seconds. */
    private final ThreadPoolExecutor persistenceExecutor = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(PERSISTENCE_QUEUE_CAPACITY), runnable -> {
            Thread thread = new Thread(runnable,
                "Seed Explorer Structure Persistence " + PERSISTENCE_THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    private volatile long lastSeed = Long.MIN_VALUE;
    private volatile int lastDimension = Integer.MIN_VALUE;
    private volatile int generation = 0;

    private StructureCache() {
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public static StructureCache get() {
        return INSTANCE;
    }

    @EventHandler
    private void onSeedChanged(SeedChangeEvent event) {
        synchronized (this) {
            clear();
            // Keep the context key in sync even before the next render tick. The
            // generation guard still rejects jobs that were queued for the old seed.
            lastSeed = event.seed;
            lastDimension = Integer.MIN_VALUE;
        }
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
        long requestedSeed = SeedManager.get().getWorldSeed();
        ensureContext(requestedSeed, dimension);
        Map<Long, List<GeneratedStructure>> targetCache = cacheFor(includeBuriedTreasure, includeMineshafts);
        Set<Long> targetPending = pendingFor(includeBuriedTreasure, includeMineshafts);
        String worldIdentity = Terrain3DRenderer.worldCacheIdentity(requestedSeed);
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
                Optional<StructurePredictionStore.RegionPrediction> persisted = StructurePredictionStore.get()
                    .load(worldIdentity, requestedSeed, dimension, regionKey,
                        includeBuriedTreasure, includeMineshafts);
                List<GeneratedStructure> structures = persisted.isPresent()
                    ? persisted.get().structures().stream()
                        .map(StructurePredictionStore.StructurePrediction::structure).toList()
                    : computeStructures(requestedSeed, chunkStartX, chunkStartZ, chunkEndX, chunkEndZ,
                        dimension, includeBuriedTreasure, includeMineshafts);
                if (generation == requestedGeneration && lastSeed == requestedSeed && lastDimension == dimension) {
                    // Publish locations before optional chest simulation. The 2D map
                    // should fill as fast as structure starts can be predicted.
                    targetCache.put(regionKey, structures);
                    if (persisted.isEmpty()) {
                        queuePredictionPersistence(worldIdentity, requestedSeed, dimension,
                            regionKey, includeBuriedTreasure, includeMineshafts,
                            requestedGeneration, structures);
                    }
                }
            } finally {
                targetPending.remove(regionKey);
            }
        })) {
            targetPending.remove(regionKey);
        }
    }

    private void queuePredictionPersistence(String worldIdentity, long seed, int dimension,
                                            long regionKey, boolean includeBuriedTreasure,
                                            boolean includeMineshafts, int requestedGeneration,
                                            List<GeneratedStructure> structures) {
        PersistenceKey key = new PersistenceKey(seed, dimension, regionKey,
            includeBuriedTreasure, includeMineshafts, requestedGeneration);
        if (!pendingPersistence.add(key)) return;
        try {
            persistenceExecutor.execute(() -> {
                try {
                    if (!isCurrent(seed, dimension, requestedGeneration)) return;
                    List<StructurePredictionStore.StructurePrediction> predictions =
                        new ArrayList<>(structures.size());
                    for (GeneratedStructure structure : structures) {
                        if (!isCurrent(seed, dimension, requestedGeneration)) return;
                        List<ChestLootOutput> chests = predictLootWithoutAbortingRegion(
                            seed, dimension, structure);
                        predictions.add(new StructurePredictionStore.StructurePrediction(
                            structure, chests));
                    }
                    if (!isCurrent(seed, dimension, requestedGeneration)) return;
                    StructurePredictionStore.get().storeAsync(worldIdentity, seed, dimension,
                        regionKey, includeBuriedTreasure, includeMineshafts, predictions);
                } finally {
                    pendingPersistence.remove(key);
                }
            });
        } catch (RejectedExecutionException rejected) {
            pendingPersistence.remove(key);
        }
    }

    private boolean isCurrent(long seed, int dimension, int requestedGeneration) {
        return generation == requestedGeneration && lastSeed == seed && lastDimension == dimension;
    }

    private static List<ChestLootOutput> predictLootWithoutAbortingRegion(
        long seed, int dimension, GeneratedStructure structure) {
        if (!ChestLootPredictor.canPredict(structure.type)) return List.of();
        try {
            return ChestLootPredictor.predictForStructure(seed, structure, 64);
        } catch (RuntimeException failure) {
            String signature = structure.type + ":" + failure.getClass().getName()
                + ":" + String.valueOf(failure.getMessage());
            if (LOGGED_LOOT_FAILURES.add(signature)) {
                PredictionDebugLogger.exception("event=structure_loot_prediction_failed"
                    + " seed=" + seed + " dimension=" + dimension
                    + " type=" + structure.type
                    + " startChunk=" + structure.startChunkX + "," + structure.startChunkZ,
                    failure);
            }
            return List.of();
        }
    }

    /**
     * Computes all structure positions overlapping the given chunk range.
     */
    private List<GeneratedStructure> computeStructures(long seed, int csx, int csz, int cex, int cez, int dimension, boolean includeBuriedTreasure, boolean includeMineshafts) {
        if (seed == 0) return List.of();

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
        long seed = SeedManager.get().getWorldSeed();
        ensureContext(seed, dimension);

        Map<Long, List<GeneratedStructure>> targetCache = cacheFor(includeBuriedTreasure, includeMineshafts);

        // Determine which 64x64 cache regions overlap the viewport
        int rMinX = Math.floorDiv(chunkMinX, 64);
        int rMinZ = Math.floorDiv(chunkMinZ, 64);
        int rMaxX = Math.floorDiv(chunkMaxX, 64);
        int rMaxZ = Math.floorDiv(chunkMaxZ, 64);

        requestRegionsCenterOut(rMinX, rMinZ, rMaxX, rMaxZ, dimension,
            includeBuriedTreasure, includeMineshafts);

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

    private void requestRegionsCenterOut(int rMinX, int rMinZ, int rMaxX, int rMaxZ,
                                         int dimension, boolean includeBuriedTreasure,
                                         boolean includeMineshafts) {
        int centerX = rMinX + (rMaxX - rMinX) / 2;
        int centerZ = rMinZ + (rMaxZ - rMinZ) / 2;
        int maxRing = Math.max(Math.max(centerX - rMinX, rMaxX - centerX),
            Math.max(centerZ - rMinZ, rMaxZ - centerZ));
        for (int ring = 0; ring <= maxRing; ring++) {
            int minZ = Math.max(rMinZ, centerZ - ring);
            int maxZ = Math.min(rMaxZ, centerZ + ring);
            int minX = Math.max(rMinX, centerX - ring);
            int maxX = Math.min(rMaxX, centerX + ring);
            for (int rz = minZ; rz <= maxZ; rz++) {
                for (int rx = minX; rx <= maxX; rx++) {
                    if (Math.max(Math.abs(rx - centerX), Math.abs(rz - centerZ)) != ring) continue;
                    requestRegion(((long) rx << 32) | (rz & 0xFFFFFFFFL), dimension,
                        includeBuriedTreasure, includeMineshafts);
                }
            }
        }
    }

    private synchronized void ensureContext(long seed, int dimension) {
        if (lastSeed == seed && lastDimension == dimension) return;
        clear();
        lastSeed = seed;
        lastDimension = dimension;
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
        synchronized (this) {
            generation++;
            cache.clear();
            cacheWithTreasure.clear();
            cacheWithMineshafts.clear();
            cacheWithTreasureAndMineshafts.clear();
            pending.clear();
            pendingWithTreasure.clear();
            pendingWithMineshafts.clear();
            pendingWithTreasureAndMineshafts.clear();
            pendingPersistence.clear();
        }
    }

    private record PersistenceKey(long seed, int dimension, long regionKey,
                                  boolean treasure, boolean mineshafts, int generation) {
    }
}
