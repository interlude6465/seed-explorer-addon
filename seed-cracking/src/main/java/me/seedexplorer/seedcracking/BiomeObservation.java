package me.seedexplorer.seedcracking;

import java.util.Objects;

/**
 * One observed biome at a block position.
 *
 * <p>Biomes in 26.1+ are produced by the multi-noise biome source from a
 * {@code RandomState} seeded with the full 64-bit world seed. A biome
 * observation is therefore one of the strongest upper-bit verifiers, but only
 * if the {@link BiomeOracle} faithfully reproduces the target version's
 * climate sampling.</p>
 *
 * <p>The Y coordinate matters: cave biomes, deep dark, and vertical biome
 * blending can differ from the surface biome at the same X/Z.</p>
 */
public record BiomeObservation(int blockX, int blockY, int blockZ, String biomeId) {
    public BiomeObservation {
        Objects.requireNonNull(biomeId, "biomeId");
        if (biomeId.isBlank()) {
            throw new IllegalArgumentException("biomeId must not be blank");
        }
    }

    /** Convenience for a surface sample at sea level (Y = 63). */
    public static BiomeObservation atSurface(int blockX, int blockZ, String biomeId) {
        return new BiomeObservation(blockX, 63, blockZ, biomeId);
    }

    public String describe() {
        return biomeId + " at (" + blockX + ", " + blockY + ", " + blockZ + ")";
    }
}
