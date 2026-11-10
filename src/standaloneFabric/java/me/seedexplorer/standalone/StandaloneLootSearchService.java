package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ItemLoot;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Ranked, allocation-bounded search over already predicted chest contents. */
public final class StandaloneLootSearchService {
    private static final int MAX_RESULTS = 256;

    public List<Match> search(List<ChestLootOutput> chests, String text, int minimumQuantity,
                              BlockPos origin, int resultLimit) {
        String query = normalize(text);
        if (query.isBlank() || chests == null || chests.isEmpty()) return List.of();
        int required = Math.max(1, minimumQuantity);
        int limit = Math.max(1, Math.min(MAX_RESULTS, resultLimit));
        List<Match> matches = new ArrayList<>();

        for (ChestLootOutput chest : chests) {
            if (chest == null || chest.predictedItems() == null) continue;
            List<ItemLoot> items = new ArrayList<>();
            int quantity = 0;
            int score = 0;
            for (ItemLoot item : chest.predictedItems()) {
                int itemScore = matchScore(item, query);
                if (itemScore <= 0) continue;
                items.add(item);
                quantity += Math.max(0, item.minCount());
                score = Math.max(score, itemScore);
            }
            if (quantity < required) continue;
            long distanceSquared = distanceSquared(chest, origin);
            matches.add(new Match(chest, List.copyOf(items), quantity, score, distanceSquared));
        }

        matches.sort(Comparator.comparingInt(Match::score).reversed()
            .thenComparingLong(Match::distanceSquared)
            .thenComparingInt(match -> match.chest().blockX())
            .thenComparingInt(match -> match.chest().blockZ()));
        return List.copyOf(matches.subList(0, Math.min(limit, matches.size())));
    }

    private static int matchScore(ItemLoot item, String query) {
        if (item == null) return 0;
        String id = normalize(item.itemId());
        String path = id.startsWith("minecraft ") ? id.substring("minecraft ".length()) : id;
        String name = normalize(item.displayName());
        String enchantment = normalize(item.enchantmentId());
        String summary = normalize(item.enchantmentSummary());
        if (id.equals(query) || path.equals(query)) return 100;
        if (name.equals(query)) return 95;
        if (enchantment.equals(query) || summary.equals(query)) return 90;
        if (id.startsWith(query) || path.startsWith(query) || name.startsWith(query)) return 80;
        if (id.contains(query) || path.contains(query) || name.contains(query)) return 70;
        if (enchantment.contains(query) || summary.contains(query)) return 65;
        return 0;
    }

    private static long distanceSquared(ChestLootOutput chest, BlockPos origin) {
        if (origin == null) return 0L;
        long dx = (long) chest.blockX() - origin.getX();
        long dy = (long) chest.blockY() - origin.getY();
        long dz = (long) chest.blockZ() - origin.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    static String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT)
            .replace(':', ' ').replace('_', ' ').replace('-', ' ')
            .replaceAll("\\s+", " ");
    }

    public record Match(ChestLootOutput chest, List<ItemLoot> matchingItems, int quantity,
                        int score, long distanceSquared) {
    }
}
