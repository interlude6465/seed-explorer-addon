package me.seedexplorer.standalone;

import com.google.gson.Gson;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.worldgen.PredictionContext;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
                                                            BooleanSupplier cancelled) {
        List<GeneratedStructure> result = new ArrayList<>();
        for (int rz = Math.floorDiv(minZ, REGION); rz <= Math.floorDiv(maxZ, REGION); rz++) {
            for (int rx = Math.floorDiv(minX, REGION); rx <= Math.floorDiv(maxX, REGION); rx++) {
                if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) return List.of();
                Key key = new Key(context, rx, rz, treasure, mineshafts);
                for (GeneratedStructure structure : region(key)) {
                    if (structure.startChunkX >= minX && structure.startChunkX <= maxX
                        && structure.startChunkZ >= minZ && structure.startChunkZ <= maxZ) {
                        result.add(structure);
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Predicts cache regions from the center outward and publishes an immutable accumulated
     * snapshot after every region. Large map views can therefore display nearby markers while
     * the outer prediction rings continue in the background.
     */
    public static List<GeneratedStructure> predictDimensionProgressive(
            PredictionContext context, int minX, int minZ, int maxX, int maxZ,
            boolean treasure, boolean mineshafts, BooleanSupplier cancelled,
            Consumer<Progress> progress) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(progress, "progress");

        int chunkMinX = Math.min(minX, maxX);
        int chunkMinZ = Math.min(minZ, maxZ);
        int chunkMaxX = Math.max(minX, maxX);
        int chunkMaxZ = Math.max(minZ, maxZ);
        int regionMinX = Math.floorDiv(chunkMinX, REGION);
        int regionMinZ = Math.floorDiv(chunkMinZ, REGION);
        int regionMaxX = Math.floorDiv(chunkMaxX, REGION);
        int regionMaxZ = Math.floorDiv(chunkMaxZ, REGION);
        int centerX = (int) Math.floorDiv(
            Math.floorDiv((long) chunkMinX + chunkMaxX, 2L), REGION);
        int centerZ = (int) Math.floorDiv(
            Math.floorDiv((long) chunkMinZ + chunkMaxZ, 2L), REGION);
        List<RegionCoordinate> regions = new ArrayList<>(
            (regionMaxX - regionMinX + 1) * (regionMaxZ - regionMinZ + 1));
        for (int rz = regionMinZ; rz <= regionMaxZ; rz++) {
            for (int rx = regionMinX; rx <= regionMaxX; rx++) {
                regions.add(new RegionCoordinate(rx, rz));
            }
        }
        regions.sort(Comparator
            .comparingInt((RegionCoordinate region) -> region.ringDistance(centerX, centerZ))
            .thenComparingLong(region -> region.distanceSquared(centerX, centerZ))
            .thenComparingInt(RegionCoordinate::z)
            .thenComparingInt(RegionCoordinate::x));

        List<GeneratedStructure> result = new ArrayList<>();
        int completed = 0;
        for (RegionCoordinate region : regions) {
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) return List.of();
            Key key = new Key(context, region.x, region.z, treasure, mineshafts);
            for (GeneratedStructure structure : region(key)) {
                if (structure.startChunkX >= chunkMinX && structure.startChunkX <= chunkMaxX
                    && structure.startChunkZ >= chunkMinZ && structure.startChunkZ <= chunkMaxZ) {
                    result.add(structure);
                }
            }
            completed++;
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) return List.of();
            progress.accept(new Progress(List.copyOf(result), completed, regions.size()));
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
    private record RegionCoordinate(int x, int z) {
        int ringDistance(int centerX, int centerZ) {
            return Math.max(Math.abs(x - centerX), Math.abs(z - centerZ));
        }

        long distanceSquared(int centerX, int centerZ) {
            long dx = (long) x - centerX;
            long dz = (long) z - centerZ;
            return dx * dx + dz * dz;
        }
    }

    public record Progress(List<GeneratedStructure> structures,
                           int completedRegions, int totalRegions) {
        public Progress {
            structures = List.copyOf(structures);
            if (completedRegions < 0 || completedRegions > totalRegions || totalRegions < 0) {
                throw new IllegalArgumentException("Invalid structure prediction progress");
            }
        }

        public boolean complete() {
            return completedRegions == totalRegions;
        }
    }
}
