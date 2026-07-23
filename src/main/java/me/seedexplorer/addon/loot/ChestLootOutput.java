package me.seedexplorer.addon.loot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ChestLootOutput(int blockX, int blockY, int blockZ, String lootTableId,
                              List<ItemLoot> predictedItems, long seed, boolean exact) {
    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    /** Backward-compatible constructor; treats the prediction as vanilla-exact. */
    public ChestLootOutput(int blockX, int blockY, int blockZ, String lootTableId,
                           List<ItemLoot> predictedItems, long seed) {
        this(blockX, blockY, blockZ, lootTableId, predictedItems, seed, true);
    }

    public boolean isEmpty() {
        return predictedItems == null || predictedItems.isEmpty();
    }

    public String summary() {
        if (isEmpty()) return "Empty (no prediction)";
        StringBuilder sb = new StringBuilder();
        for (ItemLoot item : predictedItems) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(item.displayName());
            if (item.enchantmentSummary() != null && !item.enchantmentSummary().isBlank()) {
                sb.append(" [").append(item.enchantmentSummary()).append("]");
            } else if (item.enchantmentId() != null) {
                String shortId = item.enchantmentId().contains(":")
                    ? item.enchantmentId().substring(item.enchantmentId().indexOf(':') + 1)
                    : item.enchantmentId();
                shortId = shortId.replace('_', ' ');
                String level = item.enchantmentLevel() > 0 && item.enchantmentLevel() <= ROMAN.length
                    ? ROMAN[item.enchantmentLevel() - 1] : String.valueOf(item.enchantmentLevel());
                sb.append(" [").append(shortId).append(" ").append(level).append("]");
            }
            if (item.maxCount() > 1 || item.minCount() > 1) {
                sb.append(" x").append(item.minCount());
                if (item.maxCount() > item.minCount()) sb.append("-").append(item.maxCount());
            }
        }
        return sb.toString();
    }

    public Map<String, Integer> predictedCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (predictedItems == null) return counts;
        for (ItemLoot item : predictedItems) {
            counts.merge(item.itemId(), Math.max(1, item.maxCount()), Integer::sum);
        }
        return counts;
    }

    public String diffSummary(Map<String, Integer> actualCounts) {
        Map<String, Integer> predicted = predictedCounts();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : predicted.entrySet()) {
            int actual = actualCounts == null ? 0 : actualCounts.getOrDefault(entry.getKey(), 0);
            int delta = actual - entry.getValue();
            if (delta != 0) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(delta > 0 ? "+" : "").append(delta).append(" ").append(entry.getKey());
            }
        }
        if (actualCounts != null) {
            for (Map.Entry<String, Integer> entry : actualCounts.entrySet()) {
                if (predicted.containsKey(entry.getKey())) continue;
                if (!sb.isEmpty()) sb.append(", ");
                sb.append("+").append(entry.getValue()).append(" ").append(entry.getKey());
            }
        }
        return sb.isEmpty() ? "match" : sb.toString();
    }
}
