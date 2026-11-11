package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/** Full-seed filter backed by observed stronghold start chunks. */
public final class StrongholdFilter implements WorldSeedFilter {
    private final StrongholdOracle oracle;
    private final List<StrongholdObservation> observations;

    public StrongholdFilter(StrongholdOracle oracle, List<StrongholdObservation> observations) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("At least one stronghold observation is required");
        }
        this.observations = List.copyOf(observations);
    }

    @Override
    public boolean matches(long worldSeed) {
        for (StrongholdObservation observation : observations) {
            ChunkCoordinate actual = oracle.strongholdChunk(worldSeed, observation.index());
            if (!observation.matches(actual)) {
                return false;
            }
        }
        return true;
    }

    public List<StrongholdObservation> observations() {
        return observations;
    }

    @Override
    public String description() {
        return "stronghold filter (" + observations.size() + " observation(s))";
    }
}
