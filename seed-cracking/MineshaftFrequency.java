package me.seedexplorer.seedcracking;

import java.util.Objects;

/** Exact legacy_type_3 mineshaft frequency gate and weighted type selector. */
public final class MineshaftFrequency {
    public static final float VANILLA_FREQUENCY = 0.004F;

    private MineshaftFrequency() {
    }

    /**
     * Replays {@code legacyProbabilityReducerWithDouble} from vanilla.
     * The structure-generation stream is seeded with the candidate world seed
     * and the candidate chunk, then one {@code nextDouble()} is compared with
     * the configured probability.
     */
    public static boolean accepts(long worldSeed, int chunkX, int chunkZ, float frequency) {
        if (!(frequency >= 0.0F && frequency <= 1.0F)) {
            throw new IllegalArgumentException("frequency must be in [0, 1]: " + frequency);
        }
        Lcg48 random = new Lcg48(WorldgenSeeds263.largeFeatureSeed(worldSeed, chunkX, chunkZ));
        return random.nextDouble() < (double) frequency;
    }

    public static boolean acceptsVanilla(long worldSeed, int chunkX, int chunkZ) {
        return accepts(worldSeed, chunkX, chunkZ, VANILLA_FREQUENCY);
    }

    /**
     * Selects an entry from the already-positioned structure-set RNG stream.
     * Vanilla's chunk generator may have consumed this stream while processing
     * earlier structure sets, so callers should normally pass that exact state
     * rather than use {@link #selectFromFreshChunkSeed}.
     */
    public static Type selectType(Lcg48 random, int normalWeight, int mesaWeight) {
        Objects.requireNonNull(random, "random");
        if (normalWeight <= 0 || mesaWeight <= 0) {
            throw new IllegalArgumentException("weights must be positive");
        }
        int roll = random.nextInt(normalWeight + mesaWeight);
        return roll < normalWeight ? Type.NORMAL : Type.MESA;
    }

    /** Diagnostic helper for a structure-set stream with no earlier consumers. */
    public static Type selectFromFreshChunkSeed(long worldSeed, int chunkX, int chunkZ) {
        return selectType(new Lcg48(WorldgenSeeds263.largeFeatureSeed(worldSeed, chunkX, chunkZ)), 1, 1);
    }

    public enum Type {
        NORMAL,
        MESA
    }
}
