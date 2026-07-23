package me.seedexplorer.addon.live;

import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.workers.WorkerManager;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Records chunks observed by the client so the map can override predictions with real blocks. */
public final class LiveChunkRecorder {
    private static final LiveChunkRecorder INSTANCE = new LiveChunkRecorder();
    private static final int MAX_SNAPSHOTS_IN_MEMORY = 2048;

    private final Map<ChunkKey, LiveChunkSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Set<ChunkKey> loading = ConcurrentHashMap.newKeySet();
    private final Set<ChunkKey> saving = ConcurrentHashMap.newKeySet();
    private final Set<ChunkKey> dirtyWhileSaving = ConcurrentHashMap.newKeySet();

    private LiveChunkRecorder() {
    }

    public static LiveChunkRecorder get() {
        return INSTANCE;
    }

    public void init() {
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public LiveChunkSnapshot snapshot(int dimension, int chunkX, int chunkZ) {
        String profile = SeedManager.get().getActiveProfileKey();
        ChunkKey key = new ChunkKey(profile, dimension, chunkX, chunkZ);
        LiveChunkSnapshot snapshot = snapshots.get(key);
        if (snapshot != null) return snapshot;

        if (loading.add(key)) {
            WorkerManager.get().submit(() -> {
                try {
                    LiveChunkSnapshot loaded = LiveChunkStore.get().load(profile, dimension, chunkX, chunkZ);
                    if (loaded != null) {
                        snapshots.put(key, loaded);
                        pruneSnapshotCache();
                    }
                } finally {
                    loading.remove(key);
                }
            });
        }
        return null;
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        LevelChunk chunk = event.chunk();
        if (chunk == null || chunk.getLevel() == null) return;
        int dimension = dimensionId(chunk.getLevel().dimension());
        String profile = SeedManager.get().getActiveProfileKey();
        ChunkKey key = new ChunkKey(profile, dimension, chunk.getPos().x(), chunk.getPos().z());

        LiveChunkSnapshot snapshot = captureChunk(chunk, dimension);
        snapshots.put(key, snapshot);
        pruneSnapshotCache();
        queueSave(key, snapshot);
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || event.pos == null) return;

        int dimension = dimensionId(mc.level.dimension());
        int chunkX = Math.floorDiv(event.pos.getX(), LiveChunkSnapshot.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(event.pos.getZ(), LiveChunkSnapshot.CHUNK_SIZE);
        String profile = SeedManager.get().getActiveProfileKey();
        ChunkKey key = new ChunkKey(profile, dimension, chunkX, chunkZ);

        LiveChunkSnapshot snapshot = snapshots.computeIfAbsent(key,
            ignored -> new LiveChunkSnapshot(dimension, chunkX, chunkZ, mc.level.getMinY(), mc.level.getMaxY()));
        snapshot.setBlock(event.pos.getX() & 15, event.pos.getY(), event.pos.getZ() & 15, event.newState);
        pruneSnapshotCache();
        queueSave(key, snapshot);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        snapshots.clear();
        loading.clear();
        saving.clear();
    }

    private LiveChunkSnapshot captureChunk(LevelChunk chunk, int dimension) {
        int minY = chunk.getLevel().getMinY();
        int maxY = chunk.getLevel().getMaxY();
        LiveChunkSnapshot snapshot = new LiveChunkSnapshot(dimension, chunk.getPos().x(), chunk.getPos().z(), minY, maxY);

        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) continue;
            int sectionMinY = minY + sectionIndex * 16;

            for (int y = 0; y < 16; y++) {
                int blockY = sectionMinY + y;
                if (blockY < minY || blockY >= maxY) continue;
                for (int z = 0; z < LiveChunkSnapshot.CHUNK_SIZE; z++) {
                    for (int x = 0; x < LiveChunkSnapshot.CHUNK_SIZE; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!state.isAir()) snapshot.setBlock(x, blockY, z, state);
                    }
                }
            }
        }
        return snapshot;
    }

    private void queueSave(ChunkKey key, LiveChunkSnapshot snapshot) {
        if (!saving.add(key)) {
            dirtyWhileSaving.add(key);
            return;
        }
        WorkerManager.get().submit(() -> {
            try {
                LiveChunkStore.get().save(key.profile, snapshot);
            } catch (Throwable ignored) {
            } finally {
                saving.remove(key);
                if (dirtyWhileSaving.remove(key)) {
                    LiveChunkSnapshot latest = snapshots.get(key);
                    if (latest != null) queueSave(key, latest);
                }
            }
        });
    }

    private void pruneSnapshotCache() {
        while (snapshots.size() > MAX_SNAPSHOTS_IN_MEMORY) {
            ChunkKey oldestKey = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<ChunkKey, LiveChunkSnapshot> entry : snapshots.entrySet()) {
                long updatedAt = entry.getValue().updatedAt();
                if (updatedAt < oldestTime && !saving.contains(entry.getKey())) {
                    oldestTime = updatedAt;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) return;
            snapshots.remove(oldestKey);
        }
    }

    private static int dimensionId(ResourceKey<Level> dimension) {
        if (dimension == Level.NETHER) return -1;
        if (dimension == Level.END) return 1;
        return 0;
    }

    private record ChunkKey(String profile, int dimension, int chunkX, int chunkZ) {
    }
}
