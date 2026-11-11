package me.seedexplorer.seedcracking;

import java.util.Objects;

/** Two sampled points straddling an observed biome boundary. */
public record BiomeBoundaryObservation(
    int firstX, int firstY, int firstZ, String firstBiome,
    int secondX, int secondY, int secondZ, String secondBiome
) {
    public BiomeBoundaryObservation {
        Objects.requireNonNull(firstBiome, "firstBiome");
        Objects.requireNonNull(secondBiome, "secondBiome");
        if (firstBiome.isBlank() || secondBiome.isBlank()) {
            throw new IllegalArgumentException("boundary biome ids must not be blank");
        }
        if (firstBiome.equals(secondBiome)) {
            throw new IllegalArgumentException("boundary samples must have different biome ids");
        }
    }
}
