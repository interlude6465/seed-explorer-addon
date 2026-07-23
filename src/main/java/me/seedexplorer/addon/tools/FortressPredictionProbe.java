package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.LootTableDef;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.loot.VanillaLootTables;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class FortressPredictionProbe {
    private FortressPredictionProbe() {
    }

    public static void main(String[] args) {
        PrintStream out = System.out;
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            VanillaLootTables.loadAllFromBundled();
            run(args, out);
        } catch (Throwable t) {
            out.println("fortress_probe_failed=" + t.getClass().getName() + " " + t.getMessage());
            t.printStackTrace(out);
            System.exit(1);
        }
    }

    private static void run(String[] args, PrintStream out) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : -1124201383388859604L;
        int centerChunkX = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int centerChunkZ = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int radiusChunks = args.length > 3 ? Integer.parseInt(args[3]) : 256;
        int limit = args.length > 4 ? Integer.parseInt(args[4]) : 8;
        int targetX = args.length > 5 ? Integer.parseInt(args[5]) : 207;
        int targetY = args.length > 6 ? Integer.parseInt(args[6]) : 57;
        int targetZ = args.length > 7 ? Integer.parseInt(args[7]) : 307;
        Integer directChunkX = args.length > 8 && !args[8].isBlank() ? Integer.parseInt(args[8]) : null;
        Integer directChunkZ = args.length > 9 && !args[9].isBlank() ? Integer.parseInt(args[9]) : null;
        boolean bruteForceIndices = args.length > 10 && Boolean.parseBoolean(args[10]);

        out.println("fortress_probe seed=" + seed
            + " centerChunk=" + centerChunkX + "," + centerChunkZ
            + " radiusChunks=" + radiusChunks
            + " limit=" + limit
            + " decorationIndex=" + VanillaLootStructureSimulator.decorationIndex(BuiltinStructures.FORTRESS));

        if (directChunkX != null && directChunkZ != null) {
            GeneratedStructure structure = new GeneratedStructure(
                directChunkX * 16, directChunkZ * 16, directChunkX, directChunkZ,
                StructureType.FORTRESS, "", false);
            out.println("direct_startChunk=" + directChunkX + "," + directChunkZ);
            printFortress(seed, structure, targetX, targetY, targetZ, out, 0, bruteForceIndices);
            return;
        }

        List<GeneratedStructure> fortresses = VanillaStructurePredictor.predictNether(
                seed,
                centerChunkX - radiusChunks,
                centerChunkZ - radiusChunks,
                centerChunkX + radiusChunks,
                centerChunkZ + radiusChunks
            ).stream()
            .filter(s -> s.type == StructureType.FORTRESS)
            .sorted(Comparator.comparingLong(s -> distanceSquared(centerChunkX * 16, centerChunkZ * 16, s.x, s.z)))
            .limit(limit)
            .toList();

        out.println("fortresses=" + fortresses.size());
        for (int i = 0; i < fortresses.size(); i++) {
            printFortress(seed, fortresses.get(i), targetX, targetY, targetZ, out, i, bruteForceIndices);
        }
    }

    private static void printFortress(long seed, GeneratedStructure structure,
                                      int targetX, int targetY, int targetZ,
                                      PrintStream out, int index, boolean bruteForceIndices) {
        out.println();
        out.println("fortress[" + index + "] block=" + structure.x + "," + structure.z
            + " startChunk=" + structure.startChunkX + "," + structure.startChunkZ);
        List<VanillaLootStructureSimulator.SimulatedContainer> chests =
            VanillaLootStructureSimulator.simulate(seed, -1, BuiltinStructures.FORTRESS,
                structure.startChunkX, structure.startChunkZ);
        out.println("containers=" + chests.size());
        for (int c = 0; c < chests.size(); c++) {
            var chest = chests.get(c);
            out.println("  chest[" + c + "]=" + chest.x() + "," + chest.y() + "," + chest.z()
                + " table=" + chest.lootTableId()
                + " lootSeed=" + chest.lootSeed()
                + " counts=\"" + countSummary(chest.lootTableId(), chest.lootSeed()) + "\"");
        }
        if (bruteForceIndices
            && chests.stream().anyMatch(chest -> chest.x() == targetX && chest.y() == targetY && chest.z() == targetZ)) {
            bruteForceIndex(seed, structure, targetX, targetY, targetZ, out);
        }
    }

    private static void bruteForceIndex(long seed, GeneratedStructure structure, int targetX, int targetY, int targetZ,
                                        PrintStream out) {
        out.println("  brute_force_indices target=" + targetX + "," + targetY + "," + targetZ + " range=0..96");
        for (int idx = 0; idx <= 96; idx++) {
            List<VanillaLootStructureSimulator.SimulatedContainer> chests =
                VanillaLootStructureSimulator.simulate(seed, -1, BuiltinStructures.FORTRESS,
                    structure.startChunkX, structure.startChunkZ, idx);
            for (var chest : chests) {
                if (chest.x() != targetX || chest.y() != targetY || chest.z() != targetZ) continue;
                String counts = countSummary(chest.lootTableId(), chest.lootSeed());
                boolean matchesObserved = counts.equals("2xminecraft:gold_ingot, 1xminecraft:golden_chestplate")
                    || counts.equals("1xminecraft:golden_chestplate, 2xminecraft:gold_ingot");
                out.println("    idx=" + idx
                    + " lootSeed=" + chest.lootSeed()
                    + " counts=\"" + counts + "\""
                    + " matchesObserved=" + matchesObserved);
            }
        }
    }

    private static String countSummary(String tableId, long lootSeed) {
        LootTableDef table = VanillaLootTables.get(tableId);
        if (table == null) return "missing-table";
        Map<String, Integer> counts = new TreeMap<>();
        for (ItemLoot item : LootTableSimulator.simulate(table, lootSeed, 64)) {
            if (item.itemId() == null || item.itemId().isBlank()) continue;
            counts.merge(item.itemId(), Math.max(1, item.maxCount()), Integer::sum);
        }
        if (counts.isEmpty()) return "empty";
        StringBuilder sb = new StringBuilder();
        counts.forEach((item, count) -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(count).append('x').append(item);
        });
        return sb.toString();
    }

    private static long distanceSquared(int ax, int az, int bx, int bz) {
        long dx = (long) ax - bx;
        long dz = (long) az - bz;
        return dx * dx + dz * dz;
    }
}
