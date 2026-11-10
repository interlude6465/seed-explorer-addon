package me.seedexplorer.addon.live;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Snapshot of non-air blocks observed by the client for one chunk. */
public final class LiveChunkSnapshot {
    public static final int CHUNK_SIZE = 16;
    private static final int MIN_COMPLETE_SURFACE_COLUMNS = CHUNK_SIZE * CHUNK_SIZE;
    private static final int MIN_END_SNAPSHOT_BLOCKS = 1024;

    private final int dimension;
    private final int chunkX;
    private final int chunkZ;
    private final int minY;
    private final int maxY;
    private final Map<Integer, BlockState> blocks;
    private final Map<Integer, BlockState> blocksView;
    private final BlockState[][] denseSections;
    private final int[] surfaceHeights = new int[CHUNK_SIZE * CHUNK_SIZE];
    private int denseNonAirBlockCount;
    private boolean sealed;
    private volatile long updatedAt;

    public LiveChunkSnapshot(int dimension, int chunkX, int chunkZ, int minY, int maxY) {
        this(dimension, chunkX, chunkZ, minY, maxY, new ConcurrentHashMap<>(), System.currentTimeMillis());
    }

    public LiveChunkSnapshot(int dimension, int chunkX, int chunkZ, int minY, int maxY,
                             Map<Integer, BlockState> blocks, long updatedAt) {
        this(dimension, chunkX, chunkZ, minY, maxY, blocks, updatedAt, false);
    }

    private LiveChunkSnapshot(int dimension, int chunkX, int chunkZ, int minY, int maxY,
                              Map<Integer, BlockState> initialBlocks, long updatedAt,
                              boolean dense) {
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
        this.maxY = maxY;
        this.blocks = dense ? Map.of() : new ConcurrentHashMap<>(initialBlocks);
        this.blocksView = Collections.unmodifiableMap(this.blocks);
        this.denseSections = dense
            ? new BlockState[Math.max(0, (maxY - minY + 15) >> 4)][] : null;
        this.updatedAt = updatedAt;
        java.util.Arrays.fill(surfaceHeights, minY);
        for (Map.Entry<Integer, BlockState> entry : initialBlocks.entrySet()) {
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

    /**
     * Creates an unpublished snapshot incrementally. The builder owns its
     * snapshot until {@link CaptureBuilder#build()} is called, which lets a
     * client capture spread work across ticks without a final full-map copy.
     */
    public static CaptureBuilder captureBuilder(int dimension, int chunkX, int chunkZ,
                                                int minY, int maxY) {
        return new CaptureBuilder(new LiveChunkSnapshot(
            dimension, chunkX, chunkZ, minY, maxY, Map.of(),
            System.currentTimeMillis(), true));
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
        return denseSections == null ? Map.copyOf(blocks) : Map.copyOf(materializeDenseBlocks());
    }

    /** Read-only map view for legacy map consumers. Dense captures should use
     * {@link #forEachBlock(BlockVisitor)} to avoid materialization. */
    public Map<Integer, BlockState> blocksView() {
        return denseSections == null ? blocksView
            : Collections.unmodifiableMap(materializeDenseBlocks());
    }

    public void forEachBlock(BlockVisitor visitor) {
        if (denseSections == null) {
            blocks.forEach(visitor::accept);
            return;
        }
        for (int sectionIndex = 0; sectionIndex < denseSections.length; sectionIndex++) {
            BlockState[] section = denseSections[sectionIndex];
            if (section == null) continue;
            int baseY = minY + (sectionIndex << 4);
            for (int index = 0; index < section.length; index++) {
                BlockState state = section[index];
                if (state == null || state.isAir()) continue;
                int localX = index & 15;
                int localZ = index >> 4 & 15;
                int y = baseY + (index >> 8);
                visitor.accept(pack(localX, y, localZ, minY), state);
            }
        }
    }

    public int nonAirBlockCount() {
        return denseSections == null ? blocks.size() : denseNonAirBlockCount;
    }

    public int populatedColumnCount() {
        int populated = 0;
        for (int height : surfaceHeights) {
            if (height > minY) populated++;
        }
        return populated;
    }

    /** Rejects sparse block-update captures masquerading as complete chunks. */
    public boolean usableForTerrainOverride() {
        return populatedColumnCount() >= MIN_COMPLETE_SURFACE_COLUMNS
            || dimension == 1 && nonAirBlockCount() >= MIN_END_SNAPSHOT_BLOCKS;
    }

    public BlockState block(int localX, int y, int localZ) {
        if (!inside(localX, y, localZ)) return null;
        if (denseSections != null) {
            BlockState[] section = denseSections[(y - minY) >> 4];
            return section == null ? null
                : section[((y - minY) & 15) << 8 | localZ << 4 | localX];
        }
        return blocks.get(pack(localX, y, localZ, minY));
    }

    public void setBlock(int localX, int y, int localZ, BlockState state) {
        if (sealed) throw new IllegalStateException("Snapshot is immutable");
        if (!inside(localX, y, localZ)) return;
        if (denseSections != null) {
            setDenseBlock(localX, y, localZ, state);
            updatedAt = System.currentTimeMillis();
            return;
        }
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

    private void setDenseBlock(int localX, int y, int localZ, BlockState state) {
        int sectionIndex = (y - minY) >> 4;
        BlockState[] section = denseSections[sectionIndex];
        int blockIndex = ((y - minY) & 15) << 8 | localZ << 4 | localX;
        BlockState previous = section == null ? null : section[blockIndex];
        boolean previousAir = previous == null || previous.isAir();
        boolean nextAir = state == null || state.isAir();
        if (previousAir && nextAir) return;
        if (section == null) denseSections[sectionIndex] = section = new BlockState[4_096];
        section[blockIndex] = nextAir ? null : state;
        if (previousAir) denseNonAirBlockCount++;
        else if (nextAir) denseNonAirBlockCount--;

        int surfaceIndex = localZ * CHUNK_SIZE + localX;
        if (!nextAir && y + 1 > surfaceHeights[surfaceIndex]) {
            surfaceHeights[surfaceIndex] = y + 1;
        } else if (nextAir && surfaceHeights[surfaceIndex] == y + 1) {
            recomputeSurface(localX, localZ);
        }
    }

    private Map<Integer, BlockState> materializeDenseBlocks() {
        Map<Integer, BlockState> materialized = new java.util.HashMap<>(
            Math.max(16, denseNonAirBlockCount * 4 / 3));
        forEachBlock(materialized::put);
        return materialized;
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

    public static final class CaptureBuilder {
        private LiveChunkSnapshot snapshot;

        private CaptureBuilder(LiveChunkSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        public void addBlock(int localX, int y, int localZ, BlockState state) {
            LiveChunkSnapshot target = snapshot;
            if (target == null) throw new IllegalStateException("Snapshot capture is complete");
            if (state == null || state.isAir() || !target.inside(localX, y, localZ)) return;
            target.setDenseBlock(localX, y, localZ, state);
        }

        public LiveChunkSnapshot build() {
            LiveChunkSnapshot completed = snapshot;
            if (completed == null) throw new IllegalStateException("Snapshot capture is complete");
            completed.updatedAt = System.currentTimeMillis();
            completed.sealed = true;
            snapshot = null;
            return completed;
        }
    }

    @FunctionalInterface
    public interface BlockVisitor {
        void accept(int packedPosition, BlockState state);
    }
}
