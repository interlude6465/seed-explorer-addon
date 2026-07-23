package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.BundledLootTableLoader;
import me.seedexplorer.addon.loot.ChestLootPredictor;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.loot.VanillaLootTables;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Locates the nearest structure of a given type to a target block on a seed, then sweeps
 * decoration indices comparing each simulated chest's loot to observed item counts.
 * Unlike {@link StructureLootIndexSearch} this auto-locates the structure start, so it can be
 * pointed at a seed + a chest observed in-game (e.g. from the teleport loot test log) without
 * a pre-captured Paper oracle. Reports which index (if any) reproduces the observed loot.
 */
public final class LocateAndIndexSweep {
    private LocateAndIndexSweep() {
    }

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            VanillaLootTables.loadAllForProfile(BundledLootTableLoader.runtimeProfile());

            String typeName = arg(args, 0, "MANSION");
            long seed = Long.parseLong(arg(args, 1, "-1124201383388859604"));
            int dim = Integer.parseInt(arg(args, 2, "0"));
            int chestX = Integer.parseInt(arg(args, 3, "-2486"));
            int chestY = Integer.parseInt(arg(args, 4, "73"));
            int chestZ = Integer.parseInt(arg(args, 5, "807"));
            String expectedRaw = arg(args, 6,
                "minecraft:gunpowder=6,minecraft:resin_clump=2,minecraft:diamond_hoe=1,minecraft:string=9,minecraft:vex_armor_trim_smithing_template=1,minecraft:music_disc_cat=1");
            int maxIndex = Integer.parseInt(arg(args, 7, "40"));
            int searchRadiusChunks = Integer.parseInt(arg(args, 8, "48"));

            StructureType type = StructureType.valueOf(typeName.toUpperCase());
            Map<String, Integer> expected = parseCounts(expectedRaw);

            StringBuilder report = new StringBuilder();
            report.append("type=").append(type).append(" seed=").append(seed)
                .append(" dim=").append(dim)
                .append(" chest=").append(chestX).append(',').append(chestY).append(',').append(chestZ)
                .append(" expected=").append(expectedRaw).append('\n');

            // Locate the nearest matching structure to the chest.
            int centerChunkX = Math.floorDiv(chestX, 16);
            int centerChunkZ = Math.floorDiv(chestZ, 16);
            List<GeneratedStructure> found = VanillaStructurePredictor.predictDimension(
                seed, dim,
                centerChunkX - searchRadiusChunks, centerChunkZ - searchRadiusChunks,
                centerChunkX + searchRadiusChunks, centerChunkZ + searchRadiusChunks,
                true, true);
            GeneratedStructure best = null;
            long bestDist = Long.MAX_VALUE;
            for (GeneratedStructure s : found) {
                if (s.type != type) continue;
                long dx = (long) s.x - chestX;
                long dz = (long) s.z - chestZ;
                long d = dx * dx + dz * dz;
                if (d < bestDist) {
                    bestDist = d;
                    best = s;
                }
            }
            if (best == null) {
                report.append("LOCATE_FAILED: no ").append(type).append(" found within ")
                    .append(searchRadiusChunks).append(" chunks of chest\n");
                write(report);
                return;
            }
            report.append("located block=").append(best.x).append(',').append(best.z)
                .append(" startChunk=").append(best.startChunkX).append(',').append(best.startChunkZ)
                .append(" variant=").append(best.variant).append('\n');

            int matches = 0;
            for (int index = 0; index <= maxIndex; index++) {
                List<VanillaLootStructureSimulator.SimulatedContainer> containers =
                    VanillaLootStructureSimulator.simulate(seed, dim,
                        keyFor(type),
                        best.startChunkX, best.startChunkZ, index);
                report.append("index=").append(index).append(" containers=").append(containers.size()).append('\n');
                for (var c : containers) {
                    if (c.x() != chestX || c.y() != chestY || c.z() != chestZ) continue;
                    Map<String, Integer> actual = predictedCounts(ChestLootPredictor.predictChest(
                        seed, chestX, chestY, chestZ, c.lootTableId(), c.lootSeed(), 64));
                    boolean match = actual.equals(expected);
                    report.append("  candidate table=").append(c.lootTableId())
                        .append(" seed=").append(c.lootSeed())
                        .append(" predicted=\"").append(formatCounts(actual)).append('"')
                        .append(" match=").append(match).append('\n');
                    if (match) matches++;
                }
            }
            report.append("locate_and_index_sweep_complete matches=").append(matches).append('\n');
            write(report);
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void write(StringBuilder report) throws Exception {
        Path out = Path.of("build", "reports", "locate-and-index-sweep.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        System.err.println("wrote " + out.toAbsolutePath());
        System.err.println(report);
    }

    private static String arg(String[] args, int index, String fallback) {
        return args.length > index ? args[index] : fallback;
    }

    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.structure.Structure> keyFor(StructureType type) {
        return switch (type) {
            case MANSION -> net.minecraft.world.level.levelgen.structure.BuiltinStructures.WOODLAND_MANSION;
            case IGLOO -> net.minecraft.world.level.levelgen.structure.BuiltinStructures.IGLOO;
            case ANCIENT_CITY -> net.minecraft.world.level.levelgen.structure.BuiltinStructures.ANCIENT_CITY;
            case TRIAL_CHAMBER -> net.minecraft.world.level.levelgen.structure.BuiltinStructures.TRIAL_CHAMBERS;
            case BASTION -> net.minecraft.world.level.levelgen.structure.BuiltinStructures.BASTION_REMNANT;
            case DESERT_PYRAMID -> net.minecraft.world.level.levelgen.structure.BuiltinStructures.DESERT_PYRAMID;
            case JUNGLE_TEMPLE -> net.minecraft.world.level.levelgen.structure.BuiltinStructures.JUNGLE_TEMPLE;
            case OUTPOST -> net.minecraft.world.level.levelgen.structure.BuiltinStructures.PILLAGER_OUTPOST;
            case RUINED_PORTAL -> net.minecraft.world.level.levelgen.structure.BuiltinStructures.RUINED_PORTAL_STANDARD;
            default -> throw new IllegalArgumentException("keyFor unsupported: " + type);
        };
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

    private static Map<String, Integer> predictedCounts(List<ItemLoot> items) {
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
}
