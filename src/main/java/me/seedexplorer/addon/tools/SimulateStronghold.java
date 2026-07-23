package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.LootTableDef;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.loot.VanillaLootTables;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

import java.io.PrintStream;
import java.util.List;

public final class SimulateStronghold {
    public static void main(String[] args) {
        PrintStream out = System.out;
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            VanillaLootTables.loadAllFromBundled();
            run(args, out);
        } catch (Throwable t) {
            out.println("simulate_stronghold_failed=" + t.getClass().getName() + " " + t.getMessage());
            t.printStackTrace(out);
            System.exit(1);
        }
    }

    private static void run(String[] args, PrintStream out) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 0;
        int chunkX = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MIN_VALUE;
        int chunkZ = args.length > 2 ? Integer.parseInt(args[2]) : Integer.MIN_VALUE;

        if (chunkX != Integer.MIN_VALUE) {
            if (args.length > 3) {
                if (args[3].equals("shipwreck")) {
                    simulateShipwreck(seed, chunkX, chunkZ, out);
                    return;
                }
                if (args[3].equals("mineshaft")) {
                    simulateMineshaft(seed, chunkX, chunkZ, out);
                    return;
                }
                if (args[3].equals("treasure")) {
                    simulateTreasure(seed, chunkX, chunkZ, out);
                    return;
                }
            }
            simulateOne(seed, chunkX, chunkZ, out);
            return;
        }

        out.println("seed=" + seed + " discovering_strongholds");
        List<ChunkPos> positions = WorldgenEngine.ringPositions(seed, 0, BuiltinStructureSets.STRONGHOLDS);
        if (positions == null || positions.isEmpty()) {
            out.println("no_stronghold_positions");
            return;
        }
        out.println("stronghold_positions=" + positions.size());
        for (ChunkPos pos : positions) {
            out.println("  ring=" + pos.x() + "," + pos.z());
        }
        for (int i = 0; i < Math.min(20, positions.size()); i++) {
            ChunkPos pos = positions.get(i);
            var start = WorldgenEngine.generateStructureStart(
                seed, 0, BuiltinStructures.STRONGHOLD, pos);
            if (start.isValid()) {
                out.println("valid_start idx=" + i + " chunk=" + pos.x() + "," + pos.z()
                    + " pieces=" + start.getPieces().size());
                simulateOne(seed, pos.x(), pos.z(), out);
                return;
            } else {
                out.println("invalid_start idx=" + i + " chunk=" + pos.x() + "," + pos.z());
            }
        }
        out.println("no_valid_stronghold_start_found");
    }

    private static void simulateOne(long seed, int chunkX, int chunkZ, PrintStream out) {
        out.println("--- stronghold chunk=" + chunkX + "," + chunkZ);
        var start = WorldgenEngine.generateStructureStart(
            seed, 0, BuiltinStructures.STRONGHOLD, new ChunkPos(chunkX, chunkZ));
        out.println("pieces=" + (start.isValid() ? start.getPieces().size() : 0));
        List<VanillaLootStructureSimulator.SimulatedContainer> chests =
            VanillaLootStructureSimulator.simulate(seed, BuiltinStructures.STRONGHOLD, chunkX, chunkZ);
        out.println("containers=" + chests.size());
        for (var chest : chests) {
            LootTableDef table = VanillaLootTables.get(chest.lootTableId());
            List<ItemLoot> items = table == null
                ? List.of()
                : LootTableSimulator.simulate(table, chest.lootSeed(), 64);
            out.println("chest=" + chest.x() + "," + chest.y() + "," + chest.z()
                + " table=" + chest.lootTableId() + " seed=" + chest.lootSeed());
            for (ItemLoot item : items) {
                out.println("  " + item.itemId() + " x" + item.minCount());
            }
        }
    }

    private static void simulateMineshaft(long seed, int chunkX, int chunkZ, PrintStream out) {
        out.println("--- mineshaft chunk=" + chunkX + "," + chunkZ);
        var start = WorldgenEngine.generateStructureStart(
            seed, 0, BuiltinStructures.MINESHAFT, new ChunkPos(chunkX, chunkZ));
        out.println("start_valid=" + start.isValid() + " pieces="
            + (start.isValid() ? start.getPieces().size() : 0));
        if (!start.isValid()) {
            out.println("Structure start invalid");
            return;
        }
        var chests = VanillaLootStructureSimulator.simulate(seed, BuiltinStructures.MINESHAFT, chunkX, chunkZ);
        out.println("containers=" + chests.size());
        for (var chest : chests) {
            LootTableDef table = VanillaLootTables.get(chest.lootTableId());
            List<ItemLoot> items = table == null
                ? List.of()
                : LootTableSimulator.simulate(table, chest.lootSeed(), 64);
            out.println("chest=" + chest.x() + "," + chest.y() + "," + chest.z()
                + " table=" + chest.lootTableId() + " seed=" + chest.lootSeed());
            for (ItemLoot item : items) {
                out.println("  " + item.itemId() + " x" + item.minCount());
            }
        }
        out.println("trying indices 0..50 for mineshaft (full brute-force)...");
        for (int idx = 0; idx <= 50; idx++) {
            var c2 = VanillaLootStructureSimulator.simulateChunk(
                seed, BuiltinStructures.MINESHAFT, chunkX, chunkZ, chunkX, chunkZ, idx);
            if (!c2.isEmpty()) {
                out.println("INDEX " + idx + " produced " + c2.size() + " containers:");
                for (var chest : c2) {
                    out.println("  chest=" + chest.x() + "," + chest.y() + "," + chest.z()
                        + " table=" + chest.lootTableId() + " seed=" + chest.lootSeed());
                }
            }
        }
        out.println("brute-force complete");
    }

    private static void simulateTreasure(long seed, int chunkX, int chunkZ, PrintStream out) {
        out.println("--- buried_treasure chunk=" + chunkX + "," + chunkZ);
        var start = WorldgenEngine.generateStructureStart(
            seed, 0, BuiltinStructures.BURIED_TREASURE, new ChunkPos(chunkX, chunkZ));
        out.println("start_valid=" + start.isValid() + " pieces="
            + (start.isValid() ? start.getPieces().size() : 0));
        if (!start.isValid()) {
            out.println("Structure start invalid");
            return;
        }
        out.println("brute-forcing decoration index 0..50 for buried_treasure...");
        for (int idx = 0; idx <= 50; idx++) {
            var c2 = VanillaLootStructureSimulator.simulate(
                seed, 0, BuiltinStructures.BURIED_TREASURE, chunkX, chunkZ, idx);
            if (!c2.isEmpty()) {
                out.println("INDEX " + idx + " produced " + c2.size() + " containers:");
                for (var chest : c2) {
                    out.println("  chest=" + chest.x() + "," + chest.y() + "," + chest.z()
                        + " table=" + chest.lootTableId() + " seed=" + chest.lootSeed());
                }
            }
        }
        out.println("brute-force complete");
    }

    private static void simulateShipwreck(long seed, int chunkX, int chunkZ, PrintStream out) {
        out.println("--- shipwreck chunk=" + chunkX + "," + chunkZ);
        out.println("brute-forcing decoration index 0..50 to match oracle seeds...");
        // Oracle targets: shipwreck 1 at chunk (1,-44) has these chests
        long[] oracleSeeds = {4669758454572194363L, -7834760092081062974L, 5201495591131938868L};
        for (int idx = 0; idx <= 50; idx++) {
            var c2 = VanillaLootStructureSimulator.simulate(
                seed, 0, BuiltinStructures.SHIPWRECK, chunkX, chunkZ, idx);
            if (!c2.isEmpty()) {
                // Check if ALL three oracle seeds match
                java.util.Set<Long> simSeeds = new java.util.HashSet<>();
                for (var chest : c2) {
                    simSeeds.add(chest.lootSeed());
                }
                boolean allMatch = simSeeds.size() == 3;
                for (long os : oracleSeeds) {
                    if (!simSeeds.contains(os)) { allMatch = false; break; }
                }
                out.println("INDEX " + idx + " produced " + c2.size() + " containers matches_oracle=" + allMatch);
                for (var chest : c2) {
                    out.println("  chest=" + chest.x() + "," + chest.y() + "," + chest.z()
                        + " table=" + chest.lootTableId() + " seed=" + chest.lootSeed());
                }
                if (allMatch) {
                    out.println("*** MATCH FOUND at index " + idx + " ***");
                }
            }
        }
        out.println("brute-force complete");
    }
}
