package me.seedexplorer.seedcracking;

/** Version-matched biome source adapter that receives the sampled dimension. */
@FunctionalInterface
public interface DimensionBiomeOracle {
    /** Returns a namespaced biome ID, or null when the sample is unavailable. */
    String biomeAt(long worldSeed, Dimension dimension, int blockX, int blockY, int blockZ);
}
