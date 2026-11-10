package me.seedexplorer.addon.worldgen;

import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.material.Fluids;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Generates and caches the vanilla noise/carver occupancy heightmap used by
 * structures. Unlike ChunkGenerator.getBaseHeight, this path incorporates
 * terrain-adapting structure starts and carvers that can remove surface blocks.
 */
public final class GeneratedTerrainHeightmap {
    private final long seed;
    private final int dimension;
    private final WorldgenEngine.GeneratorView view;
    private final NoiseBasedChunkGenerator generator;
    private final NoiseGeneratorSettings generatorSettings;
    private final BiomeResolver biomeResolver;
    private final StructureManager structureManager;
    private final PalettedContainerFactory containerFactory;
    private final boolean captureStageHeights;
    // Cave rendering can move through a large world. Keep a bounded working set
    // so exact carver replay does not grow memory without limit.
    private static final int DEFAULT_MAX_CACHED_CHUNKS = 48;
    private final int maximumCachedChunks;
    private final Map<Long, ProtoChunk> chunks = new LinkedHashMap<>(128, 0.75f, true);
    private final Map<Long, StageHeights> stageHeights = new LinkedHashMap<>(128, 0.75f, true);
    private final Set<Long> decoratedChunks = new HashSet<>();
    private final Set<Long> heightDecoratedChunks = new HashSet<>();
    private final List<DecorationFeatureTrace> decorationTrace = new ArrayList<>();
    private static final int NATURAL_DECORATION_NEIGHBOR_RADIUS = 1;
    private static final int MAX_NATURAL_DECORATED_ORIGINS = 192;
    private final Set<Long> naturalDecoratedOrigins = new HashSet<>();
    private final Map<Long, BlockState> naturalDecorationStates = new HashMap<>();
    private final Map<Long, Set<Long>> naturalDecorationPositions = new HashMap<>();
    private final Map<Long, int[]> naturalHeightCache = new HashMap<>();
    private static final Set<String> PRE_SURFACE_HEIGHT_FEATURES =
        Set.of("minecraft:lake_lava_surface");

    public GeneratedTerrainHeightmap(long seed, int dimension,
                                     List<StructureStart> terrainStarts) {
        this(seed, dimension, terrainStarts, true);
    }

    /** Renderer instances can skip stage snapshots that only diagnostics consume. */
    public GeneratedTerrainHeightmap(long seed, int dimension,
                                     List<StructureStart> terrainStarts,
                                     boolean captureStageHeights) {
        this(seed, dimension, terrainStarts, captureStageHeights,
            DEFAULT_MAX_CACHED_CHUNKS);
    }

    /** Allows streaming callers to retain only the working set they can reuse. */
    public GeneratedTerrainHeightmap(long seed, int dimension,
                                     List<StructureStart> terrainStarts,
                                     boolean captureStageHeights,
                                     int maximumCachedChunks) {
        this.seed = seed;
        this.dimension = dimension;
        this.captureStageHeights = captureStageHeights;
        this.maximumCachedChunks = Math.max(1, maximumCachedChunks);
        this.view = WorldgenEngine.generatorView(seed, dimension);
        this.generator = (NoiseBasedChunkGenerator) view.chunkGenerator();
        this.generatorSettings = view.lookup().lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(switch (dimension) {
                case -1 -> NoiseGeneratorSettings.NETHER;
                case 1 -> NoiseGeneratorSettings.END;
                default -> NoiseGeneratorSettings.OVERWORLD;
            }).value();
        this.biomeResolver = view.biomeSource().createUncachedResolver(view.randomState());
        this.structureManager = new TerrainStructureManager(seed, terrainStarts);
        this.containerFactory = PalettedContainerFactory.create(
            OfflineWorldgenResources.registryAccess());
    }

    public int firstFreeHeight(Heightmap.Types type, int blockX, int blockZ) {
        ProtoChunk chunk = chunk(new ChunkPos(
            Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16)));
        if (!chunk.hasPrimedHeightmap(type)) {
            Heightmap.primeHeightmaps(chunk, EnumSet.of(type));
        }
        // ChunkAccess#getHeight returns the highest occupied Y. WorldGenRegion,
        // which vanilla structure pieces query, adds one and exposes the first
        // free Y through LevelAccessor#getHeight/getHeightmapPos.
        return chunk.getHeight(type, blockX & 15, blockZ & 15) + 1;
    }

    public int stageHeight(TerrainStage stage, int blockX, int blockZ) {
        if (!captureStageHeights) {
            throw new IllegalStateException("Stage-height diagnostics were disabled for this generator");
        }
        ChunkPos pos = new ChunkPos(
            Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
        chunk(pos);
        StageHeights heights = stageHeights.get(pos.pack());
        int index = (blockZ & 15) * 16 + (blockX & 15);
        return switch (stage) {
            case NOISE -> heights.noise()[index];
            case SURFACE -> heights.surface()[index];
            case CARVERS -> heights.carvers()[index];
        };
    }

    public int minBuildHeight() {
        return view.noiseSettings().minY();
    }

    public int maxBuildHeight() {
        return view.noiseSettings().minY() + view.noiseSettings().height();
    }

    public BlockState blockState(long seed, int blockX, int blockY, int blockZ) {
        return chunk(new ChunkPos(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16)))
            .getBlockState(new BlockPos(blockX, blockY, blockZ));
    }

    public String blockStateId(int blockX, int blockY, int blockZ) {
        BlockPos pos = new BlockPos(blockX, blockY, blockZ);
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(
            chunk(new ChunkPos(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16)))
                .getBlockState(pos).getBlock()));
    }

    public enum TerrainStage {
        NOISE,
        SURFACE,
        CARVERS
    }

    /** Applies vanilla placed features from steps before the target structure step. */
    public void applyDecorationBefore(ChunkPos center, int exclusiveStep) {
        applyDecorationBefore(center, exclusiveStep, decoratedChunks, ignored -> true);
    }

    /**
     * Applies only pre-surface-structure features capable of changing the top
     * terrain height queried by desert-pyramid placement. Fossils are always
     * buried at least 15 blocks below the local surface; geodes, dungeons and
     * underground lakes likewise cannot become the highest occupied block.
     */
    public void applyHeightAffectingDecorationBefore(ChunkPos center,
                                                      int exclusiveStep) {
        applyDecorationBefore(center, exclusiveStep, heightDecoratedChunks,
            PRE_SURFACE_HEIGHT_FEATURES::contains);
    }

    private void applyDecorationBefore(ChunkPos center, int exclusiveStep,
                                       Set<Long> completedChunks,
                                       Predicate<String> includeFeature) {
        if (exclusiveStep <= 0 || !completedChunks.add(center.pack())) return;

        FeatureWorldHandler handler = new FeatureWorldHandler();
        WorldGenLevel level = featureWorld(handler);
        Set<Holder<Biome>> decorationBiomes =
            WorldgenEngine.decorationBiomes(seed, dimension, center.x(), center.z());
        List<FeatureSorter.StepFeatureData> steps =
            WorldgenEngine.featureSteps(seed, dimension);
        BlockPos origin = SectionPos.of(center, Math.floorDiv(view.noiseSettings().minY(), 16))
            .origin();
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long decorationSeed = random.setDecorationSeed(
            seed, origin.getX(), origin.getZ());

        int limit = Math.min(exclusiveStep, steps.size());
        for (int step = 0; step < limit; step++) {
            FeatureSorter.StepFeatureData stepData = steps.get(step);
            TreeSet<Integer> indices = new TreeSet<>();
            for (Holder<Biome> biome : decorationBiomes) {
                List<net.minecraft.core.HolderSet<PlacedFeature>> biomeSteps =
                    biome.value().getGenerationSettings().features();
                if (step >= biomeSteps.size()) continue;
                for (Holder<PlacedFeature> feature : biomeSteps.get(step)) {
                    indices.add(stepData.indexMapping().applyAsInt(feature.value()));
                }
            }

            for (int index : indices) {
                PlacedFeature feature = stepData.features().get(index);
                String featureId = String.valueOf(OfflineWorldgenResources.registryAccess()
                    .lookupOrThrow(Registries.PLACED_FEATURE).getKey(feature));
                if (!includeFeature.test(featureId)) continue;
                int writesBefore = handler.writeCount;
                int positionsBefore = handler.writtenPositions.size();
                random.setFeatureSeed(decorationSeed, index, step);
                boolean placed = new FeaturePlacer(level, generator)
                    .placeWithBiomeCheck(feature, random, origin);
                decorationTrace.add(new DecorationFeatureTrace(
                    center.x(), center.z(), step, index,
                    featureId,
                    placed, handler.writeCount - writesBefore,
                    handler.boundsSince(positionsBefore)));
            }
        }
    }

    public List<DecorationFeatureTrace> decorationTrace() {
        return List.copyOf(decorationTrace);
    }

    public record DecorationFeatureTrace(int chunkX, int chunkZ, int step,
                                         int index, String featureId,
                                         boolean placed, int blockWrites,
                                         String writeBounds) {
    }

    /**
     * Runs the complete vanilla placed-feature sequence for this chunk and every
     * neighboring origin that can write across its boundary. The returned list
     * contains only natural visible feature blocks; terrain replacement writes
     * remain available to later features while avoiding duplicate ground geometry
     * in the renderer's height-field pass.
     */
    public NaturalDecorationSnapshot naturalDecorationSnapshot(ChunkPos target) {
        int missingOrigins = 0;
        for (int dz = -NATURAL_DECORATION_NEIGHBOR_RADIUS;
             dz <= NATURAL_DECORATION_NEIGHBOR_RADIUS; dz++) {
            for (int dx = -NATURAL_DECORATION_NEIGHBOR_RADIUS;
                 dx <= NATURAL_DECORATION_NEIGHBOR_RADIUS; dx++) {
                if (!naturalDecoratedOrigins.contains(ChunkPos.pack(
                    target.x() + dx, target.z() + dz))) missingOrigins++;
            }
        }
        if (naturalDecoratedOrigins.size() + missingOrigins
            > MAX_NATURAL_DECORATED_ORIGINS) {
            clearNaturalDecorationWorkingSet();
        }

        for (int dz = -NATURAL_DECORATION_NEIGHBOR_RADIUS;
             dz <= NATURAL_DECORATION_NEIGHBOR_RADIUS; dz++) {
            for (int dx = -NATURAL_DECORATION_NEIGHBOR_RADIUS;
                 dx <= NATURAL_DECORATION_NEIGHBOR_RADIUS; dx++) {
                applyAllNaturalDecoration(new ChunkPos(target.x() + dx, target.z() + dz));
            }
        }

        Set<Long> positions = naturalDecorationPositions.get(target.pack());
        if (positions == null || positions.isEmpty()) {
            return new NaturalDecorationSnapshot(List.of(), naturalDecorationHash(List.of()));
        }
        List<NaturalDecorationBlock> blocks = new ArrayList<>();
        for (long packed : positions) {
            BlockPos pos = BlockPos.of(packed);
            BlockState state = naturalBlockState(pos);
            if (isNaturalDecorationBlock(state)) {
                blocks.add(new NaturalDecorationBlock(
                    pos.getX(), pos.getY(), pos.getZ(), state));
            }
        }
        blocks.sort(java.util.Comparator
            .comparingInt(NaturalDecorationBlock::y)
            .thenComparingInt(NaturalDecorationBlock::z)
            .thenComparingInt(NaturalDecorationBlock::x)
            .thenComparing(block -> blockStateKey(block.state())));
        List<NaturalDecorationBlock> immutable = List.copyOf(blocks);
        return new NaturalDecorationSnapshot(immutable, naturalDecorationHash(immutable));
    }

    public record NaturalDecorationBlock(int x, int y, int z, BlockState state) {
    }

    public record NaturalDecorationSnapshot(List<NaturalDecorationBlock> blocks,
                                            long deterministicHash) {
    }

    private void applyAllNaturalDecoration(ChunkPos center) {
        if (!naturalDecoratedOrigins.add(center.pack())) return;

        NaturalFeatureWorldHandler handler = new NaturalFeatureWorldHandler();
        WorldGenLevel level = featureWorld(handler);
        Set<Holder<Biome>> decorationBiomes =
            WorldgenEngine.decorationBiomes(seed, dimension, center.x(), center.z());
        List<FeatureSorter.StepFeatureData> steps =
            WorldgenEngine.featureSteps(seed, dimension);
        BlockPos origin = SectionPos.of(center,
            Math.floorDiv(view.noiseSettings().minY(), 16)).origin();
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long decorationSeed = random.setDecorationSeed(seed, origin.getX(), origin.getZ());

        for (int step = 0; step < steps.size(); step++) {
            FeatureSorter.StepFeatureData stepData = steps.get(step);
            TreeSet<Integer> indices = new TreeSet<>();
            for (Holder<Biome> biome : decorationBiomes) {
                List<net.minecraft.core.HolderSet<PlacedFeature>> biomeSteps =
                    biome.value().getGenerationSettings().features();
                if (step >= biomeSteps.size()) continue;
                for (Holder<PlacedFeature> feature : biomeSteps.get(step)) {
                    indices.add(stepData.indexMapping().applyAsInt(feature.value()));
                }
            }
            for (int index : indices) {
                PlacedFeature feature = stepData.features().get(index);
                String featureId = String.valueOf(OfflineWorldgenResources.registryAccess()
                    .lookupOrThrow(Registries.PLACED_FEATURE).getKey(feature));
                if (!isNaturalPlacedFeature(featureId)) continue;
                random.setFeatureSeed(decorationSeed, index, step);
                new FeaturePlacer(level, generator)
                    .placeWithBiomeCheck(feature, random, origin);
            }
        }
    }

    /** Placement RNG is reseeded from (decoration seed, feature index, step), so
     * skipping unrelated ores and geodes cannot shift the selected positions of
     * vegetation features. Natural features still execute in vanilla order and
     * interact with earlier natural writes. */
    static boolean isNaturalPlacedFeature(String featureId) {
        if (featureId == null) return false;
        String id = featureId.startsWith("minecraft:")
            ? featureId.substring("minecraft:".length()) : featureId;
        return id.contains("tree") || id.contains("vegetation")
            || id.contains("flower") || id.contains("grass")
            || id.contains("fern") || id.contains("bush")
            || id.contains("seagrass") || id.contains("kelp")
            || id.contains("coral") || id.contains("sea_pickle")
            || id.contains("bamboo") || id.contains("cactus")
            || id.contains("sugar_cane") || id.contains("mushroom")
            || id.contains("fungus") || id.contains("vines")
            || id.contains("roots") || id.contains("sprouts")
            || id.contains("moss") || id.contains("lichen")
            || id.contains("dripleaf") || id.contains("azalea")
            || id.contains("glow_berries") || id.contains("berry")
            || id.contains("pumpkin") || id.contains("melon")
            || id.contains("chorus") || id.contains("leaf_litter")
            || id.contains("lily_pad") || id.contains("spore_blossom");
    }

    private void clearNaturalDecorationWorkingSet() {
        naturalDecoratedOrigins.clear();
        naturalDecorationStates.clear();
        naturalDecorationPositions.clear();
        naturalHeightCache.clear();
    }

    private BlockState naturalBlockState(BlockPos pos) {
        BlockState decorated = naturalDecorationStates.get(pos.asLong());
        if (decorated != null) return decorated;
        return chunk(new ChunkPos(Math.floorDiv(pos.getX(), 16),
            Math.floorDiv(pos.getZ(), 16))).getBlockState(pos);
    }

    private int naturalFirstFreeHeight(Heightmap.Types type, int blockX, int blockZ) {
        long columnKey = ChunkPos.pack(blockX, blockZ);
        int[] cached = naturalHeightCache.computeIfAbsent(columnKey, ignored -> {
            int[] values = new int[Heightmap.Types.values().length];
            java.util.Arrays.fill(values, Integer.MIN_VALUE);
            return values;
        });
        int typeIndex = type.ordinal();
        if (cached[typeIndex] != Integer.MIN_VALUE) return cached[typeIndex];

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int firstFree = view.noiseSettings().minY();
        for (int y = maxBuildHeight() - 1; y >= minBuildHeight(); y--) {
            cursor.set(blockX, y, blockZ);
            if (type.isOpaque().test(naturalBlockState(cursor))) {
                firstFree = y + 1;
                break;
            }
        }
        cached[typeIndex] = firstFree;
        return firstFree;
    }

    private void writeNaturalDecoration(BlockPos pos, BlockState state) {
        long packed = pos.asLong();
        naturalDecorationStates.put(packed, state);
        long chunkKey = ChunkPos.pack(Math.floorDiv(pos.getX(), 16),
            Math.floorDiv(pos.getZ(), 16));
        naturalDecorationPositions.computeIfAbsent(chunkKey,
            ignored -> new HashSet<>()).add(packed);
        naturalHeightCache.remove(ChunkPos.pack(pos.getX(), pos.getZ()));
    }

    public static boolean isNaturalDecorationBlock(BlockState state) {
        if (state == null || state.isAir()) return false;
        String id = String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
        return id.endsWith("_leaves") || id.endsWith("_log")
            || id.endsWith("_wood") || id.endsWith("_stem")
            || id.endsWith("_hyphae") || id.endsWith("_sapling")
            || id.endsWith("_flower") || id.endsWith("_tulip")
            || id.endsWith("_orchid") || id.endsWith("_roots")
            || id.endsWith("_fungus") || id.endsWith("_coral")
            || id.endsWith("_coral_fan") || id.endsWith("_coral_wall_fan")
            || id.endsWith("_mushroom") || id.endsWith("_mushroom_block")
            || id.endsWith("_vines") || id.endsWith("_vines_plant")
            || id.endsWith("_dripleaf") || id.endsWith("_dripleaf_stem")
            || id.contains("azalea") || id.contains("mangrove_roots")
            || id.contains("moss_carpet") || id.contains("hanging_moss")
            || id.equals("short_grass") || id.equals("tall_grass")
            || id.equals("short_dry_grass") || id.equals("tall_dry_grass")
            || id.equals("fern") || id.equals("large_fern")
            || id.equals("dead_bush") || id.equals("firefly_bush")
            || id.equals("dandelion") || id.equals("poppy")
            || id.equals("allium") || id.equals("azure_bluet")
            || id.equals("oxeye_daisy") || id.equals("cornflower")
            || id.equals("lily_of_the_valley") || id.equals("wither_rose")
            || id.equals("sunflower") || id.equals("lilac")
            || id.equals("rose_bush") || id.equals("peony")
            || id.equals("pink_petals") || id.equals("wildflowers")
            || id.equals("leaf_litter") || id.equals("seagrass")
            || id.equals("tall_seagrass") || id.equals("kelp")
            || id.equals("kelp_plant") || id.equals("sea_pickle")
            || id.equals("lily_pad") || id.equals("vine")
            || id.equals("glow_lichen") || id.equals("hanging_roots")
            || id.equals("spore_blossom") || id.equals("cave_vines")
            || id.equals("cave_vines_plant") || id.equals("nether_sprouts")
            || id.equals("sugar_cane") || id.equals("cactus")
            || id.equals("cactus_flower") || id.equals("bamboo")
            || id.equals("bamboo_sapling") || id.equals("cocoa")
            || id.equals("pumpkin") || id.equals("melon")
            || id.equals("chorus_plant") || id.equals("chorus_flower")
            || id.equals("brown_mushroom_block") || id.equals("red_mushroom_block")
            || id.equals("mushroom_stem") || id.equals("nether_wart_block")
            || id.equals("warped_wart_block") || id.equals("shroomlight")
            || id.equals("bee_nest") || id.equals("beehive");
    }

    private static String blockStateKey(BlockState state) {
        return state.toString();
    }

    private static long naturalDecorationHash(List<NaturalDecorationBlock> blocks) {
        long hash = 0xCBF29CE484222325L;
        for (NaturalDecorationBlock block : blocks) {
            hash ^= BlockPos.asLong(block.x(), block.y(), block.z());
            hash *= 0x100000001B3L;
            hash ^= blockStateKey(block.state()).hashCode();
            hash *= 0x100000001B3L;
        }
        return hash;
    }

    public ProtoChunk getChunk(ChunkPos pos) { return chunk(pos); }

    private ProtoChunk chunk(ChunkPos pos) {
        long key = pos.pack();
        ProtoChunk existing = chunks.get(key);
        if (existing != null) return existing;

        ProtoChunk generated = generate(pos);
        chunks.put(key, generated);
        while (chunks.size() > maximumCachedChunks) {
            long eldest = chunks.keySet().iterator().next();
            chunks.remove(eldest);
            stageHeights.remove(eldest);
        }
        return generated;
    }

    private WorldGenLevel featureWorld(InvocationHandler handler) {
        return (WorldGenLevel) Proxy.newProxyInstance(
            WorldGenLevel.class.getClassLoader(),
            new Class<?>[] { WorldGenLevel.class },
            handler);
    }

    private final class FeatureWorldHandler implements InvocationHandler {
        private final RandomSource random = RandomSource.create(seed ^ 0xDEC0A710L);
        private int writeCount;
        private final List<BlockPos> writtenPositions = new ArrayList<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("getSeed")) return seed;
            if (name.equals("getRandom")) return random;
            if (name.equals("getLevel")) {
                return VanillaLootStructureSimulator.fakeLevelForSeed(seed, generator);
            }
            if (name.equals("registryAccess")) return OfflineWorldgenResources.registryAccess();
            if (name.equals("enabledFeatures")) return FeatureFlags.DEFAULT_FLAGS;
            if (name.equals("isClientSide")) return false;
            if (name.equals("getSeaLevel")) return generator.getSeaLevel();
            if (name.equals("getMinY") || name.equals("getMinBuildHeight")) {
                return view.noiseSettings().minY();
            }
            if (name.equals("getMaxY")) {
                return view.noiseSettings().minY() + view.noiseSettings().height() - 1;
            }
            if (name.equals("getHeight") && (args == null || args.length == 0)) {
                return view.noiseSettings().height();
            }
            if (name.equals("getMinSectionY")) {
                return Math.floorDiv(view.noiseSettings().minY(), 16);
            }
            if (name.equals("getMaxSectionY")) {
                return Math.floorDiv(
                    view.noiseSettings().minY() + view.noiseSettings().height() - 1, 16);
            }
            if (name.equals("getSectionsCount")) return view.noiseSettings().height() / 16;
            if (name.equals("getChunk")) {
                if (args != null && args.length >= 2
                    && args[0] instanceof Integer x && args[1] instanceof Integer z) {
                    return chunk(new ChunkPos(x, z));
                }
                if (args != null && args.length == 1 && args[0] instanceof BlockPos pos) {
                    return chunk(new ChunkPos(
                        Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16)));
                }
            }
            if (name.equals("getBlockState")) {
                BlockPos pos = (BlockPos) args[0];
                return chunkAt(pos).getBlockState(pos);
            }
            if (name.equals("getFluidState")) {
                BlockPos pos = (BlockPos) args[0];
                return chunkAt(pos).getFluidState(pos);
            }
            if (name.equals("isEmptyBlock")) {
                return chunkAt((BlockPos) args[0]).getBlockState((BlockPos) args[0]).isAir();
            }
            if (name.equals("isStateAtPosition")) {
                BlockPos pos = (BlockPos) args[0];
                @SuppressWarnings("unchecked")
                Predicate<BlockState> predicate = (Predicate<BlockState>) args[1];
                return predicate.test(chunkAt(pos).getBlockState(pos));
            }
            if (name.equals("isFluidAtPosition")) {
                BlockPos pos = (BlockPos) args[0];
                @SuppressWarnings("unchecked")
                Predicate<net.minecraft.world.level.material.FluidState> predicate =
                    (Predicate<net.minecraft.world.level.material.FluidState>) args[1];
                return predicate.test(chunkAt(pos).getFluidState(pos));
            }
            if (name.equals("setBlock")) {
                BlockPos pos = (BlockPos) args[0];
                BlockState state = (BlockState) args[1];
                chunkAt(pos).setBlockState(pos, state);
                writeCount++;
                writtenPositions.add(pos.immutable());
                return true;
            }
            if (name.equals("removeBlock") || name.equals("destroyBlock")) {
                BlockPos pos = (BlockPos) args[0];
                chunkAt(pos).setBlockState(pos, Blocks.AIR.defaultBlockState());
                writeCount++;
                writtenPositions.add(pos.immutable());
                return true;
            }
            if (name.equals("getHeight") && args != null && args.length == 3) {
                return firstFreeHeight(
                    (Heightmap.Types) args[0], (Integer) args[1], (Integer) args[2]);
            }
            if (name.equals("getHeightmapPos")) {
                Heightmap.Types type = (Heightmap.Types) args[0];
                BlockPos pos = (BlockPos) args[1];
                return new BlockPos(pos.getX(),
                    firstFreeHeight(type, pos.getX(), pos.getZ()), pos.getZ());
            }
            if (name.equals("getBiome")) {
                BlockPos pos = (BlockPos) args[0];
                return WorldgenEngine.getBiomeHolder(
                    seed, dimension, pos.getX(), pos.getY(), pos.getZ());
            }
            if (name.equals("getUncachedNoiseBiome")) {
                return biomeResolver.getNoiseBiome(
                    (Integer) args[0], (Integer) args[1], (Integer) args[2]);
            }
            if (name.equals("ensureCanWrite") || name.equals("hasChunk")
                || name.equals("hasChunkAt") || name.equals("isInWorldBounds")) return true;
            if (name.equals("isOutsideBuildHeight")) {
                if (args != null && args.length == 1 && args[0] instanceof BlockPos pos) {
                    return pos.getY() < view.noiseSettings().minY()
                        || pos.getY() >= view.noiseSettings().minY() + view.noiseSettings().height();
                }
                return false;
            }
            return defaultValue(method.getReturnType());
        }

        private String boundsSince(int firstIndex) {
            if (firstIndex >= writtenPositions.size()) return "none";
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (int i = firstIndex; i < writtenPositions.size(); i++) {
                BlockPos pos = writtenPositions.get(i);
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            return minX + "," + minY + "," + minZ + ".."
                + maxX + "," + maxY + "," + maxZ;
        }

        private ProtoChunk chunkAt(BlockPos pos) {
            return chunk(new ChunkPos(
                Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16)));
        }
    }

    /** Feature execution overlay used by map decoration snapshots. It leaves the
     * cached carved terrain immutable, so completed snapshots remain deterministic
     * even after the small base-chunk LRU evicts and regenerates a chunk. */
    private final class NaturalFeatureWorldHandler implements InvocationHandler {
        private final RandomSource random = RandomSource.create(seed ^ 0x4E41545552414C4CL);

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("getSeed")) return seed;
            if (name.equals("getRandom")) return random;
            if (name.equals("getLevel")) {
                return VanillaLootStructureSimulator.fakeLevelForSeed(seed, generator);
            }
            if (name.equals("registryAccess")) return OfflineWorldgenResources.registryAccess();
            if (name.equals("enabledFeatures")) return FeatureFlags.DEFAULT_FLAGS;
            if (name.equals("isClientSide")) return false;
            if (name.equals("getSeaLevel")) return generator.getSeaLevel();
            if (name.equals("getMinY") || name.equals("getMinBuildHeight")) {
                return minBuildHeight();
            }
            if (name.equals("getMaxY")) return maxBuildHeight() - 1;
            if (name.equals("getHeight") && (args == null || args.length == 0)) {
                return view.noiseSettings().height();
            }
            if (name.equals("getMinSectionY")) return Math.floorDiv(minBuildHeight(), 16);
            if (name.equals("getMaxSectionY")) return Math.floorDiv(maxBuildHeight() - 1, 16);
            if (name.equals("getSectionsCount")) return view.noiseSettings().height() / 16;
            if (name.equals("getChunk")) {
                if (args != null && args.length >= 2
                    && args[0] instanceof Integer x && args[1] instanceof Integer z) {
                    return chunk(new ChunkPos(x, z));
                }
                if (args != null && args.length == 1 && args[0] instanceof BlockPos pos) {
                    return chunk(new ChunkPos(Math.floorDiv(pos.getX(), 16),
                        Math.floorDiv(pos.getZ(), 16)));
                }
            }
            if (name.equals("getBlockState")) {
                return naturalBlockState((BlockPos) args[0]);
            }
            if (name.equals("getFluidState")) {
                return naturalBlockState((BlockPos) args[0]).getFluidState();
            }
            if (name.equals("getBlockEntity")) {
                // WorldGenLevel#getBlockEntity(pos, type) is an Optional-returning
                // convenience method. Tree decorators use it for bee nests; a null
                // proxy default aborts the entire placed-feature pass before the
                // finished tree blocks can reach the renderer.
                if (method.getReturnType() == Optional.class) return Optional.empty();
                return null;
            }
            if (name.equals("isEmptyBlock")) {
                return naturalBlockState((BlockPos) args[0]).isAir();
            }
            if (name.equals("isStateAtPosition")) {
                @SuppressWarnings("unchecked")
                Predicate<BlockState> predicate = (Predicate<BlockState>) args[1];
                return predicate.test(naturalBlockState((BlockPos) args[0]));
            }
            if (name.equals("isFluidAtPosition")) {
                @SuppressWarnings("unchecked")
                Predicate<net.minecraft.world.level.material.FluidState> predicate =
                    (Predicate<net.minecraft.world.level.material.FluidState>) args[1];
                return predicate.test(naturalBlockState((BlockPos) args[0]).getFluidState());
            }
            if (name.equals("setBlock")) {
                writeNaturalDecoration((BlockPos) args[0], (BlockState) args[1]);
                return true;
            }
            if (name.equals("removeBlock") || name.equals("destroyBlock")) {
                writeNaturalDecoration((BlockPos) args[0], Blocks.AIR.defaultBlockState());
                return true;
            }
            if (name.equals("getHeight") && args != null && args.length == 3) {
                return naturalFirstFreeHeight((Heightmap.Types) args[0],
                    (Integer) args[1], (Integer) args[2]);
            }
            if (name.equals("getHeightmapPos")) {
                Heightmap.Types type = (Heightmap.Types) args[0];
                BlockPos pos = (BlockPos) args[1];
                return new BlockPos(pos.getX(),
                    naturalFirstFreeHeight(type, pos.getX(), pos.getZ()), pos.getZ());
            }
            if (name.equals("getBiome")) {
                BlockPos pos = (BlockPos) args[0];
                return WorldgenEngine.getBiomeHolder(seed, dimension,
                    pos.getX(), pos.getY(), pos.getZ());
            }
            if (name.equals("getUncachedNoiseBiome")) {
                return biomeResolver.getNoiseBiome((Integer) args[0],
                    (Integer) args[1], (Integer) args[2]);
            }
            if (name.equals("ensureCanWrite") || name.equals("hasChunk")
                || name.equals("hasChunkAt") || name.equals("isInWorldBounds")) return true;
            if (name.equals("isOutsideBuildHeight")) {
                if (args != null && args.length == 1 && args[0] instanceof BlockPos pos) {
                    return pos.getY() < minBuildHeight() || pos.getY() >= maxBuildHeight();
                }
                return false;
            }
            return defaultValue(method.getReturnType());
        }
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

    private ProtoChunk generate(ChunkPos pos) {
        LevelHeightAccessor heights = LevelHeightAccessor.create(
            view.noiseSettings().minY(), view.noiseSettings().height());
        ProtoChunk chunk = new ProtoChunk(
            pos, UpgradeData.EMPTY, heights, containerFactory, null);
        Blender blender = Blender.empty();

        generator.createBiomes(view.randomState(), blender, structureManager, chunk).join();
        BiomeManager biomeManager = biomeManager();
        generator.buildTerrain(chunk, blender, view.randomState(), structureManager,
            biomeManager, null, view.biomeSource().possibleBiomes()).join();
        if (captureStageHeights) {
            int[] finalHeights = scanHeights(chunk,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
            stageHeights.put(pos.pack(), new StageHeights(
                finalHeights.clone(), finalHeights.clone(), finalHeights));
        }

        // ChunkStatusTasks.generateFeatures primes the live heightmaps only
        // after CARVERS has completed. Re-scan the finished carved blocks in
        // the same order instead of relying on incremental carver updates.
        Heightmap.primeHeightmaps(chunk, EnumSet.of(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.WORLD_SURFACE_WG));
        return chunk;
    }

    private static int[] scanHeights(ProtoChunk chunk, Heightmap.Types type) {
        int[] result = new int[16 * 16];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minY = chunk.getMinY();
        int maxY = chunk.getMaxY();
        ChunkPos pos = chunk.getPos();
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int firstFree = minY;
                for (int y = maxY; y >= minY; y--) {
                    cursor.set(pos.getMinBlockX() + localX, y,
                        pos.getMinBlockZ() + localZ);
                    if (type.isOpaque().test(chunk.getBlockState(cursor))) {
                        firstFree = y + 1;
                        break;
                    }
                }
                result[localZ * 16 + localX] = firstFree;
            }
        }
        return result;
    }

    private record StageHeights(int[] noise, int[] surface, int[] carvers) {
    }

    private BiomeManager biomeManager() {
        return new BiomeManager(biomeResolver, BiomeManager.obfuscateSeed(seed));
    }

    private static final class TerrainStructureManager extends StructureManager {
        private final List<StructureStart> starts;

        private TerrainStructureManager(long seed, List<StructureStart> starts) {
            super(null, new WorldOptions(seed, true, false), null);
            this.starts = List.copyOf(starts);
        }

        @Override
        public List<StructureStart> startsForStructure(
            int sectionX, int sectionZ, Predicate<Structure> predicate) {
            List<StructureStart> result = new ArrayList<>();
            for (StructureStart start : starts) {
                if (start.isValid() && predicate.test(start.getStructure())) result.add(start);
            }
            return result;
        }

        @Override
        public net.minecraft.core.RegistryAccess registryAccess() {
            return OfflineWorldgenResources.registryAccess();
        }
    }
}
