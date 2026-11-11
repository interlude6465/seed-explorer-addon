package me.seedexplorer.seedcracking;

/** One observed high-bit RNG output and the skipped LCG calls before it. */
public record RandomOutputObservation(int bits, int value, int skippedCallsBefore) {
    public RandomOutputObservation {
        if (bits < 1 || bits > 32) {
            throw new IllegalArgumentException("bits must be in [1, 32]: " + bits);
        }
        if (skippedCallsBefore < 0) {
            throw new IllegalArgumentException("skippedCallsBefore must be non-negative");
        }
        long unsignedValue = Integer.toUnsignedLong(value);
        if (bits < 32 && unsignedValue >= (1L << bits)) {
            throw new IllegalArgumentException("value does not fit in " + bits + " bits: " + value);
        }
    }
}
