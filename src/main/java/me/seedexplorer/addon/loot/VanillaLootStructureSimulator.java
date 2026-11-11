package me.seedexplorer.addon.loot;

import me.seedexplorer.addon.worldgen.WorldgenEngine;
import me.seedexplorer.addon.worldgen.GeneratedTerrainHeightmap;
import me.seedexplorer.addon.worldgen.PredictionContext;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import me.seedexplorer.addon.structures.GeneratedStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.feature.MonsterRoomFeature;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.storage.loot.LootTable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Executes vanilla structure pieces against a small recording WorldGenLevel.
 *
 * <p>This is not a hand-written chest-position formula. Vanilla creates and
 * orients the real structure piece, moves it to the real terrain height, and
 * consumes the same per-decoration RNG used by ChunkGenerator. The recording
 * world captures every chest and the loot seed assigned by createChest().</p>
 */
public final class VanillaLootStructureSimulator {
    private static final int MAX_PLACEMENT_HISTORY_CANDIDATES = 16;
    private static final ThreadLocal<List<SimulatedMinecart>> LAST_MINECARTS =
        ThreadLocal.withInitial(List::of);
    /** When true, logs every WorldGenLevel method the proxy answers with its default fallback. */
    static final boolean PROXY_DEBUG = Boolean.getBoolean("seedexplorer.proxyDebug");

    /** File-based trace that survives Bootstrap's System.out redirection. Enabled via -Dseedexplorer.probeLog=<path>. */
    private static final String PROBE_LOG = System.getProperty("seedexplorer.probeLog");
    static void probe(String line) {
        if (PROBE_LOG == null) return;
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(PROBE_LOG), line + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private VanillaLootStructureSimulator() {
    }

    private static PredictionContext legacyContext(long seed, int dimension) {
        return new PredictionContext(seed, "26.3", dimension);
    }

    private static PredictionContext legacyCurrentContext(int dimension) {
        return new PredictionContext(0L, "26.3", dimension);
    }

    /** Exposes the Unsafe-built fake ServerLevel for the real-loot-table A/B probe. */
    public static Object fakeLevelForProbe() {
        return CaptureWorld.fakeServerLevel(0L);
    }

    /** Exposes the Unsafe-built fake ServerLevel for offline feature/structure proxies. */
    public static Object fakeLevelForSeed(long seed) {
        return CaptureWorld.fakeServerLevel(seed);
    }

    /**
     * Exposes a fake ServerLevel whose chunk source can answer 26.3 worldgen
     * height predicates with the active dimension's generator.
     */
    public static Object fakeLevelForSeed(long seed, ChunkGenerator generator) {
        return CaptureWorld.fakeServerLevel(seed, generator);
    }

    /** Generic simulation for any structure type (overworld default). */
    public static List<SimulatedContainer> simulate(long seed, ResourceKey<Structure> structureKey, int chunkX, int chunkZ) {
        return simulate(legacyContext(seed, 0), structureKey, chunkX, chunkZ);
    }

    /** Generic simulation with explicit dimension. */
    public static List<SimulatedContainer> simulate(long seed, int dimension,
                                                     ResourceKey<Structure> structureKey, int chunkX, int chunkZ) {
        return simulate(legacyContext(seed, dimension), structureKey, chunkX, chunkZ);
    }

    public static List<SimulatedContainer> simulate(PredictionContext context,
                                                     ResourceKey<Structure> structureKey,
                                                     int chunkX, int chunkZ) {
        return simulate(context.worldSeed(), context.dimension(), structureKey, chunkX, chunkZ,
            decorationIndex(structureKey, context));
    }

    public static List<SimulatedContainer> simulate(long seed, int dimension,
                                                     ResourceKey<Structure> structureKey, int chunkX, int chunkZ,
                                                     BundledLootTableLoader.VersionProfile profile) {
        return simulate(seed, dimension, structureKey, chunkX, chunkZ, decorationIndex(structureKey, profile));
    }

    /** Generic simulation with explicit dimension and decoration index for oracle research. */
    public static List<SimulatedContainer> simulate(long seed, int dimension,
                                                     ResourceKey<Structure> structureKey,
                                                     int chunkX, int chunkZ, int explicitIndex) {
        ChunkPos startChunk = new ChunkPos(chunkX, chunkZ);
        // The chunk arrives from confirmed structure detection, so the biome/placement
        // is already validated. Use the validated start path (matching the preview
        // simulator) so we don't re-run the biome predicate, which throws on the
        // offline lookup's unbound tags and falls back to a hardcoded whitelist that
        // only covers a handful of structures. That whitelist was the reason ruined
        // portal / igloo / ocean ruin / fortress / bastion / trial chamber all returned
        // empty here and fell through to the fabricated-seed preview fallback.
        StructureStart start = WorldgenEngine.generateSelectedStructureStart(
            seed, dimension, structureKey, startChunk);
        if (!start.isValid()) return List.of();

        return simulateGeneratedStart(seed, dimension, structureKey, start,
            canonicalPlacementChunks(start, startChunk), explicitIndex);
    }

    /** Research hook for proving whether a mutable start depends on chunk decoration order. */
    public static List<SimulatedContainer> simulateInChunkOrderForProbe(
        long seed, int dimension, ResourceKey<Structure> structureKey,
        int chunkX, int chunkZ, List<ChunkPos> placementOrder, int explicitIndex) {
        ChunkPos startChunk = new ChunkPos(chunkX, chunkZ);
        StructureStart start = WorldgenEngine.generateSelectedStructureStart(
            seed, dimension, structureKey, startChunk);
        if (!start.isValid()) return List.of();

        List<ChunkPos> expected = canonicalPlacementChunks(start, startChunk);
        if (placementOrder.size() != expected.size()
            || !new java.util.HashSet<>(placementOrder).equals(new java.util.HashSet<>(expected))) {
            throw new IllegalArgumentException("Placement order must contain every intersecting chunk exactly once");
        }
        return simulateGeneratedStart(seed, dimension, structureKey, start,
            List.copyOf(placementOrder), explicitIndex);
    }

    /**
     * Enumerates the bounded first-placement histories that can change a beached
     * shipwreck's persistent height and placement RNG. This is intended for
     * reconciliation with already-observed world data, not seed-only caching.
     */
    public static List<PlacementHistoryCandidate> simulateBeachedShipwreckCandidates(
        PredictionContext context, int startChunkX, int startChunkZ) {
        return simulateScatteredPlacementCandidates(context, BuiltinStructures.SHIPWRECK_BEACHED,
            startChunkX, startChunkZ);
    }

    /** Bounded first-chunk alternatives for structures whose placement locks a
     * terrain-relative height. These are hypotheses, not exhaustive generation
     * histories: later terrain edits and other decoration orders can still differ. */
    public static List<PlacementHistoryCandidate> simulateScatteredPlacementCandidates(
        PredictionContext context, ResourceKey<Structure> structureKey,
        int startChunkX, int startChunkZ) {
        if (context == null || context.dimension() != 0) return List.of();
        if (!BuiltinStructures.SHIPWRECK_BEACHED.equals(structureKey)
            && !BuiltinStructures.DESERT_PYRAMID.equals(structureKey)) {
            throw new IllegalArgumentException("No validated first-chunk model for " + structureKey);
        }
        ChunkPos startChunk = new ChunkPos(startChunkX, startChunkZ);
        StructureStart initial = WorldgenEngine.generateSelectedStructureStart(
            context.worldSeed(), context.dimension(), structureKey, startChunk);
        if (!initial.isValid()) return List.of();

        List<ChunkPos> chunks = canonicalPlacementChunks(initial, startChunk);
        if (chunks.isEmpty() || chunks.size() > MAX_PLACEMENT_HISTORY_CANDIDATES) {
            return List.of();
        }
        int structureIndex = decorationIndex(structureKey, context);
        List<PlacementHistoryCandidate> candidates = new ArrayList<>(chunks.size());
        for (ChunkPos firstChunk : chunks) {
            StructureStart fresh = WorldgenEngine.generateSelectedStructureStart(
                context.worldSeed(), context.dimension(), structureKey, startChunk);
            if (!fresh.isValid()) return List.of();
            List<ChunkPos> order = new ArrayList<>(chunks.size());
            order.add(firstChunk);
            for (ChunkPos chunk : chunks) {
                if (!chunk.equals(firstChunk)) order.add(chunk);
            }
            List<SimulatedContainer> containers = simulateGeneratedStart(
                context.worldSeed(), context.dimension(), structureKey, fresh,
                List.copyOf(order), structureIndex);
            candidates.add(new PlacementHistoryCandidate(firstChunk, containers));
        }
        return List.copyOf(candidates);
    }

    /**
     * Selects one distinct full history from complete observed container columns.
     * Unobserved columns provide no negative evidence; empty or ambiguous evidence
     * deliberately leaves the seed-only approximation unchanged.
     */
    public static Optional<List<SimulatedContainer>> selectUniqueContainerCandidate(
        List<PlacementHistoryCandidate> candidates,
        Collection<ObservedContainerPosition> observed) {
        if (candidates == null || candidates.isEmpty()
            || observed == null || observed.isEmpty()) return Optional.empty();

        Set<ObservedContainerPosition> observedPositions = Set.copyOf(observed);
        Set<Long> observedColumns = new HashSet<>();
        for (ObservedContainerPosition position : observedPositions) {
            observedColumns.add(containerColumn(position.x(), position.z()));
        }

        List<List<SimulatedContainer>> matchingOutputs = new ArrayList<>();
        for (PlacementHistoryCandidate candidate : candidates) {
            Set<ObservedContainerPosition> candidatePositions = new HashSet<>();
            for (SimulatedContainer container : candidate.containers()) {
                if (observedColumns.contains(containerColumn(container.x(), container.z()))) {
                    candidatePositions.add(new ObservedContainerPosition(
                        container.x(), container.y(), container.z()));
                }
            }
            if (!candidatePositions.equals(observedPositions)) continue;
            List<SimulatedContainer> output = canonicalContainerOutput(candidate.containers());
            if (!matchingOutputs.contains(output)) matchingOutputs.add(output);
        }
        return matchingOutputs.size() == 1
            ? Optional.of(matchingOutputs.getFirst()) : Optional.empty();
    }

    private static List<ChunkPos> canonicalPlacementChunks(StructureStart start,
                                                            ChunkPos startChunk) {
        return start.getPieces().stream()
            .flatMap(piece -> piece.getBoundingBox().intersectingChunks())
            .distinct()
            // A structure start is mutable world state. Scattered pieces, for
            // example, lock their terrain-relative Y the first time they are
            // placed and retain it while later chunks are decorated. Replay the
            // start chunk first as the canonical seed-only order, then keep the
            // remainder stable for reproducible offline predictions.
            .sorted(Comparator
                .comparingInt((ChunkPos chunk) -> chunk.equals(startChunk) ? 0 : 1)
                .thenComparingInt(ChunkPos::z)
                .thenComparingInt(ChunkPos::x))
            .toList();
    }

    private static List<SimulatedContainer> simulateGeneratedStart(
        long seed, int dimension, ResourceKey<Structure> structureKey,
        StructureStart start, List<ChunkPos> chunks, int explicitIndex) {

        Structure structure = start.getStructure();
        int step = structure.step().ordinal();
        int idx = explicitIndex;
        // Village features read terrain outside template writes. Include the
        // village in Beardifier so those reads see its adapted ground, rather
        // than the unadapted hill that can incorrectly block a tree.
        boolean village = isVillage(structureKey);
        CaptureWorld capture = new CaptureWorld(seed, dimension,
            village ? List.of(start) : terrainStartsFor(start), village);
        WorldGenLevel level = capture.proxy();
        Map<Long, StructureStart> sharedStarts = overlappingMineshaftStarts(seed, dimension, structureKey, start, chunks);
        boolean needsTerrain = needsTerrainPrefill(structureKey);
        boolean needsCompleteTerrain = needsCompleteTerrainPrefill(structureKey);
        if (needsCompleteTerrain) {
            // These pieces inspect columns across their complete template during
            // the first postProcess call. Fill every intersecting chunk before
            // mutating the shared start so unseen columns cannot read as air.
            for (ChunkPos chunk : chunks) {
                capture.prepareDecorationBefore(step, chunk, false);
            }
            for (ChunkPos chunk : chunks) capture.prefillTerrain(chunk, 0);
        }
        for (ChunkPos chunk : chunks) {
            // Earlier features matter only when the piece reads generated terrain.
            // Replaying unrelated ores for rigid templates can also re-enter locked
            // ProtoChunk sections in the offline world. Desert pyramids are the one
            // height-only exception because earlier surface features can move them.
            if (!needsCompleteTerrain
                && (needsTerrain || structureKey.equals(BuiltinStructures.DESERT_PYRAMID))) {
                capture.prepareDecorationBefore(step, chunk,
                    structureKey.equals(BuiltinStructures.DESERT_PYRAMID));
            }
            if (needsTerrain && !needsCompleteTerrain) {
                capture.prefillTerrain(chunk, 0);
            }

            WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
            long decorationSeed = random.setDecorationSeed(seed, chunk.getMinBlockX(), chunk.getMinBlockZ());
            random.setFeatureSeed(decorationSeed, idx, step);

            // Noise generation in the Nether/End is 128 blocks tall, but both
            // dimensions have a legal 0..255 build range and structures can extend
            // above the noise ceiling (notably End-city tower treasure rooms).
            int minY = dimension == 0 ? capture.minBuildY : 0;
            int maxY = dimension == 0 ? capture.maxBuildY : 255;
            BoundingBox writableArea = new BoundingBox(
                chunk.getMinBlockX(), minY, chunk.getMinBlockZ(),
                chunk.getMaxBlockX(), maxY, chunk.getMaxBlockZ());
            try {
                List<StructureStart> orderedStarts = structure.type() == StructureType.MINESHAFT
                    ? startsInReferenceOrder(sharedStarts, chunk) : List.of(start);
                for (StructureStart referenced : orderedStarts) {
                    capture.captureContainerEntities = referenced == start;
                    referenced.placeInChunk(level, null, WorldgenEngine.chunkGenerator(seed, dimension), random,
                        writableArea, chunk);
                }
                capture.captureContainerEntities = true;
            } catch (IllegalStateException e) {
                if (e.getMessage() != null && e.getMessage().contains("Tags not bound")) {
                    continue;
                }
                throw e;
            }
        }

        List<SimulatedContainer> result = capture.containers();
        LAST_MINECARTS.set(capture.minecarts());
        return result;
    }

    /** Vanilla seeds RNG once per structure registry ID, not once per start.
     * Nearby mineshafts sharing a placement chunk must consume the same stream. */
    private static Map<Long, StructureStart> overlappingMineshaftStarts(
        long seed, int dimension, ResourceKey<Structure> key, StructureStart selected,
        List<ChunkPos> chunks) {
        Map<Long, StructureStart> starts = new HashMap<>();
        starts.put(selected.getChunkPos().pack(), selected);
        if (selected.getStructure().type() != StructureType.MINESHAFT || chunks.isEmpty()) return starts;
        int minX = chunks.stream().mapToInt(ChunkPos::x).min().orElseThrow() - 8;
        int maxX = chunks.stream().mapToInt(ChunkPos::x).max().orElseThrow() + 8;
        int minZ = chunks.stream().mapToInt(ChunkPos::z).min().orElseThrow() - 8;
        int maxZ = chunks.stream().mapToInt(ChunkPos::z).max().orElseThrow() + 8;
        for (GeneratedStructure marker : VanillaStructurePredictor.predictStructure(
                seed, dimension, key.identifier().toString(), minX, minZ, maxX, maxZ)) {
            ChunkPos pos = new ChunkPos(marker.startChunkX, marker.startChunkZ);
            if (starts.containsKey(pos.pack())) continue;
            StructureStart other = WorldgenEngine.generateSelectedStructureStart(seed, dimension, key, pos);
            if (other.isValid() && chunks.stream().anyMatch(chunk ->
                    Math.abs(chunk.x() - pos.x()) <= 8 && Math.abs(chunk.z() - pos.z()) <= 8
                    && other.getBoundingBox().intersects(chunk.getMinBlockX(), chunk.getMinBlockZ(),
                        chunk.getMaxBlockX(), chunk.getMaxBlockZ()))) starts.put(pos.pack(), other);
        }
        return starts;
    }

    private static List<StructureStart> startsInReferenceOrder(Map<Long, StructureStart> starts, ChunkPos chunk) {
        // ChunkGenerator.createReferences inserts X-major/Z-minor into the default
        // LongOpenHashSet; StructureManager subsequently iterates that hash set.
        var references = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int x = chunk.x() - 8; x <= chunk.x() + 8; x++) {
            for (int z = chunk.z() - 8; z <= chunk.z() + 8; z++) {
                long position = ChunkPos.pack(x, z);
                StructureStart start = starts.get(position);
                if (start != null && start.getBoundingBox().intersects(chunk.getMinBlockX(), chunk.getMinBlockZ(),
                        chunk.getMaxBlockX(), chunk.getMaxBlockZ())) references.add(position);
            }
        }
        List<StructureStart> ordered = new ArrayList<>();
        for (long position : references) ordered.add(starts.get(position));
        return ordered;
    }

    /**
     * Replays a structure and returns only chest-minecart entities. The entity
     * coordinates retain their precise double position for map markers while the
     * integer block position remains suitable for loot/container matching.
     */
    public static List<SimulatedMinecart> simulateMinecarts(long seed, int dimension,
                                                              ResourceKey<Structure> structureKey,
                                                              int chunkX, int chunkZ) {
        return simulateMinecarts(legacyContext(seed, dimension), structureKey,
            chunkX, chunkZ);
    }

    public static List<SimulatedMinecart> simulateMinecarts(PredictionContext context,
                                                              ResourceKey<Structure> structureKey,
                                                              int chunkX, int chunkZ) {
        LAST_MINECARTS.set(List.of());
        simulate(context, structureKey, chunkX, chunkZ);
        return LAST_MINECARTS.get();
    }

    /** Backward-compatible overworld simulate with explicit index. */
    public static List<SimulatedContainer> simulate(long seed, ResourceKey<Structure> structureKey,
                                                     int chunkX, int chunkZ, int explicitIndex) {
        return simulate(seed, 0, structureKey, chunkX, chunkZ, explicitIndex);
    }

    /** Places one structure-decoration chunk for fast RNG-index research. */
    public static List<SimulatedContainer> simulateChunk(long seed,
                                                           ResourceKey<Structure> structureKey,
                                                           int startChunkX, int startChunkZ,
                                                           int placementChunkX, int placementChunkZ,
                                                           int explicitIndex) {
        StructureStart start = WorldgenEngine.generateStructureStart(
            seed, 0, structureKey, new ChunkPos(startChunkX, startChunkZ));
        if (!start.isValid()) return List.of();

        return simulateStartChunk(seed, start, placementChunkX, placementChunkZ,
            explicitIndex);
    }

    /** Replays one placement chunk over an index range while sharing its generated terrain. */
    public static Map<Integer, List<SimulatedContainer>> simulateChunkIndexSweep(
        long seed, ResourceKey<Structure> structureKey,
        int startChunkX, int startChunkZ, int placementChunkX, int placementChunkZ,
        int firstIndex, int lastIndex) {
        return simulateChunkIndexSweep(seed, structureKey, startChunkX, startChunkZ,
            placementChunkX, placementChunkZ, firstIndex, lastIndex, true);
    }

    /** Research overload for isolating earlier-decoration terrain effects. */
    public static Map<Integer, List<SimulatedContainer>> simulateChunkIndexSweep(
        long seed, ResourceKey<Structure> structureKey,
        int startChunkX, int startChunkZ, int placementChunkX, int placementChunkZ,
        int firstIndex, int lastIndex, boolean applyEarlierFeatures) {
        StructureStart initial = WorldgenEngine.generateSelectedStructureStart(
            seed, 0, structureKey, new ChunkPos(startChunkX, startChunkZ));
        if (!initial.isValid() || firstIndex > lastIndex) return Map.of();

        ChunkPos chunk = new ChunkPos(placementChunkX, placementChunkZ);
        CaptureWorld prepared = new CaptureWorld(seed, terrainStartsFor(initial));
        int step = initial.getStructure().step().ordinal();
        boolean needsTerrain = needsTerrainPrefill(structureKey);
        if (applyEarlierFeatures
            && (needsTerrain || structureKey.equals(BuiltinStructures.DESERT_PYRAMID))) {
            prepared.prepareDecorationBefore(step, chunk,
                structureKey.equals(BuiltinStructures.DESERT_PYRAMID));
        }
        if (needsTerrain) prepared.prefillTerrain(chunk, 0);

        Map<Integer, List<SimulatedContainer>> result = new java.util.LinkedHashMap<>();
        for (int index = firstIndex; index <= lastIndex; index++) {
            StructureStart start = WorldgenEngine.generateSelectedStructureStart(
                seed, 0, structureKey, new ChunkPos(startChunkX, startChunkZ));
            if (!start.isValid()) continue;
            CaptureWorld capture = new CaptureWorld(prepared);
            WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
            long decorationSeed = random.setDecorationSeed(
                seed, chunk.getMinBlockX(), chunk.getMinBlockZ());
            random.setFeatureSeed(decorationSeed, index, step);
            BoundingBox writableArea = new BoundingBox(
                chunk.getMinBlockX(), capture.minBuildY, chunk.getMinBlockZ(),
                chunk.getMaxBlockX(), capture.maxBuildY, chunk.getMaxBlockZ());
            try {
                start.placeInChunk(capture.proxy(), null, WorldgenEngine.chunkGenerator(seed, 0),
                    random, writableArea, chunk);
            } catch (IllegalStateException e) {
                if (e.getMessage() == null || !e.getMessage().contains("Tags not bound")) throw e;
            }
            result.put(index, capture.containers());
        }
        return Map.copyOf(result);
    }

    /** Places one chunk from an already-created or independently loaded start. */
    public static List<SimulatedContainer> simulateStartChunk(long seed,
                                                               StructureStart start,
                                                               int placementChunkX,
                                                               int placementChunkZ,
                                                               int explicitIndex) {
        return simulateStartChunk(seed, 0, start, placementChunkX, placementChunkZ, explicitIndex);
    }

    /** Dimension-aware saved-start replay used by Paper NBT parity research. */
    public static List<SimulatedContainer> simulateStartChunk(long seed, int dimension,
                                                               StructureStart start,
                                                               int placementChunkX,
                                                               int placementChunkZ,
                                                               int explicitIndex) {
        if (start == null || !start.isValid()) return List.of();

        CaptureWorld capture = new CaptureWorld(seed, dimension, terrainStartsFor(start));
        ChunkPos chunk = new ChunkPos(placementChunkX, placementChunkZ);
        StructureType<?> structureType = start.getStructure().type();
        int step = start.getStructure().step().ordinal();
        boolean needsTerrain = needsTerrainPrefillByType(structureType);
        if (needsTerrain || structureType == StructureType.DESERT_PYRAMID) {
            capture.prepareDecorationBefore(step, chunk,
                structureType == StructureType.DESERT_PYRAMID);
        }
        if (needsTerrain) {
            capture.prefillTerrain(chunk, 0);
        }

        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long decorationSeed = random.setDecorationSeed(
            seed, chunk.getMinBlockX(), chunk.getMinBlockZ());
        random.setFeatureSeed(decorationSeed, explicitIndex, step);
        BoundingBox writableArea = new BoundingBox(
            chunk.getMinBlockX(), -64, chunk.getMinBlockZ(),
            chunk.getMaxBlockX(), 319, chunk.getMaxBlockZ());
        try {
            start.placeInChunk(capture.proxy(), null, WorldgenEngine.chunkGenerator(seed, dimension),
                random, writableArea, chunk);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("Tags not bound")) {
                return List.of();
            }
            throw e;
        }
        return capture.containers();
    }

    /** Simulates feature-placed chests (e.g. dungeons) in a specific decoration step. */
    public static List<SimulatedContainer> simulateStep(long seed, int chunkX, int chunkZ, int decorationStep) {
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        var biomes = WorldgenEngine.decorationBiomes(seed, 0, chunkX, chunkZ);
        if (biomes.isEmpty()) return List.of();

        var steps = WorldgenEngine.featureSteps(seed, 0);
        if (decorationStep < 0 || decorationStep >= steps.size()) return List.of();

        var stepData = steps.get(decorationStep);
        java.util.TreeSet<Integer> indices = new java.util.TreeSet<>();
        for (Holder<Biome> biome : biomes) {
            var biomeSteps = biome.value().getGenerationSettings().features();
            if (decorationStep >= biomeSteps.size()) continue;
            for (Holder<PlacedFeature> feature : biomeSteps.get(decorationStep)) {
                int idx = stepData.indexMapping().applyAsInt(feature.value());
                if (idx >= 0) indices.add(idx);
            }
        }

        CaptureWorld capture = new CaptureWorld(seed);
        WorldGenLevel level = capture.proxy();
        int minY = WorldgenEngine.minBuildHeight(seed, 0);
        BlockPos origin = SectionPos.of(chunkPos, Math.floorDiv(minY, 16)).origin();
        capture.prepareDecorationBefore(decorationStep, chunkPos, false);
        // Use margin=2 so that in_square offset near the chunk edge does not place
        // the room's wall perimeter outside the prefilled area.
        capture.prefillTerrain(chunkPos, 2);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long decorationSeed = random.setDecorationSeed(seed, origin.getX(), origin.getZ());

        ChunkGenerator gen = WorldgenEngine.chunkGenerator(seed, 0);
        java.util.Set<PlacedFeature> placed = new java.util.HashSet<>();
        for (Holder<Biome> biome : biomes) {
            var biomeSteps = biome.value().getGenerationSettings().features();
            if (decorationStep >= biomeSteps.size()) continue;
            for (Holder<PlacedFeature> fh : biomeSteps.get(decorationStep)) {
                PlacedFeature origFeature = fh.value();
                if (!placed.add(origFeature)) continue;
                int idx = stepData.indexMapping().applyAsInt(origFeature);
                if (idx < 0) continue;
                random.setFeatureSeed(decorationSeed, idx, decorationStep);
                // MonsterRoomFeature's opening-count check requires 1-5 wall
                // openings at y=0 (relative) on the wall perimeter. Room half-sizes
                // are rand.nextInt(2)+2 = 2 or 3, placing walls at ±3 or ±4 from
                // center. Dig at both radii to guarantee at least one opening
                // regardless of the random room size chosen inside place().
                java.util.List<PlacementModifier> mods = origFeature.placement();
                java.util.List<BlockPos> positions = java.util.List.of(origin);
                PlacementContext placementContext = new PlacementContext(
                    level, gen, java.util.Optional.of(origFeature));
                for (PlacementModifier mod : mods) {
                    java.util.List<BlockPos> next = new java.util.ArrayList<>();
                    for (BlockPos position : positions) {
                        mod.modify(placementContext, random, position, next::add);
                    }
                    positions = next;
                }
                if (!positions.isEmpty()) {
                    BlockPos dungeonPos = positions.get(0);
                    int px = dungeonPos.getX(), py = dungeonPos.getY(), pz = dungeonPos.getZ();
                    // Dig 2-block-tall air at wall offsets ±3 and ±4 to cover both
                    // possible room half-sizes (2 or 3). Room sizes are determined
                    // inside MonsterRoomFeature.place() from the random state AFTER
                    // the placement modifiers; we cannot consume random here or it
                    // would desync the feature's internal state.
                    int[] radii = {3, 4};
                    for (int r : radii) {
                        for (int wy = py; wy <= py + 1; wy++) {
                            level.setBlock(new BlockPos(px + r, wy, pz), Blocks.AIR.defaultBlockState(), 0);
                            level.setBlock(new BlockPos(px - r, wy, pz), Blocks.AIR.defaultBlockState(), 0);
                            level.setBlock(new BlockPos(px, wy, pz + r), Blocks.AIR.defaultBlockState(), 0);
                            level.setBlock(new BlockPos(px, wy, pz - r), Blocks.AIR.defaultBlockState(), 0);
                        }
                    }
                }
                random.setFeatureSeed(decorationSeed, idx, decorationStep);
                boolean placedOk = new FeaturePlacer(level, gen)
                    .placeWithBiomeCheck(origFeature, random, origin);
                // Diagnostic: log placement result and block states at the first
                // placement position when the feature fails.
                if (!placedOk && !positions.isEmpty()) {
                    BlockPos dp = positions.get(0);
                    java.io.StringWriter diag = new java.io.StringWriter();
                    diag.write("feature_failed=" + origFeature + " idx=" + idx
                        + " origin=" + origin + " pos=" + dp + "\n");
                    diag.write("floor(y=-1) solid=" + !level.getBlockState(dp.below()).isAir() + "\n");
                    diag.write("ceil(y=4)  solid=" + !level.getBlockState(dp.above(4)).isAir() + "\n");
                    for (int r : new int[]{3, 4}) {
                        int openings = 0;
                        for (BlockPos wp : new BlockPos[]{
                            dp.offset(r, 0, 0), dp.offset(-r, 0, 0),
                            dp.offset(0, 0, r), dp.offset(0, 0, -r)}) {
                            if (level.getBlockState(wp).isAir() && level.getBlockState(wp.above()).isAir()) openings++;
                        }
                        diag.write("openings_at_r=" + r + ": " + openings + "\n");
                    }
                    diag.write("blockstates:\n");
                    for (int dy = -2; dy <= 5; dy++) {
                        BlockPos p = dp.above(dy);
                        diag.write("  y=" + (dy) + " " + level.getBlockState(p) + "\n");
                    }
                    try { java.nio.file.Files.writeString(java.nio.file.Paths.get("dungeon_debug.log"), diag.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); } catch (Exception e) {}
                }
            }
        }
        return capture.containers();
    }

    /** Replays only vanilla monster-room features for one chunk, including chest and spawner RNG. */
    public static List<SimulatedDungeon> simulateDungeons(long seed, int chunkX, int chunkZ) {
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        Set<Holder<Biome>> biomes = WorldgenEngine.decorationBiomes(seed, 0, chunkX, chunkZ);
        if (biomes.isEmpty()) return List.of();

        List<net.minecraft.world.level.biome.FeatureSorter.StepFeatureData> steps =
            WorldgenEngine.featureSteps(seed, 0);
        CaptureWorld capture = new CaptureWorld(seed, true);
        WorldGenLevel level = capture.proxy();
        int minY = WorldgenEngine.minBuildHeight(seed, 0);
        BlockPos origin = SectionPos.of(chunkPos, Math.floorDiv(minY, 16)).origin();
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long decorationSeed = random.setDecorationSeed(seed, origin.getX(), origin.getZ());
        ChunkGenerator generator = WorldgenEngine.chunkGenerator(seed, 0);
        placeDungeonTerrainStructures(capture, seed, chunkPos, generator);
        capture.clearCapturedEntities();
        for (int step = 0; step < steps.size(); step++) {
            var stepData = steps.get(step);
            java.util.TreeSet<Integer> indices = new java.util.TreeSet<>();
            boolean containsMonsterRoom = false;
            for (Holder<Biome> biome : biomes) {
                var biomeSteps = biome.value().getGenerationSettings().features();
                if (step >= biomeSteps.size()) continue;
                for (Holder<PlacedFeature> holder : biomeSteps.get(step)) {
                    PlacedFeature feature = holder.value();
                    int index = stepData.indexMapping().applyAsInt(feature);
                    if (index >= 0) indices.add(index);
                    if (isMonsterRoom(feature)) containsMonsterRoom = true;
                }
            }
            if (!containsMonsterRoom) continue;

            capture.prepareDecorationBefore(step, chunkPos, false);
            for (int index : indices) {
                PlacedFeature feature = stepData.features().get(index);
                try {
                    random.setFeatureSeed(decorationSeed, index, step);
                    new FeaturePlacer(level, generator)
                        .placeWithBiomeCheck(feature, random, origin);
                } catch (Throwable ignored) {
                    // A non-dungeon feature may require server-only context that is
                    // irrelevant to the monster-room terrain/opening checks. Vanilla
                    // feature seeds are independent, so later indices remain aligned.
                }
            }
        }
        return capture.dungeons();
    }

    /** Places underground structure pieces that can create the wall openings used by
     * MonsterRoomFeature's validity check. Their block entities are discarded before
     * dungeon capture so mineshaft cave-spider spawners are never reported as rooms. */
    private static void placeDungeonTerrainStructures(CaptureWorld capture, long seed,
                                                       ChunkPos chunkPos, ChunkGenerator generator) {
        int chunkMinX = chunkPos.x() - 10;
        int chunkMinZ = chunkPos.z() - 10;
        int chunkMaxX = chunkPos.x() + 10;
        int chunkMaxZ = chunkPos.z() + 10;
        List<GeneratedStructure> nearby = new ArrayList<>();
        nearby.addAll(VanillaStructurePredictor.predictStructure(seed, 0, "minecraft:mineshaft",
            chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ));
        nearby.addAll(VanillaStructurePredictor.predictStructure(seed, 0, "minecraft:mineshaft_mesa",
            chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ));
        nearby.addAll(VanillaStructurePredictor.predictStructure(seed, 0, "minecraft:stronghold",
            chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ));
        BoundingBox writableArea = new BoundingBox(chunkPos.getMinBlockX(), capture.minBuildY,
            chunkPos.getMinBlockZ(), chunkPos.getMaxBlockX(), capture.maxBuildY, chunkPos.getMaxBlockZ());

        for (GeneratedStructure structure : nearby) {
            ResourceKey<Structure> key = switch (structure.type) {
                case MINESHAFT -> structure.variant.equals("minecraft:mineshaft_mesa")
                    ? BuiltinStructures.MINESHAFT_MESA : BuiltinStructures.MINESHAFT;
                case STRONGHOLD -> BuiltinStructures.STRONGHOLD;
                default -> null;
            };
            if (key == null) continue;

            StructureStart start;
            try {
                start = WorldgenEngine.generateSelectedStructureStart(seed, 0, key,
                    new ChunkPos(structure.startChunkX, structure.startChunkZ));
            } catch (Throwable ignored) {
                continue;
            }
            if (start == null || !start.isValid() || start.getPieces().stream().noneMatch(piece ->
                piece.getBoundingBox().intersects(writableArea))) continue;

            int step = start.getStructure().step().ordinal();
            WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
            long decorationSeed = random.setDecorationSeed(seed, chunkPos.getMinBlockX(), chunkPos.getMinBlockZ());
            random.setFeatureSeed(decorationSeed, decorationIndex(key), step);
            try {
                start.placeInChunk(capture.proxy(), null, generator, random, writableArea, chunkPos);
            } catch (Throwable ignored) {
                // Terrain fallback still permits rooms not intersecting this structure.
            }
        }
    }

    private static boolean isMonsterRoom(PlacedFeature feature) {
        try {
            return feature.feature().value() instanceof MonsterRoomFeature;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Backward-compatible alias for desert pyramid simulation. */
    public static List<SimulatedContainer> desertPyramid(long seed, int chunkX, int chunkZ) {
        return simulate(seed, BuiltinStructures.DESERT_PYRAMID, chunkX, chunkZ);
    }

    /** Overload with explicit decoration index (for brute-force testing). */
    public static List<SimulatedContainer> desertPyramid(long seed, int chunkX, int chunkZ, int explicitIndex) {
        return simulate(seed, 0, BuiltinStructures.DESERT_PYRAMID, chunkX, chunkZ, explicitIndex);
    }

    public static int decorationIndex(ResourceKey<Structure> key) {
        return decorationIndex(key, legacyCurrentContext(0));
    }

    public static int decorationIndex(ResourceKey<Structure> key, PredictionContext context) {
        return decorationIndex(key, versionProfile(context));
    }

    public static int decorationIndex(ResourceKey<Structure> key, BundledLootTableLoader.VersionProfile profile) {
        if (BundledLootTableLoader.compareVersions(profile.minecraftVersion(), "26.3") >= 0) {
            return currentStructureDecorationIndex(key);
        }

        // Check version profile for overrides first (handles per-version
        // differences in the runtime STRUCTURE registry index mapping).
        String keyStr = key.identifier().toString();
        int override = profile.decorationIndexOverride(keyStr);
        if (override >= 0) return override;

        // Fall back to the lightweight STRUCTURE_TYPE registry order.  This is
        // retained for compatibility with the verified runtime profiles below;
        // structures whose Paper index differs are pinned explicitly there.
        var lookup = WorldgenEngine.vanillaLookup().lookupOrThrow(Registries.STRUCTURE);
        Structure target = lookup.getOrThrow(key).value();
        if (key.equals(BuiltinStructures.MINESHAFT) || key.equals(BuiltinStructures.MINESHAFT_MESA)) {
            return structureDecorationIndex(lookup, target, key);
        }
        var typeLookup = WorldgenEngine.vanillaLookup().lookupOrThrow(Registries.STRUCTURE_TYPE);
        StructureType<?> targetType = target.type();
        int typeIndex = 0;
        for (Holder<StructureType<?>> holder : typeLookup.listElements().toList()) {
            if (holder.value() == targetType) return typeIndex;
            typeIndex++;
        }
        throw new IllegalStateException("Structure type not found: " + targetType);
    }

    private static int currentStructureDecorationIndex(ResourceKey<Structure> key) {
        var lookup = WorldgenEngine.vanillaLookup().lookupOrThrow(Registries.STRUCTURE);
        Structure target = lookup.getOrThrow(key).value();
        return structureDecorationIndex(lookup, target, key);
    }

    private static int structureDecorationIndex(
        net.minecraft.core.HolderLookup.RegistryLookup<Structure> lookup,
        Structure target, ResourceKey<Structure> key) {
        // ChunkGenerator.applyBiomeDecoration groups every STRUCTURE registry
        // entry by decoration step and increments the feature index once per
        // structure. Datapack registry resources load in stable key order, so
        // sorting the offline lookup reproduces the live 26.3 server stream.
        // This deliberately counts distinct keys that share one StructureType.
        int structureIndex = 0;
        List<Holder.Reference<Structure>> stepStructures = lookup.listElements()
            .filter(holder -> holder.value().step() == target.step())
            .sorted(Comparator.comparing(holder -> holder.key().identifier().toString()))
            .toList();
        for (Holder<Structure> holder : stepStructures) {
            if (holder.value() == target) return structureIndex;
            structureIndex++;
        }
        throw new IllegalStateException("Structure not found in decoration step: " + key);
    }

    private static BundledLootTableLoader.VersionProfile versionProfile(PredictionContext context) {
        String version = context == null ? "" : context.minecraftVersion();
        if (version != null && !version.isBlank()) {
            return BundledLootTableLoader.VersionProfile.forVersion(version);
        }
        return BundledLootTableLoader.runtimeProfile();
    }

    private static List<StructureStart> terrainStartsFor(StructureStart start) {
        // Terrain-adapting structures participate in NOISE through Beardifier.
        // Strongholds use BURY; omitting their start leaves cave air where the
        // server creates solid terrain and changes every later placement RNG.
        return start.getStructure().type() == StructureType.STRONGHOLD
            ? List.of(start)
            : List.of();
    }

    private static boolean isVillage(ResourceKey<Structure> structureKey) {
        return structureKey.identifier().toString().startsWith("minecraft:village_");
    }

    /**
     * Whether a structure's pieces read the pre-existing terrain before placing
     * their containers, and so need the proxy's blocks map primed with the real
     * generated chunk. Buried treasure scans downward through the column for a
     * sandstone/stone floor before dropping its chest; mineshaft corridors
     * require a solid block below each minecart-chest rail. Without prefill the
     * floor reads as air and both place zero containers. Strongholds use BURY
     * terrain adaptation and also need the primed column for piece placement.
     *
     * Ruined portals, igloos and ocean ruins are surface-relative: their chest
     * pieces descend from the heightmap (igloo basement drops ~28 blocks below
     * the surface; ruined portals bury/set-down against terrain; ocean ruins
     * anchor to the ocean floor). Without a primed column the downward scan hits
     * air immediately and the chest resolves at the surface Y instead of its true
     * depth, so they need prefill too.
     */
    private static boolean needsTerrainPrefill(ResourceKey<Structure> structureKey) {
        return structureKey.identifier().toString().startsWith("minecraft:abandoned_camp_")
            || structureKey.equals(BuiltinStructures.STRONGHOLD)
            || structureKey.equals(BuiltinStructures.BURIED_TREASURE)
            || structureKey.equals(BuiltinStructures.MINESHAFT)
            || structureKey.equals(BuiltinStructures.MINESHAFT_MESA)
            || structureKey.equals(BuiltinStructures.IGLOO)
            || structureKey.equals(BuiltinStructures.OCEAN_RUIN_COLD)
            || structureKey.equals(BuiltinStructures.OCEAN_RUIN_WARM)
            || structureKey.equals(BuiltinStructures.RUINED_PORTAL_STANDARD)
            || structureKey.equals(BuiltinStructures.RUINED_PORTAL_DESERT)
            || structureKey.equals(BuiltinStructures.RUINED_PORTAL_JUNGLE)
            || structureKey.equals(BuiltinStructures.RUINED_PORTAL_SWAMP)
            || structureKey.equals(BuiltinStructures.RUINED_PORTAL_MOUNTAIN)
            || structureKey.equals(BuiltinStructures.RUINED_PORTAL_OCEAN)
            || structureKey.equals(BuiltinStructures.RUINED_PORTAL_NETHER);
    }

    private static boolean needsCompleteTerrainPrefill(ResourceKey<Structure> structureKey) {
        String id = structureKey.identifier().toString();
        return id.startsWith("minecraft:abandoned_camp_")
            || structureKey.equals(BuiltinStructures.IGLOO)
            || structureKey.equals(BuiltinStructures.OCEAN_RUIN_COLD)
            || structureKey.equals(BuiltinStructures.OCEAN_RUIN_WARM)
            || structureKey.equals(BuiltinStructures.RUINED_PORTAL_STANDARD)
            || structureKey.equals(BuiltinStructures.RUINED_PORTAL_DESERT)
            || structureKey.equals(BuiltinStructures.RUINED_PORTAL_JUNGLE)
            || structureKey.equals(BuiltinStructures.RUINED_PORTAL_SWAMP)
            || structureKey.equals(BuiltinStructures.RUINED_PORTAL_MOUNTAIN)
            || structureKey.equals(BuiltinStructures.RUINED_PORTAL_OCEAN)
            || structureKey.equals(BuiltinStructures.RUINED_PORTAL_NETHER);
    }

    private static boolean needsTerrainPrefillByType(StructureType<?> type) {
        return type == StructureType.STRONGHOLD
            || type == StructureType.BURIED_TREASURE
            || type == StructureType.MINESHAFT;
    }

    private static long containerColumn(int x, int z) {
        return (long) x << 32 ^ z & 0xFFFFFFFFL;
    }

    private static List<SimulatedContainer> canonicalContainerOutput(
        Collection<SimulatedContainer> containers) {
        if (containers == null || containers.isEmpty()) return List.of();
        return containers.stream()
            .sorted(Comparator.comparingInt(SimulatedContainer::x)
                .thenComparingInt(SimulatedContainer::y)
                .thenComparingInt(SimulatedContainer::z)
                .thenComparing(SimulatedContainer::lootTableId)
                .thenComparingLong(SimulatedContainer::lootSeed)
                .thenComparing(SimulatedContainer::pairedChest))
            .toList();
    }

    public record PlacementHistoryCandidate(ChunkPos firstChunk,
                                            List<SimulatedContainer> containers) {
        public PlacementHistoryCandidate {
            if (firstChunk == null) throw new IllegalArgumentException("firstChunk is required");
            containers = canonicalContainerOutput(containers);
        }
    }

    public record ObservedContainerPosition(int x, int y, int z) {
    }

    public record SimulatedContainer(int x, int y, int z, String lootTableId, long lootSeed,
                                     boolean pairedChest) {
        public SimulatedContainer(int x, int y, int z, String lootTableId, long lootSeed) {
            this(x, y, z, lootTableId, lootSeed, false);
        }
    }

    /** A container carried by a minecart entity rather than a placed block. */
    public record SimulatedMinecart(int x, int y, int z, double preciseX, double preciseY,
                                    double preciseZ, String lootTableId, long lootSeed) {
        public SimulatedContainer asContainer() {
            return new SimulatedContainer(x, y, z, lootTableId, lootSeed);
        }
    }

    public record SimulatedSpawner(int x, int y, int z, String entityId) {
    }

    public record SimulatedDungeon(int x, int y, int z, String spawnerEntityId,
                                   List<SimulatedContainer> containers) {
    }

    private static final class CaptureWorld implements InvocationHandler {
        private final long seed;
        private final int dimension;
        private final int minBuildY;
        private final int maxBuildY;
        private final int seaLevel;
        private final RandomSource worldRandom;
        private final ChunkGenerator generator;
        private final GeneratedTerrainHeightmap terrainHeightmap;
        private final boolean terrainFallback;
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
        private boolean captureContainerEntities = true;
        private final List<SimulatedContainer> capturedEntities = new ArrayList<>();
        private final List<SimulatedMinecart> capturedMinecarts = new ArrayList<>();
        private final Map<Heightmap.Types, Map<Long, Integer>> heights =
            new EnumMap<>(Heightmap.Types.class);
        private WorldGenLevel proxy;

        private CaptureWorld(long seed) {
            this(seed, 0, List.of(), false);
        }

        private CaptureWorld(long seed, boolean terrainFallback) {
            this(seed, 0, List.of(), terrainFallback);
        }

        private CaptureWorld(long seed, List<StructureStart> terrainStarts) {
            this(seed, 0, terrainStarts, false);
        }

        private CaptureWorld(long seed, int dimension, List<StructureStart> terrainStarts) {
            this(seed, dimension, terrainStarts, false);
        }

        private CaptureWorld(long seed, int dimension, List<StructureStart> terrainStarts,
                             boolean terrainFallback) {
            this.seed = seed;
            this.dimension = dimension;
            this.terrainFallback = terrainFallback;
            this.minBuildY = WorldgenEngine.minBuildHeight(seed, dimension);
            this.maxBuildY = minBuildY + WorldgenEngine.overallHeight(seed, dimension) - 1;
            this.seaLevel = dimension == -1 ? 32 : 63;
            this.worldRandom = RandomSource.create(seed ^ 0x5EEDC0DEL);
            this.generator = WorldgenEngine.chunkGenerator(seed, dimension);
            this.terrainHeightmap = new GeneratedTerrainHeightmap(seed, dimension, terrainStarts);
        }

        private CaptureWorld(CaptureWorld prepared) {
            this.seed = prepared.seed;
            this.dimension = prepared.dimension;
            this.minBuildY = prepared.minBuildY;
            this.maxBuildY = prepared.maxBuildY;
            this.seaLevel = prepared.seaLevel;
            this.worldRandom = RandomSource.create(seed ^ 0x5EEDC0DEL);
            this.generator = prepared.generator;
            this.terrainHeightmap = prepared.terrainHeightmap;
            this.terrainFallback = prepared.terrainFallback;
            this.blocks.putAll(prepared.blocks);
            for (Map.Entry<Heightmap.Types, Map<Long, Integer>> entry : prepared.heights.entrySet()) {
                this.heights.put(entry.getKey(), new HashMap<>(entry.getValue()));
            }
        }

        private WorldGenLevel proxy() {
            if (proxy == null) {
                proxy = (WorldGenLevel) Proxy.newProxyInstance(
                    WorldGenLevel.class.getClassLoader(),
                    new Class<?>[] { WorldGenLevel.class },
                    this);
            }
            return proxy;
        }

        private List<SimulatedContainer> containers() {
            List<SimulatedContainer> result = new ArrayList<>();
            for (Map.Entry<BlockPos, BlockEntity> entry : blockEntities.entrySet()) {
                if (entry.getValue() instanceof RandomizableContainer container) {
                    ResourceKey<LootTable> table = container.getLootTable();
                    if (table == null) continue;
                    BlockPos pos = entry.getKey();
                    BlockState state = blocks.get(pos);
                    traceBlock("final-container", pos, state);
                    boolean pairedChest = state != null && state.hasProperty(ChestBlock.TYPE)
                        && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
                    result.add(new SimulatedContainer(
                        pos.getX(), pos.getY(), pos.getZ(), table.identifier().toString(),
                        container.getLootTableSeed(), pairedChest));
                } else if (entry.getValue() instanceof net.minecraft.world.level.block.entity.BrushableBlockEntity brushable) {
                    var tag = brushable.saveWithoutMetadata(WorldgenEngine.offlineRegistryAccess());
                    String table = tag.getStringOr("LootTable", "");
                    if (table.isBlank()) continue;
                    BlockPos pos = entry.getKey();
                    result.add(new SimulatedContainer(pos.getX(), pos.getY(), pos.getZ(), table,
                        tag.getLongOr("LootTableSeed", 0L)));
                }
            }
            result.addAll(capturedEntities);
            result.sort(Comparator.comparingInt(SimulatedContainer::x)
                .thenComparingInt(SimulatedContainer::y)
                .thenComparingInt(SimulatedContainer::z));
            return List.copyOf(result);
        }

        private List<SimulatedMinecart> minecarts() {
            return List.copyOf(capturedMinecarts);
        }

        private void clearCapturedEntities() {
            blockEntities.clear();
            capturedEntities.clear();
            capturedMinecarts.clear();
        }

        private List<SimulatedDungeon> dungeons() {
            List<SimulatedContainer> containers = containers();
            List<SimulatedDungeon> result = new ArrayList<>();
            for (Map.Entry<BlockPos, BlockEntity> entry : blockEntities.entrySet()) {
                if (!(entry.getValue() instanceof SpawnerBlockEntity spawner)) continue;
                BlockPos pos = entry.getKey();
                List<SimulatedContainer> nearby = containers.stream()
                    .filter(container -> Math.abs(container.x() - pos.getX()) <= 8
                        && Math.abs(container.y() - pos.getY()) <= 1
                        && Math.abs(container.z() - pos.getZ()) <= 8)
                    .toList();
                result.add(new SimulatedDungeon(pos.getX(), pos.getY(), pos.getZ(),
                    spawnerEntityId(spawner), List.copyOf(nearby)));
            }
            result.sort(Comparator.comparingInt(SimulatedDungeon::x)
                .thenComparingInt(SimulatedDungeon::y)
                .thenComparingInt(SimulatedDungeon::z));
            return List.copyOf(result);
        }

        private String spawnerEntityId(SpawnerBlockEntity spawner) {
            try {
                var tag = spawner.getUpdateTag(WorldgenEngine.offlineRegistryAccess());
                var spawnData = tag.getCompoundOrEmpty("SpawnData");
                var entity = spawnData.getCompoundOrEmpty("entity");
                return entity.getString("id").orElse("minecraft:pig");
            } catch (Throwable ignored) {
                return "minecraft:pig";
            }
        }

        private void prepareDecorationBefore(int structureStep, ChunkPos chunk,
                                             boolean heightOnly) {
            if (heightOnly) {
                terrainHeightmap.applyHeightAffectingDecorationBefore(
                    chunk, structureStep);
            } else {
                terrainHeightmap.applyDecorationBefore(chunk, structureStep);
            }
        }

        private void prefillTerrain(ChunkPos centerChunk, int margin) {
            int minBuildY = terrainHeightmap.minBuildHeight();
            int maxBuildY = terrainHeightmap.maxBuildHeight();
            for (int cx = centerChunk.x() - margin; cx <= centerChunk.x() + margin; cx++) {
                for (int cz = centerChunk.z() - margin; cz <= centerChunk.z() + margin; cz++) {
                    prefillChunk(new ChunkPos(cx, cz));
                }
            }
        }

        private void prefillChunk(ChunkPos chunkPos) {
            int minBuildY = terrainHeightmap.minBuildHeight();
            int maxBuildY = terrainHeightmap.maxBuildHeight();
            int baseX = chunkPos.getMinBlockX();
            int baseZ = chunkPos.getMinBlockZ();
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int worldX = baseX + dx;
                    int worldZ = baseZ + dz;
                    int surfaceY = terrainHeightmap.firstFreeHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
                    int columnMaxY = Math.min(surfaceY, maxBuildY);
                    for (int y = minBuildY; y < columnMaxY; y++) {
                        BlockPos pos = new BlockPos(worldX, y, worldZ);
                        BlockState state = terrainHeightmap.blockState(seed, worldX, y, worldZ);
                        if (!state.isAir()) {
                            blocks.put(pos, state);
                        }
                    }
                }
            }
        }

        @Override
        public Object invoke(Object proxyObject, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("getSeed")) return seed;
            if (name.equals("getRandom")) return worldRandom;
            if (name.equals("registryAccess")) {
                return WorldgenEngine.offlineRegistryAccess();
            }
            if (name.equals("holderLookup")) {
                net.minecraft.resources.ResourceKey<?> key = (net.minecraft.resources.ResourceKey<?>) args[0];
                for (var r : net.minecraft.core.registries.BuiltInRegistries.REGISTRY) {
                    if (r.key().equals(key)) return r;
                }
                return null;
            }
            if (name.equals("enabledFeatures")) return FeatureFlags.DEFAULT_FLAGS;
            if (name.equals("environmentAttributes")) return EnvironmentAttributeReader.EMPTY;
            if (name.equals("isClientSide")) return false;
            if (name.equals("getSeaLevel")) return seaLevel;
            if (name.equals("getMinY") || name.equals("getMinBuildHeight")) return minBuildY;
            if (name.equals("getMaxY")) return maxBuildY;
            if (name.equals("getHeight")) {
                if (args != null && args.length == 3 && args[0] instanceof Heightmap.Types type
                    && args[1] instanceof Integer x && args[2] instanceof Integer z) {
                    return height(type, x, z);
                }
                return maxBuildY - minBuildY + 1;
            }
            if (name.equals("getHeightmapPos")) {
                Heightmap.Types type = (Heightmap.Types) args[0];
                BlockPos pos = (BlockPos) args[1];
                return new BlockPos(pos.getX(), height(type, pos.getX(), pos.getZ()), pos.getZ());
            }
            if (name.equals("isEmptyBlock")) {
                return stateAt(immutable((BlockPos) args[0])).isAir();
            }
            // Tree placement uses LevelSimulatedReader predicates for collision
            // checks. Returning the proxy's default false aborts valid trees and
            // skips their RNG draws before subsequent village containers.
            if (name.equals("isStateAtPosition")) {
                @SuppressWarnings("unchecked")
                var predicate = (java.util.function.Predicate<BlockState>) args[1];
                return predicate.test(stateAt(immutable((BlockPos) args[0])));
            }
            if (name.equals("isFluidAtPosition")) {
                @SuppressWarnings("unchecked")
                var predicate = (java.util.function.Predicate<net.minecraft.world.level.material.FluidState>) args[1];
                return predicate.test(stateAt(immutable((BlockPos) args[0])).getFluidState());
            }
            if (name.equals("getBlockState")) {
                BlockPos pos = immutable((BlockPos) args[0]);
                BlockState state = stateAt(pos);
                traceBlock("getBlockState", pos, state);
                return state;
            }
            if (name.equals("getFluidState")) {
                BlockPos pos = immutable((BlockPos) args[0]);
                return stateAt(pos).getFluidState();
            }
            if (name.equals("setBlock")) {
                BlockPos pos = immutable((BlockPos) args[0]);
                BlockState state = (BlockState) args[1];
                traceBlock("setBlock", pos, state);
                blocks.put(pos, state);
                BlockEntity entity = blockEntityFor(pos, state);
                if (entity != null) {
                    blockEntities.put(pos, entity);
                } else {
                    blockEntities.remove(pos);
                }
                return true;
            }
            if (name.equals("getBlockEntity")) {
                BlockEntity entity = blockEntities.get(immutable((BlockPos) args[0]));
                if (args.length == 2) return Optional.ofNullable(entity);
                return entity;
            }
            if (name.equals("addFreshEntity") || name.equals("addEntity")) {
                Entity entity = (Entity) args[0];
                if (captureContainerEntities && entity instanceof ContainerEntity container) {
                    ResourceKey<LootTable> table = container.getContainerLootTable();
                    if (table != null) {
                        BlockPos pos = entity.blockPosition();
                        long lootSeed = container.getContainerLootTableSeed();
                        SimulatedContainer captured = new SimulatedContainer(
                            pos.getX(), pos.getY(), pos.getZ(), table.identifier().toString(), lootSeed);
                        capturedEntities.add(captured);
                        if (entity instanceof net.minecraft.world.entity.vehicle.minecart.MinecartChest) {
                            capturedMinecarts.add(new SimulatedMinecart(entity.getBlockX(), entity.getBlockY(),
                                entity.getBlockZ(), entity.getX(), entity.getY(), entity.getZ(),
                                table.identifier().toString(), lootSeed));
                        }
                    }
                }
                return true;
            }
            if (name.equals("getLevel")) {
                return fakeServerLevel(seed, generator, proxy());
            }
            if (name.equals("removeBlock") || name.equals("destroyBlock")) {
                BlockPos pos = immutable((BlockPos) args[0]);
                traceBlock(name, pos, Blocks.AIR.defaultBlockState());
                // Keep an explicit air override so terrain fallback cannot
                // resurrect the removed block on the next read.
                blocks.put(pos, Blocks.AIR.defaultBlockState());
                blockEntities.remove(pos);
                return true;
            }
            if (name.equals("getChunk")) {
                if (args != null && args.length >= 2 && args[0] instanceof Integer cx && args[1] instanceof Integer cz) {
                    return terrainHeightmap.getChunk(new net.minecraft.world.level.ChunkPos(cx, cz));
                }
                if (args != null && args.length >= 1 && args[0] instanceof BlockPos p) {
                    return terrainHeightmap.getChunk(new net.minecraft.world.level.ChunkPos(
                        net.minecraft.core.SectionPos.blockToSectionCoord(p.getX()),
                        net.minecraft.core.SectionPos.blockToSectionCoord(p.getZ())));
                }
            }
            if (name.equals("ensureCanWrite") || name.equals("hasChunk") || name.equals("hasChunkAt")) return true;
            if (name.equals("getBiome") && args != null && args.length == 1 && args[0] instanceof BlockPos pos) {
                return tagBoundBiome(WorldgenEngine.getBiomeHolder(seed, dimension, pos.getX(), pos.getY(), pos.getZ()));
            }
            if (name.equals("getUncachedNoiseBiome")) {
                int quartX = (Integer) args[0];
                int quartY = (Integer) args[1];
                int quartZ = (Integer) args[2];
                return tagBoundBiome(WorldgenEngine.getBiomeHolder(seed, dimension, quartX * 4, quartY * 4, quartZ * 4));
            }
            // Some structure pieces spawn mobs during placement (e.g. bastion piglins),
            // whose finalizeSpawn -> enchantSpawnedEquipment reads getCurrentDifficultyAt().
            // Mob equipment is cosmetic and never affects chest loot RNG, but a null
            // DifficultyInstance NPEs and aborts placement. Return a valid NORMAL instance.
            if (name.equals("getCurrentDifficultyAt")) {
                return new net.minecraft.world.DifficultyInstance(
                    net.minecraft.world.Difficulty.NORMAL, 0L, 0L, 0.0f);
            }
            if (name.equals("getDifficulty")) {
                return net.minecraft.world.Difficulty.NORMAL;
            }
            if (PROXY_DEBUG) {
                StringBuilder sig = new StringBuilder(name).append("(");
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        if (i > 0) sig.append(",");
                        sig.append(args[i] == null ? "null"
                            : args[i].getClass().getSimpleName() + ":" + truncate(args[i]));
                    }
                }
                sig.append(")->").append(method.getReturnType().getSimpleName());
                System.out.println("[proxy-default] " + sig);
            }
            return defaultValue(method.getReturnType());
        }

        private static void traceBlock(String operation, BlockPos pos, BlockState state) {
            String target = System.getProperty("seedexplorer.traceBlock", "");
            if (target.isBlank()) return;
            String coordinate = pos.getX() + "," + pos.getY() + "," + pos.getZ();
            if (!target.equals("*") && !target.equals(coordinate)) return;
            StringBuilder trace = new StringBuilder("[structure-trace] ")
                .append(operation).append(' ').append(coordinate)
                .append(" state=").append(state);
            StackWalker.getInstance().forEach(frame -> {
                if (frame.getClassName().startsWith("me.seedexplorer")
                    || frame.getClassName().startsWith("net.minecraft")) {
                    trace.append("\n  at ").append(frame.getClassName()).append('.')
                        .append(frame.getMethodName()).append('(').append(frame.getFileName())
                        .append(':').append(frame.getLineNumber()).append(')');
                }
            });
            probe(trace.toString());
        }

        private int height(Heightmap.Types type, int x, int z) {
            long key = ((long) x << 32) ^ (z & 0xffffffffL);
            return heights.computeIfAbsent(type, ignored -> new HashMap<>()).computeIfAbsent(key,
                ignored -> terrainHeightmap.firstFreeHeight(type, x, z));
        }

        private BlockState stateAt(BlockPos pos) {
            BlockState state = blocks.get(pos);
            if (state != null || blocks.containsKey(pos)) return state;
            if (terrainFallback) {
                return terrainHeightmap.blockState(seed, pos.getX(), pos.getY(), pos.getZ());
            }
            return Blocks.AIR.defaultBlockState();
        }

        private static BlockPos immutable(BlockPos pos) {
            return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
        }

        /**
         * The biome source used offline returns holders from a lookup whose tags
         * are unbound, so vanilla structure code that calls biome.is(someTag)
         * (e.g. MineShaftPiece.isInInvalidLocation checking BiomeTags.MINESHAFT_BLOCKING)
         * throws "Tags not bound" and aborts placement. Remap the holder to the
         * tag-bound biome registry by key so those checks behave like vanilla.
         */
        @SuppressWarnings("unchecked")
        private static Holder<Biome> tagBoundBiome(Holder<Biome> biome) {
            if (biome == null) return null;
            try {
                if (biome instanceof Holder.Reference<Biome> ref && ref.tags().findAny().isPresent()) {
                    return biome;
                }
            } catch (Throwable ignored) {
                // tags unbound — fall through to remap
            }
            try {
                ResourceKey<Biome> key = biome.unwrapKey().orElse(null);
                if (key == null) return biome;
                return (Holder<Biome>) (Holder<?>) WorldgenEngine.offlineRegistryAccess()
                    .lookupOrThrow(Registries.BIOME).getOrThrow(key);
            } catch (Throwable ignored) {
                return biome;
            }
        }

        private static String truncate(Object o) {
            String s = String.valueOf(o);
            return s.length() > 40 ? s.substring(0, 40) + "…" : s;
        }

        /**
         * The minecart-chest spawn path (MineShaftCorridor.createChest) needs a
         * concrete ServerLevel: it calls EntityType.create(getLevel(), ...), which
         * dereferences level.enabledFeatures(). ServerLevel/MinecraftServer are
         * concrete classes, so we cannot use a reflection Proxy. Instead allocate
         * both without running a constructor (Unsafe.allocateInstance) and wire only
         * the one chain that path touches:
         *   ServerLevel.enabledFeatures() -> server.getWorldData().enabledFeatures()
         * The WorldData is a proxied interface returning DEFAULT_FLAGS. Everything
         * else on these instances stays null/zero; the minecart constructor never
         * reads it (verified against 26.1.2 bytecode). Cached and shared.
         */
        private static volatile Object fakeServerLevel;
        private static volatile long fakeServerLevelSeed = Long.MIN_VALUE;
        private static volatile ChunkGenerator fakeServerLevelGenerator;
        private static volatile WorldGenLevel fakeServerLevelWorld;
        private static Object fakeServerLevel(long seed) {
            return fakeServerLevel(seed, null);
        }

        private static Object fakeServerLevel(long seed, ChunkGenerator generator) {
            return fakeServerLevel(seed, generator, null);
        }

        private static Object fakeServerLevel(long seed, ChunkGenerator generator,
                                              WorldGenLevel world) {
            Object cached = fakeServerLevel;
            if (cached != null && fakeServerLevelSeed == seed
                && (generator == null || fakeServerLevelGenerator == generator)
                && (world == null || fakeServerLevelWorld == world)) return cached;
            synchronized (VanillaLootStructureSimulator.class) {
                if (fakeServerLevel != null && fakeServerLevelSeed == seed
                    && (generator == null || fakeServerLevelGenerator == generator)
                    && (world == null || fakeServerLevelWorld == world)) {
                    return fakeServerLevel;
                }
                try {
                    sun.misc.Unsafe unsafe = unsafe();

                    Class<?> serverLevelClass = Class.forName("net.minecraft.server.level.ServerLevel");
                    Class<?> minecraftServerClass = Class.forName("net.minecraft.server.MinecraftServer");
                    // MinecraftServer is abstract, so Unsafe.allocateInstance rejects it.
                    // Allocate the concrete DedicatedServer subclass instead; the worldData
                    // field lives on the MinecraftServer parent and setField searches up.
                    Class<?> concreteServerClass = Class.forName("net.minecraft.server.dedicated.DedicatedServer");
                    Class<?> worldDataClass = Class.forName("net.minecraft.world.level.storage.WorldData");

                    Object worldData = Proxy.newProxyInstance(
                        worldDataClass.getClassLoader(),
                        new Class<?>[] { worldDataClass },
                        (p, m, a) -> {
                            if (m.getName().equals("enabledFeatures")) return FeatureFlags.DEFAULT_FLAGS;
                            return defaultValue(m.getReturnType());
                        });

                    Object server = unsafe.allocateInstance(concreteServerClass);
                    setField(unsafe, server, minecraftServerClass, worldDataClass, worldData);
                    WorldOptions worldOptions = new WorldOptions(seed, true, false);
                    WorldGenSettings settings;
                    try {
                        settings = WorldGenSettings.of(
                            worldOptions, WorldgenEngine.offlineRegistryAccess());
                    } catch (Throwable unavailableDimensions) {
                        // Structure/feature replay reads only options().seed(). The
                        // headless registry may omit the level-stem registry, so an
                        // options field is the correct minimal server stub. The public
                        // constructor rejects a registry without an overworld stem.
                        settings = (WorldGenSettings) unsafe.allocateInstance(
                            WorldGenSettings.class);
                        if (!setFieldByName(unsafe, settings, WorldGenSettings.class,
                            "options", worldOptions)) {
                            throw new IllegalStateException(
                                "Cannot wire offline world options", unavailableDimensions);
                        }
                    }
                    if (!setFieldByName(unsafe, server, minecraftServerClass,
                        "worldGenSettings", settings)) {
                        setField(unsafe, server, minecraftServerClass,
                            WorldGenSettings.class, settings);
                    }
                    try {
                        setField(unsafe, server, minecraftServerClass,
                            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager.class,
                            WorldgenEngine.structureTemplateManager());
                    } catch (Throwable t) {
                        probe("[fakeServerLevel] structureTemplateManager wiring failed: " + t);
                    }

                    Object level = unsafe.allocateInstance(serverLevelClass);
                    setField(unsafe, level, serverLevelClass, minecraftServerClass, server);
                    if (world != null) {
                        StructureManager structureManager = new StructureManager(
                            world, worldOptions, null);
                        setField(unsafe, level, serverLevelClass,
                            StructureManager.class, structureManager);
                    }

                    Class<?> serverLevelDataClass = Class.forName(
                        "net.minecraft.world.level.storage.ServerLevelData");
                    Object levelData = Proxy.newProxyInstance(
                        serverLevelDataClass.getClassLoader(),
                        new Class<?>[] { serverLevelDataClass },
                        (p, m, a) -> switch (m.getName()) {
                            case "getDifficulty" -> net.minecraft.world.Difficulty.NORMAL;
                            case "getGameType" -> net.minecraft.world.level.GameType.SURVIVAL;
                            case "getLevelName" -> "Seed Explorer Offline Replay";
                            case "isInitialized" -> true;
                            default -> defaultValue(m.getReturnType());
                        });
                    setFieldByName(unsafe, level, serverLevelClass,
                        "serverLevelData", levelData);
                    setFieldByName(unsafe, level, serverLevelClass,
                        "levelData", levelData);

                    // 26.3 allocates entity ids through ServerLevel.chunkSource.
                    // The replay never loads chunks through this object; it only
                    // needs an empty tracking map so MinecartChest construction can
                    // prove that a newly selected id is unused.
                    try {
                        Class<?> chunkSourceClass = Class.forName(
                            "net.minecraft.server.level.ServerChunkCache");
                        Class<?> chunkMapClass = Class.forName(
                            "net.minecraft.server.level.ChunkMap");
                        Object chunkMap = unsafe.allocateInstance(chunkMapClass);
                        setFieldByName(unsafe, chunkMap, chunkMapClass, "entityMap",
                            new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>());
                        if (generator != null) {
                            var worldGenContext = new net.minecraft.world.level.chunk.status.WorldGenContext(
                                (net.minecraft.server.level.ServerLevel) level,
                                generator,
                                WorldgenEngine.structureTemplateManager(),
                                null,
                                Runnable::run,
                                ignored -> {
                                });
                            setFieldByName(unsafe, chunkMap, chunkMapClass,
                                "worldGenContext", worldGenContext);
                        }
                        Object chunkSource = unsafe.allocateInstance(chunkSourceClass);
                        setFieldByName(unsafe, chunkSource, chunkSourceClass,
                            "chunkMap", chunkMap);
                        setFieldByName(unsafe, level, serverLevelClass,
                            "chunkSource", chunkSource);
                    } catch (Throwable t) {
                        probe("[fakeServerLevel] chunkSource wiring failed: " + t);
                    }

                    // Some structure pieces (e.g. the End Ship's item frame) call
                    // level.playSound() during placement, which reads soundSeedGenerator.
                    // Sound RNG is independent of loot RNG, so any non-null RandomSource
                    // suffices to keep placement from NPEing. Set every RandomSource field
                    // on ServerLevel/Level by name.
                    RandomSource soundRng = RandomSource.create(0L);
                    setFieldByNameIfPresent(unsafe, level, serverLevelClass, "soundSeedGenerator", soundRng);
                    setFieldByNameIfPresent(unsafe, level, serverLevelClass, "random", soundRng);

                    // ServerLevel.playSeededSound calls server.getPlayerList().broadcast(),
                    // which iterates the players list. Stub a PlayerList with an empty list
                    // so the (cosmetic) End Ship item-frame sound broadcast is a no-op. The
                    // treasure chest itself is captured via the block-entity loot-table path,
                    // independent of this.
                    try {
                        Class<?> playerListClass = Class.forName("net.minecraft.server.dedicated.DedicatedPlayerList");
                        Class<?> playerListBase = Class.forName("net.minecraft.server.players.PlayerList");
                        Object playerList = unsafe.allocateInstance(playerListClass);
                        setFieldByNameIfPresent(unsafe, playerList, playerListBase, "players", new ArrayList<>());
                        setFieldByNameIfPresent(unsafe, server, minecraftServerClass, "playerList", playerList);
                    } catch (Throwable ignored) {
                        // If the player-list stub can't be built, placement of sound-playing
                        // cosmetic markers may throw, but loot capture for non-sound pieces
                        // is unaffected.
                    }

                    // Level.registryAccess() is a plain field read. In an integrated server,
                    // use its live registries so runtime data-pack tags/components match the
                    // loot table being decoded. Headless probes retain the offline registry.
                    try {
                        Class<?> registryAccessClass = Class.forName("net.minecraft.core.RegistryAccess");
                        setField(unsafe, level, serverLevelClass, registryAccessClass,
                            lootRegistryAccess());
                    } catch (Throwable ignored) {
                        // registryAccess not wired — structure placement still works; only the
                        // real-loot-table path (if used) would need it.
                    }

                    // Running real vanilla loot tables builds a LootContext that reads
                    // server.reloadableRegistries() = server.resources.managers.fullRegistryHolder.
                    // ReloadableServerRegistries$Holder has a public ctor taking a
                    // HolderLookup.Provider, so build the holder from the offline registry and
                    // Unsafe-wire the two intermediate records onto the fake server.
                    try {
                        Class<?> holderClass = Class.forName(
                            "net.minecraft.server.ReloadableServerRegistries$Holder");
                        Class<?> resourcesClass = Class.forName(
                            "net.minecraft.server.ReloadableServerResources");
                        Class<?> reloadableResourcesClass = Class.forName(
                            "net.minecraft.server.MinecraftServer$ReloadableResources");

                        Object holder = liveReloadableRegistryHolder();
                        if (holder == null) {
                            holder = holderClass
                                .getConstructor(net.minecraft.core.HolderLookup.Provider.class)
                                .newInstance(WorldgenEngine.offlineRegistryAccess());
                        }

                        // ReloadableServerResources is a normal class — Unsafe can set its
                        // fullRegistryHolder. MinecraftServer$ReloadableResources is a RECORD,
                        // so Unsafe.objectFieldOffset rejects its fields; build it via its
                        // canonical constructor instead (resourceManager can be null since
                        // only .managers is ever read on this path).
                        Object managers = unsafe.allocateInstance(resourcesClass);
                        boolean m1 = setFieldByName(unsafe, managers, resourcesClass,
                            "fullRegistryHolder", holder);

                        Class<?> resourceManagerClass = Class.forName(
                            "net.minecraft.server.packs.resources.CloseableResourceManager");
                        // The record constructor is private in 26.1.2.  This is an
                        // internal test-only fake server, so make the constructor
                        // accessible rather than silently leaving resources null and
                        // losing the real vanilla loot-table A/B path.
                        java.lang.reflect.Constructor<?> reloadableResourcesCtor =
                            reloadableResourcesClass.getDeclaredConstructor(resourceManagerClass, resourcesClass);
                        reloadableResourcesCtor.setAccessible(true);
                        Object reloadableResources = reloadableResourcesCtor.newInstance(null, managers);

                        boolean m3 = setFieldByName(unsafe, server, minecraftServerClass,
                            "resources", reloadableResources);
                        probe("[fakeServerLevel] wired reloadableRegistries: fullRegistryHolder=" + m1
                            + " resources=" + m3);
                    } catch (Throwable t) {
                        probe("[fakeServerLevel] reloadableRegistries wiring failed: " + t);
                        // reloadableRegistries not wired — the real-loot-table path would fail,
                        // but the hand-rolled simulator (default) does not use it.
                    }

                    fakeServerLevel = level;
                    fakeServerLevelSeed = seed;
                    fakeServerLevelGenerator = generator;
                    fakeServerLevelWorld = world;
                    return level;
                } catch (Throwable t) {
                    probe("[fakeServerLevel] failed: " + t);
                    return null;
                }
            }
        }

        private static net.minecraft.core.RegistryAccess lootRegistryAccess() {
            try {
                net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
                net.minecraft.server.MinecraftServer server = minecraft == null
                    ? null : minecraft.getSingleplayerServer();
                if (server != null) return server.registryAccess();
            } catch (Throwable ignored) {
            }
            return WorldgenEngine.offlineRegistryAccess();
        }

        private static Object liveReloadableRegistryHolder() {
            try {
                net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
                net.minecraft.server.MinecraftServer server = minecraft == null
                    ? null : minecraft.getSingleplayerServer();
                return server == null ? null : server.reloadableRegistries();
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static sun.misc.Unsafe unsafe() throws Exception {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        }

        /** Sets the single declared field of type fieldType on obj (searching up the hierarchy). */
        private static void setField(sun.misc.Unsafe unsafe, Object obj, Class<?> owner,
                                     Class<?> fieldType, Object value) {
            for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    if (field.getType().equals(fieldType)) {
                        long offset = unsafe.objectFieldOffset(field);
                        unsafe.putObject(obj, offset, value);
                        return;
                    }
                }
            }
            throw new IllegalStateException("no field of type " + fieldType + " on " + owner);
        }

        /** Sets a field by name (searching up the hierarchy). No-op if the field is absent. */
        private static void setFieldByNameIfPresent(sun.misc.Unsafe unsafe, Object obj,
                                                    Class<?> owner, String fieldName, Object value) {
            setFieldByName(unsafe, obj, owner, fieldName, value);
        }

        /** Sets a field by name (searching up the hierarchy). Returns true if the field was found and set. */
        private static boolean setFieldByName(sun.misc.Unsafe unsafe, Object obj,
                                              Class<?> owner, String fieldName, Object value) {
            for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field field = c.getDeclaredField(fieldName);
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) return false;
                    long offset = unsafe.objectFieldOffset(field);
                    unsafe.putObject(obj, offset, value);
                    return true;
                } catch (NoSuchFieldException ignored) {
                    // try superclass
                }
            }
            return false;
        }

        private static BlockEntity blockEntityFor(BlockPos pos, BlockState state) {
            Block block = state.getBlock();
            if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST
                || Blocks.COPPER_CHEST.asList().contains(block)) {
                return new ChestBlockEntity(pos, state);
            }
            if (block == Blocks.BARREL) return new BarrelBlockEntity(pos, state);
            if (block == Blocks.DISPENSER) return new DispenserBlockEntity(pos, state);
            if (block == Blocks.DROPPER) return new DropperBlockEntity(pos, state);
            if (block == Blocks.HOPPER) return new HopperBlockEntity(pos, state);
            if (block == Blocks.SPAWNER) return new SpawnerBlockEntity(pos, state);
            if (block == Blocks.SUSPICIOUS_SAND || block == Blocks.SUSPICIOUS_GRAVEL) {
                return new net.minecraft.world.level.block.entity.BrushableBlockEntity(pos, state);
            }
            if (isShulkerBox(block)) return new ShulkerBoxBlockEntity(pos, state);
            return null;
        }

        private static boolean isShulkerBox(Block block) {
            return block == Blocks.SHULKER_BOX || Blocks.DYED_SHULKER_BOX.asList().contains(block);
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0F;
            if (type == double.class) return 0D;
            if (type == char.class) return '\0';
            return null;
        }
    }
}
