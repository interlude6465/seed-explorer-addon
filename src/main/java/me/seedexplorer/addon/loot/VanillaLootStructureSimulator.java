package me.seedexplorer.addon.loot;

import me.seedexplorer.addon.worldgen.WorldgenEngine;
import me.seedexplorer.addon.worldgen.GeneratedTerrainHeightmap;
import me.seedexplorer.addon.seed.SeedManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.storage.loot.LootTable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Executes vanilla structure pieces against a small recording WorldGenLevel.
 *
 * <p>This is not a hand-written chest-position formula. Vanilla creates and
 * orients the real structure piece, moves it to the real terrain height, and
 * consumes the same per-decoration RNG used by ChunkGenerator. The recording
 * world captures every chest and the loot seed assigned by createChest().</p>
 */
public final class VanillaLootStructureSimulator {
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

    /** Exposes the Unsafe-built fake ServerLevel for the real-loot-table A/B probe. */
    public static Object fakeLevelForProbe() {
        return CaptureWorld.fakeServerLevel(0L);
    }

    /** Exposes the Unsafe-built fake ServerLevel for offline feature/structure proxies. */
    public static Object fakeLevelForSeed(long seed) {
        return CaptureWorld.fakeServerLevel(seed);
    }

    /** Generic simulation for any structure type (overworld default). */
    public static List<SimulatedContainer> simulate(long seed, ResourceKey<Structure> structureKey, int chunkX, int chunkZ) {
        return simulate(seed, 0, structureKey, chunkX, chunkZ);
    }

    /** Generic simulation with explicit dimension. */
    public static List<SimulatedContainer> simulate(long seed, int dimension,
                                                     ResourceKey<Structure> structureKey, int chunkX, int chunkZ) {
        return simulate(seed, dimension, structureKey, chunkX, chunkZ, decorationIndex(structureKey));
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

        CaptureWorld capture = new CaptureWorld(seed, dimension, terrainStartsFor(start));
        WorldGenLevel level = capture.proxy();
        Structure structure = start.getStructure();
        int step = structure.step().ordinal();
        int idx = explicitIndex;

        List<ChunkPos> chunks = start.getPieces().stream()
            .flatMap(piece -> piece.getBoundingBox().intersectingChunks())
            .distinct()
            .sorted(Comparator.comparingInt(ChunkPos::z).thenComparingInt(ChunkPos::x))
            .toList();
        boolean needsTerrain = needsTerrainPrefill(structureKey);
        for (ChunkPos chunk : chunks) {
            capture.prepareDecorationBefore(step, chunk,
                structureKey.equals(BuiltinStructures.DESERT_PYRAMID));
            if (needsTerrain) {
                capture.prefillTerrain(chunk, 0);
            }

            WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
            long decorationSeed = random.setDecorationSeed(seed, chunk.getMinBlockX(), chunk.getMinBlockZ());
            random.setFeatureSeed(decorationSeed, idx, step);

            int minY = capture.minBuildY;
            int maxY = capture.maxBuildY;
            BoundingBox writableArea = new BoundingBox(
                chunk.getMinBlockX(), minY, chunk.getMinBlockZ(),
                chunk.getMaxBlockX(), maxY, chunk.getMaxBlockZ());
            try {
                start.placeInChunk(level, null, WorldgenEngine.chunkGenerator(seed, dimension), random, writableArea, chunk);
            } catch (IllegalStateException e) {
                if (e.getMessage() != null && e.getMessage().contains("Tags not bound")) {
                    break;
                }
                throw e;
            }
        }

        probe("[done] " + structureKey.identifier() + " captured=" + capture.containers().size());
        return capture.containers();
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

    /** Places one chunk from an already-created or independently loaded start. */
    public static List<SimulatedContainer> simulateStartChunk(long seed,
                                                               StructureStart start,
                                                               int placementChunkX,
                                                               int placementChunkZ,
                                                               int explicitIndex) {
        if (start == null || !start.isValid()) return List.of();

        CaptureWorld capture = new CaptureWorld(seed, terrainStartsFor(start));
        ChunkPos chunk = new ChunkPos(placementChunkX, placementChunkZ);
        int step = start.getStructure().step().ordinal();
        capture.prepareDecorationBefore(step, chunk,
            start.getStructure().type() == StructureType.DESERT_PYRAMID);
        if (needsTerrainPrefillByType(start.getStructure().type())) {
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
            start.placeInChunk(capture.proxy(), null, WorldgenEngine.chunkGenerator(seed, 0),
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
                java.util.stream.Stream<BlockPos> posStream = java.util.stream.Stream.of(origin);
                for (PlacementModifier mod : mods) {
                    posStream = posStream.flatMap(p -> mod.getPositions(
                        new PlacementContext(level, gen, java.util.Optional.of(origFeature)), random, p));
                }
                java.util.List<BlockPos> positions = posStream.toList();
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
                boolean placedOk = origFeature.placeWithBiomeCheck(level, gen, random, origin);
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

    /** Backward-compatible alias for desert pyramid simulation. */
    public static List<SimulatedContainer> desertPyramid(long seed, int chunkX, int chunkZ) {
        return simulate(seed, BuiltinStructures.DESERT_PYRAMID, chunkX, chunkZ);
    }

    /** Overload with explicit decoration index (for brute-force testing). */
    public static List<SimulatedContainer> desertPyramid(long seed, int chunkX, int chunkZ, int explicitIndex) {
        return simulate(seed, 0, BuiltinStructures.DESERT_PYRAMID, chunkX, chunkZ, explicitIndex);
    }

    public static int decorationIndex(ResourceKey<Structure> key) {
        return decorationIndex(key, activeVersionProfile());
    }

    public static int decorationIndex(ResourceKey<Structure> key, BundledLootTableLoader.VersionProfile profile) {
        // Check version profile for overrides first (handles per-version
        // differences in the runtime STRUCTURE registry index mapping).
        String keyStr = key.identifier().toString();
        int override = profile.decorationIndexOverride(keyStr);
        if (override >= 0) return override;

        // Fall back to the lightweight STRUCTURE_TYPE registry order.
        // This is the stable fallback for structures like desert pyramids
        // whose type index is consistent across Paper versions.
        var lookup = WorldgenEngine.vanillaLookup().lookupOrThrow(Registries.STRUCTURE);
        Structure target = lookup.getOrThrow(key).value();
        var typeLookup = WorldgenEngine.vanillaLookup().lookupOrThrow(Registries.STRUCTURE_TYPE);
        StructureType<?> targetType = target.type();
        int typeIndex = 0;
        for (Holder<StructureType<?>> holder : typeLookup.listElements().toList()) {
            if (holder.value() == targetType) return typeIndex;
            typeIndex++;
        }
        throw new IllegalStateException("Structure type not found: " + targetType);
    }

    private static BundledLootTableLoader.VersionProfile activeVersionProfile() {
        try {
            SeedManager manager = SeedManager.get();
            if (manager != null) {
                String version = manager.getMcVersion();
                if (version != null && !version.isBlank()) {
                    return BundledLootTableLoader.VersionProfile.forVersion(version);
                }
            }
        } catch (Throwable ignored) {
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
        return structureKey.equals(BuiltinStructures.STRONGHOLD)
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

    private static boolean needsTerrainPrefillByType(StructureType<?> type) {
        return type == StructureType.STRONGHOLD
            || type == StructureType.BURIED_TREASURE
            || type == StructureType.MINESHAFT;
    }

    public record SimulatedContainer(int x, int y, int z, String lootTableId, long lootSeed) {
    }

    private static final class CaptureWorld implements InvocationHandler {
        private final long seed;
        private final int dimension;
        private final int minBuildY;
        private final int maxBuildY;
        private final int seaLevel;
        private final RandomSource worldRandom;
        private final GeneratedTerrainHeightmap terrainHeightmap;
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
        private final List<SimulatedContainer> capturedEntities = new ArrayList<>();
        private final Map<Heightmap.Types, Map<Long, Integer>> heights =
            new EnumMap<>(Heightmap.Types.class);
        private WorldGenLevel proxy;

        private CaptureWorld(long seed) {
            this(seed, 0, List.of());
        }

        private CaptureWorld(long seed, List<StructureStart> terrainStarts) {
            this(seed, 0, terrainStarts);
        }

        private CaptureWorld(long seed, int dimension, List<StructureStart> terrainStarts) {
            this.seed = seed;
            this.dimension = dimension;
            this.minBuildY = WorldgenEngine.minBuildHeight(seed, dimension);
            this.maxBuildY = minBuildY + WorldgenEngine.overallHeight(seed, dimension) - 1;
            this.seaLevel = dimension == -1 ? 32 : 63;
            this.worldRandom = RandomSource.create(seed ^ 0x5EEDC0DEL);
            this.terrainHeightmap = new GeneratedTerrainHeightmap(seed, dimension, terrainStarts);
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
                    result.add(new SimulatedContainer(
                        pos.getX(), pos.getY(), pos.getZ(), table.identifier().toString(), container.getLootTableSeed()));
                }
            }
            result.addAll(capturedEntities);
            result.sort(Comparator.comparingInt(SimulatedContainer::x)
                .thenComparingInt(SimulatedContainer::y)
                .thenComparingInt(SimulatedContainer::z));
            return List.copyOf(result);
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
                return blocks.getOrDefault(immutable((BlockPos) args[0]), Blocks.AIR.defaultBlockState()).isAir();
            }
            if (name.equals("getBlockState")) {
                return blocks.getOrDefault(immutable((BlockPos) args[0]), Blocks.AIR.defaultBlockState());
            }
            if (name.equals("getFluidState")) {
                BlockPos pos = immutable((BlockPos) args[0]);
                return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()).getFluidState();
            }
            if (name.equals("setBlock")) {
                BlockPos pos = immutable((BlockPos) args[0]);
                BlockState state = (BlockState) args[1];
                blocks.put(pos, state);
                BlockEntity entity = blockEntityFor(pos, state);
                if (entity != null) {
                    blockEntities.put(pos, entity);
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
                if (entity instanceof ContainerEntity container) {
                    ResourceKey<LootTable> table = container.getContainerLootTable();
                    if (table != null) {
                        BlockPos pos = entity.blockPosition();
                        capturedEntities.add(new SimulatedContainer(
                            pos.getX(), pos.getY(), pos.getZ(), table.identifier().toString(), container.getContainerLootTableSeed()));
                    }
                }
                return true;
            }
            if (name.equals("getLevel")) {
                return fakeServerLevel(seed);
            }
            if (name.equals("removeBlock") || name.equals("destroyBlock")) {
                BlockPos pos = immutable((BlockPos) args[0]);
                blocks.remove(pos);
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

        private int height(Heightmap.Types type, int x, int z) {
            long key = ((long) x << 32) ^ (z & 0xffffffffL);
            return heights.computeIfAbsent(type, ignored -> new HashMap<>()).computeIfAbsent(key,
                ignored -> terrainHeightmap.firstFreeHeight(type, x, z));
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
        private static Object fakeServerLevel(long seed) {
            Object cached = fakeServerLevel;
            if (cached != null && fakeServerLevelSeed == seed) return cached;
            synchronized (VanillaLootStructureSimulator.class) {
                if (fakeServerLevel != null && fakeServerLevelSeed == seed) return fakeServerLevel;
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
                    try {
                        WorldGenSettings settings =
                            WorldGenSettings.of(new WorldOptions(seed, true, false), WorldgenEngine.offlineRegistryAccess());
                        boolean wired = setFieldByName(unsafe, server, minecraftServerClass, "worldGenSettings", settings);
                        if (!wired) {
                            setField(unsafe, server, minecraftServerClass, WorldGenSettings.class, settings);
                        }
                    } catch (Throwable t) {
                        probe("[fakeServerLevel] worldGenSettings wiring failed: " + t);
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

                    // Level.registryAccess() is a plain field read. Running real vanilla
                    // loot tables (LootTable.getRandomItems) builds a LootContext that reads
                    // this to resolve enchantment/tag lookups, so wire it to the offline
                    // tag-bound registry access.
                    try {
                        Class<?> registryAccessClass = Class.forName("net.minecraft.core.RegistryAccess");
                        setField(unsafe, level, serverLevelClass, registryAccessClass,
                            WorldgenEngine.offlineRegistryAccess());
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

                        Object holder = holderClass
                            .getConstructor(net.minecraft.core.HolderLookup.Provider.class)
                            .newInstance(WorldgenEngine.offlineRegistryAccess());

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
                        Object reloadableResources = reloadableResourcesClass
                            .getDeclaredConstructor(resourceManagerClass, resourcesClass)
                            .newInstance(null, managers);

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
                    return level;
                } catch (Throwable t) {
                    probe("[fakeServerLevel] failed: " + t);
                    return null;
                }
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
                || block == Blocks.COPPER_CHEST || block == Blocks.EXPOSED_COPPER_CHEST
                || block == Blocks.WEATHERED_COPPER_CHEST || block == Blocks.OXIDIZED_COPPER_CHEST
                || block == Blocks.WAXED_COPPER_CHEST || block == Blocks.WAXED_EXPOSED_COPPER_CHEST
                || block == Blocks.WAXED_WEATHERED_COPPER_CHEST || block == Blocks.WAXED_OXIDIZED_COPPER_CHEST) {
                return new ChestBlockEntity(pos, state);
            }
            if (block == Blocks.BARREL) return new BarrelBlockEntity(pos, state);
            if (block == Blocks.DISPENSER) return new DispenserBlockEntity(pos, state);
            if (block == Blocks.DROPPER) return new DropperBlockEntity(pos, state);
            if (block == Blocks.HOPPER) return new HopperBlockEntity(pos, state);
            if (isShulkerBox(block)) return new ShulkerBoxBlockEntity(pos, state);
            return null;
        }

        private static boolean isShulkerBox(Block block) {
            return block == Blocks.SHULKER_BOX || block == Blocks.WHITE_SHULKER_BOX
                || block == Blocks.ORANGE_SHULKER_BOX || block == Blocks.MAGENTA_SHULKER_BOX
                || block == Blocks.LIGHT_BLUE_SHULKER_BOX || block == Blocks.YELLOW_SHULKER_BOX
                || block == Blocks.LIME_SHULKER_BOX || block == Blocks.PINK_SHULKER_BOX
                || block == Blocks.GRAY_SHULKER_BOX || block == Blocks.LIGHT_GRAY_SHULKER_BOX
                || block == Blocks.CYAN_SHULKER_BOX || block == Blocks.PURPLE_SHULKER_BOX
                || block == Blocks.BLUE_SHULKER_BOX || block == Blocks.BROWN_SHULKER_BOX
                || block == Blocks.GREEN_SHULKER_BOX || block == Blocks.RED_SHULKER_BOX
                || block == Blocks.BLACK_SHULKER_BOX;
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
