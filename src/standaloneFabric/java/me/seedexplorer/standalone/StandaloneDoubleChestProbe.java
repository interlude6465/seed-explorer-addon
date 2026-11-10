package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.StandaloneStructureLootPredictor;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;

import java.util.List;

/** Regression checks for shared-inventory grouping without launching a client. */
public final class StandaloneDoubleChestProbe {
    private StandaloneDoubleChestProbe() {
    }

    public static void verify() {
        var left = container(10, 64, 20, "minecraft:chests/village_house", 1L, true);
        var right = container(11, 64, 20, "minecraft:chests/village_house", 2L, true);
        var paired = StandaloneStructureLootPredictor.sharedChestInventories(List.of(left, right));
        require(paired.size() == 1 && paired.getFirst().size() == 2,
            "a captured double chest was not grouped as one inventory");

        var firstSingle = container(10, 64, 20, "minecraft:chests/village_house", 3L, false);
        var adjacentSingle = container(11, 64, 20, "minecraft:chests/village_house", 4L, false);
        var independent = StandaloneStructureLootPredictor.sharedChestInventories(
            List.of(firstSingle, adjacentSingle));
        require(independent.size() == 2
                && independent.stream().allMatch(inventory -> inventory.size() == 1),
            "adjacent independent chests were incorrectly merged");

        var otherTable = container(11, 64, 20, "minecraft:chests/village_toolsmith", 5L, true);
        require(StandaloneStructureLootPredictor.sharedChestInventories(
                List.of(left, otherTable)).size() == 2,
            "adjacent paired flags with different loot tables were incorrectly merged");

        var distant = container(12, 64, 20, "minecraft:chests/village_house", 6L, true);
        require(StandaloneStructureLootPredictor.sharedChestInventories(
                List.of(left, distant)).size() == 2,
            "non-adjacent paired flags were incorrectly merged");

        ItemLoot rangeOne = new ItemLoot("minecraft:book", 1, 3, 7, "Book",
            true, "minecraft:mending", 1, null, false, 0, 0, null, false);
        ItemLoot rangeTwo = new ItemLoot("minecraft:book", 2, 5, 7, "Book",
            true, "minecraft:mending", 1, null, false, 0, 0, null, false);
        ItemLoot otherLevel = new ItemLoot("minecraft:book", 1, 1, 7, "Book",
            true, "minecraft:mending", 2, null, false, 0, 0, null, false);
        ItemLoot damageRoll = new ItemLoot("minecraft:book", 1, 1, 7, "Book",
            true, "minecraft:mending", 1, null, false, 0, 0, null, true);
        List<ItemLoot> merged = StandaloneStructureLootPredictor.mergeEquivalentItems(
            List.of(rangeOne, rangeTwo, otherLevel, damageRoll));
        require(merged.size() == 3, "different enchantment/damage metadata was merged");
        ItemLoot combined = merged.getFirst();
        require(combined.minCount() == 3 && combined.maxCount() == 8,
            "merged fallback count range was collapsed or summed incorrectly");
        require(combined.enchantmentLevel() == 1 && !combined.hasDamageRoll(),
            "merged item metadata was not retained");
    }

    private static VanillaLootStructureSimulator.SimulatedContainer container(
        int x, int y, int z, String table, long seed, boolean paired) {
        return new VanillaLootStructureSimulator.SimulatedContainer(
            x, y, z, table, seed, paired);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
