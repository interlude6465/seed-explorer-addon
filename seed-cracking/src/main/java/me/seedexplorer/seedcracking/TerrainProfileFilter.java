package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/** Full-seed filter for one or more multi-column terrain profiles. */
public final class TerrainProfileFilter implements WorldSeedFilter {
    private final TerrainOracle oracle;
    private final List<TerrainProfileObservation> profiles;

    public TerrainProfileFilter(TerrainOracle oracle, List<TerrainProfileObservation> profiles) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(profiles, "profiles");
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("At least one terrain profile is required");
        }
        this.profiles = List.copyOf(profiles);
    }

    @Override
    public boolean matches(long worldSeed) {
        for (TerrainProfileObservation profile : profiles) {
            for (TerrainObservation observation : profile.samples()) {
                if (!observation.matches(oracle.surfaceY(worldSeed,
                    observation.blockX(), observation.blockZ()))) {
                    return false;
                }
            }
        }
        return true;
    }

    public List<TerrainProfileObservation> profiles() {
        return profiles;
    }

    @Override
    public String description() {
        return "terrain profile filter (" + profiles.size() + " profile(s))";
    }
}
