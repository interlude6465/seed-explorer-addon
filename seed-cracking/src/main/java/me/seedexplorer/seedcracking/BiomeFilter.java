package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/**
 * Full-seed filter backed by observed biomes.
 *
 * <p>This is an upper-bit verifier, not a structure-lifting method. Enumerating
 * the 65,536 possible upper 16-bit halves of a recovered structure seed and
 * checking a handful of well-spread biome samples is usually enough to leave a
 * single world seed. Use surface samples from different climate regions; nearby
 * samples are correlated and carry little independent information.</p>
 */
public final class BiomeFilter implements WorldSeedFilter {
    private final BiomeOracle oracle;
    private final List<BiomeObservation> observations;

    public BiomeFilter(BiomeOracle oracle, List<BiomeObservation> observations) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("At least one biome observation is required");
        }
        this.observations = List.copyOf(observations);
    }

    @Override
    public boolean matches(long worldSeed) {
        for (BiomeObservation observation : observations) {
            String actual = oracle.biomeAt(worldSeed,
                observation.blockX(), observation.blockY(), observation.blockZ());
            if (actual == null || !observation.biomeId().equals(actual)) {
                return false;
            }
        }
        return true;
    }

    public List<BiomeObservation> observations() {
        return observations;
    }

    @Override
    public String description() {
        return "biome filter (" + observations.size() + " sample(s))";
    }
}
