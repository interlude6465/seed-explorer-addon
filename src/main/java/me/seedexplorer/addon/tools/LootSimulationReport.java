package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.LootTableDef;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.loot.VanillaLootTables;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.List;
import java.io.PrintStream;

/** Offline smoke/differential harness for the vanilla-backed loot simulation. */
public final class LootSimulationReport {
    private static final PrintStream OUTPUT = System.out;
    private static final PrintStream DIAGNOSTICS = System.err;

    private LootSimulationReport() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable throwable) {
            DIAGNOSTICS.println("loot_simulation_failed=" + throwable.getClass().getName()
                + " message=" + throwable.getMessage());
            for (StackTraceElement element : throwable.getStackTrace()) {
                DIAGNOSTICS.println("  at " + element);
            }
            Throwable cause = throwable.getCause();
            while (cause != null) {
                DIAGNOSTICS.println("caused_by=" + cause.getClass().getName() + " message=" + cause.getMessage());
                for (StackTraceElement element : cause.getStackTrace()) DIAGNOSTICS.println("  at " + element);
                cause = cause.getCause();
            }
            System.exit(1);
        }
    }

    private static void run(String[] args) {
        OUTPUT.println("loot_simulation_stage=bootstrap");
        OUTPUT.flush();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        VanillaLootTables.loadAllFromBundled();
        OUTPUT.println("loot_simulation_stage=worldgen");
        OUTPUT.flush();
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 4717879387438598985L;
        if (args.length >= 3) {
            int cx = Integer.parseInt(args[1]);
            int cz = Integer.parseInt(args[2]);
            var containers = VanillaLootStructureSimulator.desertPyramid(seed, cx, cz);
            report(seed, cx, cz, containers);
            return;
        }

        Holder<StructureSet> holder = WorldgenEngine.vanillaLookup()
            .lookupOrThrow(Registries.STRUCTURE_SET)
            .getOrThrow(BuiltinStructureSets.DESERT_PYRAMIDS);
        RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) holder.value().placement();
        int spacing = placement.spacing();

        for (int radius = 0; radius <= 32; radius++) {
            for (int rz = -radius; rz <= radius; rz++) {
                for (int rx = -radius; rx <= radius; rx++) {
                    if (Math.max(Math.abs(rx), Math.abs(rz)) != radius) continue;
                    ChunkPos chunk = placement.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    List<VanillaLootStructureSimulator.SimulatedContainer> containers =
                        VanillaLootStructureSimulator.desertPyramid(seed, chunk.x(), chunk.z());
                    if (!containers.isEmpty()) {
                        report(seed, chunk.x(), chunk.z(), containers);
                        return;
                    }
                }
            }
        }
        throw new IllegalStateException("No valid desert pyramid found in the search radius");
    }

    private static void report(long seed, int chunkX, int chunkZ,
                                List<VanillaLootStructureSimulator.SimulatedContainer> containers) {
        OUTPUT.println("seed=" + seed + " structure_chunk=" + chunkX + "," + chunkZ
            + " containers=" + containers.size());
        for (VanillaLootStructureSimulator.SimulatedContainer container : containers) {
            LootTableDef table = VanillaLootTables.get(container.lootTableId());
            List<ItemLoot> items = table == null
                ? List.of()
                : LootTableSimulator.simulate(table, container.lootSeed(), 64);
            OUTPUT.println("chest=" + container.x() + "," + container.y() + "," + container.z()
                + " table=" + container.lootTableId() + " loot_seed=" + container.lootSeed());
            for (ItemLoot item : items) {
                OUTPUT.println("  " + item.itemId() + " x" + item.minCount());
            }
        }
    }
}
