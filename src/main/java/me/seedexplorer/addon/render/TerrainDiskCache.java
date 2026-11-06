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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Persistent generated-surface cache. Files are isolated by seed and dimension,
 * written atomically. Persistent coverage is unlimited by default; an explicit
 * system-property byte cap may be supplied by installations with constrained disks.
 */
public final class TerrainDiskCache {
    public static final long DEFAULT_MAX_BYTES = Long.MAX_VALUE;
    private static final int MAGIC = 0x53455443; // SETC
    private static final int VERSION = 3;
    private static final int LEGACY_VERSION = 2;
    private static final int CELL_COUNT = 16 * 16;
    private static final int MAX_PENDING_WRITES = 4_096;
    private static final int BULK_WRITER_THREADS = Math.max(2,
        Math.min(8, Runtime.getRuntime().availableProcessors() / 3));
    private static final int WRITE_LOCK_COUNT = 256;

    private final Path root;
    private final long maxBytes;
    private final AtomicLong knownBytes = new AtomicLong(-1L);
    private final List<Thread> writerThreads = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Object[] writeLocks = createWriteLocks();
    private final ThreadPoolExecutor writer;

    public TerrainDiskCache() {
        this(defaultRoot(), configuredMaxBytes());
    }

    TerrainDiskCache(Path root, long maxBytes) {
        this.root = root;
        this.maxBytes = Math.max(1L, maxBytes);
        this.writer = new ThreadPoolExecutor(1, BULK_WRITER_THREADS, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING_WRITES), runnable -> {
                Thread thread = new Thread(runnable, "Seed Explorer Terrain Cache");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                writerThreads.add(thread);
                return thread;
            }, new ThreadPoolExecutor.CallerRunsPolicy());
        writer.execute(this::scanAndEnforceLimit);
    }

    public void setBulkMode(boolean bulk) {
        int threads = bulk ? BULK_WRITER_THREADS : 1;
        writer.setCorePoolSize(threads);
        if (bulk) writer.prestartAllCoreThreads();
        int priority = bulk ? Thread.NORM_PRIORITY : Thread.MIN_PRIORITY;
        for (Thread thread : writerThreads) {
            if (!thread.isAlive()) {
                writerThreads.remove(thread);
                continue;
            }
            try {
                thread.setPriority(priority);
            } catch (RuntimeException ignored) {
                // Cache persistence remains valid if priority changes are unavailable.
            }
        }
    }

    public int pendingWrites() {
        return writer.getQueue().size() + writer.getActiveCount();
    }

    public Optional<CachedChunk> load(long seed, int dimension, int chunkX, int chunkZ, int desiredStep) {
        Optional<CachedChunk> current = loadFile(chunkFile(VERSION, seed, dimension, chunkX, chunkZ),
            seed, dimension, chunkX, chunkZ, desiredStep);
        return current.isPresent() ? current : loadFile(
            chunkFile(LEGACY_VERSION, seed, dimension, chunkX, chunkZ), seed, dimension,
            chunkX, chunkZ, desiredStep);
    }

    public Optional<CachedChunk> load(String worldIdentity, long seed, int dimension,
                                      int chunkX, int chunkZ, int desiredStep) {
        Optional<CachedChunk> isolated = loadFile(
            chunkFile(VERSION, worldIdentity, seed, dimension, chunkX, chunkZ), seed, dimension,
            chunkX, chunkZ, desiredStep);
        if (isolated.isPresent()) return isolated;
        isolated = loadFile(chunkFile(LEGACY_VERSION, worldIdentity, seed, dimension, chunkX, chunkZ),
            seed, dimension, chunkX, chunkZ, desiredStep);
        if (isolated.isPresent()) return isolated;
        for (int version : new int[]{VERSION, LEGACY_VERSION}) {
            Path unisolated = chunkFile(version, seed, dimension, chunkX, chunkZ);
            if (Files.isRegularFile(unisolated) && claimUnisolatedCache(
                worldIdentity, seed, version)) {
                Optional<CachedChunk> claimed = loadFile(unisolated, seed, dimension,
                    chunkX, chunkZ, desiredStep);
                if (claimed.isPresent()) return claimed;
            }
        }
        return Optional.empty();
    }

    private Optional<CachedChunk> loadFile(Path file, long seed, int dimension,
                                           int chunkX, int chunkZ, int desiredStep) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(
            new BufferedInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != MAGIC) return Optional.empty();
            int version = in.readInt();
            if (version != VERSION && version != LEGACY_VERSION) return Optional.empty();
            if (in.readLong() != seed || in.readInt() != dimension
                || in.readInt() != chunkX || in.readInt() != chunkZ) return Optional.empty();
            int step = in.readInt();
            if (!validStep(step) || step > desiredStep) return Optional.empty();
            boolean exactSurface = version >= VERSION && in.readBoolean();
            int[] heights = new int[CELL_COUNT];
            String[] states = new String[CELL_COUNT];
            int[] fluidHeights = new int[CELL_COUNT];
            String[] fluidStates = new String[CELL_COUNT];
            int entries = in.readInt();
            if (entries < 0 || entries > CELL_COUNT) return Optional.empty();
            for (int i = 0; i < entries; i++) {
                int index = in.readUnsignedShort();
                if (index >= CELL_COUNT) return Optional.empty();
                heights[index] = in.readInt();
                states[index] = readNullable(in);
                fluidHeights[index] = in.readInt();
                fluidStates[index] = readNullable(in);
            }
            return Optional.of(new CachedChunk(chunkX, chunkZ, step,
                heights, states, fluidHeights, fluidStates, exactSurface));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public void storeAsync(long seed, int dimension, CachedChunk chunk) {
        writer.execute(() -> storeNow(seed, dimension, chunk));
    }

    public void storeAsync(String worldIdentity, long seed, int dimension, CachedChunk chunk) {
        writer.execute(() -> storeNow(worldIdentity, seed, dimension, chunk));
    }

    void storeNow(long seed, int dimension, CachedChunk chunk) {
        if (chunk == null) return;
        storeNow(chunkFile(VERSION, seed, dimension, chunk.chunkX(), chunk.chunkZ()), seed, dimension, chunk);
    }

    void storeNow(String worldIdentity, long seed, int dimension, CachedChunk chunk) {
        if (chunk == null) return;
        storeNow(chunkFile(VERSION, worldIdentity, seed, dimension, chunk.chunkX(), chunk.chunkZ()),
            seed, dimension, chunk);
    }

    private void storeNow(Path file, long seed, int dimension, CachedChunk chunk) {
        if (chunk == null || !validStep(chunk.generatedStep())) return;
        synchronized (writeLocks[Math.floorMod(file.hashCode(), writeLocks.length)]) {
            storeNowLocked(file, seed, dimension, chunk);
        }
    }

    private void storeNowLocked(Path file, long seed, int dimension, CachedChunk chunk) {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (Files.isRegularFile(file)) {
                Optional<CachedChunk> current = loadFile(file, seed, dimension,
                    chunk.chunkX(), chunk.chunkZ(), 16);
                if (current.isPresent()) {
                    CachedChunk existing = current.get();
                    if (existing.generatedStep() < chunk.generatedStep()) return;
                    if (existing.generatedStep() == chunk.generatedStep()
                        && (existing.exactSurface() || !chunk.exactSurface())) return;
                }
            }
            Files.createDirectories(file.getParent());
            long oldSize = Files.isRegularFile(file) ? Files.size(file) : 0L;
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(
                new BufferedOutputStream(Files.newOutputStream(temporary))))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeLong(seed);
                out.writeInt(dimension);
                out.writeInt(chunk.chunkX());
                out.writeInt(chunk.chunkZ());
                out.writeInt(chunk.generatedStep());
                out.writeBoolean(chunk.exactSurface());
                int entries = populatedEntries(chunk);
                out.writeInt(entries);
                for (int i = 0; i < CELL_COUNT; i++) {
                    if (chunk.states()[i] == null && chunk.fluidStates()[i] == null) continue;
                    out.writeShort(i);
                    out.writeInt(chunk.heights()[i]);
                    writeNullable(out, chunk.states()[i]);
                    out.writeInt(chunk.fluidHeights()[i]);
                    writeNullable(out, chunk.fluidStates()[i]);
                }
            }
            moveAtomically(temporary, file);
            long newSize = Files.size(file);
            long currentBytes = knownBytes.get();
            if (currentBytes >= 0) {
                long updated = knownBytes.addAndGet(newSize - oldSize);
                if (updated > maxBytes) scanAndEnforceLimit();
            }
        } catch (IOException | RuntimeException ignored) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignoredDelete) {
                // Best-effort cache only.
            }
        }
    }

    void awaitIdle() throws InterruptedException {
        while (writer.getActiveCount() > 0 || !writer.getQueue().isEmpty()) {
            Thread.sleep(5L);
        }
    }

    long enforceLimitNow() {
        scanAndEnforceLimit();
        return Math.max(0L, knownBytes.get());
    }

    private void scanAndEnforceLimit() {
        List<CacheFile> files = new ArrayList<>();
        long total = 0L;
        try {
            if (Files.isDirectory(root)) {
                try (var stream = Files.walk(root)) {
                    for (Path path : stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".tdc")).toList()) {
                        long size = Files.size(path);
                        total += size;
                        files.add(new CacheFile(path, size, Files.getLastModifiedTime(path).toMillis()));
                    }
                }
            }
            if (total > maxBytes) {
                files.sort(Comparator.comparingLong(CacheFile::modifiedAt));
                for (CacheFile file : files) {
                    if (total <= maxBytes) break;
                    if (Files.deleteIfExists(file.path())) total -= file.size();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Keep rendering if cache maintenance is unavailable.
        } finally {
            knownBytes.set(Math.max(0L, total));
        }
    }

    private Path chunkFile(int version, long seed, int dimension, int chunkX, int chunkZ) {
        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);
        return root.resolve("v" + version)
            .resolve(Long.toUnsignedString(seed, 16))
            .resolve("d" + dimension)
            .resolve("r." + regionX + "." + regionZ)
            .resolve("c." + chunkX + "." + chunkZ + ".tdc");
    }

    private Path chunkFile(int version, String worldIdentity, long seed, int dimension, int chunkX, int chunkZ) {
        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);
        return root.resolve("v" + version)
            .resolve("w." + identityHash(worldIdentity))
            .resolve(Long.toUnsignedString(seed, 16))
            .resolve("d" + dimension)
            .resolve("r." + regionX + "." + regionZ)
            .resolve("c." + chunkX + "." + chunkZ + ".tdc");
    }

    private static String identityHash(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private synchronized boolean claimUnisolatedCache(String worldIdentity, long seed, int version) {
        Path owner = root.resolve("v" + version).resolve(Long.toUnsignedString(seed, 16))
            .resolve(".legacy-owner");
        String identity = identityHash(worldIdentity);
        try {
            Files.createDirectories(owner.getParent());
            if (Files.isRegularFile(owner)) {
                return Files.readString(owner, StandardCharsets.UTF_8).trim().equals(identity);
            }
            Files.writeString(owner, identity, StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static Path defaultRoot() {
        String override = System.getProperty("seedexplorer.terrainCacheDir", "").trim();
        if (!override.isEmpty()) return Path.of(override);
        return Path.of(System.getProperty("user.home", "."), ".seed-explorer", "terrain-cache");
    }

    private static long configuredMaxBytes() {
        String configured = System.getProperty("seedexplorer.terrainCacheMaxBytes", "").trim();
        if (configured.isEmpty()) return DEFAULT_MAX_BYTES;
        try {
            long bytes = Long.parseLong(configured);
            return bytes > 0L ? bytes : DEFAULT_MAX_BYTES;
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_BYTES;
        }
    }

    private static int populatedEntries(CachedChunk chunk) {
        validateArrays(chunk);
        int count = 0;
        for (int i = 0; i < CELL_COUNT; i++) {
            if (chunk.states()[i] != null || chunk.fluidStates()[i] != null) count++;
        }
        return count;
    }

    private static void validateArrays(CachedChunk chunk) {
        if (chunk.heights().length != CELL_COUNT || chunk.states().length != CELL_COUNT
            || chunk.fluidHeights().length != CELL_COUNT || chunk.fluidStates().length != CELL_COUNT) {
            throw new IllegalArgumentException("Terrain cache arrays must contain 256 cells");
        }
    }

    private static boolean validStep(int step) {
        return step == 1 || step == 2 || step == 4 || step == 8 || step == 16;
    }

    private static Object[] createWriteLocks() {
        Object[] locks = new Object[WRITE_LOCK_COUNT];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }

    private static String readNullable(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    private static void writeNullable(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeUTF(value);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record CachedChunk(int chunkX, int chunkZ, int generatedStep,
                              int[] heights, String[] states,
                              int[] fluidHeights, String[] fluidStates,
                              boolean exactSurface) {
        public CachedChunk(int chunkX, int chunkZ, int generatedStep,
                           int[] heights, String[] states,
                           int[] fluidHeights, String[] fluidStates) {
            this(chunkX, chunkZ, generatedStep, heights, states, fluidHeights, fluidStates, false);
        }
    }

    private record CacheFile(Path path, long size, long modifiedAt) {
    }
}
