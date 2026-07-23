/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Predicts worldgen data from a seed using vanilla Minecraft worldgen classes.
 * Currently this powers biome lookup; structures and ores will be moved onto
 * this engine after biome parity is verified.
 */
public final class WorldgenEngine {
    private static final Object LOCK = new Object();
    /** When true, logs biome-rejection diagnostics. Off by default — these fire on
     * the hot prediction path (every chunk that fails a structure's biome check,
     * which is the common case during searches). */
    private static final boolean DEBUG = Boolean.getBoolean("seedexplorer.worldgenDebug");
    private static volatile HolderLookup.Provider vanillaLookup;
    private static volatile RegistryAccess vanillaRegistryAccess;
    private static volatile EngineCache overworldCache;
    private static volatile EngineCache netherCache;
    private static volatile EngineCache endCache;
    private static volatile StructureStateCache overworldStructureState;
    private static volatile StructureStateCache netherStructureState;
    private static volatile StructureStateCache endStructureState;

    private WorldgenEngine() {
    }

    public static PredictedBiome getBiome(long seed, int dimension, int blockX, int blockY, int blockZ) {
        if (seed == 0) return PredictedBiome.UNKNOWN;

        try {
            EngineCache cache = getCache(seed, dimension);
            Holder<Biome> biome = getBiomeHolder(cache, blockX, blockY, blockZ);
            return fromHolder(cache.biomeRegistry(), biome);
        } catch (Throwable ignored) {
            return PredictedBiome.UNKNOWN;
        }
    }

    public static Holder<Biome> getBiomeHolder(long seed, int dimension, int blockX, int blockY, int blockZ) {
        try {
            return getBiomeHolder(getCache(seed, dimension), blockX, blockY, blockZ);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean biomeHasFeature(long seed, int dimension, int blockX, int blockY, int blockZ, PlacedFeature feature) {
        try {
            Holder<Biome> biome = getBiomeHolder(getCache(seed, dimension), blockX, blockY, blockZ);
            return biome != null && biome.value().getGenerationSettings().hasFeature(feature);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Set<Holder<Biome>> decorationBiomes(long seed, int dimension, int chunkX, int chunkZ) {
        try {
            EngineCache cache = getCache(seed, dimension);
            Set<Holder<Biome>> biomes = new HashSet<>();
            int minSectionY = viewMinSectionY(dimension);
            int maxSectionY = viewMaxSectionY(dimension);

            for (int neighborChunkZ = chunkZ - 1; neighborChunkZ <= chunkZ + 1; neighborChunkZ++) {
                for (int neighborChunkX = chunkX - 1; neighborChunkX <= chunkX + 1; neighborChunkX++) {
                    int quartBaseX = QuartPos.fromBlock(neighborChunkX << 4);
                    int quartBaseZ = QuartPos.fromBlock(neighborChunkZ << 4);

                    for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                        int quartBaseY = QuartPos.fromSection(sectionY);
                        for (int quartZ = 0; quartZ < 4; quartZ++) {
                            for (int quartY = 0; quartY < 4; quartY++) {
                                for (int quartX = 0; quartX < 4; quartX++) {
                                    Holder<Biome> biome = cache.biomeSource().getNoiseBiome(
                                        quartBaseX + quartX,
                                        quartBaseY + quartY,
                                        quartBaseZ + quartZ,
                                        cache.sampler()
                                    );
                                    if (biome != null) biomes.add(biome);
                                }
                            }
                        }
                    }
                }
            }

            biomes.remove(null);
            biomes.retainAll(cache.biomeSource().possibleBiomes());
            return Set.copyOf(biomes);
        } catch (Throwable ignored) {
            return Set.of();
        }
    }

    private static int viewMinSectionY(int dimension) {
        return dimension == -1 ? 0 : -4;
    }

    private static int viewMaxSectionY(int dimension) {
        return dimension == -1 ? 7 : 19;
    }

    public static boolean anyBiomeHasFeature(Set<Holder<Biome>> biomes, PlacedFeature feature) {
        try {
            for (Holder<Biome> biome : biomes) {
                if (biome != null && biome.value().getGenerationSettings().hasFeature(feature)) return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    public static List<FeatureSorter.StepFeatureData> featureSteps(long seed, int dimension) {
        EngineCache cache = getCache(seed, dimension);
        return FeatureSorter.buildFeaturesPerStep(
            List.copyOf(cache.biomeSource().possibleBiomes()),
            biome -> biome.value().getGenerationSettings().features(),
            true
        );
    }

    public static TerrainAccessor terrainAccessor(long seed, int dimension) {
        return new TerrainAccessor(generatorView(seed, dimension));
    }

    public static BlockState baseBlock(long seed, int dimension, int blockX, int blockY, int blockZ) {
        try {
            return terrainAccessor(seed, dimension).block(blockX, blockY, blockZ);
        } catch (Throwable ignored) {
            return Blocks.AIR.defaultBlockState();
        }
    }

    public static OreVeinAccessor oreVeinAccessor(long seed) {
        return new OreVeinAccessor(generatorView(seed, 0));
    }

    public static WorldGenerationContext worldGenerationContext(long seed, int dimension) {
        GeneratorView view = generatorView(seed, dimension);
        LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(view.noiseSettings().minY(), view.noiseSettings().height());
        return new WorldGenerationContext(view.chunkGenerator(), heightAccessor);
    }

    public static PredictedBiome fromRuntimeBiome(Holder<Biome> biome) {
        return fromHolder(null, biome);
    }

    public static void clear() {
        synchronized (LOCK) {
            overworldCache = null;
            netherCache = null;
            endCache = null;
            overworldStructureState = null;
            netherStructureState = null;
            endStructureState = null;
        }
    }

    public static HolderLookup.Provider vanillaLookup() {
        return lookup();
    }

    /**
     * Runs vanilla's structure-start generator for an already selected structure chunk.
     * This is intentionally exposed for deterministic structure/loot simulation; callers
     * still need to run the returned pieces through the decoration placement RNG.
     */
    public static StructureStart generateStructureStart(long seed, int dimension,
                                                         ResourceKey<Structure> structureKey,
                                                         ChunkPos chunkPos) {
        return generateStructureStart(seed, dimension, structureKey, chunkPos, false);
    }

    /**
     * Generates a start for a placement/biome candidate already validated by
     * the caller. This is required for datagen-backed structure tags, which are
     * deliberately unavailable in the lightweight vanilla lookup.
     */
    public static StructureStart generateSelectedStructureStart(long seed, int dimension,
                                                                 ResourceKey<Structure> structureKey,
                                                                 ChunkPos chunkPos) {
        return generateStructureStart(seed, dimension, structureKey, chunkPos, true);
    }

    private static StructureStart generateStructureStart(long seed, int dimension,
                                                          ResourceKey<Structure> structureKey,
                                                          ChunkPos chunkPos,
                                                          boolean biomeAlreadyValidated) {
        GeneratorView view = generatorView(seed, dimension);
        HolderLookup.Provider lookup = lookup();
        Holder<Structure> structure = lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(structureKey);
        ResourceKey<Level> levelKey = switch (dimension) {
            case -1 -> Level.NETHER;
            case 1 -> Level.END;
            default -> Level.OVERWORLD;
        };
        LevelHeightAccessor heights = LevelHeightAccessor.create(
            view.noiseSettings().minY(), view.noiseSettings().height());

        return structure.value().generate(
            structure,
            levelKey,
            OfflineWorldgenResources.registryAccess(),
            view.chunkGenerator(),
            view.biomeSource(),
            view.randomState(),
            OfflineWorldgenResources.structureTemplateManager(),
            seed,
            chunkPos,
            0,
            heights,
            biome -> {
                if (biomeAlreadyValidated) return true;
                try {
                    boolean result = structureKey.equals(BuiltinStructures.STRONGHOLD)
                        ? biomeSetContains(structure.value().biomes(), biome)
                        : structure.value().biomes().contains(biome);
                    if (!result) {
                        if (DEBUG) {
                            biome.unwrapKey().ifPresent(key ->
                                System.err.println("BIOME_REJECTED structure=" + structureKey.identifier()
                                    + " biome=" + key.identifier() + " chunk=" + chunkPos));
                        }
                    }
                    return result;
                } catch (Throwable t) {
                    // Tags unbound in this lookup — fall back to manual biome ID check.
                    String biomeId = biome.unwrapKey()
                        .map(key -> key.identifier().toString())
                        .orElse("");
                    boolean isDesertForPyramid = structureKey.equals(BuiltinStructures.DESERT_PYRAMID)
                        && biomeId.equals("minecraft:desert");
                    boolean isStronghold = structureKey.equals(BuiltinStructures.STRONGHOLD)
                        && !biomeId.contains("ocean")
                        && !biomeId.equals("minecraft:deep_dark")
                        && !biomeId.equals("minecraft:the_void");
                    boolean isOutpost = structureKey.equals(BuiltinStructures.PILLAGER_OUTPOST)
                        && (biomeId.equals("minecraft:desert")
                            || biomeId.equals("minecraft:plains")
                            || biomeId.equals("minecraft:savanna")
                            || biomeId.equals("minecraft:savanna_plateau")
                            || biomeId.equals("minecraft:snowy_plains")
                            || biomeId.equals("minecraft:snowy_taiga")
                            || biomeId.equals("minecraft:taiga")
                            || biomeId.equals("minecraft:old_growth_taiga")
                            || biomeId.equals("minecraft:sunflower_plains")
                            || biomeId.equals("minecraft:windswept_savanna")
                            || biomeId.equals("minecraft:windswept_hills")
                            || biomeId.equals("minecraft:windswept_forest")
                            || biomeId.equals("minecraft:meadow"));
                    boolean isTreasure = structureKey.equals(BuiltinStructures.BURIED_TREASURE)
                        && (biomeId.equals("minecraft:beach")
                            || biomeId.equals("minecraft:snowy_beach")
                            || biomeId.equals("minecraft:stony_shore"));
                    boolean isMineshaft = structureKey.equals(BuiltinStructures.MINESHAFT)
                        && !biomeId.contains("ocean")
                        && !biomeId.equals("minecraft:deep_dark")
                        && !biomeId.equals("minecraft:the_void");
                    boolean isShipwreck = structureKey.equals(BuiltinStructures.SHIPWRECK)
                        && (biomeId.contains("ocean")
                            || biomeId.contains("beach")
                            || biomeId.equals("minecraft:river")
                            || biomeId.equals("minecraft:frozen_river")
                            || biomeId.equals("minecraft:stony_shore"));
                    boolean isShipwreckBeached = structureKey.equals(BuiltinStructures.SHIPWRECK_BEACHED)
                        && (biomeId.contains("beach")
                            || biomeId.contains("ocean")
                            || biomeId.equals("minecraft:stony_shore"));
                    boolean isJungleTemple = structureKey.equals(BuiltinStructures.JUNGLE_TEMPLE)
                        && (biomeId.equals("minecraft:bamboo_jungle")
                            || biomeId.equals("minecraft:jungle")
                            || biomeId.equals("minecraft:sparse_jungle"));
                    boolean isValid = isDesertForPyramid || isStronghold || isOutpost || isTreasure || isMineshaft || isShipwreck || isShipwreckBeached || isJungleTemple;
                    if (!isValid && (structureKey.equals(BuiltinStructures.DESERT_PYRAMID)
                        || structureKey.equals(BuiltinStructures.STRONGHOLD)
                        || structureKey.equals(BuiltinStructures.PILLAGER_OUTPOST)
                        || structureKey.equals(BuiltinStructures.BURIED_TREASURE)
                        || structureKey.equals(BuiltinStructures.MINESHAFT)
                        || structureKey.equals(BuiltinStructures.SHIPWRECK)
                        || structureKey.equals(BuiltinStructures.SHIPWRECK_BEACHED)
                        || structureKey.equals(BuiltinStructures.JUNGLE_TEMPLE))) {
                        if (DEBUG) {
                            System.err.println("BIOME_FALLBACK_REJECTED structure=" + structureKey.identifier()
                                + " biome=" + biomeId + " chunk=" + chunkPos);
                        }
                    }
                    return isValid;
                }
            }
        );
    }

    public static int minBuildHeight(long seed, int dimension) {
        return generatorView(seed, dimension).noiseSettings().minY();
    }

    public static int overallHeight(long seed, int dimension) {
        return generatorView(seed, dimension).noiseSettings().height();
    }

    public static int generationHeight(long seed, int dimension, Heightmap.Types type, int blockX, int blockZ) {
        GeneratorView view = generatorView(seed, dimension);
        LevelHeightAccessor heights = LevelHeightAccessor.create(
            view.noiseSettings().minY(), view.noiseSettings().height());
        // Heightmap queries exposed by LevelAccessor return the first free block,
        // not the highest occupied block. Structure pieces use this value directly.
        return view.chunkGenerator().getFirstFreeHeight(blockX, blockZ, type, heights, view.randomState());
    }

    public static ChunkGenerator chunkGenerator(long seed, int dimension) {
        return generatorView(seed, dimension).chunkGenerator();
    }

    public static RegistryAccess publicRegistryAccess() {
        return registryAccess();
    }

    /** Full vanilla dynamic registries for headless verification tools. */
    public static RegistryAccess offlineRegistryAccess() {
        return OfflineWorldgenResources.registryAccess();
    }

    /** Shared vanilla structure templates for headless preview/extraction tools. */
    public static StructureTemplateManager structureTemplateManager() {
        return OfflineWorldgenResources.structureTemplateManager();
    }

    /** Returns concentric-ring structure positions (strongholds) for the given seed. */
    public static List<ChunkPos> ringPositions(long seed, int dimension,
                                                  ResourceKey<StructureSet> structureSetKey) {
        ChunkGeneratorStructureState state;
        try {
            state = structureState(seed, dimension);
        } catch (Exception e) {
            state = null;
        }
        var lookup = vanillaLookup().lookupOrThrow(Registries.STRUCTURE_SET);
        var holder = lookup.getOrThrow(structureSetKey);
        var placement = holder.value().placement();
        if (placement instanceof ConcentricRingsStructurePlacement rings) {
            if (state != null) {
                try {
                    List<ChunkPos> positions = state.getRingPositionsFor(rings);
                    if (positions != null && !positions.isEmpty()) return positions;
                } catch (Throwable ignored) {}
            }
            return computeRingPositionsDirect(seed, dimension, rings);
        }
        return List.of();
    }

    /**
     * Direct vanilla ring position computation, replicating the algorithm in
     * {@link ChunkGeneratorStructureState#generateRingPositions}.
     * Uses LegacyRandomSource seeded with the world seed (matching createForNormal's
     * concentricRingsSeed = seed) to produce identical positions without requiring
     * bound biome tags.
     */
    private static List<ChunkPos> computeRingPositionsDirect(long seed, int dimension,
                                                              ConcentricRingsStructurePlacement rings) {
        int distance = rings.distance();
        int count = rings.count();
        if (count == 0) return List.of();

        int spread = rings.spread();
        RandomSource random = RandomSource.create();
        random.setSeed(seed);

        double angle = random.nextDouble() * Math.PI * 2.0;
        List<ChunkPos> results = new ArrayList<>(count);
        int placedInRing = 0;
        int ring = 0;

        for (int i = 0; i < count; i++) {
            double radius = 4.0 * distance + distance * ring * 6
                          + (random.nextDouble() - 0.5) * distance * 2.5;
            int ringX = (int) Math.round(Math.cos(angle) * radius);
            int ringZ = (int) Math.round(Math.sin(angle) * radius);

            ChunkPos adjusted = adjustToPreferredBiome(seed, dimension, rings, ringX, ringZ, random.fork());
            results.add(adjusted);

            angle += Math.PI * 2.0 / spread;
            placedInRing++;

            if (placedInRing == spread) {
                ring++;
                placedInRing = 0;
                spread += 2 * spread / (ring + 1);
                spread = Math.min(spread, count - i);
                angle += random.nextDouble() * Math.PI * 2.0;
            }
        }

        return List.copyOf(results);
    }

    /**
     * Attempts to find a preferred biome for the given ring position.
     * Returns the biome-adjusted chunk position if found, or the raw position
     * if the biome search fails (e.g. unbound biome tags in offline lookups).
     */
    private static ChunkPos adjustToPreferredBiome(long seed, int dimension,
                                                    ConcentricRingsStructurePlacement rings,
                                                    int chunkX, int chunkZ,
                                                    RandomSource random) {
        try {
            EngineCache cache = getCache(seed, dimension);
            var pair = cache.biomeSource().findBiomeHorizontal(
                SectionPos.sectionToBlockCoord(chunkX, 8),
                0,
                SectionPos.sectionToBlockCoord(chunkZ, 8),
                112,
                preferredBiomePredicate(rings),
                random,
                cache.sampler()
            );
            if (pair != null) {
                BlockPos pos = pair.getFirst();
                return new ChunkPos(
                    SectionPos.blockToSectionCoord(pos.getX()),
                    SectionPos.blockToSectionCoord(pos.getZ())
                );
            }
        } catch (Throwable ignored) {
        }
        return new ChunkPos(chunkX, chunkZ);
    }

    /**
     * Datagen-created structure values can retain an unbound named biome set
     * even though the copied offline biome registry has the real vanilla tag.
     * Resolve named sets through that registry and compare identifiers because
     * the biome source and copied registry use different Holder owners.
     */
    private static boolean biomeSetContains(HolderSet<Biome> set, Holder<Biome> biome) {
        try {
            if (set.isBound() && set.contains(biome)) return true;
        } catch (UnsupportedOperationException ignored) {
        }

        Identifier biomeId = biome.unwrapKey().map(ResourceKey::identifier).orElse(null);
        if (biomeId == null) return false;
        var tag = set.unwrapKey();
        if (tag.isPresent()) {
            for (Holder<Biome> tagged : OfflineWorldgenResources.registryAccess()
                .lookupOrThrow(Registries.BIOME).getTagOrEmpty(tag.get())) {
                if (tagged.unwrapKey().map(ResourceKey::identifier)
                    .filter(biomeId::equals).isPresent()) return true;
            }
            return false;
        }

        try {
            return set.stream().anyMatch(candidate -> candidate.unwrapKey()
                .map(ResourceKey::identifier).filter(biomeId::equals).isPresent());
        } catch (UnsupportedOperationException ignored) {
            return false;
        }
    }

    private static Predicate<Holder<Biome>> preferredBiomePredicate(
        ConcentricRingsStructurePlacement rings) {
        return biome -> biomeSetContains(rings.preferredBiomes(), biome);
    }

    static RegistryAccess registryAccess() {
        RegistryAccess current = vanillaRegistryAccess;
        if (current != null) return current;
        synchronized (LOCK) {
            if (vanillaRegistryAccess == null) {
                vanillaRegistryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            }
            return vanillaRegistryAccess;
        }
    }

    static EngineView view(long seed, int dimension) {
        EngineCache cache = getCache(seed, dimension);
        return new EngineView(cache.biomeSource(), cache.sampler(), cache.biomeRegistry());
    }

    static GeneratorView generatorView(long seed, int dimension) {
        EngineCache cache = getCache(seed, dimension);
        HolderLookup.Provider lookup = lookup();
        Holder<NoiseGeneratorSettings> settings = switch (dimension) {
            case -1 -> lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
            case 1 -> lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.END);
            default -> lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        };
        return new GeneratorView(new NoiseBasedChunkGenerator(cache.biomeSource(), settings), cache.biomeSource(), cache.randomState(), settings.value().noiseSettings(), lookup);
    }

    static ChunkGeneratorStructureState structureState(long seed) {
        return structureState(seed, 0);
    }

    static ChunkGeneratorStructureState structureState(long seed, int dimension) {
        StructureStateCache current = switch (dimension) {
            case -1 -> netherStructureState;
            case 1 -> endStructureState;
            default -> overworldStructureState;
        };
        if (current != null && current.seed() == seed) return current.state();

        synchronized (LOCK) {
            current = switch (dimension) {
                case -1 -> netherStructureState;
                case 1 -> endStructureState;
                default -> overworldStructureState;
            };
            if (current != null && current.seed() == seed) return current.state();

            ChunkGeneratorStructureState created = createStructureState(seed, dimension);
            switch (dimension) {
                case -1 -> netherStructureState = new StructureStateCache(seed, created);
                case 1 -> endStructureState = new StructureStateCache(seed, created);
                default -> overworldStructureState = new StructureStateCache(seed, created);
            }
            return created;
        }
    }

    private static EngineCache getCache(long seed, int dimension) {
        EngineCache current = switch (dimension) {
            case -1 -> netherCache;
            case 1 -> endCache;
            default -> overworldCache;
        };
        if (current != null && current.seed() == seed) return current;

        synchronized (LOCK) {
            current = switch (dimension) {
                case -1 -> netherCache;
                case 1 -> endCache;
                default -> overworldCache;
            };
            if (current != null && current.seed() == seed) return current;

            EngineCache created = createCache(seed, dimension);
            switch (dimension) {
                case -1 -> netherCache = created;
                case 1 -> endCache = created;
                default -> overworldCache = created;
            }
            return created;
        }
    }

    private static Holder<Biome> getBiomeHolder(EngineCache cache, int blockX, int blockY, int blockZ) {
        return cache.biomeSource().getNoiseBiome(
            QuartPos.fromBlock(blockX),
            QuartPos.fromBlock(blockY),
            QuartPos.fromBlock(blockZ),
            cache.sampler()
        );
    }

    private static EngineCache createCache(long seed, int dimension) {
        HolderLookup.Provider lookup = lookup();
        HolderGetter<Biome> biomes = lookup.lookupOrThrow(Registries.BIOME);
        BiomeSource biomeSource;
        RandomState randomState;

        if (dimension == -1) {
            Holder<MultiNoiseBiomeSourceParameterList> preset = lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER);
            biomeSource = MultiNoiseBiomeSource.createFromPreset(preset);
            randomState = RandomState.create(lookup, NoiseGeneratorSettings.NETHER, seed);
        } else if (dimension == 1) {
            biomeSource = TheEndBiomeSource.create(biomes);
            randomState = RandomState.create(lookup, NoiseGeneratorSettings.END, seed);
        } else {
            Holder<MultiNoiseBiomeSourceParameterList> preset = lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
            biomeSource = MultiNoiseBiomeSource.createFromPreset(preset);
            randomState = RandomState.create(lookup, NoiseGeneratorSettings.OVERWORLD, seed);
        }

        return new EngineCache(seed, biomeSource, randomState.sampler(), randomState, biomes);
    }

    private static ChunkGeneratorStructureState createOverworldStructureState(long seed) {
        return createStructureState(seed, 0);
    }

    private static ChunkGeneratorStructureState createStructureState(long seed, int dimension) {
        RegistryAccess registryAccess = OfflineWorldgenResources.registryAccess();
        BiomeSource biomeSource;
        RandomState randomState;

        if (dimension == -1) {
            Holder<MultiNoiseBiomeSourceParameterList> preset = registryAccess.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER);
            biomeSource = MultiNoiseBiomeSource.createFromPreset(preset);
            randomState = RandomState.create(registryAccess, NoiseGeneratorSettings.NETHER, seed);
        } else if (dimension == 1) {
            HolderGetter<Biome> biomes = registryAccess.lookupOrThrow(Registries.BIOME);
            biomeSource = TheEndBiomeSource.create(biomes);
            randomState = RandomState.create(registryAccess, NoiseGeneratorSettings.END, seed);
        } else {
            Holder<MultiNoiseBiomeSourceParameterList> preset = registryAccess.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
            biomeSource = MultiNoiseBiomeSource.createFromPreset(preset);
            randomState = RandomState.create(registryAccess, NoiseGeneratorSettings.OVERWORLD, seed);
        }

        var structureSets = registryAccess.lookupOrThrow(Registries.STRUCTURE_SET);
        try {
            return ChunkGeneratorStructureState.createForNormal(randomState, seed, biomeSource, structureSets);
        } catch (UnsupportedOperationException e) {
            // Some biome tags may be unbound in the offline registry. Fall back to
            // computing ring positions directly without full StructureState validation.
            // This path only supports strongholds via direct position computation.
            return null;
        }
    }

    private static HolderLookup.Provider lookup() {
        HolderLookup.Provider current = vanillaLookup;
        if (current != null) return current;

        synchronized (LOCK) {
            if (vanillaLookup == null) vanillaLookup = VanillaRegistries.createLookup();
            return vanillaLookup;
        }
    }

    private static PredictedBiome fromHolder(HolderGetter<Biome> registry, Holder<Biome> biome) {
        if (biome == null) return PredictedBiome.UNKNOWN;

        return biome.unwrapKey()
            .map(ResourceKey::identifier)
            .map(Identifier::toString)
            .map(BiomeColorPalette::create)
            .orElseGet(() -> fallbackFromValue(registry, biome.value()));
    }

    private static PredictedBiome fallbackFromValue(HolderGetter<Biome> registry, Biome biome) {
        if (biome == null) return PredictedBiome.UNKNOWN;
        if (registry != null) {
            Holder.Reference<Biome> plains = registry.get(Biomes.PLAINS).orElse(null);
            if (plains != null && plains.value() == biome) return BiomeColorPalette.create("minecraft:plains");
        }
        return PredictedBiome.UNKNOWN;
    }

    public record EngineView(BiomeSource biomeSource, Climate.Sampler sampler, HolderGetter<Biome> biomeRegistry) {
    }

    public static final class TerrainAccessor {
        private final GeneratorView view;
        private final LevelHeightAccessor heightAccessor;
        private final Map<Long, NoiseColumn> columns = new HashMap<>();
        private final Map<Long, Integer> heights = new HashMap<>();
        private final PositionalRandomFactory bedrockFloorRandom;
        private final PositionalRandomFactory bedrockRoofRandom;

        private TerrainAccessor(GeneratorView view) {
            this.view = view;
            this.heightAccessor = LevelHeightAccessor.create(view.noiseSettings().minY(), view.noiseSettings().height());
            this.bedrockFloorRandom = view.randomState().getOrCreateRandomFactory(Identifier.withDefaultNamespace("bedrock_floor"));
            this.bedrockRoofRandom = view.randomState().getOrCreateRandomFactory(Identifier.withDefaultNamespace("bedrock_roof"));
        }

        public BlockState block(int blockX, int blockY, int blockZ) {
            if (!heightAccessor.isInsideBuildHeight(blockY)) return Blocks.AIR.defaultBlockState();
            if (isBedrock(blockX, blockY, blockZ)) return Blocks.BEDROCK.defaultBlockState();

            try {
                return column(blockX, blockZ).getBlock(blockY);
            } catch (Throwable ignored) {
                return Blocks.AIR.defaultBlockState();
            }
        }

        public int oceanFloorHeight(int blockX, int blockZ) {
            long key = (((long) blockX) << 32) ^ (blockZ & 0xffffffffL);
            return heights.computeIfAbsent(key, ignored -> {
                NoiseColumn column = column(blockX, blockZ);
                for (int y = heightAccessor.getMaxY() - 1; y >= heightAccessor.getMinY(); y--) {
                    if (!column.getBlock(y).isAir()) return y + 1;
                }
                return heightAccessor.getMinY();
            });
        }

        private NoiseColumn column(int blockX, int blockZ) {
            long key = (((long) blockX) << 32) ^ (blockZ & 0xffffffffL);
            return columns.computeIfAbsent(key, ignored ->
                view.chunkGenerator().getBaseColumn(blockX, blockZ, heightAccessor, view.randomState())
            );
        }

        private boolean isBedrock(int blockX, int blockY, int blockZ) {
            int minY = heightAccessor.getMinY();
            int maxY = heightAccessor.getMaxY() - 1;

            if (blockY <= minY) return true;
            if (blockY < minY + 5) {
                double chance = Mth.map(blockY, minY, minY + 5, 1.0D, 0.0D);
                RandomSource random = bedrockFloorRandom.at(blockX, blockY, blockZ);
                return random.nextFloat() < chance;
            }

            if (view.noiseSettings().height() == 128) {
                if (blockY >= maxY) return true;
                if (blockY > maxY - 5) {
                    double chance = Mth.map(blockY, maxY - 5, maxY, 0.0D, 1.0D);
                    RandomSource random = bedrockRoofRandom.at(blockX, blockY, blockZ);
                    return random.nextFloat() < chance;
                }
            }

            return false;
        }
    }

    public static final class OreVeinAccessor {
        private final GeneratorView view;

        private OreVeinAccessor(GeneratorView view) {
            this.view = view;
        }

        public double veinToggle(int blockX, int blockY, int blockZ) {
            return view.randomState().router().veinToggle().compute(new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(blockX, blockY, blockZ));
        }

        public double veinRidged(int blockX, int blockY, int blockZ) {
            return view.randomState().router().veinRidged().compute(new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(blockX, blockY, blockZ));
        }

        public double veinGap(int blockX, int blockY, int blockZ) {
            return view.randomState().router().veinGap().compute(new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(blockX, blockY, blockZ));
        }

        public float oreRandomFloat(int blockX, int blockY, int blockZ, int index) {
            net.minecraft.util.RandomSource random = view.randomState().oreRandom().at(blockX, blockY, blockZ);
            float result = 0.0f;
            for (int i = 0; i <= index; i++) result = random.nextFloat();
            return result;
        }
    }

    record GeneratorView(ChunkGenerator chunkGenerator, BiomeSource biomeSource, RandomState randomState, NoiseSettings noiseSettings, HolderLookup.Provider lookup) {
    }

    private record EngineCache(long seed, BiomeSource biomeSource, Climate.Sampler sampler, RandomState randomState, HolderGetter<Biome> biomeRegistry) {
    }

    private record StructureStateCache(long seed, ChunkGeneratorStructureState state) {
    }
}
