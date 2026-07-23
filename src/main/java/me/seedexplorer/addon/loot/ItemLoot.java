package me.seedexplorer.addon.loot;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public record ItemLoot(String itemId, int minCount, int maxCount, int weight, String displayName,
                       boolean randomEnchantment, String enchantmentId, int enchantmentLevel, String referenceTableId,
                       boolean levelsEnchantment, int enchantMinLevel, int enchantMaxLevel,
                       String enchantmentSummary,
                       /** True when a {@code minecraft:set_damage} function is present on this entry.
                        *  Vanilla calls {@code random.nextFloat()} to pick a damage fraction before
                        *  the enchantment step; the simulator must consume that float to stay in sync. */
                       boolean hasDamageRoll) {
    /** Legacy constructor — no damage roll. */
    public ItemLoot(String itemId, int minCount, int maxCount, int weight, String displayName,
                    boolean randomEnchantment, String enchantmentId, int enchantmentLevel, String referenceTableId) {
        this(itemId, minCount, maxCount, weight, displayName, randomEnchantment, enchantmentId,
            enchantmentLevel, referenceTableId, false, 0, 0, null, false);
    }

    /** Full constructor without damage roll (keeps existing call-sites unchanged). */
    public ItemLoot(String itemId, int minCount, int maxCount, int weight, String displayName,
                    boolean randomEnchantment, String enchantmentId, int enchantmentLevel, String referenceTableId,
                    boolean levelsEnchantment, int enchantMinLevel, int enchantMaxLevel, String enchantmentSummary) {
        this(itemId, minCount, maxCount, weight, displayName, randomEnchantment, enchantmentId,
            enchantmentLevel, referenceTableId, levelsEnchantment, enchantMinLevel, enchantMaxLevel, enchantmentSummary, false);
    }

    public static final ItemLoot EMPTY = new ItemLoot("minecraft:air", 0, 0, 0, "", false, null, 0, null);

    public Item getItem() {
        Item item = Items.AIR;
        try {
            var key = Identifier.parse(itemId);
            var registry = net.minecraft.core.registries.BuiltInRegistries.ITEM;
            var holder = registry.getOptional(key);
            if (holder.isPresent()) item = holder.get();
        } catch (Exception ignored) {
        }
        return item;
    }

    public static ItemLoot of(String itemId, int minCount, int maxCount, int weight) {
        String name = itemId;
        try {
            var key = Identifier.parse(itemId);
            var registry = net.minecraft.core.registries.BuiltInRegistries.ITEM;
            var holder = registry.getOptional(key);
            if (holder.isPresent()) {
                Item resolved = holder.get();
                name = resolved.getName(resolved.getDefaultInstance()).getString();
            } else {
                String path = key.getPath();
                name = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ');
            }
        } catch (Exception ignored) {
            String path = itemId.substring(itemId.lastIndexOf(':') + 1).replace('_', ' ');
            name = path;
        }
        return new ItemLoot(itemId, minCount, maxCount, weight, name, false, null, 0, null);
    }

    public static ItemLoot empty(int weight) {
        return new ItemLoot("minecraft:air", 0, 0, weight, "", false, null, 0, null);
    }

    public static ItemLoot randomlyEnchantedBook(int weight) {
        return new ItemLoot("minecraft:enchanted_book", 1, 1, weight, "Enchanted Book", true, null, 0, null);
    }

    public static ItemLoot randomlyEnchantedBook(int weight, String enchantmentId) {
        return new ItemLoot("minecraft:enchanted_book", 1, 1, weight, "Enchanted Book", true, enchantmentId, 0, null);
    }

    public static ItemLoot tableReference(String tableId, int weight) {
        return new ItemLoot("", 0, 0, weight, "ref:" + tableId, false, null, 0, tableId);
    }

    public ItemLoot withLevelsEnchantment(int minLevel, int maxLevel) {
        return new ItemLoot(itemId, minCount, maxCount, weight, displayName, randomEnchantment,
            enchantmentId, enchantmentLevel, referenceTableId, true, minLevel, maxLevel, null, hasDamageRoll);
    }

    public ItemLoot withPredictedEnchantments(String summary) {
        return new ItemLoot(itemId, minCount, maxCount, weight, displayName, true,
            enchantmentId, enchantmentLevel, referenceTableId, false, 0, 0, summary, hasDamageRoll);
    }

    public ItemLoot withFixedEnchantment(String enchantmentId, int level) {
        return new ItemLoot(itemId, minCount, maxCount, weight, displayName, true,
            enchantmentId, level, referenceTableId, false, 0, 0, null, hasDamageRoll);
    }

    /** Marks that vanilla will call {@code random.nextFloat()} for set_damage on this entry. */
    public ItemLoot withDamageRoll() {
        return new ItemLoot(itemId, minCount, maxCount, weight, displayName, randomEnchantment,
            enchantmentId, enchantmentLevel, referenceTableId, levelsEnchantment,
            enchantMinLevel, enchantMaxLevel, enchantmentSummary, true);
    }

    /** Marks that this non-book item has a random enchantment (enchant_randomly function). */
    public ItemLoot withRandomEnchantment(String enchantId) {
        return new ItemLoot(itemId, minCount, maxCount, weight, displayName, true,
            enchantId, 0, referenceTableId, levelsEnchantment, enchantMinLevel, enchantMaxLevel, null, hasDamageRoll);
    }
}
