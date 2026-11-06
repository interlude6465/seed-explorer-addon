package me.seedexplorer.addon.loot;

import me.seedexplorer.addon.debug.PredictionDebugLogger;
import me.seedexplorer.addon.preview.StructurePreviewModel;
import me.seedexplorer.addon.preview.StructurePreviewSimulator;
import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructurePredictionStore;
import me.seedexplorer.addon.render.Terrain3DRenderer;
import me.seedexplorer.addon.structures.DungeonPredictor;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.HashSet;
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

    /** Structures for which the product can currently return chest predictions. */
    private static final Set<StructureType> SUPPORTED_STRUCTURES =
        EnumSet.of(StructureType.DESERT_PYRAMID, StructureType.STRONGHOLD, StructureType.SHIPWRECK,
            StructureType.OUTPOST, StructureType.JUNGLE_TEMPLE, StructureType.TREASURE,
            StructureType.MINESHAFT,
            StructureType.RUINED_PORTAL, StructureType.OCEAN_RUIN,
            StructureType.IGLOO, StructureType.ANCIENT_CITY, StructureType.TRAIL_RUINS,
            StructureType.MANSION, StructureType.END_CITY, StructureType.FORTRESS,
            StructureType.BASTION, StructureType.NETHER_RUINED_PORTAL, StructureType.DUNGEON);

    /*
     * Trial Chambers are deliberately absent above.  The offline replay can recover their
     * broad piece layout, but 26.1.2's jigsaw processor state is not reproduced exactly:
     * central chamber pieces get different loot seeds and some replay-only containers appear.
     * Returning those results as predictions is worse than returning no result, so keep the
     * structure available to the map/diagnostic tools while loot parity is being researched.
     */

    /** Structures whose chest positions and loot seeds are pinned to independent Paper oracles. */
    private static final Set<StructureType> STRICTLY_VALIDATED_STRUCTURES =
        EnumSet.of(StructureType.DESERT_PYRAMID, StructureType.STRONGHOLD, StructureType.SHIPWRECK,
            StructureType.OUTPOST, StructureType.JUNGLE_TEMPLE, StructureType.TREASURE,
            StructureType.BASTION, StructureType.DUNGEON);
    private static final Set<String> VALIDATED_LOOT_TABLES = Set.of(
        "minecraft:chests/desert_pyramid",
        "minecraft:chests/simple_dungeon",
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

    /*
     * VanillaLootStructureSimulator.simulate() accepts a dimension parameter for
     * Nether/End structures. Bastion is strictly validated against the pinned Nether
     * Paper oracle (see PredictionRegressionSuite.validateBastion); fortress and End
     * city predictions are supported but not strict. Trial chambers remain gated out
     * of STRICTLY_VALIDATED_STRUCTURES while 26.1.2 jigsaw processor parity is researched.
     */
    public static List<ChestLootOutput> predictForStructure(GeneratedStructure structure) {
        return predictForStructure(structure, DEFAULT_PREDICTIONS_PER_CHEST);
    }

    public static List<ChestLootOutput> predictForStructure(GeneratedStructure structure, int maxItems) {
        long worldSeed = SeedManager.get().getWorldSeed();
        return predictForStructure(worldSeed, structure, maxItems);
    }

    /** Explicit-seed entry point used by offline parity probes. */
    public static List<ChestLootOutput> predictForStructure(long worldSeed, GeneratedStructure structure, int maxItems) {
        if (worldSeed == 0) return List.of();
        if (!SUPPORTED_STRUCTURES.contains(structure.type)) return List.of();
        var persisted = StructurePredictionStore.get().lootFor(
            Terrain3DRenderer.worldCacheIdentity(worldSeed), worldSeed,
            structure.type.dimension, structure);
        if (persisted.isPresent()) return persisted.get();
        ensureLootTablesLoaded();

        if (structure.type == StructureType.DUNGEON) {
            List<ChestLootOutput> out = DungeonPredictor.chestsFor(worldSeed, structure, maxItems);
            PredictedChestOverlay.get().remember(out);
            return out;
        }

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
        // Preview geometry is retained only as position data when the simulator cannot
        // recover the vanilla loot seed. It must never be turned into plausible-looking loot.
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
            out = predictPreviewPositions(worldSeed, structure);
            if (out.isEmpty()) {
                source = structureUsesTemplateFallback(structure.type) ? "empty_no_template_containers" : "empty";
            } else {
                source = "preview_positions_only";
            }
        }

        out = List.copyOf(out);
        if (STRUCTURE_CACHE.size() > MAX_STRUCTURE_CACHE_ENTRIES) STRUCTURE_CACHE.clear();
        STRUCTURE_CACHE.put(cacheKey, out);
        PredictionDebugLogger.structurePrediction(worldSeed, structure.type.dimension, structure, source, out);
        PredictedChestOverlay.get().remember(out);
        return out;
    }

    /**
     * Returns chest-minecart containers from the vanilla structure replay. This
     * is deliberately separate from block chests: minecart positions are entity
     * coordinates and can be confused with nearby chest blocks when flattened
     * into one preview list.
     */
    public static List<ChestLootOutput> predictMinecartsForStructure(long worldSeed,
                                                                      GeneratedStructure structure,
                                                                      int maxItems) {
        if (worldSeed == 0 || structure == null || structure.type != StructureType.MINESHAFT) {
            return List.of();
        }
        ResourceKey<Structure> key = structureKeyFor(worldSeed, structure);
        if (key == null) return List.of();
        ensureLootTablesLoaded();
        List<ChestLootOutput> result = new ArrayList<>();
        for (VanillaLootStructureSimulator.SimulatedMinecart cart :
            VanillaLootStructureSimulator.simulateMinecarts(worldSeed, structure.type.dimension, key,
                structure.startChunkX, structure.startChunkZ)) {
            List<ItemLoot> items = LootTableSimulator.simulateVanilla(cart.lootTableId(), cart.lootSeed());
            if (items == null) {
                LootTableDef table = VanillaLootTables.get(cart.lootTableId());
                items = table == null ? List.of() : LootTableSimulator.simulate(table, cart.lootSeed(), maxItems);
            }
            result.add(new ChestLootOutput(cart.x(), cart.y(), cart.z(), cart.lootTableId(),
                items, cart.lootSeed(), true));
        }
        return List.copyOf(result);
    }

    /** Converts a validated monster-room replay into exact chest predictions. */
    public static List<ChestLootOutput> predictDungeonContainers(
        VanillaLootStructureSimulator.SimulatedDungeon dungeon, int maxItems) {
        ensureLootTablesLoaded();
        List<ChestLootOutput> results = new ArrayList<>();
        for (VanillaLootStructureSimulator.SimulatedContainer chest : dungeon.containers()) {
            List<ItemLoot> vanilla = LootTableSimulator.simulateVanilla(chest.lootTableId(), chest.lootSeed());
            LootTableDef table = VanillaLootTables.get(chest.lootTableId());
            List<ItemLoot> items = vanilla != null ? vanilla
                : (table == null ? List.of() : LootTableSimulator.simulate(table, chest.lootSeed(), maxItems));
            results.add(new ChestLootOutput(chest.x(), chest.y(), chest.z(), chest.lootTableId(),
                items, chest.lootSeed(), true));
        }
        return List.copyOf(results);
    }

    /** Returns only structure types whose loot tables can produce every requested term. */
    public static Set<StructureType> candidateStructureTypes(Set<LootSearchTerm> filters) {
        ensureLootTablesLoaded();
        if (filters == null || filters.isEmpty()) return Set.copyOf(SUPPORTED_STRUCTURES);
        Set<StructureType> result = EnumSet.noneOf(StructureType.class);
        for (StructureType type : SUPPORTED_STRUCTURES) {
            List<String> tables = lootTablesFor(type);
            if (!tables.isEmpty() && filters.stream().allMatch(term ->
                tables.stream().anyMatch(table -> tableCanProduce(table, term, new HashSet<>())))) {
                result.add(type);
            }
        }
        return Set.copyOf(result);
    }

    public static Set<Integer> candidateDimensions(Set<LootSearchTerm> filters) {
        Set<Integer> dimensions = new HashSet<>();
        for (StructureType type : candidateStructureTypes(filters)) dimensions.add(type.dimension);
        return Set.copyOf(dimensions);
    }

    private static boolean tableCanProduce(String tableId, LootSearchTerm term, Set<String> visited) {
        if (!visited.add(tableId)) return false;
        LootTableDef table = VanillaLootTables.get(tableId);
        if (table == null) return false;
        for (LootPool pool : table.pools()) {
            for (ItemLoot entry : pool.entries()) {
                if (entry.referenceTableId() != null
                    && tableCanProduce(entry.referenceTableId(), term, visited)) return true;
                if (!term.itemId().equals(entry.itemId())) continue;
                if (term.enchantment() == null || term.enchantment().isBlank()) return true;
                if (entry.randomEnchantment() || entry.levelsEnchantment()) return true;
                if (entry.enchantmentId() != null
                    && entry.enchantmentId().replace("minecraft:", "").equals(term.enchantment().replace("minecraft:", ""))
                    && (term.level() == 0 || entry.enchantmentLevel() == 0
                        || entry.enchantmentLevel() == term.level())) return true;
            }
        }
        return false;
    }

    private static List<String> lootTablesFor(StructureType type) {
        return switch (type) {
            case DUNGEON -> List.of("minecraft:chests/simple_dungeon");
            case MINESHAFT -> List.of("minecraft:chests/abandoned_mineshaft");
            case DESERT_PYRAMID -> List.of("minecraft:chests/desert_pyramid");
            case STRONGHOLD -> List.of("minecraft:chests/stronghold_corridor",
                "minecraft:chests/stronghold_crossing", "minecraft:chests/stronghold_library",
                "minecraft:chests/stronghold_storeroom");
            case SHIPWRECK -> List.of("minecraft:chests/shipwreck_treasure",
                "minecraft:chests/shipwreck_supply", "minecraft:chests/shipwreck_map");
            case OUTPOST -> List.of("minecraft:chests/pillager_outpost");
            case JUNGLE_TEMPLE -> List.of("minecraft:chests/jungle_temple",
                "minecraft:chests/jungle_temple_dispenser");
            case TREASURE -> List.of("minecraft:chests/buried_treasure");
            case RUINED_PORTAL, NETHER_RUINED_PORTAL -> List.of("minecraft:chests/ruined_portal");
            case OCEAN_RUIN -> List.of("minecraft:chests/underwater_ruin_small",
                "minecraft:chests/underwater_ruin_big");
            case IGLOO -> List.of("minecraft:chests/igloo_chest");
            case ANCIENT_CITY -> List.of("minecraft:chests/ancient_city",
                "minecraft:chests/ancient_city_ice_box");
            case TRAIL_RUINS -> List.of("minecraft:chests/trail_ruins_common",
                "minecraft:chests/trail_ruins_rare");
            case MANSION -> List.of("minecraft:chests/woodland_mansion");
            case END_CITY -> List.of("minecraft:chests/end_city_treasure");
            case FORTRESS -> List.of("minecraft:chests/nether_bridge");
            case BASTION -> List.of("minecraft:chests/bastion_treasure", "minecraft:chests/bastion_other",
                "minecraft:chests/bastion_hoglin_stable", "minecraft:chests/bastion_bridge");
            default -> List.of();
        };
    }

    public static List<ChestLootOutput> predictTemplateContainerSample(GeneratedStructure structure, int maxChests, int maxItems) {
        long worldSeed = SeedManager.get().getWorldSeed();
        if (worldSeed == 0 || maxChests <= 0) return List.of();
        if (!SUPPORTED_STRUCTURES.contains(structure.type)) return List.of();
        ensureLootTablesLoaded();

        List<StructurePreviewModel.PreviewBlock> containers =
            StructurePreviewSimulator.previewContainerBlocks(structure, worldSeed, maxChests);
        List<ChestLootOutput> results = new ArrayList<>();
        for (StructurePreviewModel.PreviewBlock block : containers) {
            results.add(ChestLootOutput.positionOnly(block.x(), block.y(), block.z()));
        }
        List<ChestLootOutput> out = List.copyOf(results);
        PredictionDebugLogger.structurePrediction(worldSeed, structure.type.dimension, structure,
            "template_positions_only", out);
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
        // These structures commonly expose template container positions even when the full
        // simulator cannot recover a loot seed. The positions remain useful for diagnostics.
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
            List<ItemLoot> predicted;
            if (STRICTLY_VALIDATED_STRUCTURES.contains(type)) {
                // Exactness is reserved for structure families covered by the
                // regression oracles. Mineshaft carts use the separate entity
                // API below because their coordinates may move after loading.
                predicted = exactChestItems(chest, chests, table, maxItems);
            } else {
                predicted = table == null
                    ? List.of()
                    : LootTableSimulator.simulate(table, chest.lootSeed(), maxItems);
            }
            results.add(new ChestLootOutput(
                chest.x(), chest.y(), chest.z(), chest.lootTableId(), predicted, chest.lootSeed(),
                STRICTLY_VALIDATED_STRUCTURES.contains(type)));
        }
        return List.copyOf(results);
    }

    /** Double chests expose two block positions and two loot seeds; the opened
     * inventory contains one loot roll from each half. */
    private static List<ItemLoot> exactChestItems(
        VanillaLootStructureSimulator.SimulatedContainer target,
        List<VanillaLootStructureSimulator.SimulatedContainer> all,
        LootTableDef fallbackTable,
        int maxItems) {
        List<ItemLoot> merged = new ArrayList<>();
        for (VanillaLootStructureSimulator.SimulatedContainer member : sharedChestMembers(target, all)) {
            List<ItemLoot> vanilla = LootTableSimulator.simulateVanilla(member.lootTableId(), member.lootSeed());
            List<ItemLoot> items = vanilla != null
                ? vanilla
                : (fallbackTable == null ? List.of()
                    : LootTableSimulator.simulate(fallbackTable, member.lootSeed(), maxItems));
            for (ItemLoot item : items) {
                int existing = -1;
                for (int i = 0; i < merged.size(); i++) {
                    ItemLoot prior = merged.get(i);
                    if (prior.itemId().equals(item.itemId())
                        && java.util.Objects.equals(prior.enchantmentSummary(), item.enchantmentSummary())) {
                        existing = i;
                        break;
                    }
                }
                if (existing < 0) {
                    merged.add(item);
                } else {
                    ItemLoot prior = merged.get(existing);
                    int count = prior.minCount() + item.minCount();
                    merged.set(existing, new ItemLoot(prior.itemId(), count, count, 0,
                        prior.displayName(), prior.randomEnchantment(), prior.enchantmentId(),
                        prior.enchantmentLevel(), null, false, 0, 0,
                        prior.enchantmentSummary(), false));
                }
            }
        }
        return List.copyOf(merged);
    }

    private static List<VanillaLootStructureSimulator.SimulatedContainer> sharedChestMembers(
        VanillaLootStructureSimulator.SimulatedContainer target,
        List<VanillaLootStructureSimulator.SimulatedContainer> all) {
        List<VanillaLootStructureSimulator.SimulatedContainer> members = new ArrayList<>();
        members.add(target);
        for (VanillaLootStructureSimulator.SimulatedContainer candidate : all) {
            if (candidate == target) continue;
            if (!candidate.lootTableId().equals(target.lootTableId())) continue;
            if (candidate.y() != target.y()) continue;
            boolean adjacent = (candidate.x() == target.x() && Math.abs(candidate.z() - target.z()) == 1)
                || (candidate.z() == target.z() && Math.abs(candidate.x() - target.x()) == 1);
            if (!adjacent) continue;
            members.add(candidate);
        }
        return members;
    }

    private static ResourceKey<Structure> structureKeyFor(long seed, GeneratedStructure structure) {
        return switch (structure.type) {
            case DESERT_PYRAMID -> BuiltinStructures.DESERT_PYRAMID;
            case STRONGHOLD -> BuiltinStructures.STRONGHOLD;
            case MINESHAFT -> structure.variant.equals("minecraft:mineshaft_mesa")
                ? BuiltinStructures.MINESHAFT_MESA : BuiltinStructures.MINESHAFT;
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

    private static List<ChestLootOutput> predictPreviewPositions(long worldSeed, GeneratedStructure structure) {
        StructurePreviewModel preview;
        try {
            preview = StructurePreviewSimulator.preview(structure, worldSeed);
        } catch (Throwable ignored) {
            return List.of();
        }
        if (preview == null || preview.isEmpty()) return List.of();

        List<ChestLootOutput> results = new ArrayList<>();
        for (StructurePreviewModel.PreviewBlock block : preview.blocks()) {
            if (!isContainerBlock(block)) continue;
            results.add(ChestLootOutput.positionOnly(block.x(), block.y(), block.z()));
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

    public static List<ItemLoot> predictChest(long worldSeed, int blockX, int blockY, int blockZ,
                                               String lootTableId, long chestSeed, int maxItems) {
        ensureLootTablesLoaded();
        LootTableDef table = VanillaLootTables.get(lootTableId);
        if (table == null || !VALIDATED_LOOT_TABLES.contains(lootTableId)) return List.of();
        List<ItemLoot> vanilla = LootTableSimulator.simulateVanilla(lootTableId, chestSeed);
        if (vanilla != null) return vanilla;
        return LootTableSimulator.simulate(table, chestSeed, maxItems);
    }

    public static boolean canPredict(StructureType type) {
        return SUPPORTED_STRUCTURES.contains(type);
    }

    public static boolean isStrictlyValidated(StructureType type) {
        return STRICTLY_VALIDATED_STRUCTURES.contains(type);
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
