package me.seedexplorer.addon.seed;

import me.seedexplorer.addon.loot.BundledLootTableLoader;
import me.seedexplorer.addon.worldgen.PredictionContext;

/** Bridges legacy active-client calls to explicit prediction inputs. */
public final class ActivePredictionContext {
    private ActivePredictionContext() {
    }

    public static PredictionContext current(int dimension) {
        SeedManager manager = manager();
        long seed = manager == null ? 0L : manager.getWorldSeed();
        String version = manager == null ? "" : manager.getMcVersion();
        return create(seed, version, dimension);
    }

    public static PredictionContext forSeed(long seed, int dimension) {
        SeedManager manager = manager();
        String version = manager == null ? "" : manager.getMcVersion();
        return create(seed, version, dimension);
    }

    private static PredictionContext create(long seed, String version, int dimension) {
        if (version == null || version.isBlank()) {
            version = BundledLootTableLoader.runtimeProfile().minecraftVersion();
        }
        return new PredictionContext(seed, version, dimension);
    }

    private static SeedManager manager() {
        try {
            return SeedManager.get();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
