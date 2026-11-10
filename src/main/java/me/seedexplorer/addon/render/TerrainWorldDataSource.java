package me.seedexplorer.addon.render;

import me.seedexplorer.addon.live.LiveChunkSnapshot;

/**
 * Supplies optional client-world state to the terrain renderer.
 *
 * <p>The renderer can run with {@link #NONE} for fully predicted standalone
 * terrain. Meteor compatibility installs a provider that exposes captured
 * chunks and structure-cache notifications without coupling the renderer to
 * Meteor-owned singletons.</p>
 */
public interface TerrainWorldDataSource {
    TerrainWorldDataSource NONE = new TerrainWorldDataSource() {
    };

    default long worldSeed() {
        return 0L;
    }

    default LiveChunkSnapshot snapshot(int dimension, int chunkX, int chunkZ) {
        return null;
    }

    default LiveChunkSnapshot peekSnapshot(int dimension, int chunkX, int chunkZ) {
        return null;
    }

    default boolean isStoredSnapshotRejected(int dimension, int chunkX, int chunkZ) {
        return false;
    }

    default void terrainRegionReady(long regionKey, int dimension) {
    }
}
