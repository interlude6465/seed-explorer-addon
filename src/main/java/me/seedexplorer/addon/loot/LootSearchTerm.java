package me.seedexplorer.addon.loot;

import java.util.Locale;

/** A normalized item or enchanted-book request used by the map loot search. */
public record LootSearchTerm(String itemId, String enchantment, int level, String display,
                             java.util.List<Property> properties) {
    public record Property(String enchantment, int level) {
        public Property { enchantment=enchantment.startsWith("minecraft:")?enchantment:"minecraft:"+enchantment; }
    }
    public LootSearchTerm(String itemId,String enchantment,int level,String display) {
        this(itemId,enchantment,level,display,enchantment==null || enchantment.isBlank()
            ?java.util.List.of():java.util.List.of(new Property(enchantment,level)));
    }
    public LootSearchTerm { properties=properties.stream().sorted(java.util.Comparator.comparing(Property::enchantment)).toList(); }
    public static LootSearchTerm withProperties(String itemId,String display,java.util.List<Property> properties) {
        var sorted=properties.stream().sorted(java.util.Comparator.comparing(Property::enchantment)).toList();
        return new LootSearchTerm(itemId,sorted.isEmpty()?null:sorted.getFirst().enchantment(),
            sorted.isEmpty()?0:sorted.getFirst().level(),display,sorted);
    }
    public String signature() {
        return itemId + "#" + properties;
    }

    public boolean matches(ItemLoot item) {
        if (item == null || !itemId.equals(item.itemId())) return false;
        return properties.stream().allMatch(property -> matchesProperty(item,property.enchantment(),property.level()));
    }
    private static boolean matchesProperty(ItemLoot item,String enchantment,int level) {

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
