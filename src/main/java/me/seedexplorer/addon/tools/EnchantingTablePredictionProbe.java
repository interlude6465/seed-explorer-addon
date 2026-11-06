package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.enchant.EnchantingTablePredictor;
import me.seedexplorer.addon.enchant.EnchantingTargetPlanner;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.PrintStream;
import java.util.List;

/** Regression probe for the exact vanilla enchanting-offer RNG path. */
public final class EnchantingTablePredictionProbe {
    private static final PrintStream OUT = System.out;

    private EnchantingTablePredictionProbe() {
    }

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            int seed = args.length > 0 ? Integer.parseInt(args[0]) : 123456789;
            var registryAccess = WorldgenEngine.offlineRegistryAccess();
            if (EnchantingTablePredictor.areCompatible(
                "minecraft:protection", "minecraft:fire_protection", registryAccess)) {
                throw new IllegalStateException("exclusive protection enchantments reported compatible");
            }
            if (!EnchantingTablePredictor.areCompatible(
                "minecraft:protection", "minecraft:unbreaking", registryAccess)) {
                throw new IllegalStateException("compatible helmet enchantments reported exclusive");
            }
            OUT.println("compatibility_rules=PASS");
            var requested = EnchantingTablePredictor.parseRequested("sharpness 2, unbreaking 3");
            if (requested.size() != 2
                || !requested.get(0).id().equals("minecraft:sharpness") || requested.get(0).level() != 2
                || !requested.get(1).id().equals("minecraft:unbreaking") || requested.get(1).level() != 3) {
                throw new IllegalStateException("level-aware enchantment request parsing failed: " + requested);
            }
            var legacy = EnchantingTablePredictor.parseRequested("sharpness unbreaking");
            if (legacy.size() != 2 || legacy.stream().anyMatch(request -> request.level() != 0)) {
                throw new IllegalStateException("legacy enchantment request parsing failed: " + legacy);
            }
            OUT.println("request_parser=PASS requested=" + requested);
            ItemStack item = new ItemStack(Items.DIAMOND_SWORD);
            OUT.println("item=minecraft:diamond_sword seed=" + seed + " registry=ready");
            for (int cost : new int[]{1, 10, 20, 30}) {
                List<EnchantingTablePredictor.EnchantmentResult> first =
                    EnchantingTablePredictor.predictOffer(seed, item, cost, registryAccess);
                List<EnchantingTablePredictor.EnchantmentResult> second =
                    EnchantingTablePredictor.predictOffer(seed, item, cost, registryAccess);
                if (!first.equals(second)) {
                    throw new IllegalStateException("non-deterministic offer at cost " + cost);
                }
                OUT.println("cost=" + cost + " offer=" + first);
            }
            for (int slot = 0; slot < 3; slot++) {
                int cost = 8 + slot * 7;
                List<EnchantingTablePredictor.EnchantmentResult> first =
                    EnchantingTablePredictor.predictOffer(seed, item, slot, cost, registryAccess);
                List<EnchantingTablePredictor.EnchantmentResult> second =
                    EnchantingTablePredictor.predictOffer(seed, item, slot, cost, registryAccess);
                if (!first.equals(second)) {
                    throw new IllegalStateException("non-deterministic slot-aware offer at slot " + slot);
                }
                OUT.println("slot=" + slot + " cost=" + cost + " offer=" + first);
            }
            var itemReport = EnchantingTablePredictor.predictForItem(item, seed, 15, registryAccess);
            if (itemReport == null || itemReport.offers().size() != 3) {
                throw new IllegalStateException("inventory-item offer prediction unavailable");
            }
            OUT.println("bookshelves=15 item_offers=" + itemReport.offers());
            var baselineOffer = itemReport.offers().stream()
                .filter(offer -> !offer.enchantments().isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no baseline offer for planner test"));
            var target = List.of(new EnchantingTablePredictor.RequestedEnchantment(
                baselineOffer.enchantments().getFirst().id(), baselineOffer.enchantments().getFirst().level()));
            var plan = EnchantingTargetPlanner.find(item, seed, 15, target, itemReport, registryAccess);
            if (plan.isEmpty() || !plan.get().offer().hasAll(target)) {
                throw new IllegalStateException("target planner failed: " + target);
            }
            OUT.println("planner=PASS target=" + target + " instruction=" + plan.get().instruction());
            var futureTarget = List.of(new EnchantingTablePredictor.RequestedEnchantment(
                "minecraft:unbreaking", 0));
            var futurePlan = EnchantingTargetPlanner.findFuture(item, 0x1234ABCDL, 15,
                futureTarget, registryAccess, 128);
            if (futurePlan.isEmpty() || futurePlan.get().currentSeed()
                || !futurePlan.get().offer().hasAll(futureTarget)) {
                throw new IllegalStateException("future RNG planner failed: " + futureTarget);
            }
            OUT.println("future_planner=PASS target=" + futureTarget
                + " instruction=" + futurePlan.get().instruction());
            OUT.println("result=PASS");
        } catch (Throwable throwable) {
            OUT.println("result=FAIL " + throwable.getClass().getName() + ": " + throwable.getMessage());
            throwable.printStackTrace(OUT);
            System.exit(1);
        }
    }
}
