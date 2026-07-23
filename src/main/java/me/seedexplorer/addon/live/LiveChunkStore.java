package me.seedexplorer.addon.live;

import me.seedexplorer.addon.worldgen.WorldgenEngine;
import meteordevelopment.meteorclient.MeteorClient;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compressed per-world/per-dimension disk storage for observed chunk snapshots. */
public final class LiveChunkStore {
    private static final int FORMAT_VERSION = 1;
    private static final LiveChunkStore INSTANCE = new LiveChunkStore();

    private LiveChunkStore() {
    }

    public static LiveChunkStore get() {
        return INSTANCE;
    }

    public Path chunkPath(String profileKey, int dimension, int chunkX, int chunkZ) {
        return root(profileKey, dimension).resolve(chunkX + "." + chunkZ + ".nbt.gz");
    }

    public LiveChunkSnapshot load(String profileKey, int dimension, int chunkX, int chunkZ) {
        return loadFrom(chunkPath(profileKey, dimension, chunkX, chunkZ), dimension, chunkX, chunkZ);
    }

    public LiveChunkSnapshot loadFrom(Path path, int dimension, int chunkX, int chunkZ) {
        if (!Files.isRegularFile(path)) return null;
        try {
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.create(64L * 1024L * 1024L));
            if (tag.getIntOr("format", 0) != FORMAT_VERSION) return null;
            int storedDimension = tag.getIntOr("dimension", dimension);
            int storedChunkX = tag.getIntOr("chunkX", chunkX);
            int storedChunkZ = tag.getIntOr("chunkZ", chunkZ);
            if (storedDimension != dimension || storedChunkX != chunkX || storedChunkZ != chunkZ) return null;

            int minY = tag.getIntOr("minY", -64);
            int maxY = tag.getIntOr("maxY", 320);
            int[] positions = tag.getIntArray("positions").orElse(new int[0]);
            int[] paletteIndices = tag.getIntArray("paletteIndices").orElse(new int[0]);
            ListTag paletteTag = tag.getListOrEmpty("palette");
            if (positions.length != paletteIndices.length || paletteTag.isEmpty()) return null;

            BlockState[] palette = new BlockState[paletteTag.size()];
            var blocks = WorldgenEngine.offlineRegistryAccess().lookupOrThrow(Registries.BLOCK);
            for (int i = 0; i < paletteTag.size(); i++) {
                palette[i] = NbtUtils.readBlockState(blocks, paletteTag.getCompoundOrEmpty(i));
            }

            Map<Integer, BlockState> snapshotBlocks = new HashMap<>();
            for (int i = 0; i < positions.length; i++) {
                int paletteIndex = paletteIndices[i];
                if (paletteIndex < 0 || paletteIndex >= palette.length) continue;
                BlockState state = palette[paletteIndex];
                if (state != null && !state.isAir()) snapshotBlocks.put(positions[i], state);
            }

            return new LiveChunkSnapshot(dimension, chunkX, chunkZ, minY, maxY,
                snapshotBlocks, tag.getLongOr("updatedAt", 0L));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void save(String profileKey, LiveChunkSnapshot snapshot) throws IOException {
        saveTo(chunkPath(profileKey, snapshot.dimension(), snapshot.chunkX(), snapshot.chunkZ()), profileKey, snapshot);
    }

    public void saveTo(Path path, String profileKey, LiveChunkSnapshot snapshot) throws IOException {
        Files.createDirectories(path.getParent());
        NbtIo.writeCompressed(toTag(profileKey, snapshot), path);
    }

    private CompoundTag toTag(String profileKey, LiveChunkSnapshot snapshot) {
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

        Map<Integer, BlockState> blocks = snapshot.blocks();
        Map<BlockState, Integer> paletteLookup = new HashMap<>();
        List<CompoundTag> palette = new java.util.ArrayList<>();
        int[] positions = new int[blocks.size()];
        int[] paletteIndices = new int[blocks.size()];
        int i = 0;
        for (Map.Entry<Integer, BlockState> entry : blocks.entrySet()) {
            BlockState state = entry.getValue();
            Integer paletteIndex = paletteLookup.get(state);
            if (paletteIndex == null) {
                paletteIndex = palette.size();
                paletteLookup.put(state, paletteIndex);
                palette.add(NbtUtils.writeBlockState(state));
            }
            positions[i] = entry.getKey();
            paletteIndices[i] = paletteIndex;
            i++;
        }

        ListTag paletteTag = new ListTag();
        for (CompoundTag stateTag : palette) paletteTag.add(stateTag);
        tag.put("palette", paletteTag);
        tag.putIntArray("positions", positions);
        tag.putIntArray("paletteIndices", paletteIndices);
        return tag;
    }

    private Path root(String profileKey, int dimension) {
        return MeteorClient.FOLDER.toPath()
            .resolve("seed-explorer")
            .resolve("observed-chunks")
            .resolve(safeProfile(profileKey))
            .resolve("dim_" + dimension);
    }

    private static String safeProfile(String profileKey) {
        String key = profileKey == null || profileKey.isBlank() ? "global" : profileKey;
        return java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
