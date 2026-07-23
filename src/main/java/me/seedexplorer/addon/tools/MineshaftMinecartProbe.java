package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

import java.io.PrintStream;
import java.util.List;

/** Probe: does the offline simulator capture mineshaft minecart chests? */
public final class MineshaftMinecartProbe {
    private static final PrintStream OUT = System.out;

    private MineshaftMinecartProbe() {}

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            OUT.println("[PROBE classloc] simulator=" + VanillaLootStructureSimulator.class
                .getProtectionDomain().getCodeSource().getLocation());
            OUT.println("[PROBE classloc] probe=" + MineshaftMinecartProbe.class
                .getProtectionDomain().getCodeSource().getLocation());
            long seed = args.length > 0 ? Long.parseLong(args[0]) : 2026071501L;
            int radius = 512;
            List<GeneratedStructure> overworld = VanillaStructurePredictor.predictDimension(
                seed, 0, -radius, -radius, radius, radius, true, true);
            List<GeneratedStructure> mineshafts = overworld.stream()
                .filter(s -> s.type == StructureType.MINESHAFT)
                .limit(8)
                .toList();
            OUT.println("seed=" + seed + " mineshafts_found=" + mineshafts.size());
            for (GeneratedStructure s : mineshafts) {
                var start = me.seedexplorer.addon.worldgen.WorldgenEngine
                    .generateSelectedStructureStart(seed, 0, BuiltinStructures.MINESHAFT,
                        new net.minecraft.world.level.ChunkPos(s.startChunkX, s.startChunkZ));
                OUT.println("  mineshaft start_chunk=" + s.startChunkX + "," + s.startChunkZ
                    + " at=" + s.x + "," + s.z
                    + " startValid=" + (start != null && start.isValid())
                    + " pieces=" + (start == null ? -1 : start.getPieces().size()));
                var containers = VanillaLootStructureSimulator.simulate(
                    seed, 0, BuiltinStructures.MINESHAFT, s.startChunkX, s.startChunkZ);
                OUT.println("    -> simulate containers=" + containers.size());
                for (var c : containers) {
                    var loot = me.seedexplorer.addon.loot.ChestLootPredictor.predictChest(
                        seed, c.x(), c.y(), c.z(), c.lootTableId(), c.lootSeed(), 27);
                    OUT.println("    container=" + c.x() + "," + c.y() + "," + c.z()
                        + " table=" + c.lootTableId() + " seed=" + c.lootSeed()
                        + " items=" + loot.size());
                    for (var item : loot) {
                        OUT.println("        " + item);
                    }
                }
            }
        } catch (Throwable t) {
            OUT.println("probe_failed=" + t.getClass().getName() + " " + t.getMessage());
            t.printStackTrace(OUT);
            System.exit(1);
        }
    }
}
