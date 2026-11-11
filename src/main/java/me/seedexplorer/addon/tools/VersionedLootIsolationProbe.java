package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.*;
import me.seedexplorer.addon.worldgen.PredictionContext;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import java.util.*;
import java.util.concurrent.*;

/** Selected-version table/fallback isolation, including nested references under concurrent use. */
public final class VersionedLootIsolationProbe {
    private static final String TABLE = "minecraft:chests/village/village_weaponsmith";
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        WorldgenEngine.offlineRegistryAccess();
        var old = BundledLootTableLoader.VersionProfile.forVersion("1.19.4");
        var current = BundledLootTableLoader.VersionProfile.forVersion("26.3");
        var historical = VanillaLootTables.get(old, TABLE);
        var latest = VanillaLootTables.get(current, TABLE);
        check(historical != null && latest != null, "profile resources missing");
        check(!contains(historical, "minecraft:iron_spear") && contains(latest, "minecraft:iron_spear"),
            "selected profiles did not retain different item pools");
        check(VanillaLootTables.get(old, "minecraft:chests/trial_chambers/reward") == null,
            "missing historical table leaked from current version");
        var wrapper = new LootTableDef("probe:wrapper", "nested profile probe",
            List.of(new LootPool(1, 1, List.of(ItemLoot.tableReference(TABLE, 1)))));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var jobs = new ArrayList<Future<?>>();
            for (var profile : List.of(old, current)) jobs.add(workers.submit(() -> {
                for (long seed = 0; seed < 128; seed++) {
                    var selected = VanillaLootTables.get(profile, TABLE);
                    var direct = LootTableSimulator.simulate(profile, selected, seed, 64);
                    var nested = LootTableSimulator.simulate(profile, wrapper, seed, 64);
                    check(direct.equals(nested), "nested profile/RNG isolation: " + profile.minecraftVersion());
                }
            }));
            for (var job : jobs) job.get();
        }
        for (var profile : List.of(old, current)) {
            var context = new PredictionContext(1, profile.minecraftVersion(), 0);
            for (long seed = 1; seed <= 16; seed++) {
                var vanilla = LootTableSimulator.simulateVanilla(context, TABLE, seed);
                var fallback = LootTableSimulator.simulate(profile, VanillaLootTables.get(profile, TABLE), seed, 64);
                check(vanilla != null && counts(vanilla).equals(counts(fallback)),
                    "selected JSON inventory mismatch version=" + profile.minecraftVersion() + " seed=" + seed
                        + " vanilla=" + counts(vanilla) + " fallback=" + counts(fallback));
            }
        }
        var oldBastion = VanillaLootTables.get(old, "minecraft:chests/bastion_other");
        check(oldBastion.pools().stream().flatMap(pool -> pool.entries().stream())
            .anyMatch(item -> "minecraft:soul_speed".equals(item.enchantmentId())),
            "historical explicit enchantments option ignored");
        var books = new LootTableDef("probe:books", "fallback enchantments",
            List.of(new LootPool(1, 1, List.of(ItemLoot.randomlyEnchantedBook(1)))));
        for (long seed = 1; seed <= 128; seed++) {
            for (var item : LootTableSimulator.simulate(old, books, seed, 64)) {
                check(!Set.of("minecraft:lunge", "minecraft:density", "minecraft:breach").contains(item.enchantmentId()),
                    "historical fallback enchantment leaked: " + item.enchantmentId());
            }
        }
        int enchanted = 0;
        for (long seed = 1; seed <= 16; seed++) {
            var items = LootTableSimulator.simulateVanilla(new PredictionContext(1, "1.19.4", 0),
                "minecraft:chests/end_city_treasure", seed);
            check(items != null, "historical enchanted table failed to decode");
            for (var item : items) {
                String summary = item.enchantmentSummary();
                if (summary == null || summary.isBlank()) continue;
                enchanted++;
                check(!summary.contains("Lunge") && !summary.contains("Density") && !summary.contains("Breach"),
                    "historical enchantment pool leaked current enchantment: " + summary);
            }
        }
        check(enchanted > 0, "historical enchantment modifiers were silently ignored");
        System.out.println("VersionedLootIsolationProbe PASS: two profiles, 256 concurrent nested replays, 32 vanilla inventories, 16 historical enchanted inventories, 128 historical fallback books, missing historical table");
    }
    private static boolean contains(LootTableDef table, String id) {
        return table.pools().stream().flatMap(pool -> pool.entries().stream()).anyMatch(item -> item.itemId().equals(id));
    }
    private static Map<String,Integer> counts(List<ItemLoot> items) {
        Map<String,Integer> counts = new TreeMap<>();
        if (items != null) for (var item : items) counts.merge(item.itemId(), item.minCount(), Integer::sum);
        return counts;
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
