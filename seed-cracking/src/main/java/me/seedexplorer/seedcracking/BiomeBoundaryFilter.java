package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/** Full-seed filter for observed biome pairs across a boundary. */
public final class BiomeBoundaryFilter implements WorldSeedFilter {
    private final BiomeOracle oracle;
    private final List<BiomeBoundaryObservation> observations;

    public BiomeBoundaryFilter(BiomeOracle oracle, List<BiomeBoundaryObservation> observations) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("At least one biome boundary observation is required");
        }
        this.observations = List.copyOf(observations);
    }

    @Override
    public boolean matches(long worldSeed) {
        for (BiomeBoundaryObservation observation : observations) {
            if (!observation.firstBiome().equals(oracle.biomeAt(worldSeed,
                observation.firstX(), observation.firstY(), observation.firstZ()))) {
                return false;
            }
            if (!observation.secondBiome().equals(oracle.biomeAt(worldSeed,
                observation.secondX(), observation.secondY(), observation.secondZ()))) {
                return false;
            }
        }
        return true;
    }

    public List<BiomeBoundaryObservation> observations() {
        return observations;
    }

    @Override
    public String description() {
        return "biome boundary filter (" + observations.size() + " boundary(s))";
    }
}
