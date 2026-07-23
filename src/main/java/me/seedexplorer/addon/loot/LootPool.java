package me.seedexplorer.addon.loot;

import java.util.List;

public record LootPool(int minRolls, int maxRolls, List<ItemLoot> entries, float chance) {
    public LootPool(int minRolls, int maxRolls, List<ItemLoot> entries) {
        this(minRolls, maxRolls, entries, 1.0f);
    }

    public int totalWeight() {
        int total = 0;
        for (ItemLoot entry : entries) total += entry.weight();
        return total;
    }
}
