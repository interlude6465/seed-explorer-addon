/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.ore;

import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.RarityFilter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Predicts ore marker positions from vanilla placed-feature seed math.
 */
public class OrePredictor {
    private static final int OVERWORLD_ORE_STEP = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
    private static final int FEATURE_WRITE_RADIUS_CHUNKS = 1;
    private static final ConcurrentMap<FeatureListKey, List<OreFeatureSpec>> FEATURE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ChunkOreKey, List<OrePatch>> CHUNK_CACHE = new ConcurrentHashMap<>();

    /**
     * Predicts all ore patches within a single chunk.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param seed   The world seed
     * @param dimension The dimension to predict for (0=Overworld, -1=Nether, 1=End)
     * @return List of predicted OrePatch objects for this chunk
     */
    public static List<OrePatch> predictInChunk(int chunkX, int chunkZ, long seed, int dimension) {
        if (seed == 0L) return List.of();
        return predictedChunkOres(chunkX, chunkZ, seed, dimension);
    }

    /**
     * Predicts one ore type inside a single chunk. Intended for debug reports.
     */
    public static List<OrePatch> predictInChunk(int chunkX, int chunkZ, long seed, int dimension, OreType oreType, int maxResults) {
        if (seed == 0L || oreType.dimension != dimension || maxResults <= 0) return List.of();
        return filterType(predictedChunkOres(chunkX, chunkZ, seed, dimension), oreType, maxResults);
    }

    /**
     * Finds nearby in-world ore markers for a single target ore using prediction only.
     */
    public static List<OrePatch> predictInChunkRadius(int centerChunkX, int centerChunkZ,
                                                       int radiusChunks, int dimension,
                                                       OreType oreType, int maxResults) {
        int centerBlockX = (centerChunkX << 4) + 8;
        int centerBlockZ = (centerChunkZ << 4) + 8;
        int centerBlockY = Math.max(oreType.minY, Math.min(oreType.maxY, 64));
        return predictInChunkRadius(centerChunkX, centerChunkZ, radiusChunks, dimension, oreType, maxResults, centerBlockX, centerBlockY, centerBlockZ);
    }

    /**
     * Finds nearby in-world ore markers for a single target ore using prediction only.
     * Scans the whole requested radius, then keeps the closest predicted blocks.
     */
    public static List<OrePatch> predictInChunkRadius(int centerChunkX, int centerChunkZ,
                                                       int radiusChunks, int dimension,
                                                       OreType oreType, int maxResults,
                                                       int centerBlockX, int centerBlockY, int centerBlockZ) {
        long seed = SeedManager.get().getWorldSeed();
        return predictInChunkRadius(centerChunkX, centerChunkZ, radiusChunks, dimension, oreType, maxResults, centerBlockX, centerBlockY, centerBlockZ, seed);
    }

    /**
     * Finds nearby in-world ore markers with an explicit seed. Used by ESP and offline checks.
     */
    public static List<OrePatch> predictInChunkRadius(int centerChunkX, int centerChunkZ,
                                                       int radiusChunks, int dimension,
                                                       OreType oreType, int maxResults,
                                                       int centerBlockX, int centerBlockY, int centerBlockZ,
                                                       long seed) {
        if (seed == 0L || oreType.dimension != dimension || maxResults <= 0) return List.of();

        int radius = Math.max(0, Math.min(radiusChunks, 12));
        List<OrePatch> range = predictOreTypeInChunkRangeFast(
            centerChunkX - radius,
            centerChunkZ - radius,
            centerChunkX + radius,
            centerChunkZ + radius,
            seed,
            dimension,
            oreType
        );

        List<OrePatch> results = new ArrayList<>();
        for (OrePatch patch : range) {
            if (patch.type == oreType) results.add(patch);
        }

        results.sort(Comparator.comparingLong(patch -> distanceSquared(patch, centerBlockX, centerBlockY, centerBlockZ)));
        return results.size() > maxResults ? List.copyOf(results.subList(0, maxResults)) : List.copyOf(results);
    }

    /**
     * Fast path for in-world ESP. It simulates only features that can produce
     * the selected ore type instead of every ore-like feature in the step.
     */
    public static List<OrePatch> predictOreTypeInChunkRangeFast(int chunkMinX, int chunkMinZ,
                                                                 int chunkMaxX, int chunkMaxZ,
                                                                 long seed, int dimension,
                                                                 OreType oreType) {
        if (seed == 0L || oreType.dimension != dimension) return List.of();

        int minChunkX = Math.min(chunkMinX, chunkMaxX);
        int minChunkZ = Math.min(chunkMinZ, chunkMaxZ);
        int maxChunkX = Math.max(chunkMinX, chunkMaxX);
        int maxChunkZ = Math.max(chunkMinZ, chunkMaxZ);

        List<OreFeatureSpec> specs = oreStepFeatures(seed, dimension).stream()
            .filter(spec -> specTargetsType(spec, oreType))
            .toList();

        Map<Long, OrePatch> patches = new HashMap<>();
        WorldgenEngine.TerrainAccessor baseTerrain = WorldgenEngine.terrainAccessor(seed, dimension);

        if (!specs.isEmpty()) {
            for (int originChunkZ = minChunkZ - FEATURE_WRITE_RADIUS_CHUNKS; originChunkZ <= maxChunkZ + FEATURE_WRITE_RADIUS_CHUNKS; originChunkZ++) {
                for (int originChunkX = minChunkX - FEATURE_WRITE_RADIUS_CHUNKS; originChunkX <= maxChunkX + FEATURE_WRITE_RADIUS_CHUNKS; originChunkX++) {
                    simulateOriginChunk(seed, dimension, originChunkX, originChunkZ, minChunkX, minChunkZ, maxChunkX, maxChunkZ, baseTerrain, specs, patches);
                }
            }
        }

        if (dimension == 0 && (oreType == OreType.IRON || oreType == OreType.COPPER)) {
            WorldgenEngine.OreVeinAccessor veins = WorldgenEngine.oreVeinAccessor(seed);
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    addOreVeinBlocks(patches, veins, baseTerrain, chunkX, chunkZ, oreType);
                }
            }
        }

        List<OrePatch> results = new ArrayList<>();
        for (OrePatch patch : patches.values()) {
            if (patch.type != oreType) continue;
            int chunkX = Math.floorDiv(patch.x, 16);
            int chunkZ = Math.floorDiv(patch.z, 16);
            if (chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ) {
                results.add(patch);
            }
        }

        results.sort(Comparator
            .comparingInt((OrePatch patch) -> patch.x)
            .thenComparingInt(patch -> patch.z)
            .thenComparingInt(patch -> patch.y)
        );
        return List.copyOf(results);
    }

    /**
     * Debug helper only. Normal ore ESP does not use loaded chunks.
     */
    public static List<OrePatch> scanLoadedChunks(int centerChunkX, int centerChunkZ,
                                                   int radiusChunks, int dimension,
                                                   OreType oreType, int maxResults) {
        List<OrePatch> results = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || oreType.dimension != dimension || maxResults <= 0) return results;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = Math.max(level.getMinY(), oreType.minY);
        int maxY = Math.min(level.getMaxY() - 1, oreType.maxY);
        if (minY > maxY) return results;

        for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) continue;

                int baseX = chunkX << 4;
                int baseZ = chunkZ << 4;
                for (int y = minY; y <= maxY; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            pos.set(baseX + x, y, baseZ + z);
                            if (!oreType.matches(chunk.getBlockState(pos))) continue;

                            results.add(new OrePatch(pos.getX(), pos.getY(), pos.getZ(), oreType, true));
                            if (results.size() >= maxResults) return results;
                        }
                    }
                }
            }
        }

        return results;
    }

    /**
     * Predicts all ore patches for a range of chunks.
     *
     * @param chunkMinX Minimum chunk X (inclusive)
     * @param chunkMinZ Minimum chunk Z (inclusive)
     * @param chunkMaxX Maximum chunk X (inclusive)
     * @param chunkMaxZ Maximum chunk Z (inclusive)
     * @param dimension The dimension to predict for
     * @return List of predicted OrePatch objects
     */
    public static List<OrePatch> predictInChunkRange(int chunkMinX, int chunkMinZ,
                                                      int chunkMaxX, int chunkMaxZ,
                                                      int dimension) {
        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0L) return List.of();

        return predictInChunkRange(chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, seed, dimension);
    }

    /**
     * Predicts all ore patches for a range of chunks with an explicit seed.
     * This simulates each origin chunk once and is much faster than asking for
     * each target chunk separately.
     */
    public static List<OrePatch> predictInChunkRange(int chunkMinX, int chunkMinZ,
                                                      int chunkMaxX, int chunkMaxZ,
                                                      long seed, int dimension) {
        if (seed == 0L) return List.of();

        int minChunkX = Math.min(chunkMinX, chunkMaxX);
        int minChunkZ = Math.min(chunkMinZ, chunkMaxZ);
        int maxChunkX = Math.max(chunkMinX, chunkMaxX);
        int maxChunkZ = Math.max(chunkMinZ, chunkMaxZ);

        List<OreFeatureSpec> specs = oreStepFeatures(seed, dimension);
        if (specs.isEmpty()) return List.of();

        Map<Long, OrePatch> patches = new HashMap<>();
        WorldgenEngine.TerrainAccessor baseTerrain = WorldgenEngine.terrainAccessor(seed, dimension);

        for (int originChunkZ = minChunkZ - FEATURE_WRITE_RADIUS_CHUNKS; originChunkZ <= maxChunkZ + FEATURE_WRITE_RADIUS_CHUNKS; originChunkZ++) {
            for (int originChunkX = minChunkX - FEATURE_WRITE_RADIUS_CHUNKS; originChunkX <= maxChunkX + FEATURE_WRITE_RADIUS_CHUNKS; originChunkX++) {
                simulateOriginChunk(seed, dimension, originChunkX, originChunkZ, minChunkX, minChunkZ, maxChunkX, maxChunkZ, baseTerrain, specs, patches);
            }
        }

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                addOreVeinBlocks(patches, baseTerrain, seed, dimension, chunkX, chunkZ);
            }
        }

        List<OrePatch> results = new ArrayList<>();
        for (OrePatch patch : patches.values()) {
            int chunkX = Math.floorDiv(patch.x, 16);
            int chunkZ = Math.floorDiv(patch.z, 16);
            if (chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ) {
                results.add(patch);
            }
        }

        results.sort(Comparator
            .comparingInt((OrePatch patch) -> patch.x)
            .thenComparingInt(patch -> patch.z)
            .thenComparingInt(patch -> patch.y)
        );
        cacheRangeResults(seed, dimension, minChunkX, minChunkZ, maxChunkX, maxChunkZ, results);
        return results;
    }

    /**
     * Returns vanilla placed-feature diagnostics for ore-debug reports.
     */
    public static List<String> debugFeatureSummary(long seed, int dimension) {
        List<String> lines = new ArrayList<>();
        try {
            List<FeatureSorter.StepFeatureData> steps = WorldgenEngine.featureSteps(seed, dimension);
            HolderLookup.RegistryLookup<PlacedFeature> placedFeatures = WorldgenEngine.vanillaLookup().lookupOrThrow(Registries.PLACED_FEATURE);
            Map<PlacedFeature, String> idsByFeature = placedFeatureIds(placedFeatures);
            Map<String, FeatureLocation> locations = featureLocations(steps, idsByFeature);

            lines.add("feature_steps=" + steps.size() + " overworld_ore_step=" + OVERWORLD_ORE_STEP);
            lines.add("simulated_ore_feature_count=" + oreStepFeatures(seed, dimension).size());
            lines.add("known_ore_features=");
            for (String id : allOreLikePlacedFeatureIds()) {
                ResourceKey<PlacedFeature> keyId = ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(id));
                Holder.Reference<PlacedFeature> holder = placedFeatures.get(keyId).orElse(null);
                if (holder == null) {
                    lines.add("  " + id + " missing_registry");
                    continue;
                }

                PlacedFeature placedFeature = holder.value();
                FeatureLocation location = locations.get(id);
                ConfiguredFeature<?, ?> configured = placedFeature.feature().value();
                String config = configured.config() instanceof OreConfiguration oreConfig
                    ? "ore size=" + oreConfig.size + " discard=" + oreConfig.discardChanceOnAirExposure
                    : configured.config().getClass().getSimpleName();
                boolean inStep = location != null;
                PlacementData placement = inStep ? placedFeatureData(id, placedFeature, seed, dimension) : null;
                lines.add("  " + id
                    + " step=" + (location == null ? -1 : location.stepIndex())
                    + " index=" + (location == null ? -1 : location.featureIndex())
                    + " in_step=" + inStep
                    + " feature=" + configured.feature().getClass().getSimpleName()
                    + " config=" + config
                    + " placement=" + placedFeature.placement()
                    + " resolved=" + (!inStep ? "not_in_dimension_step" : placement == null ? "unsupported" : placement.describe()));
            }

            lines.add("ore_feature_order=");
            for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
                FeatureSorter.StepFeatureData step = steps.get(stepIndex);
                for (int featureIndex = 0; featureIndex < step.features().size(); featureIndex++) {
                    PlacedFeature feature = step.features().get(featureIndex);
                    String id = idsByFeature.get(feature);
                    PlacementData placement = id == null ? null : placedFeatureData(id, feature, seed, dimension);
                    if (placement == null) continue;

                    ConfiguredFeature<?, ?> configured = feature.feature().value();
                    if (configured.config() instanceof OreConfiguration oreConfig) {
                        lines.add("  step=" + stepIndex + " index=" + featureIndex + " id=" + id
                            + " feature=" + configured.feature().getClass().getSimpleName()
                            + " size=" + oreConfig.size
                            + " discard=" + oreConfig.discardChanceOnAirExposure
                            + " placement=" + feature.placement()
                            + " resolved=" + placement.describe()
                            + " simulated=true");
                    }
                }
            }
        } catch (Throwable throwable) {
            lines.add("feature_summary_error=" + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }

        return List.copyOf(lines);
    }

    /**
     * Debug helper for offline reports. Normal ore ESP does not call this.
     */
    public static List<String> debugOrePlacementTrace(long seed, int dimension, OreType oreType,
                                                       int centerChunkX, int centerChunkZ,
                                                       int radiusChunks, int sampleLimit) {
        if (seed == 0L || oreType.dimension != dimension || sampleLimit <= 0) return List.of();

        List<OreFeatureSpec> specs = oreStepFeatures(seed, dimension).stream()
            .filter(spec -> specTargetsType(spec, oreType))
            .toList();
        if (specs.isEmpty()) return List.of("no matching ore feature specs");

        int radius = Math.max(0, Math.min(radiusChunks, 12));
        int minChunkX = centerChunkX - radius;
        int minChunkZ = centerChunkZ - radius;
        int maxChunkX = centerChunkX + radius;
        int maxChunkZ = centerChunkZ + radius;

        List<String> lines = new ArrayList<>();
        Map<String, Integer> rejectionCounts = new LinkedHashMap<>();
        WorldgenEngine.TerrainAccessor baseTerrain = WorldgenEngine.terrainAccessor(seed, dimension);

        int sampled = 0;
        int accepted = 0;
        int candidates = 0;

        for (int originChunkZ = minChunkZ - FEATURE_WRITE_RADIUS_CHUNKS; originChunkZ <= maxChunkZ + FEATURE_WRITE_RADIUS_CHUNKS; originChunkZ++) {
            for (int originChunkX = minChunkX - FEATURE_WRITE_RADIUS_CHUNKS; originChunkX <= maxChunkX + FEATURE_WRITE_RADIUS_CHUNKS; originChunkX++) {
                SimulatedTerrain terrain = new SimulatedTerrain(baseTerrain, dimension);
                WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
                long decorationSeed = random.setDecorationSeed(seed, originChunkX << 4, originChunkZ << 4);
                BlockPos chunkOrigin = new BlockPos(originChunkX << 4, minBuildY(dimension), originChunkZ << 4);
                Set<Holder<net.minecraft.world.level.biome.Biome>> decorationBiomes = WorldgenEngine.decorationBiomes(seed, dimension, originChunkX, originChunkZ);

                for (OreFeatureSpec spec : specs) {
                    if (!WorldgenEngine.anyBiomeHasFeature(decorationBiomes, spec.placedFeature())) {
                        increment(rejectionCounts, spec.sourceId() + ":decoration_biome");
                        continue;
                    }

                    random.setFeatureSeed(decorationSeed, spec.featureIndex(), spec.stepIndex());
                    int count = spec.placement().sampleCount(random);
                    if (count == 0) increment(rejectionCounts, spec.sourceId() + ":placement_count_zero");

                    for (int placementIndex = 0; placementIndex < count; placementIndex++) {
                        BlockPos origin = sampleOrigin(spec.placement(), random, chunkOrigin);
                        if (origin == null) {
                            increment(rejectionCounts, spec.sourceId() + ":origin_null");
                            continue;
                        }
                        if (!WorldgenEngine.biomeHasFeature(seed, dimension, origin.getX(), origin.getY(), origin.getZ(), spec.placedFeature())) {
                            increment(rejectionCounts, spec.sourceId() + ":origin_biome");
                            continue;
                        }

                        int scatterCount = spec.scattered() ? random.nextInt(spec.size() + 1) : 1;
                        if (scatterCount == 0) increment(rejectionCounts, spec.sourceId() + ":scatter_count_zero");

                        for (int scatterIndex = 0; scatterIndex < scatterCount; scatterIndex++) {
                            int x = origin.getX();
                            int y = origin.getY();
                            int z = origin.getZ();
                            if (spec.scattered()) {
                                int distance = Math.min(scatterIndex, 7);
                                x += scatteredOffset(random, distance);
                                y += scatteredOffset(random, distance);
                                z += scatteredOffset(random, distance);
                            }

                            candidates++;
                            String reason = null;
                            if (!terrain.isInsideBuildHeight(y)) {
                                reason = "outside_build_height";
                            } else if (!canOriginWrite(originChunkX, originChunkZ, x, z)) {
                                reason = "origin_write_window";
                            }

                            BlockState baseState = terrain.block(x, y, z);
                            boolean targetMatch = false;
                            for (OreConfiguration.TargetBlockState targetState : spec.targetStates()) {
                                if (canReplaceTarget(spec, targetState, baseState, random)) {
                                    targetMatch = true;
                                    break;
                                }
                            }

                            boolean adjacentAir = isAdjacentToAir(terrain, x, y, z);
                            boolean wouldPlace = reason == null
                                && targetMatch
                                && (shouldSkipAirCheckForReport(spec.discardChanceOnAirExposure()) || !adjacentAir);

                            if (wouldPlace) {
                                accepted++;
                            } else {
                                if (reason == null && !targetMatch) reason = "target_miss";
                                if (reason == null && adjacentAir) reason = "adjacent_air";
                                increment(rejectionCounts, spec.sourceId() + ":" + reason);
                            }

                            if (sampled < sampleLimit) {
                                lines.add(spec.sourceId()
                                    + " origin_chunk=" + originChunkX + "," + originChunkZ
                                    + " origin=" + origin.getX() + "," + origin.getY() + "," + origin.getZ()
                                    + " candidate=" + x + "," + y + "," + z
                                    + " base=" + blockStateId(baseState)
                                    + " target=" + targetMatch
                                    + " adjacent_air=" + adjacentAir
                                    + " result=" + (wouldPlace ? "place" : reason));
                                sampled++;
                            }
                        }
                    }
                }
            }
        }

        lines.add(0, "accepted=" + accepted + " candidates=" + candidates + " sampled=" + sampled);
        if (!rejectionCounts.isEmpty()) lines.add(1, "rejections=" + rejectionCounts);
        return List.copyOf(lines);
    }

    private static List<OrePatch> predictedChunkOres(int chunkX, int chunkZ, long seed, int dimension) {
        return CHUNK_CACHE.computeIfAbsent(new ChunkOreKey(seed, dimension, chunkX, chunkZ),
            key -> simulateTargetChunk(chunkX, chunkZ, seed, dimension)
        );
    }

    private static List<OrePatch> simulateTargetChunk(int targetChunkX, int targetChunkZ, long seed, int dimension) {
        List<OreFeatureSpec> specs = oreStepFeatures(seed, dimension);
        if (specs.isEmpty()) return List.of();

        Map<Long, OrePatch> patches = new HashMap<>();
        WorldgenEngine.TerrainAccessor baseTerrain = WorldgenEngine.terrainAccessor(seed, dimension);

        for (int originChunkZ = targetChunkZ - FEATURE_WRITE_RADIUS_CHUNKS; originChunkZ <= targetChunkZ + FEATURE_WRITE_RADIUS_CHUNKS; originChunkZ++) {
            for (int originChunkX = targetChunkX - FEATURE_WRITE_RADIUS_CHUNKS; originChunkX <= targetChunkX + FEATURE_WRITE_RADIUS_CHUNKS; originChunkX++) {
                simulateOriginChunk(seed, dimension, originChunkX, originChunkZ, targetChunkX, targetChunkZ, baseTerrain, specs, patches);
            }
        }

        addOreVeinBlocks(patches, baseTerrain, seed, dimension, targetChunkX, targetChunkZ);

        List<OrePatch> result = new ArrayList<>(patches.values());
        result.sort(Comparator
            .comparingInt((OrePatch patch) -> patch.x)
            .thenComparingInt(patch -> patch.z)
            .thenComparingInt(patch -> patch.y)
        );
        return List.copyOf(result);
    }

    private static void simulateOriginChunk(long seed,
                                            int dimension,
                                            int originChunkX,
                                            int originChunkZ,
                                            int targetChunkX,
                                            int targetChunkZ,
                                            WorldgenEngine.TerrainAccessor baseTerrain,
                                            List<OreFeatureSpec> specs,
                                            Map<Long, OrePatch> targetPatches) {
        simulateOriginChunk(seed, dimension, originChunkX, originChunkZ, targetChunkX, targetChunkZ, targetChunkX, targetChunkZ, baseTerrain, specs, targetPatches);
    }

    private static void simulateOriginChunk(long seed,
                                            int dimension,
                                            int originChunkX,
                                            int originChunkZ,
                                            int minTargetChunkX,
                                            int minTargetChunkZ,
                                            int maxTargetChunkX,
                                            int maxTargetChunkZ,
                                            WorldgenEngine.TerrainAccessor baseTerrain,
                                            List<OreFeatureSpec> specs,
                                            Map<Long, OrePatch> targetPatches) {
        SimulatedTerrain terrain = new SimulatedTerrain(baseTerrain, dimension);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long decorationSeed = random.setDecorationSeed(seed, originChunkX << 4, originChunkZ << 4);
        BlockPos chunkOrigin = new BlockPos(originChunkX << 4, minBuildY(dimension), originChunkZ << 4);
        Set<Holder<net.minecraft.world.level.biome.Biome>> decorationBiomes = WorldgenEngine.decorationBiomes(seed, dimension, originChunkX, originChunkZ);

        for (OreFeatureSpec spec : specs) {
            if (!WorldgenEngine.anyBiomeHasFeature(decorationBiomes, spec.placedFeature())) continue;

            random.setFeatureSeed(decorationSeed, spec.featureIndex(), spec.stepIndex());
            int count = spec.placement().sampleCount(random);
            for (int i = 0; i < count; i++) {
                BlockPos origin = sampleOrigin(spec.placement(), random, chunkOrigin);
                if (origin == null) continue;
                if (!WorldgenEngine.biomeHasFeature(seed, dimension, origin.getX(), origin.getY(), origin.getZ(), spec.placedFeature())) continue;

                if (spec.scattered()) {
                    placeScatteredFeature(terrain, random, spec, origin, originChunkX, originChunkZ, minTargetChunkX, minTargetChunkZ, maxTargetChunkX, maxTargetChunkZ, targetPatches);
                } else {
                    placeVeinFeature(terrain, random, spec, origin, originChunkX, originChunkZ, minTargetChunkX, minTargetChunkZ, maxTargetChunkX, maxTargetChunkZ, targetPatches);
                }
            }
        }
    }

    private static void placeVeinFeature(SimulatedTerrain terrain,
                                         WorldgenRandom random,
                                         OreFeatureSpec spec,
                                         BlockPos origin,
                                         int originChunkX,
                                         int originChunkZ,
                                         int minTargetChunkX,
                                         int minTargetChunkZ,
                                         int maxTargetChunkX,
                                         int maxTargetChunkZ,
                                         Map<Long, OrePatch> targetPatches) {
        int size = spec.size();
        float angle = random.nextFloat() * (float) Math.PI;
        float horizontalSize = size / 8.0f;
        int radius = Mth.ceil(((size / 16.0f) * 2.0f + 1.0f) / 2.0f);
        double startX = origin.getX() + Math.sin(angle) * horizontalSize;
        double endX = origin.getX() - Math.sin(angle) * horizontalSize;
        double startZ = origin.getZ() + Math.cos(angle) * horizontalSize;
        double endZ = origin.getZ() - Math.cos(angle) * horizontalSize;
        double startY = origin.getY() + random.nextInt(3) - 2;
        double endY = origin.getY() + random.nextInt(3) - 2;

        int minSectionX = origin.getX() - Mth.ceil(horizontalSize) - radius;
        int minSectionY = origin.getY() - 2 - radius;
        int minSectionZ = origin.getZ() - Mth.ceil(horizontalSize) - radius;
        int horizontalSpan = 2 * (Mth.ceil(horizontalSize) + radius);
        int verticalSpan = 2 * (2 + radius);
        if (isAboveTerrain(terrain, minSectionY, minSectionX, minSectionZ, horizontalSpan)) return;

        double[] spheres = new double[size * 4];
        for (int i = 0; i < size; i++) {
            float t = (float) i / (float) size;
            double centerX = Mth.lerp(t, startX, endX);
            double centerY = Mth.lerp(t, startY, endY);
            double centerZ = Mth.lerp(t, startZ, endZ);
            double growth = random.nextDouble() * size / 16.0D;
            double sphereRadius = ((Mth.sin((float) (Math.PI * t)) + 1.0D) * growth + 1.0D) / 2.0D;
            int base = i * 4;
            spheres[base] = centerX;
            spheres[base + 1] = centerY;
            spheres[base + 2] = centerZ;
            spheres[base + 3] = sphereRadius;
        }

        removeCoveredSpheres(spheres, size);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < size; i++) {
            int base = i * 4;
            double sphereRadius = spheres[base + 3];
            if (sphereRadius < 0.0D) continue;

            double centerX = spheres[base];
            double centerY = spheres[base + 1];
            double centerZ = spheres[base + 2];
            int minX = Math.max(Mth.floor(centerX - sphereRadius), minSectionX);
            int minY = Math.max(Mth.floor(centerY - sphereRadius), minSectionY);
            int minZ = Math.max(Mth.floor(centerZ - sphereRadius), minSectionZ);
            int maxX = Math.max(Mth.floor(centerX + sphereRadius), minX);
            int maxY = Math.max(Mth.floor(centerY + sphereRadius), minY);
            int maxZ = Math.max(Mth.floor(centerZ + sphereRadius), minZ);

            for (int x = minX; x <= maxX; x++) {
                double dx = (x + 0.5D - centerX) / sphereRadius;
                if (dx * dx >= 1.0D) continue;
                for (int y = minY; y <= maxY; y++) {
                    if (!terrain.isInsideBuildHeight(y)) continue;
                    double dy = (y + 0.5D - centerY) / sphereRadius;
                    if (dx * dx + dy * dy >= 1.0D) continue;
                    for (int z = minZ; z <= maxZ; z++) {
                        double dz = (z + 0.5D - centerZ) / sphereRadius;
                        if (dx * dx + dy * dy + dz * dz >= 1.0D) continue;
                        if (!canOriginWrite(originChunkX, originChunkZ, x, z)) continue;

                        long posKey = BlockPos.asLong(x, y, z);
                        int bitIndex = (x - minSectionX) + (y - minSectionY) * horizontalSpan + (z - minSectionZ) * horizontalSpan * verticalSpan;
                        if (!seen.add(bitIndex)) continue;

                        BlockState placedState = placeStateOrNull(terrain, spec, random, spec.targetStates(), spec.discardChanceOnAirExposure(), x, y, z);
                        if (placedState == null) continue;

                        terrain.setBlock(x, y, z, placedState);
                        recordTargetWrite(targetPatches, spec, placedState, minTargetChunkX, minTargetChunkZ, maxTargetChunkX, maxTargetChunkZ, x, y, z, posKey);
                    }
                }
            }
        }
    }

    private static void placeScatteredFeature(SimulatedTerrain terrain,
                                              WorldgenRandom random,
                                              OreFeatureSpec spec,
                                              BlockPos origin,
                                              int originChunkX,
                                              int originChunkZ,
                                              int minTargetChunkX,
                                              int minTargetChunkZ,
                                              int maxTargetChunkX,
                                              int maxTargetChunkZ,
                                              Map<Long, OrePatch> targetPatches) {
        int count = random.nextInt(spec.size() + 1);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < count; i++) {
            int distance = Math.min(i, 7);
            int x = origin.getX() + scatteredOffset(random, distance);
            int y = origin.getY() + scatteredOffset(random, distance);
            int z = origin.getZ() + scatteredOffset(random, distance);
            if (!terrain.isInsideBuildHeight(y)) continue;
            if (!canOriginWrite(originChunkX, originChunkZ, x, z)) continue;

            long posKey = BlockPos.asLong(x, y, z);
            if (!seen.add(posKey)) continue;

            BlockState placedState = placeStateOrNull(terrain, spec, random, spec.targetStates(), spec.discardChanceOnAirExposure(), x, y, z);
            if (placedState == null) continue;

            terrain.setBlock(x, y, z, placedState);
            recordTargetWrite(targetPatches, spec, placedState, minTargetChunkX, minTargetChunkZ, maxTargetChunkX, maxTargetChunkZ, x, y, z, posKey);
        }
    }

    private static boolean canOriginWrite(int originChunkX, int originChunkZ, int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        return Math.abs(chunkX - originChunkX) <= FEATURE_WRITE_RADIUS_CHUNKS
            && Math.abs(chunkZ - originChunkZ) <= FEATURE_WRITE_RADIUS_CHUNKS;
    }

    private static void recordTargetWrite(Map<Long, OrePatch> targetPatches,
                                          OreFeatureSpec spec,
                                          BlockState placedState,
                                          int minTargetChunkX,
                                          int minTargetChunkZ,
                                          int maxTargetChunkX,
                                          int maxTargetChunkZ,
                                          int x,
                                          int y,
                                          int z,
                                          long posKey) {
        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        if (chunkX < minTargetChunkX || chunkX > maxTargetChunkX || chunkZ < minTargetChunkZ || chunkZ > maxTargetChunkZ) return;

        OreType type = oreTypeForState(placedState);
        if (type == null) {
            targetPatches.remove(posKey);
            return;
        }

        targetPatches.put(posKey, new OrePatch(x, y, z, type, false, spec.sourceId()));
    }

    private static void cacheRangeResults(long seed,
                                          int dimension,
                                          int minChunkX,
                                          int minChunkZ,
                                          int maxChunkX,
                                          int maxChunkZ,
                                          List<OrePatch> results) {
        Map<ChunkOreKey, List<OrePatch>> byChunk = new HashMap<>();
        for (OrePatch patch : results) {
            int chunkX = Math.floorDiv(patch.x, 16);
            int chunkZ = Math.floorDiv(patch.z, 16);
            ChunkOreKey key = new ChunkOreKey(seed, dimension, chunkX, chunkZ);
            byChunk.computeIfAbsent(key, ignored -> new ArrayList<>()).add(patch);
        }

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                ChunkOreKey key = new ChunkOreKey(seed, dimension, chunkX, chunkZ);
                List<OrePatch> chunkPatches = byChunk.getOrDefault(key, List.of());
                if (!chunkPatches.isEmpty()) {
                    chunkPatches = new ArrayList<>(chunkPatches);
                    chunkPatches.sort(Comparator
                        .comparingInt((OrePatch patch) -> patch.x)
                        .thenComparingInt(patch -> patch.z)
                        .thenComparingInt(patch -> patch.y)
                    );
                }
                CHUNK_CACHE.putIfAbsent(key, List.copyOf(chunkPatches));
            }
        }
    }

    private static BlockState placeStateOrNull(SimulatedTerrain terrain,
                                               OreFeatureSpec spec,
                                               WorldgenRandom random,
                                               List<OreConfiguration.TargetBlockState> targetStates,
                                               float discardChanceOnAirExposure,
                                               int x,
                                               int y,
                                               int z) {
        BlockState baseState = terrain.block(x, y, z);

        for (OreConfiguration.TargetBlockState targetState : targetStates) {
            if (!canReplaceTarget(spec, targetState, baseState, random)) continue;
            if (shouldSkipAirCheck(random, discardChanceOnAirExposure)) return targetState.state;
            if (!isAdjacentToAir(terrain, x, y, z)) return targetState.state;
        }

        return null;
    }

    private static boolean isAboveTerrain(SimulatedTerrain terrain, int minSectionY, int minSectionX, int minSectionZ, int horizontalSpan) {
        for (int x = minSectionX; x <= minSectionX + horizontalSpan; x++) {
            for (int z = minSectionZ; z <= minSectionZ + horizontalSpan; z++) {
                if (minSectionY <= terrain.oceanFloorHeight(x, z)) return false;
            }
        }
        return true;
    }

    private static void addOreVeinBlocks(Map<Long, OrePatch> patches,
                                         WorldgenEngine.TerrainAccessor terrain,
                                         long seed,
                                         int dimension,
                                         int chunkX,
                                         int chunkZ) {
        if (dimension != 0) return;

        WorldgenEngine.OreVeinAccessor veins = WorldgenEngine.oreVeinAccessor(seed);
        addOreVeinBlocks(patches, veins, terrain, chunkX, chunkZ, OreType.IRON);
        addOreVeinBlocks(patches, veins, terrain, chunkX, chunkZ, OreType.COPPER);
    }

    private static void addOreVeinBlocks(Map<Long, OrePatch> patches,
                                         WorldgenEngine.OreVeinAccessor veins,
                                         WorldgenEngine.TerrainAccessor terrain,
                                         int chunkX,
                                         int chunkZ,
                                         OreType oreType) {
        int minY = oreType == OreType.COPPER ? 0 : -60;
        int maxY = oreType == OreType.COPPER ? 50 : -8;
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        for (int y = minY; y <= maxY; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int blockX = baseX + x;
                    int blockZ = baseZ + z;
                    if (!isOreVeinBlock(veins, terrain, blockX, y, blockZ, oreType)) continue;

                    long posKey = BlockPos.asLong(blockX, y, blockZ);
                    patches.putIfAbsent(posKey, new OrePatch(blockX, y, blockZ, oreType, false, "ore_vein"));
                }
            }
        }
    }

    private static boolean isOreVeinBlock(WorldgenEngine.OreVeinAccessor veins,
                                          WorldgenEngine.TerrainAccessor terrain,
                                          int blockX,
                                          int blockY,
                                          int blockZ,
                                          OreType oreType) {
        double veininess = veins.veinToggle(blockX, blockY, blockZ);
        if (oreType == OreType.COPPER && veininess <= 0.0D) return false;
        if (oreType == OreType.IRON && veininess >= 0.0D) return false;

        int minY = oreType == OreType.COPPER ? 0 : -60;
        int maxY = oreType == OreType.COPPER ? 50 : -8;
        int edgeDistance = Math.min(maxY - blockY, blockY - minY);
        if (edgeDistance < 0) return false;

        double edgeRoundoff = Mth.clampedMap(edgeDistance, 0.0D, 20.0D, -0.2D, 0.0D);
        double absVeininess = Math.abs(veininess);
        if (absVeininess + edgeRoundoff < 0.4000000059604645D) return false;
        if (veins.oreRandomFloat(blockX, blockY, blockZ, 0) > 0.7f) return false;
        if (veins.veinRidged(blockX, blockY, blockZ) >= 0.0D) return false;

        double richness = Mth.clampedMap(absVeininess, 0.4000000059604645D, 0.6000000238418579D, 0.10000000149011612D, 0.30000001192092896D);
        if (veins.oreRandomFloat(blockX, blockY, blockZ, 1) >= richness) return false;
        if (veins.veinGap(blockX, blockY, blockZ) <= -0.30000001192092896D) return false;
        if (!canReplaceForOreVein(terrain, blockX, blockY, blockZ)) return false;

        // Vanilla uses this final roll to choose raw ore blocks 2% of the time.
        // The ESP highlights ore blocks only, so skip the raw-block positions.
        return veins.oreRandomFloat(blockX, blockY, blockZ, 2) >= 0.02f;
    }

    private static boolean canReplaceForOreVein(WorldgenEngine.TerrainAccessor terrain, int blockX, int blockY, int blockZ) {
        BlockState state = terrain.block(blockX, blockY, blockZ);
        return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.TUFF);
    }

    private static List<OrePatch> filterType(List<OrePatch> patches, OreType type, int maxResults) {
        List<OrePatch> results = new ArrayList<>();
        for (OrePatch patch : patches) {
            if (patch.type != type) continue;
            results.add(patch);
            if (results.size() >= maxResults) break;
        }
        return List.copyOf(results);
    }

    private static long distanceSquared(OrePatch patch, int centerBlockX, int centerBlockY, int centerBlockZ) {
        long dx = patch.x - centerBlockX;
        long dy = patch.y - centerBlockY;
        long dz = patch.z - centerBlockZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static List<OreFeatureSpec> oreStepFeatures(long seed, int dimension) {
        return FEATURE_CACHE.computeIfAbsent(new FeatureListKey(seed, dimension), key -> {
            List<OreFeatureSpec> specs = new ArrayList<>();
            try {
                List<FeatureSorter.StepFeatureData> steps = WorldgenEngine.featureSteps(seed, dimension);
                HolderLookup.RegistryLookup<PlacedFeature> placedFeatures = WorldgenEngine.vanillaLookup().lookupOrThrow(Registries.PLACED_FEATURE);
                Map<PlacedFeature, String> idsByFeature = placedFeatureIds(placedFeatures);

                for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
                    FeatureSorter.StepFeatureData step = steps.get(stepIndex);
                    for (int featureIndex = 0; featureIndex < step.features().size(); featureIndex++) {
                        PlacedFeature placedFeature = step.features().get(featureIndex);
                        ConfiguredFeature<?, ?> configured = placedFeature.feature().value();
                        if (!(configured.config() instanceof OreConfiguration oreConfig)) continue;

                        String id = idsByFeature.get(placedFeature);
                        PlacementData placement = id == null ? null : placedFeatureData(id, placedFeature, seed, dimension);
                        if (placement == null) continue;

                        specs.add(new OreFeatureSpec(
                            id,
                            placedFeature,
                            stepIndex,
                            featureIndex,
                            placement,
                            oreConfig.targetStates,
                            oreConfig.size,
                            oreConfig.discardChanceOnAirExposure,
                            configured.feature() instanceof ScatteredOreFeature
                        ));
                    }
                }
            } catch (Throwable ignored) {
                return List.of();
            }

            specs.sort(Comparator
                .comparingInt(OreFeatureSpec::stepIndex)
                .thenComparingInt(OreFeatureSpec::featureIndex));
            return List.copyOf(specs);
        });
    }

    private static Map<String, FeatureLocation> featureLocations(List<FeatureSorter.StepFeatureData> steps, Map<PlacedFeature, String> idsByFeature) {
        Map<String, FeatureLocation> locations = new LinkedHashMap<>();
        for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
            FeatureSorter.StepFeatureData step = steps.get(stepIndex);
            for (int featureIndex = 0; featureIndex < step.features().size(); featureIndex++) {
                String id = idsByFeature.get(step.features().get(featureIndex));
                if (id != null) locations.put(id, new FeatureLocation(stepIndex, featureIndex));
            }
        }
        return locations;
    }

    private static Map<PlacedFeature, String> placedFeatureIds(HolderLookup.RegistryLookup<PlacedFeature> placedFeatures) {
        Map<PlacedFeature, String> idsByFeature = new IdentityHashMap<>();
        placedFeatures.listElements().forEach(holder ->
            idsByFeature.put(holder.value(), holder.key().identifier().toString())
        );
        return idsByFeature;
    }

    private static BlockPos sampleOrigin(PlacementData placement, WorldgenRandom random, BlockPos chunkOrigin) {
        if (placement.rarityChance() > 1 && random.nextFloat() >= 1.0f / placement.rarityChance()) return null;

        int x = chunkOrigin.getX();
        int z = chunkOrigin.getZ();
        if (placement.inSquare()) {
            x += random.nextInt(16);
            z += random.nextInt(16);
        }

        int y = placement.sampleY(random);
        return new BlockPos(x, y, z);
    }

    private static boolean shouldSkipAirCheck(WorldgenRandom random, float discardChanceOnAirExposure) {
        if (discardChanceOnAirExposure <= 0.0f) return true;
        if (discardChanceOnAirExposure >= 1.0f) return false;
        return random.nextFloat() >= discardChanceOnAirExposure;
    }

    private static boolean shouldSkipAirCheckForReport(float discardChanceOnAirExposure) {
        return discardChanceOnAirExposure <= 0.0f;
    }

    private static boolean isAdjacentToAir(SimulatedTerrain terrain, int x, int y, int z) {
        return terrain.block(x + 1, y, z).isAir()
            || terrain.block(x - 1, y, z).isAir()
            || terrain.block(x, y + 1, z).isAir()
            || terrain.block(x, y - 1, z).isAir()
            || terrain.block(x, y, z + 1).isAir()
            || terrain.block(x, y, z - 1).isAir();
    }

    private static void removeCoveredSpheres(double[] spheres, int size) {
        for (int i = 0; i < size - 1; i++) {
            if (spheres[i * 4 + 3] <= 0.0D) continue;
            for (int j = i + 1; j < size; j++) {
                if (spheres[j * 4 + 3] <= 0.0D) continue;

                double dx = spheres[i * 4] - spheres[j * 4];
                double dy = spheres[i * 4 + 1] - spheres[j * 4 + 1];
                double dz = spheres[i * 4 + 2] - spheres[j * 4 + 2];
                double dr = spheres[i * 4 + 3] - spheres[j * 4 + 3];
                if (dr * dr <= dx * dx + dy * dy + dz * dz) continue;

                if (dr > 0.0D) {
                    spheres[j * 4 + 3] = -1.0D;
                } else {
                    spheres[i * 4 + 3] = -1.0D;
                }
            }
        }
    }

    private static int scatteredOffset(WorldgenRandom random, int distance) {
        return Math.round((random.nextFloat() - random.nextFloat()) * distance);
    }

    private static OreType oreTypeForState(BlockState state) {
        for (OreType type : OreType.values()) {
            if (type.matches(state)) return type;
        }
        return null;
    }

    private static boolean canReplaceTarget(OreFeatureSpec spec,
                                            OreConfiguration.TargetBlockState targetState,
                                            BlockState baseState,
                                            WorldgenRandom random) {
        if (targetState.target.test(baseState, random)) return true;
        return canReplaceByVanillaFallback(spec, targetState.state, baseState);
    }

    private static boolean canReplaceByVanillaFallback(OreFeatureSpec spec, BlockState targetState, BlockState baseState) {
        if (targetState.is(Blocks.DIRT)
            || targetState.is(Blocks.GRAVEL)
            || targetState.is(Blocks.GRANITE)
            || targetState.is(Blocks.DIORITE)
            || targetState.is(Blocks.ANDESITE)
            || targetState.is(Blocks.TUFF)
            || targetState.is(Blocks.CLAY)) {
            return isOverworldBaseStone(baseState);
        }

        if (targetState.is(Blocks.COAL_ORE)
            || targetState.is(Blocks.IRON_ORE)
            || targetState.is(Blocks.COPPER_ORE)
            || targetState.is(Blocks.GOLD_ORE)
            || targetState.is(Blocks.REDSTONE_ORE)
            || targetState.is(Blocks.DIAMOND_ORE)
            || targetState.is(Blocks.LAPIS_ORE)
            || targetState.is(Blocks.EMERALD_ORE)
            || targetState.is(Blocks.INFESTED_STONE)) {
            return isStoneOreReplaceable(baseState);
        }

        if (targetState.is(Blocks.DEEPSLATE_COAL_ORE)
            || targetState.is(Blocks.DEEPSLATE_IRON_ORE)
            || targetState.is(Blocks.DEEPSLATE_COPPER_ORE)
            || targetState.is(Blocks.DEEPSLATE_GOLD_ORE)
            || targetState.is(Blocks.DEEPSLATE_REDSTONE_ORE)
            || targetState.is(Blocks.DEEPSLATE_DIAMOND_ORE)
            || targetState.is(Blocks.DEEPSLATE_LAPIS_ORE)
            || targetState.is(Blocks.DEEPSLATE_EMERALD_ORE)
            || targetState.is(Blocks.INFESTED_DEEPSLATE)) {
            return isDeepslateOreReplaceable(baseState);
        }

        if (targetState.is(Blocks.ANCIENT_DEBRIS)) {
            return isNetherBaseStone(baseState);
        }

        return false;
    }

    private static boolean isOverworldBaseStone(BlockState state) {
        return state.is(Blocks.STONE)
            || state.is(Blocks.GRANITE)
            || state.is(Blocks.DIORITE)
            || state.is(Blocks.ANDESITE)
            || state.is(Blocks.TUFF)
            || state.is(Blocks.DEEPSLATE);
    }

    private static boolean isStoneOreReplaceable(BlockState state) {
        return state.is(Blocks.STONE)
            || state.is(Blocks.GRANITE)
            || state.is(Blocks.DIORITE)
            || state.is(Blocks.ANDESITE);
    }

    private static boolean isDeepslateOreReplaceable(BlockState state) {
        return state.is(Blocks.DEEPSLATE)
            || state.is(Blocks.TUFF);
    }

    private static boolean isNetherBaseStone(BlockState state) {
        return state.is(Blocks.NETHERRACK)
            || state.is(Blocks.BASALT)
            || state.is(Blocks.BLACKSTONE);
    }

    private static String blockStateId(BlockState state) {
        if (state == null) return "unknown";
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static boolean specTargetsType(OreFeatureSpec spec, OreType oreType) {
        for (OreConfiguration.TargetBlockState targetState : spec.targetStates()) {
            if (oreType.matches(targetState.state)) return true;
        }
        return false;
    }

    private static List<String> allOreLikePlacedFeatureIds() {
        return List.of(
            "minecraft:ore_dirt",
            "minecraft:ore_gravel",
            "minecraft:ore_granite_lower",
            "minecraft:ore_granite_upper",
            "minecraft:ore_diorite_lower",
            "minecraft:ore_diorite_upper",
            "minecraft:ore_andesite_lower",
            "minecraft:ore_andesite_upper",
            "minecraft:ore_tuff",
            "minecraft:ore_coal_upper",
            "minecraft:ore_coal_lower",
            "minecraft:ore_iron_upper",
            "minecraft:ore_iron_middle",
            "minecraft:ore_iron_small",
            "minecraft:ore_copper",
            "minecraft:ore_copper_large",
            "minecraft:ore_gold",
            "minecraft:ore_gold_lower",
            "minecraft:ore_gold_extra",
            "minecraft:ore_redstone",
            "minecraft:ore_redstone_lower",
            "minecraft:ore_diamond",
            "minecraft:ore_diamond_large",
            "minecraft:ore_diamond_buried",
            "minecraft:ore_diamond_medium",
            "minecraft:ore_lapis",
            "minecraft:ore_lapis_buried",
            "minecraft:ore_emerald",
            "minecraft:ore_infested",
            "minecraft:ore_clay",
            "minecraft:ore_gravel_nether",
            "minecraft:ore_blackstone",
            "minecraft:ore_magma",
            "minecraft:ore_soul_sand",
            "minecraft:ore_quartz_nether",
            "minecraft:ore_quartz_deltas",
            "minecraft:ore_gold_nether",
            "minecraft:ore_gold_deltas",
            "minecraft:ore_ancient_debris_large",
            "minecraft:ore_debris_small"
        );
    }

    private static PlacementData placedFeatureData(String id, PlacedFeature placedFeature, long seed, int dimension) {
        PlacementData extracted = extractPlacementData(placedFeature, seed, dimension);
        if (extracted != null) return extracted;

        // Vanilla VerticalAnchor.top() resolves to (build height - 1), i.e. the top placeable
        // block, not the exclusive ceiling returned by topAnchor(). Using the inclusive value
        // keeps the uniform Y sampling in step with vanilla for top()-anchored features.
        int top = topAnchor(dimension) - 1;
        int bottom = minBuildY(dimension);

        return switch (id) {
            case "minecraft:ore_dirt" -> PlacementData.count(7, HeightMode.UNIFORM, 0, 160);
            case "minecraft:ore_gravel" -> PlacementData.count(14, HeightMode.UNIFORM, bottom, top);
            case "minecraft:ore_granite_lower", "minecraft:ore_diorite_lower", "minecraft:ore_andesite_lower" -> PlacementData.count(2, HeightMode.UNIFORM, 0, 60);
            case "minecraft:ore_granite_upper", "minecraft:ore_diorite_upper", "minecraft:ore_andesite_upper" -> PlacementData.rarity(6, HeightMode.UNIFORM, 64, 128);
            case "minecraft:ore_tuff" -> PlacementData.count(2, HeightMode.UNIFORM, bottom, 0);
            case "minecraft:ore_coal_upper" -> PlacementData.count(30, HeightMode.UNIFORM, 136, top);
            case "minecraft:ore_coal_lower" -> PlacementData.count(20, HeightMode.TRAPEZOID, 0, 192);
            case "minecraft:ore_iron_upper" -> PlacementData.count(90, HeightMode.TRAPEZOID, 80, 384);
            case "minecraft:ore_iron_middle" -> PlacementData.count(10, HeightMode.TRAPEZOID, -24, 56);
            case "minecraft:ore_iron_small" -> PlacementData.count(10, HeightMode.UNIFORM, -64, 72);
            case "minecraft:ore_copper", "minecraft:ore_copper_large" -> PlacementData.count(16, HeightMode.TRAPEZOID, -16, 112);
            case "minecraft:ore_gold" -> PlacementData.count(4, HeightMode.TRAPEZOID, -64, 32);
            case "minecraft:ore_gold_lower" -> PlacementData.uniformCount(0, 1, HeightMode.UNIFORM, -64, -48);
            case "minecraft:ore_gold_extra" -> PlacementData.count(50, HeightMode.UNIFORM, 32, 256);
            case "minecraft:ore_redstone" -> PlacementData.count(4, HeightMode.UNIFORM, -64, 15);
            case "minecraft:ore_redstone_lower" -> PlacementData.count(8, HeightMode.TRAPEZOID, -96, -32);
            case "minecraft:ore_diamond" -> PlacementData.count(7, HeightMode.TRAPEZOID, -144, 16);
            case "minecraft:ore_diamond_large" -> PlacementData.rarity(9, HeightMode.TRAPEZOID, -144, 16);
            case "minecraft:ore_diamond_buried" -> PlacementData.count(4, HeightMode.TRAPEZOID, -144, 16);
            case "minecraft:ore_diamond_medium" -> PlacementData.count(2, HeightMode.UNIFORM, -64, -4);
            case "minecraft:ore_lapis" -> PlacementData.count(2, HeightMode.TRAPEZOID, -32, 32);
            case "minecraft:ore_lapis_buried" -> PlacementData.count(4, HeightMode.UNIFORM, -64, 64);
            case "minecraft:ore_emerald" -> PlacementData.count(100, HeightMode.TRAPEZOID, -16, 480);
            case "minecraft:ore_infested" -> PlacementData.count(14, HeightMode.UNIFORM, bottom, 63);
            case "minecraft:ore_clay" -> PlacementData.count(46, HeightMode.UNIFORM, bottom, 256);
            case "minecraft:ore_gravel_nether" -> PlacementData.count(2, HeightMode.UNIFORM, 5, 41);
            case "minecraft:ore_blackstone" -> PlacementData.count(2, HeightMode.UNIFORM, 5, 31);
            case "minecraft:ore_magma" -> PlacementData.count(4, HeightMode.UNIFORM, 27, 36);
            case "minecraft:ore_soul_sand" -> PlacementData.count(12, HeightMode.UNIFORM, 0, 31);
            case "minecraft:ore_quartz_nether" -> PlacementData.count(16, HeightMode.UNIFORM, 10, 117);
            case "minecraft:ore_quartz_deltas" -> PlacementData.count(32, HeightMode.UNIFORM, 10, 117);
            case "minecraft:ore_gold_nether" -> PlacementData.count(10, HeightMode.UNIFORM, 10, 117);
            case "minecraft:ore_gold_deltas" -> PlacementData.count(20, HeightMode.UNIFORM, 10, 117);
            case "minecraft:ore_ancient_debris_large" -> PlacementData.count(1, HeightMode.TRAPEZOID, 8, 24);
            case "minecraft:ore_debris_small" -> PlacementData.count(1, HeightMode.UNIFORM, 8, 119);
            default -> null;
        };
    }

    private static PlacementData extractPlacementData(PlacedFeature placedFeature, long seed, int dimension) {
        try {
            IntProvider countProvider = null;
            int rarityChance = 1;
            boolean inSquare = false;
            HeightProvider heightProvider = null;

            for (PlacementModifier modifier : placedFeature.placement()) {
                if (modifier instanceof CountPlacement) {
                    Object value = readField(modifier, "count");
                    if (!(value instanceof IntProvider provider)) return null;
                    countProvider = provider;
                } else if (modifier instanceof RarityFilter) {
                    Object value = readField(modifier, "chance");
                    if (!(value instanceof Integer chance)) return null;
                    rarityChance = chance;
                } else if (modifier instanceof InSquarePlacement) {
                    inSquare = true;
                } else if (modifier instanceof HeightRangePlacement) {
                    Object value = readField(modifier, "height");
                    if (!(value instanceof HeightProvider provider)) return null;
                    heightProvider = provider;
                } else if (modifier instanceof BiomeFilter) {
                    // Biome filtering is handled before feature placement.
                } else {
                    return null;
                }
            }

            if (heightProvider == null) return null;
            return PlacementData.vanilla(countProvider, rarityChance, inSquare, heightProvider, WorldgenEngine.worldGenerationContext(seed, dimension));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static int minBuildY(int dimension) {
        return dimension == -1 ? 0 : -64;
    }

    private static int topAnchor(int dimension) {
        return dimension == -1 ? 128 : 320;
    }

    private record FeatureListKey(long seed, int dimension) {
    }

    private record ChunkOreKey(long seed, int dimension, int chunkX, int chunkZ) {
    }

    private record OreFeatureSpec(String sourceId, PlacedFeature placedFeature, int stepIndex, int featureIndex, PlacementData placement, List<OreConfiguration.TargetBlockState> targetStates, int size, float discardChanceOnAirExposure, boolean scattered) {
    }

    private record FeatureLocation(int stepIndex, int featureIndex) {
    }

    private enum HeightMode {
        UNIFORM,
        TRAPEZOID
    }

    private record PlacementData(IntProvider countProvider,
                                 int minCount,
                                 int maxCount,
                                 int rarityChance,
                                 boolean inSquare,
                                 HeightProvider heightProvider,
                                 WorldGenerationContext generationContext,
                                 HeightMode heightMode,
                                 int minY,
                                 int maxY) {
        static PlacementData vanilla(IntProvider countProvider, int rarityChance, boolean inSquare, HeightProvider heightProvider, WorldGenerationContext generationContext) {
            return new PlacementData(countProvider, 1, 1, rarityChance, inSquare, heightProvider, generationContext, HeightMode.UNIFORM, 0, 0);
        }

        static PlacementData count(int count, HeightMode heightMode, int minY, int maxY) {
            return new PlacementData(null, count, count, 1, true, null, null, heightMode, minY, maxY);
        }

        static PlacementData uniformCount(int minCount, int maxCount, HeightMode heightMode, int minY, int maxY) {
            return new PlacementData(null, minCount, maxCount, 1, true, null, null, heightMode, minY, maxY);
        }

        static PlacementData rarity(int chance, HeightMode heightMode, int minY, int maxY) {
            return new PlacementData(null, 1, 1, chance, true, null, null, heightMode, minY, maxY);
        }

        int sampleCount(WorldgenRandom random) {
            if (countProvider != null) return countProvider.sample(random);
            if (minCount == maxCount) return minCount;
            return Mth.randomBetweenInclusive(random, minCount, maxCount);
        }

        int sampleY(WorldgenRandom random) {
            if (heightProvider != null && generationContext != null) {
                return heightProvider.sample(random, generationContext);
            }

            int min = minY;
            int max = maxY;
            if (min > max) return min;

            if (heightMode == HeightMode.UNIFORM) {
                return Mth.randomBetweenInclusive(random, min, max);
            }

            int range = max - min;
            int lower = range / 2;
            int upper = range - lower;
            return min + Mth.randomBetweenInclusive(random, 0, upper) + Mth.randomBetweenInclusive(random, 0, lower);
        }

        String describe() {
            String source = heightProvider != null ? "vanilla" : "fallback";
            String count = countProvider != null
                ? countProvider.getClass().getSimpleName() + "[" + countProvider.minInclusive() + ".." + countProvider.maxInclusive() + "]"
                : minCount == maxCount ? Integer.toString(minCount) : minCount + ".." + maxCount;
            String height;
            if (heightProvider != null && generationContext != null) {
                int[] bounds = resolvedYBounds();
                height = heightProvider.getClass().getSimpleName()
                    + "[" + bounds[0] + ".." + bounds[1] + "]"
                    + " " + heightProvider;
            } else {
                height = heightMode + "[" + minY + ".." + maxY + "]";
            }

            return source
                + " count=" + count
                + " rarity=" + rarityChance
                + " in_square=" + inSquare
                + " height=" + height;
        }

        private int[] resolvedYBounds() {
            if (heightProvider == null || generationContext == null) return new int[] {minY, maxY};

            Object minAnchor = readField(heightProvider, "minInclusive");
            Object maxAnchor = readField(heightProvider, "maxInclusive");
            if (minAnchor instanceof VerticalAnchor min && maxAnchor instanceof VerticalAnchor max) {
                return new int[] {min.resolveY(generationContext), max.resolveY(generationContext)};
            }

            Object valueAnchor = readField(heightProvider, "value");
            if (valueAnchor instanceof VerticalAnchor value) {
                int resolved = value.resolveY(generationContext);
                return new int[] {resolved, resolved};
            }

            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
            for (int i = 0; i < 4096; i++) {
                int y = heightProvider.sample(random, generationContext);
                min = Math.min(min, y);
                max = Math.max(max, y);
            }

            if (min == Integer.MAX_VALUE) return new int[] {0, 0};
            return new int[] {min, max};
        }
    }

    private static final class SimulatedTerrain {
        private final WorldgenEngine.TerrainAccessor base;
        private final int minY;
        private final int maxY;
        private final Map<Long, BlockState> overrides = new HashMap<>();

        private SimulatedTerrain(WorldgenEngine.TerrainAccessor base, int dimension) {
            this.base = base;
            this.minY = minBuildY(dimension);
            this.maxY = topAnchor(dimension) - 1;
        }

        private boolean isInsideBuildHeight(int y) {
            return y >= minY && y <= maxY;
        }

        private BlockState block(int blockX, int blockY, int blockZ) {
            if (!isInsideBuildHeight(blockY)) return Blocks.AIR.defaultBlockState();
            BlockState override = overrides.get(BlockPos.asLong(blockX, blockY, blockZ));
            return override != null ? override : base.block(blockX, blockY, blockZ);
        }

        private void setBlock(int blockX, int blockY, int blockZ, BlockState state) {
            if (!isInsideBuildHeight(blockY)) return;
            overrides.put(BlockPos.asLong(blockX, blockY, blockZ), state);
        }

        private int oceanFloorHeight(int blockX, int blockZ) {
            return base.oceanFloorHeight(blockX, blockZ);
        }
    }
}
