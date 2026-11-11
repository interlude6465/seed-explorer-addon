package me.seedexplorer.seedcracking;

/**
 * One observed slime/non-slime chunk.
 *
 * <p>Prefer chunks spread over a large area. Nearby chunks are correlated by
 * the quadratic position terms and provide less independent information.</p>
 */
public record SlimeChunkObservation(int chunkX, int chunkZ, boolean slime) {
    public boolean matches(long structureSeed) {
        return SlimeChunkFilter.isSlimeChunk(structureSeed, chunkX, chunkZ) == slime;
    }

    public boolean matchesWorldSeed(long worldSeed) {
        return matches(worldSeed & Lcg48.MASK);
    }
}
