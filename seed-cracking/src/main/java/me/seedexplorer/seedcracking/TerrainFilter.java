package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/**
 * Full-seed filter backed by terrain surface heights.
 *
 * <p>Prefer columns in different terrain regions (mountains, plains, ocean) and
 * avoid columns whose height is dominated by a single surface rule. A tolerance
 * of 0 to 2 blocks is typical.</p>
 */
public final class TerrainFilter implements WorldSeedFilter {
    private final TerrainOracle oracle;
    private final List<TerrainObservation> observations;

    public TerrainFilter(TerrainOracle oracle, List<TerrainObservation> observations) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("At least one terrain observation is required");
        }
        this.observations = List.copyOf(observations);
    }

    @Override
    public boolean matches(long worldSeed) {
        for (TerrainObservation observation : observations) {
            int actual = oracle.surfaceY(worldSeed, observation.blockX(), observation.blockZ());
            if (!observation.matches(actual)) {
                return false;
            }
        }
        return true;
    }

    public List<TerrainObservation> observations() {
        return observations;
    }

    @Override
    public String description() {
        return "terrain filter (" + observations.size() + " column(s))";
    }
}
