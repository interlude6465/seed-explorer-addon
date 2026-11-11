package me.seedexplorer.seedcracking;

import java.util.Objects;
import java.util.function.LongPredicate;

/**
 * A full-64-bit world-seed predicate.
 *
 * <p>Structure lifting recovers only the low 48 bits. The upper 16 bits must be
 * recovered by features that depend on the full world seed: biomes, terrain,
 * strongholds, bedrock, structure starts, or loot. Implementations of this
 * interface wrap one such feature.</p>
 *
 * <p>Filters are expected to be pure and thread-safe. They are called from
 * multiple worker threads during {@link UpperBitRecovery}.</p>
 */
@FunctionalInterface
public interface WorldSeedFilter {
    /** Returns true when the candidate world seed agrees with the observation. */
    boolean matches(long worldSeed);

    /** Short human-readable description used in results and logs. */
    default String description() {
        return getClass().getSimpleName();
    }

    static WorldSeedFilter of(String description, LongPredicate predicate) {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(predicate, "predicate");
        return new WorldSeedFilter() {
            @Override
            public boolean matches(long worldSeed) {
                return predicate.test(worldSeed);
            }

            @Override
            public String description() {
                return description;
            }
        };
    }

    /** Requires every supplied filter to match. */
    static WorldSeedFilter allOf(String description, WorldSeedFilter... filters) {
        Objects.requireNonNull(filters, "filters");
        WorldSeedFilter[] copy = filters.clone();
        for (WorldSeedFilter filter : copy) {
            Objects.requireNonNull(filter, "filter");
        }
        return of(description, seed -> {
            for (WorldSeedFilter filter : copy) {
                if (!filter.matches(seed)) {
                    return false;
                }
            }
            return true;
        });
    }
}
