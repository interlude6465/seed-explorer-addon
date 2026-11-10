package me.seedexplorer.standalone;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/** Minimal standalone state; prediction settings move here as adapters are extracted. */
public final class StandaloneSeedExplorerConfig {
    private static final String WAYPOINT_PREFIX = "waypoint.";
    private static final int MAX_PERSISTED_WAYPOINTS = 10_000;

    private final Path path;
    private volatile long seed;
    private volatile String predictionVersion = "26.3";
    private volatile boolean seedSet;
    private String profile = "";
    private final Properties values = new Properties();
    private final List<StandaloneWaypoint> waypoints = new ArrayList<>();

    private StandaloneSeedExplorerConfig(Path path) {
        this.path = path;
    }

    public static StandaloneSeedExplorerConfig load() {
        return load(FabricLoader.getInstance().getConfigDir().resolve("seed-explorer.properties"));
    }

    static StandaloneSeedExplorerConfig load(Path path) {
        StandaloneSeedExplorerConfig config = new StandaloneSeedExplorerConfig(path);
        if (!Files.isRegularFile(path)) return config;
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            values.load(reader);
            config.values.putAll(values);
            config.seed = parseLong(values.getProperty("seed"), 0L);
            config.seedSet = Boolean.parseBoolean(values.getProperty("seedSet", Boolean.toString(config.seed != 0L)));
            config.predictionVersion = validVersionOrDefault(values.getProperty("predictionVersion", "26.3"));
            config.loadWaypoints(values);
        } catch (IOException | IllegalArgumentException ignored) {
            // Defaults keep the frontend usable if a partial config cannot be read.
        }
        return config;
    }

    public void save() {
        values.setProperty("seed", Long.toString(seed));
        values.setProperty("seedSet", Boolean.toString(seedSet));
        values.setProperty("predictionVersion", predictionVersion);
        saveProfile();
        writeWaypoints();
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                values.store(writer, "Seed Explorer standalone settings");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // A read-only config directory must not crash the client.
        }
    }

    public long seed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
        seedSet = true;
    }

    public boolean hasSeed() { return seedSet; }

    /** Client-thread world transition. New servers never inherit another server's seed. */
    public void selectProfile(String identity) {
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(identity.getBytes(StandardCharsets.UTF_8));
        if (profile.equals(key)) return;
        boolean first = profile.isEmpty();
        saveProfile();
        profile = key;
        String prefix = "profile." + profile + ".";
        if (values.containsKey(prefix + "seed")) {
            seed = parseLong(values.getProperty(prefix + "seed"), 0L);
            seedSet = Boolean.parseBoolean(values.getProperty(prefix + "seedSet", "true"));
            predictionVersion = validVersionOrDefault(values.getProperty(prefix + "version", "26.3"));
        } else if (!first || values.containsKey("profiles.migrated")) {
            seed = 0L; seedSet = false; predictionVersion = "26.3";
        }
        values.setProperty("profiles.migrated", "true");
        saveProfile();
    }

    private void saveProfile() {
        if (profile.isEmpty()) return;
        String prefix = "profile." + profile + ".";
        values.setProperty(prefix + "seed", Long.toString(seed));
        values.setProperty(prefix + "seedSet", Boolean.toString(seedSet));
        values.setProperty(prefix + "version", predictionVersion);
    }

    public boolean structureVisible(String type) {
        return Boolean.parseBoolean(values.getProperty("profile." + profile + ".structure." + type, "true"));
    }
    public void setStructureVisible(String type, boolean visible) {
        values.setProperty("profile." + profile + ".structure." + type, Boolean.toString(visible));
    }
    public boolean completed(int dimension, String type, int x, int z) {
        return Boolean.parseBoolean(values.getProperty(completedKey(dimension, type, x, z), "false"));
    }
    public boolean toggleCompleted(int dimension, String type, int x, int z) {
        boolean next = !completed(dimension, type, x, z);
        values.setProperty(completedKey(dimension, type, x, z), Boolean.toString(next));
        save(); return next;
    }
    private String completedKey(int dimension, String type, int x, int z) {
        return "completed." + profile + "." + seed + "." + dimension + "." + type + "." + x + "." + z;
    }

    public String predictionVersion() {
        return predictionVersion;
    }

    public void setPredictionVersion(String version) {
        if (version == null || !supportedVersions().contains(version.trim()))
            throw new IllegalArgumentException("Supported prediction versions: " + String.join(", ", supportedVersions()));
        predictionVersion = version.trim();
    }
    public static List<String> supportedVersions() {
        return java.util.Arrays.stream(me.seedexplorer.addon.loot.BundledLootTableLoader.supportedVersions())
            .map(me.seedexplorer.addon.loot.BundledLootTableLoader.VersionProfile::minecraftVersion).toList();
    }
    private static String validVersionOrDefault(String version) {
        return supportedVersions().contains(version) ? version : "26.3";
    }

    public boolean booleanValue(String key, boolean fallback) {
        String value = values.getProperty(settingKey(key));
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    public void setBoolean(String key, boolean value) {
        values.setProperty(settingKey(key), Boolean.toString(value));
    }

    public int intValue(String key, int fallback, int min, int max) {
        int value;
        try {
            value = Integer.parseInt(values.getProperty(settingKey(key), Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            value = fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    public void setInt(String key, int value, int min, int max) {
        values.setProperty(settingKey(key), Integer.toString(Math.max(min, Math.min(max, value))));
    }

    public double doubleValue(String key, double fallback, double min, double max) {
        double value;
        try {
            value = Double.parseDouble(values.getProperty(settingKey(key), Double.toString(fallback)));
        } catch (NumberFormatException ignored) {
            value = fallback;
        }
        if (!Double.isFinite(value)) value = fallback;
        return Math.max(min, Math.min(max, value));
    }

    public void setDouble(String key, double value, double min, double max) {
        double clamped = Math.max(min, Math.min(max, value));
        values.setProperty(settingKey(key), Double.toString(clamped));
    }

    public String stringValue(String key, String fallback) {
        return values.getProperty(settingKey(key), fallback);
    }

    public void setString(String key, String value) {
        values.setProperty(settingKey(key), value == null ? "" : value);
    }

    public void resetSettings() {
        for (String key : List.copyOf(values.stringPropertyNames())) {
            if (key.startsWith("setting.")) values.remove(key);
        }
    }

    public List<StandaloneWaypoint> waypoints(long worldSeed, int dimension) {
        return waypoints.stream()
            .filter(waypoint -> waypoint.worldSeed() == worldSeed
                && waypoint.dimension() == dimension)
            .toList();
    }

    public StandaloneWaypoint addWaypoint(long worldSeed, int dimension, int x, int z,
                                           String name, String structureType) {
        StandaloneWaypoint existing = waypointAt(worldSeed, dimension, x, z);
        if (existing != null) return existing;
        StandaloneWaypoint waypoint = new StandaloneWaypoint(UUID.randomUUID().toString(), worldSeed,
            name, x, z, dimension, structureType);
        waypoints.add(waypoint);
        return waypoint;
    }

    public boolean removeWaypoint(String id) {
        return id != null && waypoints.removeIf(waypoint -> id.equals(waypoint.id()));
    }
    public StandaloneWaypoint updateWaypoint(String id, String name, int x, int z) {
        for (int i = 0; i < waypoints.size(); i++) {
            StandaloneWaypoint old = waypoints.get(i);
            if (!old.id().equals(id)) continue;
            StandaloneWaypoint occupied = waypointAt(old.worldSeed(), old.dimension(), x, z);
            if (occupied != null && !occupied.id().equals(id)) throw new IllegalArgumentException("A waypoint already exists there.");
            StandaloneWaypoint replacement = new StandaloneWaypoint(id, old.worldSeed(), name,
                x, z, old.dimension(), old.structureType());
            waypoints.set(i, replacement);
            save();
            return replacement;
        }
        return null;
    }

    public StandaloneWaypoint waypointAt(long worldSeed, int dimension, int x, int z) {
        for (StandaloneWaypoint waypoint : waypoints) {
            if (waypoint.worldSeed() == worldSeed && waypoint.dimension() == dimension
                && waypoint.x() == x && waypoint.z() == z) return waypoint;
        }
        return null;
    }

    public boolean hasWaypointAt(long worldSeed, int dimension, int x, int z) {
        return waypointAt(worldSeed, dimension, x, z) != null;
    }

    private void loadWaypoints(Properties source) {
        int count = parseInt(source.getProperty("waypoint.count"), 0);
        count = Math.max(0, Math.min(MAX_PERSISTED_WAYPOINTS, count));
        for (int index = 0; index < count; index++) {
            String prefix = WAYPOINT_PREFIX + index + ".";
            try {
                String id = required(source, prefix + "id");
                long worldSeed = Long.parseLong(required(source, prefix + "seed"));
                String name = source.getProperty(prefix + "name", "");
                int x = Integer.parseInt(required(source, prefix + "x"));
                int z = Integer.parseInt(required(source, prefix + "z"));
                int dimension = Integer.parseInt(required(source, prefix + "dimension"));
                String structureType = source.getProperty(prefix + "structureType", "");
                if (!hasWaypointId(id) && waypointAt(worldSeed, dimension, x, z) == null) {
                    waypoints.add(new StandaloneWaypoint(id, worldSeed, name, x, z,
                        dimension, structureType));
                }
            } catch (RuntimeException ignored) {
                // A damaged waypoint must not discard the rest of the standalone config.
            }
        }
    }

    private boolean hasWaypointId(String id) {
        for (StandaloneWaypoint waypoint : waypoints) {
            if (waypoint.id().equals(id)) return true;
        }
        return false;
    }

    private void writeWaypoints() {
        for (String key : List.copyOf(values.stringPropertyNames())) {
            if (key.startsWith(WAYPOINT_PREFIX)) values.remove(key);
        }
        values.setProperty("waypoint.count", Integer.toString(waypoints.size()));
        for (int index = 0; index < waypoints.size(); index++) {
            StandaloneWaypoint waypoint = waypoints.get(index);
            String prefix = WAYPOINT_PREFIX + index + ".";
            values.setProperty(prefix + "id", waypoint.id());
            values.setProperty(prefix + "seed", Long.toString(waypoint.worldSeed()));
            values.setProperty(prefix + "name", waypoint.name());
            values.setProperty(prefix + "x", Integer.toString(waypoint.x()));
            values.setProperty(prefix + "z", Integer.toString(waypoint.z()));
            values.setProperty(prefix + "dimension", Integer.toString(waypoint.dimension()));
            values.setProperty(prefix + "structureType", waypoint.structureType());
        }
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value.trim();
    }

    private static String settingKey(String key) {
        return "setting." + key;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
