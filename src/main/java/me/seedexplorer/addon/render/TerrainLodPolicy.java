package me.seedexplorer.addon.render;

/** Pure distance policy shared by streaming terrain generation and probes. */
public final class TerrainLodPolicy {
    /** 10,025 chunks across, centered on the stream anchor. */
    public static final int FULL_DETAIL_CHUNKS = 5_012;
    /** The next 10,000 chunks use an 8x8 surface sampling footprint. */
    public static final int MID_DETAIL_CHUNKS = FULL_DETAIL_CHUNKS + 10_000;
    /** A final 10,000-chunk band is retained for future far-region tiles. */
    public static final int HORIZON_CHUNKS = MID_DETAIL_CHUNKS + 10_000;
    public static final int LOAD_PADDING_CHUNKS = 1;
    public static final int MID_SAMPLE_STEP = 8;
    public static final int COARSE_SAMPLE_STEP = 16;
    public static final int EXACT_DISPLAY_MAX_STEP = 8;
    public static final int EXACT_FALLBACK_TOP_VERTICES = 16;
    /** Uniform one-block surface window around the camera (32 chunks each way). */
    public static final double EXACT_STEP_1_DISTANCE_BLOCKS = 512.0;
    public static final double EXACT_STEP_2_DISTANCE_BLOCKS = 1_024.0;
    public static final double EXACT_STEP_4_DISTANCE_BLOCKS = 4_096.0;
    public static final double LOD_HYSTERESIS_FRACTION = 0.10;
    /** Stable per-chunk threshold variation turns a hard LOD ring into a broad handoff. */
    public static final double LOD_TRANSITION_VARIATION = 0.16;
    public static final int UNINITIALIZED_ANCHOR = Integer.MIN_VALUE;

    private TerrainLodPolicy() {
    }

    public static int sampleStep(int chunkDistance) {
        if (chunkDistance <= FULL_DETAIL_CHUNKS) return 1;
        if (chunkDistance <= MID_DETAIL_CHUNKS) return MID_SAMPLE_STEP;
        return COARSE_SAMPLE_STEP;
    }

    /** Fast source quality for the interactive 3D viewport; bulk loading is separate. */
    public static int interactiveSampleStep(int chunkDistance) {
        int distance = Math.max(0, chunkDistance);
        if (distance <= 4) return 1;
        if (distance <= 16) return 4;
        if (distance <= 32) return 8;
        return COARSE_SAMPLE_STEP;
    }

    /** The leading coverage lane always uses one sample per chunk. */
    public static int coverageSampleStep(int chunkDistance) {
        return COARSE_SAMPLE_STEP;
    }

    /** Source accuracy is fixed to the saved stream origin, never the moving camera. */
    public static int sourceSampleStep(int anchorX, int anchorZ, int chunkX, int chunkZ) {
        return sampleStep(retentionRing(anchorX, anchorZ, chunkX, chunkZ));
    }

    /** Minimum non-empty surface geometry retained for one visible chunk. */
    public static int minimumSurfaceVertexReserve(int requestedSourceStep) {
        return requestedSourceStep == 1 ? EXACT_FALLBACK_TOP_VERTICES : 4;
    }

    /** Camera movement never relocates an initialized persistent stream origin. */
    public static int persistentStreamAnchor(int existingAnchor, int cameraChunk, int regionSize) {
        if (existingAnchor != UNINITIALIZED_ANCHOR) return existingAnchor;
        int size = Math.max(1, regionSize);
        return Math.floorDiv(cameraChunk, size) * size + size / 2;
    }

    /** Mesh retention is byte-based; an arbitrary chunk count must never punch holes. */
    public static boolean bakedCacheRequiresEviction(int bakedChunks, long bakedBytes,
                                                     long maximumBytes) {
        return bakedBytes > Math.max(0L, maximumBytes);
    }

    /** Stable square-ring order used for retention independently of the moving camera. */
    public static int retentionRing(int anchorX, int anchorZ, int chunkX, int chunkZ) {
        return Math.max(Math.abs(chunkX - anchorX), Math.abs(chunkZ - anchorZ));
    }

    /** Stable accumulated square plus a camera-local spectator window. */
    public static boolean visibleInAnchoredOrCameraWindow(
        int anchorX, int anchorZ, int cameraX, int cameraZ, int chunkX, int chunkZ,
        int anchorRadius, int cameraRadius) {
        return retentionRing(anchorX, anchorZ, chunkX, chunkZ) <= Math.max(0, anchorRadius)
            || retentionRing(cameraX, cameraZ, chunkX, chunkZ) <= Math.max(0, cameraRadius);
    }

    /** Next geometry LOD used when the finer mesh would exceed the frame budget. */
    public static int nextCoarserStep(int step) {
        if (step <= 1) return 2;
        if (step <= 2) return 4;
        if (step <= 4) return 8;
        return COARSE_SAMPLE_STEP;
    }

    /** Underground requests must run their shell pass at every surface display LOD. */
    public static boolean shouldBakeUndergroundGeometry(boolean requested, int dimension,
                                                        int surfaceStep) {
        return requested && dimension != 1;
    }

    /** Pre-bake the compact cave window with nearby exact surface chunks. */
    public static boolean shouldPrebakeUndergroundGeometry(
        int dimension, int chunkDistance, int caveRadius, boolean exactSurface
    ) {
        return exactSurface && dimension != 1
            && chunkDistance >= 0 && chunkDistance <= Math.max(0, caveRadius);
    }

    /** Keep the active cave window resident when the broad surface cache is compacted. */
    public static boolean protectsPrebakedUndergroundGeometry(
        int cameraX, int cameraZ, int chunkX, int chunkZ, int caveRadius,
        boolean cavesIncluded
    ) {
        return cavesIncluded && cameraX != UNINITIALIZED_ANCHOR
            && cameraZ != UNINITIALIZED_ANCHOR
            && retentionRing(cameraX, cameraZ, chunkX, chunkZ) <= Math.max(0, caveRadius);
    }

    /** A surface-only bake cannot change the geometry shown by an underground pass. */
    public static boolean bakeAffectsActiveView(boolean undergroundView,
                                                boolean cavesIncluded) {
        return !undergroundView || cavesIncluded;
    }

    /**
     * Continuous generation may revise a region while its GPU upload is being
     * packed. Display that same-view result and refine it on the next upload
     * instead of leaving the region black until generation becomes idle.
     */
    public static boolean admitProgressiveGpuBatch(boolean sameViewLifecycle,
                                                   boolean stillVisible,
                                                   boolean revisionMatches) {
        return sameViewLifecycle && stillVisible;
    }

    /** A late packed result may refine an older display, but must never replace a newer one. */
    public static boolean progressiveGpuBatchDoesNotRegress(long displayedRevision,
                                                             long candidateRevision) {
        return displayedRevision < 0L || candidateRevision >= displayedRevision;
    }

    public static boolean insideActiveWindow(int dx, int dz) {
        int radius = HORIZON_CHUNKS + LOAD_PADDING_CHUNKS;
        return Math.abs(dx) <= radius && Math.abs(dz) <= radius;
    }

    public static boolean insideExactSquare(int dx, int dz) {
        return Math.max(Math.abs(dx), Math.abs(dz)) <= FULL_DETAIL_CHUNKS;
    }

    public static long squareChunkCount(int radius) {
        long width = Math.max(0L, (long) radius) * 2L + 1L;
        return width * width;
    }

    /** Reserves one top quad per visible chunk before detailed geometry is admitted. */
    public static int detailedVertexBudget(int visibleChunks, int maximumVertices,
                                           int coarseSkirtReserve) {
        long coarseTops = Math.max(0L, (long) visibleChunks) * 4L;
        long reserved = Math.min(Math.max(0, maximumVertices), coarseTops)
            + Math.max(0, coarseSkirtReserve);
        return (int) Math.max(0L, (long) maximumVertices - reserved);
    }

    /**
     * Maximum geometry the current visible chunk may consume after reserving one
     * top quad for every chunk that will be visited later in the frame.
     */
    public static int currentChunkVertexBudget(int renderedVertices, int remainingVisibleChunks,
                                               int maximumVertices) {
        long reservedForLater = Math.max(0L, (long) remainingVisibleChunks) * 4L;
        long available = (long) Math.max(0, maximumVertices)
            - Math.max(0, renderedVertices) - reservedForLater;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, available));
    }

    /** Every cached visible surface chunk always retains room for its coarse top. */
    public static boolean coarseTopIsGuaranteed(int renderedVertices, int remainingVisibleChunks,
                                                int maximumVertices) {
        return currentChunkVertexBudget(renderedVertices, remainingVisibleChunks,
            maximumVertices) >= 4;
    }

    /** Converts a missing stored mesh selection into the guaranteed coarse placeholder. */
    public static int displayedStepOrPlaceholder(Integer selectedStep,
                                                 boolean coarsePlaceholderAvailable) {
        if (selectedStep != null) return selectedStep;
        return coarsePlaceholderAvailable ? COARSE_SAMPLE_STEP : -1;
    }

    public static boolean requiresTransitionSkirt(Integer firstDisplayedStep,
                                                  Integer secondDisplayedStep) {
        return firstDisplayedStep != null && secondDisplayedStep != null
            && !firstDisplayedStep.equals(secondDisplayedStep);
    }

    public static boolean shouldRenderHeightTransition(int heightDifference) {
        return heightDifference != 0;
    }

    public static boolean permitsCoarsePlaceholder(int requestedStep) {
        return requestedStep > 1;
    }

    public static int exactDisplayStep(double cameraDistanceBlocks) {
        if (cameraDistanceBlocks <= EXACT_STEP_1_DISTANCE_BLOCKS) return 1;
        if (cameraDistanceBlocks <= EXACT_STEP_2_DISTANCE_BLOCKS) return 2;
        if (cameraDistanceBlocks <= EXACT_STEP_4_DISTANCE_BLOCKS) return 4;
        return EXACT_DISPLAY_MAX_STEP;
    }

    /**
     * Keeps an existing display LOD until the camera moves decisively through a
     * threshold. This prevents adjacent frames from rebuilding the same GPU batch
     * while the camera hovers near a 1/2/4/8 boundary.
     */
    public static int exactDisplayStepWithHysteresis(Integer previousStep,
                                                     double cameraDistanceBlocks) {
        int requested = exactDisplayStep(cameraDistanceBlocks);
        if (previousStep == null || previousStep < 1 || previousStep > EXACT_DISPLAY_MAX_STEP) {
            return requested;
        }
        double upperScale = 1.0 + LOD_HYSTERESIS_FRACTION;
        return switch (previousStep) {
            case 1 -> cameraDistanceBlocks <= EXACT_STEP_1_DISTANCE_BLOCKS * upperScale
                ? 1 : requested;
            case 2 -> cameraDistanceBlocks <= EXACT_STEP_1_DISTANCE_BLOCKS
                ? requested
                : cameraDistanceBlocks <= EXACT_STEP_2_DISTANCE_BLOCKS * upperScale ? 2 : requested;
            case 4 -> cameraDistanceBlocks <= EXACT_STEP_2_DISTANCE_BLOCKS
                ? requested
                : cameraDistanceBlocks <= EXACT_STEP_4_DISTANCE_BLOCKS * upperScale ? 4 : requested;
            default -> cameraDistanceBlocks <= EXACT_STEP_4_DISTANCE_BLOCKS
                ? requested : EXACT_DISPLAY_MAX_STEP;
        };
    }

    public static int exactDisplayStepWithHysteresis(Integer previousStep,
                                                     double cameraDistanceBlocks,
                                                     int chunkX, int chunkZ) {
        return exactDisplayStepWithHysteresis(previousStep,
            cameraDistanceBlocks / lodThresholdScale(chunkX, chunkZ));
    }

    /** Deterministic noise is stable while moving and avoids a visible geometric ring. */
    public static double lodThresholdScale(int chunkX, int chunkZ) {
        long hash = (long) chunkX * 0x9E3779B97F4A7C15L
            ^ (long) chunkZ * 0xC2B2AE3D27D4EB4FL;
        hash ^= hash >>> 30;
        hash *= 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 27;
        hash *= 0x94D049BB133111EBL;
        hash ^= hash >>> 31;
        double unit = (hash >>> 11) * 0x1.0p-53;
        return 1.0 + (unit * 2.0 - 1.0) * LOD_TRANSITION_VARIATION;
    }

    public static int tiledSurfaceQuadCount(int width, int depth, int repeatSpan) {
        int span = Math.max(1, repeatSpan);
        int x = Math.max(1, (Math.max(1, width) + span - 1) / span);
        int z = Math.max(1, (Math.max(1, depth) + span - 1) / span);
        return x * z;
    }

    /** True only when camera movement changes a batch's requested LOD signature. */
    public static boolean batchLodChanged(long previousSignature, long requestedSignature) {
        return previousSignature != requestedSignature;
    }

    /** Allows one oversized upload to make progress without admitting a second one. */
    public static boolean admitGpuUpload(long uploadedBytes, long candidateBytes,
                                         long frameBudgetBytes) {
        if (candidateBytes <= 0L) return false;
        if (uploadedBytes == 0L) return true;
        return uploadedBytes + candidateBytes <= Math.max(0L, frameBudgetBytes);
    }

    /** Empty packed batches have no GPU work and must be consumed, not queued forever. */
    public static boolean gpuUploadHasBuffers(long packedBytes) {
        return packedBytes > 0L;
    }

    /** Expands a first-person map horizon with altitude while respecting RAM limits. */
    public static int flightVisibleRadius(double cameraDistanceBlocks, double eyeY,
                                          int minimumRadius, int maximumRadius) {
        int minimum = Math.max(0, minimumRadius);
        int maximum = Math.max(minimum, maximumRadius);
        double altitude = Math.max(0.0, eyeY - 64.0);
        double horizonBlocks = Math.max(Math.max(0.0, cameraDistanceBlocks), altitude * 2.0);
        int requested = (int) Math.ceil(horizonBlocks / 12.0);
        return Math.max(minimum, Math.min(maximum, requested));
    }

    /** Uses spare frame capacity for exposed coarse sides without starving detail. */
    public static int coarseSkirtVertexBudget(int visibleChunks, int maximumVertices,
                                              int minimumDetailedVertices) {
        long coarseTops = Math.max(0L, (long) visibleChunks) * 4L;
        long available = (long) maximumVertices - Math.max(0, minimumDetailedVertices) - coarseTops;
        long requested = Math.max(0L, (long) visibleChunks) * 16L;
        return (int) Math.max(0L, Math.min(requested, available));
    }

    /**
     * Converts measured rendering pressure into a small, stable throttle level.
     * Terrain generation continues at every level; only burst size is reduced.
     */
    public static int performanceThrottleLevel(double frameIntervalMs,
                                               double terrainRenderMs) {
        if (frameIntervalMs >= 40.0 || terrainRenderMs >= 18.0) return 3;
        if (frameIntervalMs >= 28.0 || terrainRenderMs >= 12.0) return 2;
        if (frameIntervalMs >= 21.0 || terrainRenderMs >= 7.0) return 1;
        return 0;
    }

    /** Keeps at least one unit of continuous background progress under load. */
    public static int adaptiveBackgroundBudget(int normalBudget, int throttleLevel,
                                               int minimumBudget) {
        int normal = Math.max(0, normalBudget);
        int minimum = Math.max(0, Math.min(normal, minimumBudget));
        int shift = Math.max(0, Math.min(3, throttleLevel));
        return Math.max(minimum, normal >> shift);
    }
}
