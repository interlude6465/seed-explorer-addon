package me.seedexplorer.seedcracking;

/**
 * Predicts a stronghold start chunk for a candidate world seed.
 *
 * <p>An adapter should run the complete version-matched concentric-ring
 * placement, including the preferred-biome search. Returning only the pre-biome
 * ring candidate is not a full-seed verifier because the ring geometry depends
 * only on the low 48 bits of the seed. Implementations may return {@code null}
 * when the index is unavailable.</p>
 */
@FunctionalInterface
public interface StrongholdOracle {
    ChunkCoordinate strongholdChunk(long worldSeed, int index);

    default StrongholdFilter filter(StrongholdObservation... observations) {
        return new StrongholdFilter(this, java.util.List.of(observations));
    }
}
