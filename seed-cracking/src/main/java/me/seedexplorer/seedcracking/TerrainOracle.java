package me.seedexplorer.seedcracking;

/**
 * Returns a terrain surface height for a candidate world seed.
 *
 * <p>Implementations must use the full world seed. A common adapter calls
 * {@code ChunkGenerator.getBaseHeight} or samples the target version's noise
 * router at the
 * requested column. The returned Y should be the top solid block (the block a
 * heightmap would report), not the first air block.</p>
 */
@FunctionalInterface
public interface TerrainOracle {
    /** Sentinel returned when a column cannot be sampled. */
    int UNKNOWN = Integer.MIN_VALUE;

    int surfaceY(long worldSeed, int blockX, int blockZ);

    default TerrainFilter filter(TerrainObservation... observations) {
        return new TerrainFilter(this, java.util.List.of(observations));
    }
}
