package me.seedexplorer.seedcracking;

/**
 * One observed stronghold start chunk.
 *
 * <p>Minecraft 26.1-26.3 strongholds use {@code ConcentricRingsStructurePlacement}
 * with 128 attempts, distance 32, spread 3, and the
 * {@code stronghold_biased_to} biome tag. The ring candidate positions are
 * low-48-seed dependent; the final position also depends on the full seed
 * through the preferred-biome search. Treat this as a full-seed verifier and
 * allow a tolerance of a few chunks.</p>
 *
 * @param index           zero-based stronghold index in ring-generation order
 * @param chunkX          observed start chunk X
 * @param chunkZ          observed start chunk Z
 * @param toleranceChunks allowed Euclidean distance in chunks
 */
public record StrongholdObservation(int index, int chunkX, int chunkZ, int toleranceChunks) {
    public StrongholdObservation {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative: " + index);
        }
        if (toleranceChunks < 0) {
            throw new IllegalArgumentException("toleranceChunks must be non-negative: " + toleranceChunks);
        }
    }

    public boolean matches(ChunkCoordinate actual) {
        if (actual == null) {
            return false;
        }
        long dx = (long) actual.x() - chunkX;
        long dz = (long) actual.z() - chunkZ;
        return dx * dx + dz * dz <= (long) toleranceChunks * toleranceChunks;
    }

    public String describe() {
        return "stronghold #" + index + " at (" + chunkX + ", " + chunkZ + ") +/- "
            + toleranceChunks + " chunk(s)";
    }
}
