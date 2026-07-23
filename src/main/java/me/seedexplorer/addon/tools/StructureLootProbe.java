package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.ChestLootPredictor;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.preview.StructurePreviewModel;
import me.seedexplorer.addon.preview.StructurePreviewSimulator;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.io.PrintStream;
import java.util.List;

/**
 * General structure loot probe. Args: [type] [seed] [limit].
 * type = StructureType enum name (e.g. TRIAL_CHAMBER, IGLOO, MINESHAFT).
 * Bypasses SeedManager (unavailable headless): resolves the structure key,
 * runs simulate() then predictChest() directly for each match.
 */
public final class StructureLootProbe {
    private static final PrintStream OUT = System.out;

    private StructureLootProbe() {}

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            StructureType type = StructureType.valueOf(
                args.length > 0 ? args[0].toUpperCase() : "TRIAL_CHAMBER");
            long seed = args.length > 1 ? Long.parseLong(args[1]) : 2026071501L;
            int radius = args.length > 3 ? Integer.parseInt(args[3]) : 900;
            int dim = type.dimension;
            ResourceKey<Structure> key = keyFor(type);

            // Direct-chunk mode: a "chunk=X,Z" arg skips the slow dimension scan and
            // simulates that exact start chunk. Used to verify a specific structure from
            // an in-game debug log without scanning the whole region.
            for (String a : args) {
                if (a.startsWith("chunk=")) {
                    String[] parts = a.substring("chunk=".length()).split(",");
                    int cx = Integer.parseInt(parts[0].trim());
                    int cz = Integer.parseInt(parts[1].trim());
                    OUT.println("type=" + type + " key=" + key + " seed=" + seed
                        + " dim=" + dim + " DIRECT startChunk=" + cx + "," + cz);
                    probeChunk(type, key, seed, dim, cx, cz);
                    return;
                }
            }

            int limit = args.length > 2 ? Integer.parseInt(args[2]) : 4;
            List<GeneratedStructure> found;
            if (type == StructureType.ANCIENT_CITY && radius <= 128) {
                found = findByPlacementRegions(seed, type, radius, limit);
            } else {
                found = VanillaStructurePredictor.predictDimension(
                    seed, dim, -radius, -radius, radius, radius, true, true).stream()
                    .filter(s -> s.type == type)
                    .limit(limit)
                    .toList();
            }
            OUT.println("type=" + type + " key=" + key + " seed=" + seed
                + " dim=" + dim + " radius=" + radius + " found=" + found.size());

            for (GeneratedStructure s : found) {
                var start = me.seedexplorer.addon.worldgen.WorldgenEngine
                    .generateSelectedStructureStart(seed, dim, key,
                        new net.minecraft.world.level.ChunkPos(s.startChunkX, s.startChunkZ));
                OUT.println("  " + type + " at=" + s.x + "," + s.z
                    + " variant=" + s.variant + " startChunk=" + s.startChunkX + "," + s.startChunkZ
                    + " startValid=" + (start != null && start.isValid())
                    + " pieces=" + (start == null ? -1 : start.getPieces().size()));
                var containers = VanillaLootStructureSimulator.simulate(
                    seed, dim, key, s.startChunkX, s.startChunkZ);
                OUT.println("    simulate -> " + containers.size() + " containers");
                for (var c : containers) {
                    List<ItemLoot> loot = ChestLootPredictor.predictChest(
                        seed, c.x(), c.y(), c.z(), c.lootTableId(), c.lootSeed(), 27);
                    OUT.println("    container=" + c.x() + "," + c.y() + "," + c.z()
                        + " table=" + c.lootTableId() + " seed=" + c.lootSeed()
                        + " items=" + loot.size());
                    for (ItemLoot item : loot) {
                        OUT.println("        " + item.minCount() + "-" + item.maxCount()
                            + "x " + item.itemId()
                            + enchantText(item));
                    }
                }
            }
        } catch (Throwable t) {
            OUT.println("probe_failed=" + t.getClass().getName() + " " + t.getMessage());
            t.printStackTrace(OUT);
            System.exit(1);
        }
    }

    private static void probeChunk(StructureType type, ResourceKey<Structure> key,
                                   long seed, int dim, int cx, int cz) {
        var start = me.seedexplorer.addon.worldgen.WorldgenEngine
            .generateSelectedStructureStart(seed, dim, key,
                new net.minecraft.world.level.ChunkPos(cx, cz));
        OUT.println("  " + type + " startChunk=" + cx + "," + cz
            + " startValid=" + (start != null && start.isValid())
            + " pieces=" + (start == null ? -1 : start.getPieces().size()));
        var containers = VanillaLootStructureSimulator.simulate(seed, dim, key, cx, cz);
        OUT.println("    simulate -> " + containers.size() + " containers");
        if (containers.isEmpty()) {
            printTemplateContainerFallback(type, seed, dim, cx, cz);
        }
        for (var c : containers) {
            List<ItemLoot> loot = ChestLootPredictor.predictChest(
                seed, c.x(), c.y(), c.z(), c.lootTableId(), c.lootSeed(), 27);
            OUT.println("    container=" + c.x() + "," + c.y() + "," + c.z()
                + " table=" + c.lootTableId() + " seed=" + c.lootSeed()
                + " items=" + loot.size());
            for (ItemLoot item : loot) {
                OUT.println("        " + item.minCount() + "-" + item.maxCount()
                    + "x " + item.itemId()
                    + enchantText(item));
            }
        }
    }

    private static void printTemplateContainerFallback(StructureType type, long seed, int dim, int cx, int cz) {
        try {
            GeneratedStructure structure = new GeneratedStructure(cx * 16, cz * 16, cx, cz, type, "", false);
            StructurePreviewModel preview = StructurePreviewSimulator.preview(structure, seed);
            long containerBlocks = preview.blocks().stream().filter(StructureLootProbe::isContainerBlock).count();
            OUT.println("    template preview -> " + containerBlocks + " container blocks"
                + " blocks=" + preview.blocks().size()
                + " box=" + preview.minX() + "," + preview.minY() + "," + preview.minZ()
                + ".." + preview.maxX() + "," + preview.maxY() + "," + preview.maxZ());
            preview.blocks().stream()
                .filter(StructureLootProbe::isContainerBlock)
                .limit(12)
                .forEach(block -> OUT.println("      template_container="
                    + block.x() + "," + block.y() + "," + block.z()
                    + " block=" + block.state().getBlock()));
        } catch (Throwable t) {
            OUT.println("    template preview failed=" + t.getClass().getSimpleName()
                + " " + String.valueOf(t.getMessage()));
        }
    }

    private static boolean isContainerBlock(StructurePreviewModel.PreviewBlock block) {
        if (block.state() == null) return false;
        return block.state().is(Blocks.CHEST)
            || block.state().is(Blocks.BARREL)
            || block.state().is(Blocks.DISPENSER)
            || block.state().is(Blocks.DROPPER)
            || block.state().is(Blocks.HOPPER);
    }

    private static List<GeneratedStructure> findByPlacementRegions(long seed, StructureType type, int regionRadius, int limit) {
        java.util.ArrayList<GeneratedStructure> out = new java.util.ArrayList<>();
        for (int rz = -regionRadius; rz <= regionRadius && out.size() < limit; rz++) {
            for (int rx = -regionRadius; rx <= regionRadius && out.size() < limit; rx++) {
                GeneratedStructure structure = VanillaStructurePredictor.predictInRegion(seed, type, rx, rz);
                if (structure != null && structure.type == type) out.add(structure);
            }
        }
        return out;
    }

    private static String enchantText(ItemLoot item) {
        if (item.enchantmentSummary() != null && !item.enchantmentSummary().isBlank()) {
            return " [ENCH " + item.enchantmentSummary() + "]";
        }
        if (item.randomEnchantment()) {
            return " [ENCH " + item.enchantmentId() + " lvl" + item.enchantmentLevel() + "]";
        }
        return "";
    }

    private static ResourceKey<Structure> keyFor(StructureType type) {
        return switch (type) {
            case TRIAL_CHAMBER -> BuiltinStructures.TRIAL_CHAMBERS;
            case IGLOO -> BuiltinStructures.IGLOO;
            case MINESHAFT -> BuiltinStructures.MINESHAFT;
            case OCEAN_RUIN -> BuiltinStructures.OCEAN_RUIN_COLD;
            case RUINED_PORTAL -> BuiltinStructures.RUINED_PORTAL_STANDARD;
            case DESERT_PYRAMID -> BuiltinStructures.DESERT_PYRAMID;
            case OUTPOST -> BuiltinStructures.PILLAGER_OUTPOST;
            case END_CITY -> BuiltinStructures.END_CITY;
            case ANCIENT_CITY -> BuiltinStructures.ANCIENT_CITY;
            case BASTION -> BuiltinStructures.BASTION_REMNANT;
            case FORTRESS -> BuiltinStructures.FORTRESS;
            case MANSION -> BuiltinStructures.WOODLAND_MANSION;
            default -> throw new IllegalArgumentException("no key mapping for " + type);
        };
    }
}
