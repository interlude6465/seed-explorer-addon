package me.seedexplorer.seedcracking;

/**
 * Standalone implementation of Minecraft's legacy_type_1 outpost frequency gate.
 *
 * <p>The gate consumes the full world seed as the seed passed to
 * {@code LegacyRandomSource}; the LCG then retains only the low 48 bits. The
 * candidate structure chunk is folded into the seed in 16-chunk bands. An
 * observed outpost must pass this gate in addition to placement, village
 * exclusion, biome, and Jigsaw-start checks.</p>
 */
public final class PillagerOutpostFrequency {
    public static final float VANILLA_FREQUENCY = 0.2F;

    private PillagerOutpostFrequency() {
    }

    /**
     * Replays {@code StructurePlacement.legacyPillagerOutpostReducer}.
     *
     * @param worldSeed the world's 64-bit seed
     * @param chunkX candidate structure-start chunk X (not block coordinates)
     * @param chunkZ candidate structure-start chunk Z (not block coordinates)
     * @param frequency configured probability, e.g. {@code 0.2F} for outposts
     */
    public static boolean accepts(long worldSeed, int chunkX, int chunkZ, float frequency) {
        if (!(frequency > 0.0F && frequency <= 1.0F)) {
            throw new IllegalArgumentException("frequency must be in (0, 1]: " + frequency);
        }

        // Java's signed shifts are significant for negative chunk coordinates.
        int regionX = chunkX >> 4;
        int regionZ = chunkZ >> 4;
        long mixedSeed = worldSeed ^ (long) ((regionX ^ (regionZ << 4)));

        Lcg48 random = new Lcg48(mixedSeed);
        random.nextInt(); // Legacy outpost behavior discards the first 32-bit draw.
        int bound = (int) (1.0F / frequency);
        return random.nextInt(bound) == 0;
    }

    public static boolean acceptsVanilla(long worldSeed, int chunkX, int chunkZ) {
        return accepts(worldSeed, chunkX, chunkZ, VANILLA_FREQUENCY);
    }
}
