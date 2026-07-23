package me.seedexplorer.addon.loot;

import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Deterministic simulator for the supported vanilla loot-table operations. */
public final class LootTableSimulator {
    // Expansion order of #minecraft:on_random_loot in the bundled 26.1.2 data.
    // Vanilla's lightweight construction registries expose values but do not bind tags,
    // so the offline differential harness must retain the data-pack order explicitly.
    private static final List<String> RANDOM_LOOT_ENCHANTMENTS = List.of(
        "protection", "fire_protection", "feather_falling", "blast_protection",
        "projectile_protection", "respiration", "aqua_affinity", "thorns", "depth_strider",
        "sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect", "looting",
        "sweeping_edge", "efficiency", "silk_touch", "unbreaking", "fortune", "power", "punch",
        "flame", "infinity", "luck_of_the_sea", "lure", "loyalty", "impaling", "riptide",
        "channeling", "multishot", "quick_charge", "piercing", "density", "breach", "lunge",
        "binding_curse", "vanishing_curse", "frost_walker", "mending"
    );

    private record EnchantmentRoll(String id, int level) {
    }

    // Enchantment pools never change after Bootstrap. Building them on every enchanted-item
    // roll (64 rolls/chest x hundreds of chests) was allocating enough to exhaust heap/CPU.
    // Cache both pools lazily on first use.
    private static volatile List<Holder<Enchantment>> bookEnchantmentPool;
    private static volatile List<String> bookEnchantmentIds;
    private static volatile List<Holder<Enchantment>> gearEnchantmentPool;

    private LootTableSimulator() {
    }

    public static List<ItemLoot> simulate(LootTableDef table, long seed, int maxRolls) {
        if (table.pools().isEmpty()) return List.of();
        LootRandom random = new LootRandom(seed);
        List<ItemLoot> results = new ArrayList<>();
        simulateOnRandom(table, random, maxRolls, new HashMap<>(), results, new int[]{0});
        return List.copyOf(results);
    }

    private static void simulateOnRandom(LootTableDef table, LootRandom random, int maxRolls,
                                          Map<String, Boolean> resolving, List<ItemLoot> results,
                                          int[] totalRollsRef) {
        for (LootPool pool : table.pools()) {
            // Handle random_chance condition
            if (pool.chance() < 1.0f && random.nextFloat() >= pool.chance()) {
                continue;
            }

            int rolls = pool.minRolls() + (pool.maxRolls() > pool.minRolls()
                ? random.nextInt(pool.maxRolls() - pool.minRolls() + 1) : 0);

            for (int r = 0; r < rolls; r++) {
                if (totalRollsRef[0] >= maxRolls) break;
                ItemLoot selected = selectWeightedEntry(pool, random);
                totalRollsRef[0]++;
                if (selected == null) continue;

                if (selected.referenceTableId() != null) {
                    if (resolving.putIfAbsent(selected.referenceTableId(), Boolean.TRUE) == null) {
                        LootTableDef refTable = VanillaLootTables.get(selected.referenceTableId());
                        if (refTable != null) {
                            simulateOnRandom(refTable, random, maxRolls, resolving, results, totalRollsRef);
                        }
                        resolving.remove(selected.referenceTableId());
                    }
                    continue;
                }

                if (selected.itemId().equals("minecraft:air")) continue;

                int count = selected.minCount();
                if (selected.maxCount() > selected.minCount()) {
                    count += random.nextInt(selected.maxCount() - selected.minCount() + 1);
                }
                // Vanilla's SetItemDamageFunction.run() calls random.nextFloat() before the
                // enchantment step. Consuming it here keeps the RNG stream aligned even though
                // predicted output doesn't include durability values.
                if (selected.hasDamageRoll()) {
                    random.nextFloat();
                }
                if (selected.randomEnchantment()) {
                    EnchantmentRoll roll = selected.enchantmentId() != null
                        ? consumeSpecificRandomEnchantment(random, selected.enchantmentId())
                        : consumeRandomEnchantment(random, selected.itemId());
                    selected = new ItemLoot(selected.itemId(), selected.minCount(), selected.maxCount(),
                        selected.weight(), selected.displayName(), true,
                        roll.id(), roll.level(), selected.referenceTableId());
                }
                if (selected.levelsEnchantment()) {
                    selected = applyLevelsEnchantment(selected, random);
                }

                merge(results, selected, count);
            }
            if (totalRollsRef[0] >= maxRolls) break;
        }
    }

    private static void merge(List<ItemLoot> results, ItemLoot selected, int count) {
        for (int i = 0; i < results.size(); i++) {
            ItemLoot existing = results.get(i);
            if (!canMerge(existing, selected)) continue;
            int mergedCount = existing.minCount() + count;
            results.set(i, new ItemLoot(selected.itemId(), mergedCount, mergedCount,
                selected.weight(), selected.displayName(), selected.randomEnchantment(),
                selected.enchantmentId(), selected.enchantmentLevel(), null,
                false, 0, 0, selected.enchantmentSummary()));
            return;
        }
        results.add(new ItemLoot(selected.itemId(), count, count, selected.weight(),
            selected.displayName(), selected.randomEnchantment(),
            selected.enchantmentId(), selected.enchantmentLevel(), null,
            false, 0, 0, selected.enchantmentSummary()));
    }

    private static boolean canMerge(ItemLoot existing, ItemLoot selected) {
        return existing.itemId().equals(selected.itemId())
            && existing.randomEnchantment() == selected.randomEnchantment()
            && Objects.equals(existing.enchantmentId(), selected.enchantmentId())
            && existing.enchantmentLevel() == selected.enchantmentLevel()
            && Objects.equals(existing.enchantmentSummary(), selected.enchantmentSummary());
    }

    private static ItemLoot selectWeightedEntry(LootPool pool, LootRandom random) {
        // Vanilla LootPool.addRandomItem selects a sole expanded entry directly.
        // In particular, it does not consume nextInt(1) before entry functions run.
        if (pool.entries().size() == 1) {
            ItemLoot only = pool.entries().getFirst();
            return only.weight() > 0 ? only : null;
        }

        int totalWeight = pool.totalWeight();
        if (totalWeight <= 0) return null;
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (ItemLoot entry : pool.entries()) {
            cumulative += entry.weight();
            if (roll < cumulative) return entry;
        }
        return pool.entries().isEmpty() ? null : pool.entries().getLast();
    }



    private static EnchantmentRoll consumeRandomEnchantment(LootRandom random, String itemId) {
        // Vanilla's EnchantRandomlyFunction splits by item type:
        // - enchanted_book: uses the #minecraft:on_random_loot tag (the 40-entry list).
        // - non-book gear (swords, helmets, etc.): uses ALL enchantments in the registry.
        // Using the wrong pool size causes nextInt(N) to diverge from vanilla's stream.
        boolean isBook = "minecraft:enchanted_book".equals(itemId);
        if (isBook) {
            // Books use #minecraft:on_random_loot — same as the RANDOM_LOOT_ENCHANTMENTS list.
            var registry = WorldgenEngine.vanillaLookup().lookupOrThrow(Registries.ENCHANTMENT);
            List<Holder<Enchantment>> options = RANDOM_LOOT_ENCHANTMENTS.stream()
                .map(id -> ResourceKey.create(Registries.ENCHANTMENT, Identifier.withDefaultNamespace(id)))
                .map(registry::getOrThrow)
                .map(h -> (Holder<Enchantment>) h)
                .toList();
            if (options.isEmpty()) return new EnchantmentRoll(null, 0);
            int idx = random.nextInt(options.size());
            Holder<Enchantment> selected = options.get(idx);
            int min = selected.value().getMinLevel();
            int max = selected.value().getMaxLevel();
            int level = min;
            if (max > min) level += random.nextInt(max - min + 1);
            String id = Identifier.withDefaultNamespace(RANDOM_LOOT_ENCHANTMENTS.get(idx)).toString();
            return new EnchantmentRoll(id, level);
        }
        // Non-book gear: full enchantment registry.
        // offlineRegistryAccess() supports listElements(); vanillaLookup() throws UnsupportedOperationException.
        List<Holder<Enchantment>> options;
        try {
            options = WorldgenEngine.offlineRegistryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .listElements()
                .map(h -> (Holder<Enchantment>) h)
                .toList();
        } catch (Throwable ignored) {
            var registry = WorldgenEngine.vanillaLookup().lookupOrThrow(Registries.ENCHANTMENT);
            options = RANDOM_LOOT_ENCHANTMENTS.stream()
                .map(id -> ResourceKey.create(Registries.ENCHANTMENT, Identifier.withDefaultNamespace(id)))
                .map(registry::getOrThrow)
                .map(h -> (Holder<Enchantment>) h)
                .toList();
        }
        if (options.isEmpty()) return new EnchantmentRoll(null, 0);
        int idx = random.nextInt(options.size());
        Holder<Enchantment> selected = options.get(idx);
        int min = selected.value().getMinLevel();
        int max = selected.value().getMaxLevel();
        int level = min;
        if (max > min) level += random.nextInt(max - min + 1);
        String id = selected.unwrapKey().map(k -> k.identifier().toString()).orElse(null);
        return new EnchantmentRoll(id, level);
    }

    private static EnchantmentRoll consumeSpecificRandomEnchantment(LootRandom random, String enchantmentId) {
        var registry = WorldgenEngine.vanillaLookup().lookupOrThrow(Registries.ENCHANTMENT);
        String[] ids = enchantmentId.split(",");
        String chosen = ids.length == 1 ? ids[0] : ids[random.nextInt(ids.length)];
        Identifier identifier = Identifier.parse(chosen.trim());
        Holder<Enchantment> selected = registry.getOrThrow(ResourceKey.create(Registries.ENCHANTMENT, identifier));
        int min = selected.value().getMinLevel();
        int max = selected.value().getMaxLevel();
        int level = min;
        if (max > min) level += random.nextInt(max - min + 1);
        return new EnchantmentRoll(identifier.toString(), level);
    }

    private static ItemLoot applyLevelsEnchantment(ItemLoot selected, LootRandom random) {
        int min = Math.max(0, selected.enchantMinLevel());
        int max = Math.max(min, selected.enchantMaxLevel());
        int level = min + (max > min ? random.nextInt(max - min + 1) : 0);

        try {
            ItemStack stack = new ItemStack(selected.getItem());
            RegistryAccess registryAccess = WorldgenEngine.offlineRegistryAccess();
            Optional<HolderSet<Enchantment>> options;
            try {
                options = Optional.of(registryAccess.lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(EnchantmentTags.ON_RANDOM_LOOT));
            } catch (Throwable ignored) {
                options = Optional.empty();
            }

            ItemStack enchanted = EnchantmentHelper.enchantItem(random.raw(), stack, level, registryAccess, options);
            String summary = enchantmentSummary(enchanted);
            if (summary == null || summary.isBlank()) {
                summary = "Enchanted level " + level;
            }
            return selected.withPredictedEnchantments(summary);
        } catch (Throwable ignored) {
            return selected.withPredictedEnchantments("Enchanted level " + level);
        }
    }

    private static String enchantmentSummary(ItemStack stack) {
        var enchantments = stack.getEnchantments();
        if (enchantments == null || enchantments.isEmpty()) return "";

        List<String> parts = new ArrayList<>();
        enchantments.entrySet().forEach(entry -> {
            String id = entry.getKey().unwrapKey()
                .map(key -> key.identifier().toString())
                .orElse("minecraft:unknown");
            parts.add(shortEnchantmentName(id) + " " + roman(entry.getIntValue()));
        });
        return String.join(", ", parts);
    }

    private static String shortEnchantmentName(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String[] words = path.split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(level);
        };
    }
}
