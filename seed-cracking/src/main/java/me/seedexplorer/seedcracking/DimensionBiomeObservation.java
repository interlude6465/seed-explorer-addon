package me.seedexplorer.seedcracking;

import java.util.Objects;

/** A biome observed at block coordinates in a specific vanilla dimension. */
public record DimensionBiomeObservation(
    Dimension dimension,
    int blockX,
    int blockY,
    int blockZ,
    String biomeId
) {
    public DimensionBiomeObservation {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(biomeId, "biomeId");
        if (biomeId.isBlank()) {
            throw new IllegalArgumentException("biomeId must not be blank");
        }
    }
}
