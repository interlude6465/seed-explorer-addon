package me.seedexplorer.addon.render;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Versioned, per-world streaming frontier persisted independently of terrain meshes. */
final class TerrainStreamStateStore {
    private static final int MAGIC = 0x53455346; // SESF
    private static final int VERSION = 1;
    private static final int MAX_RETRIES = 65_536;
    private static final int MAX_REGIONS = 1_000_000;

    private final Path root;
    private final ThreadPoolExecutor writer;

    TerrainStreamStateStore() {
        this(Path.of(System.getProperty("user.home", "."), ".seed-explorer", "terrain-streams"));
    }

    TerrainStreamStateStore(Path root) {
        this.root = root;
        writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "Seed Explorer Terrain Frontier");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    Optional<State> load(String worldIdentity, long seed, int dimension) {
        Path file = stateFile(worldIdentity, seed, dimension);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return Optional.empty();
            if (!in.readUTF().equals(worldIdentity) || in.readLong() != seed || in.readInt() != dimension) {
                return Optional.empty();
            }
            int anchorX = in.readInt();
            int anchorZ = in.readInt();
            TerrainStreamingCursor.State coverage = new TerrainStreamingCursor.State(in.readInt(), in.readInt());
            TerrainStreamingCursor.State detail = new TerrainStreamingCursor.State(in.readInt(), in.readInt());
            int retryCount = in.readInt();
            if (retryCount < 0 || retryCount > MAX_RETRIES) return Optional.empty();
            List<Long> retries = new ArrayList<>(retryCount);
            for (int i = 0; i < retryCount; i++) retries.add(in.readLong());
            int regionCount = in.readInt();
            if (regionCount < 0 || regionCount > MAX_REGIONS) return Optional.empty();
            Set<Long> regions = new HashSet<>(Math.max(16, regionCount * 2));
            for (int i = 0; i < regionCount; i++) regions.add(in.readLong());
            return Optional.of(new State(anchorX, anchorZ, coverage, detail,
                List.copyOf(retries), Set.copyOf(regions)));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    void storeAsync(String worldIdentity, long seed, int dimension, State state) {
        writer.execute(() -> storeNow(worldIdentity, seed, dimension, state));
    }

    void storeNow(String worldIdentity, long seed, int dimension, State state) {
        Path file = stateFile(worldIdentity, seed, dimension);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(temporary)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeUTF(worldIdentity);
                out.writeLong(seed);
                out.writeInt(dimension);
                out.writeInt(state.anchorChunkX());
                out.writeInt(state.anchorChunkZ());
                out.writeInt(state.coverage().ring());
                out.writeInt(state.coverage().index());
                out.writeInt(state.detail().ring());
                out.writeInt(state.detail().index());
                out.writeInt(state.retryCoordinates().size());
                for (long retry : state.retryCoordinates()) out.writeLong(retry);
                out.writeInt(state.completedRegions().size());
                for (long region : state.completedRegions()) out.writeLong(region);
            }
            moveAtomically(temporary, file);
        } catch (IOException | RuntimeException ignored) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignoredDelete) {
                // Best-effort progress cache.
            }
        }
    }

    void awaitIdle() throws InterruptedException {
        while (writer.getActiveCount() > 0 || !writer.getQueue().isEmpty()) Thread.sleep(5L);
    }

    private Path stateFile(String worldIdentity, long seed, int dimension) {
        return root.resolve(identityHash(worldIdentity))
            .resolve(Long.toUnsignedString(seed, 16))
            .resolve("d" + dimension + ".frontier");
    }

    private static String identityHash(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    record State(int anchorChunkX, int anchorChunkZ,
                 TerrainStreamingCursor.State coverage,
                 TerrainStreamingCursor.State detail,
                 List<Long> retryCoordinates,
                 Set<Long> completedRegions) {
    }
}
