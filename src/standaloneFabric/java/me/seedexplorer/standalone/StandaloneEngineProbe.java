package me.seedexplorer.standalone;

import me.seedexplorer.addon.worldgen.PredictedBiome;
import me.seedexplorer.addon.worldgen.PredictionContext;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Artifact-level smoke test for the engine classes bundled in the standalone jar. */
public final class StandaloneEngineProbe {
    private StandaloneEngineProbe() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try {
            var method = WorldgenEngine.class.getDeclaredMethod("getCache", long.class, int.class);
            method.setAccessible(true);
            method.invoke(null, 42L, 0);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            throw new IllegalStateException("Standalone biome engine initialization failed",
                exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Standalone biome engine probe could not invoke engine", exception);
        }
        PredictedBiome biome = WorldgenEngine.getBiome(42L, 0, 0, 64, 0);
        if (biome == PredictedBiome.UNKNOWN || "unknown".equals(biome.id())) {
            throw new IllegalStateException("Standalone biome engine returned unknown after initialization");
        }
        VanillaStructurePredictor.predictDimension(
            new PredictionContext(42L, "26.3", 0), -8, -8, 8, 8, false, false);
        // Exercise the legacy overload from the isolated jar too. It must fall
        // back cleanly when the compatibility client's context bridge is absent.
        VanillaStructurePredictor.predictDimension(42L, -1,
            -32, -32, 32, 32, false, false);
        VanillaStructurePredictor.predictDimension(
            new PredictionContext(42L, "26.3", 1), -32, -32, 32, 32, false, false);
        verifyKnownCampStarts();
        verifyOracleStructureLocations();
        verifyRegionCache();
        System.out.println("standalone engine probe: biome=" + biome.id());
    }

    private static void verifyKnownCampStarts() {
        PredictionContext context = new PredictionContext(1L, "26.3", 0);
        CampStart[] starts = {
            new CampStart("bamboo_jungle", 897, -1089),
            new CampStart("birch_forest", -389, -538),
            new CampStart("cherry_grove", 851, 1057),
            new CampStart("dappled_forest", -681, 126),
            new CampStart("flower_forest", -946, -565),
            new CampStart("forest", 267, -146),
            new CampStart("meadow", 168, -830),
            new CampStart("old_growth_birch_forest", 543, 555),
            new CampStart("old_growth_pine_taiga", -1233, 280),
            new CampStart("old_growth_spruce_taiga", -701, 652),
            new CampStart("pale_garden", 1538, 167),
            new CampStart("savanna", -917, -909),
            new CampStart("snowy_taiga", 644, -813),
            new CampStart("sparse_jungle", 593, -492),
            new CampStart("swamp", 81, 228),
            new CampStart("taiga", -196, -69),
            new CampStart("windswept_forest", -236, 112),
            new CampStart("wooded_badlands", -690, -57)
        };
        for (CampStart expected : starts) {
            String id = "minecraft:abandoned_camp_" + expected.variant;
            boolean matched = VanillaStructurePredictor.predictStructure(context, id,
                    expected.chunkX, expected.chunkZ, expected.chunkX, expected.chunkZ)
                .stream().anyMatch(candidate -> candidate.startChunkX == expected.chunkX
                    && candidate.startChunkZ == expected.chunkZ && id.equals(candidate.variant));
            if (!matched) throw new AssertionError("missed known 26.3 camp " + id
                + " at " + expected.chunkX + ',' + expected.chunkZ);
        }

        var suppliedCandidate = VanillaStructurePredictor.predictDimension(context,
                93, -71, 93, -71, false, false).stream()
            .filter(candidate -> candidate.type == me.seedexplorer.addon.structures.StructureType.ABANDONED_CAMP)
            .map(candidate -> candidate.variant)
            .toList();
        System.out.println("standalone camp probe: oracleStarts=" + starts.length
            + " suppliedChunk93,-71=" + suppliedCandidate);

        boolean jungleMatched = VanillaStructurePredictor.predictStructure(context,
                "minecraft:jungle_pyramid", 258, -1054, 258, -1054)
            .stream().anyMatch(candidate -> candidate.startChunkX == 258
                && candidate.startChunkZ == -1054);
        if (!jungleMatched) {
            throw new AssertionError("missed known 26.3 jungle pyramid at 258,-1054");
        }
    }

    private static void verifyOracleStructureLocations() {
        PredictionContext context = new PredictionContext(1L, "26.3", 0);
        int[][] monuments = {{-241, -757}, {725, -402}};
        for (int[] chunk : monuments) {
            boolean matched = VanillaStructurePredictor.predictStructure(context,
                    "minecraft:monument", chunk[0], chunk[1], chunk[0], chunk[1])
                .stream().anyMatch(candidate -> candidate.startChunkX == chunk[0]
                    && candidate.startChunkZ == chunk[1]);
            if (!matched) throw new AssertionError("missed known 26.3 monument at "
                + chunk[0] + ',' + chunk[1]);
        }

        int[][] excludedOutposts = {{-201, 198}, {-187, 132}};
        for (int[] chunk : excludedOutposts) {
            boolean exposed = VanillaStructurePredictor.predictStructure(context,
                    "minecraft:pillager_outpost", chunk[0], chunk[1], chunk[0], chunk[1])
                .stream().anyMatch(candidate -> candidate.startChunkX == chunk[0]
                    && candidate.startChunkZ == chunk[1]);
            if (exposed) throw new AssertionError("exposed village-excluded outpost at "
                + chunk[0] + ',' + chunk[1]);
        }
    }

    private static void verifyRegionCache() {
        try {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("seed-explorer-regions-");
            System.setProperty("seedexplorer.structureCacheDir", root.toString());
            try {
                PredictionContext context = new PredictionContext(42L, "26.3", 0);
                var expected = signatures(VanillaStructurePredictor.predictDimension(context, -4, -4, 4, 4, false, false));
                java.util.List<StandalonePredictionCache.Progress> updates = new java.util.ArrayList<>();
                var actual = signatures(StandalonePredictionCache.predictDimensionProgressive(
                    context, -4, -4, 4, 4, false, false, () -> false, updates::add));
                if (!actual.equals(expected)) throw new AssertionError("region cache changed range results");
                if (updates.size() != 4) throw new AssertionError("region cache did not publish every region");
                var expectedCenter = signatures(VanillaStructurePredictor.predictDimension(
                    context, 0, 0, 4, 4, false, false));
                if (!signatures(updates.getFirst().structures()).equals(expectedCenter)) {
                    throw new AssertionError("region cache did not publish the center region first");
                }
                for (int index = 0; index < updates.size(); index++) {
                    var update = updates.get(index);
                    if (update.completedRegions() != index + 1 || update.totalRegions() != 4) {
                        throw new AssertionError("region cache published invalid progress");
                    }
                }
                var completed = updates.getLast();
                if (!completed.complete() || !signatures(completed.structures()).equals(expected)) {
                    throw new AssertionError("region cache final progress changed range results");
                }
                java.util.concurrent.atomic.AtomicBoolean cancel =
                    new java.util.concurrent.atomic.AtomicBoolean();
                java.util.List<StandalonePredictionCache.Progress> cancelledUpdates =
                    new java.util.ArrayList<>();
                var cancelled = StandalonePredictionCache.predictDimensionProgressive(
                    context, -4, -4, 36, 36, false, false, cancel::get, update -> {
                        cancelledUpdates.add(update);
                        cancel.set(true);
                    });
                if (!cancelled.isEmpty() || cancelledUpdates.size() != 1
                    || cancelledUpdates.getFirst().complete()) {
                    throw new AssertionError("region cache cancellation did not stop after publication");
                }
                StandalonePredictionCache.clearMemory();
                var restored = signatures(StandalonePredictionCache.predictDimension(context, -4, -4, 4, 4, false, false));
                java.util.List<StandalonePredictionCache.Progress> savedUpdates = new java.util.ArrayList<>();
                StandalonePredictionCache.clearMemory();
                StandalonePredictionCache.predictDimensionProgressive(context, -4, -4, 4, 4,
                    false, false, () -> false, savedUpdates::add);
                if (savedUpdates.size() != 1 || !savedUpdates.getFirst().complete()
                    || !signatures(savedUpdates.getFirst().structures()).equals(expected)) {
                    throw new AssertionError("saved markers were not restored together before prediction");
                }
                if (!restored.equals(expected)) throw new AssertionError("region cache round trip changed results");
                try (var paths = java.nio.file.Files.walk(root)) {
                    if (paths.noneMatch(p -> p.toString().endsWith(".json"))) throw new AssertionError("region cache was not persisted");
                }
            } finally {
                System.clearProperty("seedexplorer.structureCacheDir");
                StandalonePredictionCache.clearMemory();
                try (var paths = java.nio.file.Files.walk(root)) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
                }
            }
        } catch (java.io.IOException failure) { throw new AssertionError("cache probe IO", failure); }
    }
    private static java.util.Set<String> signatures(java.util.List<me.seedexplorer.addon.structures.GeneratedStructure> list) {
        return list.stream().map(s -> s.type + ":" + s.startChunkX + ":" + s.startChunkZ + ":" + s.x + ":" + s.y + ":" + s.z + ":" + s.variant)
            .collect(java.util.stream.Collectors.toSet());
    }

    private record CampStart(String variant, int chunkX, int chunkZ) {
    }
}
