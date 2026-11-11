package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.ChestLootOutput;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/** Coordinate-deduplicated remembered chest state shared by loot publication paths. */
final class StandaloneRememberedLoot {
    private StandaloneRememberedLoot() { }

    static List<ChestLootOutput> remember(List<ChestLootOutput> current,
                                           List<ChestLootOutput> incoming, int limit) {
        LinkedHashMap<BlockPos, ChestLootOutput> byPosition = new LinkedHashMap<>();
        for (ChestLootOutput chest : current) byPosition.put(position(chest), chest);
        for (ChestLootOutput chest : incoming) byPosition.put(position(chest), chest);
        while (byPosition.size() > limit) byPosition.remove(byPosition.keySet().iterator().next());
        return List.copyOf(byPosition.values());
    }

    static List<ChestLootOutput> replace(List<ChestLootOutput> current,
                                          List<ChestLootOutput> canonical,
                                          List<ChestLootOutput> previous,
                                          List<ChestLootOutput> replacement, int limit) {
        Set<BlockPos> removed = new HashSet<>();
        addPositions(removed, canonical);
        addPositions(removed, previous);
        addPositions(removed, replacement);
        List<ChestLootOutput> retained = new ArrayList<>();
        for (ChestLootOutput chest : current) if (!removed.contains(position(chest))) retained.add(chest);
        return remember(retained, replacement, limit);
    }

    private static void addPositions(Set<BlockPos> positions, List<ChestLootOutput> chests) {
        for (ChestLootOutput chest : chests) positions.add(position(chest));
    }

    private static BlockPos position(ChestLootOutput chest) {
        return new BlockPos(chest.blockX(), chest.blockY(), chest.blockZ());
    }
}
