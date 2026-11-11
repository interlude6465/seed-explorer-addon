package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/** A named terrain profile made from several surface-height observations. */
public record TerrainProfileObservation(String id, List<TerrainObservation> samples) {
    public TerrainProfileObservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(samples, "samples");
        if (id.isBlank()) {
            throw new IllegalArgumentException("profile id must not be blank");
        }
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("terrain profile must contain at least one sample");
        }
        samples = List.copyOf(samples);
    }
}
