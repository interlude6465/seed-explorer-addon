package me.seedexplorer.addon.render;

import me.seedexplorer.addon.live.LiveChunkRecorder;
import me.seedexplorer.addon.live.LiveChunkSnapshot;
import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.structures.StructureCache;

/** Meteor compatibility provider for terrain state that is optional in standalone builds. */
public final class MeteorTerrainWorldDataSource implements TerrainWorldDataSource {
    private static final MeteorTerrainWorldDataSource INSTANCE = new MeteorTerrainWorldDataSource();

    private MeteorTerrainWorldDataSource() {
    }

    public static MeteorTerrainWorldDataSource get() {
        return INSTANCE;
    }

    @Override
    public long worldSeed() {
        return SeedManager.get().getWorldSeed();
    }

    @Override
    public LiveChunkSnapshot snapshot(int dimension, int chunkX, int chunkZ) {
        return LiveChunkRecorder.get().snapshot(dimension, chunkX, chunkZ);
    }

    @Override
    public LiveChunkSnapshot peekSnapshot(int dimension, int chunkX, int chunkZ) {
        return LiveChunkRecorder.get().peekSnapshot(dimension, chunkX, chunkZ);
    }

    @Override
    public boolean isStoredSnapshotRejected(int dimension, int chunkX, int chunkZ) {
        return LiveChunkRecorder.get().isStoredSnapshotRejected(dimension, chunkX, chunkZ);
    }

    @Override
    public void terrainRegionReady(long regionKey, int dimension) {
        StructureCache.get().requestRegion(regionKey, dimension, true, true);
    }
}
