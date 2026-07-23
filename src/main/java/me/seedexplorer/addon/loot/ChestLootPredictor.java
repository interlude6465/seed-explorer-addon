package me.seedexplorer.addon.loot;

import me.seedexplorer.addon.debug.PredictionDebugLogger;
import me.seedexplorer.addon.preview.StructurePreviewModel;
import me.seedexplorer.addon.preview.StructurePreviewSimulator;
import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChestLootPredictor {
    private static final int DEFAULT_PREDICTIONS_PER_CHEST = 64;
    private static final int MAX_STRUCTURE_CACHE_ENTRIES = 4096;
    private static volatile String loadedVersion = "";
    private static final Map<String, List<ChestLootOutput>> STRUCTURE_CACHE = new ConcurrentHashMap<>();

    /** Structures with chest placement, loot seeds and contents verified against a 26.1.2 Paper oracle. */
    private static final Set<StructureType> VALIDATED_STRUCTURES =
        EnumSet.of(StructureType.DESERT_PYRAMID, StructureType.STRONGHOLD, StructureType.SHIPWRECK,
            StructureType.OUTPOST, StructureType.JUNGLE_TEMPLE, StructureType.TREASURE,
            StructureType.TRIAL_CHAMBER, StructureType.RUINED_PORTAL, StructureType.OCEAN_RUIN,
            StructureType.IGLOO, StructureType.ANCIENT_CITY, StructureType.TRAIL_RUINS,
            StructureType.MANSION, StructureType.END_CITY, StructureType.FORTRESS,
            StructureType.BASTION, StructureType.MINESHAFT);
    private static final Set<String> VALIDATED_LOOT_TABLES = Set.of(
        "minecraft:chests/desert_pyramid",
        "minecraft:chests/stronghold_corridor",
        "minecraft:chests/stronghold_crossing",
        "minecraft:chests/stronghold_library",
        "minecraft:chests/stronghold_storeroom",
        "minecraft:chests/shipwreck_treasure",
        "minecraft:chests/shipwreck_supply",
        "minecraft:chests/shipwreck_map",
        "minecraft:chests/pillager_outpost",
        "minecraft:chests/jungle_temple",
        "minecraft:chests/buried_treasure",
        "minecraft:chests/abandoned_mineshaft",
        "minecraft:chests/jungle_temple_dispenser",
        "minecraft:chests/trial_chambers/corridor",
        "minecraft:chests/trial_chambers/reward",
        "minecraft:chests/trial_chambers/reward_common",
        "minecraft:chests/trial_chambers/reward_rare",
        "minecraft:chests/trial_chambers/reward_unique",
        "minecraft:chests/trial_chambers/reward_ominous",
        "minecraft:chests/trial_chambers/reward_ominous_common",
        "minecraft:chests/trial_chambers/reward_ominous_unique",
        "minecraft:chests/trial_chambers/entrance",
        "minecraft:chests/trial_chambers/supply",
        "minecraft:chests/trial_chambers/intersection",
        "minecraft:chests/trial_chambers/intersection_barrel",
        "minecraft:chests/trial_chambers/reward_ominous_rare",
        "minecraft:dispensers/trial_chambers/chamber",
        "minecraft:dispensers/trial_chambers/corridor",
        "minecraft:dispensers/trial_chambers/water",
        "minecraft:pots/trial_chambers/corridor",
        "minecraft:chests/igloo_chest",
        "minecraft:chests/ruined_portal",
        "minecraft:chests/underwater_ruin_small",
        "minecraft:chests/underwater_ruin_big",
        // Jigsaw structures — now placed exactly by the offline simulator after the
        // component-binding / fake-ServerLevel fixes (2026-07-16 session 2).
        "minecraft:chests/end_city_treasure",
        "minecraft:chests/nether_bridge",
        "minecraft:chests/bastion_treasure",
        "minecraft:chests/bastion_other",
        "minecraft:chests/bastion_hoglin_stable",
        "minecraft:chests/bastion_bridge",
        "minecraft:chests/ancient_city",
        "minecraft:chests/ancient_city_ice_box",
        "minecraft:chests/woodland_mansion");

    /**
     * RESEARCH-ONLY: VanillaLootStructureSimulator.simulate() accepts a dimension
     * parameter for Nether/End structures. A bastion Paper oracle exists, but
     * offline jigsaw placement parity is not established, so Nether structures
     * remain gated out of product predictions.
     */
    public static List<ChestLootOutput> predictForStructure(GeneratedStructure structure) {
        return predictForStructure(structure, DEFAULT_PREDICTIONS_PER_CHEST);
    }

    public static List<ChestLootOutput> predictForStructure(GeneratedStructure structure, int maxItems) {
        long worldSeed = SeedManager.get().getWorldSeed();
        if (worldSeed == 0) return List.of();
        if (!VALIDATED_STRUCTURES.contains(structure.type)) return List.of();
        ensureLootTablesLoaded();

        ResourceKey<Structure> structureKey = structureKeyFor(worldSeed, structure);
        if (structureKey == null) return List.of();
        String cacheKey = structureCacheKey(worldSeed, loadedVersion, structure, structureKey, maxItems);
        List<ChestLootOutput> cached = STRUCTURE_CACHE.get(cacheKey);
        if (cached != null) {
            PredictionDebugLogger.structurePrediction(worldSeed, structure.type.dimension, structure, "cache", cached);
            PredictedChestOverlay.get().remember(cached);
            return cached;
        }

        // When the vanilla simulator can place the structure it produces the COMPLETE, exact
        // chest set (real loot seeds). In that case trust it exclusively — do NOT bolt on
        // preview-derived containers, which are reconstructed from templates and can add phantom
        // chests (e.g. shipwreck was getting 3 real chests plus 3 fake copies at y+32).
        // The preview fallback is only for structures the simulator cannot place at all
        // (jigsaw: fortress / ancient city), where it returns empty.
        // Note: bastion and mansion were previously listed here, but after the 2026-07-18
        // decoration-index and enchant_randomly fixes the simulator returns exact results for
        // both — they no longer fall through to the fabricated-seed fallback.
        List<ChestLootOutput> exact = predictValidatedStructure(worldSeed, structure.type, structure.type.dimension, structureKey,
            structure.startChunkX, structure.startChunkZ, maxItems);

        List<ChestLootOutput> out;
        String source;
        if (!exact.isEmpty()) {
            out = exact;
            source = "vanilla_simulator";
        } else {
            out = predictPreviewFallback(worldSeed, structure, maxItems);
            if (out.isEmpty()) {
                source = structureUsesTemplateFallback(structure.type) ? "empty_no_template_containers" : "empty";
            } else {
                source = structureUsesTemplateFallback(structure.type) ? "template_fallback" : "preview_fallback";
            }
        }

        out = List.copyOf(out);
        if (STRUCTURE_CACHE.size() > MAX_STRUCTURE_CACHE_ENTRIES) STRUCTURE_CACHE.clear();
        STRUCTURE_CACHE.put(cacheKey, out);
        PredictionDebugLogger.structurePrediction(worldSeed, structure.type.dimension, structure, source, out);
        PredictedChestOverlay.get().remember(out);
        return out;
    }

    public static List<ChestLootOutput> predictTemplateContainerSample(GeneratedStructure structure, int maxChests, int maxItems) {
        long worldSeed = SeedManager.get().getWorldSeed();
        if (worldSeed == 0 || maxChests <= 0) return List.of();
        if (!VALIDATED_STRUCTURES.contains(structure.type)) return List.of();
        ensureLootTablesLoaded();

        String lootTable = fallbackLootTable(structure.type);
        LootTableDef table = VanillaLootTables.get(lootTable);
        List<StructurePreviewModel.PreviewBlock> containers =
            StructurePreviewSimulator.previewContainerBlocks(structure, worldSeed, maxChests);
        List<ChestLootOutput> results = new ArrayList<>();
        for (StructurePreviewModel.PreviewBlock block : containers) {
            long lootSeed = deterministicFallbackLootSeed(worldSeed, block.x(), block.y(), block.z(), lootTable);
            List<ItemLoot> predicted = table == null ? List.of() : LootTableSimulator.simulate(table, lootSeed, maxItems);
            results.add(new ChestLootOutput(block.x(), block.y(), block.z(), lootTable, predicted, lootSeed, false));
        }
        List<ChestLootOutput> out = List.copyOf(results);
        PredictionDebugLogger.structurePrediction(worldSeed, structure.type.dimension, structure, "template_container_sample", out);
        PredictedChestOverlay.get().remember(out);
        return out;
    }

    private static String structureCacheKey(long seed, String version, GeneratedStructure structure,
                                            ResourceKey<Structure> structureKey, int maxItems) {
        return seed + "|" + version + "|" + maxItems + "|" + structure.type.dimension
            + "|" + structure.type + "|" + structureKey.identifier()
            + "|" + structure.startChunkX + "," + structure.startChunkZ
            + "|" + structure.variant + "|" + structure.hasShip;
    }

    private static boolean structureUsesTemplateFallback(StructureType type) {
        // Structures that fall back to a fabricated-seed preview when the simulator returns
        // empty. Bastion and Mansion were removed from this list 2026-07-18 after the
        // decoration-index + enchant_randomly fixes made their simulator results exact.
        return type == StructureType.END_CITY
            || type == StructureType.FORTRESS
            || type == StructureType.ANCIENT_CITY;
    }

    private static List<ChestLootOutput> predictValidatedStructure(long worldSeed, StructureType type, int dimension,
                                                                    ResourceKey<Structure> structureKey,
                                                                    int chunkX, int chunkZ, int maxItems) {
        List<VanillaLootStructureSimulator.SimulatedContainer> chests =
            VanillaLootStructureSimulator.simulate(worldSeed, dimension, structureKey, chunkX, chunkZ);

        List<ChestLootOutput> results = new ArrayList<>();
        for (VanillaLootStructureSimulator.SimulatedContainer chest : chests) {
            LootTableDef table = VanillaLootTables.get(chest.lootTableId());
            List<ItemLoot> predicted = table == null
                ? List.of()
                : LootTableSimulator.simulate(table, chest.lootSeed(), maxItems);
            results.add(new ChestLootOutput(
                chest.x(), chest.y(), chest.z(), chest.lootTableId(), predicted, chest.lootSeed(),
                type != StructureType.FORTRESS));
        }
        return List.copyOf(results);
    }

    private static ResourceKey<Structure> structureKeyFor(long seed, GeneratedStructure structure) {
        return switch (structure.type) {
            case DESERT_PYRAMID -> BuiltinStructures.DESERT_PYRAMID;
            case STRONGHOLD -> BuiltinStructures.STRONGHOLD;
            case MINESHAFT -> BuiltinStructures.MINESHAFT;
            case SHIPWRECK -> variantStructureKey(structure, shipwreckKeyForBiome(seed, structure),
                BuiltinStructures.SHIPWRECK, BuiltinStructures.SHIPWRECK_BEACHED,
                "minecraft:shipwreck_beached", "minecraft:shipwreck");
            case TREASURE -> BuiltinStructures.BURIED_TREASURE;
            case JUNGLE_TEMPLE -> BuiltinStructures.JUNGLE_TEMPLE;
            case IGLOO -> BuiltinStructures.IGLOO;
            case ANCIENT_CITY -> BuiltinStructures.ANCIENT_CITY;
            case TRAIL_RUINS -> BuiltinStructures.TRAIL_RUINS;
            case OUTPOST -> BuiltinStructures.PILLAGER_OUTPOST;
            case MANSION -> BuiltinStructures.WOODLAND_MANSION;
            case END_CITY -> BuiltinStructures.END_CITY;
            case FORTRESS -> BuiltinStructures.FORTRESS;
            case BASTION -> BuiltinStructures.BASTION_REMNANT;
            case TRIAL_CHAMBER -> BuiltinStructures.TRIAL_CHAMBERS;
            case RUINED_PORTAL -> ruinedPortalKeyForVariant(seed, structure);
            case NETHER_RUINED_PORTAL -> BuiltinStructures.RUINED_PORTAL_NETHER;
            case OCEAN_RUIN -> variantStructureKey(structure, oceanRuinKeyForBiome(seed, structure),
                BuiltinStructures.OCEAN_RUIN_COLD, BuiltinStructures.OCEAN_RUIN_WARM,
                "minecraft:ocean_ruin_warm", "minecraft:ocean_ruin_cold");
            default -> null;
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

    private static ResourceKey<Structure> shipwreckKeyForBiome(long seed, GeneratedStructure structure) {
        String biomeId = WorldgenEngine.getBiome(seed, structure.type.dimension, structure.x, 64, structure.z).id();
        return biomeId.equals("minecraft:beach") || biomeId.equals("minecraft:snowy_beach")
            ? BuiltinStructures.SHIPWRECK_BEACHED
            : BuiltinStructures.SHIPWRECK;
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

    private static ResourceKey<Structure> oceanRuinKeyForBiome(long seed, GeneratedStructure structure) {
        String biomeId = WorldgenEngine.getBiome(seed, structure.type.dimension, structure.x, 64, structure.z).id();
        return switch (biomeId) {
            case "minecraft:warm_ocean", "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean" -> BuiltinStructures.OCEAN_RUIN_WARM;
            default -> BuiltinStructures.OCEAN_RUIN_COLD;
        };
    }

    private static List<ChestLootOutput> predictPreviewFallback(long worldSeed, GeneratedStructure structure, int maxItems) {
        StructurePreviewModel preview;
        try {
            preview = StructurePreviewSimulator.preview(structure, worldSeed);
        } catch (Throwable ignored) {
            return List.of();
        }
        if (preview == null || preview.isEmpty()) return List.of();

        String lootTable = fallbackLootTable(structure.type);
        LootTableDef table = VanillaLootTables.get(lootTable);
        List<ChestLootOutput> results = new ArrayList<>();
        for (StructurePreviewModel.PreviewBlock block : preview.blocks()) {
            if (!isContainerBlock(block)) continue;
            long lootSeed = deterministicFallbackLootSeed(worldSeed, block.x(), block.y(), block.z(), lootTable);
            List<ItemLoot> predicted = table == null ? List.of() : LootTableSimulator.simulate(table, lootSeed, maxItems);
            // Position is now vanilla-accurate, but the loot seed is fabricated (offline jigsaw
            // placement can't recover the real vanilla loot seed), so mark contents non-exact.
            results.add(new ChestLootOutput(block.x(), block.y(), block.z(), lootTable, predicted, lootSeed, false));
        }
        return List.copyOf(results);
    }

    private static boolean isContainerBlock(StructurePreviewModel.PreviewBlock block) {
        if (block.state() == null) return false;
        return block.state().is(Blocks.CHEST)
            || block.state().is(Blocks.BARREL)
            || block.state().is(Blocks.DISPENSER)
            || block.state().is(Blocks.DROPPER)
            || block.state().is(Blocks.HOPPER)
            || BuiltInRegistries.BLOCK.getKey(block.state().getBlock()).toString().contains("shulker_box");
    }

    private static String fallbackLootTable(StructureType type) {
        return switch (type) {
            case ANCIENT_CITY -> "minecraft:chests/ancient_city";
            case MANSION -> "minecraft:chests/woodland_mansion";
            case END_CITY -> "minecraft:chests/end_city_treasure";
            case BASTION -> "minecraft:chests/bastion_treasure";
            case FORTRESS -> "minecraft:chests/nether_bridge";
            case SHIPWRECK -> "minecraft:chests/shipwreck_treasure";
            case MINESHAFT -> "minecraft:chests/abandoned_mineshaft";
            case RUINED_PORTAL, NETHER_RUINED_PORTAL -> "minecraft:chests/ruined_portal";
            case OCEAN_RUIN -> "minecraft:chests/underwater_ruin_big";
            case IGLOO -> "minecraft:chests/igloo_chest";
            case TRAIL_RUINS -> "minecraft:chests/trail_ruins_common";
            case TRIAL_CHAMBER -> "minecraft:chests/trial_chambers/corridor";
            case DESERT_PYRAMID -> "minecraft:chests/desert_pyramid";
            case JUNGLE_TEMPLE -> "minecraft:chests/jungle_temple";
            case OUTPOST -> "minecraft:chests/pillager_outpost";
            case STRONGHOLD -> "minecraft:chests/stronghold_corridor";
            case TREASURE -> "minecraft:chests/buried_treasure";
            default -> "minecraft:chests/simple_dungeon";
        };
    }

    private static long deterministicFallbackLootSeed(long worldSeed, int x, int y, int z, String lootTable) {
        long seed = worldSeed;
        seed ^= (long) x * 341873128712L;
        seed ^= (long) y * 132897987541L;
        seed ^= (long) z * 42317861L;
        seed ^= (long) lootTable.hashCode() * 31L;
        return seed;
    }

    public static List<ItemLoot> predictChest(long worldSeed, int blockX, int blockY, int blockZ,
                                               String lootTableId, long chestSeed, int maxItems) {
        ensureLootTablesLoaded();
        LootTableDef table = VanillaLootTables.get(lootTableId);
        if (table == null || !VALIDATED_LOOT_TABLES.contains(lootTableId)) return List.of();
        return LootTableSimulator.simulate(table, chestSeed, maxItems);
    }

    public static boolean canPredict(StructureType type) {
        return VALIDATED_STRUCTURES.contains(type);
    }

    public static void clearCache() {
        STRUCTURE_CACHE.clear();
    }

    private static void ensureLootTablesLoaded() {
        String version = "";
        try {
            version = SeedManager.get().getMcVersion();
        } catch (Throwable ignored) {
        }
        if (version == null || version.isBlank()) {
            version = BundledLootTableLoader.runtimeProfile().minecraftVersion();
        }
        if (version.equals(loadedVersion) && VanillaLootTables.has("minecraft:chests/buried_treasure")) return;

        synchronized (ChestLootPredictor.class) {
            if (version.equals(loadedVersion) && VanillaLootTables.has("minecraft:chests/buried_treasure")) return;
            VanillaLootTables.loadAllForProfile(BundledLootTableLoader.VersionProfile.forVersion(version));
            loadedVersion = version;
        }
    }
}
