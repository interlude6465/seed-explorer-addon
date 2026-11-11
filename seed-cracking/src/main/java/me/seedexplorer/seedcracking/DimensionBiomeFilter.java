package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/** Full-seed filter for biome observations spanning one or more dimensions. */
public final class DimensionBiomeFilter implements WorldSeedFilter {
    private final DimensionBiomeOracle oracle;
    private final List<DimensionBiomeObservation> observations;

    public DimensionBiomeFilter(
        DimensionBiomeOracle oracle,
        List<DimensionBiomeObservation> observations
    ) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("At least one biome observation is required");
        }
        this.observations = List.copyOf(observations);
    }

    @Override
    public boolean matches(long worldSeed) {
        for (DimensionBiomeObservation observation : observations) {
            String actual = oracle.biomeAt(worldSeed, observation.dimension(),
                observation.blockX(), observation.blockY(), observation.blockZ());
            if (actual == null || !observation.biomeId().equals(actual)) {
                return false;
            }
        }
        return true;
    }

    public List<DimensionBiomeObservation> observations() {
        return observations;
    }

    @Override
    public String description() {
        return "dimension-aware biome filter (" + observations.size() + " sample(s))";
    }
}
