package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/** Bridges exact 26.1-26.3 concentric-ring geometry to a supplied biome gate. */
public final class ConcentricRingsStrongholdOracle implements StrongholdOracle {
    private final ConcentricRings263.BiomeGate biomeGate;
    private final ThreadLocal<CachedStrongholds> cache = new ThreadLocal<>();

    public ConcentricRingsStrongholdOracle(ConcentricRings263.BiomeGate biomeGate) {
        this.biomeGate = Objects.requireNonNull(biomeGate, "biomeGate");
    }

    @Override
    public ChunkCoordinate strongholdChunk(long worldSeed, int index) {
        if (index < 0 || index >= ConcentricRings263.COUNT) {
            return null;
        }
        CachedStrongholds cached = cache.get();
        if (cached == null || cached.worldSeed() != worldSeed) {
            List<ChunkCoordinate> strongholds = ConcentricRings263.strongholds(worldSeed, biomeGate);
            cached = new CachedStrongholds(worldSeed, strongholds);
            cache.set(cached);
        }
        return cached.positions().get(index);
    }

    private record CachedStrongholds(long worldSeed, List<ChunkCoordinate> positions) {
    }
}
