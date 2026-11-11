package me.seedexplorer.seedcracking;

/**
 * Samples the 26.1+ biome source for a candidate world seed.
 *
 * <p>There is deliberately no Minecraft dependency here. An adapter can be
 * written in the mod (or in a test harness) that creates a version-matched
 * {@code RandomState} and calls {@code BiomeSource.getNoiseBiome}. The adapter
 * must use the same Minecraft version, dimension noise settings, and the full
 * world seed.</p>
 */
@FunctionalInterface
public interface BiomeOracle {
    /**
     * Returns a namespaced biome id such as {@code minecraft:plains}, or
     * {@code null} when the sample is unavailable.
     *
     * <p>Coordinates are block coordinates, not chunk coordinates.</p>
     */
    String biomeAt(long worldSeed, int blockX, int blockY, int blockZ);

    default BiomeFilter filter(BiomeObservation... observations) {
        return new BiomeFilter(this, java.util.List.of(observations));
    }
}
