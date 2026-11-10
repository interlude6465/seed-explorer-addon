package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.ChestLootPredictor;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.PredictionContext;
import me.seedexplorer.addon.worldgen.PredictionVersionPolicy;

/** Pure checks for the selected-profile content boundary. */
public final class PredictionVersionPolicyProbe {
    private PredictionVersionPolicyProbe() {
    }

    public static void main(String[] args) {
        require(!PredictionVersionPolicy.supportsStructure("1.19.4", StructureType.TRAIL_RUINS),
            "Trail Ruins leaked into 1.19.4");
        require(PredictionVersionPolicy.supportsStructure("1.20.4", StructureType.TRAIL_RUINS),
            "Trail Ruins missing from 1.20.4");
        require(!PredictionVersionPolicy.supportsStructure("1.20.4", StructureType.TRIAL_CHAMBER),
            "Trial Chambers leaked into 1.20.4");
        require(PredictionVersionPolicy.supportsStructure("1.21", StructureType.TRIAL_CHAMBER),
            "Trial Chambers missing from 1.21");
        require(!PredictionVersionPolicy.supportsStructure("26.2", StructureType.ABANDONED_CAMP),
            "Abandoned Camps leaked into 26.2");
        require(PredictionVersionPolicy.supportsStructure("26.3", StructureType.ABANDONED_CAMP),
            "Abandoned Camps missing from 26.3");

        require(!PredictionVersionPolicy.supportsBiome("26.1.2", "sulfur_caves"),
            "Sulfur Caves leaked into 26.1.2");
        require(PredictionVersionPolicy.supportsBiome("26.2", "minecraft:sulfur_caves"),
            "Sulfur Caves missing from 26.2");
        require(!PredictionVersionPolicy.supportsBiome("26.2", "dappled forest"),
            "Dappled Forest leaked into 26.2");
        require(PredictionVersionPolicy.supportsBiome("26.3", "minecraft:dappled_forest"),
            "Dappled Forest missing from 26.3");
        require(!PredictionVersionPolicy.supportsPoplar("26.2"),
            "Poplar content leaked into 26.2");
        require(PredictionVersionPolicy.supportsPoplar("26.3"),
            "Poplar content missing from 26.3");

        require(PredictionVersionPolicy.supportsStructure("26.3", StructureType.VILLAGE),
            "baseline structures must remain enabled");
        PredictionContext context26_2 = new PredictionContext(1L, "26.2", 0);
        PredictionContext moved = context26_2.withDimension(-1);
        require(moved.worldSeed() == 1L && moved.dimension() == -1
                && moved.minecraftVersion().equals("26.2"),
            "prediction context dimension copy changed seed/version");
        require(!ChestLootPredictor.canPredict(context26_2, StructureType.ABANDONED_CAMP),
            "explicit 26.2 loot context leaked Abandoned Camps");
        require(ChestLootPredictor.canPredict(
                new PredictionContext(1L, "26.3", 0), StructureType.ABANDONED_CAMP),
            "explicit 26.3 loot context omitted Abandoned Camps");
        require(PredictionVersionPolicy.compareVersions("1.21", "1.21.0") == 0,
            "equivalent version forms compare differently");
        System.out.println("Prediction version policy probe: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
