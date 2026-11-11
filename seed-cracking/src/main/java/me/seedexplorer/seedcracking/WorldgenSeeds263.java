package me.seedexplorer.seedcracking;

/**
 * Exact seed-mixing helpers from 26.1-26.3 {@code WorldgenRandom}.
 *
 * <p>These are the helpers that turn a chunk, feature index, or region into a
 * Legacy random seed. They are useful when a feature's first RNG call is known
 * and the observation needs to be replayed without a Minecraft runtime.</p>
 *
 * <p>All methods were checked against the 26.1.2, 26.2, and 26.3 bytecode. The
 * arithmetic that looks odd (notably the 32-bit multiply in
 * {@link #featureSeed}) is intentional: vanilla multiplies two ints before
 * widening to long.</p>
 */
public final class WorldgenSeeds263 {
    public static final long LARGE_FEATURE_SALT_X = 341873128712L;
    public static final long LARGE_FEATURE_SALT_Z = 132897987541L;
    public static final int FEATURE_STEP_MULTIPLIER = 10000;

    private WorldgenSeeds263() {
    }

    /** {@code setFeatureSeed(seed, index, step)}. */
    public static long featureSeed(long seed, int index, int step) {
        int stepOffset = FEATURE_STEP_MULTIPLIER * step;
        return seed + index + (long) stepOffset;
    }

    /** {@code setLargeFeatureSeed(seed, chunkX, chunkZ)}. */
    public static long largeFeatureSeed(long seed, int chunkX, int chunkZ) {
        Lcg48 random = new Lcg48(seed);
        long first = random.nextLong();
        long second = random.nextLong();
        return seed ^ ((long) chunkX * first) ^ ((long) chunkZ * second);
    }

    /** {@code setDecorationSeed(seed, chunkX, chunkZ)}. */
    public static long decorationSeed(long seed, int chunkX, int chunkZ) {
        Lcg48 random = new Lcg48(seed);
        long first = random.nextLong() | 1L;
        long second = random.nextLong() | 1L;
        return seed ^ ((long) chunkX * first + (long) chunkZ * second);
    }

    /** {@code setLargeFeatureWithSalt(seed, regionX, regionZ, salt)}. */
    public static long largeFeatureWithSaltSeed(long seed, int regionX, int regionZ, int salt) {
        return seed
            + (long) regionX * LARGE_FEATURE_SALT_X
            + (long) regionZ * LARGE_FEATURE_SALT_Z
            + (long) salt;
    }

    public static Lcg48 featureRandom(long seed, int index, int step) {
        return new Lcg48(featureSeed(seed, index, step));
    }

    public static Lcg48 largeFeatureRandom(long seed, int chunkX, int chunkZ) {
        return new Lcg48(largeFeatureSeed(seed, chunkX, chunkZ));
    }

    public static Lcg48 decorationRandom(long seed, int chunkX, int chunkZ) {
        return new Lcg48(decorationSeed(seed, chunkX, chunkZ));
    }

    public static Lcg48 largeFeatureWithSaltRandom(long seed, int regionX, int regionZ, int salt) {
        return new Lcg48(largeFeatureWithSaltSeed(seed, regionX, regionZ, salt));
    }

    /** Exact {@code WorldgenRandom.seedSlimeChunk} mixed seed, before XOR with the salt. */
    public static long slimeSeed(long structureSeed, int chunkX, int chunkZ) {
        int xSquared = chunkX * chunkX;
        int zSquared = chunkZ * chunkZ;
        return structureSeed
            + (long) (xSquared * 4987142)
            + (long) (chunkX * 5947611)
            + (long) zSquared * 4392871L
            + (long) (chunkZ * 389711);
    }

    /** Exact slime-check seed including the vanilla slime salt. */
    public static long slimeChunkSeed(long structureSeed, int chunkX, int chunkZ) {
        return slimeSeed(structureSeed, chunkX, chunkZ) ^ SlimeChunkFilter.SALT;
    }

    /** The first {@code nextLong() & 0xFFFF} End spike key. */
    public static int endPillarSeed(long worldSeed) {
        return EndPillarFingerprint.spikeSeedForWorldSeed(worldSeed);
    }
}
