package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/** Full-seed filter for sampled climate/temperature values. */
public final class ClimateFilter implements WorldSeedFilter {
    private final ClimateOracle oracle;
    private final List<ClimateObservation> observations;

    public ClimateFilter(ClimateOracle oracle, List<ClimateObservation> observations) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("At least one climate observation is required");
        }
        this.observations = List.copyOf(observations);
    }

    @Override
    public boolean matches(long worldSeed) {
        for (ClimateObservation observation : observations) {
            if (!observation.matches(oracle.temperatureAt(worldSeed, observation.blockX(),
                observation.blockY(), observation.blockZ()))) {
                return false;
            }
        }
        return true;
    }

    public List<ClimateObservation> observations() {
        return observations;
    }

    @Override
    public String description() {
        return "climate filter (" + observations.size() + " sample(s))";
    }
}
