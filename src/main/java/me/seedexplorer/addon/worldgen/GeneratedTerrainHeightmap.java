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
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.material.Fluids;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final StructureManager structureManager;
    private final PalettedContainerFactory containerFactory;
    private final Map<Long, ProtoChunk> chunks = new HashMap<>();
    private final Map<Long, StageHeights> stageHeights = new HashMap<>();
    private final Set<Long> decoratedChunks = new HashSet<>();
    private final Set<Long> heightDecoratedChunks = new HashSet<>();
    private final List<DecorationFeatureTrace> decorationTrace = new ArrayList<>();
    private static final Set<String> PRE_SURFACE_HEIGHT_FEATURES =
        Set.of("minecraft:lake_lava_surface");

    public GeneratedTerrainHeightmap(long seed, int dimension,
                                     List<StructureStart> terrainStarts) {
        this.seed = seed;
        this.dimension = dimension;
        this.view = WorldgenEngine.generatorView(seed, dimension);
        this.generator = (NoiseBasedChunkGenerator) view.chunkGenerator();
        this.generatorSettings = view.lookup().lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(switch (dimension) {
                case -1 -> NoiseGeneratorSettings.NETHER;
                case 1 -> NoiseGeneratorSettings.END;
                default -> NoiseGeneratorSettings.OVERWORLD;
            }).value();
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
                boolean placed = feature.placeWithBiomeCheck(
                    level, generator, random, origin);
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

    public ProtoChunk getChunk(ChunkPos pos) { return chunk(pos); }

    private ProtoChunk chunk(ChunkPos pos) {
        return chunks.computeIfAbsent(pos.pack(), ignored -> generate(pos));
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
            if (name.equals("getLevel")) return VanillaLootStructureSimulator.fakeLevelForSeed(seed);
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
                return view.biomeSource().getNoiseBiome(
                    (Integer) args[0], (Integer) args[1], (Integer) args[2],
                    view.randomState().sampler());
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
        generator.fillFromNoise(blender, view.randomState(), structureManager, chunk).join();
        int[] afterNoise = scanHeights(chunk,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);

        BiomeManager biomeManager = biomeManager();
        Registry<Biome> biomeRegistry = OfflineWorldgenResources.registryAccess()
            .lookupOrThrow(Registries.BIOME);
        generator.buildSurface(
            chunk,
            new WorldGenerationContext(generator, heights),
            view.randomState(),
            structureManager,
            biomeManager,
            biomeRegistry,
            blender);
        int[] afterSurface = scanHeights(chunk,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);

        applyCarvers(chunk);
        int[] afterCarvers = scanHeights(chunk,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        stageHeights.put(pos.pack(), new StageHeights(
            afterNoise, afterSurface, afterCarvers));

        // ChunkStatusTasks.generateFeatures primes the live heightmaps only
        // after CARVERS has completed. Re-scan the finished carved blocks in
        // the same order instead of relying on incremental carver updates.
        Heightmap.primeHeightmaps(chunk,
            EnumSet.of(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
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

    private void applyCarvers(ProtoChunk target) {
        NoiseChunk noiseChunk = target.getOrCreateNoiseChunk(chunk -> {
            throw new IllegalStateException("Noise chunk was not created by fillFromNoise");
        });
        Aquifer aquifer = noiseChunk.aquifer();
        CarvingContext context = new CarvingContext(
            generator,
            OfflineWorldgenResources.registryAccess(),
            target.getHeightAccessorForGeneration(),
            noiseChunk,
            view.randomState(),
            generatorSettings.surfaceRule());
        var mask = target.getOrCreateCarvingMask();
        BiomeManager biomeManager = biomeManager();

        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        ChunkPos targetPos = target.getPos();

        for (int offsetX = -8; offsetX <= 8; offsetX++) {
            for (int offsetZ = -8; offsetZ <= 8; offsetZ++) {
                ChunkPos sourcePos = new ChunkPos(
                    targetPos.x() + offsetX, targetPos.z() + offsetZ);
                Holder<Biome> sourceBiome = view.biomeSource().getNoiseBiome(
                    QuartPos.fromBlock(sourcePos.getMinBlockX()),
                    0,
                    QuartPos.fromBlock(sourcePos.getMinBlockZ()),
                    view.randomState().sampler());
                BiomeGenerationSettings settings =
                    generator.getBiomeGenerationSettings(sourceBiome);
                int carverIndex = 0;
                for (Holder<ConfiguredWorldCarver<?>> holder : settings.getCarvers()) {
                    random.setLargeFeatureSeed(
                        seed + carverIndex, sourcePos.x(), sourcePos.z());
                    ConfiguredWorldCarver<?> carver = holder.value();
                    if (carver.isStartChunk(random)) {
                        carver.carve(
                            context,
                            target,
                            biomeManager::getBiome,
                            random,
                            aquifer,
                            sourcePos,
                            mask);
                    }
                    carverIndex++;
                }
            }
        }
    }

    private BiomeManager biomeManager() {
        return new BiomeManager(
            (quartX, quartY, quartZ) -> view.biomeSource().getNoiseBiome(
                quartX, quartY, quartZ, view.randomState().sampler()),
            BiomeManager.obfuscateSeed(seed));
    }

    private static final class TerrainStructureManager extends StructureManager {
        private final List<StructureStart> starts;

        private TerrainStructureManager(long seed, List<StructureStart> starts) {
            super(null, new WorldOptions(seed, true, false), null);
            this.starts = List.copyOf(starts);
        }

        @Override
        public List<StructureStart> startsForStructure(
            ChunkPos chunkPos, Predicate<Structure> predicate) {
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
