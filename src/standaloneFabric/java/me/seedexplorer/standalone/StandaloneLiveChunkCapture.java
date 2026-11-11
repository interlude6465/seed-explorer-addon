package me.seedexplorer.standalone;

import me.seedexplorer.addon.live.LiveChunkSnapshot;
import me.seedexplorer.addon.render.TerrainWorldDataSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded client-thread capture of already-loaded chunks for the shared 3D
 * renderer. Partial captures are never published and renderer workers only
 * observe completed snapshots.
 */
public final class StandaloneLiveChunkCapture implements TerrainWorldDataSource {
    static final int CAPTURE_RADIUS_CHUNKS = 3;
    static final int MAX_SNAPSHOTS = 64;
    static final int MAX_BLOCK_READS_PER_TICK = 8_192;
    static final long CAPTURE_BUDGET_NANOS = 750_000L;
    static final long RECAPTURE_MILLIS = 60_000L;
    private static final List<ChunkOffset> CAPTURE_ORDER = createCaptureOrder();

    private final Map<Long, LiveChunkSnapshot> snapshots = new LinkedHashMap<>(128, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, LiveChunkSnapshot> eldest) {
            return size() > MAX_SNAPSHOTS;
        }
    };
    private ClientLevel level;
    private volatile int dimension = Integer.MIN_VALUE;
    private int anchorChunkX = Integer.MIN_VALUE;
    private int anchorChunkZ = Integer.MIN_VALUE;
    private int captureCursor;
    private PendingCapture pending;

    /** Advances capture only from the Fabric end-client-tick callback. */
    public void tick(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            clear();
            return;
        }

        ClientLevel currentLevel = minecraft.level;
        int currentDimension = dimensionId(currentLevel);
        ensureContext(currentLevel, currentDimension);
        int playerChunkX = Math.floorDiv(minecraft.player.blockPosition().getX(), 16);
        int playerChunkZ = Math.floorDiv(minecraft.player.blockPosition().getZ(), 16);
        if (playerChunkX != anchorChunkX || playerChunkZ != anchorChunkZ) {
            if (pending != null && Math.max(Math.abs(pending.chunkX - playerChunkX),
                Math.abs(pending.chunkZ - playerChunkZ)) > CAPTURE_RADIUS_CHUNKS) {
                pending = null;
            }
            anchorChunkX = playerChunkX;
            anchorChunkZ = playerChunkZ;
            captureCursor = 0;
        }

        long deadline = System.nanoTime() + CAPTURE_BUDGET_NANOS;
        int remainingReads = MAX_BLOCK_READS_PER_TICK;
        while (remainingReads > 0 && System.nanoTime() < deadline) {
            if (pending == null) pending = nextCapture(currentLevel, currentDimension);
            if (pending == null) return;
            LevelChunk stillLoaded = currentLevel.getChunkSource().getChunkNow(
                pending.chunkX, pending.chunkZ);
            if (stillLoaded != pending.chunk) {
                pending = null;
                continue;
            }
            int used = pending.advance(remainingReads, deadline);
            remainingReads -= used;
            if (!pending.complete()) return;
            LiveChunkSnapshot completed = pending.finish();
            pending = null;
            if (completed.usableForTerrainOverride()) {
                synchronized (snapshots) {
                    snapshots.put(key(completed.chunkX(), completed.chunkZ()), completed);
                }
            }
        }
    }

    @Override
    public LiveChunkSnapshot snapshot(int requestedDimension, int chunkX, int chunkZ) {
        return peekSnapshot(requestedDimension, chunkX, chunkZ);
    }

    @Override
    public LiveChunkSnapshot peekSnapshot(int requestedDimension, int chunkX, int chunkZ) {
        if (requestedDimension != dimension) return null;
        synchronized (snapshots) {
            return snapshots.get(key(chunkX, chunkZ));
        }
    }

    /** Copies references to complete immutable captures; callers never read a live chunk. */
    List<LiveChunkSnapshot> snapshots(ClientLevel expectedLevel, int requestedDimension) {
        if (expectedLevel == null || level != expectedLevel || requestedDimension != dimension) return List.of();
        synchronized (snapshots) {
            return List.copyOf(snapshots.values());
        }
    }

    public void clear() {
        synchronized (snapshots) {
            snapshots.clear();
        }
        level = null;
        dimension = Integer.MIN_VALUE;
        anchorChunkX = Integer.MIN_VALUE;
        anchorChunkZ = Integer.MIN_VALUE;
        captureCursor = 0;
        pending = null;
    }

    private PendingCapture nextCapture(ClientLevel currentLevel, int currentDimension) {
        long now = System.currentTimeMillis();
        int examined = 0;
        while (examined++ < CAPTURE_ORDER.size()) {
            if (captureCursor >= CAPTURE_ORDER.size()) captureCursor = 0;
            ChunkOffset offset = CAPTURE_ORDER.get(captureCursor++);
            int chunkX = anchorChunkX + offset.x;
            int chunkZ = anchorChunkZ + offset.z;
            LiveChunkSnapshot existing;
            synchronized (snapshots) {
                existing = snapshots.get(key(chunkX, chunkZ));
            }
            if (existing != null && now - existing.updatedAt() < RECAPTURE_MILLIS) continue;
            LevelChunk chunk = currentLevel.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk != null) return new PendingCapture(chunk, currentDimension);
        }
        return null;
    }

    private void ensureContext(ClientLevel currentLevel, int currentDimension) {
        if (level == currentLevel && dimension == currentDimension) return;
        synchronized (snapshots) {
            snapshots.clear();
        }
        level = currentLevel;
        dimension = currentDimension;
        anchorChunkX = Integer.MIN_VALUE;
        anchorChunkZ = Integer.MIN_VALUE;
        captureCursor = 0;
        pending = null;
    }

    private static long key(int chunkX, int chunkZ) {
        return (long) chunkX << 32 ^ chunkZ & 0xFFFFFFFFL;
    }

    private static int dimensionId(Level level) {
        if (level.dimension() == Level.NETHER) return -1;
        if (level.dimension() == Level.END) return 1;
        return 0;
    }

    private static List<ChunkOffset> createCaptureOrder() {
        List<ChunkOffset> offsets = new ArrayList<>();
        for (int z = -CAPTURE_RADIUS_CHUNKS; z <= CAPTURE_RADIUS_CHUNKS; z++) {
            for (int x = -CAPTURE_RADIUS_CHUNKS; x <= CAPTURE_RADIUS_CHUNKS; x++) {
                offsets.add(new ChunkOffset(x, z));
            }
        }
        offsets.sort(Comparator
            .comparingInt((ChunkOffset offset) -> Math.max(Math.abs(offset.x), Math.abs(offset.z)))
            .thenComparingInt(offset -> spiralSide(offset.x, offset.z))
            .thenComparingInt(offset -> spiralPosition(offset.x, offset.z)));
        return List.copyOf(offsets);
    }

    private static int spiralSide(int x, int z) {
        int ring = Math.max(Math.abs(x), Math.abs(z));
        if (ring == 0 || z == -ring && x < ring) return 0;
        if (x == ring && z < ring) return 1;
        if (z == ring && x > -ring) return 2;
        return 3;
    }

    private static int spiralPosition(int x, int z) {
        return switch (spiralSide(x, z)) {
            case 0 -> x;
            case 1 -> z;
            case 2 -> -x;
            default -> -z;
        };
    }

    static int captureOrderSize() {
        return CAPTURE_ORDER.size();
    }

    static boolean captureOrderIsComplete() {
        boolean[][] seen = new boolean[CAPTURE_RADIUS_CHUNKS * 2 + 1][CAPTURE_RADIUS_CHUNKS * 2 + 1];
        int previousRing = -1;
        for (ChunkOffset offset : CAPTURE_ORDER) {
            int ix = offset.x + CAPTURE_RADIUS_CHUNKS;
            int iz = offset.z + CAPTURE_RADIUS_CHUNKS;
            int ring = Math.max(Math.abs(offset.x), Math.abs(offset.z));
            if (ix < 0 || iz < 0 || ix >= seen.length || iz >= seen.length
                || seen[iz][ix] || ring < previousRing) return false;
            seen[iz][ix] = true;
            previousRing = ring;
        }
        return CAPTURE_ORDER.size() == seen.length * seen.length;
    }

    private record ChunkOffset(int x, int z) {
    }

    private static final class PendingCapture {
        private final LevelChunk chunk;
        private final int chunkX;
        private final int chunkZ;
        private final int minY;
        private final LevelChunkSection[] sections;
        private final int minSectionY;
        private final LiveChunkSnapshot.CaptureBuilder builder;
        private int sectionIndex;
        private int blockIndex;

        private PendingCapture(LevelChunk chunk, int dimension) {
            this.chunk = chunk;
            this.chunkX = chunk.getPos().x();
            this.chunkZ = chunk.getPos().z();
            this.minY = chunk.getMinY();
            this.sections = chunk.getSections();
            this.minSectionY = chunk.getMinSectionY();
            this.builder = LiveChunkSnapshot.captureBuilder(
                dimension, chunkX, chunkZ, minY, chunk.getMaxY());
        }

        private int advance(int maximumReads, long deadline) {
            int reads = 0;
            while (sectionIndex < sections.length && reads < maximumReads) {
                LevelChunkSection section = sections[sectionIndex];
                if (section == null || section.hasOnlyAir()) {
                    sectionIndex++;
                    blockIndex = 0;
                    continue;
                }
                while (blockIndex < 4_096 && reads < maximumReads) {
                    int packed = blockIndex++;
                    int x = packed & 15;
                    int z = packed >> 4 & 15;
                    int y = packed >> 8;
                    BlockState state = section.getBlockState(x, y, z);
                    if (!state.isAir()) {
                        builder.addBlock(x, ((minSectionY + sectionIndex) << 4) + y, z, state);
                    }
                    reads++;
                    if ((reads & 63) == 0 && System.nanoTime() >= deadline) return reads;
                }
                if (blockIndex >= 4_096) {
                    sectionIndex++;
                    blockIndex = 0;
                }
            }
            return reads;
        }

        private boolean complete() {
            return sectionIndex >= sections.length;
        }

        private LiveChunkSnapshot finish() {
            if (!complete()) throw new IllegalStateException("Chunk capture is incomplete");
            return builder.build();
        }
    }
}
