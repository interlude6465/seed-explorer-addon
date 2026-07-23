package me.seedexplorer.addon.preview;

import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
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
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.biome.Biome;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class StructurePreviewSimulator {
    private static final int MAX_CAPTURED_BLOCKS = 18000;
    private static volatile Field templatePalettesField;
    private static volatile Method singlePoolTemplateMethod;
    private static volatile Method templateLocationMethod;

    private StructurePreviewSimulator() {
    }

    public static StructurePreviewModel preview(GeneratedStructure structure, long seed) {
        ResourceKey<Structure> key = structureKey(structure, seed);
        if (key == null) {
            writeStateReport(structure, seed, "no_structure_key", null, null);
            return empty();
        }

        StructureStart start = null;
        ResourceKey<Structure> selectedKey = key;
        Throwable lastFailure = null;
        for (ResourceKey<Structure> candidate : structureKeyCandidates(structure, seed)) {
            try {
                StructureStart candidateStart = WorldgenEngine.generateSelectedStructureStart(seed, structure.type.dimension, candidate,
                    new ChunkPos(structure.startChunkX, structure.startChunkZ));
                if (candidateStart != null && candidateStart.isValid()) {
                    start = candidateStart;
                    selectedKey = candidate;
                    break;
                }
            } catch (Throwable throwable) {
                lastFailure = throwable;
            }
        }
        if (start == null) {
            writeStateReport(structure, seed, lastFailure == null ? "start_invalid" : "start_generation_failed", null, lastFailure);
            return empty();
        }

        StructurePreviewModel direct = extractDirectModel(start);
        if (!direct.isEmpty()) {
            return withPredictedContainers(direct, structure, seed, selectedKey);
        }

        PreviewWorld world = new PreviewWorld(seed, structure.type.dimension, terrainStartsFor(start));
        WorldGenLevel level = world.proxy();
        ChunkPos placementChunk = new ChunkPos(structure.startChunkX, structure.startChunkZ);
        world.prepareDecorationBefore(start.getStructure().step().ordinal(), placementChunk);
        world.prefillTerrain(placementChunk, 2);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long decorationSeed = random.setDecorationSeed(seed, structure.x, structure.z);
        random.setFeatureSeed(decorationSeed, 0, start.getStructure().step().ordinal());

        BoundingBox box = start.getBoundingBox();
        try {
            start.placeInChunk(level, null, WorldgenEngine.chunkGenerator(seed, structure.type.dimension),
                random, box, new ChunkPos(structure.startChunkX, structure.startChunkZ));
        } catch (Throwable throwable) {
            writeStateReport(structure, seed, "place_failed", start, throwable);
        }
        StructurePreviewModel model = world.toModel();
        if (model.isEmpty()) {
            writeStateReport(structure, seed, "empty_model", start, null);
        }
        return withPredictedContainers(model, structure, seed, selectedKey);
    }

    public static List<StructurePreviewModel.PreviewBlock> previewContainerBlocks(GeneratedStructure structure, long seed,
                                                                                  int maxContainers) {
        if (maxContainers <= 0) return List.of();
        StructureStart start = null;
        for (ResourceKey<Structure> candidate : structureKeyCandidates(structure, seed)) {
            try {
                StructureStart candidateStart = WorldgenEngine.generateSelectedStructureStart(seed, structure.type.dimension, candidate,
                    new ChunkPos(structure.startChunkX, structure.startChunkZ));
                if (candidateStart != null && candidateStart.isValid()) {
                    start = candidateStart;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        if (start == null) return List.of();

        StructureTemplateManager manager = WorldgenEngine.structureTemplateManager();
        List<StructurePreviewModel.PreviewBlock> containers = new ArrayList<>();
        for (var piece : start.getPieces()) {
            if (containers.size() >= maxContainers) break;
            if (piece instanceof TemplateStructurePiece templatePiece) {
                Optional<StructureTemplate> template = resolveTemplate(templatePiece, manager);
                if (template.isPresent()) {
                    addTemplateContainerBlocks(containers, template.get(), templatePiece.templatePosition(),
                        templatePiece.placeSettings(), maxContainers);
                }
                continue;
            }
            if (piece instanceof PoolElementStructurePiece poolPiece) {
                StructurePoolElement element = poolPiece.getElement();
                if (element instanceof SinglePoolElement single) {
                    Optional<StructureTemplate> template = resolveTemplate(single, manager);
                    if (template.isPresent()) {
                        StructurePlaceSettings settings = new StructurePlaceSettings()
                            .setRotation(poolPiece.getRotation())
                            .setBoundingBox(poolPiece.getBoundingBox())
                            .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING)
                            .setKnownShape(true)
                            .setIgnoreEntities(true);
                        addTemplateContainerBlocks(containers, template.get(), poolPiece.getPosition(), settings, maxContainers);
                    }
                }
            }
        }
        containers.sort(Comparator.comparingInt(StructurePreviewModel.PreviewBlock::y)
            .thenComparingInt(StructurePreviewModel.PreviewBlock::z)
            .thenComparingInt(StructurePreviewModel.PreviewBlock::x));
        return List.copyOf(containers);
    }

    private static List<ResourceKey<Structure>> structureKeyCandidates(GeneratedStructure structure, long seed) {
        ResourceKey<Structure> primary = structureKey(structure, seed);
        if (primary == null) return List.of();
        if (structure.type != StructureType.RUINED_PORTAL) return List.of(primary);
        List<ResourceKey<Structure>> keys = new ArrayList<>();
        keys.add(primary);
        for (ResourceKey<Structure> key : List.of(
            BuiltinStructures.RUINED_PORTAL_STANDARD,
            BuiltinStructures.RUINED_PORTAL_DESERT,
            BuiltinStructures.RUINED_PORTAL_JUNGLE,
            BuiltinStructures.RUINED_PORTAL_SWAMP,
            BuiltinStructures.RUINED_PORTAL_MOUNTAIN,
            BuiltinStructures.RUINED_PORTAL_OCEAN)) {
            if (!keys.contains(key)) keys.add(key);
        }
        return keys;
    }

    private static StructurePreviewModel withPredictedContainers(StructurePreviewModel model, GeneratedStructure structure,
                                                                 long seed, ResourceKey<Structure> key) {
        List<VanillaLootStructureSimulator.SimulatedContainer> containers;
        try {
            containers = VanillaLootStructureSimulator.simulate(seed, structure.type.dimension, key,
                structure.startChunkX, structure.startChunkZ);
        } catch (Throwable ignored) {
            return model;
        }
        if (containers.isEmpty()) return model;

        List<StructurePreviewModel.PreviewBlock> blocks = new ArrayList<>(model.blocks());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (StructurePreviewModel.PreviewBlock block : blocks) {
            seen.add(block.x() + "," + block.y() + "," + block.z());
        }
        for (VanillaLootStructureSimulator.SimulatedContainer container : containers) {
            String keyPos = container.x() + "," + container.y() + "," + container.z();
            if (seen.add(keyPos)) {
                blocks.add(new StructurePreviewModel.PreviewBlock(
                    container.x(), container.y(), container.z(), Blocks.CHEST.defaultBlockState()));
            }
        }
        return buildModel(blocks);
    }

    private static StructurePreviewModel extractDirectModel(StructureStart start) {
        StructureTemplateManager manager = WorldgenEngine.structureTemplateManager();
        List<StructurePreviewModel.PreviewBlock> blocks = new ArrayList<>();

        for (var piece : start.getPieces()) {
            if (piece instanceof TemplateStructurePiece templatePiece) {
                resolveTemplate(templatePiece, manager).ifPresent(template ->
                    addTemplateBlocks(blocks, template, templatePiece.templatePosition(), templatePiece.placeSettings()));
                continue;
            }
            if (piece instanceof PoolElementStructurePiece poolPiece) {
                StructurePoolElement element = poolPiece.getElement();
                if (element instanceof SinglePoolElement single) {
                    resolveTemplate(single, manager).ifPresent(template -> {
                        StructurePlaceSettings settings = new StructurePlaceSettings()
                            .setRotation(poolPiece.getRotation())
                            .setBoundingBox(poolPiece.getBoundingBox())
                            .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING)
                            .setKnownShape(true)
                            .setIgnoreEntities(true);
                        addTemplateBlocks(blocks, template, poolPiece.getPosition(), settings);
                    });
                }
            }
        }

        if (blocks.isEmpty()) {
            return empty();
        }
        return buildModel(blocks);
    }

    private static Optional<StructureTemplate> resolveTemplate(SinglePoolElement element, StructureTemplateManager manager) {
        try {
            Method method = singlePoolTemplateMethod;
            if (method == null) {
                method = SinglePoolElement.class.getDeclaredMethod("getTemplate", StructureTemplateManager.class);
                method.setAccessible(true);
                singlePoolTemplateMethod = method;
            }
            Object value = method.invoke(element, manager);
            return value instanceof StructureTemplate template ? Optional.of(template) : Optional.empty();
        } catch (Throwable ignored) {
            try {
                return manager.get(element.getTemplateLocation());
            } catch (Throwable innerIgnored) {
                return Optional.empty();
            }
        }
    }

    private static Optional<StructureTemplate> resolveTemplate(TemplateStructurePiece piece, StructureTemplateManager manager) {
        try {
            Identifier id = templateLocation(piece);
            if (id != null) {
                Optional<StructureTemplate> loaded = manager.get(id);
                if (loaded.isPresent()) return loaded;
            }
        } catch (Throwable ignored) {
        }
        return Optional.ofNullable(piece.template());
    }

    private static Identifier templateLocation(TemplateStructurePiece piece) {
        try {
            Method method = templateLocationMethod;
            if (method == null) {
                method = TemplateStructurePiece.class.getDeclaredMethod("makeTemplateLocation");
                method.setAccessible(true);
                templateLocationMethod = method;
            }
            Object value = method.invoke(piece);
            return value instanceof Identifier id ? id : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addTemplateBlocks(List<StructurePreviewModel.PreviewBlock> into, StructureTemplate template,
                                          BlockPos basePos, StructurePlaceSettings settings) {
        try {
            List<StructureTemplate.Palette> palettes = templatePalettes(template);
            if (palettes.isEmpty()) return;
            StructureTemplate.Palette palette = settings.getRandomPalette(palettes, basePos);
            if (palette == null) return;

            // Match vanilla StructureTemplate.calculateRelativePosition exactly:
            //   worldPos = basePos + transform(info.pos, mirror, rotation, settings.getRotationPivot())
            // The previous code used getZeroPositionWithTransform (size-based corner compensation) plus a
            // ZERO pivot, which diverges from vanilla for any rotated piece and shifted chests by
            // (templateSize - 1) blocks. Jigsaw pool elements (bastion/fortress/end city) and the nether
            // ruined portal are randomly rotated, so they were consistently off by ~5 blocks.
            for (var info : palette.blocks()) {
                BlockState state = info.state();
                if (state == null || state.isAir()) continue;
                BlockPos rel = StructureTemplate.transform(info.pos(), settings.getMirror(), settings.getRotation(), settings.getRotationPivot());
                BlockPos pos = basePos.offset(rel);
                into.add(new StructurePreviewModel.PreviewBlock(pos.getX(), pos.getY(), pos.getZ(), state));
                if (into.size() > MAX_CAPTURED_BLOCKS) return;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void addTemplateContainerBlocks(List<StructurePreviewModel.PreviewBlock> into, StructureTemplate template,
                                                   BlockPos basePos, StructurePlaceSettings settings, int maxContainers) {
        try {
            List<StructureTemplate.Palette> palettes = templatePalettes(template);
            if (palettes.isEmpty()) return;
            StructureTemplate.Palette palette = settings.getRandomPalette(palettes, basePos);
            if (palette == null) return;

            for (var info : palette.blocks()) {
                if (into.size() >= maxContainers) return;
                BlockState state = info.state();
                if (!isContainerState(state)) continue;
                BlockPos rel = StructureTemplate.transform(info.pos(), settings.getMirror(), settings.getRotation(), settings.getRotationPivot());
                BlockPos pos = basePos.offset(rel);
                into.add(new StructurePreviewModel.PreviewBlock(pos.getX(), pos.getY(), pos.getZ(), state));
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isContainerState(BlockState state) {
        if (state == null || state.isAir()) return false;
        return state.is(Blocks.CHEST)
            || state.is(Blocks.BARREL)
            || state.is(Blocks.DISPENSER)
            || state.is(Blocks.DROPPER)
            || state.is(Blocks.HOPPER)
            || net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().contains("shulker_box");
    }

    @SuppressWarnings("unchecked")
    private static List<StructureTemplate.Palette> templatePalettes(StructureTemplate template) throws ReflectiveOperationException {
        Field field = templatePalettesField;
        if (field == null) {
            field = StructureTemplate.class.getDeclaredField("palettes");
            field.setAccessible(true);
            templatePalettesField = field;
        }
        Object value = field.get(template);
        return value instanceof List<?> list ? (List<StructureTemplate.Palette>) list : List.of();
    }

    private static StructurePreviewModel buildModel(List<StructurePreviewModel.PreviewBlock> blocks) {
        blocks.sort(Comparator.comparingInt(StructurePreviewModel.PreviewBlock::y)
            .thenComparingInt(StructurePreviewModel.PreviewBlock::z)
            .thenComparingInt(StructurePreviewModel.PreviewBlock::x));
        boolean truncated = false;
        if (blocks.size() > MAX_CAPTURED_BLOCKS) {
            truncated = true;
            blocks = new ArrayList<>(blocks.subList(0, MAX_CAPTURED_BLOCKS));
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var block : blocks) {
            minX = Math.min(minX, block.x());
            minY = Math.min(minY, block.y());
            minZ = Math.min(minZ, block.z());
            maxX = Math.max(maxX, block.x());
            maxY = Math.max(maxY, block.y());
            maxZ = Math.max(maxZ, block.z());
        }
        return new StructurePreviewModel(List.copyOf(blocks), minX, minY, minZ, maxX, maxY, maxZ, truncated);
    }

    private static StructurePreviewModel empty() {
        return new StructurePreviewModel(List.of(), 0, 0, 0, 0, 0, 0, false);
    }

    private static ResourceKey<Structure> structureKey(GeneratedStructure structure, long seed) {
        StructureType type = structure.type;
        return switch (type) {
            case END_CITY -> BuiltinStructures.END_CITY;
            case VILLAGE -> villageKeyForBiome(seed, structure);
            case DESERT_PYRAMID -> BuiltinStructures.DESERT_PYRAMID;
            case JUNGLE_TEMPLE -> BuiltinStructures.JUNGLE_TEMPLE;
            case WITCH_HUT -> BuiltinStructures.SWAMP_HUT;
            case IGLOO -> BuiltinStructures.IGLOO;
            case OUTPOST -> BuiltinStructures.PILLAGER_OUTPOST;
            case MONUMENT -> BuiltinStructures.OCEAN_MONUMENT;
            case MANSION -> BuiltinStructures.WOODLAND_MANSION;
            case ANCIENT_CITY -> BuiltinStructures.ANCIENT_CITY;
            case TRIAL_CHAMBER -> BuiltinStructures.TRIAL_CHAMBERS;
            case TRAIL_RUINS -> BuiltinStructures.TRAIL_RUINS;
            case STRONGHOLD -> BuiltinStructures.STRONGHOLD;
            case RUINED_PORTAL -> ruinedPortalKeyForVariant(seed, structure);
            case SHIPWRECK -> variantStructureKey(structure, shipwreckKeyForBiome(seed, structure),
                BuiltinStructures.SHIPWRECK, BuiltinStructures.SHIPWRECK_BEACHED,
                "minecraft:shipwreck_beached", "minecraft:shipwreck");
            case OCEAN_RUIN -> variantStructureKey(structure, oceanRuinKeyForBiome(seed, structure),
                BuiltinStructures.OCEAN_RUIN_COLD, BuiltinStructures.OCEAN_RUIN_WARM,
                "minecraft:ocean_ruin_warm", "minecraft:ocean_ruin_cold");
            case MINESHAFT -> BuiltinStructures.MINESHAFT;
            case TREASURE -> BuiltinStructures.BURIED_TREASURE;
            case FORTRESS -> BuiltinStructures.FORTRESS;
            case BASTION -> BuiltinStructures.BASTION_REMNANT;
            case NETHER_RUINED_PORTAL -> BuiltinStructures.RUINED_PORTAL_NETHER;
            case NETHER_FOSSIL -> BuiltinStructures.NETHER_FOSSIL;
            default -> null;
        };
    }

    private static ResourceKey<Structure> villageKeyForBiome(long seed, GeneratedStructure structure) {
        String biomeId = WorldgenEngine.getBiome(seed, structure.type.dimension, structure.x, 64, structure.z).id();
        return switch (biomeId) {
            case "minecraft:desert" -> BuiltinStructures.VILLAGE_DESERT;
            case "minecraft:savanna" -> BuiltinStructures.VILLAGE_SAVANNA;
            case "minecraft:snowy_plains" -> BuiltinStructures.VILLAGE_SNOWY;
            case "minecraft:taiga" -> BuiltinStructures.VILLAGE_TAIGA;
            default -> BuiltinStructures.VILLAGE_PLAINS;
        };
    }

    private static ResourceKey<Structure> variantStructureKey(GeneratedStructure structure,
                                                               ResourceKey<Structure> inferred,
                                                               ResourceKey<Structure> fallback,
                                                               ResourceKey<Structure> alternate,
                                                               String alternateId,
                                                               String fallbackId) {
        if (structure.variant.equals(alternateId)) return alternate;
        if (structure.variant.equals(fallbackId)) return fallback;
        return inferred;
    }

    private static ResourceKey<Structure> ruinedPortalKeyForVariant(long seed, GeneratedStructure structure) {
        return switch (structure.variant) {
            case "minecraft:ruined_portal_desert" -> BuiltinStructures.RUINED_PORTAL_DESERT;
            case "minecraft:ruined_portal_jungle" -> BuiltinStructures.RUINED_PORTAL_JUNGLE;
            case "minecraft:ruined_portal_swamp" -> BuiltinStructures.RUINED_PORTAL_SWAMP;
            case "minecraft:ruined_portal_mountain" -> BuiltinStructures.RUINED_PORTAL_MOUNTAIN;
            case "minecraft:ruined_portal_ocean" -> BuiltinStructures.RUINED_PORTAL_OCEAN;
            case "minecraft:ruined_portal" -> BuiltinStructures.RUINED_PORTAL_STANDARD;
            default -> ruinedPortalKeyForBiome(seed, structure, false);
        };
    }

    private static ResourceKey<Structure> ruinedPortalKeyForBiome(long seed, GeneratedStructure structure, boolean nether) {
        String biomeId = WorldgenEngine.getBiome(seed, nether ? -1 : structure.type.dimension, structure.x, 64, structure.z).id();
        if (nether) return BuiltinStructures.RUINED_PORTAL_NETHER;
        return switch (biomeId) {
            case "minecraft:desert" -> BuiltinStructures.RUINED_PORTAL_DESERT;
            case "minecraft:jungle", "minecraft:bamboo_jungle" -> BuiltinStructures.RUINED_PORTAL_JUNGLE;
            case "minecraft:swamp", "minecraft:mangrove_swamp" -> BuiltinStructures.RUINED_PORTAL_SWAMP;
            case "minecraft:badlands", "minecraft:wooded_badlands", "minecraft:eroded_badlands",
                 "minecraft:windswept_hills", "minecraft:windswept_gravelly_hills",
                 "minecraft:windswept_forest", "minecraft:stony_peaks", "minecraft:stony_shore" ->
                BuiltinStructures.RUINED_PORTAL_MOUNTAIN;
            case "minecraft:ocean", "minecraft:deep_ocean", "minecraft:cold_ocean", "minecraft:deep_cold_ocean",
                 "minecraft:frozen_ocean", "minecraft:deep_frozen_ocean", "minecraft:lukewarm_ocean",
                 "minecraft:deep_lukewarm_ocean", "minecraft:warm_ocean" -> BuiltinStructures.RUINED_PORTAL_OCEAN;
            default -> BuiltinStructures.RUINED_PORTAL_STANDARD;
        };
    }

    private static ResourceKey<Structure> shipwreckKeyForBiome(long seed, GeneratedStructure structure) {
        String biomeId = WorldgenEngine.getBiome(seed, structure.type.dimension, structure.x, 64, structure.z).id();
        return biomeId.equals("minecraft:beach") || biomeId.equals("minecraft:snowy_beach")
            ? BuiltinStructures.SHIPWRECK_BEACHED
            : BuiltinStructures.SHIPWRECK;
    }

    private static ResourceKey<Structure> oceanRuinKeyForBiome(long seed, GeneratedStructure structure) {
        String biomeId = WorldgenEngine.getBiome(seed, structure.type.dimension, structure.x, 64, structure.z).id();
        return switch (biomeId) {
            case "minecraft:warm_ocean", "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean" -> BuiltinStructures.OCEAN_RUIN_WARM;
            default -> BuiltinStructures.OCEAN_RUIN_COLD;
        };
    }

    private static List<StructureStart> terrainStartsFor(StructureStart start) {
        return start.getStructure().type() == net.minecraft.world.level.levelgen.structure.StructureType.STRONGHOLD
            ? List.of(start)
            : List.of();
    }

    private static void writeStateReport(GeneratedStructure structure, long seed, String reason, StructureStart start, Throwable throwable) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of("build", "reports", "structure-preview-debug.txt");
            java.nio.file.Files.createDirectories(path.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("reason=").append(reason).append('\n');
            sb.append("structure=").append(structure.displayName()).append('\n');
            sb.append("seed=").append(seed).append('\n');
            sb.append("chunk=").append(structure.startChunkX).append(',').append(structure.startChunkZ).append('\n');
            sb.append("pieces=").append(start == null ? -1 : start.getPieces().size()).append('\n');
            if (throwable != null) {
                sb.append("throwable=").append(throwable.getClass().getName()).append(':')
                    .append(throwable.getMessage()).append('\n');
                Throwable cause = throwable.getCause();
                int depth = 0;
                while (cause != null && depth++ < 4) {
                    sb.append("cause=").append(cause.getClass().getName()).append(':')
                        .append(cause.getMessage()).append('\n');
                    cause = cause.getCause();
                }
            }
            java.nio.file.Files.writeString(path, sb.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    private static final class PreviewWorld implements InvocationHandler {
        private final long seed;
        private final int dimension;
        private final int minBuildY;
        private final int maxBuildY;
        private final int seaLevel;
        private final RandomSource worldRandom;
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private final Map<BlockPos, BlockState> placedBlocks = new HashMap<>();
        private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
        private final Map<Heightmap.Types, Map<Long, Integer>> heights = new EnumMap<>(Heightmap.Types.class);
        private final me.seedexplorer.addon.worldgen.GeneratedTerrainHeightmap terrainHeightmap;
        private final List<StructurePreviewModel.PreviewBlock> previewBlocks = new ArrayList<>();
        private boolean truncated;
        private WorldGenLevel proxy;

        private PreviewWorld(long seed, int dimension, List<StructureStart> terrainStarts) {
            this.seed = seed;
            this.dimension = dimension;
            this.minBuildY = WorldgenEngine.minBuildHeight(seed, dimension);
            this.maxBuildY = minBuildY + WorldgenEngine.overallHeight(seed, dimension) - 1;
            this.seaLevel = dimension == -1 ? 32 : 63;
            this.worldRandom = RandomSource.create(seed ^ 0x5EEDC0DEL);
            this.terrainHeightmap = new me.seedexplorer.addon.worldgen.GeneratedTerrainHeightmap(seed, dimension, terrainStarts);
        }

        private WorldGenLevel proxy() {
            if (proxy == null) {
                proxy = (WorldGenLevel) Proxy.newProxyInstance(
                    WorldGenLevel.class.getClassLoader(),
                    new Class<?>[]{WorldGenLevel.class},
                    this);
            }
            return proxy;
        }

        private StructurePreviewModel toModel() {
            for (Map.Entry<BlockPos, BlockState> entry : placedBlocks.entrySet()) {
                BlockPos pos = entry.getKey();
                previewBlocks.add(new StructurePreviewModel.PreviewBlock(
                    pos.getX(), pos.getY(), pos.getZ(), entry.getValue()));
            }
            previewBlocks.sort(Comparator.comparingInt(StructurePreviewModel.PreviewBlock::y)
                .thenComparingInt(StructurePreviewModel.PreviewBlock::z)
                .thenComparingInt(StructurePreviewModel.PreviewBlock::x));
            if (previewBlocks.size() > MAX_CAPTURED_BLOCKS) {
                truncated = true;
                previewBlocks.subList(MAX_CAPTURED_BLOCKS, previewBlocks.size()).clear();
            }
            if (previewBlocks.isEmpty()) return empty();
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (var block : previewBlocks) {
                minX = Math.min(minX, block.x());
                minY = Math.min(minY, block.y());
                minZ = Math.min(minZ, block.z());
                maxX = Math.max(maxX, block.x());
                maxY = Math.max(maxY, block.y());
                maxZ = Math.max(maxZ, block.z());
            }
            return new StructurePreviewModel(List.copyOf(previewBlocks), minX, minY, minZ, maxX, maxY, maxZ, truncated);
        }

        private void prepareDecorationBefore(int structureStep, ChunkPos chunk) {
            terrainHeightmap.applyDecorationBefore(chunk, structureStep);
        }

        private void prefillTerrain(ChunkPos centerChunk, int margin) {
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
                        if (!state.isAir()) blocks.put(pos, state);
                    }
                }
            }
        }

        @Override
        public Object invoke(Object proxyObject, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("getSeed")) return seed;
            if (name.equals("getRandom")) return worldRandom;
            if (name.equals("registryAccess")) return WorldgenEngine.offlineRegistryAccess();
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
            if (name.equals("isEmptyBlock")) return blocks.getOrDefault(immutable((BlockPos) args[0]), Blocks.AIR.defaultBlockState()).isAir();
            if (name.equals("getBlockState")) return blocks.getOrDefault(immutable((BlockPos) args[0]), Blocks.AIR.defaultBlockState());
            if (name.equals("getFluidState")) return blocks.getOrDefault(immutable((BlockPos) args[0]), Blocks.AIR.defaultBlockState()).getFluidState();
            if (name.equals("setBlock")) {
                BlockPos pos = immutable((BlockPos) args[0]);
                BlockState state = (BlockState) args[1];
                blocks.put(pos, state);
                if (state == null || state.isAir()) {
                    placedBlocks.remove(pos);
                } else {
                    placedBlocks.put(pos, state);
                }
                BlockEntity entity = blockEntityFor(pos, state);
                if (entity != null) blockEntities.put(pos, entity);
                return true;
            }
            if (name.equals("getBlockEntity")) return blockEntities.get(immutable((BlockPos) args[0]));
            if (name.equals("addFreshEntity") || name.equals("addEntity")) return true;
            if (name.equals("removeBlock") || name.equals("destroyBlock")) {
                BlockPos pos = immutable((BlockPos) args[0]);
                blocks.remove(pos);
                placedBlocks.remove(pos);
                blockEntities.remove(pos);
                return true;
            }
            if (name.equals("getChunk")) {
                if (args != null && args.length >= 2 && args[0] instanceof Integer cx && args[1] instanceof Integer cz) {
                    return terrainHeightmap.getChunk(new ChunkPos(cx, cz));
                }
            }
            if (name.equals("hasChunk") || name.equals("hasChunkAt") || name.equals("ensureCanWrite")) return true;
            if (name.equals("getBiome") && args != null && args.length == 1 && args[0] instanceof BlockPos pos) {
                return WorldgenEngine.getBiomeHolder(seed, dimension, pos.getX(), pos.getY(), pos.getZ());
            }
            if (name.equals("getUncachedNoiseBiome")) {
                int quartX = (Integer) args[0];
                int quartY = (Integer) args[1];
                int quartZ = (Integer) args[2];
                return WorldgenEngine.getBiomeHolder(seed, dimension, quartX * 4, quartY * 4, quartZ * 4);
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

        private static BlockEntity blockEntityFor(BlockPos pos, BlockState state) {
            Block block = state.getBlock();
            if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) return new ChestBlockEntity(pos, state);
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
