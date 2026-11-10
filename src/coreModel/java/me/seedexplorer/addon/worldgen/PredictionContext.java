package me.seedexplorer.addon.worldgen;

/** Immutable inputs shared by prediction engines. */
public record PredictionContext(long worldSeed, String minecraftVersion, int dimension) {
    public PredictionContext {
        minecraftVersion = minecraftVersion == null ? "" : minecraftVersion.trim();
    }

    public PredictionContext withDimension(int targetDimension) {
        return targetDimension == dimension
            ? this
            : new PredictionContext(worldSeed, minecraftVersion, targetDimension);
    }
}
