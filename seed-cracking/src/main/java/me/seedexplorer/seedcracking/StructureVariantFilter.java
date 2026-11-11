package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/** Full-seed filter for exact observed structure variants. */
public final class StructureVariantFilter implements WorldSeedFilter {
    private final StructureVariantOracle oracle;
    private final List<StructureVariantObservation> observations;

    public StructureVariantFilter(StructureVariantOracle oracle,
                                  List<StructureVariantObservation> observations) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("At least one structure variant observation is required");
        }
        this.observations = List.copyOf(observations);
    }

    @Override
    public boolean matches(long worldSeed) {
        for (StructureVariantObservation observation : observations) {
            String actual = oracle.variantAt(worldSeed, observation.structureId(),
                observation.chunkX(), observation.chunkZ());
            if (!observation.variantId().equals(actual)) {
                return false;
            }
        }
        return true;
    }

    public List<StructureVariantObservation> observations() {
        return observations;
    }

    @Override
    public String description() {
        return "structure variant filter (" + observations.size() + " observation(s))";
    }
}
