package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

/** Searches the vanilla structure-decoration RNG index against a Paper chest seed. */
public final class StrongholdDecorationIndexSearch {
    private StrongholdDecorationIndexSearch() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 0L;
        int startChunkX = args.length > 1 ? Integer.parseInt(args[1]) : 125;
        int startChunkZ = args.length > 2 ? Integer.parseInt(args[2]) : 57;
        int chestX = args.length > 3 ? Integer.parseInt(args[3]) : 1964;
        int chestY = args.length > 4 ? Integer.parseInt(args[4]) : 28;
        int chestZ = args.length > 5 ? Integer.parseInt(args[5]) : 894;
        long expectedSeed = args.length > 6
            ? Long.parseLong(args[6]) : -393332197303699418L;
        int minIndex = args.length > 7 ? Integer.parseInt(args[7]) : 0;
        int maxIndex = args.length > 8 ? Integer.parseInt(args[8]) : 64;
        int placementChunkX = Math.floorDiv(chestX, 16);
        int placementChunkZ = Math.floorDiv(chestZ, 16);

        int matches = 0;
        for (int index = minIndex; index <= maxIndex; index++) {
            var containers = VanillaLootStructureSimulator.simulateChunk(
                seed, BuiltinStructures.STRONGHOLD, startChunkX, startChunkZ,
                placementChunkX, placementChunkZ, index);
            for (var container : containers) {
                if (container.x() != chestX || container.y() != chestY || container.z() != chestZ) continue;
                if (container.lootSeed() == expectedSeed) {
                    System.out.println("stronghold_decoration_index_match=" + index);
                    matches++;
                }
            }
        }
        System.out.println("stronghold_decoration_index_search_complete=true matches=" + matches
            + " range=" + minIndex + ".." + maxIndex);
        if (matches != 1) System.exit(2);
    }
}
