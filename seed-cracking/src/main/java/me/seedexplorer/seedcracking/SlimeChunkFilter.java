package me.seedexplorer.seedcracking;

/**
 * Exact 26.1+ slime-chunk predicate.
 *
 * <p>Bytecode-verified against
 * {@code WorldgenRandom.seedSlimeChunk(int chunkX, int chunkZ, long seed, long salt)}
 * and the slime spawn check in 26.1.2, 26.2, and 26.3. The arithmetic
 * intentionally uses the same
 * 32-bit overflow points as vanilla before widening to {@code long}.</p>
 *
 * <p>Slime chunks depend only on the low 48 bits of the world seed. One chunk
 * is a weak observation: a slime chunk gives about 3.32 bits, while a non-slime
 * chunk gives about 0.15 bits. Use many chunks as a candidate filter; do not
 * expect a handful to recover a seed on their own.</p>
 */
public final class SlimeChunkFilter {
    public static final long SALT = 987234911L;

    private SlimeChunkFilter() {
    }

    public static boolean isSlimeChunk(long structureSeed, int chunkX, int chunkZ) {
        int xSquared = chunkX * chunkX;
        int zSquared = chunkZ * chunkZ;

        long mixed = structureSeed
            + (long) (xSquared * 4987142)
            + (long) (chunkX * 5947611)
            + (long) zSquared * 4392871L
            + (long) (chunkZ * 389711);
        mixed ^= SALT;

        return new Lcg48(mixed).nextInt(10) == 0;
    }

    /** Convenience for a full world seed; only its low 48 bits are used. */
    public static boolean isSlimeChunkForWorldSeed(long worldSeed, int chunkX, int chunkZ) {
        return isSlimeChunk(worldSeed & Lcg48.MASK, chunkX, chunkZ);
    }
}
