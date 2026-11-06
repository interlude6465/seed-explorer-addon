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
        int radius = args.length > 3 ? Math.max(0, Integer.parseInt(args[3])) : 3;
        out.println("seed=" + seed + " chunk=" + cx + "," + cz);
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                var found = VanillaLootStructureSimulator.simulateDungeons(seed, cx + dx, cz + dz);
                if (!found.isEmpty()) {
                    out.println("  FOUND dungeon at chunk " + (cx + dx) + "," + (cz + dz));
                    for (var dungeon : found) {
                        out.println("    spawner=" + dungeon.x() + "," + dungeon.y() + "," + dungeon.z()
                            + " mob=" + dungeon.spawnerEntityId() + " chests=" + dungeon.containers().size());
                        for (var chest : dungeon.containers()) {
                            LootTableDef table = VanillaLootTables.get(chest.lootTableId());
                            List<ItemLoot> items = table == null ? List.of()
                                : LootTableSimulator.simulate(table, chest.lootSeed(), 64);
                            out.println("      chest=" + chest.x() + "," + chest.y() + "," + chest.z()
                                + " table=" + chest.lootTableId() + " seed=" + chest.lootSeed());
                            for (ItemLoot item : items) {
                                out.println("        " + item.itemId() + " x" + item.minCount());
                            }
                        }
                    }
                }
            }
        }
    }
}
