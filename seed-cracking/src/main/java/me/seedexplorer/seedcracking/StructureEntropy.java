package me.seedexplorer.seedcracking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Information accounting for structure observations.
 *
 * <p>The formulas mirror SeedCrackerX's {@code DataStorage.getBits}:</p>
 * <ul>
 *   <li>linear structure: {@code log2(range^2)} bits;</li>
 *   <li>triangular structure: {@code log2(peak^2)} bits, where
 *       {@code peak = (range + 1) / 2};</li>
 *   <li>buried treasure: {@code log2(100)} bits for the 1% frequency gate.</li>
 * </ul>
 *
 * <p>These are estimates, not independent information in a strict
 * information-theoretic sense: two observations of the same structure set share
 * the same placement salt and RNG stream, and the frequency/exclusion gates may
 * be correlated. They are still useful for deciding when a search is worth
 * starting, which is how SeedCrackerX uses them.</p>
 *
 * <p>{@link #shannonOffsetBits(PlacementSpec)} computes the exact entropy of the
 * offset distribution and is provided for comparison with the SeedCrackerX
 * approximation.</p>
 */
public final class StructureEntropy {
    public static final double LIFTING_THRESHOLD_BITS = 40.0;
    public static final double WORLD_SEED_THRESHOLD_BITS = 32.0;
    public static final double BURIED_TREASURE_BITS = log2(100.0);
    public static final double MINESHAFT_BITS = log2(250.0);

    private static final double LOG2 = Math.log(2.0);

    private StructureEntropy() {
    }

    /** SeedCrackerX-style offset + frequency bits for one observation. */
    public static double observationBits(StructureObservation observation) {
        Objects.requireNonNull(observation, "observation");
        return observationBits(observation.placement());
    }

    /** SeedCrackerX-style offset + frequency bits for one placement spec. */
    public static double observationBits(PlacementSpec placement) {
        Objects.requireNonNull(placement, "placement");
        return offsetBits(placement) + frequencyBits(placement);
    }

    /** SeedCrackerX-style offset bits, excluding frequency gates. */
    public static double offsetBits(PlacementSpec placement) {
        Objects.requireNonNull(placement, "placement");
        int range = placement.range();
        if (range <= 1) {
            return 0.0;
        }
        if (placement.spreadType() == SpreadType.LINEAR) {
            return log2((double) range * (double) range);
        }
        int peak = (range + 1) / 2;
        return log2((double) peak * (double) peak);
    }

    /**
     * Exact Shannon entropy of the offset distribution, in bits per axis.
     *
     * <p>For triangular placements this is slightly lower than the
     * {@code log2(peak^2)} approximation because the averaged distribution is
     * not flat even near its peak.</p>
     */
    public static double shannonOffsetBits(PlacementSpec placement) {
        Objects.requireNonNull(placement, "placement");
        int range = placement.range();
        if (range <= 1) {
            return 0.0;
        }
        if (placement.spreadType() == SpreadType.LINEAR) {
            return log2(range);
        }
        if (range > 4096) {
            // The exact triangular distribution is O(range^2); vanilla ranges
            // are tiny, so this guard is only for custom datapacks.
            return offsetBits(placement) / 2.0;
        }
        long[] counts = new long[range];
        for (int first = 0; first < range; first++) {
            for (int second = 0; second < range; second++) {
                counts[(first + second) / 2]++;
            }
        }
        double total = (double) range * (double) range;
        double entropy = 0.0;
        for (long count : counts) {
            if (count == 0) {
                continue;
            }
            double probability = count / total;
            entropy -= probability * log2(probability);
        }
        return entropy;
    }

    /** Frequency-reduction bits for structures that have a known 26.1+ gate. */
    public static double frequencyBits(PlacementSpec placement) {
        Objects.requireNonNull(placement, "placement");
        return switch (placement.id()) {
            case "minecraft:buried_treasures" -> BURIED_TREASURE_BITS;
            case "minecraft:mineshafts" -> MINESHAFT_BITS;
            default -> 0.0;
        };
    }

    public static double totalBits(List<StructureObservation> observations) {
        Objects.requireNonNull(observations, "observations");
        double bits = 0.0;
        for (StructureObservation observation : observations) {
            bits += observationBits(observation);
        }
        return bits;
    }

    /**
     * SeedCrackerX's {@code getBaseBits}: all observations except pillager
     * outposts, which are omitted because their exclusion zone makes the naive
     * bit count misleading.
     */
    public static double baseBits(List<StructureObservation> observations) {
        Objects.requireNonNull(observations, "observations");
        double bits = 0.0;
        for (StructureObservation observation : observations) {
            if (!VanillaPlacements.PILLAGER_OUTPOSTS.id().equals(observation.placement().id())) {
                bits += observationBits(observation);
            }
        }
        return bits;
    }

    /**
     * Bits available to the low-bit sieve. Every 26.1+ set that
     * {@link StructureSeedLifter} accepts is included; this intentionally
     * generalises SeedCrackerX, which hard-codes a 19-bit sieve for its
     * old-structure and shipwreck finders.
     */
    public static double liftingBits(List<StructureObservation> observations) {
        Objects.requireNonNull(observations, "observations");
        double bits = 0.0;
        for (StructureObservation observation : observations) {
            if (observation.placement().supportsLowBitLifting()) {
                bits += offsetBits(observation.placement());
            }
        }
        return bits;
    }

    /** Convenience gate for starting the low-bit sieve. */
    public static boolean readyForLifting(List<StructureObservation> observations) {
        return liftingBits(observations) >= LIFTING_THRESHOLD_BITS;
    }

    /** Convenience gate for starting the normal SeedCrackerX crack phase. */
    public static boolean readyForWorldSeedSearch(List<StructureObservation> observations) {
        return baseBits(observations) >= WORLD_SEED_THRESHOLD_BITS;
    }

    public static Map<String, Double> bitsByPlacement(List<StructureObservation> observations) {
        Objects.requireNonNull(observations, "observations");
        Map<String, Double> bits = new LinkedHashMap<>();
        for (StructureObservation observation : observations) {
            bits.merge(observation.placement().id(), observationBits(observation), Double::sum);
        }
        return bits;
    }

    public static Map<String, Double> bitsByStructure(List<StructureObservation> observations) {
        Objects.requireNonNull(observations, "observations");
        Map<String, Double> bits = new LinkedHashMap<>();
        for (StructureObservation observation : observations) {
            String id = observation.structureId();
            if (id == null || id.isBlank()) {
                id = observation.placement().id();
            }
            bits.merge(id, observationBits(observation), Double::sum);
        }
        return bits;
    }

    private static double log2(double value) {
        return Math.log(value) / LOG2;
    }
}
