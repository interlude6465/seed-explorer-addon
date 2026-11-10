package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.StandaloneStructureLootPredictor;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.PrintStream;
import java.util.stream.Collectors;

/** Artifact-level exact loot replay for a pinned 26.3 abandoned camp. */
public final class StandaloneLootProbe {
    private static final PrintStream OUT = System.out;
    private static final long WORLD_SEED = 42L;
    private static final int START_CHUNK_X = 40;
    private static final int START_CHUNK_Z = -120;
    private static final ResourceKey<Structure> CAMP = ResourceKey.create(Registries.STRUCTURE,
        Identifier.parse("minecraft:abandoned_camp_windswept_forest"));

    private StandaloneLootProbe() {
    }

    public static void main(String[] args) {
        try {
            runProbe();
        } catch (Throwable failure) {
            OUT.println("standalone loot probe failed: " + failure);
            failure.printStackTrace(OUT);
            OUT.flush();
            System.exit(1);
        }
    }

    private static void runProbe() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        System.setErr(OUT);

        PredictionContext context = new PredictionContext(WORLD_SEED, "26.3", 0);
        GeneratedStructure marker = new GeneratedStructure(
            START_CHUNK_X * 16, START_CHUNK_Z * 16, START_CHUNK_X, START_CHUNK_Z,
            StructureType.ABANDONED_CAMP,
            "minecraft:abandoned_camp_windswept_forest", false);
        require(CAMP.equals(StandaloneStructureLootPredictor.structureKey(context, marker)),
            "standalone map marker resolved to the wrong camp structure key");
        List<ChestLootOutput> outputs = StandaloneStructureLootPredictor.predict(
            context, marker, 64);
        require(outputs.size() == 3, "expected three camp containers, got " + outputs.size());
        require(outputs.stream().allMatch(ChestLootOutput::exact),
            "standalone replay fell back to approximate loot");
        for (ChestLootOutput output : outputs) {
            OUT.println(output.lootTableId() + " " + output.predictedCounts());
        }

        Set<String> tables = outputs.stream().map(ChestLootOutput::lootTableId)
            .collect(Collectors.toUnmodifiableSet());
        require(tables.equals(Set.of(
            "minecraft:barrels/abandoned_camp_barrel",
            "minecraft:chests/abandoned_camp_common_chest",
            "minecraft:chests/abandoned_camp_secret_chest")),
            "unexpected camp tables " + tables);

        Map<String, Long> seeds = outputs.stream().collect(Collectors.toUnmodifiableMap(
            ChestLootOutput::lootTableId, ChestLootOutput::seed));
        require(seeds.get("minecraft:chests/abandoned_camp_common_chest") == 3565625889219861159L,
            "common chest seed drifted");
        require(seeds.get("minecraft:chests/abandoned_camp_secret_chest") == -7067353347797955036L,
            "secret chest seed drifted");
        require(seeds.get("minecraft:barrels/abandoned_camp_barrel") == 8971376422279707887L,
            "barrel seed drifted");

        ChestLootOutput common = outputs.stream()
            .filter(output -> output.lootTableId().endsWith("common_chest"))
            .findFirst().orElseThrow();
        require(common.predictedCounts().getOrDefault("minecraft:copper_spear", 0) == 1,
            "26.3 copper spear loot is missing");

        verifyVillageKeys(context);
        verifyDungeonReplay();
        StandaloneDoubleChestProbe.verify();

        OUT.println("standalone loot probe: exact containers=" + outputs.size()
            + " items=" + outputs.stream().mapToInt(output -> output.predictedItems().size()).sum());
    }

    private static void verifyVillageKeys(PredictionContext context) {
        Map<String, ResourceKey<Structure>> expected = Map.of(
            "minecraft:village_plains", BuiltinStructures.VILLAGE_PLAINS,
            "minecraft:village_desert", BuiltinStructures.VILLAGE_DESERT,
            "minecraft:village_savanna", BuiltinStructures.VILLAGE_SAVANNA,
            "minecraft:village_snowy", BuiltinStructures.VILLAGE_SNOWY,
            "minecraft:village_taiga", BuiltinStructures.VILLAGE_TAIGA);
        for (Map.Entry<String, ResourceKey<Structure>> entry : expected.entrySet()) {
            GeneratedStructure village = new GeneratedStructure(0, 0, 0, 0,
                StructureType.VILLAGE, entry.getKey(), false);
            require(entry.getValue().equals(
                StandaloneStructureLootPredictor.structureKey(context, village)),
                "wrong standalone village key for " + entry.getKey());
        }
    }

    private static void verifyDungeonReplay() {
        long seed = -5330673357612662400L;
        PredictionContext context = new PredictionContext(seed, "26.3", 0);
        GeneratedStructure dungeon = new GeneratedStructure(-606, -20, -275, -38, -18,
            StructureType.DUNGEON, "minecraft:skeleton", false);
        List<ChestLootOutput> outputs = StandaloneStructureLootPredictor.predict(context, dungeon, 64);
        require(!outputs.isEmpty(), "standalone dungeon replay returned no containers");
        require(outputs.stream().allMatch(ChestLootOutput::exact),
            "standalone dungeon replay returned approximate loot");
        require(outputs.stream().allMatch(output ->
            output.lootTableId().equals("minecraft:chests/simple_dungeon")),
            "standalone dungeon replay returned another loot table");
        Set<Long> expectedSeeds = Set.of(-8144953013160125626L, 3527612760189953148L,
            8433468082816297385L);
        require(outputs.stream().allMatch(output -> expectedSeeds.contains(output.seed())),
            "standalone dungeon loot seed drifted");
        OUT.println("standalone dungeon probe: exact containers=" + outputs.size());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
