package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.levelgen.LegacyRandomSource;

import java.io.PrintStream;
import java.util.Optional;

/**
 * De-risk probe: can the REAL EnchantmentHelper.enchantItem run offline?
 * If yes, LootTableSimulator can call it directly at the enchant_with_levels
 * RNG point to get exact armour/tool enchantments (no reimplementation).
 * Args: [itemId] [level] [seed]
 */
public final class EnchantHelperProbe {
    private static final PrintStream OUT = System.out;

    private EnchantHelperProbe() {}

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();

            int level = args.length > 1 ? Integer.parseInt(args[1]) : 30;
            long seed = args.length > 2 ? Long.parseLong(args[2]) : 123456789L;

            RegistryAccess registryAccess = WorldgenEngine.offlineRegistryAccess();

            // Resolve #minecraft:on_random_loot as the options HolderSet.
            Optional<HolderSet<Enchantment>> options;
            try {
                HolderSet<Enchantment> set = registryAccess
                    .lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(EnchantmentTags.ON_RANDOM_LOOT);
                options = Optional.of(set);
                OUT.println("resolved ON_RANDOM_LOOT size=" + set.size());
            } catch (Throwable t) {
                options = Optional.empty();
                OUT.println("ON_RANDOM_LOOT resolve failed: " + t);
            }

            // Build a real ItemStack (requires item components bound).
            ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
            OUT.println("stack=" + stack + " enchantable=" + stack.get(
                net.minecraft.core.component.DataComponents.ENCHANTABLE));

            RandomSource random = new LegacyRandomSource(seed);
            ItemStack result = EnchantmentHelper.enchantItem(
                random, stack, level, registryAccess, options);

            OUT.println("result=" + result);
            var ench = result.getEnchantments();
            OUT.println("enchantment_count=" + ench.size());
            ench.entrySet().forEach(e ->
                OUT.println("  " + e.getKey().unwrapKey().map(k -> k.identifier().toString()).orElse("?")
                    + " lvl" + e.getIntValue()));
        } catch (Throwable t) {
            OUT.println("probe_failed=" + t.getClass().getName() + " " + t.getMessage());
            t.printStackTrace(OUT);
            System.exit(1);
        }
    }
}
