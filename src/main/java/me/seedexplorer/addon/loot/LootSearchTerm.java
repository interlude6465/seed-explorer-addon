package me.seedexplorer.addon.loot;

import java.util.Locale;

/** A normalized item or enchanted-book request used by the map loot search. */
public record LootSearchTerm(String itemId, String enchantment, int level, String display) {
    public String signature() {
        return itemId + "#" + (enchantment == null ? "" : enchantment) + "#" + level;
    }

    public boolean matches(ItemLoot item) {
        if (item == null || !itemId.equals(item.itemId())) return false;
        if (enchantment == null || enchantment.isBlank()) return true;

        String expected = normalize(enchantment);
        if (item.enchantmentId() != null) {
            String actual = normalize(item.enchantmentId());
            if (actual.equals(expected) && (level == 0 || item.enchantmentLevel() == level)) return true;
        }

        String summary = item.enchantmentSummary();
        if (summary == null || summary.isBlank()) return false;
        for (String part : summary.split(",")) {
            String normalized = normalize(part);
            String name = normalized.replaceFirst("\\s+(?:[0-9]+|i{1,4}|v|vi{0,3}|ix|x)$", "");
            if (!name.equals(expected)) continue;
            if (level == 0) return true;
            String levelText = normalized.substring(name.length()).trim();
            return levelText.equals(Integer.toString(level))
                || levelText.equals(roman(level).toLowerCase(Locale.ROOT));
        }
        return false;
    }

    private static String normalize(String value) {
        return value.replace("minecraft:", "").replace('_', ' ')
            .trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String roman(int value) {
        return switch (value) {
            case 1 -> "i";
            case 2 -> "ii";
            case 3 -> "iii";
            case 4 -> "iv";
            case 5 -> "v";
            case 6 -> "vi";
            case 7 -> "vii";
            case 8 -> "viii";
            case 9 -> "ix";
            case 10 -> "x";
            default -> Integer.toString(value);
        };
    }
}
