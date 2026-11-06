package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.enchant.EnchantingSeedCracker;
import me.seedexplorer.addon.enchant.EnchantingPlayerRng;
import me.seedexplorer.addon.enchant.EnchantingTablePredictor;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.PrintStream;

/** Headless parity check for the ClientCommands-style XP-seed candidate filter. */
public final class EnchantingSeedCrackerProbe {
    private static final PrintStream OUT = System.out;

    private EnchantingSeedCrackerProbe() {
    }

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            int seed = args.length > 0 ? Integer.parseInt(args[0]) : 0xF1234567;
            var registryAccess = WorldgenEngine.offlineRegistryAccess();
            if (!EnchantingPlayerRng.verifyRecoveryForTesting(0x1234ABCDL)) {
                throw new IllegalStateException("player RNG recovery math failed");
            }
            OUT.println("player_rng_recovery=PASS");
            if (!EnchantingPlayerRng.verifyPendingConfirmationForTesting(0x1234ABCDL)) {
                throw new IllegalStateException("pending enchant confirmation protocol failed");
            }
            OUT.println("pending_enchant_confirmation=PASS");
            ItemStack[] observations = {
                new ItemStack(Items.DIAMOND_SWORD),
                new ItemStack(Items.DIAMOND_PICKAXE),
                new ItemStack(Items.BOOK)
            };

            int[] candidates = null;
            for (int index = 0; index < observations.length; index++) {
                ItemStack item = observations[index];
                EnchantingTablePredictor.Report report = EnchantingTablePredictor.predictForItem(
                    item, seed, 15, registryAccess);
                if (report == null) throw new IllegalStateException("missing report for " + item);
                int[] costs = report.offers().stream().mapToInt(EnchantingTablePredictor.Offer::cost).toArray();
                int[] clues = report.offers().stream().mapToInt(EnchantingTablePredictor.Offer::clueId).toArray();
                int[] levels = report.offers().stream().mapToInt(EnchantingTablePredictor.Offer::clueLevel).toArray();
                candidates = EnchantingSeedCracker.filterCandidatesForTesting(
                    seed, 15, item, costs, clues, levels, registryAccess, candidates);
                boolean containsSeed = java.util.Arrays.stream(candidates).anyMatch(candidate -> candidate == seed);
                if (!containsSeed) throw new IllegalStateException("true seed was filtered at observation " + index);
                OUT.println("observation=" + (index + 1) + " item=" + item.getItem()
                    + " candidates=" + candidates.length + " containsSeed=" + containsSeed);
                if (candidates.length == 1) break;
            }
            if (candidates == null || candidates.length != 1 || candidates[0] != seed) {
                throw new IllegalStateException("seed did not resolve: "
                    + (candidates == null ? 0 : candidates.length));
            }
            OUT.println("resolvedSeed=" + candidates[0]);
            OUT.println("result=PASS");
        } catch (Throwable throwable) {
            OUT.println("result=FAIL " + throwable.getClass().getName() + ": " + throwable.getMessage());
            throwable.printStackTrace(OUT);
            System.exit(1);
        }
    }
}
