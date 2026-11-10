package me.seedexplorer.standalone;

import me.seedexplorer.addon.ore.OrePatch;
import me.seedexplorer.addon.ore.OrePredictor;
import me.seedexplorer.addon.ore.OreType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Async standalone ore prediction with a render-thread-only immutable gizmo snapshot. */
public final class StandaloneOreEspRenderer {
    private static final long REFRESH_NANOS = 500_000_000L;
    private static final long DIAGNOSTIC_INTERVAL_NANOS = 10_000_000_000L;
    private static final int MAX_DIAGNOSTIC_FRAMES = 6;
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "Seed Explorer ore ESP");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final AtomicLong REQUESTS = new AtomicLong();

    private static volatile StandaloneSeedExplorerConfig config;
    private static volatile PredictionResult prediction = PredictionResult.EMPTY;
    private static volatile RenderSnapshot rendered = RenderSnapshot.EMPTY;
    private static volatile long pendingKey = Long.MIN_VALUE;
    private static volatile long lastRequestNanos;
    private static volatile ClientLevel activeLevel;
    private static final Object DIAGNOSTIC_LOCK = new Object();
    private static long lastDiagnosticNanos;
    private static int suppressedDiagnostics;

    private StandaloneOreEspRenderer() {
    }

    public static void install(StandaloneSeedExplorerConfig standaloneConfig) {
        config = standaloneConfig;
    }

    /** Called from the Fabric end-client-tick callback. Never performs worldgen itself. */
    public static void tick(Minecraft minecraft) {
        StandaloneSeedExplorerConfig currentConfig = config;
        if (currentConfig == null || minecraft == null || minecraft.player == null
            || minecraft.level == null || !currentConfig.booleanValue("ore-esp", false)) {
            clear();
            return;
        }

        ClientLevel level = minecraft.level;
        if (activeLevel != level) {
            invalidatePredictions();
            activeLevel = level;
            lastRequestNanos = 0L;
        }

        int dimension = dimensionId(level);
        OreType type = selectedType(currentConfig, dimension);
        if (type == null) {
            clear();
            return;
        }

        Long resolvedSeed = effectiveSeed(minecraft, currentConfig);
        if (resolvedSeed == null) {
            clear();
            return;
        }
        long seed = resolvedSeed;

        BlockPos playerPos = minecraft.player.blockPosition();
        int chunkX = Math.floorDiv(playerPos.getX(), 16);
        int chunkZ = Math.floorDiv(playerPos.getZ(), 16);
        int radius = currentConfig.intValue("ore-esp-radius", 2, 1, 6);
        int maxBoxes = currentConfig.intValue("ore-esp-max-boxes", 80, 1, 500);
        long key = requestKey(seed, dimension, type, chunkX, chunkZ, radius, maxBoxes);

        PredictionResult current = prediction;
        if (current.key != key && pendingKey != key) {
            invalidatePredictions();
            current = PredictionResult.EMPTY;
        }
        if (current.key == key) {
            rendered = reconcileLoaded(level, current, currentConfig);
        }

        long now = System.nanoTime();
        if (key == pendingKey || current.key == key || now - lastRequestNanos < REFRESH_NANOS) return;
        pendingKey = key;
        lastRequestNanos = now;
        long request = REQUESTS.incrementAndGet();
        int playerX = playerPos.getX();
        int playerY = playerPos.getY();
        int playerZ = playerPos.getZ();
        WORKER.execute(() -> predict(request, key, seed, dimension, type, chunkX, chunkZ,
            radius, maxBoxes, playerX, playerY, playerZ));
    }

    /** Called by the level-render mixin while vanilla's per-frame gizmo collector is active. */
    public static void renderGizmos() {
        RenderSnapshot snapshot = rendered;
        if (snapshot.patches.isEmpty()) return;
        GizmoStyle style = style(snapshot.type, snapshot.opacity, snapshot.shapeMode);
        for (OrePatch patch : snapshot.patches) {
            GizmoProperties properties = Gizmos.cuboid(
                new AABB(patch.x, patch.y, patch.z, patch.x + 1.0, patch.y + 1.0, patch.z + 1.0),
                style);
            // ESP remains visible through terrain, matching the original module.
            properties.setAlwaysOnTop();
        }
    }

    static int[] stageRadii(int radius) {
        int bounded = Math.max(0, radius);
        if (bounded == 0) return new int[]{0};
        if (bounded == 1) return new int[]{0, 1};
        return new int[]{0, 1, bounded};
    }

    static long requestKey(long seed, int dimension, OreType type, int chunkX, int chunkZ,
                           int radius, int maxBoxes) {
        long key = seed;
        key = key * 31L + dimension;
        key = key * 31L + type.ordinal();
        key = key * 31L + chunkX;
        key = key * 31L + chunkZ;
        key = key * 31L + radius;
        return key * 31L + maxBoxes;
    }

    static GizmoStyle style(OreType type, int opacity, ShapeMode mode) {
        int line = argb(type.color.a, type.color.r, type.color.g, type.color.b);
        int fill = argb(opacity, type.color.r, type.color.g, type.color.b);
        return switch (mode) {
            case LINES -> GizmoStyle.stroke(line, 1.5f);
            case SIDES -> GizmoStyle.fill(fill);
            case BOTH -> GizmoStyle.strokeAndFill(line, 1.5f, fill);
        };
    }

    static boolean matchesLoadedOreBlock(ClientLevel level, OrePatch patch) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(
            Math.floorDiv(patch.x, 16), Math.floorDiv(patch.z, 16));
        if (chunk == null) return true;
        return patch.type.matches(chunk.getBlockState(new BlockPos(patch.x, patch.y, patch.z)));
    }

    private static void predict(long request, long key, long seed, int dimension, OreType type,
                                int chunkX, int chunkZ, int radius, int maxBoxes,
                                int playerX, int playerY, int playerZ) {
        try {
            for (int stageRadius : stageRadii(radius)) {
                if (REQUESTS.get() != request || pendingKey != key) return;
                List<OrePatch> patches = OrePredictor.predictInChunkRadius(
                    chunkX, chunkZ, stageRadius, dimension, type, maxBoxes,
                    playerX, playerY, playerZ, seed);
                patches = stableNearest(patches, playerX, playerY, playerZ, maxBoxes);
                if (REQUESTS.get() != request || pendingKey != key) return;
                prediction = new PredictionResult(key, type, patches);
            }
        } catch (RuntimeException | LinkageError failure) {
            if (REQUESTS.get() == request) {
                prediction = PredictionResult.EMPTY;
                rendered = RenderSnapshot.EMPTY;
                reportFailure(failure);
            }
        } finally {
            if (REQUESTS.get() == request && pendingKey == key) pendingKey = Long.MIN_VALUE;
        }
    }

    private static RenderSnapshot reconcileLoaded(ClientLevel level, PredictionResult source,
                                                   StandaloneSeedExplorerConfig currentConfig) {
        List<OrePatch> reconciled = new ArrayList<>(source.patches.size());
        for (OrePatch patch : source.patches) {
            if (matchesLoadedOreBlock(level, patch)) reconciled.add(patch);
        }
        int opacity = currentConfig.intValue("ore-opacity", 55, 20, 255);
        ShapeMode shape = ShapeMode.parse(
            currentConfig.stringValue("ore-esp-shape-mode", "Both"));
        return new RenderSnapshot(source.type, List.copyOf(reconciled), opacity, shape);
    }

    private static List<OrePatch> stableNearest(List<OrePatch> patches, int x, int y, int z,
                                                int maxBoxes) {
        List<OrePatch> sorted = patches.stream().sorted(Comparator
            .comparingLong((OrePatch patch) -> distanceSquared(patch, x, y, z))
            .thenComparingInt(patch -> patch.x)
            .thenComparingInt(patch -> patch.y)
            .thenComparingInt(patch -> patch.z)).toList();
        return sorted.size() <= maxBoxes ? sorted : List.copyOf(sorted.subList(0, maxBoxes));
    }

    private static long distanceSquared(OrePatch patch, int x, int y, int z) {
        long dx = patch.x - x;
        long dy = patch.y - y;
        long dz = patch.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static OreType selectedType(StandaloneSeedExplorerConfig currentConfig, int dimension) {
        String selected = currentConfig.stringValue("ore-esp-type", "Diamond");
        for (OreType type : OreType.values()) {
            if (type.dimension == dimension && type.displayName.equals(selected)) return type;
        }
        return null;
    }

    private static Long effectiveSeed(Minecraft minecraft,
                                      StandaloneSeedExplorerConfig currentConfig) {
        if (currentConfig.hasSeed()) return currentConfig.seed();
        try {
            if (!minecraft.hasSingleplayerServer()) return null;
            Object server = minecraft.getClass().getMethod("getSingleplayerServer").invoke(minecraft);
            Object overworld = server == null ? null
                : server.getClass().getMethod("overworld").invoke(server);
            Object seed = overworld == null ? null
                : overworld.getClass().getMethod("getSeed").invoke(overworld);
            return seed instanceof Number number ? number.longValue() : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static int dimensionId(ClientLevel level) {
        if (level.dimension() == Level.NETHER) return -1;
        if (level.dimension() == Level.END) return 1;
        return 0;
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16)
            | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    private static void clear() {
        activeLevel = null;
        lastRequestNanos = 0L;
        invalidatePredictions();
    }

    private static void invalidatePredictions() {
        REQUESTS.incrementAndGet();
        pendingKey = Long.MIN_VALUE;
        prediction = PredictionResult.EMPTY;
        rendered = RenderSnapshot.EMPTY;
    }

    private static void reportFailure(Throwable failure) {
        long now = System.nanoTime();
        synchronized (DIAGNOSTIC_LOCK) {
            if (now - lastDiagnosticNanos < DIAGNOSTIC_INTERVAL_NANOS) {
                suppressedDiagnostics++;
                return;
            }
            String message = failure.getMessage();
            if (message != null && message.length() > 180) message = message.substring(0, 180) + "...";
            System.err.println("[Seed Explorer] Ore ESP prediction failed: "
                + failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message)
                + (suppressedDiagnostics == 0 ? ""
                    : " (" + suppressedDiagnostics + " similar diagnostics suppressed)"));
            StackTraceElement[] trace = failure.getStackTrace();
            for (int i = 0; i < Math.min(MAX_DIAGNOSTIC_FRAMES, trace.length); i++) {
                System.err.println("    at " + trace[i]);
            }
            suppressedDiagnostics = 0;
            lastDiagnosticNanos = now;
        }
    }

    enum ShapeMode {
        BOTH,
        LINES,
        SIDES;

        static ShapeMode parse(String value) {
            if ("Lines".equalsIgnoreCase(value)) return LINES;
            if ("Sides".equalsIgnoreCase(value)) return SIDES;
            return BOTH;
        }
    }

    private record PredictionResult(long key, OreType type, List<OrePatch> patches) {
        private static final PredictionResult EMPTY =
            new PredictionResult(Long.MIN_VALUE, OreType.DIAMOND, List.of());
    }

    private record RenderSnapshot(OreType type, List<OrePatch> patches,
                                  int opacity, ShapeMode shapeMode) {
        private static final RenderSnapshot EMPTY =
            new RenderSnapshot(OreType.DIAMOND, List.of(), 55, ShapeMode.BOTH);
    }
}
