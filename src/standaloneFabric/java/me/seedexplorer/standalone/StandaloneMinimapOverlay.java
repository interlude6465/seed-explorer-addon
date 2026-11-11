package me.seedexplorer.standalone;

import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.PredictionContext;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded predicted minimap with authoritative already-loaded terrain overlays. */
final class StandaloneMinimapOverlay implements HudElement {
    static final int GRID_SIZE = 24;
    static final int MAX_NEW_SAMPLES_PER_FRAME = 24;
    static final long SAMPLE_BUDGET_NANOS = 300_000L;
    static final int MAX_CACHED_SAMPLES = 12_288;
    static final int AREA_STRIDE_BLOCKS = 32;
    static final int PREDICTION_RADIUS_CELLS = 18;
    static final int PREDICTION_GRID_SIZE = PREDICTION_RADIUS_CELLS * 2 + 1;
    static final int MAX_PREDICTION_AREAS = 8;
    static final int MAX_PREDICTION_QUEUE = 1;

    private static final int SAMPLE_STEP = 5;
    private static final int UNSAMPLED = 0;
    private static final int[] SAMPLE_ORDER = createSampleOrder();

    private static final int FRAME = 0xEE080B11;
    private static final int INNER = 0xEE0F1319;
    private static final int BORDER = 0xEB56B0C2;
    private static final int OVERWORLD_PLACEHOLDER = 0xE636563E;
    private static final int NETHER_PLACEHOLDER = 0xE65C2428;
    private static final int END_PLACEHOLDER = 0xE6BCB87E;
    private static final long DIAGNOSTIC_INTERVAL_NANOS = 10_000_000_000L;
    private static final ThreadPoolExecutor PREDICTOR = new ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(MAX_PREDICTION_QUEUE), runnable -> {
            Thread thread = new Thread(runnable, "Seed Explorer minimap prediction");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());

    private final StandaloneSeedExplorerConfig config;
    private final AtomicLong predictionRequests = new AtomicLong();
    private final Map<Long, Integer> terrainColors = new LinkedHashMap<>(2048, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
            return size() > MAX_CACHED_SAMPLES;
        }
    };
    private final Map<PredictionKey, PredictionSnapshot> predictionCache =
        new LinkedHashMap<>(MAX_PREDICTION_AREAS, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<PredictionKey, PredictionSnapshot> eldest) {
                return size() > MAX_PREDICTION_AREAS;
            }
        };

    private Level cachedLevel;
    private int cachedDimension = Integer.MIN_VALUE;
    private int samplingCenterX = Integer.MIN_VALUE;
    private int samplingCenterZ = Integer.MIN_VALUE;
    private int sampleCursor;
    private int retryFrames;
    private volatile PredictionKey activePredictionKey;
    private volatile PredictionKey pendingPredictionKey;
    private volatile PredictionSnapshot prediction = PredictionSnapshot.EMPTY;
    private volatile Future<?> predictionFuture;
    private volatile PredictionKey failedPredictionKey;
    private volatile long retryPredictionAfterNanos;
    private long lastDiagnosticNanos;
    private int suppressedDiagnostics;

    StandaloneMinimapOverlay(StandaloneSeedExplorerConfig config) {
        this.config = config;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!config.booleanValue("minimap", true)
            || minecraft.player == null || minecraft.level == null
            || minecraft.gui.screen() != null) return;

        int available = Math.min(graphics.guiWidth() - 16, graphics.guiHeight() - 32);
        if (available < 64) return;
        int size = Math.min(config.intValue("minimap-size", 150, 90, 260), available);
        int dimension = dimensionId(minecraft.level);
        ensureCacheContext(minecraft.level, dimension);

        int playerX = minecraft.player.blockPosition().getX();
        int playerZ = minecraft.player.blockPosition().getZ();
        int centerX = alignedSample(playerX);
        int centerZ = alignedSample(playerZ);
        requestPrediction(centerX, centerZ, dimension);
        sampleLoadedTerrain(minecraft, centerX, centerZ, dimension);
        draw(graphics, minecraft, size, centerX, centerZ, dimension, currentPrediction());
    }

    private void draw(GuiGraphicsExtractor graphics, Minecraft minecraft, int size,
                      int centerX, int centerZ, int dimension,
                      PredictionSnapshot predicted) {
        int x = 10;
        int y = 10;
        int inset = 6;
        int mapX = x + inset;
        int mapY = y + inset;
        int mapSize = size - inset * 2;
        int placeholder = placeholder(dimension);
        int biomeOpacity = config.intValue("biome-opacity", 255, 40, 255);

        graphics.fill(x + 3, y + 4, x + size + 3, y + size + 4, 0x70000000);
        graphics.fill(x, y, x + size, y + size, FRAME);
        graphics.fill(mapX - 2, mapY - 2, mapX + mapSize + 2, mapY + mapSize + 2, 0xD228353E);
        graphics.fill(mapX, mapY, mapX + mapSize, mapY + mapSize, INNER);

        for (int row = 0; row < GRID_SIZE; row++) {
            int z = centerZ + (row - GRID_SIZE / 2) * SAMPLE_STEP;
            int y0 = mapY + row * mapSize / GRID_SIZE;
            int y1 = mapY + (row + 1) * mapSize / GRID_SIZE + 1;
            for (int column = 0; column < GRID_SIZE; column++) {
                int worldX = centerX + (column - GRID_SIZE / 2) * SAMPLE_STEP;
                int predictedColor = predicted.colorAt(worldX, z, placeholder);
                predictedColor = (biomeOpacity << 24) | (predictedColor & 0x00FFFFFF);
                int color = terrainColors.getOrDefault(pack(worldX, z), predictedColor);
                int x0 = mapX + column * mapSize / GRID_SIZE;
                int x1 = mapX + (column + 1) * mapSize / GRID_SIZE + 1;
                graphics.fill(x0, y0, Math.min(mapX + mapSize, x1),
                    Math.min(mapY + mapSize, y1), color);
            }
        }

        drawStructureMarkers(graphics, predicted, mapX, mapY, mapSize, centerX, centerZ, dimension);
        drawWaypointMarkers(graphics, mapX, mapY, mapSize, centerX, centerZ, dimension);
        graphics.outline(x, y, size, size, BORDER);
        graphics.outline(mapX - 2, mapY - 2, mapSize + 4, mapSize + 4, 0x88DCF2F5);
        drawPlayer(graphics, minecraft, mapX + mapSize / 2, mapY + mapSize / 2);
        graphics.centeredText(minecraft.font, "N", mapX + mapSize / 2, mapY + 3, 0xFFF3F7F8);

        String coords = (int) Math.floor(minecraft.player.getX()) + ", "
            + (int) Math.floor(minecraft.player.getY()) + ", "
            + (int) Math.floor(minecraft.player.getZ());
        int labelWidth = Math.min(size, minecraft.font.width(coords) + 8);
        graphics.fill(x, y + size + 3, x + labelWidth, y + size + 16, 0xC8070A0E);
        graphics.text(minecraft.font, coords, x + 4, y + size + 5, 0xFFE6EBEE, true);
    }

    private void drawStructureMarkers(GuiGraphicsExtractor graphics, PredictionSnapshot predicted,
                                      int mapX, int mapY, int mapSize,
                                      int centerX, int centerZ, int dimension) {
        if (!config.booleanValue("structure-layer", true) || predicted == PredictionSnapshot.EMPTY) return;
        Set<Long> waypointPositions = Set.of();
        if (config.booleanValue("dim-waypoint-structures", true)) {
            waypointPositions = new HashSet<>();
            for (StandaloneWaypoint waypoint : config.waypoints(predicted.key.seed, dimension)) {
                waypointPositions.add(pack(waypoint.x(), waypoint.z()));
            }
        }
        int radius = Math.max(2, (int) Math.round(
            2.0 * config.doubleValue("structure-marker-scale", 1.0, 0.5, 3.0)));
        for (GeneratedStructure structure : predicted.structures) {
            if (!config.structureVisible(structure.type.name())) continue;
            int markerX = markerCoordinate(mapX, mapSize, structure.x - centerX);
            int markerY = markerCoordinate(mapY, mapSize, structure.z - centerZ);
            if (!insideMap(markerX, markerY, mapX, mapY, mapSize)) continue;
            int color = structureColor(structure.type);
            if (waypointPositions.contains(pack(structure.x, structure.z))
                || config.completed(dimension, structure.type.name(), structure.x, structure.z)) {
                color = dimColor(color);
            }
            graphics.fill(markerX - radius - 1, markerY - radius - 1,
                markerX + radius + 2, markerY + radius + 2, 0xE0101417);
            graphics.fill(markerX - radius, markerY - radius,
                markerX + radius + 1, markerY + radius + 1, color);
        }
    }

    private void drawWaypointMarkers(GuiGraphicsExtractor graphics,
                                     int mapX, int mapY, int mapSize,
                                     int centerX, int centerZ, int dimension) {
        if (!config.booleanValue("waypoint-layer", true) || !config.hasSeed()) return;
        int radius = Math.max(3, (int) Math.round(
            3.0 * config.doubleValue("waypoint-marker-scale", 1.0, 0.5, 3.0)));
        for (StandaloneWaypoint waypoint : config.waypoints(config.seed(), dimension)) {
            int markerX = markerCoordinate(mapX, mapSize, waypoint.x() - centerX);
            int markerY = markerCoordinate(mapY, mapSize, waypoint.z() - centerZ);
            if (!insideMap(markerX, markerY, mapX, mapY, mapSize)) continue;
            graphics.fill(markerX - 1, markerY - radius - 1,
                markerX + 2, markerY + radius + 2, 0xF0101619);
            graphics.fill(markerX - radius - 1, markerY - 1,
                markerX + radius + 2, markerY + 2, 0xF0101619);
            graphics.fill(markerX, markerY - radius, markerX + 1,
                markerY + radius + 1, 0xFF63F3CE);
            graphics.fill(markerX - radius, markerY, markerX + radius + 1,
                markerY + 1, 0xFF63F3CE);
        }
    }

    private void requestPrediction(int centerX, int centerZ, int dimension) {
        if (!config.hasSeed()) {
            invalidatePrediction();
            return;
        }
        int areaX = Math.floorDiv(centerX, AREA_STRIDE_BLOCKS) * AREA_STRIDE_BLOCKS
            + AREA_STRIDE_BLOCKS / 2;
        int areaZ = Math.floorDiv(centerZ, AREA_STRIDE_BLOCKS) * AREA_STRIDE_BLOCKS
            + AREA_STRIDE_BLOCKS / 2;
        PredictionKey key = new PredictionKey(config.seed(), config.predictionVersion(),
            dimension, areaX, areaZ);
        if (key.equals(activePredictionKey)) return;
        if (key.equals(failedPredictionKey) && System.nanoTime() < retryPredictionAfterNanos) return;

        long request = predictionRequests.incrementAndGet();
        cancelPredictionFuture();
        activePredictionKey = key;
        pendingPredictionKey = null;
        if (!samePredictionContext(key, prediction.key)) prediction = PredictionSnapshot.EMPTY;
        PredictionSnapshot cached;
        synchronized (predictionCache) {
            cached = predictionCache.get(key);
        }
        if (cached != null) {
            prediction = cached;
            return;
        }

        pendingPredictionKey = key;
        predictionFuture = PREDICTOR.submit(() -> generatePrediction(request, key));
    }

    private void generatePrediction(long request, PredictionKey key) {
        try {
            int centerSampleX = alignedSample(key.areaX);
            int centerSampleZ = alignedSample(key.areaZ);
            int minimumX = centerSampleX - PREDICTION_RADIUS_CELLS * SAMPLE_STEP;
            int minimumZ = centerSampleZ - PREDICTION_RADIUS_CELLS * SAMPLE_STEP;
            int[] colors = new int[PREDICTION_GRID_SIZE * PREDICTION_GRID_SIZE];
            for (int row = 0; row < PREDICTION_GRID_SIZE; row++) {
                if (!isCurrent(request, key)) return;
                int z = minimumZ + row * SAMPLE_STEP;
                for (int column = 0; column < PREDICTION_GRID_SIZE; column++) {
                    int x = minimumX + column * SAMPLE_STEP;
                    colors[row * PREDICTION_GRID_SIZE + column] = predictedBiomeColor(
                        key.seed, key.dimension, x, z);
                }
            }

            int minimumChunkX = Math.floorDiv(minimumX, 16) - 1;
            int minimumChunkZ = Math.floorDiv(minimumZ, 16) - 1;
            int maximumX = minimumX + (PREDICTION_GRID_SIZE - 1) * SAMPLE_STEP;
            int maximumZ = minimumZ + (PREDICTION_GRID_SIZE - 1) * SAMPLE_STEP;
            int maximumChunkX = Math.floorDiv(maximumX, 16) + 1;
            int maximumChunkZ = Math.floorDiv(maximumZ, 16) + 1;
            List<GeneratedStructure> structures = StandalonePredictionCache.predictDimension(
                new PredictionContext(key.seed, key.version, key.dimension),
                minimumChunkX, minimumChunkZ, maximumChunkX, maximumChunkZ, true, true,
                () -> !isCurrent(request, key));
            if (!isCurrent(request, key)) return;
            PredictionSnapshot completed = new PredictionSnapshot(key, minimumX, minimumZ,
                colors, List.copyOf(structures));
            synchronized (predictionCache) {
                predictionCache.put(key, completed);
            }
            if (isCurrent(request, key)) {
                failedPredictionKey = null;
                retryPredictionAfterNanos = 0L;
                prediction = completed;
            }
        } catch (RuntimeException | LinkageError failure) {
            if (isCurrent(request, key)) {
                reportPredictionFailure(failure);
                pendingPredictionKey = null;
                activePredictionKey = null;
                predictionFuture = null;
                failedPredictionKey = key;
                retryPredictionAfterNanos = System.nanoTime() + DIAGNOSTIC_INTERVAL_NANOS;
                predictionRequests.incrementAndGet();
            }
        } finally {
            if (isCurrent(request, key)) {
                predictionFuture = null;
                pendingPredictionKey = null;
            }
        }
    }

    private PredictionSnapshot currentPrediction() {
        PredictionSnapshot current = prediction;
        PredictionKey expected = new PredictionKey(config.seed(), config.predictionVersion(), cachedDimension, 0, 0);
        return config.hasSeed() && samePredictionContext(expected, current.key)
            ? current : PredictionSnapshot.EMPTY;
    }

    private boolean isCurrent(long request, PredictionKey key) {
        return predictionRequests.get() == request && key.equals(activePredictionKey)
            && key.equals(pendingPredictionKey);
    }

    private void invalidatePrediction() {
        if (activePredictionKey == null && prediction == PredictionSnapshot.EMPTY
            && predictionFuture == null && failedPredictionKey == null) return;
        predictionRequests.incrementAndGet();
        cancelPredictionFuture();
        activePredictionKey = null;
        pendingPredictionKey = null;
        failedPredictionKey = null;
        retryPredictionAfterNanos = 0L;
        prediction = PredictionSnapshot.EMPTY;
    }

    private void cancelPredictionFuture() {
        Future<?> current = predictionFuture;
        predictionFuture = null;
        // Interruption during a vanilla template read closes the shared jar's
        // ZipFS channel and poisons later predictions. Cancel cooperatively.
        if (current != null) current.cancel(false);
    }

    private void reportPredictionFailure(Throwable failure) {
        long now = System.nanoTime();
        synchronized (this) {
            if (now - lastDiagnosticNanos < DIAGNOSTIC_INTERVAL_NANOS) {
                suppressedDiagnostics++;
                return;
            }
            String message = failure.getMessage();
            if (message != null && message.length() > 160) message = message.substring(0, 160) + "...";
            System.err.println("[Seed Explorer] Minimap prediction failed: "
                + failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message)
                + (suppressedDiagnostics == 0 ? ""
                    : " (" + suppressedDiagnostics + " similar diagnostics suppressed)"));
            suppressedDiagnostics = 0;
            lastDiagnosticNanos = now;
        }
    }

    private void drawPlayer(GuiGraphicsExtractor graphics, Minecraft minecraft, int centerX, int centerY) {
        graphics.fill(centerX - 3, centerY - 3, centerX + 4, centerY + 4, 0xE610141A);
        graphics.outline(centerX - 3, centerY - 3, 7, 7, 0xFFF5F7F8);
        graphics.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, 0xFFF02B32);

        double yaw = Math.toRadians(minecraft.player.getYRot());
        int directionX = (int) Math.round(-Math.sin(yaw) * 5.0);
        int directionY = (int) Math.round(Math.cos(yaw) * 5.0);
        graphics.fill(centerX + directionX - 1, centerY + directionY - 1,
            centerX + directionX + 2, centerY + directionY + 2, 0xFFF02B32);
    }

    private void sampleLoadedTerrain(Minecraft minecraft, int centerX, int centerZ, int dimension) {
        if (samplingCenterX != centerX || samplingCenterZ != centerZ) {
            samplingCenterX = centerX;
            samplingCenterZ = centerZ;
            sampleCursor = 0;
            retryFrames = 0;
        } else if (sampleCursor >= SAMPLE_ORDER.length) {
            if (++retryFrames < 30) return;
            retryFrames = 0;
            sampleCursor = 0;
        }

        long deadline = System.nanoTime() + SAMPLE_BUDGET_NANOS;
        int sampled = 0;
        int examined = 0;
        while (sampleCursor < SAMPLE_ORDER.length
            && sampled < MAX_NEW_SAMPLES_PER_FRAME
            && examined < MAX_NEW_SAMPLES_PER_FRAME * 3
            && System.nanoTime() < deadline) {
            int cell = SAMPLE_ORDER[sampleCursor++];
            examined++;
            int column = cell & 0xFFFF;
            int row = cell >>> 16;
            int worldX = centerX + (column - GRID_SIZE / 2) * SAMPLE_STEP;
            int worldZ = centerZ + (row - GRID_SIZE / 2) * SAMPLE_STEP;
            long key = pack(worldX, worldZ);
            if (terrainColors.containsKey(key)) continue;
            int color = terrainColor(minecraft, worldX, worldZ, dimension);
            if (color == UNSAMPLED) continue;
            terrainColors.put(key, color);
            sampled++;
        }
    }

    private int terrainColor(Minecraft minecraft, int x, int z, int dimension) {
        Level level = minecraft.level;
        LevelChunk chunk = level.getChunkSource().getChunkNow(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        if (chunk == null) return UNSAMPLED;

        int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE,
            Math.floorMod(x, 16), Math.floorMod(z, 16)) - 1;
        int playerY = minecraft.player.blockPosition().getY();
        int y = level.dimension() == Level.NETHER || playerY < 45 && surfaceY - playerY > 18
            ? sampleNearPlayer(level, x, z, playerY) : surfaceY;
        if (y < level.getMinY() || y >= level.getMaxY()) return placeholder(dimension);

        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = chunk.getBlockState(pos);
        if (state.isAir()) return placeholder(dimension);
        int heightShade = clamp(y - 64, -24, 30);
        if (state.is(Blocks.WATER)) return shade(0xFF2060B2, heightShade / 3);
        if (state.is(Blocks.LAVA)) return 0xFFF05E20;
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MOSS_BLOCK)) {
            int rgb = level.getBiome(pos).value().getGrassColor(x, z);
            return shade(0xFF000000 | rgb, heightShade / 3);
        }
        if (state.is(BlockTags.LEAVES)) {
            int rgb = level.getBiome(pos).value().getFoliageColor();
            return shade(0xFF000000 | rgb, heightShade / 4);
        }

        MapColor color = state.getMapColor(level, pos);
        if (color == MapColor.NONE) return placeholder(dimension);
        return shade(color.calculateARGBColor(MapColor.Brightness.NORMAL), heightShade / 2);
    }

    private static int predictedBiomeColor(long seed, int dimension, int x, int z) {
        int abgr = WorldgenEngine.getBiome(seed, dimension, x, 64, z).mapColorAbgr();
        int argb = (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >>> 16) & 0xFF);
        int alpha = dimension == 1 ? 230 : 238;
        return alpha << 24 | (argb & 0x00FFFFFF);
    }

    private static int markerCoordinate(int origin, int size, int deltaBlocks) {
        double cellsFromCenter = deltaBlocks / (double) SAMPLE_STEP;
        return origin + size / 2 + (int) Math.round(cellsFromCenter * size / GRID_SIZE);
    }

    private static boolean insideMap(int x, int y, int mapX, int mapY, int mapSize) {
        return x >= mapX && x < mapX + mapSize && y >= mapY && y < mapY + mapSize;
    }

    private static int structureColor(StructureType type) {
        return switch (type.dimension) {
            case -1 -> 0xFFFF8B57;
            case 1 -> 0xFFC9A5FF;
            default -> type == StructureType.ABANDONED_CAMP ? 0xFF46E0BA : 0xFFFFD25A;
        };
    }

    private static int dimColor(int color) {
        int red = ((color >>> 16) & 0xFF) * 2 / 5;
        int green = ((color >>> 8) & 0xFF) * 2 / 5;
        int blue = (color & 0xFF) * 2 / 5;
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    static PredictionKey predictionKey(long seed, String version, int dimension,
                                        int centerX, int centerZ) {
        int areaX = Math.floorDiv(alignedSample(centerX), AREA_STRIDE_BLOCKS) * AREA_STRIDE_BLOCKS
            + AREA_STRIDE_BLOCKS / 2;
        int areaZ = Math.floorDiv(alignedSample(centerZ), AREA_STRIDE_BLOCKS) * AREA_STRIDE_BLOCKS
            + AREA_STRIDE_BLOCKS / 2;
        return new PredictionKey(seed, version, dimension, areaX, areaZ);
    }

    static boolean predictionKeyChanges(long seed, String version, int dimension,
                                        int firstX, int firstZ, int secondX, int secondZ) {
        return !predictionKey(seed, version, dimension, firstX, firstZ)
            .equals(predictionKey(seed, version, dimension, secondX, secondZ));
    }

    static boolean isPredictionAreaBounded() {
        return PREDICTION_GRID_SIZE == 37
            && PREDICTION_GRID_SIZE * PREDICTION_GRID_SIZE <= 1_500
            && MAX_PREDICTION_AREAS <= 8 && MAX_PREDICTION_QUEUE == 1;
    }

    static boolean predictionMarginCoversHud() {
        int hudRadius = GRID_SIZE / 2 * SAMPLE_STEP;
        int maximumCenterDrift = AREA_STRIDE_BLOCKS / 2 + SAMPLE_STEP;
        return PREDICTION_RADIUS_CELLS * SAMPLE_STEP >= hudRadius + maximumCenterDrift;
    }

    static boolean samePredictionContext(PredictionKey first, PredictionKey second) {
        return first != null && second != null && first.seed == second.seed
            && first.dimension == second.dimension && first.version.equals(second.version);
    }

    record PredictionKey(long seed, String version, int dimension, int areaX, int areaZ) {
    }

    private record PredictionSnapshot(PredictionKey key, int minimumX, int minimumZ,
                                      int[] colors, List<GeneratedStructure> structures) {
        private static final PredictionSnapshot EMPTY =
            new PredictionSnapshot(null, 0, 0, new int[0], List.of());

        private int colorAt(int x, int z, int fallback) {
            if (key == null) return fallback;
            int column = (int) Math.round((x - minimumX) / (double) SAMPLE_STEP);
            int row = (int) Math.round((z - minimumZ) / (double) SAMPLE_STEP);
            if (column < 0 || column >= PREDICTION_GRID_SIZE
                || row < 0 || row >= PREDICTION_GRID_SIZE) return fallback;
            return colors[row * PREDICTION_GRID_SIZE + column];
        }
    }

    private int sampleNearPlayer(Level level, int x, int z, int playerY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, playerY, z);
        int max = Math.min(level.getMaxY() - 1, playerY + 8);
        int min = Math.max(level.getMinY(), playerY - 8);
        for (int y = max; y >= min; y--) {
            pos.setY(y);
            if (!level.getBlockState(pos).isAir()) return y;
        }
        return playerY;
    }

    private void ensureCacheContext(Level level, int dimension) {
        if (cachedLevel == level && cachedDimension == dimension) return;
        terrainColors.clear();
        cachedLevel = level;
        cachedDimension = dimension;
        samplingCenterX = Integer.MIN_VALUE;
        samplingCenterZ = Integer.MIN_VALUE;
        sampleCursor = 0;
    }

    private static int[] createSampleOrder() {
        int[] order = new int[GRID_SIZE * GRID_SIZE];
        int center = GRID_SIZE / 2;
        int index = 0;
        for (int ring = 0; ring <= center; ring++) {
            int min = Math.max(0, center - ring);
            int max = Math.min(GRID_SIZE - 1, center + ring);
            for (int row = min; row <= max; row++) {
                for (int column = min; column <= max; column++) {
                    if (Math.max(Math.abs(column - center), Math.abs(row - center)) != ring) continue;
                    order[index++] = row << 16 | column;
                }
            }
        }
        if (index != order.length) throw new IllegalStateException("Incomplete minimap sample order");
        return order;
    }

    static boolean sampleOrderIsComplete() {
        boolean[] seen = new boolean[GRID_SIZE * GRID_SIZE];
        for (int cell : SAMPLE_ORDER) {
            int column = cell & 0xFFFF;
            int row = cell >>> 16;
            int index = row * GRID_SIZE + column;
            if (row >= GRID_SIZE || column >= GRID_SIZE || seen[index]) return false;
            seen[index] = true;
        }
        return true;
    }

    private static int alignedSample(int coordinate) {
        return Math.floorDiv(coordinate, SAMPLE_STEP) * SAMPLE_STEP + SAMPLE_STEP / 2;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int dimensionId(Level level) {
        if (level.dimension() == Level.NETHER) return -1;
        if (level.dimension() == Level.END) return 1;
        return 0;
    }

    private static int placeholder(int dimension) {
        return switch (dimension) {
            case -1 -> NETHER_PLACEHOLDER;
            case 1 -> END_PLACEHOLDER;
            default -> OVERWORLD_PLACEHOLDER;
        };
    }

    private static int shade(int argb, int amount) {
        int alpha = argb >>> 24;
        int red = clamp(((argb >>> 16) & 0xFF) + amount, 0, 255);
        int green = clamp(((argb >>> 8) & 0xFF) + amount, 0, 255);
        int blue = clamp((argb & 0xFF) + amount, 0, 255);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
