package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/**
 * Full-seed filter that verifies real structure starts.
 *
 * <p>This filter is stronger than {@link StructureSeedLifter}'s exact placement
 * replay: placement replay proves that the chunk is a potential chunk, while a
 * structure-start oracle also proves that biome, frequency, and exclusion gates
 * passed.</p>
 */
public final class StructureStartFilter implements WorldSeedFilter {
    private final StructureStartOracle oracle;
    private final List<StructureObservation> observations;

    public StructureStartFilter(StructureStartOracle oracle, List<StructureObservation> observations) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("At least one structure observation is required");
        }
        this.observations = List.copyOf(observations);
    }

    @Override
    public boolean matches(long worldSeed) {
        for (StructureObservation observation : observations) {
            if (!oracle.hasStructureStart(worldSeed, observation)) {
                return false;
            }
        }
        return true;
    }

    public List<StructureObservation> observations() {
        return observations;
    }

    @Override
    public String description() {
        return "structure-start filter (" + observations.size() + " structure(s))";
    }
}
