package me.seedexplorer.addon.loot;

import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.worldgen.PredictionContext;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/**
 * Client-state-free facade for exact vanilla structure container replay.
 */
public final class StandaloneStructureLootPredictor {
    /**
     * Exact means the complete 26.3 chain (container position, table, seed, and
     * items) has passed an independent generated-world oracle. The offline
     * simulator agreeing with itself is not sufficient evidence.
     */
    private static final Set<String> VALIDATED_26_3_STRUCTURE_KEYS = Set.of(
        "minecraft:buried_treasure",
        "minecraft:igloo",
        "minecraft:ruined_portal",
        "minecraft:ruined_portal_desert",
        "minecraft:ruined_portal_jungle",
        "minecraft:ruined_portal_mountain",
        "minecraft:ruined_portal_ocean",
        "minecraft:ruined_portal_swamp"
    );

    private StandaloneStructureLootPredictor() {
    }

    public static List<ChestLootOutput> predict(PredictionContext context,
                                                 ResourceKey<Structure> structureKey,
                                                 int startChunkX, int startChunkZ,
                                                 int maxFallbackRolls) {
        return predictDetailed(context, structureKey, startChunkX, startChunkZ, maxFallbackRolls).inventories();
    }

    public record DetailedPrediction(List<VanillaLootStructureSimulator.SimulatedContainer> containers,
                                     List<ChestLootOutput> inventories) {
        public DetailedPrediction {
            containers = List.copyOf(containers);
            inventories = List.copyOf(inventories);
        }
    }

    /** Keeps each physical container's seed for independent inventory-fill verification. */
    public static DetailedPrediction predictDetailed(PredictionContext context,
                                                       ResourceKey<Structure> structureKey,
                                                       int startChunkX, int startChunkZ,
                                                       int maxFallbackRolls) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(structureKey, "structureKey");
        if (maxFallbackRolls < 1) throw new IllegalArgumentException("maxFallbackRolls must be positive");

        BundledLootTableLoader.VersionProfile profile =
            BundledLootTableLoader.VersionProfile.forVersion(context.minecraftVersion());

        List<VanillaLootStructureSimulator.SimulatedContainer> containers =
            VanillaLootStructureSimulator.simulate(context, structureKey, startChunkX, startChunkZ);
        return new DetailedPrediction(containers, outputs(context, containers, maxFallbackRolls,
            independentlyValidated(context, structureKey)));
    }

    /** Converts a simulator-selected container candidate into modeled loot.
     * Selection may use live block positions, so this path deliberately cannot
     * claim seed-only exactness even when every modeled component is complete. */
    public static List<ChestLootOutput> predictContainers(
        PredictionContext context,
        List<VanillaLootStructureSimulator.SimulatedContainer> containers,
        int maxFallbackRolls) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(containers, "containers");
        if (maxFallbackRolls < 1) {
            throw new IllegalArgumentException("maxFallbackRolls must be positive");
        }

        BundledLootTableLoader.VersionProfile profile =
            BundledLootTableLoader.VersionProfile.forVersion(context.minecraftVersion());
        return outputs(context, List.copyOf(containers), maxFallbackRolls, false);
    }

    private static List<ChestLootOutput> outputs(
        PredictionContext context,
        List<VanillaLootStructureSimulator.SimulatedContainer> containers,
        int maxFallbackRolls,
        boolean independentlyValidated) {
        BundledLootTableLoader.VersionProfile profile =
            BundledLootTableLoader.VersionProfile.forVersion(context.minecraftVersion());
        List<ChestLootOutput> outputs = new ArrayList<>(containers.size());
        for (List<VanillaLootStructureSimulator.SimulatedContainer> members
            : sharedChestInventories(containers)) {
            VanillaLootStructureSimulator.SimulatedContainer container = members.getFirst();
            List<ItemLoot> items = exactItems(context, members);
            boolean exact = items != null && independentlyValidated
                && metadataComplete(items);
            if (items == null) {
                items = fallbackItems(profile, members, maxFallbackRolls);
            }
            outputs.add(new ChestLootOutput(container.x(), container.y(), container.z(),
                container.lootTableId(), items, container.lootSeed(), exact));
        }
        return List.copyOf(outputs);
    }

    /** Resolves a map marker to the exact vanilla structure key used for replay. */
    public static ResourceKey<Structure> structureKey(PredictionContext context,
                                                       GeneratedStructure structure) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(structure, "structure");
        return switch (structure.type) {
            case VILLAGE -> switch (structure.variant) {
                case "minecraft:village_desert" -> BuiltinStructures.VILLAGE_DESERT;
                case "minecraft:village_savanna" -> BuiltinStructures.VILLAGE_SAVANNA;
                case "minecraft:village_snowy" -> BuiltinStructures.VILLAGE_SNOWY;
                case "minecraft:village_taiga" -> BuiltinStructures.VILLAGE_TAIGA;
                default -> BuiltinStructures.VILLAGE_PLAINS;
            };
            case DESERT_PYRAMID -> BuiltinStructures.DESERT_PYRAMID;
            case STRONGHOLD -> BuiltinStructures.STRONGHOLD;
            case MINESHAFT -> structure.variant.equals("minecraft:mineshaft_mesa")
                ? BuiltinStructures.MINESHAFT_MESA : BuiltinStructures.MINESHAFT;
            case SHIPWRECK -> structure.variant.equals("minecraft:shipwreck_beached")
                ? BuiltinStructures.SHIPWRECK_BEACHED : BuiltinStructures.SHIPWRECK;
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
            case NETHER_RUINED_PORTAL -> BuiltinStructures.RUINED_PORTAL_NETHER;
            case ABANDONED_CAMP -> variantKey(structure.variant, "minecraft:abandoned_camp_");
            case RUINED_PORTAL -> ruinedPortalKey(context, structure);
            case OCEAN_RUIN -> oceanRuinKey(context, structure);
            default -> null;
        };
    }

    public static List<ChestLootOutput> predict(PredictionContext context,
                                                 GeneratedStructure structure,
                                                 int maxFallbackRolls) {
        if (structure.type == me.seedexplorer.addon.structures.StructureType.DUNGEON) {
            return predictDungeon(context, structure, maxFallbackRolls);
        }
        ResourceKey<Structure> key = structureKey(context, structure);
        if (key == null) return List.of();
        return predict(context, key, structure.startChunkX, structure.startChunkZ,
            maxFallbackRolls);
    }

    private static List<ChestLootOutput> predictDungeon(PredictionContext context,
                                                         GeneratedStructure structure,
                                                         int maxFallbackRolls) {
        BundledLootTableLoader.VersionProfile profile =
            BundledLootTableLoader.VersionProfile.forVersion(context.minecraftVersion());
        for (VanillaLootStructureSimulator.SimulatedDungeon dungeon
            : VanillaLootStructureSimulator.simulateDungeons(context.worldSeed(),
                structure.startChunkX, structure.startChunkZ)) {
            if (dungeon.x() != structure.x || dungeon.z() != structure.z
                || structure.y != GeneratedStructure.UNKNOWN_Y && dungeon.y() != structure.y) continue;
            List<ChestLootOutput> outputs = new ArrayList<>();
            for (VanillaLootStructureSimulator.SimulatedContainer container : dungeon.containers()) {
                List<ItemLoot> items = LootTableSimulator.simulateVanilla(context,
                    container.lootTableId(), container.lootSeed(), container.x(), container.y(), container.z());
                // Dungeon replay is deterministic, but its complete 26.3
                // position/seed chain is not yet independently validated.
                boolean exact = false;
                if (items == null) {
                    LootTableDef fallback = VanillaLootTables.get(profile, container.lootTableId());
                    items = fallback == null ? List.of()
                        : LootTableSimulator.simulate(profile, fallback, container.lootSeed(), maxFallbackRolls);
                }
                outputs.add(new ChestLootOutput(container.x(), container.y(), container.z(),
                    container.lootTableId(), items, container.lootSeed(), exact));
            }
            return List.copyOf(outputs);
        }
        return List.of();
    }

    private static ResourceKey<Structure> variantKey(String variant, String prefix) {
        if (variant == null || !variant.startsWith(prefix)) return null;
        return ResourceKey.create(Registries.STRUCTURE, Identifier.parse(variant));
    }

    private static boolean independentlyValidated(PredictionContext context,
                                                   ResourceKey<Structure> structureKey) {
        return "26.3".equals(context.minecraftVersion())
            && VALIDATED_26_3_STRUCTURE_KEYS.contains(structureKey.identifier().toString());
    }

    private static ResourceKey<Structure> ruinedPortalKey(PredictionContext context,
                                                           GeneratedStructure structure) {
        return switch (structure.variant) {
            case "minecraft:ruined_portal" -> BuiltinStructures.RUINED_PORTAL_STANDARD;
            case "minecraft:ruined_portal_desert" -> BuiltinStructures.RUINED_PORTAL_DESERT;
            case "minecraft:ruined_portal_jungle" -> BuiltinStructures.RUINED_PORTAL_JUNGLE;
            case "minecraft:ruined_portal_swamp" -> BuiltinStructures.RUINED_PORTAL_SWAMP;
            case "minecraft:ruined_portal_mountain" -> BuiltinStructures.RUINED_PORTAL_MOUNTAIN;
            case "minecraft:ruined_portal_ocean" -> BuiltinStructures.RUINED_PORTAL_OCEAN;
            default -> switch (biomeAt(context, structure)) {
                case "minecraft:desert" -> BuiltinStructures.RUINED_PORTAL_DESERT;
                case "minecraft:jungle", "minecraft:bamboo_jungle" -> BuiltinStructures.RUINED_PORTAL_JUNGLE;
                case "minecraft:swamp", "minecraft:mangrove_swamp" -> BuiltinStructures.RUINED_PORTAL_SWAMP;
                case "minecraft:badlands", "minecraft:wooded_badlands", "minecraft:eroded_badlands",
                     "minecraft:windswept_hills", "minecraft:windswept_gravelly_hills",
                     "minecraft:windswept_forest", "minecraft:stony_peaks", "minecraft:stony_shore" ->
                    BuiltinStructures.RUINED_PORTAL_MOUNTAIN;
                case "minecraft:ocean", "minecraft:deep_ocean", "minecraft:cold_ocean",
                     "minecraft:deep_cold_ocean", "minecraft:frozen_ocean", "minecraft:deep_frozen_ocean",
                     "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean", "minecraft:warm_ocean" ->
                    BuiltinStructures.RUINED_PORTAL_OCEAN;
                default -> BuiltinStructures.RUINED_PORTAL_STANDARD;
            };
        };
    }

    private static ResourceKey<Structure> oceanRuinKey(PredictionContext context,
                                                        GeneratedStructure structure) {
        if (structure.variant.equals("minecraft:ocean_ruin_warm")) return BuiltinStructures.OCEAN_RUIN_WARM;
        if (structure.variant.equals("minecraft:ocean_ruin_cold")) return BuiltinStructures.OCEAN_RUIN_COLD;
        return switch (biomeAt(context, structure)) {
            case "minecraft:warm_ocean", "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean" ->
                BuiltinStructures.OCEAN_RUIN_WARM;
            default -> BuiltinStructures.OCEAN_RUIN_COLD;
        };
    }

    private static String biomeAt(PredictionContext context, GeneratedStructure structure) {
        return WorldgenEngine.getBiome(context.worldSeed(), context.dimension(),
            structure.x, 64, structure.z).id();
    }

    private static List<ItemLoot> exactItems(PredictionContext context,
                                             List<VanillaLootStructureSimulator.SimulatedContainer> members) {
        List<ItemLoot> merged = new ArrayList<>();
        for (VanillaLootStructureSimulator.SimulatedContainer member : members) {
            List<ItemLoot> items = LootTableSimulator.simulateVanilla(context, member.lootTableId(),
                member.lootSeed(), member.x(), member.y(), member.z());
            if (items == null) return null;
            mergeItems(merged, items);
        }
        return List.copyOf(merged);
    }

    private static String containerPosition(VanillaLootStructureSimulator.SimulatedContainer container) {
        return container.x() + ":" + container.y() + ":" + container.z();
    }

    private static List<ItemLoot> fallbackItems(
        BundledLootTableLoader.VersionProfile profile, List<VanillaLootStructureSimulator.SimulatedContainer> members, int maxFallbackRolls) {
        List<ItemLoot> merged = new ArrayList<>();
        for (VanillaLootStructureSimulator.SimulatedContainer member : members) {
            LootTableDef fallback = VanillaLootTables.get(profile, member.lootTableId());
            if (fallback != null) {
                mergeItems(merged, LootTableSimulator.simulate(
                    profile, fallback, member.lootSeed(), maxFallbackRolls));
            }
        }
        return List.copyOf(merged);
    }

    public static List<List<VanillaLootStructureSimulator.SimulatedContainer>> sharedChestInventories(
        List<VanillaLootStructureSimulator.SimulatedContainer> containers) {
        List<List<VanillaLootStructureSimulator.SimulatedContainer>> inventories = new ArrayList<>();
        Set<String> consumed = new HashSet<>();
        for (VanillaLootStructureSimulator.SimulatedContainer target : containers) {
            String targetPosition = containerPosition(target);
            if (consumed.contains(targetPosition)) continue;
            List<VanillaLootStructureSimulator.SimulatedContainer> members = new ArrayList<>();
            members.add(target);
            if (target.pairedChest()) {
                for (VanillaLootStructureSimulator.SimulatedContainer candidate : containers) {
                    if (candidate == target || !candidate.pairedChest()
                        || consumed.contains(containerPosition(candidate))) continue;
                    if (!candidate.lootTableId().equals(target.lootTableId())
                        || candidate.y() != target.y()) continue;
                    boolean adjacent = candidate.x() == target.x()
                        && Math.abs(candidate.z() - target.z()) == 1
                        || candidate.z() == target.z()
                        && Math.abs(candidate.x() - target.x()) == 1;
                    if (adjacent) {
                        members.add(candidate);
                        break;
                    }
                }
            }
            for (VanillaLootStructureSimulator.SimulatedContainer member : members) {
                consumed.add(containerPosition(member));
            }
            inventories.add(List.copyOf(members));
        }
        return List.copyOf(inventories);
    }

    private static void mergeItems(List<ItemLoot> destination, List<ItemLoot> incoming) {
        for (ItemLoot item : incoming) {
            int existingIndex = -1;
            for (int i = 0; i < destination.size(); i++) {
                ItemLoot existing = destination.get(i);
                if (equivalentItem(existing, item)) {
                    existingIndex = i;
                    break;
                }
            }
            if (existingIndex < 0) {
                destination.add(item);
                continue;
            }
            ItemLoot existing = destination.get(existingIndex);
            destination.set(existingIndex, new ItemLoot(existing.itemId(),
                existing.minCount() + item.minCount(), existing.maxCount() + item.maxCount(),
                existing.weight(),
                existing.displayName(), existing.randomEnchantment(), existing.enchantmentId(),
                existing.enchantmentLevel(), existing.referenceTableId(),
                existing.levelsEnchantment(), existing.enchantMinLevel(),
                existing.enchantMaxLevel(), existing.enchantmentSummary(),
                existing.hasDamageRoll(), existing.damage(), existing.potionContents(),
                existing.hasUnrepresentedComponents(), existing.mapDecorations(),
                existing.suspiciousStewEffects()));
        }
    }

    private static boolean equivalentItem(ItemLoot first, ItemLoot second) {
        return first.itemId().equals(second.itemId())
            && first.randomEnchantment() == second.randomEnchantment()
            && Objects.equals(first.enchantmentId(), second.enchantmentId())
            && first.enchantmentLevel() == second.enchantmentLevel()
            && Objects.equals(first.referenceTableId(), second.referenceTableId())
            && first.levelsEnchantment() == second.levelsEnchantment()
            && first.enchantMinLevel() == second.enchantMinLevel()
            && first.enchantMaxLevel() == second.enchantMaxLevel()
            && Objects.equals(first.enchantmentSummary(), second.enchantmentSummary())
            && first.hasDamageRoll() == second.hasDamageRoll()
            && Objects.equals(first.damage(), second.damage())
            && Objects.equals(first.potionContents(), second.potionContents())
                    && Objects.equals(first.mapDecorations(), second.mapDecorations())
                    && Objects.equals(first.suspiciousStewEffects(), second.suspiciousStewEffects())
            && first.hasUnrepresentedComponents() == second.hasUnrepresentedComponents();
    }

    private static boolean metadataComplete(List<ItemLoot> items) {
        return items.stream().noneMatch(ItemLoot::hasUnrepresentedComponents);
    }

    public static List<ItemLoot> mergeEquivalentItems(List<ItemLoot> items) {
        List<ItemLoot> merged = new ArrayList<>();
        mergeItems(merged, items == null ? List.of() : items);
        return List.copyOf(merged);
    }
}
