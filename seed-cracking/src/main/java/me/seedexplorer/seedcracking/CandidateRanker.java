package me.seedexplorer.seedcracking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Probabilistic candidate ranking for noisy or partially contradictory
 * observations.
 *
 * <p>Exact filters should normally eliminate candidates outright. When data is
 * uncertain (a mis-clicked structure chunk, a screenshot-derived biome, an
 * incomplete chest), ranking lets a caller keep the best candidates instead of
 * discarding the true seed because one observation is wrong.</p>
 */
public final class CandidateRanker {
    private CandidateRanker() {
    }

    /** A full-seed filter with a log2-selectivity weight. */
    public record WeightedFilter(WorldSeedFilter filter, double bits) {
        public WeightedFilter {
            Objects.requireNonNull(filter, "filter");
            if (bits < 0 || Double.isNaN(bits) || Double.isInfinite(bits)) {
                throw new IllegalArgumentException("bits must be finite and non-negative: " + bits);
            }
        }
    }

    /** A structure seed scored against exact placement observations. */
    public record RankedStructureSeed(
        long structureSeed,
        int matches,
        int observations,
        double matchedBits
    ) {
        public double matchFraction() {
            return observations == 0 ? 0.0 : (double) matches / (double) observations;
        }
    }

    /** A world seed scored against full-seed filters. */
    public record RankedWorldSeed(
        long worldSeed,
        int passed,
        int filters,
        double matchedBits
    ) {
        public double passFraction() {
            return filters == 0 ? 0.0 : (double) passed / (double) filters;
        }
    }

    public static List<RankedStructureSeed> rankStructureSeeds(
        Collection<Long> structureSeeds,
        List<StructureObservation> observations) {
        return rankStructureSeeds(structureSeeds, observations, Integer.MAX_VALUE);
    }

    public static List<RankedStructureSeed> rankStructureSeeds(
        Collection<Long> structureSeeds,
        List<StructureObservation> observations,
        int limit) {
        Objects.requireNonNull(structureSeeds, "structureSeeds");
        Objects.requireNonNull(observations, "observations");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }

        List<Long> seeds = UpperBitRecovery.distinctStructureSeeds(new ArrayList<>(structureSeeds));
        List<RankedStructureSeed> ranked = new ArrayList<>(seeds.size());
        Lcg48 random = new Lcg48();
        for (long seed : seeds) {
            int matches = 0;
            double bits = 0.0;
            for (StructureObservation observation : observations) {
                if (observation.placementMatches(seed, random)) {
                    matches++;
                    bits += StructureEntropy.observationBits(observation);
                }
            }
            ranked.add(new RankedStructureSeed(seed, matches, observations.size(), bits));
        }

        ranked.sort(Comparator
            .comparingInt(RankedStructureSeed::matches).reversed()
            .thenComparing(Comparator.comparingDouble(RankedStructureSeed::matchedBits).reversed())
            .thenComparingLong(RankedStructureSeed::structureSeed));

        return List.copyOf(ranked.subList(0, Math.min(limit, ranked.size())));
    }

    public static List<RankedWorldSeed> rankWorldSeeds(
        Collection<Long> worldSeeds,
        List<WorldSeedFilter> filters) {
        Objects.requireNonNull(filters, "filters");
        List<WeightedFilter> weighted = new ArrayList<>(filters.size());
        for (WorldSeedFilter filter : filters) {
            weighted.add(new WeightedFilter(filter, 1.0));
        }
        return rankWeightedWorldSeeds(worldSeeds, weighted, Integer.MAX_VALUE);
    }

    public static List<RankedWorldSeed> rankWeightedWorldSeeds(
        Collection<Long> worldSeeds,
        List<WeightedFilter> filters) {
        return rankWeightedWorldSeeds(worldSeeds, filters, Integer.MAX_VALUE);
    }

    public static List<RankedWorldSeed> rankWeightedWorldSeeds(
        Collection<Long> worldSeeds,
        List<WeightedFilter> filters,
        int limit) {
        Objects.requireNonNull(worldSeeds, "worldSeeds");
        Objects.requireNonNull(filters, "filters");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }

        LinkedHashSet<Long> unique = new LinkedHashSet<>(worldSeeds);
        List<RankedWorldSeed> ranked = new ArrayList<>(unique.size());
        for (long seed : unique) {
            int passed = 0;
            double bits = 0.0;
            for (WeightedFilter weighted : filters) {
                if (weighted.filter().matches(seed)) {
                    passed++;
                    bits += weighted.bits();
                }
            }
            ranked.add(new RankedWorldSeed(seed, passed, filters.size(), bits));
        }

        ranked.sort(Comparator
            .comparingInt(RankedWorldSeed::passed).reversed()
            .thenComparing(Comparator.comparingDouble(RankedWorldSeed::matchedBits).reversed())
            .thenComparingLong(RankedWorldSeed::worldSeed));

        return List.copyOf(ranked.subList(0, Math.min(limit, ranked.size())));
    }
}
