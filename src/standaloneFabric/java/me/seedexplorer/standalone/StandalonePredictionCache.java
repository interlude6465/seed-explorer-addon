package me.seedexplorer.standalone;

import com.google.gson.Gson;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.worldgen.PredictionContext;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import java.nio.file.*;
import java.util.*;

/** Worker-only, version-scoped region cache shared by search, 2D and 3D. */
public final class StandalonePredictionCache {
    private static final int REGION = 32;
    private static final int FORMAT = 1;
    private static final Gson JSON = new Gson();
    private static final Map<Key, List<GeneratedStructure>> CACHE = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, List<GeneratedStructure>> e) { return size() > 256; }
    };
    private StandalonePredictionCache() { }
    static void clearMemory() { synchronized (CACHE) { CACHE.clear(); } }
    public static List<GeneratedStructure> predictDimension(PredictionContext context, int minX, int minZ,
                                                            int maxX, int maxZ, boolean treasure, boolean mineshafts) {
        return predictDimension(context, minX, minZ, maxX, maxZ, treasure, mineshafts, () -> false);
    }
    public static List<GeneratedStructure> predictDimension(PredictionContext context, int minX, int minZ,
                                                            int maxX, int maxZ, boolean treasure, boolean mineshafts,
                                                            java.util.function.BooleanSupplier cancelled) {
        List<GeneratedStructure> result = new ArrayList<>();
        for (int rz = Math.floorDiv(minZ, REGION); rz <= Math.floorDiv(maxZ, REGION); rz++) {
            for (int rx = Math.floorDiv(minX, REGION); rx <= Math.floorDiv(maxX, REGION); rx++) {
                if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) return List.of();
                Key key = new Key(context, rx, rz, treasure, mineshafts);
                for (GeneratedStructure s : region(key)) {
                    if (s.startChunkX >= minX && s.startChunkX <= maxX && s.startChunkZ >= minZ && s.startChunkZ <= maxZ)
                        result.add(s);
                }
            }
        }
        return List.copyOf(result);
    }
    private static List<GeneratedStructure> region(Key key) {
        synchronized (CACHE) { List<GeneratedStructure> hit = CACHE.get(key); if (hit != null) return hit; }
        Path file = file(key);
        try {
            if (Files.isRegularFile(file) && Files.size(file) < 2_000_000) {
                Data data = JSON.fromJson(Files.readString(file), Data.class);
                if (data != null && data.format == FORMAT && key.equals(data.key) && data.structures != null
                    && data.structures.size() < 20_000 && data.structures.stream().allMatch(s -> s != null && s.type != null && s.variant != null)) {
                    List<GeneratedStructure> hit = List.copyOf(data.structures);
                    synchronized (CACHE) { CACHE.put(key, hit); }
                    return hit;
                }
            }
        } catch (Exception ignored) { /* Corrupt caches are regenerated. */ }
        List<GeneratedStructure> computed = VanillaStructurePredictor.predictDimension(key.context,
            key.x * REGION, key.z * REGION, key.x * REGION + REGION - 1, key.z * REGION + REGION - 1,
            key.treasure, key.mineshafts);
        synchronized (CACHE) { CACHE.put(key, computed); }
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "region-", ".tmp");
            Files.writeString(temporary, JSON.toJson(new Data(FORMAT, key, computed)));
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception ignored) { /* Disk caching is best effort. */ }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { } }
        return computed;
    }
    private static Path file(Key key) {
        String scope = Long.toHexString(key.context.worldSeed()) + "-" + key.context.dimension() + "-"
            + key.context.minecraftVersion().replaceAll("[^A-Za-z0-9._-]", "_");
        return Path.of(System.getProperty("seedexplorer.structureCacheDir", ".cache/seed-explorer-standalone-structures"),
            "v" + FORMAT, scope, key.x + "_" + key.z + "_" + key.treasure + "_" + key.mineshafts + ".json");
    }
    private record Key(PredictionContext context, int x, int z, boolean treasure, boolean mineshafts) { }
    private record Data(int format, Key key, List<GeneratedStructure> structures) { }
}
