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
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

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
        "minecraft:chests/jungle_temple_dispenser"
    };

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

        for (BundledLootTableLoader.VersionProfile profile : BundledLootTableLoader.supportedVersions()) {
            if (BundledLootTableLoader.compareVersions(profile.minecraftVersion(), "1.21.11") < 0) continue;
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

        append(report, "checked_profiles=" + checkedProfiles
            + " checked_containers=" + checkedContainers
            + " versioned_loot_profile_audit_pass=" + failures.isEmpty()
            + " failures=" + failures.size());
        for (String failure : failures) append(report, "failure=" + failure);
        writeReport(report.toString());
        if (!failures.isEmpty()) System.exit(2);
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
