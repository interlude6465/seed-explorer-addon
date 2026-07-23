package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.ChestLootPredictor;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

import java.util.List;

/** Targets one igloo start chunk directly. Args: seed chunkX chunkZ */
public final class IglooTargetProbe {
    private IglooTargetProbe() {}

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            long seed = Long.parseLong(args[0]);
            int cx = Integer.parseInt(args[1]);
            int cz = Integer.parseInt(args[2]);
            var containers = VanillaLootStructureSimulator.simulate(
                seed, 0, BuiltinStructures.IGLOO, cx, cz);
            System.out.println("seed=" + seed + " startChunk=" + cx + "," + cz
                + " containers=" + containers.size());
            for (var c : containers) {
                List<ItemLoot> loot = ChestLootPredictor.predictChest(
                    seed, c.x(), c.y(), c.z(), c.lootTableId(), c.lootSeed(), 27);
                System.out.println("  container=" + c.x() + "," + c.y() + "," + c.z()
                    + " table=" + c.lootTableId() + " seed=" + c.lootSeed()
                    + " items=" + loot.size());
                for (ItemLoot item : loot) {
                    System.out.println("      " + item.minCount() + "-" + item.maxCount()
                        + "x " + item.itemId());
                }
            }
        } catch (Throwable t) {
            System.out.println("probe_failed=" + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }
}
