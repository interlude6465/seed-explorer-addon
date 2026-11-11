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
                       boolean hasDamageRoll,
                       Integer damage,
                       String potionContents,
                       /** True when the generated stack carries non-default data components that
                        *  this compact prediction model does not expose or compare. */
                       boolean hasUnrepresentedComponents,
                       String mapDecorations, String suspiciousStewEffects) {
    /** Compatibility constructor for loot cached before map/stew properties were modeled. */
    public ItemLoot(String itemId, int minCount, int maxCount, int weight, String displayName,
                    boolean randomEnchantment, String enchantmentId, int enchantmentLevel,
                    String referenceTableId, boolean levelsEnchantment, int enchantMinLevel,
                    int enchantMaxLevel, String enchantmentSummary, boolean hasDamageRoll,
                    Integer damage, String potionContents, boolean hasUnrepresentedComponents) {
        this(itemId, minCount, maxCount, weight, displayName, randomEnchantment, enchantmentId,
            enchantmentLevel, referenceTableId, levelsEnchantment, enchantMinLevel, enchantMaxLevel,
            enchantmentSummary, hasDamageRoll, damage, potionContents, hasUnrepresentedComponents,
            null, null);
    }

    /** Previous full constructor retained for source compatibility. */
    public ItemLoot(String itemId, int minCount, int maxCount, int weight, String displayName,
                    boolean randomEnchantment, String enchantmentId, int enchantmentLevel,
                    String referenceTableId, boolean levelsEnchantment, int enchantMinLevel,
                    int enchantMaxLevel, String enchantmentSummary, boolean hasDamageRoll,
                    boolean hasUnrepresentedComponents) {
        this(itemId, minCount, maxCount, weight, displayName, randomEnchantment, enchantmentId,
            enchantmentLevel, referenceTableId, levelsEnchantment, enchantMinLevel,
            enchantMaxLevel, enchantmentSummary, hasDamageRoll, null, null,
            hasUnrepresentedComponents);
    }

    /** Full legacy constructor retained for source and persisted-data compatibility. */
    public ItemLoot(String itemId, int minCount, int maxCount, int weight, String displayName,
                    boolean randomEnchantment, String enchantmentId, int enchantmentLevel,
                    String referenceTableId, boolean levelsEnchantment, int enchantMinLevel,
                    int enchantMaxLevel, String enchantmentSummary, boolean hasDamageRoll) {
        this(itemId, minCount, maxCount, weight, displayName, randomEnchantment, enchantmentId,
            enchantmentLevel, referenceTableId, levelsEnchantment, enchantMinLevel,
            enchantMaxLevel, enchantmentSummary, hasDamageRoll, null, null, false);
    }

    /** Legacy constructor — no damage roll. */
    public ItemLoot(String itemId, int minCount, int maxCount, int weight, String displayName,
                    boolean randomEnchantment, String enchantmentId, int enchantmentLevel, String referenceTableId) {
        this(itemId, minCount, maxCount, weight, displayName, randomEnchantment, enchantmentId,
            enchantmentLevel, referenceTableId, false, 0, 0, null, false, false);
    }

    /** Full constructor without damage roll (keeps existing call-sites unchanged). */
    public ItemLoot(String itemId, int minCount, int maxCount, int weight, String displayName,
                    boolean randomEnchantment, String enchantmentId, int enchantmentLevel, String referenceTableId,
                    boolean levelsEnchantment, int enchantMinLevel, int enchantMaxLevel, String enchantmentSummary) {
        this(itemId, minCount, maxCount, weight, displayName, randomEnchantment, enchantmentId,
            enchantmentLevel, referenceTableId, levelsEnchantment, enchantMinLevel,
            enchantMaxLevel, enchantmentSummary, false, false);
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
                name = displayText(resolved.getName(resolved.getDefaultInstance()));
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

    /** Translation contents lazily cache decomposed text without synchronization.
     * Item default components can be shared across prediction workers and the UI.
     * Copy translation contents (not just MutableComponent's shallow copy) before
     * resolving names so one worker cannot observe another's half-built cache. */
    public static String displayText(net.minecraft.network.chat.Component component) {
        return copyDisplayComponent(component).getString();
    }

    private static net.minecraft.network.chat.MutableComponent copyDisplayComponent(
        net.minecraft.network.chat.Component component) {
        net.minecraft.network.chat.MutableComponent copy;
        if (component.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translated) {
            Object[] args = translated.getArgs().clone();
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof net.minecraft.network.chat.Component nested)
                    args[i] = copyDisplayComponent(nested);
            }
            copy = net.minecraft.network.chat.Component.translatableWithFallback(
                translated.getKey(), translated.getFallback(), args);
        } else copy = component.plainCopy();
        copy.setStyle(component.getStyle());
        for (var sibling : component.getSiblings()) copy.append(copyDisplayComponent(sibling));
        return copy;
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
            enchantmentId, enchantmentLevel, referenceTableId, true, minLevel, maxLevel, null,
            hasDamageRoll, damage, potionContents, hasUnrepresentedComponents, mapDecorations, suspiciousStewEffects);
    }

    public ItemLoot withPredictedEnchantments(String summary) {
        return new ItemLoot(itemId, minCount, maxCount, weight, displayName, true,
            enchantmentId, enchantmentLevel, referenceTableId, false, 0, 0, summary,
            hasDamageRoll, damage, potionContents, hasUnrepresentedComponents, mapDecorations, suspiciousStewEffects);
    }

    public ItemLoot withItemId(String replacementItemId) {
        return new ItemLoot(replacementItemId, minCount, maxCount, weight, displayName, randomEnchantment,
            enchantmentId, enchantmentLevel, referenceTableId, levelsEnchantment, enchantMinLevel,
            enchantMaxLevel, enchantmentSummary, hasDamageRoll, damage, potionContents,
            hasUnrepresentedComponents, mapDecorations, suspiciousStewEffects);
    }

    public ItemLoot withDisplayName(String replacementDisplayName) {
        return new ItemLoot(itemId, minCount, maxCount, weight,
            replacementDisplayName == null || replacementDisplayName.isBlank()
                ? displayName : replacementDisplayName,
            randomEnchantment, enchantmentId, enchantmentLevel, referenceTableId,
            levelsEnchantment, enchantMinLevel, enchantMaxLevel, enchantmentSummary,
            hasDamageRoll, damage, potionContents, hasUnrepresentedComponents, mapDecorations, suspiciousStewEffects);
    }

    public ItemLoot withFixedEnchantment(String enchantmentId, int level) {
        return new ItemLoot(itemId, minCount, maxCount, weight, displayName, true,
            enchantmentId, level, referenceTableId, false, 0, 0, null,
            hasDamageRoll, damage, potionContents, hasUnrepresentedComponents, mapDecorations, suspiciousStewEffects);
    }

    /** Marks that vanilla will call {@code random.nextFloat()} for set_damage on this entry. */
    public ItemLoot withDamageRoll() {
        return new ItemLoot(itemId, minCount, maxCount, weight, displayName, randomEnchantment,
            enchantmentId, enchantmentLevel, referenceTableId, levelsEnchantment,
            enchantMinLevel, enchantMaxLevel, enchantmentSummary, true,
            damage, potionContents, hasUnrepresentedComponents, mapDecorations, suspiciousStewEffects);
    }

    public ItemLoot withDamage(Integer value) {
        return new ItemLoot(itemId, minCount, maxCount, weight, displayName, randomEnchantment,
            enchantmentId, enchantmentLevel, referenceTableId, levelsEnchantment,
            enchantMinLevel, enchantMaxLevel, enchantmentSummary, hasDamageRoll, value,
            potionContents, hasUnrepresentedComponents, mapDecorations, suspiciousStewEffects);
    }

    public ItemLoot withPotionContents(String value) {
        return new ItemLoot(itemId, minCount, maxCount, weight, displayName, randomEnchantment,
            enchantmentId, enchantmentLevel, referenceTableId, levelsEnchantment,
            enchantMinLevel, enchantMaxLevel, enchantmentSummary, hasDamageRoll, damage,
            value, hasUnrepresentedComponents, mapDecorations, suspiciousStewEffects);
    }

    public ItemLoot withUnrepresentedComponents() {
        return new ItemLoot(itemId, minCount, maxCount, weight, displayName, randomEnchantment,
            enchantmentId, enchantmentLevel, referenceTableId, levelsEnchantment,
            enchantMinLevel, enchantMaxLevel, enchantmentSummary, hasDamageRoll, damage,
            potionContents, true, mapDecorations, suspiciousStewEffects);
    }

    public ItemLoot withMapDecorations(String value) {
        return new ItemLoot(itemId, minCount, maxCount, weight, displayName, randomEnchantment,
            enchantmentId, enchantmentLevel, referenceTableId, levelsEnchantment,
            enchantMinLevel, enchantMaxLevel, enchantmentSummary, hasDamageRoll, damage,
            potionContents, hasUnrepresentedComponents, value, suspiciousStewEffects);
    }

    public ItemLoot withSuspiciousStewEffects(String value) {
        return new ItemLoot(itemId, minCount, maxCount, weight, displayName, randomEnchantment,
            enchantmentId, enchantmentLevel, referenceTableId, levelsEnchantment,
            enchantMinLevel, enchantMaxLevel, enchantmentSummary, hasDamageRoll, damage,
            potionContents, hasUnrepresentedComponents, mapDecorations, value);
    }

    /** Marks that this non-book item has a random enchantment (enchant_randomly function). */
    public ItemLoot withRandomEnchantment(String enchantId) {
        return new ItemLoot(itemId, minCount, maxCount, weight, displayName, true,
            enchantId, 0, referenceTableId, levelsEnchantment, enchantMinLevel, enchantMaxLevel,
            null, hasDamageRoll, damage, potionContents, hasUnrepresentedComponents, mapDecorations, suspiciousStewEffects);
    }
}
