package me.seedexplorer.addon.live;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Snapshot of non-air blocks observed by the client for one chunk. */
public final class LiveChunkSnapshot {
    public static final int CHUNK_SIZE = 16;

    private final int dimension;
    private final int chunkX;
    private final int chunkZ;
    private final int minY;
    private final int maxY;
    private final Map<Integer, BlockState> blocks;
    private final int[] surfaceHeights = new int[CHUNK_SIZE * CHUNK_SIZE];
    private volatile long updatedAt;

    public LiveChunkSnapshot(int dimension, int chunkX, int chunkZ, int minY, int maxY) {
        this(dimension, chunkX, chunkZ, minY, maxY, new ConcurrentHashMap<>(), System.currentTimeMillis());
    }

    public LiveChunkSnapshot(int dimension, int chunkX, int chunkZ, int minY, int maxY,
                             Map<Integer, BlockState> blocks, long updatedAt) {
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
        this.maxY = maxY;
        this.blocks = new ConcurrentHashMap<>(blocks);
        this.updatedAt = updatedAt;
        java.util.Arrays.fill(surfaceHeights, minY);
        for (Map.Entry<Integer, BlockState> entry : blocks.entrySet()) {
            BlockState state = entry.getValue();
            if (state == null || state.isAir()) continue;
            int packed = entry.getKey();
            int localX = localX(packed);
            int localZ = localZ(packed);
            int y = y(packed, minY) + 1;
            int index = localZ * CHUNK_SIZE + localX;
            if (y > surfaceHeights[index]) surfaceHeights[index] = y;
        }
    }

    public int dimension() {
        return dimension;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public Map<Integer, BlockState> blocks() {
        return Map.copyOf(blocks);
    }

    public BlockState block(int localX, int y, int localZ) {
        if (!inside(localX, y, localZ)) return null;
        return blocks.get(pack(localX, y, localZ, minY));
    }

    public void setBlock(int localX, int y, int localZ, BlockState state) {
        if (!inside(localX, y, localZ)) return;
        int key = pack(localX, y, localZ, minY);
        int surfaceIndex = localZ * CHUNK_SIZE + localX;
        if (state == null || state.isAir()) {
            blocks.remove(key);
            if (surfaceHeights[surfaceIndex] == y + 1) recomputeSurface(localX, localZ);
        } else {
            blocks.put(key, state);
            if (y + 1 > surfaceHeights[surfaceIndex]) surfaceHeights[surfaceIndex] = y + 1;
        }
        updatedAt = System.currentTimeMillis();
    }

    public int surfaceY(int localX, int localZ) {
        if (localX < 0 || localX >= CHUNK_SIZE || localZ < 0 || localZ >= CHUNK_SIZE) return minY;
        return surfaceHeights[localZ * CHUNK_SIZE + localX];
    }

    public static int pack(BlockPos pos, int minY) {
        return pack(pos.getX() & 15, pos.getY(), pos.getZ() & 15, minY);
    }

    public static int pack(int localX, int y, int localZ, int minY) {
        return ((y - minY) << 8) | ((localZ & 15) << 4) | (localX & 15);
    }

    public static int localX(int packed) {
        return packed & 15;
    }

    public static int localZ(int packed) {
        return (packed >> 4) & 15;
    }

    public static int y(int packed, int minY) {
        return (packed >> 8) + minY;
    }

    private boolean inside(int localX, int y, int localZ) {
        return localX >= 0 && localX < CHUNK_SIZE
            && localZ >= 0 && localZ < CHUNK_SIZE
            && y >= minY && y < maxY;
    }

    private void recomputeSurface(int localX, int localZ) {
        int index = localZ * CHUNK_SIZE + localX;
        for (int y = maxY - 1; y >= minY; y--) {
            BlockState state = block(localX, y, localZ);
            if (state != null && !state.isAir()) {
                surfaceHeights[index] = y + 1;
                return;
            }
        }
        surfaceHeights[index] = minY;
    }
}
