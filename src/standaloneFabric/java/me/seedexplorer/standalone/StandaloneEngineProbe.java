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
        verifyRegionCache();
        System.out.println("standalone engine probe: biome=" + biome.id());
    }

    private static void verifyRegionCache() {
        try {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("seed-explorer-regions-");
            System.setProperty("seedexplorer.structureCacheDir", root.toString());
            try {
                PredictionContext context = new PredictionContext(42L, "26.3", 0);
                var expected = signatures(VanillaStructurePredictor.predictDimension(context, -4, -4, 4, 4, false, false));
                var actual = signatures(StandalonePredictionCache.predictDimension(context, -4, -4, 4, 4, false, false));
                if (!actual.equals(expected)) throw new AssertionError("region cache changed range results");
                StandalonePredictionCache.clearMemory();
                var restored = signatures(StandalonePredictionCache.predictDimension(context, -4, -4, 4, 4, false, false));
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
}
