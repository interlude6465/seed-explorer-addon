package me.seedexplorer.seedcracking;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Recovers low-48 seed candidates from observable high-bit outputs of
 * {@link Lcg48}.
 *
 * <p>One observed output reveals the upper 32 bits of the LCG state after the
 * call. For observations of at least 24 bits, enumerating the unknown state
 * bits requires at most 2^24 candidates; each additional output filters that
 * set exactly.</p>
 */
public final class Lcg48OutputRecovery {
    public static final int MAX_SUPPORTED_OUTPUT_BITS = 32;
    public static final int MIN_SUPPORTED_OUTPUT_BITS = 24;
    public static final int MAX_CANDIDATES = 1 << (48 - MIN_SUPPORTED_OUTPUT_BITS);
    public static final int NEXT_INT_CANDIDATES = 1 << 16;

    private Lcg48OutputRecovery() {
    }

    /**
     * Finds low-48 user-facing seeds that produce every supplied consecutive
     * {@code nextInt()} output, beginning with the first call after seeding.
     *
     * <p>For a {@code nextLong()} observation, pass its two consecutive signed
     * 32-bit halves in order.</p>
     */
    public static List<Long> recoverNextIntOutputs(List<Integer> outputs) {
        return recoverNextIntOutputs(outputs, 0, 0);
    }

    /**
     * Variant for observations separated by a fixed number of unobserved
     * {@code nextInt()} calls.
     *
     * @param callsBeforeFirst number of unobserved calls after seeding
     * @param callsBetweenOutputs number of unobserved calls between observations
     */
    public static List<Long> recoverNextIntOutputs(List<Integer> outputs,
                                                   int callsBeforeFirst,
                                                   int callsBetweenOutputs) {
        return recoverNextBitsOutputs(outputs, 32, callsBeforeFirst, callsBetweenOutputs);
    }

    /**
     * Recovers seeds from repeated {@code random.next(bits)} observations.
     * Each output must be the non-negative value returned by {@link Lcg48#next(int)}.
     *
     * <p>The first output must expose at least 24 bits so the search remains
     * bounded to at most {@code 2^24} candidates.</p>
     */
    public static List<Long> recoverNextBitsOutputs(List<Integer> outputs, int bits,
                                                    int callsBeforeFirst,
                                                    int callsBetweenOutputs) {
        Objects.requireNonNull(outputs, "outputs");
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("At least one RNG output is required");
        }
        if (bits < MIN_SUPPORTED_OUTPUT_BITS || bits > MAX_SUPPORTED_OUTPUT_BITS) {
            throw new IllegalArgumentException("bits must be in [24, 32]: " + bits);
        }
        if (callsBeforeFirst < 0 || callsBetweenOutputs < 0) {
            throw new IllegalArgumentException("unobserved call counts must be non-negative");
        }
        for (Integer output : outputs) {
            Objects.requireNonNull(output, "output");
            if (bits < 32 && (output < 0 || (output >>> bits) != 0)) {
                throw new IllegalArgumentException("output does not fit in " + bits + " bits: " + output);
            }
        }

        int[] expected = outputs.stream().mapToInt(Integer::intValue).toArray();
        int[] gaps = new int[Math.max(0, expected.length - 1)];
        java.util.Arrays.fill(gaps, callsBetweenOutputs);
        return recoverNextBitsOutputs(expected, bits, callsBeforeFirst, gaps);
    }

    /** Recovers candidates from exact unbounded {@code nextFloat()} values. */
    public static List<Long> recoverNextFloatOutputs(List<Float> outputs,
                                                     int callsBeforeFirst,
                                                     int callsBetweenOutputs) {
        Objects.requireNonNull(outputs, "outputs");
        List<Integer> bits = new ArrayList<>(outputs.size());
        for (Float output : outputs) {
            Objects.requireNonNull(output, "output");
            if (!Float.isFinite(output) || output < 0.0F || output >= 1.0F) {
                throw new IllegalArgumentException("nextFloat output must be finite and in [0, 1)");
            }
            float scaled = output * (1 << 24);
            int value = (int) scaled;
            if ((float) value != scaled) {
                throw new IllegalArgumentException("value is not an exact Lcg48 nextFloat output: " + output);
            }
            bits.add(value);
        }
        return recoverNextBitsOutputs(bits, 24, callsBeforeFirst, callsBetweenOutputs);
    }

    /** Recovers candidates from exact {@code nextDouble()} values. */
    public static List<Long> recoverNextDoubleOutputs(List<Double> outputs,
                                                      int callsBeforeFirstDouble,
                                                      int callsBetweenDoubles) {
        Objects.requireNonNull(outputs, "outputs");
        if (callsBeforeFirstDouble < 0 || callsBetweenDoubles < 0
            || callsBeforeFirstDouble > Integer.MAX_VALUE / 2
            || callsBetweenDoubles > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("unobserved call counts must be non-negative and fit in range");
        }
        List<RandomOutputObservation> observations = new ArrayList<>(outputs.size() * 2);
        for (int index = 0; index < outputs.size(); index++) {
            Double output = Objects.requireNonNull(outputs.get(index), "output");
            if (!Double.isFinite(output) || output < 0.0 || output >= 1.0) {
                throw new IllegalArgumentException("nextDouble output must be finite and in [0, 1)");
            }
            double scaled = output * 0x1.0p53;
            long raw = (long) scaled;
            if ((double) raw != scaled) {
                throw new IllegalArgumentException("value is not an exact Lcg48 nextDouble output: " + output);
            }
            int firstBits = (int) (raw >>> 27);
            int secondBits = (int) (raw & ((1L << 27) - 1L));
            observations.add(new RandomOutputObservation(26, firstBits,
                index == 0 ? callsBeforeFirstDouble * 2 : callsBetweenDoubles * 2));
            observations.add(new RandomOutputObservation(27, secondBits, 0));
        }
        return recoverOutputSequence(observations);
    }

    private static List<Long> recoverNextBitsOutputs(int[] expected, int bits,
                                                      int callsBeforeFirst,
                                                      int[] callsBetweenOutputs) {
        List<RandomOutputObservation> observations = new ArrayList<>(expected.length);
        for (int i = 0; i < expected.length; i++) {
            observations.add(new RandomOutputObservation(bits, expected[i],
                i == 0 ? callsBeforeFirst : callsBetweenOutputs[i - 1]));
        }
        return recoverOutputSequence(observations);
    }

    /** Recovers seeds from mixed-width outputs with an explicit gap per output. */
    public static List<Long> recoverOutputSequence(List<RandomOutputObservation> observations) {
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("At least one RNG output is required");
        }
        RandomOutputObservation first = Objects.requireNonNull(observations.get(0), "observation");
        if (first.bits() < MIN_SUPPORTED_OUTPUT_BITS) {
            throw new IllegalArgumentException("first observation must expose at least 24 bits");
        }
        for (RandomOutputObservation observation : observations) {
            Objects.requireNonNull(observation, "observation");
        }

        int unknownBits = 48 - first.bits();
        long candidateCount = 1L << unknownBits;
        long stateHigh = Integer.toUnsignedLong(first.value()) << unknownBits;
        List<Long> matches = new ArrayList<>();
        Lcg48 random = new Lcg48();

        for (long lowBits = 0; lowBits < candidateCount; lowBits++) {
            long stateAfterFirstOutput = stateHigh | lowBits;
            long stateBeforeFirstOutput = Lcg48.previousState(stateAfterFirstOutput);
            long initialState = Lcg48.retreatState(stateBeforeFirstOutput, first.skippedCallsBefore());
            long seed = initialState ^ Lcg48.MULTIPLIER;
            random.setRawState(Lcg48.advanceState(initialState, first.skippedCallsBefore()));
            boolean matchesAll = true;
            for (int index = 0; index < observations.size(); index++) {
                RandomOutputObservation observation = observations.get(index);
                if (index > 0 && observation.skippedCallsBefore() > 0) {
                    random.setRawState(Lcg48.advanceState(
                        random.rawState(), observation.skippedCallsBefore()));
                }
                if (random.next(observation.bits()) != observation.value()) {
                    matchesAll = false;
                    break;
                }
            }
            if (matchesAll) {
                matches.add(seed & Lcg48.MASK);
            }
        }

        return List.copyOf(matches);
    }

    /** Recovers candidates from consecutive {@code nextLong()} observations. */
    public static List<Long> recoverNextLongOutputs(List<Long> outputs) {
        return recoverNextLongOutputs(outputs, 0, 0);
    }

    /**
     * Recovers candidates from {@code nextLong()} observations with fixed gaps
     * between observations.
     */
    public static List<Long> recoverNextLongOutputs(List<Long> outputs,
                                                    int callsBeforeFirstLong,
                                                    int callsBetweenLongs) {
        Objects.requireNonNull(outputs, "outputs");
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("At least one nextLong output is required");
        }
        if (callsBeforeFirstLong < 0 || callsBetweenLongs < 0) {
            throw new IllegalArgumentException("unobserved call counts must be non-negative");
        }
        if (callsBeforeFirstLong > Integer.MAX_VALUE / 2
            || callsBetweenLongs > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("unobserved call count is too large");
        }
        List<Integer> halves = new ArrayList<>(outputs.size() * 2);
        int[] gaps = new int[Math.max(0, outputs.size() * 2 - 1)];
        int halfIndex = 0;
        for (Long output : outputs) {
            Objects.requireNonNull(output, "output");
            int low = (int) (long) output;
            int high = (int) (output >>> 32);
            halves.add(low < 0 ? high + 1 : high);
            halves.add(low);
            if (halfIndex + 2 < gaps.length) {
                gaps[halfIndex + 1] = callsBetweenLongs * 2;
            }
            halfIndex += 2;
        }
        int[] expected = halves.stream().mapToInt(Integer::intValue).toArray();
        return recoverNextBitsOutputs(expected, 32, callsBeforeFirstLong * 2, gaps);
    }
}
