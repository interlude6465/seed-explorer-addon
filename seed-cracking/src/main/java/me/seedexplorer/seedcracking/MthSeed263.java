package me.seedexplorer.seedcracking;

/**
 * Exact 26.1-26.3 {@code Mth.getSeed(int, int, int)} positional hash.
 *
 * <p>Bytecode-verified against 26.1.2, 26.2, and 26.3. The multiplication before
 * widening is intentionally 32-bit, matching vanilla's overflow behavior.</p>
 */
final class MthSeed263 {
    private MthSeed263() {
    }

    static long getSeed(int x, int y, int z) {
        long seed = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        seed = seed * seed * 42317861L + seed * 11L;
        return seed >> 16;
    }
}
