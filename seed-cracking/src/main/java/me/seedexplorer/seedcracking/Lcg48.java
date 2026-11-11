package me.seedexplorer.seedcracking;

/**
 * An exact reimplementation of Java's 48-bit linear congruential generator.
 *
 * <p>Minecraft 26.1+ uses this generator through {@code LegacyRandomSource} for
 * structure placement, End spike (pillar) layout, slime chunks, and several
 * decoration passes. The generator keeps only the low 48 bits of its seed:
 * {@code setSeed(s)} stores {@code (s ^ 0x5DEECE66D) & ((1L << 48) - 1)}.
 * Consequently every LegacyRandomSource-driven feature depends only on the low
 * 48 bits of the world seed. That is the "structure seed" used throughout this
 * package.</p>
 *
 * <p>This class is deliberately dependency-free so the seed-cracking math can
 * be compiled and tested without Minecraft on the classpath.</p>
 */
public final class Lcg48 {
    public static final long MULTIPLIER = 0x5DEECE66DL;
    public static final long ADDEND = 0xBL;
    public static final long MASK = (1L << 48) - 1L;
    /** Modular inverse of {@link #MULTIPLIER} modulo 2^48. */
    public static final long MULTIPLIER_INVERSE = 0xDFE05BCB1365L;
    public static final int STRUCTURE_SEED_BITS = 48;

    private long state;

    public Lcg48() {
        this(0L);
    }

    public Lcg48(long seed) {
        setSeed(seed);
    }

    /** Mirrors {@code java.util.Random.setSeed(long)} and LegacyRandomSource. */
    public void setSeed(long seed) {
        this.state = (seed ^ MULTIPLIER) & MASK;
    }

    /** Sets the raw 48-bit LCG state, not the user-facing seed. */
    public void setRawState(long state) {
        this.state = state & MASK;
    }

    /** Returns the raw 48-bit LCG state. */
    public long rawState() {
        return state;
    }

    /** Mirrors {@code java.util.Random.next(int)} / LegacyRandomSource.next(int). */
    public int next(int bits) {
        if (bits < 1 || bits > 32) {
            throw new IllegalArgumentException("bits must be in [1, 32]: " + bits);
        }
        state = (state * MULTIPLIER + ADDEND) & MASK;
        return (int) (state >>> (48 - bits));
    }

    /**
     * Reverses one raw LCG state transition.
     *
     * <p>Used by the SeedCrackerX-style pillar time machine, which walks two LCG
     * steps backwards from a partly known state. The input and output are raw
     * 48-bit states, not user-facing seeds.</p>
     */
    public static long previousState(long state) {
        return ((state - ADDEND) * MULTIPLIER_INVERSE) & MASK;
    }

    /** Applies {@link #previousState(long)} {@code steps} times. */
    public static long previousState(long state, int steps) {
        if (steps < 0) {
            throw new IllegalArgumentException("steps must be non-negative: " + steps);
        }
        return retreatState(state, steps);
    }

    /** Advances a raw state by {@code steps} transitions in O(log steps). */
    public static long advanceState(long state, long steps) {
        if (steps < 0) {
            throw new IllegalArgumentException("steps must be non-negative: " + steps);
        }
        return applyRepeatedTransition(state, steps, MULTIPLIER, ADDEND);
    }

    /** Reverses a raw state by {@code steps} transitions in O(log steps). */
    public static long retreatState(long state, long steps) {
        if (steps < 0) {
            throw new IllegalArgumentException("steps must be non-negative: " + steps);
        }
        long inverseAddend = (-ADDEND * MULTIPLIER_INVERSE) & MASK;
        return applyRepeatedTransition(state, steps, MULTIPLIER_INVERSE, inverseAddend);
    }

    private static long applyRepeatedTransition(long state, long steps,
                                                long multiplier, long addend) {
        long accumulatedMultiplier = 1L;
        long accumulatedAddend = 0L;
        long currentMultiplier = multiplier;
        long currentAddend = addend;
        long remaining = steps;

        while (remaining > 0) {
            if ((remaining & 1L) != 0L) {
                accumulatedAddend = (accumulatedAddend * currentMultiplier + currentAddend) & MASK;
                accumulatedMultiplier = (accumulatedMultiplier * currentMultiplier) & MASK;
            }
            currentAddend = (currentAddend * (currentMultiplier + 1L)) & MASK;
            currentMultiplier = (currentMultiplier * currentMultiplier) & MASK;
            remaining >>>= 1;
        }

        return (accumulatedMultiplier * (state & MASK) + accumulatedAddend) & MASK;
    }

    /** Mirrors {@code RandomSource.nextInt()}. */
    public int nextInt() {
        return next(32);
    }

    /**
     * Mirrors {@code RandomSource.nextInt(int)} (which is the same algorithm as
     * {@code java.util.Random.nextInt(int)} in 26.1+).
     */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }

        if ((bound & -bound) == bound) {
            return (int) ((bound * (long) next(31)) >> 31);
        }

        int bits;
        int value;
        do {
            bits = next(31);
            value = bits % bound;
        } while (bits - value + (bound - 1) < 0);
        return value;
    }

    /** Mirrors {@code RandomSource.nextLong()}. */
    public long nextLong() {
        return ((long) next(32) << 32) + next(32);
    }

    public boolean nextBoolean() {
        return next(1) != 0;
    }

    public float nextFloat() {
        return next(24) / (float) (1 << 24);
    }

    public double nextDouble() {
        return (((long) next(26) << 27) + next(27)) * 0x1.0p-53;
    }

    /**
     * Exact 26.1+ {@code WorldgenRandom.setLargeFeatureWithSalt(seed, regionX,
     * regionZ, salt)}.
     *
     * <p>Bytecode-verified against 26.1.2, 26.2, and 26.3. The large-feature seed is
     * {@code seed + regionX * 341873128712L + regionZ * 132897987541L + salt},
     * then passed through {@link #setSeed(long)}.</p>
     */
    public void setLargeFeatureWithSalt(long seed, int regionX, int regionZ, int salt) {
        long mixed = seed
            + (long) regionX * 341873128712L
            + (long) regionZ * 132897987541L
            + (long) salt;
        setSeed(mixed);
    }

    /** Returns the low 48 bits, the part that all LegacyRandomSource features use. */
    public static long structureSeed(long worldSeed) {
        return worldSeed & MASK;
    }

    /** Recombines a known low-48 structure seed with a 16-bit upper part. */
    public static long worldSeed(long structureSeed, int upperBits) {
        return ((long) (upperBits & 0xFFFF) << 48) | (structureSeed & MASK);
    }

    public Lcg48 copy() {
        Lcg48 copy = new Lcg48();
        copy.state = this.state;
        return copy;
    }

    @Override
    public String toString() {
        return "Lcg48[state=0x" + Long.toHexString(state) + "]";
    }
}
