package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.BundledLootTableLoader;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.LootTableDef;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.loot.VanillaLootTables;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.resources.ResourceKey;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

public final class VersionedLootProfileAudit {
    private static final String[] REQUIRED_TABLES = {
        "minecraft:chests/desert_pyramid",
        "minecraft:chests/stronghold_corridor",
        "minecraft:chests/stronghold_crossing",
        "minecraft:chests/stronghold_library",
        "minecraft:chests/shipwreck_treasure",
        "minecraft:chests/shipwreck_supply",
        "minecraft:chests/shipwreck_map",
        "minecraft:chests/pillager_outpost",
        "minecraft:chests/jungle_temple",
        "minecraft:chests/buried_treasure",
        "minecraft:chests/abandoned_mineshaft",
        "minecraft:chests/jungle_temple_dispenser"
    };
    private static final String[] CAMP_TABLES = {
        "minecraft:barrels/abandoned_camp_barrel",
        "minecraft:chests/abandoned_camp_common_chest",
        "minecraft:chests/abandoned_camp_secret_chest"
    };
    private static final Map<String, String> MINESHAFT_SHA256 = Map.of(
        "1.19.4", "fd8e11f7cb30bac2e121d9cba20bb57d501ec296d625c9f21c997dbddf4bb764",
        "1.20.4", "ac8e330ebb6592f38fc8bd1a5cea144714b600814c50449407128a530a596d1b",
        "1.21", "77acb9080edf27a0e18c426599eb9a73cabfda771be197ff4a5eb24e17dbfc76",
        "1.21.4", "77acb9080edf27a0e18c426599eb9a73cabfda771be197ff4a5eb24e17dbfc76",
        "1.21.5", "77acb9080edf27a0e18c426599eb9a73cabfda771be197ff4a5eb24e17dbfc76",
        "1.21.11", "77acb9080edf27a0e18c426599eb9a73cabfda771be197ff4a5eb24e17dbfc76",
        "26.1.2", "77acb9080edf27a0e18c426599eb9a73cabfda771be197ff4a5eb24e17dbfc76",
        "26.2", "344174c26f14b5c490dd57a9854b2e24e17be2990e60bdee4aaf31b6ddb95b19",
        "26.3", "03a36071a254948001b4733c930b5def46551d4e2afa80d6503b6bc7639b31e6"
    );

    private static final Case[] CASES = {
        new Case("desert_pyramid", 4717879387438598985L, 0, BuiltinStructures.DESERT_PYRAMID, -21, -370, 4),
        new Case("stronghold", 0L, 0, BuiltinStructures.STRONGHOLD, 125, 57, 3),
        new Case("shipwreck", 0L, 0, BuiltinStructures.SHIPWRECK, 1, -44, 3),
        new Case("outpost", 0L, 0, BuiltinStructures.PILLAGER_OUTPOST, 36, 103, 1),
        new Case("buried_treasure", 0L, 0, BuiltinStructures.BURIED_TREASURE, 0, -22, 1),
        new Case("jungle_temple", -2843430517209339837L, 0, BuiltinStructures.JUNGLE_TEMPLE, -241, 16, 4)
    };

    private VersionedLootProfileAudit() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        int checkedProfiles = 0;
        int checkedContainers = 0;
        verifyProvenance(failures);

        for (BundledLootTableLoader.VersionProfile profile : BundledLootTableLoader.supportedVersions()) {
            checkedProfiles++;
            append(report, "profile=" + profile.minecraftVersion()
                + " table_path=" + profile.lootTablePathPrefix());

            VanillaLootTables.resetForTest();
            VanillaLootTables.loadAllForProfile(profile);
            for (String tableId : REQUIRED_TABLES) {
                if (!VanillaLootTables.has(tableId)) {
                    failures.add(profile.minecraftVersion() + " missing_table " + tableId);
                }
            }
            verifyAuthoritativeProfile(profile, failures);

            if (BundledLootTableLoader.compareVersions(
                profile.minecraftVersion(), "1.21.11") < 0) {
                append(report, "  structure_replay_skipped=true reason=current_runtime_cannot_certify_historical_worldgen");
                continue;
            }

            for (Case c : CASES) {
                List<VanillaLootStructureSimulator.SimulatedContainer> containers =
                    VanillaLootStructureSimulator.simulate(c.seed, c.dimension, c.key, c.chunkX, c.chunkZ, profile);
                append(report, "  case=" + c.name + " containers=" + containers.size()
                    + " expected_min=" + c.expectedMinContainers
                    + " decoration_index=" + VanillaLootStructureSimulator.decorationIndex(c.key, profile));
                if (containers.size() < c.expectedMinContainers) {
                    failures.add(profile.minecraftVersion() + " " + c.name
                        + " containers expected_min=" + c.expectedMinContainers
                        + " actual=" + containers.size());
                }
                checkedContainers += containers.size();
                for (VanillaLootStructureSimulator.SimulatedContainer container : containers) {
                    LootTableDef table = VanillaLootTables.get(container.lootTableId());
                    if (table == null) {
                        failures.add(profile.minecraftVersion() + " missing_captured_table "
                            + container.lootTableId() + " case=" + c.name);
                        continue;
                    }
                    List<ItemLoot> first = LootTableSimulator.simulate(table, container.lootSeed(), 64);
                    List<ItemLoot> second = LootTableSimulator.simulate(table, container.lootSeed(), 64);
                    if (!first.equals(second)) {
                        failures.add(profile.minecraftVersion() + " nondeterministic_items table="
                            + container.lootTableId() + " seed=" + container.lootSeed());
                    }
                }
            }
        }

        try {
            BundledLootTableLoader.VersionProfile.forVersion("26.3-unsupported");
            failures.add("unsupported version silently selected another profile");
        } catch (IllegalArgumentException expected) {
            append(report, "unsupported_profile_rejected=true");
        }

        append(report, "checked_profiles=" + checkedProfiles
            + " checked_containers=" + checkedContainers
            + " versioned_loot_profile_audit_pass=" + failures.isEmpty()
            + " failures=" + failures.size());
        for (String failure : failures) append(report, "failure=" + failure);
        writeReport(report.toString());
        if (!failures.isEmpty()) System.exit(2);
    }

    private static void verifyAuthoritativeProfile(
        BundledLootTableLoader.VersionProfile profile, List<String> failures) {
        String expectedHash = MINESHAFT_SHA256.get(profile.minecraftVersion());
        try (InputStream in = BundledLootTableLoader.openAuthoritativeLootTable(
            profile, "minecraft:chests/abandoned_mineshaft")) {
            if (in == null) {
                failures.add(profile.minecraftVersion() + " missing authoritative mineshaft resource");
            } else {
                String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
                if (!actual.equals(expectedHash)) {
                    failures.add(profile.minecraftVersion() + " mineshaft resource hash expected="
                        + expectedHash + " actual=" + actual);
                }
            }
        } catch (Exception failure) {
            failures.add(profile.minecraftVersion() + " mineshaft resource read failed: "
                + failure.getMessage());
        }

        boolean campsExpected = BundledLootTableLoader.compareVersions(
            profile.minecraftVersion(), "26.3") >= 0;
        for (String tableId : CAMP_TABLES) {
            boolean present;
            try (InputStream in = BundledLootTableLoader.openAuthoritativeLootTable(profile, tableId)) {
                present = in != null;
            } catch (Exception failure) {
                present = false;
            }
            if (present != campsExpected || VanillaLootTables.has(tableId) != campsExpected) {
                failures.add(profile.minecraftVersion() + " camp table gate mismatch " + tableId);
            }
        }
    }

    private static void verifyProvenance(List<String> failures) {
        try (InputStream in = VersionedLootProfileAudit.class.getResourceAsStream(
            "/data/meteor-seed-explorer/versioned_loot/provenance.tsv")) {
            if (in == null) {
                failures.add("missing authoritative loot provenance");
                return;
            }
            String provenance = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (BundledLootTableLoader.VersionProfile profile :
                BundledLootTableLoader.supportedVersions()) {
                if (!provenance.contains("\n" + profile.minecraftVersion() + "\t")) {
                    failures.add("missing provenance row for " + profile.minecraftVersion());
                }
            }
        } catch (Exception failure) {
            failures.add("could not read authoritative loot provenance: " + failure.getMessage());
        }
    }

    private static void append(StringBuilder report, String line) {
        System.out.println(line);
        report.append(line).append('\n');
    }

    private static void writeReport(String report) {
        try {
            Path path = Path.of("build", "reports", "versioned-loot-profile-audit.txt");
            Files.createDirectories(path.getParent());
            Files.writeString(path, report);
        } catch (Exception ignored) {
        }
    }

    private record Case(String name, long seed, int dimension, ResourceKey<Structure> key,
                        int chunkX, int chunkZ, int expectedMinContainers) {
    }
}
