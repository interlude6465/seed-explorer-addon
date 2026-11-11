package me.seedexplorer.standalone;

import java.nio.file.Files;
import java.nio.file.Path;

/** Checks the complete native settings contract without starting Minecraft. */
public final class StandaloneSettingsProbe {
    private StandaloneSettingsProbe() {
    }

    public static void main(String[] args) throws Exception {
        StandaloneSeedExplorerSettingsScreen.validateDefinitions();
        require(StandaloneSeedExplorerSettingsScreen.settingCount() == 36,
            "native settings must retain the 32 original controls and four cracking controls");

        Path directory = Files.createTempDirectory("seed-explorer-settings-");
        Path path = directory.resolve("seed-explorer.properties");
        try {
            StandaloneSeedExplorerConfig config = StandaloneSeedExplorerConfig.load(path);
            config.setSeed(42L);
            config.setPredictionVersion("26.2");
            config.setBoolean("minimap", false);
            config.setDouble("zoom-step", 1.7, 1.01, 2.0);
            config.addWaypoint(42L, 0, 8, -9, "Probe", "VILLAGE");
            config.save();
            config.resetSettings();
            config.save();

            StandaloneSeedExplorerConfig reloaded = StandaloneSeedExplorerConfig.load(path);
            require(reloaded.seed() == 42L, "reset removed the selected seed");
            require(reloaded.predictionVersion().equals("26.2"),
                "reset removed the prediction version");
            require(reloaded.booleanValue("minimap", true),
                "reset did not remove boolean overrides");
            require(reloaded.doubleValue("zoom-step", 1.2, 1.01, 2.0) == 1.2,
                "reset did not remove numeric overrides");
            require(reloaded.waypoints(42L, 0).size() == 1,
                "reset removed persisted waypoints");
            reloaded.selectProfile("server:a");
            reloaded.setSeed(0L);
            reloaded.setMapDimension(-1);
            reloaded.setMapStructureFilters(-1, java.util.Set.of("BASTION", "FORTRESS"));
            reloaded.setMapStructureFilters(0, java.util.Set.of("VILLAGE"));
            reloaded.save();
            var mapReload = StandaloneSeedExplorerConfig.load(path);
            mapReload.selectProfile("server:a");
            require(mapReload.mapDimension(0) == -1
                && mapReload.mapStructureFilters(-1).equals(java.util.Set.of("BASTION", "FORTRESS"))
                && mapReload.mapStructureFilters(0).equals(java.util.Set.of("VILLAGE")),
                "map selections lost on restart or crossed dimensions");
            reloaded.setStructureVisible("VILLAGE", false);
            reloaded.toggleCompleted(0, "VILLAGE", 12, -34);
            reloaded.selectProfile("server:b");
            require(reloaded.mapStructureFilters(-1).isEmpty(), "map selections leaked across worlds");
            require(!reloaded.hasSeed(), "new server inherited another server's seed");
            require(reloaded.structureVisible("VILLAGE"), "structure filter leaked across servers");
            require(!reloaded.completed(0, "VILLAGE", 12, -34), "completion leaked across servers");
            reloaded.setSeed(-91L);
            reloaded.setPredictionVersion("26.3");
            reloaded.selectProfile("server:a");
            require(reloaded.hasSeed() && reloaded.seed() == 0L, "explicit zero seed lost on transition");
            require(reloaded.predictionVersion().equals("26.2"), "profile version lost");
            reloaded.resetSettings();
            reloaded.save();
            StandaloneSeedExplorerConfig restarted = StandaloneSeedExplorerConfig.load(path);
            restarted.selectProfile("server:b");
            require(restarted.seed() == -91L, "profile lost after restart");
            restarted.selectProfile("server:a");
            require(restarted.hasSeed() && restarted.seed() == 0L, "zero seed lost after restart");
            require(!restarted.structureVisible("VILLAGE"), "visibility lost after restart/reset");
            require(restarted.completed(0, "VILLAGE", 12, -34), "completion lost after restart/reset");
            System.out.println("Standalone settings probe: PASS (36 controls)");
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
