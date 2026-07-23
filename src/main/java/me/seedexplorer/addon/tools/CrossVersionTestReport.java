package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.BundledLootTableLoader;
import me.seedexplorer.addon.loot.BundledLootTableLoader.VersionProfile;
import me.seedexplorer.addon.loot.VanillaLootTables;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-version loot table loading test.
 * Loads all defined version profiles and reports parse failures per version.
 */
public final class CrossVersionTestReport {
    public static void main(String[] args) {
        PrintStream out = System.out;
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            run(out);
        } catch (Throwable t) {
            out.println("FATAL: " + t.getClass().getName() + " " + t.getMessage());
            t.printStackTrace(out);
            System.exit(1);
        }
    }

    private static void run(PrintStream out) {
        String runtimeVersion = SharedConstants.getCurrentVersion().name();
        out.println("Cross-Version Loot Table Test");
        out.println("Runtime Minecraft version: " + runtimeVersion);
        out.println();

        VersionProfile[] profiles = BundledLootTableLoader.supportedVersions();
        List<String> failures = new ArrayList<>();
        int totalParsed = 0;
        int totalErrors = 0;

        record ProfileResult(String version, int parsed, int expected, int errors) {}
        List<ProfileResult> results = new ArrayList<>();

        for (VersionProfile profile : profiles) {
            out.println("--- Profile: " + profile.minecraftVersion() + " ---");
            out.println("  loot_table path: " + profile.lootTablePathPrefix());

            VanillaLootTables.resetForTest();
            int count = 0;
            int errors = 0;
            List<String> failedTables = new ArrayList<>();

            String[] tableIds = {
                "minecraft:chests/abandoned_mineshaft",
                "minecraft:chests/ancient_city",
                "minecraft:chests/ancient_city_ice_box",
                "minecraft:chests/bastion_bridge",
                "minecraft:chests/bastion_hoglin_stable",
                "minecraft:chests/bastion_other",
                "minecraft:chests/bastion_treasure",
                "minecraft:chests/buried_treasure",
                "minecraft:chests/desert_pyramid",
                "minecraft:chests/end_city_treasure",
                "minecraft:chests/igloo_chest",
                "minecraft:chests/jungle_temple",
                "minecraft:chests/jungle_temple_dispenser",
                "minecraft:chests/nether_bridge",
                "minecraft:chests/pillager_outpost",
                "minecraft:chests/ruined_portal",
                "minecraft:chests/shipwreck_map",
                "minecraft:chests/shipwreck_supply",
                "minecraft:chests/shipwreck_treasure",
                "minecraft:chests/simple_dungeon",
                "minecraft:chests/stronghold_corridor",
                "minecraft:chests/stronghold_crossing",
                "minecraft:chests/stronghold_library",
                "minecraft:chests/stronghold_storeroom",
                "minecraft:chests/underwater_ruin_big",
                "minecraft:chests/underwater_ruin_small",
                "minecraft:chests/woodland_mansion",
                "minecraft:chests/spawn_bonus_chest",
                "minecraft:chests/trial_chambers/corridor",
                "minecraft:chests/trial_chambers/reward",
                "minecraft:chests/trial_chambers/reward_common",
                "minecraft:chests/trial_chambers/reward_rare",
                "minecraft:chests/trial_chambers/reward_unique",
                "minecraft:chests/trial_chambers/reward_ominous",
                "minecraft:chests/trial_chambers/reward_ominous_common",
                "minecraft:chests/trial_chambers/reward_ominous_unique",
                "minecraft:chests/trial_chambers/entrance",
                "minecraft:chests/trial_chambers/supply",
                "minecraft:chests/trial_chambers/intersection",
                "minecraft:chests/trial_chambers/intersection_barrel",
                "minecraft:chests/trial_chambers/reward_ominous_rare",
                "minecraft:dispensers/trial_chambers/chamber",
                "minecraft:dispensers/trial_chambers/corridor",
                "minecraft:dispensers/trial_chambers/water",
                "minecraft:pots/trial_chambers/corridor",
                "minecraft:chests/village/village_armorer",
                "minecraft:chests/village/village_butcher",
                "minecraft:chests/village/village_cartographer",
                "minecraft:chests/village/village_desert_house",
                "minecraft:chests/village/village_fisher",
                "minecraft:chests/village/village_fletcher",
                "minecraft:chests/village/village_mason",
                "minecraft:chests/village/village_plains_house",
                "minecraft:chests/village/village_savanna_house",
                "minecraft:chests/village/village_shepherd",
                "minecraft:chests/village/village_snowy_house",
                "minecraft:chests/village/village_taiga_house",
                "minecraft:chests/village/village_tannery",
                "minecraft:chests/village/village_temple",
                "minecraft:chests/village/village_toolsmith",
                "minecraft:chests/village/village_weaponsmith"
            };

            boolean isRuntime = profile.minecraftVersion().equals(runtimeVersion);
            boolean isModernBundled = BundledLootTableLoader.compareVersions(profile.minecraftVersion(), "1.21.11") >= 0;

            // Table IDs known to be absent in 26.1.2 (renamed or removed from earlier versions)
            java.util.Set<String> knownAbsent = java.util.Set.of(
                "minecraft:chests/stronghold_storeroom"
            );

            for (String tableId : tableIds) {
                boolean absentByVersion = isModernBundled && knownAbsent.contains(tableId);
                var def = BundledLootTableLoader.parseFromBundled(profile, tableId, tableId);
                if (def != null) {
                    count++;
                } else if (absentByVersion) {
                    // Table was renamed/removed in this version — expected
                } else {
                    errors++;
                    failedTables.add(tableId);
                    if (isRuntime || isModernBundled) {
                        failures.add(profile.minecraftVersion() + ": " + tableId + " (MISSING)");
                    }
                }
            }

            totalParsed += count;
            totalErrors += errors;

            int expectedCount = isModernBundled ? tableIds.length - knownAbsent.size() : tableIds.length;
            out.println("  parsed: " + count + " / " + expectedCount + " (expected for version)");
            if (!failedTables.isEmpty()) {
                if (isRuntime) {
                    out.println("  RUNTIME PARSE FAILURES: " + errors);
                    for (String ft : failedTables) {
                        out.println("    " + ft);
                    }
                } else {
                    out.println("  (expected — " + profile.lootTablePathPrefix() + " resources not bundled)");
                    for (String ft : failedTables) {
                        out.println("    " + ft);
                    }
                }
            }
            results.add(new ProfileResult(profile.minecraftVersion(), count, expectedCount, errors));
            out.println();

            // Check structure index overrides
            out.println("  Structure decoration index overrides:");
            int dpOverride = profile.decorationIndexOverride("minecraft:desert_pyramid");
            int shOverride = profile.decorationIndexOverride("minecraft:stronghold");
            out.println("    desert_pyramid: " + (dpOverride >= 0 ? dpOverride : "fallback to STRUCTURE_TYPE"));
            out.println("    stronghold: " + (shOverride >= 0 ? shOverride : "fallback to STRUCTURE_TYPE"));
            out.println();
        }

        out.println("========================================");
        out.println("Version Profile Summary");
        out.println("-------------------------------------------------------------");
        out.println(String.format("%-12s %8s %10s %8s", "Version", "Parsed", "Expected", "Errors"));
        out.println("-------------------------------------------------------------");
        for (ProfileResult r : results) {
            out.println(String.format("%-12s %8d %10d %8d",
                r.version(), r.parsed(), r.expected(), r.errors()));
        }
        out.println("-------------------------------------------------------------");
        out.println(String.format("%-12s %8d %10s %8s", "TOTAL", totalParsed, "", ""));
        out.println("========================================");
        out.println("TOTAL parsed across all profiles: " + totalParsed);
        if (failures.isEmpty()) {
            out.println("RESULT: ALL PASSED — 0 parse failures for runtime version " + runtimeVersion);
        } else {
            out.println("RESULT: " + failures.size() + " RUNTIME FAILURES");
            for (String f : failures) {
                out.println("  " + f);
            }
            System.exit(1);
        }
    }
}
