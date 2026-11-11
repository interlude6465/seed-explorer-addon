package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/**
 * Full-seed filter for exact structure-facing and mirror observations.
 *
 * <p>The filter owns no Minecraft generation code. A target-version adapter
 * supplies the structure-start oracle, which must return {@code null} when
 * biome, frequency, exclusion, or terrain rules prevent a start.</p>
 */
public final class StructureOrientationFilter implements WorldSeedFilter {
    private final StructureOrientationOracle oracle;
    private final List<StructureOrientationObservation> observations;

    public StructureOrientationFilter(StructureOrientationOracle oracle,
                                      List<StructureOrientationObservation> observations) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("At least one orientation observation is required");
        }
        this.observations = List.copyOf(observations);
    }

    @Override
    public boolean matches(long worldSeed) {
        for (StructureOrientationObservation observation : observations) {
            StructureGeometry.Orientation actual = oracle.orientationAt(worldSeed,
                observation.structureId(), observation.chunkX(), observation.chunkZ());
            if (!observation.orientation().equals(actual)) {
                return false;
            }
        }
        return true;
    }

    public List<StructureOrientationObservation> observations() {
        return observations;
    }

    @Override
    public String description() {
        return "structure orientation filter (" + observations.size() + " observation(s))";
    }
}
