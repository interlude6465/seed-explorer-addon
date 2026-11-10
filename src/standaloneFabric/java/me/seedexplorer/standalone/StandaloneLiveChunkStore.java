package me.seedexplorer.standalone;

import me.seedexplorer.addon.live.LiveChunkSnapshot;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Palette-compressed standalone persistence with a coalescing single background writer. */
public final class StandaloneLiveChunkStore implements AutoCloseable {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_PENDING_SNAPSHOTS = 512;
    private static final long MAX_DECOMPRESSED_BYTES = 64L * 1024L * 1024L;
    private final Path root;
    private final ExecutorService writer;
    private final Map<Path, PendingSave> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public StandaloneLiveChunkStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Seed Explorer chunk store");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    public static StandaloneLiveChunkStore createDefault() {
        return new StandaloneLiveChunkStore(FabricLoader.getInstance().getConfigDir()
            .resolve("seed-explorer").resolve("observed-chunks"));
    }

    public Path chunkPath(String profileKey, int dimension, int chunkX, int chunkZ) {
        return root.resolve(safeProfile(profileKey)).resolve("dim_" + dimension)
            .resolve(chunkX + "." + chunkZ + ".nbt.gz");
    }

    public LiveChunkSnapshot load(String profileKey, int dimension, int chunkX, int chunkZ) {
        Path path = chunkPath(profileKey, dimension, chunkX, chunkZ);
        if (!Files.isRegularFile(path)) return null;
        try {
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.create(MAX_DECOMPRESSED_BYTES));
            if (tag.getIntOr("format", 0) != FORMAT_VERSION
                || tag.getIntOr("dimension", dimension) != dimension
                || tag.getIntOr("chunkX", chunkX) != chunkX
                || tag.getIntOr("chunkZ", chunkZ) != chunkZ) return null;
            int minY = tag.getIntOr("minY", -64);
            int maxY = tag.getIntOr("maxY", 320);
            int[] positions = tag.getIntArray("positions").orElse(new int[0]);
            int[] paletteIndices = tag.getIntArray("paletteIndices").orElse(new int[0]);
            ListTag paletteTag = tag.getListOrEmpty("palette");
            if (positions.length != paletteIndices.length || paletteTag.isEmpty()) return null;

            BlockState[] palette = new BlockState[paletteTag.size()];
            var blocks = WorldgenEngine.offlineRegistryAccess().lookupOrThrow(Registries.BLOCK);
            for (int index = 0; index < palette.length; index++) {
                palette[index] = NbtUtils.readBlockState(blocks, paletteTag.getCompoundOrEmpty(index));
            }
            Map<Integer, BlockState> snapshotBlocks = new HashMap<>();
            for (int index = 0; index < positions.length; index++) {
                int paletteIndex = paletteIndices[index];
                if (paletteIndex < 0 || paletteIndex >= palette.length) continue;
                BlockState state = palette[paletteIndex];
                if (state != null && !state.isAir()) snapshotBlocks.put(positions[index], state);
            }
            return new LiveChunkSnapshot(dimension, chunkX, chunkZ, minY, maxY,
                snapshotBlocks, tag.getLongOr("updatedAt", 0L));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public boolean saveAsync(String profileKey, LiveChunkSnapshot snapshot) {
        if (snapshot == null || closed.get()) return false;
        Path path = chunkPath(profileKey, snapshot.dimension(), snapshot.chunkX(), snapshot.chunkZ());
        if (!pending.containsKey(path) && pending.size() >= MAX_PENDING_SNAPSHOTS) return false;
        pending.put(path, new PendingSave(profileKey, snapshot));
        scheduleDrain();
        return true;
    }

    void saveNow(String profileKey, LiveChunkSnapshot snapshot) throws IOException {
        writeAtomically(chunkPath(profileKey, snapshot.dimension(), snapshot.chunkX(), snapshot.chunkZ()),
            toTag(profileKey, snapshot));
    }

    public int pendingCount() {
        return pending.size();
    }

    private void scheduleDrain() {
        if (closed.get() || !drainScheduled.compareAndSet(false, true)) return;
        writer.execute(this::drain);
    }

    private void drain() {
        try {
            int written = 0;
            for (Map.Entry<Path, PendingSave> entry : List.copyOf(pending.entrySet())) {
                if (written++ >= 8) break;
                PendingSave save = entry.getValue();
                if (!pending.remove(entry.getKey(), save)) continue;
                try {
                    writeAtomically(entry.getKey(), toTag(save.profileKey(), save.snapshot()));
                } catch (IOException ignored) {
                    // A failed cache write must never take down the client.
                }
            }
        } finally {
            drainScheduled.set(false);
            if (!pending.isEmpty()) scheduleDrain();
        }
    }

    private static void writeAtomically(Path path, CompoundTag tag) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        NbtIo.writeCompressed(tag, temporary);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException unsupportedAtomicMove) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static CompoundTag toTag(String profileKey, LiveChunkSnapshot snapshot) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("format", FORMAT_VERSION);
        tag.putString("profile", profileKey == null ? "" : profileKey);
        tag.putInt("dimension", snapshot.dimension());
        tag.putInt("chunkX", snapshot.chunkX());
        tag.putInt("chunkZ", snapshot.chunkZ());
        tag.putLong("chunkKey", ChunkPos.pack(snapshot.chunkX(), snapshot.chunkZ()));
        tag.putInt("minY", snapshot.minY());
        tag.putInt("maxY", snapshot.maxY());
        tag.putLong("updatedAt", snapshot.updatedAt());

        Map<BlockState, Integer> paletteLookup = new LinkedHashMap<>();
        List<CompoundTag> palette = new ArrayList<>();
        Map<Integer, BlockState> blocks = snapshot.blocks();
        int[] positions = new int[blocks.size()];
        int[] paletteIndices = new int[blocks.size()];
        int index = 0;
        for (Map.Entry<Integer, BlockState> entry : blocks.entrySet()) {
            BlockState state = entry.getValue();
            Integer paletteIndex = paletteLookup.get(state);
            if (paletteIndex == null) {
                paletteIndex = palette.size();
                paletteLookup.put(state, paletteIndex);
                palette.add(NbtUtils.writeBlockState(state));
            }
            positions[index] = entry.getKey();
            paletteIndices[index] = paletteIndex;
            index++;
        }
        ListTag paletteTag = new ListTag();
        palette.forEach(paletteTag::add);
        tag.put("palette", paletteTag);
        tag.putIntArray("positions", positions);
        tag.putIntArray("paletteIndices", paletteIndices);
        return tag;
    }

    private static String safeProfile(String profileKey) {
        String key = profileKey == null || profileKey.isBlank() ? "global" : profileKey;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(2, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException interrupted) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record PendingSave(String profileKey, LiveChunkSnapshot snapshot) {
    }
}
