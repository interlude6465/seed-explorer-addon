package me.seedexplorer.addon.structures;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ChestLootPredictor;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/** Bounded, cancellable cache around the expensive vanilla monster-room replay. */
public final class DungeonPredictor {
    private static final int MAX_CACHED_CHUNKS = 8192;
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "Seed Explorer Dungeon Predictor " + THREAD_IDS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<ChunkKey, List<PredictedDungeon>> CACHE = new ConcurrentHashMap<>();
    private static volatile long cachedSeed = Long.MIN_VALUE;

    private DungeonPredictor() {
    }

    public static List<PredictedDungeon> scanChunk(long seed, int chunkX, int chunkZ) {
        ensureSeed(seed);
        ChunkKey key = new ChunkKey(seed, chunkX, chunkZ);
        List<PredictedDungeon> cached = CACHE.get(key);
        if (cached != null) return cached;

        List<PredictedDungeon> predicted = VanillaLootStructureSimulator.simulateDungeons(seed, chunkX, chunkZ)
            .stream().map(dungeon -> new PredictedDungeon(
                new GeneratedStructure(dungeon.x(), dungeon.y(), dungeon.z(), chunkX, chunkZ,
                    StructureType.DUNGEON, dungeon.spawnerEntityId(), false),
                dungeon)).toList();
        if (CACHE.size() >= MAX_CACHED_CHUNKS) CACHE.clear();
        CACHE.put(key, predicted);
        return predicted;
    }

    public static ScanResult scanAround(long seed, int centerChunkX, int centerChunkZ, int radius,
                                        int stopAfterDungeons, Predicate<PredictedDungeon> stopWhen,
                                        BooleanSupplier cancelled,
                                        IntConsumer progress) {
        int boundedRadius = Math.max(0, radius);
        int total = (boundedRadius * 2 + 1) * (boundedRadius * 2 + 1);
        int checked = 0;
        List<PredictedDungeon> found = new ArrayList<>();

        for (int ring = 0; ring <= boundedRadius; ring++) {
            if (cancelled.getAsBoolean()) return new ScanResult(List.copyOf(found), checked, total, true);
            List<ChunkPos> chunks = ringChunks(centerChunkX, centerChunkZ, ring);
            List<CompletableFuture<List<PredictedDungeon>>> futures = chunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(
                    () -> cancelled.getAsBoolean() ? List.<PredictedDungeon>of()
                        : scanChunk(seed, chunk.x(), chunk.z()), WORKERS))
                .toList();

            for (CompletableFuture<List<PredictedDungeon>> future : futures) {
                if (cancelled.getAsBoolean()) {
                    futures.forEach(job -> job.cancel(true));
                    return new ScanResult(List.copyOf(found), checked, total, true);
                }
                try {
                    found.addAll(future.join());
                } catch (Throwable ignored) {
                    // One failed chunk must not abort the rest of a bounded search.
                }
                checked++;
                progress.accept(checked);
                if (stopAfterDungeons > 0 && found.size() >= stopAfterDungeons) {
                    futures.forEach(job -> {
                        if (!job.isDone()) job.cancel(true);
                    });
                    found.sort(Comparator.comparingDouble(dungeon -> distanceSquared(
                        dungeon.structure().x, dungeon.structure().z,
                        centerChunkX * 16 + 8, centerChunkZ * 16 + 8)));
                    return new ScanResult(List.copyOf(found), checked, total, false);
                }
            }
            if (stopWhen != null && found.stream().anyMatch(stopWhen)) break;
        }

        found.sort(Comparator.comparingDouble(dungeon -> distanceSquared(
            dungeon.structure().x, dungeon.structure().z, centerChunkX * 16 + 8, centerChunkZ * 16 + 8)));
        return new ScanResult(List.copyOf(found), checked, total, false);
    }

    public static List<GeneratedStructure> cachedStructures(long seed, int chunkMinX, int chunkMinZ,
                                                             int chunkMaxX, int chunkMaxZ) {
        ensureSeed(seed);
        List<GeneratedStructure> result = new ArrayList<>();
        for (Map.Entry<ChunkKey, List<PredictedDungeon>> entry : CACHE.entrySet()) {
            ChunkKey key = entry.getKey();
            if (key.seed() != seed) continue;
            for (PredictedDungeon dungeon : entry.getValue()) {
                GeneratedStructure structure = dungeon.structure();
                int chunkX = Math.floorDiv(structure.x, 16);
                int chunkZ = Math.floorDiv(structure.z, 16);
                if (chunkX >= chunkMinX && chunkX <= chunkMaxX
                    && chunkZ >= chunkMinZ && chunkZ <= chunkMaxZ) result.add(structure);
            }
        }
        return List.copyOf(result);
    }

    public static List<ChestLootOutput> chestsFor(long seed, GeneratedStructure structure, int maxItems) {
        if (structure == null || structure.type != StructureType.DUNGEON) return List.of();
        for (PredictedDungeon dungeon : scanChunk(seed, structure.startChunkX, structure.startChunkZ)) {
            GeneratedStructure candidate = dungeon.structure();
            if (candidate.x == structure.x && candidate.y == structure.y && candidate.z == structure.z) {
                return ChestLootPredictor.predictDungeonContainers(dungeon.simulated(), maxItems);
            }
        }
        return List.of();
    }

    public static void clear() {
        CACHE.clear();
        cachedSeed = Long.MIN_VALUE;
    }

    private static synchronized void ensureSeed(long seed) {
        if (cachedSeed == seed) return;
        CACHE.clear();
        cachedSeed = seed;
    }

    private static List<ChunkPos> ringChunks(int centerX, int centerZ, int ring) {
        if (ring == 0) return List.of(new ChunkPos(centerX, centerZ));
        List<ChunkPos> result = new ArrayList<>(ring * 8);
        for (int dx = -ring; dx <= ring; dx++) {
            result.add(new ChunkPos(centerX + dx, centerZ - ring));
            result.add(new ChunkPos(centerX + dx, centerZ + ring));
        }
        for (int dz = -ring + 1; dz < ring; dz++) {
            result.add(new ChunkPos(centerX - ring, centerZ + dz));
            result.add(new ChunkPos(centerX + ring, centerZ + dz));
        }
        return result;
    }

    private static double distanceSquared(int x1, int z1, int x2, int z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    private record ChunkKey(long seed, int chunkX, int chunkZ) {
    }

    public record PredictedDungeon(GeneratedStructure structure,
                                   VanillaLootStructureSimulator.SimulatedDungeon simulated) {
    }

    public record ScanResult(List<PredictedDungeon> dungeons, int checkedChunks,
                             int totalChunks, boolean cancelled) {
    }
}
