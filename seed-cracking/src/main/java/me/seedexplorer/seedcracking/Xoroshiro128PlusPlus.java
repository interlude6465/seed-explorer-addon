package me.seedexplorer.seedcracking;

/**
 * Exact 26.1-26.3 {@code Xoroshiro128PlusPlus} generator.
 *
 * <p>Bytecode-verified against 26.1.2, 26.2, and 26.3. This is the generator
 * used
 * by the Overworld noise settings ({@code legacy_random_source: false}), so its
 * positional factories depend on the full 64-bit world seed, including the
 * upper 16 bits that structure lifting cannot recover.</p>
 */
public final class Xoroshiro128PlusPlus {
    private long seedLo;
    private long seedHi;

    public Xoroshiro128PlusPlus(long seedLo, long seedHi) {
        if ((seedLo | seedHi) == 0L) {
            this.seedLo = RandomSupport263.GOLDEN_GAMMA;
            this.seedHi = RandomSupport263.SILVER_GAMMA;
        } else {
            this.seedLo = seedLo;
            this.seedHi = seedHi;
        }
    }

    public long nextLong() {
        long stateLo = seedLo;
        long stateHi = seedHi;
        long result = Long.rotateLeft(stateLo + stateHi, 17) + stateLo;

        stateHi ^= stateLo;
        seedLo = Long.rotateLeft(stateLo, 49) ^ stateHi ^ (stateHi << 21);
        seedHi = Long.rotateLeft(stateHi, 28);
        return result;
    }

    public long seedLo() {
        return seedLo;
    }

    public long seedHi() {
        return seedHi;
    }
}
