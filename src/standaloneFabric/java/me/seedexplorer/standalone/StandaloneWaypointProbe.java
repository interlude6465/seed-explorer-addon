package me.seedexplorer.standalone;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** Focused persistence checks that run without starting Minecraft. */
public final class StandaloneWaypointProbe {
    private StandaloneWaypointProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("seed-explorer-waypoints-");
        Path path = directory.resolve("seed-explorer.properties");
        try {
            StandaloneSeedExplorerConfig config = StandaloneSeedExplorerConfig.load(path);
            config.setString("unknown-future-setting", "preserved");
            StandaloneWaypoint overworld = config.addWaypoint(42L, 0, 120, -80,
                "Village [120, -80]", "VILLAGE");
            StandaloneWaypoint duplicate = config.addWaypoint(42L, 0, 120, -80,
                "Duplicate", "MINESHAFT");
            check(overworld.id().equals(duplicate.id()), "Duplicate coordinates were not rejected");
            config.addWaypoint(42L, -1, 120, -80, "Nether marker", "FORTRESS");
            config.addWaypoint(99L, 0, 8, 9, "Other seed", "");
            config.save();

            StandaloneSeedExplorerConfig reloaded = StandaloneSeedExplorerConfig.load(path);
            List<StandaloneWaypoint> scoped = reloaded.waypoints(42L, 0);
            check(scoped.size() == 1, "Seed/dimension filtering failed");
            check(scoped.getFirst().name().equals("Village [120, -80]"), "Waypoint name changed");
            check(scoped.getFirst().structureType().equals("VILLAGE"), "Structure type changed");
            check(reloaded.waypoints(42L, -1).size() == 1, "Nether waypoint was lost");
            check(reloaded.waypoints(99L, 0).size() == 1, "Other-seed waypoint was lost");
            check(reloaded.stringValue("unknown-future-setting", "").equals("preserved"),
                "Unknown config setting was discarded");
            reloaded.updateWaypoint(overworld.id(), "Renamed base", -8, 17);
            var edited = StandaloneSeedExplorerConfig.load(path).waypoints(42L, 0).getFirst();
            check(edited.name().equals("Renamed base") && edited.x() == -8 && edited.z() == 17
                && edited.id().equals(overworld.id()), "Waypoint edit was not persisted with its original identity");

            Properties damaged = new Properties();
            try (Reader reader = Files.newBufferedReader(path)) {
                damaged.load(reader);
            }
            int count = Integer.parseInt(damaged.getProperty("waypoint.count"));
            damaged.setProperty("waypoint.count", Integer.toString(count + 1));
            damaged.setProperty("waypoint." + count + ".id", "damaged");
            damaged.setProperty("waypoint." + count + ".seed", "not-a-number");
            try (Writer writer = Files.newBufferedWriter(path)) {
                damaged.store(writer, "damaged probe entry");
            }

            StandaloneSeedExplorerConfig tolerant = StandaloneSeedExplorerConfig.load(path);
            check(tolerant.waypoints(42L, 0).size() == 1,
                "A malformed entry discarded valid waypoints");
            check(tolerant.removeWaypoint(overworld.id()), "Waypoint removal failed");
            tolerant.save();
            StandaloneSeedExplorerConfig removed = StandaloneSeedExplorerConfig.load(path);
            check(removed.waypoints(42L, 0).isEmpty(), "Removed waypoint returned after reload");

            System.out.println("Standalone waypoint probe passed: scoped persistence, duplicate "
                + "prevention, malformed-entry recovery, removal, and unknown-setting preservation");
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
