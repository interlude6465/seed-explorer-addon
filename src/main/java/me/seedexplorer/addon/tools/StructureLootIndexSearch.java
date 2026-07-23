package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.BundledLootTableLoader;
import me.seedexplorer.addon.loot.ChestLootPredictor;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.loot.VanillaLootTables;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.ChunkPos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

/** Searches decoration RNG indices by comparing simulated loot counts to an observed chest. */
public final class StructureLootIndexSearch {
    private StructureLootIndexSearch() {
    }

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            VanillaLootTables.loadAllForProfile(BundledLootTableLoader.runtimeProfile());

            String type = arg(args, 0, "TRIAL_CHAMBER");
            long seed = Long.parseLong(arg(args, 1, "-1124201383388859604"));
            int dim = Integer.parseInt(arg(args, 2, "0"));
            int startChunkX = Integer.parseInt(arg(args, 3, "-1"));
            int startChunkZ = Integer.parseInt(arg(args, 4, "19"));
            int chestX = Integer.parseInt(arg(args, 5, "-23"));
            int chestY = Integer.parseInt(arg(args, 6, "-11"));
            int chestZ = Integer.parseInt(arg(args, 7, "315"));
            String expectedRaw = arg(args, 8, "minecraft:arrow=14,minecraft:stick=4");
            int maxIndex = Integer.parseInt(arg(args, 9, "80"));

            ResourceKey<Structure> key = keyFor(type);
            Map<String, Integer> expected = parseCounts(expectedRaw);
            StringBuilder report = new StringBuilder();
            report.append("type=").append(type).append(" seed=").append(seed)
                .append(" dim=").append(dim)
                .append(" startChunk=").append(startChunkX).append(',').append(startChunkZ)
                .append(" chest=").append(chestX).append(',').append(chestY).append(',').append(chestZ)
                .append(" expected=").append(expectedRaw).append('\n');
            var start = me.seedexplorer.addon.worldgen.WorldgenEngine.generateSelectedStructureStart(
                seed, dim, key, new ChunkPos(startChunkX, startChunkZ));
            report.append("startValid=").append(start != null && start.isValid())
                .append(" pieces=").append(start == null ? -1 : start.getPieces().size()).append('\n');
            int placementChunkX = Math.floorDiv(chestX, 16);
            int placementChunkZ = Math.floorDiv(chestZ, 16);
            int matches = 0;
            for (int index = 0; index <= maxIndex; index++) {
                var containers = dim == 0 && start != null && start.isValid()
                    ? VanillaLootStructureSimulator.simulateStartChunk(seed, start, placementChunkX, placementChunkZ, index)
                    : VanillaLootStructureSimulator.simulate(seed, dim, key, startChunkX, startChunkZ, index);
                report.append("index=").append(index).append(" containers=").append(containers.size()).append('\n');
                for (var container : containers) {
                    if (container.x() != chestX || container.y() != chestY || container.z() != chestZ) continue;
                    Map<String, Integer> actual = predictedCounts(ChestLootPredictor.predictChest(
                        seed, chestX, chestY, chestZ, container.lootTableId(), container.lootSeed(), 64));
                    boolean match = actual.equals(expected);
                    report.append("  candidate table=").append(container.lootTableId())
                        .append(" seed=").append(container.lootSeed())
                        .append(" predicted=\"").append(formatCounts(actual)).append("\"")
                        .append(" match=").append(match).append('\n');
                    if (match) matches++;
                }
            }
            report.append("structure_loot_index_search_complete matches=").append(matches).append('\n');
            Path out = Path.of("build", "reports", "structure-loot-index-search.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, report.toString());
            System.err.println("wrote " + out.toAbsolutePath());
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static String arg(String[] args, int index, String fallback) {
        return args.length > index ? args[index] : fallback;
    }

    private static Map<String, Integer> parseCounts(String raw) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (raw == null || raw.isBlank() || raw.equals("empty")) return counts;
        for (String part : raw.split(",")) {
            String[] kv = part.trim().split("=");
            if (kv.length != 2) continue;
            counts.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
        }
        return counts;
    }

    private static Map<String, Integer> predictedCounts(java.util.List<ItemLoot> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemLoot item : items) {
            counts.merge(item.itemId(), Math.max(0, item.maxCount()), Integer::sum);
        }
        return counts;
    }

    private static String formatCounts(Map<String, Integer> counts) {
        if (counts.isEmpty()) return "empty";
        StringBuilder sb = new StringBuilder();
        counts.forEach((item, count) -> {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(item).append('=').append(count);
        });
        return sb.toString();
    }

    private static ResourceKey<Structure> keyFor(String type) {
        return switch (type.toUpperCase()) {
            case "TRIAL_CHAMBER" -> BuiltinStructures.TRIAL_CHAMBERS;
            case "BASTION" -> BuiltinStructures.BASTION_REMNANT;
            case "MANSION" -> BuiltinStructures.WOODLAND_MANSION;
            case "IGLOO" -> BuiltinStructures.IGLOO;
            case "SHIPWRECK" -> BuiltinStructures.SHIPWRECK;
            case "SHIPWRECK_BEACHED" -> BuiltinStructures.SHIPWRECK_BEACHED;
            case "RUINED_PORTAL" -> BuiltinStructures.RUINED_PORTAL_STANDARD;
            case "STRONGHOLD" -> BuiltinStructures.STRONGHOLD;
            case "MINESHAFT" -> BuiltinStructures.MINESHAFT;
            case "OUTPOST" -> BuiltinStructures.PILLAGER_OUTPOST;
            default -> throw new IllegalArgumentException("Unsupported structure type: " + type);
        };
    }
}
