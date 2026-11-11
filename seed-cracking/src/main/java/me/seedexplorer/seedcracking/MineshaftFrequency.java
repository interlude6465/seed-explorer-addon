package me.seedexplorer.seedcracking;

import java.util.Objects;

/** Exact legacy_type_3 mineshaft frequency gate and weighted type selector. */
public final class MineshaftFrequency {
    public static final float VANILLA_FREQUENCY = 0.004F;

    private MineshaftFrequency() {
    }

    /** Replays vanilla's {@code nextDouble() < probability} placement reducer. */
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
     * Selects the normal/mesa entry using the supplied structure-set RNG state.
     * Vanilla's chunk generator shares one per-chunk stream across structure
     * sets, so the exact preselection state can depend on earlier sets.
     */
    public static Type selectType(Lcg48 random, int normalWeight, int mesaWeight) {
        Objects.requireNonNull(random, "random");
        if (normalWeight <= 0 || mesaWeight <= 0
            || normalWeight > Integer.MAX_VALUE - mesaWeight) {
            throw new IllegalArgumentException("positive weights must sum within int range");
        }
        return random.nextInt(normalWeight + mesaWeight) < normalWeight
            ? Type.NORMAL : Type.MESA;
    }

    /** Diagnostic for an isolated fresh stream; not a replacement for vanilla set ordering. */
    public static Type selectFromFreshChunkSeed(long worldSeed, int chunkX, int chunkZ) {
        return selectType(new Lcg48(WorldgenSeeds263.largeFeatureSeed(worldSeed, chunkX, chunkZ)),
            1, 1);
    }

    public enum Type {
        NORMAL,
        MESA
    }
}
