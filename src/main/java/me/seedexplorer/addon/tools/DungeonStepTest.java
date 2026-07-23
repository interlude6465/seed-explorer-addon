package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.LootTableDef;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.loot.VanillaLootTables;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.io.PrintStream;
import java.util.List;

public final class DungeonStepTest {
    public static void main(String[] args) {
        PrintStream out = System.out;
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            VanillaLootTables.loadAllFromBundled();
            run(args, out);
        } catch (Throwable t) {
            out.println("FAILED=" + t.getClass().getName() + " " + t.getMessage());
            t.printStackTrace(out);
            System.exit(1);
        }
    }

    private static void run(String[] args, PrintStream out) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 4717879387438598985L;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : -21;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : -370;
        int step = args.length > 3 ? Integer.parseInt(args[3]) : 3;
        out.println("seed=" + seed + " chunk=" + cx + "," + cz + " step=" + step);
        java.util.List<VanillaLootStructureSimulator.SimulatedContainer> chests =
            VanillaLootStructureSimulator.simulateStep(seed, cx, cz, step);
        out.println("chests_found=" + chests.size());
        for (VanillaLootStructureSimulator.SimulatedContainer chest : chests) {
            LootTableDef table = VanillaLootTables.get(chest.lootTableId());
            java.util.List<ItemLoot> items = table == null
                ? java.util.List.of()
                : LootTableSimulator.simulate(table, chest.lootSeed(), 64);
            out.println("  " + chest.x() + "," + chest.y() + "," + chest.z()
                + " table=" + chest.lootTableId() + " seed=" + chest.lootSeed());
            for (ItemLoot item : items) {
                out.println("    " + item.itemId() + " x" + item.minCount());
            }
        }
        // Scan a line of chunks to find dungeon chests
        for (int dx = -3; dx <= 3; dx++) {
            java.util.List<VanillaLootStructureSimulator.SimulatedContainer> found =
                VanillaLootStructureSimulator.simulateStep(seed, cx + dx, cz, step);
            if (!found.isEmpty()) {
                out.println("  FOUND dungeon at chunk " + (cx + dx) + "," + cz);
                for (var chest : found) {
                    out.println("    " + chest.x() + "," + chest.y() + "," + chest.z()
                        + " table=" + chest.lootTableId());
                }
            }
        }
    }
}
