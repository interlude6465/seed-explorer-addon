package me.seedexplorer.addon.render;

import me.seedexplorer.addon.live.LiveChunkSnapshot;
import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.preview.StructurePreviewModel;
import me.seedexplorer.addon.preview.StructurePreviewSimulator;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.waypoints.SeedWaypoint;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import me.seedexplorer.addon.worldgen.GeneratedTerrainHeightmap;
import me.seedexplorer.addon.worldgen.HorizonTerrainSampler;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.data.AtlasIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.LightLayer;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.util.RandomSource;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class Terrain3DRenderer {
    private GuiGraphicsExtractor frameGraphics;
    private RenderTarget scopedRenderTarget;
    private final MeshUniformBuffer meshUniforms = new MeshUniformBuffer();
    private final TerrainOverlayRenderer overlayRenderer = TerrainOverlayRenderers.get();
    private volatile TerrainWorldDataSource worldDataSource = TerrainWorldDataSource.NONE;
    /** Shared renderer/cache used by the module prewarmer and map screens. */
    public static Terrain3DRenderer shared() {
        return SharedHolder.INSTANCE;
    }

    /** Installs optional live-world state. Standalone renderers can retain the no-op source. */
    public void setWorldDataSource(TerrainWorldDataSource worldDataSource) {
        this.worldDataSource = worldDataSource == null ? TerrainWorldDataSource.NONE : worldDataSource;
    }

    // The horizon is deliberately broad, but distant chunks only sample a small
    // number of columns. Nearby terrain is upgraded progressively as the camera moves.
    // Chunks continue to be generated as the camera moves outward, while detail
    // falls back to step-16 beyond the configured quality horizon.
    /** Minimum nearby window; zoom expands it lazily up to the requested LOD horizon. */
    // The old 150-chunk minimum created a 90,601-chunk startup window before the
    // user could inspect nearby terrain. Start local and expand with camera height.
    private static final int RENDER_DIST = 32;
    private static final int MAX_RENDER_DIST = 96;
    /** Only nearby holes receive individual temporary chunk tiles. */
    private static final int MISSING_PLACEHOLDER_RADIUS = 192;
    private static final int MAX_PLACEHOLDER_POSITIONS_EXAMINED = 16_384;
    private static final int CACHE_WARM_RADIUS = RENDER_DIST;
    private static final int INTERACTIVE_CACHE_WARM_RADIUS = 8;
    private static final int MAX_CACHE_WARM_CHUNKS = 250_000;
    private static final int MAX_IN_MEMORY_VISIBLE_RADIUS = 96;
    private static final int WORLD_BORDER_CHUNK_RADIUS = 1_875_000;
    private static final int CHUNK_SIZE = 16;
    private static final int FACE_TOP = 1;
    private static final int FACE_BOTTOM = 1 << 1;
    private static final int FACE_NORTH = 1 << 2;
    private static final int FACE_SOUTH = 1 << 3;
    private static final int FACE_WEST = 1 << 4;
    private static final int FACE_EAST = 1 << 5;
    private static final int COVERAGE_SUBMISSIONS_PER_PULSE = 16;
    private static final int DETAIL_SUBMISSIONS_PER_PULSE = 2;
    private static final int RETRY_SUBMISSIONS_PER_PULSE = 2;
    private static final int MAX_STREAM_POSITIONS_EXAMINED_PER_PULSE = 1024;
    private static final long STREAM_PULSE_NANOS = 10_000_000L;
    private static final long STREAM_STATE_SAVE_NANOS = 5_000_000_000L;
    private static final long TERRAIN_FRAME_SUMMARY_NANOS = 2_000_000_000L;
    private static final int STREAM_ANCHOR_REGION_SIZE = 64;
    private static final int CAMERA_PRIORITY_RADIUS = 32;
    private static final int MAX_IN_FLIGHT_CHUNKS = 32;
    private static final int BULK_TERRAIN_THREADS = Math.max(2,
        Math.min(32, Runtime.getRuntime().availableProcessors() - 1));
    private static final int BULK_MAX_IN_FLIGHT_CHUNKS = Math.max(96,
        Math.min(256, BULK_TERRAIN_THREADS * 8));
    private static final int BULK_COVERAGE_SUBMISSIONS_PER_PULSE = Math.max(48,
        BULK_TERRAIN_THREADS * 4);
    private static final int BULK_DETAIL_SUBMISSIONS_PER_PULSE = Math.max(256,
        BULK_TERRAIN_THREADS * 16);
    private static final int BULK_RETRY_SUBMISSIONS_PER_PULSE = Math.max(24,
        BULK_TERRAIN_THREADS * 2);
    private static final int BULK_EXACT_UPGRADES_PER_PULSE = Math.max(48,
        BULK_TERRAIN_THREADS * 4);
    private static final int BULK_MAX_STREAM_POSITIONS_EXAMINED_PER_PULSE = 65_536;
    /** Keep enough exact coverage queued to saturate workers without abandoning the square frontier. */
    private static final long BULK_COVERAGE_LEAD_POSITIONS = BULK_MAX_IN_FLIGHT_CHUNKS * 4L;
    private static final long BULK_STREAM_PULSE_NANOS = 5_000_000L;
    // Keep the nearby horizon visually continuous while generation
    // catches up. These are four-vertex temporary tiles, so the cap is cheap
    // compared with a detailed bake and still bounded by the frame vertex budget.
    private static final int MAX_MISSING_PLACEHOLDERS_PER_FRAME = 8_192;
    private static final int MAX_CHUNK_GENERATION_RETRIES = 3;
    private static final int MAX_CACHED_CHUNKS = 250_000;
    private static final int CHUNK_CACHE_TRIM_BATCH = 4_096;
    private static final int OBSERVED_REFRESHES_PER_FRAME = 4;
    private static final int TERRAIN_THREADS = 2;
    private static final int MAX_SIDE_FACE_HEIGHT = 32;
    private static final int MAX_TERRAIN_CHUNKS_PER_FRAME = 250_000;
    private static final int MAX_TERRAIN_VERTICES_PER_FRAME = 8_000_000;
    private static final int MIN_DETAILED_TERRAIN_VERTICES_PER_FRAME = 6_500_000;
    /** Stable GPU regions prevent the complete terrain mesh being uploaded every frame. */
    private static final int GPU_BATCH_SIZE_CHUNKS = 16;
    /** Cave shells are much denser than heightfield terrain. Smaller regions keep one
     * completed cave chunk from starving every neighbour in a surface-sized batch. */
    private static final int CAVE_GPU_BATCH_SIZE_CHUNKS = 2;
    /** Underground is a first-person inspection view: keep a complete exact shell
     * around the free camera instead of filling the wider horizon with coarse slabs. */
    private static final int CAVE_RENDER_RADIUS_CHUNKS = 8;
    /**
     * Omit the closest cave shell rather than drawing it translucently. This gives
     * the inspection camera an actually empty viewing space, with solid cave walls
     * remaining beyond it and no faint block outlines.
     */
    private static final int CAVE_CUTAWAY_RADIUS_BLOCKS = 48;
    /** Camera movement inside this cell keeps the previous cave bake drawable. */
    private static final int CAVE_CUTAWAY_BUCKET_BLOCKS = 16;
    /** Focus mode updates its sightline more often so cave shells never hide a structure. */
    private static final int CAVE_FOCUS_CUTAWAY_BUCKET_BLOCKS = 4;
    /** Width of the temporary clear tunnel from the focus camera to the structure. */
    private static final int CAVE_FOCUS_CORRIDOR_RADIUS_BLOCKS = 64;
    private static final int GPU_BATCH_MAX_VERTICES = 2_250_000;
    private static final int GPU_CAMERA_REFRESH_RADIUS_CHUNKS = 80;
    private static final long GPU_BUILD_BUDGET_NANOS = 2_500_000L;
    private static final int GPU_BUILD_MIN_CHUNKS_PER_FRAME = 8;
    private static final int GPU_BUILD_MAX_CHUNKS_PER_FRAME = 96;
    private static final long GPU_UPLOAD_BUDGET_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_PENDING_GPU_UPLOADS = 2;
    /** Avoid a map-open hitch when a large spiral completed while the screen was closed. */
    private static final int MAX_PRESENTATION_REVEALS_PER_FRAME = 8_192;
    private static final int GPU_BATCH_MIN_Y = -128;
    private static final int GPU_BATCH_MAX_Y = 384;
    private static final int COARSE_SKIRT_DISTANCE_CHUNKS = 150;
    private static final int COARSE_SKIRT_DEPTH = 24;
    private static final int PREVIEW_RENDER_RADIUS_BLOCKS = 192;
    private static final int PREVIEW_CACHE_RETAIN_DIST = 16;
    private static final int PREVIEW_SUBMISSIONS_PER_FRAME = 4;
    private static final int MAX_IN_FLIGHT_PREVIEWS = 12;
    /** Always upgrade the immediate spectator neighborhood to true one-block data. */
    private static final int CAMERA_EXACT_RING_CHUNKS = 12;
    /** Vanilla decoration is substantially more expensive than the height field.
     * Stream it independently, but cover the complete one-block camera window. */
    private static final int NATURAL_FEATURE_RADIUS_CHUNKS = CAMERA_EXACT_RING_CHUNKS;
    private static final int NATURAL_FEATURE_SUBMISSIONS_PER_PULSE = 1;
    private static final int MAX_IN_FLIGHT_NATURAL_FEATURES = 2;
    /** Dormant baked-model prototype retained for the later full model-pipeline rollout. */
    private static final int VANILLA_SURFACE_RADIUS_CHUNKS = 2;
    private static final int VANILLA_SURFACE_DEPTH_BLOCKS = 8;
    private static final int MAX_CACHED_PREVIEWS = 64;
    private static final int MAX_STRUCTURE_PREVIEW_BLOCKS_PER_FRAME = 6000;
    private static final int CAVE_MIN_Y = -63;
    private static final int CAVE_MAX_Y = 192;
    /** Per-chunk voxel caps used while baking (bounds memory of a single baked chunk). */
    private static final int MAX_CAVE_VOXELS_PER_CHUNK = 9_000;
    private static final int MAX_OBSERVED_CAVE_VOXELS_PER_CHUNK = 18_000;
    /** Chunk geometry bakes started per frame. Baking runs off the render thread. */
    private static final int BAKES_PER_FRAME = 16;
    private static final int MAX_IN_FLIGHT_BAKES = 64;
    /** Background rebakes are opportunistic; never let a world-scale cache turn
     *  a mode transition into a giant GUI-thread or heap workload. */
    private static final int MAX_PENDING_BACKGROUND_BAKES = 8_192;
    private static final long MAX_BAKED_CACHE_BYTES = 1_024L * 1024L * 1024L;
    private static final long TARGET_BAKED_CACHE_BYTES = 896L * 1024L * 1024L;
    private static final int BAKED_CACHE_TRIM_CANDIDATES = 8_192;
    /** Prevent a broken worldgen/atlas from turning diagnostics into an unbounded log. */
    private static final int MAX_TERRAIN_DIAGNOSTIC_KEYS = 16_384;
    /** Routine queue/bake transitions are useful samples, not failures. */
    private static final int MAX_ROUTINE_TERRAIN_DIAGNOSTICS = 512;
    private static final long VISIBLE_REFRESH_NANOS = 750_000_000L;
    private static final long PLACEHOLDER_REFRESH_NANOS = 1_000_000_000L;
    private static final DateTimeFormatter TERRAIN_DIAGNOSTIC_TIME =
        DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * Lazy holder prevents the renderer constructor from running while the outer
     * class is still initializing its executor-size constants.
     */
    private static final class SharedHolder {
        private static final Terrain3DRenderer INSTANCE = new Terrain3DRenderer();
    }

    private final Set<Thread> terrainWorkerThreads = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor terrainExecutor = new ThreadPoolExecutor(
        TERRAIN_THREADS, BULK_TERRAIN_THREADS, 30L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4_096), runnable -> {
        Thread thread = new Thread(runnable, "Seed Explorer Terrain Generator " + TERRAIN_THREAD_IDS.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        terrainWorkerThreads.add(thread);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private static final AtomicInteger TERRAIN_THREAD_IDS = new AtomicInteger();
    private final ThreadPoolExecutor naturalFeatureExecutor = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), runnable -> {
        Thread thread = new Thread(runnable, "Seed Explorer Vanilla Decoration");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private static final int BAKE_THREADS = Math.max(2,
        Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    private final ThreadPoolExecutor bakeExecutor = new ThreadPoolExecutor(
        BAKE_THREADS, BAKE_THREADS, 30L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(MAX_IN_FLIGHT_BAKES), runnable -> {
            Thread thread = new Thread(runnable,
                "Seed Explorer Terrain Baker " + BAKE_THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private static final AtomicInteger BAKE_THREAD_IDS = new AtomicInteger();
    private static final int PREVIEW_THREADS = 2;
    private final ExecutorService previewExecutor = Executors.newFixedThreadPool(PREVIEW_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "Seed Explorer Structure Preview " + PREVIEW_THREAD_IDS.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final AtomicInteger PREVIEW_THREAD_IDS = new AtomicInteger();
    private final ThreadPoolExecutor diagnosticExecutor = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), runnable -> {
            Thread thread = new Thread(runnable, "Seed Explorer Terrain Diagnostics");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }, new ThreadPoolExecutor.DiscardPolicy());
    private final ThreadLocal<TerrainAccessorSlot> terrainAccessors =
        ThreadLocal.withInitial(TerrainAccessorSlot::new);
    private final ThreadLocal<CarvedTerrainSlot> carvedTerrainAccessors =
        ThreadLocal.withInitial(CarvedTerrainSlot::new);
    private final ThreadLocal<CarvedTerrainSlot> exactSurfaceTerrainAccessors =
        ThreadLocal.withInitial(CarvedTerrainSlot::new);
    private final TerrainDiskCache diskCache = new TerrainDiskCache();
    private final TerrainStreamStateStore streamStateStore = new TerrainStreamStateStore();
    private final ExecutorService cacheLoaderExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Seed Explorer Cache Loader");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** Render-thread-owned persistent terrain buffers, rebuilt a region at a time. */
    private final Map<Long, TerrainGpuBatch> gpuBatches = new java.util.LinkedHashMap<>();
    private final Map<Long, Long> gpuBatchRevisions = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> pendingGpuBatches =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Set<Long> queuedGpuBatches = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<Long> priorityGpuBatches = new ArrayDeque<>();
    private final Set<Long> queuedPriorityGpuBatches = new HashSet<>();
    private final AtomicLong nextGpuBatchRevision = new AtomicLong();
    private volatile long gpuLifecycleGeneration;
    private TerrainGpuBuild activeGpuBuild;
    private final java.util.concurrent.ConcurrentLinkedQueue<PreparedGpuUpload> pendingGpuUploads =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final AtomicInteger gpuUploadPackingJobs = new AtomicInteger();
    private PreparedGpuUpload activeGpuUpload;
    private final ExecutorService gpuUploadPackingExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Seed Explorer GPU Mesh Packer");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private volatile Set<Long> visibleGpuBatchKeys = Set.of();
    private volatile boolean gpuCaveView;
    private boolean gpuTextureDebugView;
    private double gpuRenderOriginX = Double.NaN;
    private double gpuRenderOriginZ = Double.NaN;
    private int gpuCameraChunkX = Integer.MIN_VALUE;
    private int gpuCameraChunkZ = Integer.MIN_VALUE;
    /** Cutaway used by the current cave GPU window, retained to refresh only nearby batches. */
    private CaveCutaway gpuCaveCutaway;
    private int gpuVisibleRadius = -1;
    private int gpuWindowAnchorX = Integer.MIN_VALUE;
    private int gpuWindowAnchorZ = Integer.MIN_VALUE;
    private int performanceThrottle;
    private volatile boolean bulkLoading;
    private boolean bulkRateInitialized;
    private long bulkRateCompletedChunks;
    private long bulkRateSampleNanos;
    private long bulkLastProgressNanos;
    private double bulkChunksPerSecond;
    private double averageFrameIntervalNanos = 16_666_667.0;
    private long lastRenderInvocationNanos;
    private volatile long lastTerrainRenderNanos;
    private volatile int lastGpuBatchesDrawn;
    private volatile int lastGpuBatchesQueued;
    private volatile int lastGpuBatchesCulled;
    private volatile long lastGpuUploadBytes;
    private volatile long lastGpuBuildNanos;
    private volatile long lastGpuUploadNanos;
    private final AtomicLong caveBakesWithGeometry = new AtomicLong();
    private final AtomicLong caveBakesWithoutGeometry = new AtomicLong();
    private final AtomicLong caveVerticesBaked = new AtomicLong();
    private final AtomicLong naturalFeatureSnapshotsResolved = new AtomicLong();
    private final AtomicLong naturalFeatureBlocksResolved = new AtomicLong();
    private final AtomicLong naturalFeatureBakesWithGeometry = new AtomicLong();
    private final AtomicLong naturalFeatureBakesWithoutGeometry = new AtomicLong();
    private final AtomicLong naturalFeatureVerticesBaked = new AtomicLong();
    private final AtomicLong naturalFeatureBudgetSkips = new AtomicLong();
    private final AtomicLong emptyGpuUploadsDiscarded = new AtomicLong();

    private final Map<Long, ChunkData> chunkCache = new ConcurrentHashMap<>();
    /** Pre-baked, ready-to-replay geometry per chunk. Built off the render thread so
     *  each frame only copies vertices into the frame mesh instead of regenerating
     *  (and re-sampling worldgen noise for) the whole visible world. */
    private final Map<Long, BakedChunk> bakedCache = new ConcurrentHashMap<>();
    private final AtomicLong bakedCacheBytes = new AtomicLong();
    private final AtomicLong bakedCacheEvictions = new AtomicLong();
    private final AtomicLong bakedCacheCompactions = new AtomicLong();
    private volatile String lastBakedEvictionReason = "none";
    private final Map<Long, Long> geometryRevisions = new ConcurrentHashMap<>();
    private final AtomicLong nextGeometryRevision = new AtomicLong();
    private final Set<WorkKey> inFlightBakes = ConcurrentHashMap.newKeySet();
    private final Set<WorkKey> inFlightChunks = ConcurrentHashMap.newKeySet();
    private final Set<WorkKey> inFlightNaturalFeatures = ConcurrentHashMap.newKeySet();
    private final TerrainPresentationFrontier presentationFrontier =
        new TerrainPresentationFrontier();
    private final Map<WorkKey, Integer> chunkGenerationFailures = new ConcurrentHashMap<>();
    private final Set<String> terrainDiagnosticKeys = ConcurrentHashMap.newKeySet();
    private final Map<Long, String> terrainFailureReasons = new ConcurrentHashMap<>();
    private final Map<Long, Integer> displayedSurfaceSteps = new ConcurrentHashMap<>();
    private final Set<Long> frameBudgetFallbackChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> staleUpgradeChunks = ConcurrentHashMap.newKeySet();
    private final AtomicInteger routineTerrainDiagnosticCount = new AtomicInteger();
    private final Object terrainDiagnosticLock = new Object();
    private final Map<PreviewKey, StructurePreviewModel> previewCache = new ConcurrentHashMap<>();
    private final Set<PreviewJobKey> inFlightPreviews = ConcurrentHashMap.newKeySet();
    private final Map<BlockState, PreviewTexture> previewTextureCache = new ConcurrentHashMap<>();
    /** Temporary live-atlas sink used for vanilla baked structure previews. */
    private BakingSink vanillaPreviewGeometry;
    // The moving terrain ring is intentionally not called. These fields only
    // support the retained baked-model prototype below; persistent GPU batches
    // are the sole owner of the active surface.
    private long vanillaRingKey = Long.MIN_VALUE;
    private int vanillaRingDimension = Integer.MIN_VALUE;
    private BakedGeometry vanillaRingCache = EMPTY_GEOMETRY;
    private long vanillaRingSeed = Long.MIN_VALUE;
    private long vanillaRingRevision = Long.MIN_VALUE;
    private int vanillaRingYBucket = Integer.MIN_VALUE;
    private int vanillaRingCenterChunkX = Integer.MIN_VALUE;
    private int vanillaRingCenterChunkZ = Integer.MIN_VALUE;
    private boolean vanillaRingCaveView;
    private boolean vanillaRingComplete;
    private RgbaColor previewTopTint = RgbaColor.WHITE;
    private RgbaColor previewSideTint = new RgbaColor(180, 180, 180, 255);
    /** Retry queue only. New work is emitted lazily by the two streaming cursors. */
    private java.util.ArrayDeque<long[]> pendingChunks = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<long[]> cameraCoverageChunks = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<long[]> cameraLodChunks = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<long[]> cameraExactChunks = new java.util.ArrayDeque<>();
    /** Quickly establishes one-sample-per-chunk terrain across the broad map. */
    private final TerrainStreamingCursor coverageCursor = new TerrainStreamingCursor();
    /** Follows coverage and upgrades it to the requested near/mid detail. */
    private final TerrainStreamingCursor detailCursor = new TerrainStreamingCursor();
    private TerrainStreamingCursor.Coordinate pendingDetailCoordinate;
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> pendingBackgroundBakes =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Set<Long> queuedBackgroundBakes = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> pendingExactUpgrades =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Set<Long> queuedExactUpgrades = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> pendingNaturalFeatures =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Set<Long> queuedNaturalFeatures = ConcurrentHashMap.newKeySet();
    private int naturalFeatureQueueCenterX = Integer.MIN_VALUE;
    private int naturalFeatureQueueCenterZ = Integer.MIN_VALUE;
    private long lastStreamPulseNanos;
    private long lastStreamStateSaveNanos;
    private volatile long lastTerrainFrameSummaryNanos;
    private volatile boolean streamStateLoading;
    private volatile long cacheWarmGeneration = Long.MIN_VALUE;
    private volatile int cacheWarmCenterX = Integer.MIN_VALUE;
    private volatile int cacheWarmCenterZ = Integer.MIN_VALUE;
    private volatile int cacheWarmRadius;
    private int streamAnchorChunkX = Integer.MIN_VALUE;
    private int streamAnchorChunkZ = Integer.MIN_VALUE;
    private String streamWorldIdentity = "";
    private final Set<Long> completedStreamRegions = ConcurrentHashMap.newKeySet();
    private final Set<Long> diskRestoredChunks = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> rejectedObservedSnapshots = new ConcurrentHashMap<>();
    private volatile long lastSeed;
    private volatile int lastDimension = Integer.MIN_VALUE;
    private volatile long worldGeneration;
    private volatile String lastTerrainError = "";
    private final AtomicInteger failedChunkGenerations = new AtomicInteger();
    private final AtomicLong chunkRevision = new AtomicLong();
    private volatile int lastCenterChunkX = Integer.MIN_VALUE;
    private volatile int lastCenterChunkZ = Integer.MIN_VALUE;
    private List<VisibleChunk> visibleChunkCache = List.of();
    private long visibleChunkRevision = Long.MIN_VALUE;
    private double visibleCameraDistance = Double.NaN;
    private int visibleAnchorChunkX = Integer.MIN_VALUE;
    private int visibleAnchorChunkZ = Integer.MIN_VALUE;
    private int visibleCameraChunkX = Integer.MIN_VALUE;
    private int visibleCameraChunkZ = Integer.MIN_VALUE;
    private int visibleRenderRadius = -1;
    private long visibleRefreshNanos;
    private List<Long> missingPlaceholderCache = List.of();
    private int missingPlaceholderCenterX = Integer.MIN_VALUE;
    private int missingPlaceholderCenterZ = Integer.MIN_VALUE;
    private long missingPlaceholderRefreshNanos;

    /** Invalidates pending work and releases all cached world and mesh data. */
    public void reset() {
        worldGeneration++;
        pendingChunks.clear();
        clearCameraQueues();
        coverageCursor.reset();
        detailCursor.reset();
        pendingDetailCoordinate = null;
        presentationFrontier.reset();
        pendingBackgroundBakes.clear();
        queuedBackgroundBakes.clear();
        pendingExactUpgrades.clear();
        queuedExactUpgrades.clear();
        pendingNaturalFeatures.clear();
        queuedNaturalFeatures.clear();
        inFlightNaturalFeatures.clear();
        naturalFeatureQueueCenterX = Integer.MIN_VALUE;
        naturalFeatureQueueCenterZ = Integer.MIN_VALUE;
        lastStreamPulseNanos = 0L;
        lastStreamStateSaveNanos = 0L;
        lastTerrainFrameSummaryNanos = 0L;
        streamStateLoading = false;
        cacheWarmGeneration = Long.MIN_VALUE;
        cacheWarmCenterX = Integer.MIN_VALUE;
        cacheWarmCenterZ = Integer.MIN_VALUE;
        cacheWarmRadius = 0;
        streamAnchorChunkX = Integer.MIN_VALUE;
        streamAnchorChunkZ = Integer.MIN_VALUE;
        streamWorldIdentity = "";
        completedStreamRegions.clear();
        diskRestoredChunks.clear();
        rejectedObservedSnapshots.clear();
        synchronized (chunkCache) {
            chunkCache.clear();
        }
        clearBakedCache();
        clearGpuBatches();
        gpuBatchRevisions.clear();
        previewCache.clear();
        previewTextureCache.clear();
        chunkRevision.incrementAndGet();
        visibleChunkCache = List.of();
        visibleChunkRevision = Long.MIN_VALUE;
        visibleCameraDistance = Double.NaN;
        visibleAnchorChunkX = Integer.MIN_VALUE;
        visibleAnchorChunkZ = Integer.MIN_VALUE;
        visibleCameraChunkX = Integer.MIN_VALUE;
        visibleCameraChunkZ = Integer.MIN_VALUE;
        visibleRenderRadius = -1;
        visibleRefreshNanos = 0L;
        missingPlaceholderCache = List.of();
        missingPlaceholderCenterX = Integer.MIN_VALUE;
        missingPlaceholderCenterZ = Integer.MIN_VALUE;
        missingPlaceholderRefreshNanos = 0L;
        lastCenterChunkX = Integer.MIN_VALUE;
        lastCenterChunkZ = Integer.MIN_VALUE;
        lastSeed = 0;
        lastDimension = Integer.MIN_VALUE;
        lastTerrainError = "";
        failedChunkGenerations.set(0);
        chunkGenerationFailures.clear();
        terrainDiagnosticKeys.clear();
        terrainFailureReasons.clear();
        displayedSurfaceSteps.clear();
        frameBudgetFallbackChunks.clear();
        staleUpgradeChunks.clear();
        geometryRevisions.clear();
        nextGeometryRevision.set(0L);
        nextGpuBatchRevision.set(0L);
        performanceThrottle = 0;
        averageFrameIntervalNanos = 16_666_667.0;
        lastRenderInvocationNanos = 0L;
        lastTerrainRenderNanos = 0L;
        lastGpuBatchesDrawn = 0;
        lastGpuBatchesQueued = 0;
        lastGpuBatchesCulled = 0;
        lastGpuUploadBytes = 0L;
        lastGpuBuildNanos = 0L;
        lastGpuUploadNanos = 0L;
        caveBakesWithGeometry.set(0L);
        caveBakesWithoutGeometry.set(0L);
        caveVerticesBaked.set(0L);
        naturalFeatureSnapshotsResolved.set(0L);
        naturalFeatureBlocksResolved.set(0L);
        naturalFeatureBakesWithGeometry.set(0L);
        naturalFeatureBakesWithoutGeometry.set(0L);
        naturalFeatureVerticesBaked.set(0L);
        naturalFeatureBudgetSkips.set(0L);
        emptyGpuUploadsDiscarded.set(0L);
        routineTerrainDiagnosticCount.set(0);
    }

    /**
     * Writes one bounded, structured diagnostic record for a terrain chunk. The
     * key deliberately includes the world generation, reason, coordinates and
     * requested step so a normal retry does not spam the report while distinct
     * failures remain actionable. This method is safe to call from worker threads.
     */
    private void terrainDiagnostic(String reason, long seed, int dimension,
                                   int chunkX, int chunkZ, int requestedStep,
                                   ChunkData chunk, Throwable error, String detail) {
        boolean routine = isRoutineTerrainDiagnostic(reason);
        if (routine && routineTerrainDiagnosticCount.get() >= MAX_ROUTINE_TERRAIN_DIAGNOSTICS) return;
        String key = worldGeneration + "|" + reason + "|" + chunkX + "|" + chunkZ
            + "|" + requestedStep;
        if (!terrainDiagnosticKeys.add(key)) return;
        if (routine
            && routineTerrainDiagnosticCount.incrementAndGet() > MAX_ROUTINE_TERRAIN_DIAGNOSTICS) {
            terrainDiagnosticKeys.remove(key);
            return;
        }
        if (terrainDiagnosticKeys.size() > MAX_TERRAIN_DIAGNOSTIC_KEYS) {
            terrainDiagnosticKeys.remove(key);
            return;
        }
        StringBuilder line = new StringBuilder(256)
            .append('[').append(LocalDateTime.now().format(TERRAIN_DIAGNOSTIC_TIME)).append("] ")
            .append("reason=").append(reason)
            .append(" seed=").append(seed)
            .append(" dimension=").append(dimension)
            .append(" worldGeneration=").append(worldGeneration)
            .append(" chunk=").append(chunkX).append(',').append(chunkZ)
            .append(" requestedStep=").append(requestedStep)
            .append(" generatedStep=").append(chunk == null ? "none" : chunk.generatedStep)
            .append(" accuracy=").append(chunk == null ? "unavailable" : chunk.accuracy)
            .append(" cacheChunks=").append(chunkCache.size())
            .append(" pending=").append(pendingChunks.size())
            .append(" inFlight=").append(inFlightChunks.size())
            .append(" inFlightBakes=").append(inFlightBakes.size());
        if (detail != null && !detail.isBlank()) {
            line.append(" detail=\"").append(escapeTerrainDiagnostic(detail)).append('"');
        }
        if (error != null) {
            line.append(" exception=").append(error.getClass().getName());
            if (error.getMessage() != null && !error.getMessage().isBlank()) {
                line.append(" message=\"").append(escapeTerrainDiagnostic(error.getMessage())).append('"');
            }
        }
        line.append(System.lineSeparator());
        String record = line.toString();
        diagnosticExecutor.execute(() -> {
            synchronized (terrainDiagnosticLock) {
                try {
                    Path path = terrainDiagnosticPath();
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, record, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException | RuntimeException ignored) {
                    // Diagnostics must never affect world generation or rendering.
                }
            }
        });
    }

    private static String escapeTerrainDiagnostic(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace('\r', ' ').replace('\n', ' ');
    }

    private static boolean isRoutineTerrainDiagnostic(String reason) {
        return "missing-cache".equals(reason)
            || "missing-bake".equals(reason)
            || "bake-stale-step".equals(reason)
            || "bake-stale-observation".equals(reason)
            || "bake-retained-stale-geometry".equals(reason)
            || "vertex-budget-fallback".equals(reason);
    }

    private void terrainFrameSummary(long seed, int dimension, int visibleChunks,
                                     int renderedChunks, int renderedVertices,
                                     int missingBakes, int budgetSkippedChunks) {
        long now = System.nanoTime();
        if (now - lastTerrainFrameSummaryNanos < TERRAIN_FRAME_SUMMARY_NANOS) return;
        lastTerrainFrameSummaryNanos = now;
        long generation = worldGeneration;
        int centerX = lastCenterChunkX;
        int centerZ = lastCenterChunkZ;
        int anchorX = streamAnchorChunkX;
        int anchorZ = streamAnchorChunkZ;
        TerrainStreamingCursor.State coverage = coverageCursor.snapshot();
        TerrainStreamingCursor.State detail = detailCursor.snapshot();
        int cachedChunks = chunkCache.size();
        int bakedChunks = bakedCache.size();
        long bakedBytes = bakedCacheBytes.get();
        long bakedEvictions = bakedCacheEvictions.get();
        long bakedCompactions = bakedCacheCompactions.get();
        String bakedEvictionReason = lastBakedEvictionReason;
        int generating = inFlightChunks.size();
        int baking = inFlightBakes.size();
        int retries = pendingChunks.size();
        int exactUpgrades = pendingExactUpgrades.size();
        int naturalPending = pendingNaturalFeatures.size();
        int naturalInFlight = inFlightNaturalFeatures.size();
        int naturalExecutorQueued = naturalFeatureExecutor.getQueue().size();
        int diskRestored = diskRestoredChunks.size();
        diagnosticExecutor.execute(() -> {
            if (generation != worldGeneration) return;
            int exact = 0;
            int base = 0;
            int naturalResolved = 0;
            long naturalBlocks = 0L;
            for (ChunkData chunk : chunkCache.values()) {
                if (isExactSurface(chunk)) exact++;
                else base++;
                if (chunk.naturalDecorationsResolved) {
                    naturalResolved++;
                    naturalBlocks += chunk.naturalDecorations.size();
                }
            }
            String record = "[" + LocalDateTime.now().format(TERRAIN_DIAGNOSTIC_TIME) + "]"
                + " reason=frame-summary seed=" + seed
                + " dimension=" + dimension
                + " worldGeneration=" + generation
                + " center=" + centerX + ',' + centerZ
                + " anchor=" + anchorX + ',' + anchorZ
                + " coverage=" + coverage.ring() + '/' + coverage.index()
                + " detail=" + detail.ring() + '/' + detail.index()
                + " cacheChunks=" + cachedChunks
                + " cachedExact=" + exact
                + " cachedBase=" + base
                + " bakedChunks=" + bakedChunks
                + " bakedCacheBytes=" + bakedBytes
                + " bakedCacheEvictions=" + bakedEvictions
                + " bakedCacheCompactions=" + bakedCompactions
                + " bakedEvictionReason=" + bakedEvictionReason
                + " visibleChunks=" + visibleChunks
                + " renderedChunks=" + renderedChunks
                + " renderedVertices=" + renderedVertices
                + " missingBakes=" + missingBakes
                + " budgetSkipped=" + budgetSkippedChunks
                + " inFlightGeneration=" + generating
                + " inFlightBakes=" + baking
                + " pendingRetries=" + retries
                + " pendingExactUpgrades=" + exactUpgrades
                + " naturalPending=" + naturalPending
                + " naturalInFlight=" + naturalInFlight
                + " naturalExecutorQueued=" + naturalExecutorQueued
                + " naturalResolved=" + naturalResolved
                + " naturalBlocks=" + naturalBlocks
                + " naturalSnapshotsStored=" + naturalFeatureSnapshotsResolved.get()
                + " naturalBlocksStored=" + naturalFeatureBlocksResolved.get()
                + " naturalBakesWithGeometry=" + naturalFeatureBakesWithGeometry.get()
                + " naturalBakesEmpty=" + naturalFeatureBakesWithoutGeometry.get()
                + " naturalVerticesBaked=" + naturalFeatureVerticesBaked.get()
                + " naturalBudgetSkips=" + naturalFeatureBudgetSkips.get()
                + " diskRestored=" + diskRestored
                + " terrainRenderMs=" + String.format(java.util.Locale.ROOT, "%.3f",
                    lastTerrainRenderNanos / 1_000_000.0)
                + " avgFrameMs=" + String.format(java.util.Locale.ROOT, "%.3f",
                    averageFrameIntervalNanos / 1_000_000.0)
                + " gpuBatches=" + lastGpuBatchesDrawn
                + " gpuBatchQueue=" + lastGpuBatchesQueued
                + " gpuBatchesCulled=" + lastGpuBatchesCulled
                + " gpuUploadBytes=" + lastGpuUploadBytes
                + " gpuBuildMs=" + String.format(java.util.Locale.ROOT, "%.3f",
                    lastGpuBuildNanos / 1_000_000.0)
                + " gpuUploadMs=" + String.format(java.util.Locale.ROOT, "%.3f",
                    lastGpuUploadNanos / 1_000_000.0)
                + " caveBakesWithGeometry=" + caveBakesWithGeometry.get()
                + " caveBakesEmpty=" + caveBakesWithoutGeometry.get()
                + " caveVerticesBaked=" + caveVerticesBaked.get()
                + " gpuPackingJobs=" + gpuUploadPackingJobs.get()
                + " gpuUploadsWaiting=" + pendingGpuUploads.size()
                + " emptyGpuUploadsDiscarded=" + emptyGpuUploadsDiscarded.get()
                + " performanceThrottle=" + performanceThrottle
                + System.lineSeparator();
            synchronized (terrainDiagnosticLock) {
                try {
                    Path path = terrainDiagnosticPath();
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, record, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException | RuntimeException ignored) {
                    // Diagnostics must never affect generation or rendering.
                }
            }
        });
    }

    private static Path terrainDiagnosticPath() {
        Minecraft mc = Minecraft.getInstance();
        Path base = mc != null && mc.gameDirectory != null
            ? mc.gameDirectory.toPath() : Path.of(".");
        return base.resolve("config").resolve("seed-explorer").resolve("terrain-3d-chunks.log");
    }

    public void render(GuiGraphicsExtractor graphics, FreeCamera3D camera, MapRenderContext context,
                       List<GeneratedStructure> structures, List<SeedWaypoint> waypoints,
                       GeneratedStructure hoveredStructure, SeedWaypoint hoveredWaypoint) {
        render(graphics, camera, context, structures, waypoints, hoveredStructure, hoveredWaypoint,
            false, false, false, null);
    }

    public void render(FreeCamera3D camera, MapRenderContext context,
                       List<GeneratedStructure> structures, List<SeedWaypoint> waypoints,
                       GeneratedStructure hoveredStructure, SeedWaypoint hoveredWaypoint,
                       boolean undergroundView) {
        render(null, camera, context, structures, waypoints, hoveredStructure, hoveredWaypoint,
            undergroundView, false, false, null);
    }

    public void render(FreeCamera3D camera, MapRenderContext context,
                       List<GeneratedStructure> structures, List<SeedWaypoint> waypoints,
                       GeneratedStructure hoveredStructure, SeedWaypoint hoveredWaypoint,
                       boolean undergroundView, boolean textureDebugView) {
        render(null, camera, context, structures, waypoints, hoveredStructure, hoveredWaypoint,
            undergroundView, textureDebugView, false, null);
    }

    public void render(FreeCamera3D camera, MapRenderContext context,
                       List<GeneratedStructure> structures, List<SeedWaypoint> waypoints,
                       GeneratedStructure hoveredStructure, SeedWaypoint hoveredWaypoint,
                       boolean undergroundView, boolean textureDebugView,
                       boolean terrainDebugView) {
        render(null, camera, context, structures, waypoints, hoveredStructure, hoveredWaypoint,
            undergroundView, textureDebugView, terrainDebugView, null);
    }

    public void render(GuiGraphicsExtractor graphics, FreeCamera3D camera, MapRenderContext context,
                       List<GeneratedStructure> structures, List<SeedWaypoint> waypoints,
                       GeneratedStructure hoveredStructure, SeedWaypoint hoveredWaypoint,
                       boolean undergroundView, boolean textureDebugView,
                       boolean terrainDebugView, GeneratedStructure inspectionStructure) {
        render(graphics, camera, context, structures, waypoints, hoveredStructure, hoveredWaypoint,
            undergroundView, textureDebugView, terrainDebugView, inspectionStructure,
            worldDataSource.worldSeed());
    }

    /** Primary standalone entry point: all prediction state is supplied explicitly. */
    public void render(GuiGraphicsExtractor graphics, FreeCamera3D camera, MapRenderContext context,
                       List<GeneratedStructure> structures, List<SeedWaypoint> waypoints,
                       GeneratedStructure hoveredStructure, SeedWaypoint hoveredWaypoint,
                       boolean undergroundView, boolean textureDebugView,
                       boolean terrainDebugView, GeneratedStructure inspectionStructure,
                       long seed) {
        frameGraphics = graphics;
        overlayRenderer.beginFrame(graphics);
        meshUniforms.beginFrame();

        int centerChunkX = (int) Math.floor(camera.targetX() / CHUNK_SIZE);
        int centerChunkZ = (int) Math.floor(camera.targetZ() / CHUNK_SIZE);

        int dimension = context.dimension();
        prepare(seed, dimension, camera.targetX(), camera.targetZ());
        // Texture construction uploads to the GPU. Always do it on the render
        // thread before a background bake reads atlas regions, otherwise a first
        // bake can fail and leave a permanent-looking empty chunk.
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        if (chunkCache.isEmpty()) {
            renderStatusOverlay("Loading 3D terrain...",
                inFlightChunks.size() + " chunks queued"
                    + " | " + atlas.status()
                    + (lastTerrainError.isBlank() ? "" : " | last error: " + lastTerrainError));
            return;
        }
        refreshObservedChunks(dimension, centerChunkX, centerChunkZ);
        // The orbit camera is intentionally centered above the surface in normal
        // mode. Only the explicit Underground toggle selects cave geometry; using
        // the orbit eye height here made mountains turn the default view into a
        // sparse underground shell with black gaps.
        boolean caveView = undergroundView;
        TerrainFocusTarget inspectionTarget = inspectionStructure == null
            ? null : structureFocusTarget(inspectionStructure);

        meshUniforms.projection().set(camera.getProjectionMatrix());

        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        TerrainRenderStats terrainStats;
        modelView.pushMatrix();
        try {
            modelView.set(camera.getViewMatrix());

            updatePerformanceState(System.nanoTime());
            terrainStats = renderPersistentTerrain(camera, seed, dimension,
                centerChunkX, centerChunkZ, caveView, textureDebugView, atlas, inspectionTarget);

            // Structure previews and the optional debug tint remain dynamic. They are
            // small compared with terrain and can change independently every frame.
            BakingSink dynamicSolid = new BakingSink();
            BakingSink vanillaPreview = new BakingSink();
            renderStructurePreviews(dynamicSolid, camera, structures,
                hoveredStructure, inspectionStructure, seed, dimension, caveView, textureDebugView,
                vanillaPreview);
            // Never paint diagnostic colors over the terrain unless the user
            // explicitly opened the debug layer. The green slabs in recent
            // screenshots were the generated+baked legend leaking into normal
            // map rendering after a mode transition.
            List<VisibleChunk> debugVisible = terrainDebugView && !caveView
                ? visibleChunks(camera, centerChunkX, centerChunkZ) : List.of();
            if (!debugVisible.isEmpty()) {
                renderTerrainDebugColors(dynamicSolid, debugVisible, seed, dimension);
            }
            renderDynamicGeometry(SeedExplorerRenderPipelines.WORLD_TEXTURED,
                atlas, dynamicSolid.finish());
            renderVanillaPreviewGeometry(vanillaPreview.finish());

            terrainFrameSummary(seed, dimension, terrainStats.visibleChunks(),
                terrainStats.renderedChunks(), terrainStats.renderedVertices(),
                terrainStats.missingBakes(), terrainStats.budgetFallbackChunks());
        } finally {
            modelView.popMatrix();
        }

        if (caveView && terrainStats.renderedVertices() == 0) {
            String detail = undergroundStatusDetail()
                + (lastTerrainError.isBlank() ? "" : " | " + lastTerrainError);
            renderStatusOverlay("Generating underground terrain...", detail);
        }

        // Structure and waypoint icons (2D overlay)
        Minecraft mc = Minecraft.getInstance();
        double centerX = mc.getWindow().getWidth() / 2.0;
        double centerY = mc.getWindow().getHeight() / 2.0;

        if (context.enabled(MapLayer.STRUCTURES)) {
            renderStructureIcons(camera, structures, hoveredStructure, centerX, centerY, mc, context);
        }
        if (context.enabled(MapLayer.WAYPOINTS)) {
            renderWaypointIcons(camera, waypoints, hoveredWaypoint, centerX, centerY, mc);
        }
    }

    /**
     * Renders the existing terrain pipeline into a caller-owned target. This is used by native
     * screens whose GUI state is extracted before Minecraft clears and renders its main target.
     */
    public void renderToTarget(RenderTarget target,
                               GuiGraphicsExtractor graphics, FreeCamera3D camera,
                               MapRenderContext context,
                               List<GeneratedStructure> structures, List<SeedWaypoint> waypoints,
                               GeneratedStructure hoveredStructure, SeedWaypoint hoveredWaypoint,
                               boolean undergroundView, boolean textureDebugView,
                               boolean terrainDebugView, GeneratedStructure inspectionStructure,
                               long seed) {
        RenderTarget previousTarget = scopedRenderTarget;
        scopedRenderTarget = target;
        try {
            render(graphics, camera, context, structures, waypoints,
                hoveredStructure, hoveredWaypoint, undergroundView, textureDebugView,
                terrainDebugView, inspectionStructure, seed);
        } finally {
            scopedRenderTarget = previousTarget;
        }
    }

    public int lastGpuBatchesDrawn() {
        return lastGpuBatchesDrawn;
    }

    public boolean hasDrawableTerrain() {
        return lastGpuBatchesDrawn > 0;
    }

    private RenderTarget activeRenderTarget(Minecraft minecraft) {
        return scopedRenderTarget != null
            ? scopedRenderTarget : minecraft.gameRenderer.mainRenderTarget();
    }

    /**
     * Advances the generated-terrain streaming window without opening the 3D map.
     * Call this once per client frame while a 2D map is visible to prewarm terrain.
     */
    public PreparationStatus prepare(long seed, int dimension, double centerBlockX, double centerBlockZ) {
        int centerChunkX = (int) Math.floor(centerBlockX / CHUNK_SIZE);
        int centerChunkZ = (int) Math.floor(centerBlockZ / CHUNK_SIZE);
        return prepareWindow(seed, dimension, centerChunkX, centerChunkZ);
    }

    public synchronized BulkLoadStatus bulkLoad(long seed, int dimension,
                                                double centerBlockX, double centerBlockZ) {
        if (!bulkLoading) {
            bulkLoading = true;
            bulkRateInitialized = false;
            bulkChunksPerSecond = 0.0;
            bulkLastProgressNanos = System.nanoTime();
            terrainExecutor.setCorePoolSize(BULK_TERRAIN_THREADS);
            terrainExecutor.prestartAllCoreThreads();
            setTerrainWorkerPriority(Thread.NORM_PRIORITY);
            diskCache.setBulkMode(true);
        }
        PreparationStatus preparation = prepare(seed, dimension, centerBlockX, centerBlockZ);
        TerrainStreamingCursor.State coverage = coverageCursor.snapshot();
        TerrainStreamingCursor.State detail = detailCursor.snapshot();
        long exactTarget = TerrainLodPolicy.squareChunkCount(TerrainLodPolicy.FULL_DETAIL_CHUNKS);
        long detailCompleted = detail.completedPositions()
            - (pendingDetailCoordinate == null ? 0L : 1L);
        detailCompleted = Math.min(exactTarget, Math.max(0L, detailCompleted));
        updateBulkRate(detailCompleted, streamStateLoading);
        long etaSeconds = bulkChunksPerSecond > 0.0
            ? (long) Math.ceil(Math.max(0L, exactTarget - detailCompleted) / bulkChunksPerSecond)
            : -1L;
        long stalledSeconds = Math.max(0L,
            (System.nanoTime() - bulkLastProgressNanos) / 1_000_000_000L);
        return new BulkLoadStatus(detailCompleted, exactTarget, coverage.ring(), detail.ring(),
            preparation.cachedChunks(), inFlightChunks.size(), pendingChunks.size(),
            failedChunkGenerations.get(), streamStateLoading, lastTerrainError,
            bulkChunksPerSecond, etaSeconds, diskCache.pendingWrites(),
            terrainExecutor.getPoolSize(), terrainExecutor.getActiveCount(),
            terrainExecutor.getQueue().size(), stalledSeconds);
    }

    public synchronized void stopBulkLoading() {
        if (!bulkLoading) return;
        // Do not walk chunkCache here. A long bulk run can contain hundreds of
        // thousands of entries, and the old implementation synchronously bumped
        // every geometry revision, queued a bake for every nearby chunk, and
        // invalidated batches from the Stop click handler. That made the client
        // appear hung and could exhaust the bake queue before the next frame.
        bulkLoading = false;
        diskCache.setBulkMode(false);
        setTerrainWorkerPriority(Thread.MIN_PRIORITY);
        terrainExecutor.setCorePoolSize(TERRAIN_THREADS);
        bulkRateInitialized = false;
        bulkChunksPerSecond = 0.0;
        performanceThrottle = 0;
        lastStreamPulseNanos = 0L;
        chunkRevision.incrementAndGet();
        // Bulk mode does not bake or upload terrain. Normal rendering will
        // request only the camera-visible meshes through appendChunkToGpuBuild,
        // while late bulk jobs self-check worldGeneration and remain cache-safe.
        // Drop speculative rebake requests left by a previous normal pass; keep
        // generated ChunkData and disk progress untouched.
        pendingBackgroundBakes.clear();
        queuedBackgroundBakes.clear();
        pendingExactUpgrades.clear();
        queuedExactUpgrades.clear();
        if (lastCenterChunkX != Integer.MIN_VALUE
            && lastCenterChunkZ != Integer.MIN_VALUE) {
            rebuildNaturalFeatureQueue(lastCenterChunkX, lastCenterChunkZ);
        }
        refreshVisibleGpuBatchesAfterBulk();
        terrainDiagnostic("bulk-stop", lastSeed, lastDimension,
            lastCenterChunkX == Integer.MIN_VALUE ? 0 : lastCenterChunkX,
            lastCenterChunkZ == Integer.MIN_VALUE ? 0 : lastCenterChunkZ, 0, null, null,
            "cached=" + chunkCache.size()
                + " generationInFlight=" + inFlightChunks.size()
                + " bakeInFlight=" + inFlightBakes.size());
        saveStreamState(lastSeed, lastDimension, true);
    }

    private void setTerrainWorkerPriority(int priority) {
        for (Thread thread : terrainWorkerThreads) {
            if (!thread.isAlive()) {
                terrainWorkerThreads.remove(thread);
                continue;
            }
            try {
                thread.setPriority(priority);
            } catch (RuntimeException ignored) {
                // Priority is an optimization; generation remains correct if the JVM rejects it.
            }
        }
    }

    private void updateBulkRate(long completedChunks, boolean restoringCache) {
        long now = System.nanoTime();
        if (restoringCache || !bulkRateInitialized) {
            if (!restoringCache) {
                bulkRateInitialized = true;
                bulkRateCompletedChunks = completedChunks;
                bulkRateSampleNanos = now;
                bulkLastProgressNanos = now;
            }
            return;
        }
        long elapsed = now - bulkRateSampleNanos;
        if (elapsed < 1_000_000_000L) return;
        long completed = Math.max(0L, completedChunks - bulkRateCompletedChunks);
        if (completed > 0L) bulkLastProgressNanos = now;
        double currentRate = completed * 1_000_000_000.0 / elapsed;
        bulkChunksPerSecond = bulkChunksPerSecond <= 0.0
            ? currentRate
            : bulkChunksPerSecond * 0.7 + currentRate * 0.3;
        bulkRateCompletedChunks = completedChunks;
        bulkRateSampleNanos = now;
    }

    private synchronized PreparationStatus prepareWindow(long seed, int dimension,
                                                          int centerChunkX, int centerChunkZ) {
        if (lastRenderInvocationNanos != 0L
            && System.nanoTime() - lastRenderInvocationNanos > 1_000_000_000L) {
            performanceThrottle = 0;
            averageFrameIntervalNanos = 16_666_667.0;
        }
        boolean worldChanged = seed != lastSeed || dimension != lastDimension;
        int desiredAnchorX = worldChanged ? streamAnchor(centerChunkX)
            : TerrainLodPolicy.persistentStreamAnchor(streamAnchorChunkX, centerChunkX,
                STREAM_ANCHOR_REGION_SIZE);
        int desiredAnchorZ = worldChanged ? streamAnchor(centerChunkZ)
            : TerrainLodPolicy.persistentStreamAnchor(streamAnchorChunkZ, centerChunkZ,
                STREAM_ANCHOR_REGION_SIZE);
        if (worldChanged || centerChunkX != lastCenterChunkX
            || centerChunkZ != lastCenterChunkZ) {
            if (worldChanged) {
                saveStreamState(lastSeed, lastDimension, true);
                worldGeneration++;
                lastSeed = seed;
                lastDimension = dimension;
                synchronized (chunkCache) {
                    chunkCache.clear();
                }
                presentationFrontier.reset();
                chunkRevision.incrementAndGet();
                clearBakedCache();
                clearGpuBatches();
                gpuBatchRevisions.clear();
                pendingBackgroundBakes.clear();
                queuedBackgroundBakes.clear();
                pendingExactUpgrades.clear();
                queuedExactUpgrades.clear();
                pendingNaturalFeatures.clear();
                queuedNaturalFeatures.clear();
                naturalFeatureQueueCenterX = Integer.MIN_VALUE;
                naturalFeatureQueueCenterZ = Integer.MIN_VALUE;
                lastStreamPulseNanos = 0L;
                previewCache.clear();
                lastTerrainError = "";
                failedChunkGenerations.set(0);
                chunkGenerationFailures.clear();
                terrainDiagnosticKeys.clear();
                routineTerrainDiagnosticCount.set(0);
                visibleChunkCache = List.of();
                visibleChunkRevision = Long.MIN_VALUE;
                missingPlaceholderCache = List.of();
                missingPlaceholderRefreshNanos = 0L;
                diskRestoredChunks.clear();
                completedStreamRegions.clear();
                geometryRevisions.clear();
                nextGeometryRevision.set(0L);
                streamWorldIdentity = worldCacheIdentity(seed);
            }
            if (worldChanged) {
                rebuildPending(desiredAnchorX, desiredAnchorZ);
                loadStreamStateAsync(seed, dimension, centerChunkX, centerChunkZ);
            }
            warmCachedWindowAsync(seed, dimension, centerChunkX, centerChunkZ);
            rebuildCameraPriority(centerChunkX, centerChunkZ);
            lastCenterChunkX = centerChunkX;
            lastCenterChunkZ = centerChunkZ;
            rebuildNaturalFeatureQueue(centerChunkX, centerChunkZ);
        }
        submitStreamingChunks(seed, dimension, centerChunkX, centerChunkZ);
        submitNaturalFeatureUpgrades(seed, dimension, centerChunkX, centerChunkZ);
        submitPreparedBakes(seed, dimension, centerChunkX, centerChunkZ);
        saveStreamState(seed, dimension, false);
        ChunkData center = chunkCache.get(ChunkPos.pack(centerChunkX, centerChunkZ));
        return new PreparationStatus(chunkCache.size(), pendingChunks.size(), inFlightChunks.size(),
            RENDER_DIST, center == null ? TerrainAccuracy.UNAVAILABLE : center.accuracy);
    }

    public TerrainAccuracy accuracyAt(int blockX, int blockZ) {
        ChunkData chunk = chunkCache.get(ChunkPos.pack(Math.floorDiv(blockX, CHUNK_SIZE),
            Math.floorDiv(blockZ, CHUNK_SIZE)));
        return chunk == null ? TerrainAccuracy.UNAVAILABLE : chunk.accuracy;
    }

    public DebugChunkInfo pickDebugChunk(FreeCamera3D camera, double mouseX, double mouseY,
                                         double screenCenterX, double screenCenterY) {
        DebugChunkInfo best = null;
        double bestDistance = 48.0 * 48.0;
        for (VisibleChunk visible : visibleChunkCache) {
            ChunkData chunk = visible.chunk();
            CoarseSurface surface = coarseSurface(chunk, lastDimension, BlockTextureAtlas.get());
            if (surface == null) continue;
            double[] projected = project(camera, chunk.chunkX * CHUNK_SIZE + 8.0,
                surface.y + 1.2, chunk.chunkZ * CHUNK_SIZE + 8.0, screenCenterX, screenCenterY);
            if (!Double.isFinite(projected[0]) || projected[2] < -1.0 || projected[2] > 1.0) continue;
            double dx = mouseX - projected[0];
            double dy = mouseY - projected[1];
            double distance = dx * dx + dy * dy;
            if (distance >= bestDistance) continue;
            bestDistance = distance;
            long key = ChunkPos.pack(chunk.chunkX, chunk.chunkZ);
            BakedChunk baked = bakedCache.get(key);
            int requested = sampleStep(camera, chunk);
            Integer displayed = displayedSurfaceSteps.get(key);
            String bakeState = terrainFailureReasons.containsKey(key) ? "failed"
                : isChunkInFlight(inFlightBakes, key) ? "baking"
                : baked == null ? "waiting"
                : staleUpgradeChunks.contains(key) ? "stale-upgrade" : "baked";
            best = new DebugChunkInfo(chunk.chunkX, chunk.chunkZ, requested,
                displayed == null ? -1 : displayed, chunk.generatedStep,
                diskRestoredChunks.contains(key) ? "disk" : "generated", bakeState,
                terrainFailureReasons.getOrDefault(key,
                    frameBudgetFallbackChunks.contains(key) ? "frame-budget-fallback" : ""));
        }
        return best;
    }

    private void renderTerrainDebugColors(GeoSink builder, List<VisibleChunk> visibleChunks,
                                          long seed, int dimension) {
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        int limit = Math.min(32_768, visibleChunks.size());
        for (int i = 0; i < limit; i++) {
            ChunkData chunk = visibleChunks.get(i).chunk();
            long key = ChunkPos.pack(chunk.chunkX, chunk.chunkZ);
            CoarseSurface surface = coarseSurface(chunk, dimension, atlas);
            if (surface == null) continue;
            RgbaColor color = terrainDebugColor(seed, dimension, key,
                bakedCache.get(key), chunk);
            TextureRegion region = atlas.region(surface.state);
            if (region == null) continue;
            double x = chunk.chunkX * (double) CHUNK_SIZE;
            double z = chunk.chunkZ * (double) CHUNK_SIZE;
            double y = surface.y + 0.08;
            builder.ensureQuadCapacity();
            builder.quad(
                builder.vec3(x, y, z).vec2(region.x1, region.y1).color(color).next(),
                builder.vec3(x + CHUNK_SIZE, y, z).vec2(region.x2, region.y1).color(color).next(),
                builder.vec3(x + CHUNK_SIZE, y, z + CHUNK_SIZE).vec2(region.x2, region.y2).color(color).next(),
                builder.vec3(x, y, z + CHUNK_SIZE).vec2(region.x1, region.y2).color(color).next());
        }
    }

    private RgbaColor terrainDebugColor(long seed, int dimension, long key,
                                    BakedChunk baked, ChunkData chunk) {
        if (terrainFailureReasons.containsKey(key)) return new RgbaColor(235, 55, 62, 255);
        if (isChunkInFlight(inFlightChunks, key)) return new RgbaColor(153, 78, 214, 255);
        if (baked == null || isChunkInFlight(inFlightBakes, key)) return new RgbaColor(241, 208, 58, 255);
        if (staleUpgradeChunks.contains(key)) return new RgbaColor(53, 218, 220, 255);
        if (frameBudgetFallbackChunks.contains(key)) return new RgbaColor(240, 132, 42, 255);
        if (diskRestoredChunks.contains(key)) return new RgbaColor(55, 112, 238, 255);
        return new RgbaColor(48, 194, 88, 255);
    }

    private boolean isChunkInFlight(Set<WorkKey> work, long chunkKey) {
        for (WorkKey key : work) {
            if (key.generation() == worldGeneration && key.seed() == lastSeed
                && key.dimension() == lastDimension && key.chunkKey() == chunkKey) return true;
        }
        return false;
    }

    /** Initializes the persistent coverage/detail spirals for a new world. */
    private void rebuildPending(int anchorChunkX, int anchorChunkZ) {
        pendingChunks.clear();
        clearCameraQueues();
        coverageCursor.reset();
        detailCursor.reset();
        pendingDetailCoordinate = null;
        streamAnchorChunkX = anchorChunkX;
        streamAnchorChunkZ = anchorChunkZ;
        lastStreamPulseNanos = 0L;
        previewCache.entrySet().removeIf(entry -> {
            PreviewKey key = entry.getKey();
            return Math.abs(key.startChunkX() - anchorChunkX) > PREVIEW_CACHE_RETAIN_DIST
                || Math.abs(key.startChunkZ() - anchorChunkZ) > PREVIEW_CACHE_RETAIN_DIST;
        });
        trimPreviewCacheToBudget(anchorChunkX, anchorChunkZ);
    }

    private void rebuildCameraPriority(int centerChunkX, int centerChunkZ) {
        clearCameraQueues();
        TerrainStreamingCursor cursor = new TerrainStreamingCursor();
        while (true) {
            TerrainStreamingCursor.Coordinate coordinate = cursor.next(centerChunkX, centerChunkZ);
            if (coordinate.ring() > CAMERA_PRIORITY_RADIUS) break;
            long key = ChunkPos.pack(coordinate.chunkX(), coordinate.chunkZ());
            ChunkData chunk = chunkCache.get(key);
            if (needsGeneration(chunk, 16)) {
                cameraCoverageChunks.addLast(work(key, coordinate, 16));
            }
            int desiredStep = TerrainLodPolicy.interactiveSampleStep(coordinate.ring());
            int progressiveStep = desiredStep == 1 ? 4 : desiredStep;
            if (needsGeneration(chunk, progressiveStep)) {
                cameraLodChunks.addLast(work(key, coordinate, progressiveStep));
            }
            if (desiredStep == 1 && needsGeneration(chunk, 1)) {
                cameraExactChunks.addLast(work(key, coordinate, 1));
            }
        }
    }

    private static long[] work(long key, TerrainStreamingCursor.Coordinate coordinate, int step) {
        return new long[]{key, coordinate.chunkX(), coordinate.chunkZ(), step};
    }

    private static boolean needsGeneration(ChunkData chunk, int desiredStep) {
        return chunk == null || chunk.observedSnapshot == null
            && (chunk.generatedStep > desiredStep
                || desiredStep == 1 && !isExactSurface(chunk));
    }

    private void clearCameraQueues() {
        cameraCoverageChunks.clear();
        cameraLodChunks.clear();
        cameraExactChunks.clear();
    }

    /**
     * Hands bounded work to low-priority workers on short pulses. Coarse coverage moves
     * ahead first so zoomed-out terrain becomes useful quickly; a second cursor then
     * upgrades the same chunks to the requested near/mid detail in place.
     */
    private void submitStreamingChunks(long seed, int dimension,
                                       int centerChunkX, int centerChunkZ) {
        if (streamStateLoading) return;
        long now = System.nanoTime();
        int throttle = bulkLoading ? 0 : performanceThrottle;
        long pulseNanos = bulkLoading ? BULK_STREAM_PULSE_NANOS
            : STREAM_PULSE_NANOS * Math.max(1L, throttle + 1L);
        if (now - lastStreamPulseNanos < pulseNanos) return;
        lastStreamPulseNanos = now;

        int maximumInFlight = bulkLoading ? BULK_MAX_IN_FLIGHT_CHUNKS
            : TerrainLodPolicy.adaptiveBackgroundBudget(MAX_IN_FLIGHT_CHUNKS, throttle, 4);
        int maximumExamined = bulkLoading ? BULK_MAX_STREAM_POSITIONS_EXAMINED_PER_PULSE
            : MAX_STREAM_POSITIONS_EXAMINED_PER_PULSE;
        int reservedCoverageSlot = Math.max(0, maximumInFlight - 1);

        boolean interactiveCoverageComplete = cameraCoverageChunks.isEmpty()
            && cameraLodChunks.isEmpty() && inFlightChunks.isEmpty();
        int exactBudget = bulkLoading ? BULK_EXACT_UPGRADES_PER_PULSE
            : interactiveCoverageComplete ? TerrainLodPolicy.adaptiveBackgroundBudget(
                COVERAGE_SUBMISSIONS_PER_PULSE, throttle, 1) : 0;
        while (exactBudget > 0 && inFlightChunks.size() < reservedCoverageSlot) {
            Long key = pendingExactUpgrades.poll();
            if (key == null) break;
            queuedExactUpgrades.remove(key);
            ChunkData current = chunkCache.get(key);
            if (current == null || current.observedSnapshot != null || isExactSurface(current)) continue;
            if (submitChunkGeneration(seed, dimension, current.chunkX, current.chunkZ, 1)) {
                exactBudget--;
            } else {
                queueExactUpgrade(key);
                break;
            }
        }

        int priorityBudget = bulkLoading ? 0 : TerrainLodPolicy.adaptiveBackgroundBudget(
            COVERAGE_SUBMISSIONS_PER_PULSE, throttle, 1);
        java.util.ArrayDeque<long[]> cameraQueue = activeCameraQueue();
        while (priorityBudget > 0 && inFlightChunks.size() < reservedCoverageSlot
            && cameraQueue != null && !cameraQueue.isEmpty()) {
            long[] priority = cameraQueue.pollFirst();
            ChunkData current = chunkCache.get(priority[0]);
            int desiredStep = (int) priority[3];
            if (current != null && (current.observedSnapshot != null
                || current.generatedStep < desiredStep
                || current.generatedStep == desiredStep
                    && (desiredStep != 1 || isExactSurface(current)))) continue;
            if (submitChunkGeneration(seed, dimension, (int) priority[1], (int) priority[2],
                desiredStep)) priorityBudget--;
        }
        int retryBudget = bulkLoading ? BULK_RETRY_SUBMISSIONS_PER_PULSE
            : TerrainLodPolicy.adaptiveBackgroundBudget(
                RETRY_SUBMISSIONS_PER_PULSE, throttle, 1);
        while (retryBudget > 0 && inFlightChunks.size() < reservedCoverageSlot
            && !pendingChunks.isEmpty()) {
            long[] retry = pendingChunks.pollFirst();
            int cx = (int) retry[1];
            int cz = (int) retry[2];
            if (chunkCache.containsKey(retry[0])) continue;
            if (submitChunkGeneration(seed, dimension, cx, cz,
                generationStepForChunk(cx, cz))) retryBudget--;
        }

        // The detail cursor is the contiguous, persisted completion frontier. In
        // bulk mode it must receive newly freed slots before the speculative
        // coverage lane, otherwise one queued coverage job can park progress for
        // minutes while the coverage spiral races far ahead.
        int detailBudget = bulkLoading ? BULK_DETAIL_SUBMISSIONS_PER_PULSE : 0;
        int examined = 0;
        while (detailBudget > 0 && inFlightChunks.size() < maximumInFlight
            && examined++ < maximumExamined) {
            if (pendingDetailCoordinate == null) {
                pendingDetailCoordinate = detailCursor.next(streamAnchorChunkX, streamAnchorChunkZ);
            }
            TerrainStreamingCursor.Coordinate coordinate = pendingDetailCoordinate;
            if (coordinate.ring() > TerrainLodPolicy.MID_DETAIL_CHUNKS) return;

            int desiredStep = coordinate.ring() <= CAMERA_EXACT_RING_CHUNKS
                ? 1 : generationStepForDistance(coordinate.ring());
            long key = ChunkPos.pack(coordinate.chunkX(), coordinate.chunkZ());
            ChunkData current = chunkCache.get(key);
            if (current == null) {
                WorkKey workKey = new WorkKey(seed, dimension, key, worldGeneration);
                if (inFlightChunks.contains(workKey)) break;
                // First paint is always the cheap one-sample coverage tile. Keep
                // this detail coordinate pending so it is upgraded after the
                // contiguous spiral has something visible at this position.
                if (submitChunkGeneration(seed, dimension, coordinate.chunkX(),
                    coordinate.chunkZ(), TerrainLodPolicy.coverageSampleStep(
                        coordinate.ring()))) {
                    detailBudget--;
                    continue;
                }
                break;
            }
            if (current != null && (current.observedSnapshot != null
                || current.generatedStep < desiredStep
                || current.generatedStep == desiredStep
                    && (desiredStep != 1 || isExactSurface(current)))) {
                pendingDetailCoordinate = null;
                continue;
            }

            WorkKey workKey = new WorkKey(seed, dimension, key, worldGeneration);
            if (inFlightChunks.contains(workKey)) break;
            if (submitChunkGeneration(seed, dimension, coordinate.chunkX(),
                coordinate.chunkZ(), desiredStep)) {
                pendingDetailCoordinate = null;
                detailBudget--;
            } else {
                break;
            }
        }

        int coverageBudget = bulkLoading ? BULK_COVERAGE_SUBMISSIONS_PER_PULSE : 0;
        long coverageLead = coverageCursor.snapshot().completedPositions()
            - detailCursor.snapshot().completedPositions();
        if (bulkLoading && coverageLead >= BULK_COVERAGE_LEAD_POSITIONS) return;
        examined = 0;
        while (coverageBudget > 0 && inFlightChunks.size() < maximumInFlight
            && examined++ < maximumExamined) {
            TerrainStreamingCursor.Coordinate coordinate =
                coverageCursor.next(streamAnchorChunkX, streamAnchorChunkZ);
            if (coordinate.ring() > WORLD_BORDER_CHUNK_RADIUS) break;
            int cx = coordinate.chunkX();
            int cz = coordinate.chunkZ();
            long key = ChunkPos.pack(cx, cz);
            ChunkData current = chunkCache.get(key);
            // Coverage is deliberately one sample per chunk. The detail cursor
            // upgrades the saved exact square without delaying first paint.
            int desiredStep = TerrainLodPolicy.coverageSampleStep(coordinate.ring());
            if (current != null && (current.observedSnapshot != null
                || current.generatedStep < desiredStep
                || current.generatedStep == desiredStep
                    && (desiredStep != 1 || isExactSurface(current)))) continue;
            if (submitChunkGeneration(seed, dimension, cx, cz, desiredStep)) {
                if (current == null) {
                    terrainDiagnostic("missing-cache", seed, dimension, cx, cz,
                        desiredStep, null, null, "coverage=true");
                }
                coverageBudget--;
            }
            coverageLead = coverageCursor.snapshot().completedPositions()
                - detailCursor.snapshot().completedPositions();
            if (bulkLoading && coverageLead >= BULK_COVERAGE_LEAD_POSITIONS) break;
        }
    }

    private java.util.ArrayDeque<long[]> activeCameraQueue() {
        if (!cameraCoverageChunks.isEmpty()) return cameraCoverageChunks;
        // Do not let a finer job overtake coarse work that is already executing.
        if (!inFlightChunks.isEmpty()) return null;
        if (!cameraLodChunks.isEmpty()) return cameraLodChunks;
        if (!cameraExactChunks.isEmpty()) return cameraExactChunks;
        return null;
    }

    private void loadStreamStateAsync(long seed, int dimension,
                                      int centerChunkX, int centerChunkZ) {
        long generation = worldGeneration;
        String identity = streamWorldIdentity;
        streamStateLoading = true;
        cacheLoaderExecutor.execute(() -> {
            try {
                var restored = streamStateStore.load(identity, seed, dimension);
                synchronized (Terrain3DRenderer.this) {
                    if (generation != worldGeneration || seed != lastSeed
                        || dimension != lastDimension) return;
                    restored.ifPresent(state -> {
                        streamAnchorChunkX = state.anchorChunkX();
                        streamAnchorChunkZ = state.anchorChunkZ();
                        coverageCursor.restore(state.coverage());
                        detailCursor.restore(state.detail());
                        pendingChunks.clear();
                        for (long retry : state.retryCoordinates()) {
                            pendingChunks.addLast(new long[]{retry,
                                ChunkPos.getX(retry), ChunkPos.getZ(retry)});
                        }
                        completedStreamRegions.addAll(state.completedRegions());
                    });
                }
            } finally {
                if (generation == worldGeneration) {
                    streamStateLoading = false;
                    int restoredRadius = bulkLoading
                        ? Math.min(MAX_IN_MEMORY_VISIBLE_RADIUS,
                            Math.max(CACHE_WARM_RADIUS, detailCursor.ring()))
                        : INTERACTIVE_CACHE_WARM_RADIUS;
                    warmCachedWindowAsync(seed, dimension, centerChunkX, centerChunkZ,
                        restoredRadius);
                }
            }
        });
    }

    private void warmCachedWindowAsync(long seed, int dimension, int centerChunkX, int centerChunkZ) {
        warmCachedWindowAsync(seed, dimension, centerChunkX, centerChunkZ,
            bulkLoading ? CACHE_WARM_RADIUS : INTERACTIVE_CACHE_WARM_RADIUS);
    }

    private void warmCachedWindowAsync(long seed, int dimension, int centerChunkX, int centerChunkZ,
                                       int requestedRadius) {
        long generation = worldGeneration;
        int minimumRadius = bulkLoading ? CACHE_WARM_RADIUS : INTERACTIVE_CACHE_WARM_RADIUS;
        int radius = Math.max(minimumRadius,
            Math.min(MAX_IN_MEMORY_VISIBLE_RADIUS, requestedRadius));
        if (cacheWarmGeneration == generation
            && Math.abs(centerChunkX - cacheWarmCenterX) < STREAM_ANCHOR_REGION_SIZE / 2
            && Math.abs(centerChunkZ - cacheWarmCenterZ) < STREAM_ANCHOR_REGION_SIZE / 2
            && cacheWarmRadius >= radius) return;
        cacheWarmGeneration = generation;
        cacheWarmCenterX = centerChunkX;
        cacheWarmCenterZ = centerChunkZ;
        cacheWarmRadius = radius;
        String identity = streamWorldIdentity;
        cacheLoaderExecutor.execute(() -> {
            TerrainStreamingCursor cursor = new TerrainStreamingCursor();
            int restored = 0;
            int restoredSinceRevision = 0;
            while (restored < MAX_CACHE_WARM_CHUNKS) {
                TerrainStreamingCursor.Coordinate coordinate = cursor.next(centerChunkX, centerChunkZ);
                if (coordinate.ring() > radius || generation != worldGeneration) break;
                int desiredStep = bulkLoading
                    ? TerrainLodPolicy.sourceSampleStep(streamAnchorChunkX,
                        streamAnchorChunkZ, coordinate.chunkX(), coordinate.chunkZ())
                    : TerrainLodPolicy.interactiveSampleStep(coordinate.ring());
                var cached = diskCache.load(identity, seed, dimension, coordinate.chunkX(),
                    coordinate.chunkZ(), desiredStep);
                if (cached.isEmpty()) continue;
                ChunkData restoredChunk = fromCachedChunk(cached.get());
                long key = ChunkPos.pack(coordinate.chunkX(), coordinate.chunkZ());
                presentationFrontier.sequenceFor(key);
                boolean retained = restoreCachedChunk(generation, key, restoredChunk);
                if (generation == worldGeneration && chunkCache.containsKey(key)) {
                    presentationFrontier.markReady(key);
                }
                if (!retained) continue;
                diskRestoredChunks.add(key);
                queueNaturalFeature(key);
                invalidateBakedDependencies(restoredChunk.chunkX, restoredChunk.chunkZ,
                    restoredChunk.observedSnapshot != null);
                queueBackgroundBake(key);
                restored++;
                restoredSinceRevision++;
                if ((restored & 31) == 0 && performanceThrottle > 0) {
                    LockSupport.parkNanos(performanceThrottle * 2_000_000L);
                }
                if (restoredSinceRevision >= 256) {
                    chunkRevision.incrementAndGet();
                    restoredSinceRevision = 0;
                }
            }
            if (restoredSinceRevision > 0) chunkRevision.incrementAndGet();
            terrainDiagnostic("cache-warm-complete", seed, dimension, centerChunkX, centerChunkZ,
                1, null, null, "restored=" + restored + " radius=" + radius);
        });
    }

    private boolean restoreCachedChunk(long generation, long key, ChunkData restored) {
        synchronized (chunkCache) {
            if (generation != worldGeneration) return false;
            ChunkData current = chunkCache.get(key);
            if (current != null) {
                if (current.observedSnapshot != null) return false;
                if (isExactSurface(current) && !isExactSurface(restored)) return false;
                if (current.generatedStep < restored.generatedStep) return false;
                if (current.generatedStep == restored.generatedStep
                    && isExactSurface(current) == isExactSurface(restored)) return false;
            }
                chunkCache.put(key, restored);
                trimChunkCacheToBudgetLocked(lastCenterChunkX, lastCenterChunkZ);
                if (presentationFrontier.isRevealed(key)) {
                    invalidateGpuBatchForChunk(restored.chunkX, restored.chunkZ);
                }
                return true;
        }
    }

    private static int streamAnchor(int chunkCoordinate) {
        return Math.floorDiv(chunkCoordinate, STREAM_ANCHOR_REGION_SIZE)
            * STREAM_ANCHOR_REGION_SIZE + STREAM_ANCHOR_REGION_SIZE / 2;
    }

    private static long streamRegionKey(int chunkX, int chunkZ) {
        return ChunkPos.pack(Math.floorDiv(chunkX, STREAM_ANCHOR_REGION_SIZE),
            Math.floorDiv(chunkZ, STREAM_ANCHOR_REGION_SIZE));
    }

    private void saveStreamState(long seed, int dimension, boolean force) {
        if (dimension == Integer.MIN_VALUE || streamWorldIdentity.isBlank()
            || streamAnchorChunkX == Integer.MIN_VALUE) return;
        if (streamStateLoading && !force) return;
        long now = System.nanoTime();
        if (!force && now - lastStreamStateSaveNanos < STREAM_STATE_SAVE_NANOS) return;
        lastStreamStateSaveNanos = now;
        List<Long> retries = new ArrayList<>(pendingChunks.size());
        for (long[] retry : pendingChunks) retries.add(retry[0]);
        TerrainStreamStateStore.State state = new TerrainStreamStateStore.State(
            streamAnchorChunkX, streamAnchorChunkZ, coverageCursor.snapshot(),
            detailCursor.snapshot(), List.copyOf(retries), Set.copyOf(completedStreamRegions));
        streamStateStore.storeAsync(streamWorldIdentity, seed, dimension, state);
    }

    public static String worldCacheIdentity(long seed) {
        String override = System.getProperty("seedexplorer.worldIdentity", "").trim();
        if (!override.isEmpty()) return override;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            String singleplayer = reflectedWorldName(mc);
            if (!singleplayer.isBlank()) return "singleplayer:" + singleplayer;
            var server = mc.getCurrentServer();
            if (server != null) return "multiplayer:" + server.ip.toLowerCase(java.util.Locale.ROOT);
            if (mc.gameDirectory != null) {
                return "game:" + mc.gameDirectory.toPath().toAbsolutePath().normalize()
                    + ":seed:" + Long.toUnsignedString(seed);
            }
        }
        return "seed:" + Long.toUnsignedString(seed);
    }

    private static String reflectedWorldName(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null) return "";
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .toAbsolutePath().normalize().toString();
    }

    private static Object invokeNoArgs(Object target, String methodName) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private TerrainRenderStats renderPersistentTerrain(FreeCamera3D camera, long seed,
                                                        int dimension, int centerChunkX,
                                                        int centerChunkZ, boolean caveView,
                                                        boolean textureDebugView,
                                                        BlockTextureAtlas atlas,
                                                        TerrainFocusTarget inspectionTarget) {
        long started = System.nanoTime();
        // Structure orbit uses the same camera-to-target tunnel as the cave
        // view so nearby terrain cannot cover the selected preview while it
        // rotates.
        CaveCutaway requestedCutaway = (caveView || inspectionTarget != null)
            ? CaveCutaway.at(camera, inspectionTarget) : null;
        if (!caveView) drainPresentationReveals();
        ensureGpuBatchWindow(camera, centerChunkX, centerChunkZ, caveView, textureDebugView,
            requestedCutaway);
        processPendingGpuUploads();
        continueGpuBatchBuild(camera, seed, dimension, caveView, textureDebugView, requestedCutaway);

        int renderedBatches = renderGpuBatches(SeedExplorerRenderPipelines.WORLD_TEXTURED,
            atlas, camera, TerrainGpuLayer.SOLID);
        renderGpuBatches(SeedExplorerRenderPipelines.WORLD_TEXTURED_CUTOUT,
            atlas, camera, TerrainGpuLayer.CUTOUT);
        renderGpuBatches(SeedExplorerRenderPipelines.WORLD_TEXTURED_TRANSLUCENT,
            atlas, camera, TerrainGpuLayer.FLUID);

        int renderedChunks = 0;
        int renderedVertices = 0;
        int missingBakes = 0;
        int budgetFallbacks = 0;
        for (TerrainGpuBatch batch : gpuBatches.values()) {
            renderedChunks += batch.renderedChunks();
            renderedVertices += batch.vertexCount();
            missingBakes += batch.missingBakes();
            budgetFallbacks += batch.budgetFallbackChunks();
        }
        lastGpuBatchesDrawn = renderedBatches;
        lastGpuBatchesQueued = queuedGpuBatches.size()
            + (activeGpuBuild == null ? 0 : 1)
            + gpuUploadPackingJobs.get() + pendingGpuUploads.size();
        lastTerrainRenderNanos = System.nanoTime() - started;
        int batchSize = gpuBatchSize(gpuCaveView);
        return new TerrainRenderStats(gpuBatches.size() * batchSize * batchSize,
            renderedChunks, renderedVertices,
            missingBakes, budgetFallbacks);
    }

    private void updatePerformanceState(long now) {
        if (lastRenderInvocationNanos != 0L) {
            long interval = Math.max(1L, Math.min(250_000_000L,
                now - lastRenderInvocationNanos));
            averageFrameIntervalNanos += (interval - averageFrameIntervalNanos) * 0.08;
        }
        lastRenderInvocationNanos = now;

        int requested = TerrainLodPolicy.performanceThrottleLevel(
            averageFrameIntervalNanos / 1_000_000.0,
            lastTerrainRenderNanos / 1_000_000.0);
        if (requested > performanceThrottle) {
            performanceThrottle++;
        } else if (requested < performanceThrottle
            && averageFrameIntervalNanos < 19_000_000.0
            && lastTerrainRenderNanos < 6_000_000L) {
            performanceThrottle--;
        }
        updateBakeConcurrency();
    }

    private void updateBakeConcurrency() {
        int desired = performanceThrottle <= 0 ? BAKE_THREADS
            : performanceThrottle == 1 ? Math.max(1, BAKE_THREADS / 2) : 1;
        if (bakeExecutor.getCorePoolSize() == desired
            && bakeExecutor.getMaximumPoolSize() == desired) return;
        if (desired > bakeExecutor.getMaximumPoolSize()) {
            bakeExecutor.setMaximumPoolSize(desired);
            bakeExecutor.setCorePoolSize(desired);
        } else {
            bakeExecutor.setCorePoolSize(desired);
            bakeExecutor.setMaximumPoolSize(desired);
        }
    }

    private void ensureGpuBatchWindow(FreeCamera3D camera, int centerChunkX, int centerChunkZ,
                                      boolean caveView, boolean textureDebugView,
                                      CaveCutaway requestedCutaway) {
        boolean modeChanged = caveView != gpuCaveView
            || textureDebugView != gpuTextureDebugView;
        boolean cutawayChanged = (caveView || requestedCutaway != null || gpuCaveCutaway != null)
            && !CaveCutaway.sameBucket(gpuCaveCutaway, requestedCutaway);
        int radius = caveView ? CAVE_RENDER_RADIUS_CHUNKS : renderDistanceChunks(camera);
        boolean windowChanged = gpuCameraChunkX == Integer.MIN_VALUE
            || Math.abs(centerChunkX - gpuCameraChunkX) >= 4
            || Math.abs(centerChunkZ - gpuCameraChunkZ) >= 4
            || cutawayChanged
            || radius != gpuVisibleRadius
            || (!caveView && (streamAnchorChunkX != gpuWindowAnchorX
                || streamAnchorChunkZ != gpuWindowAnchorZ));
        if (modeChanged) {
            clearGpuBatches();
            gpuCaveView = caveView;
            gpuTextureDebugView = textureDebugView;
            windowChanged = true;
        }
        if (!windowChanged) return;

        Set<Long> visible = new HashSet<>();
        int batchSize = gpuBatchSize(caveView);
        if (caveView) {
            // Caves must be locally complete and block-accurate. Retaining the
            // surface stream anchor here caused a distant 16x16 batch to consume
            // the cave budget before the camera's neighbouring chunks were baked.
            addGpuBatchSquare(visible, centerChunkX, centerChunkZ, radius, batchSize);
        } else {
            int anchorX = streamAnchorChunkX == Integer.MIN_VALUE ? centerChunkX : streamAnchorChunkX;
            int anchorZ = streamAnchorChunkZ == Integer.MIN_VALUE ? centerChunkZ : streamAnchorChunkZ;
            addGpuBatchSquare(visible, anchorX, anchorZ, radius, batchSize);
            addGpuBatchSquare(visible, centerChunkX, centerChunkZ, Math.min(radius, RENDER_DIST), batchSize);
        }
        visibleGpuBatchKeys = Set.copyOf(visible);

        if (cutawayChanged && !modeChanged) {
            // Only the batches touched by the old or new inspection bubble need
            // replacement. Keeping the rest of the cave window resident avoids a
            // full mesh churn every time the camera crosses a 16-block cell.
            activeGpuBuild = null;
            refreshCaveCutawayBatches(gpuCaveCutaway, requestedCutaway);
        }

        for (var iterator = gpuBatches.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<Long, TerrainGpuBatch> entry = iterator.next();
            if (visible.contains(entry.getKey())) continue;
            entry.getValue().close();
            removeDisplayedSteps(entry.getValue());
            iterator.remove();
        }

        List<Long> priority = new ArrayList<>(visible);
        priority.sort(Comparator
            .comparingLong((Long key) -> gpuBatchDistanceSq(
                key, centerChunkX, centerChunkZ, batchSize))
            .thenComparingLong(Long::longValue));
        for (long key : priority) {
            TerrainGpuBatch current = gpuBatches.get(key);
            long revision = gpuBatchRevisions.getOrDefault(key, 0L);
            if ((current == null || current.revision() != revision)
                && gpuBatchHasCachedChunk(key)) {
                queueGpuBatchPriority(key);
            }
        }

        // Camera movement only invalidates batches whose requested LOD signature
        // changed. The old broad refresh rebuilt every region in an 80-chunk square
        // every four chunks of movement, even when all selected LODs were stable.
        if (!caveView && gpuCameraChunkX != Integer.MIN_VALUE) {
            int refreshRadius = Math.min(radius, GPU_CAMERA_REFRESH_RADIUS_CHUNKS);
            Set<Long> nearby = new HashSet<>();
            Set<Long> lodInvalidations = new HashSet<>();
            addGpuBatchSquare(nearby, centerChunkX, centerChunkZ, refreshRadius, batchSize);
            for (long key : nearby) {
                TerrainGpuBatch current = gpuBatches.get(key);
                if (current == null) continue;
                long requestedSignature = gpuBatchLodSignature(key, camera,
                    current.displayedSteps());
                if (!TerrainLodPolicy.batchLodChanged(current.lodSignature(),
                    requestedSignature)) continue;
                lodInvalidations.add(key);
            }
            // Transition skirts are emitted only on east/south chunk edges. A
            // changed west/north batch edge therefore belongs to its neighbour.
            for (long key : List.copyOf(lodInvalidations)) {
                int batchX = ChunkPos.getX(key);
                int batchZ = ChunkPos.getZ(key);
                long west = ChunkPos.pack(batchX - 1, batchZ);
                long north = ChunkPos.pack(batchX, batchZ - 1);
                if (visible.contains(west)) lodInvalidations.add(west);
                if (visible.contains(north)) lodInvalidations.add(north);
            }
            for (long key : lodInvalidations) {
                gpuBatchRevisions.put(key, nextGpuBatchRevision.incrementAndGet());
                queueGpuBatchPriority(key);
            }
        }

        gpuCameraChunkX = centerChunkX;
        gpuCameraChunkZ = centerChunkZ;
        gpuCaveCutaway = requestedCutaway;
        gpuVisibleRadius = radius;
        gpuWindowAnchorX = streamAnchorChunkX;
        gpuWindowAnchorZ = streamAnchorChunkZ;
    }

    private void refreshCaveCutawayBatches(CaveCutaway previous, CaveCutaway current) {
        for (long key : visibleGpuBatchKeys) {
            if (!caveCutawayAffectsBatch(previous, key, gpuBatchSize(gpuCaveView))
                && !caveCutawayAffectsBatch(current, key, gpuBatchSize(gpuCaveView))) continue;
            gpuBatchRevisions.put(key, nextGpuBatchRevision.incrementAndGet());
            queueGpuBatchPriority(key);
        }
    }

    private static boolean caveCutawayAffectsBatch(CaveCutaway cutaway, long key, int batchSize) {
        if (cutaway == null) return false;
        int baseChunkX = ChunkPos.getX(key) * batchSize;
        int baseChunkZ = ChunkPos.getZ(key) * batchSize;
        for (int dx = 0; dx < batchSize; dx++) {
            for (int dz = 0; dz < batchSize; dz++) {
                if (cutaway.affectsChunk(baseChunkX + dx, baseChunkZ + dz)) return true;
            }
        }
        return false;
    }

    private static void addGpuBatchSquare(Set<Long> destination, int centerChunkX,
                                          int centerChunkZ, int radius, int batchSize) {
        int minX = Math.floorDiv(centerChunkX - radius, batchSize);
        int maxX = Math.floorDiv(centerChunkX + radius, batchSize);
        int minZ = Math.floorDiv(centerChunkZ - radius, batchSize);
        int maxZ = Math.floorDiv(centerChunkZ + radius, batchSize);
        for (int batchX = minX; batchX <= maxX; batchX++) {
            for (int batchZ = minZ; batchZ <= maxZ; batchZ++) {
                destination.add(ChunkPos.pack(batchX, batchZ));
            }
        }
    }

    private static long gpuBatchDistanceSq(long key, int centerChunkX, int centerChunkZ,
                                           int batchSize) {
        long chunkX = (long) ChunkPos.getX(key) * batchSize + batchSize / 2L;
        long chunkZ = (long) ChunkPos.getZ(key) * batchSize + batchSize / 2L;
        long dx = chunkX - centerChunkX;
        long dz = chunkZ - centerChunkZ;
        return dx * dx + dz * dz;
    }

    private boolean gpuBatchHasCachedChunk(long key) {
        int batchSize = gpuBatchSize(gpuCaveView);
        int baseChunkX = ChunkPos.getX(key) * batchSize;
        int baseChunkZ = ChunkPos.getZ(key) * batchSize;
        for (int dx = 0; dx < batchSize; dx++) {
            for (int dz = 0; dz < batchSize; dz++) {
                long chunkKey = ChunkPos.pack(baseChunkX + dx, baseChunkZ + dz);
                if (chunkCache.containsKey(chunkKey)
                    && (gpuCaveView || presentationFrontier.isRevealed(chunkKey))) return true;
            }
        }
        return false;
    }

    private long gpuBatchLodSignature(long key, FreeCamera3D camera,
                                      Map<Long, Integer> previousSteps) {
        int baseChunkX = ChunkPos.getX(key) * GPU_BATCH_SIZE_CHUNKS;
        int baseChunkZ = ChunkPos.getZ(key) * GPU_BATCH_SIZE_CHUNKS;
        long signature = 0xcbf29ce484222325L;
        for (int dx = 0; dx < GPU_BATCH_SIZE_CHUNKS; dx++) {
            for (int dz = 0; dz < GPU_BATCH_SIZE_CHUNKS; dz++) {
                long chunkKey = ChunkPos.pack(baseChunkX + dx, baseChunkZ + dz);
                ChunkData chunk = chunkCache.get(chunkKey);
                if (chunk == null) continue;
                if (!presentationFrontier.isRevealed(chunkKey)) continue;
                int sourceStep = sampleStep(camera, chunk);
                int displayStep = sourceStep;
                if (sourceStep == 1 && isExactSurface(chunk)) {
                    double horizontalSq = chunkDistanceSq(camera, chunk.chunkX, chunk.chunkZ);
                    displayStep = TerrainLodPolicy.exactDisplayStepWithHysteresis(
                        previousSteps.get(chunkKey), Math.sqrt(horizontalSq),
                        chunk.chunkX, chunk.chunkZ);
                }
                signature ^= chunkKey;
                signature *= 0x100000001b3L;
                signature ^= displayStep;
                signature *= 0x100000001b3L;
            }
        }
        return signature;
    }

    private static int gpuBatchSize(boolean caveView) {
        return caveView ? CAVE_GPU_BATCH_SIZE_CHUNKS : GPU_BATCH_SIZE_CHUNKS;
    }

    private void continueGpuBatchBuild(FreeCamera3D camera, long seed, int dimension,
                                       boolean caveView, boolean textureDebugView,
                                       CaveCutaway caveCutaway) {
        if (activeGpuBuild == null) {
            if (gpuUploadPackingJobs.get() + pendingGpuUploads.size()
                >= MAX_PENDING_GPU_UPLOADS) return;
            Long key = pollGpuBatch();
            if (key == null) return;
            activeGpuBuild = beginGpuBuild(key, camera, caveView, caveCutaway);
        }

        TerrainGpuBuild build = activeGpuBuild;
        if (build == null) return;
        long deadline = System.nanoTime() + Math.max(350_000L,
            GPU_BUILD_BUDGET_NANOS / (performanceThrottle + 1L));
        int maximumChunks = TerrainLodPolicy.adaptiveBackgroundBudget(
            GPU_BUILD_MAX_CHUNKS_PER_FRAME, performanceThrottle,
            GPU_BUILD_MIN_CHUNKS_PER_FRAME);
        int bakeBudget = TerrainLodPolicy.adaptiveBackgroundBudget(
            BAKES_PER_FRAME, performanceThrottle, 1);
        int processed = 0;
        int bakesSubmitted = 0;
        while (build.nextChunk() < build.chunks().size()
            && processed < maximumChunks
            && (processed < GPU_BUILD_MIN_CHUNKS_PER_FRAME || System.nanoTime() < deadline)) {
            ChunkData chunk = build.chunks().get(build.nextChunk());
            build.nextChunk(build.nextChunk() + 1);
            processed++;
            if (!caveView) {
                build.remainingMinimumVertices(build.remainingMinimumVertices()
                    - minimumVisibleSurfaceVertices(chunk, sampleStep(camera, chunk)));
            }
            int remainingBudget = (int) Math.max(0L, Math.min(Integer.MAX_VALUE,
                (long) GPU_BATCH_MAX_VERTICES - build.vertexCount()
                    - build.remainingMinimumVertices()));
            bakesSubmitted += appendChunkToGpuBuild(build, camera, seed, dimension, chunk,
                caveView, textureDebugView, remainingBudget,
                Math.max(0, bakeBudget - bakesSubmitted));
        }
        if (build.nextChunk() < build.chunks().size()) return;

        if (!build.skirtsAdded() && !caveView
            && build.vertexCount() < GPU_BATCH_MAX_VERTICES) {
            build.addVertices(renderGpuBuildTransitionSkirts(build, dimension,
                GPU_BATCH_MAX_VERTICES - build.vertexCount()));
            build.skirtsAdded(true);
        }
        if (scheduleGpuUploadPacking(build)) activeGpuBuild = null;
    }

    private TerrainGpuBuild beginGpuBuild(long key, FreeCamera3D camera, boolean caveView) {
        return beginGpuBuild(key, camera, caveView, null);
    }

    private TerrainGpuBuild beginGpuBuild(long key, FreeCamera3D camera, boolean caveView,
                                          CaveCutaway caveCutaway) {
        int batchSize = gpuBatchSize(caveView);
        int baseChunkX = ChunkPos.getX(key) * batchSize;
        int baseChunkZ = ChunkPos.getZ(key) * batchSize;
        List<ChunkData> chunks = new ArrayList<>(batchSize * batchSize);
        for (int dx = 0; dx < batchSize; dx++) {
            for (int dz = 0; dz < batchSize; dz++) {
                long chunkKey = ChunkPos.pack(baseChunkX + dx, baseChunkZ + dz);
                ChunkData chunk = chunkCache.get(chunkKey);
                if (!caveView && !presentationFrontier.isRevealed(chunkKey)) continue;
                if (chunk != null) chunks.add(chunk);
            }
        }
        chunks.sort(Comparator.comparingDouble(chunk -> chunkDistanceSq(
            camera, chunk.chunkX, chunk.chunkZ)));
        TerrainGpuBatch previous = gpuBatches.get(key);
        Map<Long, Integer> previousSteps = previous == null
            ? Map.of() : previous.displayedSteps();
        Map<Long, Integer> requestedSteps = new java.util.HashMap<>();
        long minimumVertices = 0L;
        if (!caveView) {
            for (ChunkData chunk : chunks) {
                minimumVertices += minimumVisibleSurfaceVertices(chunk, sampleStep(camera, chunk));
                requestedSteps.put(ChunkPos.pack(chunk.chunkX, chunk.chunkZ),
                    requestedDisplayStep(camera, chunk, previousSteps));
            }
        }
        long lodSignature = caveView ? 0L
            : gpuBatchLodSignature(key, camera, previousSteps);
        BakingSink solid = new BakingSink();
        BakingSink cutout = new BakingSink();
        BakingSink fluid = new BakingSink();
        return new TerrainGpuBuild(key, gpuBatchRevisions.getOrDefault(key, 0L),
            chunks, solid, cutout, fluid, requestedSteps, lodSignature,
            minimumVertices, caveCutaway);
    }

    private int requestedDisplayStep(FreeCamera3D camera, ChunkData chunk,
                                     Map<Long, Integer> previousSteps) {
        int sourceStep = sampleStep(camera, chunk);
        if (sourceStep != 1 || !isExactSurface(chunk)) return sourceStep;
        double horizontalSq = chunkDistanceSq(camera, chunk.chunkX, chunk.chunkZ);
        return TerrainLodPolicy.exactDisplayStepWithHysteresis(
            previousSteps.get(ChunkPos.pack(chunk.chunkX, chunk.chunkZ)),
            Math.sqrt(horizontalSq), chunk.chunkX, chunk.chunkZ);
    }

    private int appendChunkToGpuBuild(TerrainGpuBuild build, FreeCamera3D camera,
                                      long seed, int dimension, ChunkData chunk,
                                      boolean caveView, boolean textureDebugView,
                                      int vertexBudget, int bakeBudget) {
        long key = ChunkPos.pack(chunk.chunkX, chunk.chunkZ);
        BakedChunk baked = bakedCache.get(key);
        int requestedDataStep = sampleStep(camera, chunk);
        int requestedDisplayStep = build.requestedSteps().getOrDefault(key,
            displayStep(camera, chunk, requestedDataStep));
        boolean exactRequired = requestedDataStep == 1;
        // Keep exact refinement queued, but display the progressively generated
        // surface until it arrives. Hiding valid coarse data made the camera-local
        // area remain blank for seconds while vanilla chunks generated.
        if (!caveView && exactRequired && !isExactSurface(chunk)) {
            staleUpgradeChunks.add(key);
            queueExactUpgrade(key);
        }

        int submitted = 0;
        boolean includeCaves = caveView
            || TerrainLodPolicy.shouldPrebakeUndergroundGeometry(
                dimension, chunkDistanceChunks(camera, chunk.chunkX, chunk.chunkZ),
                CAVE_RENDER_RADIUS_CHUNKS, isExactSurface(chunk));
        boolean bakeStale = isBakeStale(baked, seed, dimension, chunk, camera,
            textureDebugView, includeCaves);
        if (bakeStale) {
            staleUpgradeChunks.add(key);
            boolean sourceReady = caveView || chunk.generatedStep <= requestedDataStep;
            if (sourceReady && bakeBudget > 0
                && submitBake(seed, dimension, chunk, camera, textureDebugView, includeCaves)) {
                submitted = 1;
            }
            // An empty cave region must not become final just because every
            // worker slot was occupied when it was first visited. The completed
            // bake will also invalidate this batch, but this retry covers a
            // temporarily exhausted admission budget and retained old bakes.
            if (caveView && submitted == 0) queueGpuBatchPriority(build.key());
        }

        if (baked == null) {
            build.incrementMissingBakes();
            if (!caveView) {
                int fallbackStep = fallbackDisplayStep(requestedDataStep,
                    requestedDisplayStep, vertexBudget);
                BakingSink fallbackSolid = build.caveCutaway() == null
                    ? build.solid() : new BakingSink();
                int vertices = renderMinimumSurfaceFallback(fallbackSolid,
                    build.fluid(), chunk, dimension, requestedDataStep,
                    fallbackStep, vertexBudget);
                if (fallbackSolid != build.solid()) {
                    int filtered = build.solid().appendFiltered(fallbackSolid.finish(),
                        build.caveCutaway());
                    vertices = filtered;
                }
                if (vertices > 0) {
                    build.addVertices(vertices);
                    build.incrementRenderedChunks();
                    build.displayedSteps().put(key, fallbackStep);
                    frameBudgetFallbackChunks.add(key);
                }
            }
            return submitted;
        }

        if (caveView) {
            int vertices = baked.caveVertexCount();
            if (vertices > 0 && vertices <= vertexBudget) {
                // Cave meshes stay camera-independent in the bake cache. Filtering
                // their quads here makes a moving focus corridor cheap: no carver or
                // block resampling is needed as the orbit advances.
                int visibleVertices = build.solid().appendFiltered(
                    baked.caveSolid(), build.caveCutaway());
                build.addVertices(visibleVertices);
                if (visibleVertices > 0) build.incrementRenderedChunks();
            }
            return submitted;
        }

        // Inside the one-block window, an older coarse LOD is not a valid
        // progressive result. Mixing it with completed step-1 chunks produces
        // the large tiles that appeared to move under the camera.
        int maximumStep = requestedDisplayStep == 1 ? 1
            : exactRequired ? TerrainLodPolicy.EXACT_DISPLAY_MAX_STEP
            : TerrainLodPolicy.COARSE_SAMPLE_STEP;
        SurfaceLod selected = baked.surfaceLodForBudget(requestedDisplayStep,
            maximumStep, vertexBudget);
        if (selected != null && selected.vertexCount() > 0) {
            // Once vegetation is resolved, retain it when the camera moves. The
            // generation radius limits new work, not already available geometry.
            int naturalVertices = baked.naturalSolid().px().length;
            BakingSink naturalTarget = build.cutout();
            boolean includeNatural = naturalVertices == 0
                || selected.vertexCount() + naturalVertices <= vertexBudget;
            if (naturalVertices > 0 && !includeNatural) {
                naturalFeatureBudgetSkips.incrementAndGet();
            }
            // During a structure orbit, remove terrain quads in the same
            // camera-to-target tunnel used by the cave shell. This keeps the
            // preview readable without changing the persistent terrain bake.
            if (build.caveCutaway() != null) {
                build.solid().appendFiltered(selected.solid(), build.caveCutaway());
                if (includeNatural) {
                    naturalTarget.appendFiltered(baked.naturalSolid(), build.caveCutaway());
                }
            } else {
                build.solid().append(selected.solid());
                if (includeNatural) naturalTarget.append(baked.naturalSolid());
            }
            build.fluid().append(selected.fluid());
            build.addVertices(selected.vertexCount()
                + (includeNatural ? naturalVertices : 0));
            build.incrementRenderedChunks();
            build.displayedSteps().put(key, selected.step());
            return submitted;
        }

        boolean genuinelyEmpty = baked.hasNoSurfaceGeometry() && !hasSurfaceData(chunk);
        if (genuinelyEmpty) {
            terrainDiagnostic("empty-surface-bake", seed, dimension, chunk.chunkX,
                chunk.chunkZ, baked.step(), chunk, null, "bake emitted no visible surface quads");
            return submitted;
        }
        int fallbackStep = fallbackDisplayStep(requestedDataStep,
            requestedDisplayStep, vertexBudget);
        BakingSink fallbackSolid = build.caveCutaway() == null
            ? build.solid() : new BakingSink();
        int vertices = renderMinimumSurfaceFallback(fallbackSolid,
            build.fluid(), chunk, dimension, requestedDataStep,
            fallbackStep, vertexBudget);
        if (fallbackSolid != build.solid()) {
            vertices = build.solid().appendFiltered(fallbackSolid.finish(),
                build.caveCutaway());
        }
        if (vertices > 0) {
            build.addVertices(vertices);
            build.incrementRenderedChunks();
            build.incrementBudgetFallbackChunks();
            build.displayedSteps().put(key, fallbackStep);
            frameBudgetFallbackChunks.add(key);
        }
        return submitted;
    }

    private int renderGpuBuildTransitionSkirts(TerrainGpuBuild build, int dimension,
                                               int vertexBudget) {
        if (vertexBudget < 4) return 0;
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        int emitted = 0;
        for (ChunkData chunk : build.chunks()) {
            long key = ChunkPos.pack(chunk.chunkX, chunk.chunkZ);
            Integer step = build.displayedSteps().get(key);
            if (step == null) continue;
            ChunkData east = chunkCache.get(ChunkPos.pack(chunk.chunkX + 1, chunk.chunkZ));
            Integer eastStep = displayedStepForBuild(build,
                ChunkPos.pack(chunk.chunkX + 1, chunk.chunkZ));
            if (east != null && TerrainLodPolicy.requiresTransitionSkirt(step, eastStep)) {
                emitted += renderLodBoundary(build.solid(), atlas, chunk, east,
                    true, Math.min(step, eastStep), vertexBudget - emitted);
            }
            if (emitted + 4 > vertexBudget) break;
            ChunkData south = chunkCache.get(ChunkPos.pack(chunk.chunkX, chunk.chunkZ + 1));
            Integer southStep = displayedStepForBuild(build,
                ChunkPos.pack(chunk.chunkX, chunk.chunkZ + 1));
            if (south != null && TerrainLodPolicy.requiresTransitionSkirt(step, southStep)) {
                emitted += renderLodBoundary(build.solid(), atlas, chunk, south,
                    false, Math.min(step, southStep), vertexBudget - emitted);
            }
            if (emitted + 4 > vertexBudget) break;
        }
        return emitted;
    }

    private Integer displayedStepForBuild(TerrainGpuBuild build, long chunkKey) {
        Integer local = build.displayedSteps().get(chunkKey);
        return local != null ? local : displayedSurfaceSteps.get(chunkKey);
    }

    private boolean scheduleGpuUploadPacking(TerrainGpuBuild build) {
        if (gpuUploadPackingJobs.get() + pendingGpuUploads.size()
            >= MAX_PENDING_GPU_UPLOADS) return false;
        long lifecycle = gpuLifecycleGeneration;
        gpuUploadPackingJobs.incrementAndGet();
        gpuUploadPackingExecutor.execute(() -> {
            long started = System.nanoTime();
            PreparedGpuUpload prepared = null;
            try {
                BakedGeometry solid = build.solid().finish();
                BakedGeometry cutout = build.cutout().finish();
                BakedGeometry fluid = build.fluid().finish();
                prepared = new PreparedGpuUpload(build.key(), build.revision(), lifecycle,
                    PackedGpuGeometry.pack(solid), PackedGpuGeometry.pack(cutout),
                    PackedGpuGeometry.pack(fluid),
                    build.renderedChunks(), build.vertexCount(), build.missingBakes(),
                    build.budgetFallbackChunks(), Map.copyOf(build.displayedSteps()),
                    build.lodSignature(), System.nanoTime() - started);
                if (lifecycle == gpuLifecycleGeneration) {
                    pendingGpuUploads.add(prepared);
                    prepared = null;
                }
            } finally {
                if (prepared != null) prepared.close();
                gpuUploadPackingJobs.decrementAndGet();
            }
        });
        return true;
    }

    private void processPendingGpuUploads() {
        long uploadedBytes = 0L;
        long uploadNanos = 0L;
        long packedNanos = 0L;
        while (true) {
            PreparedGpuUpload prepared = pendingGpuUploads.peek();
            if (prepared == null) break;
            // A cave batch can legitimately finish before any of its asynchronous
            // chunk bakes have produced geometry. Such a batch has no buffers to
            // upload. Leaving it at the head of the queue made admitGpuUpload()
            // reject it forever and blocked every later, non-empty cave upload.
            if (!TerrainLodPolicy.gpuUploadHasBuffers(prepared.byteSize())) {
                pendingGpuUploads.poll();
                emptyGpuUploadsDiscarded.incrementAndGet();
                prepared.close();
                continue;
            }
            if (!TerrainLodPolicy.admitGpuUpload(uploadedBytes, prepared.byteSize(),
                GPU_UPLOAD_BUDGET_BYTES)) break;
            pendingGpuUploads.poll();
            boolean sameLifecycle = prepared.lifecycleGeneration() == gpuLifecycleGeneration;
            boolean visible = visibleGpuBatchKeys.contains(prepared.key());
            long currentRevision = gpuBatchRevisions.getOrDefault(prepared.key(), 0L);
            boolean revisionMatches = currentRevision == prepared.revision();
            if (!TerrainLodPolicy.admitProgressiveGpuBatch(
                sameLifecycle, visible, revisionMatches)) {
                prepared.close();
                continue;
            }
            if (!revisionMatches) {
                // Keep this same-view result drawable while a newer revision is
                // queued. Rejecting every stale-in-flight upload caused permanent
                // black terrain whenever generation changed a region continuously.
                queueGpuBatch(prepared.key());
            }
            TerrainGpuBatch displayed = gpuBatches.get(prepared.key());
            long displayedRevision = displayed == null ? -1L : displayed.revision();
            if (!TerrainLodPolicy.progressiveGpuBatchDoesNotRegress(
                displayedRevision, prepared.revision())) {
                prepared.close();
                continue;
            }
            long started = System.nanoTime();
            PersistentGpuGeometry solid = uploadPackedGeometry(
                "Seed Explorer terrain solid", prepared.solid());
            PersistentGpuGeometry cutout;
            try {
                cutout = uploadPackedGeometry("Seed Explorer terrain cutout", prepared.cutout());
            } catch (RuntimeException | Error error) {
                solid.close();
                prepared.close();
                throw error;
            }
            PersistentGpuGeometry fluid;
            try {
                fluid = uploadPackedGeometry("Seed Explorer terrain fluid", prepared.fluid());
            } catch (RuntimeException | Error error) {
                solid.close();
                cutout.close();
                prepared.close();
                throw error;
            }
            TerrainGpuBatch batch = new TerrainGpuBatch(prepared.key(), prepared.revision(),
                prepared.lodSignature(), solid.vertices(), solid.indices(), solid.indexCount(),
                cutout.vertices(), cutout.indices(), cutout.indexCount(),
                fluid.vertices(), fluid.indices(), fluid.indexCount(), prepared.renderedChunks(),
                prepared.vertexCount(), prepared.missingBakes(), prepared.budgetFallbackChunks(),
                prepared.displayedSteps());
            TerrainGpuBatch previous = gpuBatches.put(prepared.key(), batch);
            if (previous != null) {
                previous.close();
                removeDisplayedSteps(previous);
            }
            displayedSurfaceSteps.putAll(batch.displayedSteps());
            uploadedBytes += prepared.byteSize();
            packedNanos += prepared.packNanos();
            uploadNanos += System.nanoTime() - started;
            prepared.close();
        }
        lastGpuUploadBytes = uploadedBytes;
        lastGpuBuildNanos = packedNanos;
        lastGpuUploadNanos = uploadNanos;
    }

    private PersistentGpuGeometry uploadPackedGeometry(String name,
                                                       PackedGpuGeometry geometry) {
        if (geometry.isEmpty()) return PersistentGpuGeometry.EMPTY;
        GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
            () -> name + " vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
            geometry.vertices());
        try {
            GpuBuffer indexBuffer = RenderSystem.getDevice().createBuffer(
                () -> name + " indices", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
                geometry.indices());
            return new PersistentGpuGeometry(vertexBuffer, indexBuffer, geometry.indexCount());
        } catch (RuntimeException | Error error) {
            vertexBuffer.close();
            throw error;
        }
    }

    private PersistentGpuGeometry uploadPersistentGeometry(String name,
                                                           BakedGeometry geometry) {
        if (geometry.isEmpty()) return PersistentGpuGeometry.EMPTY;

        ByteBuffer vertices = geometry.vertexBuffer();
        ByteBuffer indices = geometry.indexBuffer();
        try {
            GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
                () -> name + " vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                vertices);
            try {
                GpuBuffer indexBuffer = RenderSystem.getDevice().createBuffer(
                    () -> name + " indices",
                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
                    indices);
                return new PersistentGpuGeometry(vertexBuffer, indexBuffer,
                    geometry.indexCount());
            } catch (RuntimeException | Error error) {
                vertexBuffer.close();
                throw error;
            }
        } finally {
            MemoryUtil.memFree(vertices);
            MemoryUtil.memFree(indices);
        }
    }

    private void renderDynamicGeometry(RenderPipeline pipeline, BlockTextureAtlas atlas,
                                       BakedGeometry geometry) {
        if (geometry.isEmpty() || atlas.texture() == null) return;
        PersistentGpuGeometry uploaded = uploadPersistentGeometry(
            "Seed Explorer dynamic overlay", geometry);
        try {
            renderGpuGeometry(pipeline, atlas, uploaded.vertices(), uploaded.indices(),
                uploaded.indexCount(), "Seed Explorer dynamic overlay");
        } finally {
            uploaded.close();
        }
    }

    private void renderGpuGeometry(RenderPipeline pipeline, GpuTextureView textureView,
                                   GpuSampler sampler, BakedGeometry geometry,
                                   String passName) {
        if (geometry == null || geometry.isEmpty() || textureView == null || sampler == null) return;
        PersistentGpuGeometry uploaded = uploadPersistentGeometry(passName, geometry,
            pipeline == SeedExplorerRenderPipelines.WORLD_VANILLA_LIGHTMAP);
        try {
            renderGpuGeometry(pipeline, textureView, sampler, uploaded.vertices(),
                uploaded.indices(), uploaded.indexCount(), passName);
        } finally {
            uploaded.close();
        }
    }

    private PersistentGpuGeometry uploadPersistentGeometry(String name, BakedGeometry geometry,
                                                            boolean vanillaLightmap) {
        if (!vanillaLightmap) return uploadPersistentGeometry(name, geometry);
        if (geometry.isEmpty()) return PersistentGpuGeometry.EMPTY;
        ByteBuffer vertices = geometry.vanillaVertexBuffer();
        ByteBuffer indices = geometry.indexBuffer();
        try {
            GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
                () -> name + " vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, vertices);
            try {
                GpuBuffer indexBuffer = RenderSystem.getDevice().createBuffer(
                    () -> name + " indices", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, indices);
                return new PersistentGpuGeometry(vertexBuffer, indexBuffer, geometry.indexCount());
            } catch (RuntimeException | Error error) {
                vertexBuffer.close();
                throw error;
            }
        } finally {
            MemoryUtil.memFree(vertices);
            MemoryUtil.memFree(indices);
        }
    }

    private int renderGpuBatches(RenderPipeline pipeline, BlockTextureAtlas atlas,
                                 FreeCamera3D camera, TerrainGpuLayer layer) {
        if (gpuBatches.isEmpty() || atlas.texture() == null) return 0;
        FrustumIntersection frustum = new FrustumIntersection(
            new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix()));
        int drawable = 0;
        int culled = 0;
        for (TerrainGpuBatch batch : gpuBatches.values()) {
            if (layer.indexCount(batch) <= 0) continue;
            if (!gpuBatchInFrustum(batch.key(), frustum, gpuBatchSize(gpuCaveView))) {
                culled++;
                continue;
            }
            drawable++;
        }
        if (layer == TerrainGpuLayer.SOLID) lastGpuBatchesCulled = culled;
        if (drawable == 0) return 0;

        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = activeRenderTarget(mc);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        RenderPass pass = null;
        modelView.pushMatrix();
        try {
            GpuBufferSlice meshData = meshUniforms.write(modelView);
            pass = pipeline.wantsDepthTexture()
                ? RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Seed Explorer persistent terrain", target.getColorTextureView(),
                    Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())
                : RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Seed Explorer persistent terrain", target.getColorTextureView(),
                    Optional.empty());
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            pass.setUniform("MeshData", meshData);
            pass.setUniform("u_Texture", atlas.texture().getTextureView(), atlas.texture().getSampler());
            for (TerrainGpuBatch batch : gpuBatches.values()) {
                if (!gpuBatchInFrustum(batch.key(), frustum, gpuBatchSize(gpuCaveView))) continue;
                GpuBuffer vertices = layer.vertices(batch);
                GpuBuffer indices = layer.indices(batch);
                int indexCount = layer.indexCount(batch);
                if (vertices == null || indices == null || indexCount <= 0) continue;
                pass.setVertexBuffer(0, vertices.slice());
                pass.setIndexBuffer(indices, IndexType.INT);
                pass.drawIndexed(indexCount, 1, 0, 0, 0);
            }
        } finally {
            if (pass != null) pass.close();
            modelView.popMatrix();
        }
        return drawable;
    }

    private static boolean gpuBatchInFrustum(long key, FrustumIntersection frustum, int batchSize) {
        float minX = ChunkPos.getX(key) * batchSize * (float) CHUNK_SIZE;
        float minZ = ChunkPos.getZ(key) * batchSize * (float) CHUNK_SIZE;
        float maxX = minX + batchSize * (float) CHUNK_SIZE;
        float maxZ = minZ + batchSize * (float) CHUNK_SIZE;
        return frustum.testAab(minX, GPU_BATCH_MIN_Y, minZ,
            maxX, GPU_BATCH_MAX_Y, maxZ);
    }

    private void renderGpuGeometry(RenderPipeline pipeline, BlockTextureAtlas atlas,
                                   GpuBuffer vertices, GpuBuffer indices, int indexCount,
                                   String passName) {
        if (atlas == null || atlas.texture() == null) return;
        renderGpuGeometry(pipeline, atlas.texture().getTextureView(), atlas.texture().getSampler(),
            vertices, indices, indexCount, passName);
    }

    private void renderGpuGeometry(RenderPipeline pipeline, GpuTextureView textureView,
                                   GpuSampler sampler, GpuBuffer vertices, GpuBuffer indices,
                                   int indexCount, String passName) {
        if (vertices == null || indices == null || indexCount <= 0 || textureView == null || sampler == null) return;

        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = activeRenderTarget(mc);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        RenderPass pass = null;
        modelView.pushMatrix();
        try {
            GpuBufferSlice meshData = meshUniforms.write(modelView);
            pass = pipeline.wantsDepthTexture()
                ? RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> passName, target.getColorTextureView(), Optional.empty(),
                    target.getDepthTextureView(), OptionalDouble.empty())
                : RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> passName, target.getColorTextureView(), Optional.empty());
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            pass.setUniform("MeshData", meshData);
            pass.setUniform("u_Texture", textureView, sampler);
            if (pipeline == SeedExplorerRenderPipelines.WORLD_VANILLA_LIGHTMAP) {
                GpuTextureView lightmap = mc.gameRenderer.levelLightmap();
                if (lightmap != null) {
                    pass.setUniform("u_Lightmap", lightmap,
                        RenderSystem.getSamplerCache().getSampler(
                            AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                            FilterMode.NEAREST, FilterMode.NEAREST, false));
                }
            }
            pass.setVertexBuffer(0, vertices.slice());
            pass.setIndexBuffer(indices, IndexType.INT);
            pass.drawIndexed(indexCount, 1, 0, 0, 0);
        } finally {
            if (pass != null) pass.close();
            modelView.popMatrix();
        }
    }

    private void queueGpuBatch(long key) {
        if (!visibleGpuBatchKeys.contains(key)) return;
        if (queuedGpuBatches.add(key)) pendingGpuBatches.add(key);
    }

    private void queueGpuBatchPriority(long key) {
        if (!visibleGpuBatchKeys.contains(key)) return;
        queuedGpuBatches.add(key);
        if (queuedPriorityGpuBatches.add(key)) priorityGpuBatches.addLast(key);
    }

    private Long pollGpuBatch() {
        while (!priorityGpuBatches.isEmpty()) {
            long key = priorityGpuBatches.removeFirst();
            queuedPriorityGpuBatches.remove(key);
            if (queuedGpuBatches.remove(key) && visibleGpuBatchKeys.contains(key)) return key;
        }
        while (true) {
            Long key = pendingGpuBatches.poll();
            if (key == null) return null;
            if (queuedGpuBatches.remove(key) && visibleGpuBatchKeys.contains(key)) return key;
        }
    }

    private void invalidateGpuBatchForChunk(int chunkX, int chunkZ) {
        invalidateGpuBatchForChunk(chunkX, chunkZ, false);
    }

    /** Coalesces a completed spiral segment so a 16x16 batch is rebuilt once per frame. */
    private void drainPresentationReveals() {
        Set<Long> batches = new HashSet<>();
        Long chunkKey;
        int drained = 0;
        while (drained < MAX_PRESENTATION_REVEALS_PER_FRAME
            && (chunkKey = presentationFrontier.pollNewlyRevealed()) != null) {
            batches.add(ChunkPos.pack(
                Math.floorDiv(ChunkPos.getX(chunkKey), GPU_BATCH_SIZE_CHUNKS),
                Math.floorDiv(ChunkPos.getZ(chunkKey), GPU_BATCH_SIZE_CHUNKS)));
            drained++;
        }
        if (batches.isEmpty()) return;
        for (long batchKey : batches) {
            gpuBatchRevisions.put(batchKey, nextGpuBatchRevision.incrementAndGet());
            queueGpuBatchPriority(batchKey);
        }
        chunkRevision.incrementAndGet();
    }

    private void invalidateGpuBatchForChunk(int chunkX, int chunkZ, boolean priority) {
        int batchSize = gpuBatchSize(gpuCaveView);
        long key = ChunkPos.pack(Math.floorDiv(chunkX, batchSize),
            Math.floorDiv(chunkZ, batchSize));
        gpuBatchRevisions.put(key, nextGpuBatchRevision.incrementAndGet());
        if (priority) queueGpuBatchPriority(key);
        else queueGpuBatch(key);
    }

    private void queueGpuBatchForChunkPriority(int chunkX, int chunkZ) {
        int batchSize = gpuBatchSize(gpuCaveView);
        long key = ChunkPos.pack(Math.floorDiv(chunkX, batchSize),
            Math.floorDiv(chunkZ, batchSize));
        queueGpuBatchPriority(key);
    }

    private void clearGpuBatches() {
        gpuLifecycleGeneration++;
        activeGpuBuild = null;
        for (TerrainGpuBatch batch : gpuBatches.values()) batch.close();
        gpuBatches.clear();
        pendingGpuBatches.clear();
        queuedGpuBatches.clear();
        priorityGpuBatches.clear();
        queuedPriorityGpuBatches.clear();
        PreparedGpuUpload pending;
        while ((pending = pendingGpuUploads.poll()) != null) pending.close();
        visibleGpuBatchKeys = Set.of();
        displayedSurfaceSteps.clear();
        gpuCameraChunkX = Integer.MIN_VALUE;
        gpuCameraChunkZ = Integer.MIN_VALUE;
        gpuCaveCutaway = null;
        gpuVisibleRadius = -1;
        gpuWindowAnchorX = Integer.MIN_VALUE;
        gpuWindowAnchorZ = Integer.MIN_VALUE;
        gpuRenderOriginX = Double.NaN;
        gpuRenderOriginZ = Double.NaN;
    }

    /**
     * Refreshes only the currently retained 16x16 regions after bulk generation.
     * Existing buffers stay drawable until their replacements upload, avoiding a
     * synchronous GPU-resource teardown and a blank first frame after Stop.
     */
    private void refreshVisibleGpuBatchesAfterBulk() {
        activeGpuBuild = null;
        pendingGpuBatches.clear();
        queuedGpuBatches.clear();
        priorityGpuBatches.clear();
        queuedPriorityGpuBatches.clear();
        for (long key : visibleGpuBatchKeys) {
            gpuBatchRevisions.put(key, nextGpuBatchRevision.incrementAndGet());
            if (gpuBatchHasCachedChunk(key)) queueGpuBatchPriority(key);
        }
    }

    private void removeDisplayedSteps(TerrainGpuBatch batch) {
        for (Map.Entry<Long, Integer> entry : batch.displayedSteps().entrySet()) {
            displayedSurfaceSteps.remove(entry.getKey(), entry.getValue());
        }
    }

    private boolean trimChunkCacheToBudgetLocked(int centerChunkX, int centerChunkZ) {
        if (chunkCache.size() <= MAX_CACHED_CHUNKS) return false;
        int targetSize = MAX_CACHED_CHUNKS - CHUNK_CACHE_TRIM_BATCH;
        int excess = chunkCache.size() - targetSize;
        int anchorX = streamAnchorChunkX == Integer.MIN_VALUE ? centerChunkX : streamAnchorChunkX;
        int anchorZ = streamAnchorChunkZ == Integer.MIN_VALUE ? centerChunkZ : streamAnchorChunkZ;
        int cameraProtectRadius = RENDER_DIST + STREAM_ANCHOR_REGION_SIZE;
        PriorityQueue<ChunkData> candidates = new PriorityQueue<>(excess,
            (a, b) -> compareChunkEvictionPriority(a, b, centerChunkX, centerChunkZ,
                anchorX, anchorZ, cameraProtectRadius));
        for (ChunkData chunk : chunkCache.values()) {
            if (candidates.size() < excess) {
                candidates.add(chunk);
                continue;
            }
            ChunkData weakest = candidates.peek();
            if (weakest != null && compareChunkEvictionPriority(chunk, weakest,
                centerChunkX, centerChunkZ, anchorX, anchorZ, cameraProtectRadius) > 0) {
                candidates.poll();
                candidates.add(chunk);
            }
        }
        boolean removed = false;
        for (ChunkData chunk : candidates) {
            long key = ChunkPos.pack(chunk.chunkX, chunk.chunkZ);
            if (chunkCache.remove(key, chunk)) {
                removed = true;
            }
        }
        return removed;
    }

    /** Positive means the first chunk is a better eviction candidate. */
    private static int compareChunkEvictionPriority(ChunkData first, ChunkData second,
                                                     int cameraX, int cameraZ,
                                                     int anchorX, int anchorZ,
                                                     int cameraProtectRadius) {
        boolean firstProtected = TerrainLodPolicy.retentionRing(cameraX, cameraZ,
            first.chunkX, first.chunkZ) <= cameraProtectRadius;
        boolean secondProtected = TerrainLodPolicy.retentionRing(cameraX, cameraZ,
            second.chunkX, second.chunkZ) <= cameraProtectRadius;
        if (firstProtected != secondProtected) return firstProtected ? -1 : 1;
        int firstRing = TerrainLodPolicy.retentionRing(anchorX, anchorZ,
            first.chunkX, first.chunkZ);
        int secondRing = TerrainLodPolicy.retentionRing(anchorX, anchorZ,
            second.chunkX, second.chunkZ);
        int ring = Integer.compare(firstRing, secondRing);
        if (ring != 0) return ring;
        int x = Integer.compare(first.chunkX, second.chunkX);
        return x != 0 ? x : Integer.compare(first.chunkZ, second.chunkZ);
    }

    private void storeBakedChunk(WorkKey workKey, long key, BakedChunk baked) {
        synchronized (bakedCache) {
            if (!isCurrent(workKey)) {
                terrainDiagnostic("bake-discarded-stale-world", workKey.seed(), workKey.dimension(),
                    baked.chunkX(), baked.chunkZ(), baked.step(), null, null,
                    "workGeneration=" + workKey.generation() + " currentGeneration=" + worldGeneration);
                return;
            }
            BakedChunk previous = bakedCache.put(key, baked);
            long bytes = baked.estimatedBytes() - (previous == null ? 0 : previous.estimatedBytes());
            bakedCacheBytes.addAndGet(bytes);
            boolean presentationVisible = gpuCaveView
                || presentationFrontier.isRevealed(key);
            if (presentationVisible
                && TerrainLodPolicy.bakeAffectsActiveView(gpuCaveView, baked.cavesIncluded())) {
                invalidateGpuBatchForChunk(baked.chunkX(), baked.chunkZ(),
                    gpuCaveView && baked.cavesIncluded());
            } else if (presentationVisible && gpuCaveView) {
                // A surface bake already running at the mode switch may have held
                // this chunk's work key. Revisit its region after that work ends so
                // the cave bake gets submitted without invalidating cave geometry.
                queueGpuBatchForChunkPriority(baked.chunkX(), baked.chunkZ());
            }
            trimBakedCacheLocked();
        }
    }

    private void clearBakedCache() {
        synchronized (bakedCache) {
            bakedCache.clear();
            bakedCacheBytes.set(0);
        }
    }

    private void trimBakedCacheLocked() {
        if (!TerrainLodPolicy.bakedCacheRequiresEviction(bakedCache.size(),
            bakedCacheBytes.get(), MAX_BAKED_CACHE_BYTES)) return;

        int anchorX = streamAnchorChunkX == Integer.MIN_VALUE ? 0 : streamAnchorChunkX;
        int anchorZ = streamAnchorChunkZ == Integer.MIN_VALUE ? 0 : streamAnchorChunkZ;
        // Shed optional step-1/2 detail first while retaining the step-4 exact mesh.
        while (bakedCacheBytes.get() > TARGET_BAKED_CACHE_BYTES) {
            List<BakedChunk> candidates = selectBakedTrimCandidates(anchorX, anchorZ, true);
            if (candidates.isEmpty()) break;
            boolean changed = false;
            for (BakedChunk baked : candidates) {
                if (bakedCacheBytes.get() <= TARGET_BAKED_CACHE_BYTES) break;
                BakedChunk minimum = baked.minimumSurfaceCopy();
                if (minimum == baked) continue;
                long key = ChunkPos.pack(baked.chunkX(), baked.chunkZ());
                if (bakedCache.replace(key, baked, minimum)) {
                    bakedCacheBytes.addAndGet(minimum.estimatedBytes() - baked.estimatedBytes());
                    bakedCacheCompactions.incrementAndGet();
                    changed = true;
                }
            }
            if (!changed) break;
        }
        if (bakedCacheBytes.get() <= TARGET_BAKED_CACHE_BYTES) {
            lastBakedEvictionReason = "byte-budget-detail-compaction";
            return;
        }

        // Only the byte cap may evict a minimum mesh, and ordering remains anchored
        // to the persistent square origin rather than following the camera. Chunk
        // data remains available for direct step-4 rendering and a priority rebake.
        while (bakedCacheBytes.get() > TARGET_BAKED_CACHE_BYTES) {
            List<BakedChunk> candidates = selectBakedTrimCandidates(anchorX, anchorZ, false);
            if (candidates.isEmpty()) break;
            long bytesBefore = bakedCacheBytes.get();
            for (BakedChunk baked : candidates) {
                if (bakedCacheBytes.get() <= TARGET_BAKED_CACHE_BYTES) break;
                if (removeBakedChunkLocked(baked)) bakedCacheEvictions.incrementAndGet();
            }
            if (bakedCacheBytes.get() >= bytesBefore) break;
        }
        lastBakedEvictionReason = "byte-budget-minimum-eviction";
    }

    private List<BakedChunk> selectBakedTrimCandidates(int anchorX, int anchorZ,
                                                        boolean compactableOnly) {
        PriorityQueue<BakedChunk> selected = new PriorityQueue<>(
            BAKED_CACHE_TRIM_CANDIDATES,
            (a, b) -> compareBakedEvictionPriority(a, b, anchorX, anchorZ));
        for (BakedChunk baked : bakedCache.values()) {
            if (TerrainLodPolicy.protectsPrebakedUndergroundGeometry(
                lastCenterChunkX, lastCenterChunkZ, baked.chunkX(), baked.chunkZ(),
                CAVE_RENDER_RADIUS_CHUNKS, baked.cavesIncluded())) continue;
            if (compactableOnly && baked.isMinimumSurfaceOnly()) continue;
            if (selected.size() < BAKED_CACHE_TRIM_CANDIDATES) {
                selected.add(baked);
                continue;
            }
            BakedChunk weakest = selected.peek();
            if (weakest != null && compareBakedEvictionPriority(
                baked, weakest, anchorX, anchorZ) > 0) {
                selected.poll();
                selected.add(baked);
            }
        }
        List<BakedChunk> farthestFirst = new ArrayList<>(selected);
        farthestFirst.sort((a, b) -> compareBakedEvictionPriority(
            b, a, anchorX, anchorZ));
        return farthestFirst;
    }

    /** Positive means the first baked chunk is farther from the persistent anchor. */
    private static int compareBakedEvictionPriority(BakedChunk first, BakedChunk second,
                                                     int anchorX, int anchorZ) {
        int ring = Integer.compare(
            TerrainLodPolicy.retentionRing(anchorX, anchorZ, first.chunkX(), first.chunkZ()),
            TerrainLodPolicy.retentionRing(anchorX, anchorZ, second.chunkX(), second.chunkZ()));
        if (ring != 0) return ring;
        int x = Integer.compare(first.chunkX(), second.chunkX());
        return x != 0 ? x : Integer.compare(first.chunkZ(), second.chunkZ());
    }

    private boolean removeBakedChunkLocked(BakedChunk baked) {
        long key = ChunkPos.pack(baked.chunkX(), baked.chunkZ());
        if (bakedCache.remove(key, baked)) {
            bakedCacheBytes.addAndGet(-baked.estimatedBytes());
            return true;
        }
        return false;
    }

    private static double chunkDistanceSq(int centerChunkX, int centerChunkZ, int chunkX, int chunkZ) {
        double dx = chunkX - centerChunkX;
        double dz = chunkZ - centerChunkZ;
        return dx * dx + dz * dz;
    }

    private void trimPreviewCacheToBudget(int centerChunkX, int centerChunkZ) {
        int excess = previewCache.size() - MAX_CACHED_PREVIEWS;
        if (excess <= 0) return;
        List<PreviewKey> farthestFirst = new ArrayList<>(previewCache.keySet());
        farthestFirst.sort((a, b) -> Double.compare(
            chunkDistanceSq(centerChunkX, centerChunkZ, b.startChunkX(), b.startChunkZ()),
            chunkDistanceSq(centerChunkX, centerChunkZ, a.startChunkX(), a.startChunkZ())));
        for (PreviewKey key : farthestFirst) {
            if (excess <= 0) break;
            if (previewCache.remove(key) != null) excess--;
        }
    }

    /** Reuses the sorted visible set while the camera is idle. Newly completed
     * chunks and meaningful target movement invalidate it through the revision. */
    private List<VisibleChunk> visibleChunks(FreeCamera3D camera, int centerChunkX, int centerChunkZ) {
        long revision = chunkRevision.get();
        long now = System.nanoTime();
        boolean zoomChanged = !Double.isFinite(visibleCameraDistance)
            || Math.abs(camera.distance() - visibleCameraDistance)
                > Math.max(CHUNK_SIZE * 4.0, visibleCameraDistance * 0.08);
        int currentRenderRadius = renderDistanceChunks(camera);
        boolean horizonChanged = currentRenderRadius != visibleRenderRadius;
        boolean anchorChanged = visibleAnchorChunkX != streamAnchorChunkX
            || visibleAnchorChunkZ != streamAnchorChunkZ;
        boolean cameraWindowMoved = visibleCameraChunkX == Integer.MIN_VALUE
            || Math.abs(centerChunkX - visibleCameraChunkX) >= 4
            || Math.abs(centerChunkZ - visibleCameraChunkZ) >= 4;
        boolean cacheRefreshDue = revision != visibleChunkRevision
            && (visibleChunkCache.isEmpty() || now - visibleRefreshNanos >= VISIBLE_REFRESH_NANOS);
        if (cacheRefreshDue || zoomChanged || horizonChanged || anchorChanged || cameraWindowMoved) {
            List<VisibleChunk> result = new ArrayList<>();
            for (ChunkData chunk : chunkCache.values()) {
                if (!isChunkVisible(camera, chunk.chunkX, chunk.chunkZ)) continue;
                result.add(new VisibleChunk(chunk,
                    chunkDistanceSq(camera, chunk.chunkX, chunk.chunkZ)));
            }
            int anchorX = streamAnchorChunkX;
            int anchorZ = streamAnchorChunkZ;
            result.sort((first, second) -> {
                ChunkData a = first.chunk();
                ChunkData b = second.chunk();
                int cameraComparison = Double.compare(first.distanceSq(), second.distanceSq());
                if (cameraComparison != 0) return cameraComparison;
                int ringA = Math.max(Math.abs(a.chunkX - anchorX), Math.abs(a.chunkZ - anchorZ));
                int ringB = Math.max(Math.abs(b.chunkX - anchorX), Math.abs(b.chunkZ - anchorZ));
                int ringComparison = Integer.compare(ringA, ringB);
                if (ringComparison != 0) return ringComparison;
                int xComparison = Integer.compare(a.chunkX, b.chunkX);
                return xComparison != 0 ? xComparison : Integer.compare(a.chunkZ, b.chunkZ);
            });
            visibleChunkCache = List.copyOf(result);
            visibleChunkRevision = revision;
            visibleCameraDistance = camera.distance();
            visibleAnchorChunkX = streamAnchorChunkX;
            visibleAnchorChunkZ = streamAnchorChunkZ;
            visibleCameraChunkX = centerChunkX;
            visibleCameraChunkZ = centerChunkZ;
            visibleRenderRadius = currentRenderRadius;
            visibleRefreshNanos = now;
        }
        return visibleChunkCache;
    }

    /** Builds the player's nearby surface and cave meshes even while the map is closed. */
    private void submitPreparedBakes(long seed, int dimension, int centerChunkX, int centerChunkZ) {
        // The active cave batch builder submits the cave bakes it needs. Draining
        // the larger surface warmup queue here kept revising those GPU batches
        // before an underground replacement could be uploaded.
        if (bulkLoading || gpuCaveView) return;
        // Atlas creation performs a GPU upload and prepare() is called on the client
        // thread. Initialize it here before any bake worker reads its texture regions.
        if (!pendingBackgroundBakes.isEmpty()) BlockTextureAtlas.get();

        int budget = TerrainLodPolicy.adaptiveBackgroundBudget(
            2, performanceThrottle, 1);
        int examined = 0;
        int maximumExamined = 128;
        while (budget > 0 && examined++ < maximumExamined) {
            Long key = pendingBackgroundBakes.poll();
            if (key == null) break;
            queuedBackgroundBakes.remove(key);
            ChunkData chunk = chunkCache.get(key);
            if (chunk == null) continue;

            int distance = Math.max(Math.abs(chunk.chunkX - centerChunkX),
                Math.abs(chunk.chunkZ - centerChunkZ));
            if (distance > RENDER_DIST) continue;
            int desiredStep = generationStepForDistance(distance);
            if (desiredStep == 1 && !isExactSurface(chunk)) continue;
            if (chunk.generatedStep > desiredStep) continue;
            int step = isExactSurface(chunk) ? TerrainLodPolicy.EXACT_DISPLAY_MAX_STEP
                : Math.max(chunk.generatedStep, desiredStep);
            BakedChunk baked = bakedCache.get(key);
            boolean includeCaves = TerrainLodPolicy.shouldPrebakeUndergroundGeometry(
                dimension, distance, CAVE_RENDER_RADIUS_CHUNKS, isExactSurface(chunk));
            if (!isBakeStaleAtStep(baked, seed, dimension, chunk, step, false, includeCaves)) {
                continue;
            }
            if (submitBake(seed, dimension, chunk, step, false, chunk.observedAt, includeCaves)) {
                budget--;
            } else {
                queueBackgroundBake(key);
                break;
            }
        }
    }

    private void queueBackgroundBake(long key) {
        if (!canQueueBackgroundBake(queuedBackgroundBakes.size())) return;
        if (!queuedBackgroundBakes.add(key)) return;
        pendingBackgroundBakes.add(key);
    }

    static boolean canQueueBackgroundBake(int queued) {
        return queued >= 0 && queued < MAX_PENDING_BACKGROUND_BAKES;
    }

    private void queueExactUpgrade(long key) {
        if (queuedExactUpgrades.add(key)) pendingExactUpgrades.add(key);
    }

    private void rebuildNaturalFeatureQueue(int centerChunkX, int centerChunkZ) {
        naturalFeatureQueueCenterX = centerChunkX;
        naturalFeatureQueueCenterZ = centerChunkZ;
        pendingNaturalFeatures.clear();
        queuedNaturalFeatures.clear();
        for (int ring = 0; ring <= NATURAL_FEATURE_RADIUS_CHUNKS; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    queueNaturalFeature(ChunkPos.pack(centerChunkX + dx, centerChunkZ + dz));
                }
            }
        }
    }

    private void queueNaturalFeature(long key) {
        int centerX = naturalFeatureQueueCenterX;
        int centerZ = naturalFeatureQueueCenterZ;
        if (centerX == Integer.MIN_VALUE || centerZ == Integer.MIN_VALUE) return;
        if (!withinNaturalFeatureRadius(centerX, centerZ,
            ChunkPos.getX(key), ChunkPos.getZ(key))) return;
        ChunkData chunk = chunkCache.get(key);
        if (chunk == null || !isExactSurface(chunk) || chunk.naturalDecorationsResolved) return;
        if (queuedNaturalFeatures.add(key)) pendingNaturalFeatures.add(key);
    }

    static boolean withinNaturalFeatureRadius(int centerChunkX, int centerChunkZ,
                                              int chunkX, int chunkZ) {
        return Math.max(Math.abs(chunkX - centerChunkX),
            Math.abs(chunkZ - centerChunkZ)) <= NATURAL_FEATURE_RADIUS_CHUNKS;
    }

    private void submitNaturalFeatureUpgrades(long seed, int dimension,
                                              int centerChunkX, int centerChunkZ) {
        if (bulkLoading || naturalFeatureExecutor.getActiveCount()
            + naturalFeatureExecutor.getQueue().size() >= MAX_IN_FLIGHT_NATURAL_FEATURES) return;
        int budget = TerrainLodPolicy.adaptiveBackgroundBudget(
            NATURAL_FEATURE_SUBMISSIONS_PER_PULSE, performanceThrottle, 1);
        while (budget-- > 0) {
            Long key = pendingNaturalFeatures.poll();
            if (key == null) return;
            queuedNaturalFeatures.remove(key);
            ChunkData current = chunkCache.get(key);
            if (current == null || !isExactSurface(current)
                || current.naturalDecorationsResolved) continue;
            if (Math.max(Math.abs(current.chunkX - centerChunkX),
                Math.abs(current.chunkZ - centerChunkZ)) > NATURAL_FEATURE_RADIUS_CHUNKS) continue;

            WorkKey workKey = new WorkKey(seed, dimension, key, worldGeneration);
            if (!inFlightNaturalFeatures.add(workKey)) continue;
            try {
                naturalFeatureExecutor.execute(() -> {
                    try {
                        GeneratedTerrainHeightmap.NaturalDecorationSnapshot snapshot =
                            carvedTerrain(seed, dimension).naturalDecorationSnapshot(
                                new ChunkPos(current.chunkX, current.chunkZ));
                        if (!isCurrent(workKey)) return;
                        ChunkData upgraded = current.withNaturalDecorations(snapshot.blocks());
                        boolean stored = false;
                        synchronized (chunkCache) {
                            ChunkData latest = chunkCache.get(key);
                            if (latest == current && !latest.naturalDecorationsResolved) {
                                chunkCache.put(key, upgraded);
                                stored = true;
                            }
                        }
                        if (stored) {
                            terrainFailureReasons.remove(key);
                            naturalFeatureSnapshotsResolved.incrementAndGet();
                            naturalFeatureBlocksResolved.addAndGet(snapshot.blocks().size());
                            chunkRevision.incrementAndGet();
                            invalidateBakedDependencies(current.chunkX, current.chunkZ, false);
                        }
                    } catch (Throwable error) {
                        terrainFailureReasons.put(key, "decoration-failed: "
                            + error.getClass().getSimpleName());
                        terrainDiagnostic("decoration-failed", seed, dimension,
                            current.chunkX, current.chunkZ, 1, current, error,
                            "vanilla placed-feature replay");
                        if (isCurrent(workKey)) queueNaturalFeature(key);
                    } finally {
                        inFlightNaturalFeatures.remove(workKey);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                inFlightNaturalFeatures.remove(workKey);
                queueNaturalFeature(key);
                return;
            }
        }
    }

    private boolean submitChunkGeneration(long seed, int dimension, int cx, int cz, int generatedStep) {
        int maximumInFlight = bulkLoading ? BULK_MAX_IN_FLIGHT_CHUNKS
            : TerrainLodPolicy.adaptiveBackgroundBudget(
                MAX_IN_FLIGHT_CHUNKS, performanceThrottle, 4);
        if (inFlightChunks.size() >= maximumInFlight) return false;
        long key = ChunkPos.pack(cx, cz);
        if (!chunkCache.containsKey(key)) presentationFrontier.sequenceFor(key);
        WorkKey workKey = new WorkKey(seed, dimension, key, worldGeneration);
        if (!inFlightChunks.add(workKey)) return false;

        Runnable generationTask = () -> {
            try {
                ChunkData generated = generateChunkData(seed, dimension, cx, cz, generatedStep);
                boolean retained = storeGeneratedChunk(workKey, key, generated);
                if (isCurrent(workKey) && chunkCache.containsKey(key)
                    && presentationFrontier.hasSequence(key)) {
                    presentationFrontier.markReady(key);
                }
                if (isCurrent(workKey)) {
                    chunkGenerationFailures.remove(workKey);
                    terrainFailureReasons.remove(key);
                    if (retained && !bulkLoading) {
                        chunkRevision.incrementAndGet();
                        queueBackgroundBake(key);
                    }
                    // The expanding background rings eventually leave the in-memory
                    // window. Persist them anyway so moving the free camera there can
                    // reuse the completed prediction instead of regenerating it.
                    if (generated.observedSnapshot == null
                        && generated.accuracy != TerrainAccuracy.EXPERIMENTAL_HORIZON) {
                        diskCache.storeAsync(streamWorldIdentity, seed, dimension,
                            toCachedChunk(generated));
                    }
                }
            } catch (Throwable ignored) {
                failedChunkGenerations.incrementAndGet();
                terrainFailureReasons.put(key, "generation-failed: "
                    + ignored.getClass().getSimpleName());
                lastTerrainError = ignored.getClass().getSimpleName()
                    + (ignored.getMessage() == null ? "" : ": " + ignored.getMessage());
                int failures = chunkGenerationFailures.merge(workKey, 1, Integer::sum);
                terrainDiagnostic("generation-failed", seed, dimension, cx, cz, generatedStep,
                    null, ignored, "attempt=" + failures + "/" + MAX_CHUNK_GENERATION_RETRIES);
                System.err.println("[Seed Explorer] Terrain chunk " + cx + "," + cz
                    + " generation failed (attempt " + failures + "): " + lastTerrainError);
                if (failures < MAX_CHUNK_GENERATION_RETRIES && isCurrent(workKey)) {
                    synchronized (Terrain3DRenderer.this) {
                        if (!chunkCache.containsKey(key)) pendingChunks.addFirst(new long[]{key, cx, cz});
                    }
                } else {
                    if (isCurrent(workKey)) presentationFrontier.markReady(key);
                    terrainDiagnostic("generation-retry-exhausted", seed, dimension, cx, cz,
                        generatedStep, null, ignored, "attempts=" + failures);
                }
            } finally {
                inFlightChunks.remove(workKey);
            }
        };
        try {
            terrainExecutor.execute(generationTask);
        } catch (RejectedExecutionException rejected) {
            inFlightChunks.remove(workKey);
            if (presentationFrontier.hasSequence(key) && !chunkCache.containsKey(key)) {
                synchronized (this) {
                    pendingChunks.addFirst(new long[]{key, cx, cz});
                }
            }
            return false;
        }
        return true;
    }

    /** Never lets a late coarse job replace observed or higher-detail terrain. */
    private boolean storeGeneratedChunk(WorkKey workKey, long key, ChunkData generated) {
        synchronized (chunkCache) {
            if (!isCurrent(workKey)) {
                terrainDiagnostic("generation-discarded-stale-world", workKey.seed(),
                    workKey.dimension(), generated.chunkX, generated.chunkZ, generated.generatedStep,
                    generated, null, "workGeneration=" + workKey.generation()
                        + " currentGeneration=" + worldGeneration);
                return false;
            }
            ChunkData current = chunkCache.get(key);
            if (current != null) {
                if (current.observedAt > generated.observedAt) {
                    terrainDiagnostic("generation-discarded-newer-observation", workKey.seed(),
                        workKey.dimension(), generated.chunkX, generated.chunkZ, generated.generatedStep,
                        generated, null, "cachedObservedAt=" + current.observedAt);
                    return false;
                }
                if (current.observedSnapshot != null && generated.observedSnapshot == null) {
                    terrainDiagnostic("generation-discarded-observed-chunk", workKey.seed(),
                        workKey.dimension(), generated.chunkX, generated.chunkZ, generated.generatedStep,
                        generated, null, "exact observed snapshot already cached");
                    return false;
                }
                if (current.observedAt == generated.observedAt
                    && current.generatedStep <= generated.generatedStep
                    && (isExactSurface(current) || !isExactSurface(generated))) {
                    terrainDiagnostic("generation-discarded-lower-detail", workKey.seed(),
                        workKey.dimension(), generated.chunkX, generated.chunkZ, generated.generatedStep,
                        generated, null, "cachedStep=" + current.generatedStep);
                    return false;
                }
            }
            chunkCache.put(key, generated);
            if (!bulkLoading) queueNaturalFeature(key);
            if (!bulkLoading) {
                invalidateBakedDependencies(generated.chunkX, generated.chunkZ,
                    generated.observedSnapshot != null);
                if (presentationFrontier.isRevealed(key)) {
                    invalidateGpuBatchForChunk(generated.chunkX, generated.chunkZ);
                }
            }
            long completedRegion = streamRegionKey(generated.chunkX, generated.chunkZ);
            if (completedStreamRegions.add(completedRegion)) {
                worldDataSource.terrainRegionReady(completedRegion, workKey.dimension());
            }
            trimChunkCacheToBudgetLocked(lastCenterChunkX, lastCenterChunkZ);
            return true;
        }
    }

    private void invalidateBakedDependencies(int chunkX, int chunkZ,
                                             boolean observedCaveData) {
        int[][] offsets = observedCaveData
            ? new int[][]{{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}}
            : new int[][]{{0, 0}, {-1, 0}, {0, -1}};
        for (int[] offset : offsets) {
            long key = ChunkPos.pack(chunkX + offset[0], chunkZ + offset[1]);
            geometryRevisions.put(key, nextGeometryRevision.incrementAndGet());
            if (chunkCache.containsKey(key)) queueBackgroundBake(key);
        }
    }

    /** The detail step a chunk's geometry should be baked at, given the camera. */
    private int bakeStep(FreeCamera3D camera, ChunkData chunk) {
        int dataStep = sampleStep(camera, chunk);
        return Math.max(displayStep(camera, chunk, dataStep), chunk.generatedStep);
    }

    private int displayStep(FreeCamera3D camera, ChunkData chunk, int dataStep) {
        if (dataStep != 1 || !isExactSurface(chunk)) return dataStep;
        double horizontalSq = chunkDistanceSq(camera, chunk.chunkX, chunk.chunkZ);
        return TerrainLodPolicy.exactDisplayStepWithHysteresis(null,
            Math.sqrt(horizontalSq), chunk.chunkX, chunk.chunkZ);
    }

    private static int minimumVisibleSurfaceVertices(ChunkData chunk, int requestedDataStep) {
        return hasSurfaceData(chunk)
            ? TerrainLodPolicy.minimumSurfaceVertexReserve(requestedDataStep) : 0;
    }

    /**
     * A baked mesh is stale (and must be re-baked) when it is missing, was baked for
     * a different world/detail step, or before newer observed chunk data.
     */
    private boolean isBakeStale(BakedChunk baked, long seed, int dimension, ChunkData chunk,
                                 FreeCamera3D camera, boolean textureDebugView,
                                 boolean requireCaves) {
        return isBakeStaleAtStep(baked, seed, dimension, chunk, bakeStep(camera, chunk),
            textureDebugView, requireCaves);
    }

    private boolean isBakeStaleAtStep(BakedChunk baked, long seed, int dimension, ChunkData chunk,
                                      int step, boolean textureDebugView, boolean requireCaves) {
        if (baked == null) return true;
        if (baked.seed() != seed || baked.dimension() != dimension) return true;
        if (baked.textureDebugView() != textureDebugView) return true;
        if (baked.naturalFeaturesIncluded()
            != hasNaturalDecorationSource(chunk)) return true;
        if (baked.observedAt() != chunk.observedAt) return true;
        if (baked.geometryRevision() != geometryRevisions.getOrDefault(
            ChunkPos.pack(chunk.chunkX, chunk.chunkZ), 0L)) return true;
        if (!baked.hasSurfaceLod(step)) return true;
        if (requireCaves && dimension != 1) {
            if (!baked.cavesIncluded()
                || baked.caveStep() != undergroundSampleStepFor(chunk, baked.step())) return true;
        }
        return false;
    }

    /**
     * Bakes one chunk's geometry off the render thread and stores it. Returns true if
     * a bake was actually queued (i.e. it wasn't already in flight). All the expensive
     * work - both the surface mesh and cave shells - happens here together once per
     * detail level. Switching views only selects a different cached geometry array.
     */
    private boolean submitBake(long seed, int dimension, ChunkData chunk, FreeCamera3D camera,
                               boolean textureDebugView, boolean includeCaves) {
        return submitBake(seed, dimension, chunk, bakeStep(camera, chunk), textureDebugView,
            chunk.observedAt, includeCaves);
    }

    private boolean submitBake(long seed, int dimension, ChunkData chunk, int step,
                               boolean textureDebugView, long observedAt, boolean includeCaves) {
        int maximumInFlight = TerrainLodPolicy.adaptiveBackgroundBudget(
            MAX_IN_FLIGHT_BAKES, performanceThrottle, 8);
        if (inFlightBakes.size() >= maximumInFlight) return false;
        long key = ChunkPos.pack(chunk.chunkX, chunk.chunkZ);
        long generation = worldGeneration;
        long geometryRevision = geometryRevisions.getOrDefault(key, 0L);
        boolean includeNaturalFeatures = hasNaturalDecorationSource(chunk);
        WorkKey workKey = new WorkKey(seed, dimension, key, generation);
        if (!inFlightBakes.add(workKey)) return false;

        int caveStep = undergroundSampleStepFor(chunk, step);
        try {
            bakeExecutor.execute(() -> {
                try {
                    BakedChunk baked = bakeChunk(seed, dimension, chunk, step, caveStep,
                        textureDebugView, observedAt, includeCaves, includeNaturalFeatures,
                        geometryRevision);
                    long currentGeometryRevision = geometryRevisions.getOrDefault(key, 0L);
                    if (geometryRevision != currentGeometryRevision) {
                        terrainDiagnostic("bake-retained-stale-geometry", seed, dimension,
                            chunk.chunkX, chunk.chunkZ, step, chunk, null,
                            "bakedRevision=" + geometryRevision + " currentRevision="
                                + currentGeometryRevision + " retainedForProgressiveDisplay=true");
                        // Neighbor arrival can revise a boundary while this expensive
                        // cave bake is running. Retain the completed mesh as a drawable
                        // progressive result, but keep its old revision so stale checks
                        // immediately schedule a refined replacement. Discarding it left
                        // the underground batch black whenever streaming stayed active.
                    }
                    if (baked.cavesIncluded()) {
                        int vertices = baked.caveVertexCount();
                        caveVerticesBaked.addAndGet(vertices);
                        if (vertices > 0) caveBakesWithGeometry.incrementAndGet();
                        else caveBakesWithoutGeometry.incrementAndGet();
                    }
                    storeBakedChunk(workKey, key, baked);
                    terrainFailureReasons.remove(key);
                } catch (Throwable ignored) {
                    terrainFailureReasons.put(key, "bake-failed: "
                        + ignored.getClass().getSimpleName());
                    lastTerrainError = ignored.getClass().getSimpleName()
                        + (ignored.getMessage() == null ? "" : ": " + ignored.getMessage());
                    terrainDiagnostic("bake-failed", seed, dimension, chunk.chunkX, chunk.chunkZ,
                        step, chunk, ignored, "caveStep=" + caveStep
                            + " textureDebugView=" + textureDebugView
                            + " includeCaves=" + includeCaves);
                } finally {
                    inFlightBakes.remove(workKey);
                }
            });
        } catch (RejectedExecutionException rejected) {
            inFlightBakes.remove(workKey);
            return false;
        }
        return true;
    }

    /** Builds the solid + fluid geometry for a chunk into reusable arrays. */
    private BakedChunk bakeChunk(long seed, int dimension, ChunkData chunk, int step, int caveStep,
                                 boolean textureDebugView, long observedAt,
                                 boolean includeCaves, boolean includeNaturalFeatures,
                                 long geometryRevision) {
        BakingSink caveSolid = new BakingSink();
        BakingSink naturalSolid = new BakingSink();

        ChunkData surfaceChunk = chunk;
        ChunkAccess generatedBlocks = null;
        GeneratedTerrainHeightmap carved = null;
        // Exact predicted chunks come from the post-surface/carver vanilla chunk.
        // Retain that block volume for cliff faces so they show real dirt, stone,
        // terracotta, and deepslate layers instead of repeating the top material.
        if (isExactSurface(chunk) && chunk.observedSnapshot == null) {
            carved = carvedTerrain(seed, dimension);
            generatedBlocks = carved.getChunk(new ChunkPos(chunk.chunkX, chunk.chunkZ));
        }
        List<SurfaceLod> surfaceLods = new ArrayList<>(5);
        int coarsestStoredStep = isExactSurface(chunk)
            ? TerrainLodPolicy.EXACT_DISPLAY_MAX_STEP : TerrainLodPolicy.COARSE_SAMPLE_STEP;
        int firstStoredStep = isExactSurface(chunk)
            ? Math.min(step, 4) : step;
        for (int lodStep = firstStoredStep; ; lodStep = TerrainLodPolicy.nextCoarserStep(lodStep)) {
            BakingSink surfaceSolid = new BakingSink();
            BakingSink surfaceFluid = new BakingSink();
            renderChunkMesh(surfaceSolid, surfaceFluid, surfaceChunk, generatedBlocks,
                dimension, lodStep, textureDebugView);
            surfaceLods.add(new SurfaceLod(lodStep, surfaceSolid.finish(), surfaceFluid.finish()));
            if (lodStep >= coarsestStoredStep) break;
        }
        if (includeNaturalFeatures && hasNaturalDecorationSource(chunk)) {
            renderNaturalDecorations(naturalSolid, chunk, generatedBlocks, seed, dimension);
        }

        boolean cavesIncluded = TerrainLodPolicy.shouldBakeUndergroundGeometry(
            includeCaves, dimension, step);
        if (cavesIncluded) {
            // Cave faces use the exact observed snapshot where available and the
            // generated carver replay elsewhere. They are retained alongside the
            // surface mesh so changing views never discards either representation.
            WorldgenEngine.TerrainAccessor terrain = terrainAccessor(seed, dimension);
            if (carved == null) carved = carvedTerrain(seed, dimension);
            int voxelBudget = chunk.observedSnapshot == null
                ? MAX_CAVE_VOXELS_PER_CHUNK : MAX_OBSERVED_CAVE_VOXELS_PER_CHUNK;
            renderUndergroundCaveShells(caveSolid, surfaceChunk, terrain, carved, dimension, caveStep,
                voxelBudget, textureDebugView);
        }

        BakedGeometry naturalGeometry = naturalSolid.finish();
        if (chunk.naturalDecorationsResolved) {
            int naturalVertices = naturalGeometry.px().length;
            naturalFeatureVerticesBaked.addAndGet(naturalVertices);
            if (naturalVertices > 0) naturalFeatureBakesWithGeometry.incrementAndGet();
            else naturalFeatureBakesWithoutGeometry.incrementAndGet();
        }
        return new BakedChunk(seed, dimension, chunk.chunkX, chunk.chunkZ, step, caveStep,
            observedAt, geometryRevision, textureDebugView, cavesIncluded,
            includeNaturalFeatures, List.copyOf(surfaceLods), naturalGeometry, caveSolid.finish());
    }

    private static boolean isExactSurface(ChunkData chunk) {
        return chunk != null && (chunk.accuracy == TerrainAccuracy.OBSERVED_EXACT
            || chunk.accuracy == TerrainAccuracy.PREDICTED_VANILLA_SURFACE);
    }

    /**
     * Reads the actual post-surface, post-carver vanilla blocks from the same
     * generated chunk used for cave rendering. This captures grass, sand, gravel,
     * snow, terracotta, deepslate and biome-specific surface rules instead of
     * coloring a base-noise stone column.
     */
    private ChunkData generateVanillaSurfaceChunk(ChunkAccess vanilla, int dimension,
                                                   int chunkX, int chunkZ, int step) {
        int[] heights = new int[CHUNK_SIZE * CHUNK_SIZE];
        BlockState[] states = new BlockState[CHUNK_SIZE * CHUNK_SIZE];
        int[] fluidHeights = new int[CHUNK_SIZE * CHUNK_SIZE];
        BlockState[] fluidStates = new BlockState[CHUNK_SIZE * CHUNK_SIZE];
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;

        // Populate every column once. Surface and cave meshes can then choose
        // independent LOD steps without reading an unpopulated sample cell.
        for (int dx = 0; dx < CHUNK_SIZE; dx++) {
            for (int dz = 0; dz < CHUNK_SIZE; dz++) {
                int index = dz * CHUNK_SIZE + dx;
                SurfaceSample surface = resolveVanillaSurface(vanilla, dimension, baseX + dx, baseZ + dz);
                BlockState top = surface.surfaceState();
                if (top == null || top.isAir()) continue;
                if (isFluid(top)) {
                    fluidHeights[index] = surface.surfaceY();
                    fluidStates[index] = top;
                    heights[index] = surface.solidY() + 1;
                    states[index] = surface.solidState();
                } else {
                    heights[index] = surface.surfaceY();
                    states[index] = top;
                }
            }
        }
        return new ChunkData(chunkX, chunkZ, heights, states, fluidHeights, fluidStates,
            null, 0L, step, TerrainAccuracy.PREDICTED_VANILLA_SURFACE,
            List.of(), false);
    }

    private SurfaceSample resolveVanillaSurface(ChunkAccess chunk, int dimension, int worldX, int worldZ) {
        int minY = chunk.getMinY();
        int dimensionMaxY = dimension == -1
            ? Math.min(chunk.getMaxY() - 1, TerrainMaterialPolicy.NETHER_ROOF_CUTOFF_Y - 1)
            : chunk.getMaxY() - 1;
        int heightmapY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG,
            Math.floorMod(worldX, CHUNK_SIZE), Math.floorMod(worldZ, CHUNK_SIZE));
        int maxY = Math.min(dimensionMaxY, Math.max(minY, heightmapY));
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = maxY; y >= minY; y--) {
            BlockState state = chunk.getBlockState(cursor.set(worldX, y, worldZ));
            if (state == null || state.isAir()) continue;
            if (dimension == -1) {
                BlockState above = chunk.getBlockState(cursor.set(worldX, y + 1, worldZ));
                if (!isAirLike(above)) continue;
            }
            if (isFluid(state) || isIce(state)) {
                for (int solidY = y - 1; solidY >= minY; solidY--) {
                    BlockState solid = chunk.getBlockState(cursor.set(worldX, solidY, worldZ));
                    if (solid != null && !solid.isAir() && !isFluid(solid) && !isNonSolidCover(solid)) {
                        return new SurfaceSample(y + 1, solidY, state, solid);
                    }
                }
            }
            if (!isTerrainSupportingSurface(state)) continue;
            return new SurfaceSample(y + 1, y, state, state);
        }
        return new SurfaceSample(minY, minY, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState());
    }

    private int undergroundSampleStepFor(ChunkData chunk, int surfaceStep) {
        // A cave cell can be one block wide, so merging even a four-block cell
        // turns walls into false slabs and removes narrow passages. Underground
        // view intentionally renders a compact local window at exact resolution
        // rather than attempting a distant coarse cave LOD.
        return 1;
    }

    /** Reuse the terrain column cache within each worker thread. Creating one
     * accessor per chunk discarded all sampled noise columns immediately. */
    private WorldgenEngine.TerrainAccessor terrainAccessor(long seed, int dimension) {
        TerrainAccessorSlot slot = terrainAccessors.get();
        if (slot.accessor == null || slot.seed != seed || slot.dimension != dimension) {
            slot.seed = seed;
            slot.dimension = dimension;
            slot.accessor = WorldgenEngine.terrainAccessor(seed, dimension);
        }
        return slot.accessor;
    }

    /** Vanilla noise + surface + carver replay for predicted underground geometry. */
    private GeneratedTerrainHeightmap carvedTerrain(long seed, int dimension) {
        CarvedTerrainSlot slot = carvedTerrainAccessors.get();
        if (slot.terrain == null || slot.seed != seed || slot.dimension != dimension) {
            slot.seed = seed;
            slot.dimension = dimension;
            slot.terrain = new GeneratedTerrainHeightmap(seed, dimension, List.of(), false);
        }
        return slot.terrain;
    }

    /** Exact surface workers visit each chunk once. Keeping their old ProtoChunks
     * only multiplies heap use across the bulk worker pool. */
    private GeneratedTerrainHeightmap exactSurfaceTerrain(long seed, int dimension) {
        CarvedTerrainSlot slot = exactSurfaceTerrainAccessors.get();
        if (slot.terrain == null || slot.seed != seed || slot.dimension != dimension) {
            slot.seed = seed;
            slot.dimension = dimension;
            slot.terrain = new GeneratedTerrainHeightmap(seed, dimension, List.of(), false, 2);
        }
        return slot.terrain;
    }

    private ChunkData generateChunkData(long seed, int dimension, int cx, int cz, int generatedStep) {
        LiveChunkSnapshot snapshot = worldDataSource.snapshot(dimension, cx, cz);
        if (snapshot != null && snapshot.usableForTerrainOverride()) {
            return generateObservedChunkData(snapshot);
        }
        if (snapshot != null) rejectObservedSnapshot(seed, dimension, snapshot);

        // Keep the Horizon-style experiment isolated from the normal persistent
        // cache: the approximate material and density profile must never be
        // restored later as if it were a vanilla surface.
        if (dimension == 0 && generatedStep >= 4
            && TerrainGenerationMode.usesHorizonSampling()) {
            HorizonTerrainSampler.SampledChunk sampled = HorizonTerrainSampler.sampleChunk(
                seed, cx, cz, generatedStep);
            return new ChunkData(cx, cz, sampled.heights(), sampled.states(),
                sampled.fluidHeights(), sampled.fluidStates(), null, 0L,
                generatedStep, TerrainAccuracy.EXPERIMENTAL_HORIZON, List.of(), true);
        }

        var cached = diskCache.load(streamWorldIdentity, seed, dimension, cx, cz, generatedStep);
        if (cached.isPresent() && (generatedStep != 1 || cached.get().exactSurface())) {
            diskRestoredChunks.add(ChunkPos.pack(cx, cz));
            ChunkData chunk = fromCachedChunk(cached.get());
            if (!isExactSurface(chunk)) applyCoarseSurfaceMaterials(seed, dimension, chunk);
            return chunk;
        }
        if (generatedStep == 1) {
            ChunkData exact = generateVanillaSurfaceChunk(
                exactSurfaceTerrain(seed, dimension).getChunk(new ChunkPos(cx, cz)),
                dimension, cx, cz, 1);
            diskRestoredChunks.remove(ChunkPos.pack(cx, cz));
            return exact;
        }

        int[] heights = new int[CHUNK_SIZE * CHUNK_SIZE];
        BlockState[] states = new BlockState[CHUNK_SIZE * CHUNK_SIZE];
        int[] fluidHeights = new int[CHUNK_SIZE * CHUNK_SIZE];
        BlockState[] fluidStates = new BlockState[CHUNK_SIZE * CHUNK_SIZE];
        int baseX = cx * CHUNK_SIZE;
        int baseZ = cz * CHUNK_SIZE;
        WorldgenEngine.TerrainAccessor terrain = terrainAccessor(seed, dimension);

        for (int dx = 0; dx < CHUNK_SIZE; dx += generatedStep) {
            for (int dz = 0; dz < CHUNK_SIZE; dz += generatedStep) {
                int sampleOffset = Math.max(0, generatedStep / 2);
                int wx = baseX + Math.min(CHUNK_SIZE - 1, dx + sampleOffset);
                int wz = baseZ + Math.min(CHUNK_SIZE - 1, dz + sampleOffset);
                int index = dz * CHUNK_SIZE + dx;
                SurfaceSample surface = dimension == -1
                    ? resolveNetherSurface(terrain, wx, wz)
                    : resolvePredictedSurface(dimension, terrain, wx, wz, terrain.oceanFloorHeight(wx, wz));
                BlockState topState = surface.surfaceState();
                if (topState == null || topState.isAir()) continue;
                if (isFluid(topState)) {
                    fluidHeights[index] = surface.surfaceY();
                    fluidStates[index] = topState;
                    heights[index] = surface.solidY() + 1;
                    states[index] = surface.solidState();
                    continue;
                }
                heights[index] = surface.surfaceY();
                states[index] = topState;
            }
        }
        ChunkData chunk = new ChunkData(cx, cz, heights, states, fluidHeights, fluidStates, null, 0L,
            generatedStep, TerrainAccuracy.PREDICTED_BASE_DENSITY, List.of(), true);
        diskRestoredChunks.remove(ChunkPos.pack(cx, cz));
        applyCoarseSurfaceMaterials(seed, dimension, chunk);
        return chunk;
    }

    /**
     * Applies a cheap biome-aware material to distant placeholders. Full vanilla
     * surface-rule chunks still replace these asynchronously; this path only keeps
     * the expanding low-LOD map recognizably colored while those bakes catch up.
     */
    private void applyCoarseSurfaceMaterials(long seed, int dimension, ChunkData chunk) {
        if (chunk.observedSnapshot != null) return;
        int baseX = chunk.chunkX * CHUNK_SIZE;
        int baseZ = chunk.chunkZ * CHUNK_SIZE;
        for (int dx = 0; dx < CHUNK_SIZE; dx += chunk.generatedStep) {
            for (int dz = 0; dz < CHUNK_SIZE; dz += chunk.generatedStep) {
                int index = dz * CHUNK_SIZE + dx;
                if (chunk.states[index] == null && chunk.fluidStates[index] == null) continue;
                int surfaceY = chunk.fluidStates[index] != null
                    ? chunk.fluidHeights[index] : chunk.heights[index];
                int sampleOffset = Math.max(0, chunk.generatedStep / 2);
                String biomeId = WorldgenEngine.getBiome(seed, dimension,
                    baseX + Math.min(CHUNK_SIZE - 1, dx + sampleOffset), 64,
                    baseZ + Math.min(CHUNK_SIZE - 1, dz + sampleOffset)).id();
                chunk.states[index] = coarseSurfaceMaterial(dimension, biomeId, surfaceY,
                    chunk.states[index]);
            }
        }
    }

    private static BlockState coarseSurfaceMaterial(int dimension, String biomeId,
                                                    int surfaceY, BlockState fallback) {
        String id = biomeId == null ? "" : biomeId;

        // The density sampler already returns the block selected by vanilla's
        // surface rules. Do not repaint a valid sample with a biome guess while
        // a coarse chunk is waiting for its exact upgrade; that created the
        // large saturated green/snow fields visible during streaming.
        if (fallback != null && !fallback.isAir()
            && TerrainMaterialPolicy.validPredictedSurface(dimension, blockId(fallback))
            && isTerrainSupportingSurface(fallback)) {
            return fallback;
        }

        if (dimension == 1) return Blocks.END_STONE.defaultBlockState();
        if (dimension == -1) {
            if (id.contains("warped_forest")) return Blocks.WARPED_NYLIUM.defaultBlockState();
            if (id.contains("crimson_forest")) return Blocks.CRIMSON_NYLIUM.defaultBlockState();
            if (id.contains("soul_sand_valley")) return Blocks.SOUL_SAND.defaultBlockState();
            if (id.contains("basalt_deltas")) return Blocks.BASALT.defaultBlockState();
            return Blocks.NETHERRACK.defaultBlockState();
        }
        if (id.contains("badlands")) {
            return (surfaceY & 3) == 0
                ? Blocks.RED_SAND.defaultBlockState() : Blocks.TERRACOTTA.defaultBlockState();
        }
        if (id.contains("desert") || id.equals("minecraft:beach")) {
            return Blocks.SAND.defaultBlockState();
        }
        if (id.contains("snow") || id.contains("frozen") || id.contains("ice_spikes")
            || id.equals("minecraft:grove")) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        if (id.contains("gravelly") || id.equals("minecraft:stony_shore")) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        if (id.contains("peak")) {
            return Blocks.STONE.defaultBlockState();
        }
        if (id.equals("minecraft:mushroom_fields")) return Blocks.MYCELIUM.defaultBlockState();
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private TerrainDiskCache.CachedChunk toCachedChunk(ChunkData chunk) {
        String[] states = new String[chunk.states.length];
        String[] fluidStates = new String[chunk.fluidStates.length];
        for (int i = 0; i < states.length; i++) {
            states[i] = blockId(chunk.states[i]);
            fluidStates[i] = blockId(chunk.fluidStates[i]);
        }
        return new TerrainDiskCache.CachedChunk(chunk.chunkX, chunk.chunkZ, chunk.generatedStep,
            chunk.copyHeights(), states, chunk.copyFluidHeights(), fluidStates,
            isExactSurface(chunk));
    }

    private ChunkData fromCachedChunk(TerrainDiskCache.CachedChunk cached) {
        BlockState[] states = new BlockState[cached.states().length];
        BlockState[] fluidStates = new BlockState[cached.fluidStates().length];
        for (int i = 0; i < states.length; i++) {
            states[i] = blockState(cached.states()[i]);
            fluidStates[i] = blockState(cached.fluidStates()[i]);
        }
        return new ChunkData(cached.chunkX(), cached.chunkZ(), cached.heights(), states,
            cached.fluidHeights(), fluidStates, null, 0L, cached.generatedStep(),
            cached.exactSurface() ? TerrainAccuracy.PREDICTED_VANILLA_SURFACE
                : TerrainAccuracy.PREDICTED_BASE_DENSITY,
            List.of(), !cached.exactSurface());
    }

    private static String blockId(BlockState state) {
        return state == null ? null : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static BlockState blockState(String id) {
        if (id == null) return null;
        try {
            return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id))
                .map(block -> block.defaultBlockState()).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ChunkData generateObservedChunkData(LiveChunkSnapshot snapshot) {
        int[] heights = new int[CHUNK_SIZE * CHUNK_SIZE];
        BlockState[] states = new BlockState[CHUNK_SIZE * CHUNK_SIZE];
        int[] fluidHeights = new int[CHUNK_SIZE * CHUNK_SIZE];
        BlockState[] fluidStates = new BlockState[CHUNK_SIZE * CHUNK_SIZE];

        for (int dx = 0; dx < CHUNK_SIZE; dx++) {
            for (int dz = 0; dz < CHUNK_SIZE; dz++) {
                int index = dz * CHUNK_SIZE + dx;
                int y = snapshot.surfaceY(dx, dz);
                SurfaceSample surface = resolveObservedSurface(snapshot, dx, dz, y);
                BlockState topState = surface.surfaceState();
                if (isFluid(topState)) {
                    fluidHeights[index] = surface.surfaceY();
                    fluidStates[index] = topState;
                    heights[index] = surface.solidY() + 1;
                    states[index] = surface.solidState();
                    continue;
                }
                heights[index] = surface.surfaceY();
                states[index] = topState;
            }
        }

        return new ChunkData(snapshot.chunkX(), snapshot.chunkZ(), heights, states, fluidHeights, fluidStates,
            snapshot, snapshot.updatedAt(), 1, TerrainAccuracy.OBSERVED_EXACT,
            List.of(), true);
    }

    private static boolean hasNaturalDecorationSource(ChunkData chunk) {
        return chunk != null && chunk.naturalDecorationsResolved
            && (!chunk.naturalDecorations.isEmpty() || chunk.observedSnapshot != null);
    }

    private static List<GeneratedTerrainHeightmap.NaturalDecorationBlock>
        observedNaturalDecorations(LiveChunkSnapshot snapshot) {
        List<GeneratedTerrainHeightmap.NaturalDecorationBlock> blocks = new ArrayList<>();
        int baseX = snapshot.chunkX() * CHUNK_SIZE;
        int baseZ = snapshot.chunkZ() * CHUNK_SIZE;
        snapshot.forEachBlock((packed, state) -> {
            if (!GeneratedTerrainHeightmap.isNaturalDecorationBlock(state)) return;
            blocks.add(new GeneratedTerrainHeightmap.NaturalDecorationBlock(
                baseX + LiveChunkSnapshot.localX(packed),
                LiveChunkSnapshot.y(packed, snapshot.minY()),
                baseZ + LiveChunkSnapshot.localZ(packed), state));
        });
        blocks.sort(Comparator
            .comparingInt(GeneratedTerrainHeightmap.NaturalDecorationBlock::y)
            .thenComparingInt(GeneratedTerrainHeightmap.NaturalDecorationBlock::z)
            .thenComparingInt(GeneratedTerrainHeightmap.NaturalDecorationBlock::x));
        return List.copyOf(blocks);
    }

    private void refreshObservedChunks(int dimension, int centerChunkX, int centerChunkZ) {
        int refreshed = 0;
        // Authoritative cave data near the camera must win over the much larger
        // predicted cache. Iterating ConcurrentHashMap order could miss these
        // chunks indefinitely once the distant streaming window filled up.
        for (int ring = 0; ring <= 10 && refreshed < OBSERVED_REFRESHES_PER_FRAME; ring++) {
            for (int dx = -ring; dx <= ring && refreshed < OBSERVED_REFRESHES_PER_FRAME; dx++) {
                for (int dz = -ring; dz <= ring && refreshed < OBSERVED_REFRESHES_PER_FRAME; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    long key = ChunkPos.pack(chunkX, chunkZ);
                    ChunkData chunk = chunkCache.get(key);
                    if (chunk == null) continue;
                    TerrainWorldDataSource dataSource = worldDataSource;
                    LiveChunkSnapshot snapshot = dataSource.peekSnapshot(dimension, chunkX, chunkZ);
                    if (snapshot == null) {
                        if (dataSource.isStoredSnapshotRejected(dimension, chunkX, chunkZ)) {
                            rejectStoredObservedSnapshot(lastSeed, dimension, chunkX, chunkZ);
                        }
                        continue;
                    }
                    if (snapshot.updatedAt() <= chunk.observedAt) continue;
                    if (!snapshot.usableForTerrainOverride()) {
                        rejectObservedSnapshot(lastSeed, dimension, snapshot);
                        continue;
                    }
                    ChunkData observed = generateObservedChunkData(snapshot);
                    if (!hasSurfaceData(observed)) {
                        rejectObservedSnapshot(lastSeed, dimension, snapshot);
                        continue;
                    }
                    synchronized (chunkCache) {
                        ChunkData current = chunkCache.get(key);
                        if (current != null && current.observedAt >= observed.observedAt) continue;
                        chunkCache.put(key, observed);
                    }
                    invalidateBakedDependencies(chunkX, chunkZ, true);
                    chunkRevision.incrementAndGet();
                    refreshed++;
                }
            }
        }
    }

    private void rejectObservedSnapshot(long seed, int dimension, LiveChunkSnapshot snapshot) {
        long key = ChunkPos.pack(snapshot.chunkX(), snapshot.chunkZ());
        Long previous = rejectedObservedSnapshots.put(key, snapshot.updatedAt());
        if (previous != null && previous.longValue() == snapshot.updatedAt()) return;
        terrainDiagnostic("observed-snapshot-rejected", seed, dimension,
            snapshot.chunkX(), snapshot.chunkZ(), 1, null, null,
            "populatedColumns=" + snapshot.populatedColumnCount()
                + " nonAirBlocks=" + snapshot.nonAirBlockCount()
                + " updatedAt=" + snapshot.updatedAt());
    }

    private void rejectStoredObservedSnapshot(long seed, int dimension, int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (rejectedObservedSnapshots.putIfAbsent(key, Long.MIN_VALUE) != null) return;
        terrainDiagnostic("observed-snapshot-rejected", seed, dimension, chunkX, chunkZ,
            1, chunkCache.get(key), null,
            "stored snapshot was incomplete, corrupt, or from an unsupported format");
    }

    private boolean isChunkVisible(FreeCamera3D camera, int chunkX, int chunkZ) {
        int centerX = streamAnchorChunkX == Integer.MIN_VALUE
            ? (int) Math.floor(camera.targetX() / CHUNK_SIZE) : streamAnchorChunkX;
        int centerZ = streamAnchorChunkZ == Integer.MIN_VALUE
            ? (int) Math.floor(camera.targetZ() / CHUNK_SIZE) : streamAnchorChunkZ;
        int radius = renderDistanceChunks(camera);
        int cameraX = (int) Math.floor(camera.targetX() / CHUNK_SIZE);
        int cameraZ = (int) Math.floor(camera.targetZ() / CHUNK_SIZE);
        return TerrainLodPolicy.visibleInAnchoredOrCameraWindow(centerX, centerZ,
            cameraX, cameraZ, chunkX, chunkZ, radius, Math.min(radius, RENDER_DIST));
    }

    /** Expands the visible horizon as the orbit camera zooms out. */
    private int renderDistanceChunks(FreeCamera3D camera) {
        return TerrainLodPolicy.flightVisibleRadius(camera.distance(), camera.eyeY(),
            RENDER_DIST, Math.min(MAX_RENDER_DIST, MAX_IN_MEMORY_VISIBLE_RADIUS));
    }

    private double chunkDistanceSq(FreeCamera3D camera, int chunkX, int chunkZ) {
        double chunkWorldX = chunkX * CHUNK_SIZE + CHUNK_SIZE / 2.0;
        double chunkWorldZ = chunkZ * CHUNK_SIZE + CHUNK_SIZE / 2.0;
        double dx = chunkWorldX - camera.targetX();
        double dz = chunkWorldZ - camera.targetZ();
        return dx * dx + dz * dz;
    }

    public boolean isCameraBelowSurface(FreeCamera3D camera) {
        int chunkX = (int) Math.floor(camera.targetX() / CHUNK_SIZE);
        int chunkZ = (int) Math.floor(camera.targetZ() / CHUNK_SIZE);
        return cameraBelowSurface(camera, chunkX, chunkZ);
    }

    private boolean cameraBelowSurface(FreeCamera3D camera, int chunkX, int chunkZ) {
        if (lastDimension == 1) return false;
        ChunkData chunk = chunkCache.get(ChunkPos.pack(chunkX, chunkZ));
        if (chunk == null) return false;
        int localX = Math.floorMod((int) Math.floor(camera.targetX()), CHUNK_SIZE);
        int localZ = Math.floorMod((int) Math.floor(camera.targetZ()), CHUNK_SIZE);
        int index = localZ * CHUNK_SIZE + localX;
        int surfaceY = index >= 0 && index < chunk.heights.length ? chunk.heights[index] : 0;
        if (surfaceY == 0) {
            for (int height : chunk.heights) {
                if (height != 0) {
                    surfaceY = height;
                    break;
                }
            }
        }
        return surfaceY != 0 && camera.eyeY() < surfaceY - 1.0;
    }

    private int chunkDistanceChunks(FreeCamera3D camera, int chunkX, int chunkZ) {
        int centerChunkX = (int) Math.floor(camera.targetX() / CHUNK_SIZE);
        int centerChunkZ = (int) Math.floor(camera.targetZ() / CHUNK_SIZE);
        return Math.max(Math.abs(chunkX - centerChunkX), Math.abs(chunkZ - centerChunkZ));
    }

    private int sampleStep(FreeCamera3D camera, ChunkData chunk) {
        int cameraChunkX = (int) Math.floor(camera.targetX() / CHUNK_SIZE);
        int cameraChunkZ = (int) Math.floor(camera.targetZ() / CHUNK_SIZE);
        if (Math.max(Math.abs(chunk.chunkX - cameraChunkX),
                Math.abs(chunk.chunkZ - cameraChunkZ)) <= CAMERA_EXACT_RING_CHUNKS) {
            return 1;
        }
        return generationStepForChunk(chunk.chunkX, chunk.chunkZ);
    }

    private int generationStepForChunk(int chunkX, int chunkZ) {
        if (!bulkLoading) {
            int distance = Math.max(Math.abs(chunkX - lastCenterChunkX),
                Math.abs(chunkZ - lastCenterChunkZ));
            return TerrainLodPolicy.interactiveSampleStep(distance);
        }
        int anchorX = streamAnchorChunkX == Integer.MIN_VALUE ? lastCenterChunkX : streamAnchorChunkX;
        int anchorZ = streamAnchorChunkZ == Integer.MIN_VALUE ? lastCenterChunkZ : streamAnchorChunkZ;
        return TerrainLodPolicy.sourceSampleStep(anchorX, anchorZ, chunkX, chunkZ);
    }

    private int generationStepForDistance(int chunkDistance) {
        return bulkLoading ? TerrainLodPolicy.sampleStep(chunkDistance)
            : TerrainLodPolicy.interactiveSampleStep(chunkDistance);
    }

    private void renderChunkMesh(GeoSink builder, GeoSink fluidBuilder, ChunkData chunk,
                                 ChunkAccess generatedBlocks, int dimension, int step,
                                 boolean textureDebugView) {
        step = Math.max(step, chunk.generatedStep);
        int baseX = chunk.chunkX * CHUNK_SIZE;
        int baseZ = chunk.chunkZ * CHUNK_SIZE;
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        for (int dx = 0; dx < CHUNK_SIZE; dx += step) {
            for (int dz = 0; dz < CHUNK_SIZE; dz += step) {
                int index = dz * CHUNK_SIZE + dx;
                int y = chunk.heights[index];
                BlockState state = chunk.states[index];
                if (state == null) continue;
                if (!isTerrainSupportingSurface(state)) continue;
                // An unsupported flower/feature texture must not silently become a
                // stone surface. Exact observed scanning normally resolves down to
                // supporting terrain; if a material is still unsupported, leave
                // that cell for the supported-neighbor fallback instead.
                if (atlas.usesFallback(state)) continue;
                BlockState renderState = state;

                double wx = baseX + dx;
                double wz = baseZ + dz;
                int width = Math.min(step, CHUNK_SIZE - dx);
                int depth = Math.min(step, CHUNK_SIZE - dz);
                if (atlas.region(renderState) == null) continue;

                // Every LOD tile gets its four exterior sides. The previous coarse
                // path emitted only a top quad, making distant blocks look like
                // missing-sided sheets when the camera pitched below the horizon.
                renderSurfaceCube(builder, atlas, wx, y - 1, wz, width, depth, renderState, textureDebugView);
                renderHeightFaceX(builder, atlas, chunk, generatedBlocks, dx, dz, step,
                    baseX, baseZ, wx, wz, width, depth, textureDebugView);
                renderHeightFaceZ(builder, atlas, chunk, generatedBlocks, dx, dz, step,
                    baseX, baseZ, wx, wz, width, depth, textureDebugView);

                BlockState fluidState = chunk.fluidStates[index];
                int fluidY = chunk.fluidHeights[index];
                if (fluidState != null && fluidY > y) {
                    TextureRegion fluidRegion = atlas.region(fluidState);
                    if (fluidRegion != null && TerrainMaterialPolicy.renderAtlasMaterial(atlas.usesFallback(fluidState))) {
                        RgbaColor fluidTint = textureTintAt(fluidState,
                            wx + width * 0.5, fluidY, wz + depth * 0.5);
                        RgbaColor transparentFluid = new RgbaColor(fluidTint.r, fluidTint.g, fluidTint.b, 150);
                        fluidBuilder.ensureQuadCapacity();
                        fluidBuilder.quad(
                            fluidBuilder.vec3(wx, fluidY, wz).vec2(fluidRegion.x1, fluidRegion.y1).color(transparentFluid).next(),
                            fluidBuilder.vec3(wx + width, fluidY, wz).vec2(fluidRegion.x2, fluidRegion.y1).color(transparentFluid).next(),
                            fluidBuilder.vec3(wx + width, fluidY, wz + depth).vec2(fluidRegion.x2, fluidRegion.y2).color(transparentFluid).next(),
                            fluidBuilder.vec3(wx, fluidY, wz + depth).vec2(fluidRegion.x1, fluidRegion.y2).color(transparentFluid).next()
                        );
                    }
                }
            }
        }
    }

    /** Renders an inexpensive temporary terrain tile while its detailed mesh bakes. */
    private int renderCoarsePlaceholder(GeoSink builder, ChunkData chunk, int dimension, boolean topOnly) {
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        CoarseSurface surface = coarseSurface(chunk, dimension, atlas);
        if (surface == null || atlas.region(surface.state) == null) return 0;
        double x = chunk.chunkX * CHUNK_SIZE;
        double z = chunk.chunkZ * CHUNK_SIZE;
        renderSurfaceTop(builder, atlas, x, surface.y - 1, z, CHUNK_SIZE, CHUNK_SIZE, surface.state);
        if (topOnly) return 4;
        return 4 + renderCoarseTransitionSides(builder, atlas, chunk, dimension, surface);
    }

    /** Renders the best direct surface grid that fits while a stored mesh is unavailable. */
    private int renderMinimumSurfaceFallback(GeoSink solidBuilder, GeoSink fluidBuilder,
                                             ChunkData chunk, int dimension,
                                             int requestedDataStep, int fallbackStep,
                                             int vertexBudget) {
        if (!hasSurfaceData(chunk)) return 0;
        if (requestedDataStep != 1) {
            return vertexBudget >= 4 && TerrainLodPolicy.permitsCoarsePlaceholder(requestedDataStep)
                ? renderCoarsePlaceholder(solidBuilder, chunk, dimension, true) : 0;
        }
        if (!isExactSurface(chunk)
            || fallbackStep < 1
            || vertexBudget < TerrainLodPolicy.EXACT_FALLBACK_TOP_VERTICES) return 0;

        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        int emitted = 0;
        int step = fallbackStep;
        int baseX = chunk.chunkX * CHUNK_SIZE;
        int baseZ = chunk.chunkZ * CHUNK_SIZE;
        for (int dz = 0; dz < CHUNK_SIZE; dz += step) {
            for (int dx = 0; dx < CHUNK_SIZE; dx += step) {
                SurfaceCell cell = representativeSurfaceCell(chunk, dx, dz, step, atlas);
                if (cell == null) continue;
                GeoSink target = isFluid(cell.state()) ? fluidBuilder : solidBuilder;
                renderSurfaceTop(target, atlas, baseX + dx, cell.y() - 1, baseZ + dz,
                    step, step, cell.state());
                emitted += 4;
            }
        }
        emitted += renderExactFallbackSides(solidBuilder, chunk, atlas, step,
            Math.max(0, vertexBudget - emitted));
        return emitted;
    }

    private static int fallbackDisplayStep(int requestedDataStep, int requestedDisplayStep,
                                           int vertexBudget) {
        if (requestedDataStep != 1) {
            return vertexBudget >= 4 ? TerrainLodPolicy.COARSE_SAMPLE_STEP : -1;
        }
        int step = Math.max(1, requestedDisplayStep);
        if (step == 1) {
            return surfaceTopVertices(1) <= vertexBudget ? 1 : -1;
        }
        while (step < TerrainLodPolicy.EXACT_DISPLAY_MAX_STEP
            && surfaceTopVertices(step) > vertexBudget) {
            step = TerrainLodPolicy.nextCoarserStep(step);
        }
        return surfaceTopVertices(step) <= vertexBudget ? step : -1;
    }

    private static int surfaceTopVertices(int step) {
        int samples = Math.max(1, CHUNK_SIZE / Math.max(1, step));
        return samples * samples * 4;
    }

    private int renderExactFallbackSides(GeoSink builder, ChunkData chunk,
                                         BlockTextureAtlas atlas, int step,
                                         int vertexBudget) {
        if (vertexBudget < 4) return 0;
        int emitted = 0;
        int baseX = chunk.chunkX * CHUNK_SIZE;
        int baseZ = chunk.chunkZ * CHUNK_SIZE;
        for (int dz = 0; dz < CHUNK_SIZE; dz += step) {
            for (int dx = 0; dx < CHUNK_SIZE; dx += step) {
                SurfaceCell current = representativeSurfaceCell(chunk, dx, dz, step, atlas);
                if (current == null) continue;

                ChunkData eastChunk = dx + step < CHUNK_SIZE ? chunk
                    : chunkCache.get(ChunkPos.pack(chunk.chunkX + 1, chunk.chunkZ));
                int eastX = dx + step < CHUNK_SIZE ? dx + step : 0;
                SurfaceCell east = eastChunk == null ? null
                    : representativeSurfaceCell(eastChunk, eastX, dz, step, atlas);
                if (east != null && current.y() != east.y() && emitted + 4 <= vertexBudget) {
                    emitted += renderFallbackHeightSide(builder, atlas, current, east,
                        baseX + dx + step, baseZ + dz,
                        baseX + dx + step, baseZ + Math.min(CHUNK_SIZE, dz + step));
                }

                ChunkData southChunk = dz + step < CHUNK_SIZE ? chunk
                    : chunkCache.get(ChunkPos.pack(chunk.chunkX, chunk.chunkZ + 1));
                int southZ = dz + step < CHUNK_SIZE ? dz + step : 0;
                SurfaceCell south = southChunk == null ? null
                    : representativeSurfaceCell(southChunk, dx, southZ, step, atlas);
                if (south != null && current.y() != south.y() && emitted + 4 <= vertexBudget) {
                    emitted += renderFallbackHeightSide(builder, atlas, current, south,
                        baseX + dx, baseZ + dz + step,
                        baseX + Math.min(CHUNK_SIZE, dx + step), baseZ + dz + step);
                }
                if (emitted + 4 > vertexBudget) return emitted;
            }
        }
        return emitted;
    }

    private int renderFallbackHeightSide(GeoSink builder, BlockTextureAtlas atlas,
                                         SurfaceCell first, SurfaceCell second,
                                         double x1, double z1, double x2, double z2) {
        SurfaceCell higher = first.y() >= second.y() ? first : second;
        if (isFluid(higher.state())) return 0;
        TextureRegion region = atlas.sideRegion(higher.state());
        if (region == null
            || !TerrainMaterialPolicy.renderAtlasMaterial(atlas.sideUsesFallback(higher.state()))) return 0;
        int top = Math.max(first.y(), second.y());
        int bottom = Math.max(Math.min(first.y(), second.y()), top - MAX_SIDE_FACE_HEIGHT);
        if (bottom >= top) return 0;
        RgbaColor tint = sideTextureTint(higher.state());
        RgbaColor sideTint = new RgbaColor((int) (tint.r * 0.7), (int) (tint.g * 0.7),
            (int) (tint.b * 0.7), tint.a);
        int emittedVertices = emitSide(builder, region, sideTint, x1, bottom, z1, x2, top, z2);
        if (higher.state().is(Blocks.GRASS_BLOCK)) {
            emitGrassSideOverlay(builder, atlas, x1, top - 1, z1, x2, top, z2,
                (x1 + x2) * 0.5, (z1 + z2) * 0.5);
        }
        return emittedVertices;
    }

    private SurfaceCell representativeSurfaceCell(ChunkData chunk, int startX, int startZ,
                                                   int step, BlockTextureAtlas atlas) {
        SurfaceCell first = null;
        for (int z = startZ; z < Math.min(CHUNK_SIZE, startZ + step); z++) {
            for (int x = startX; x < Math.min(CHUNK_SIZE, startX + step); x++) {
                SurfaceCell candidate = surfaceCell(chunk, x, z);
                if (candidate == null) continue;
                if (first == null) first = candidate;
                if (!atlas.usesFallback(candidate.state())) return candidate;
            }
        }
        return first != null && !atlas.usesFallback(first.state()) ? first : null;
    }

    private static boolean hasSurfaceData(ChunkData chunk) {
        if (chunk == null) return false;
        for (int i = 0; i < chunk.states.length; i++) {
            BlockState solid = chunk.states[i];
            BlockState fluid = chunk.fluidStates[i];
            if (solid != null && !solid.isAir() || fluid != null && !fluid.isAir()) return true;
        }
        return false;
    }

    /**
     * Fills a bounded number of cache holes with a temporary, textured tile. A
     * missing chunk is normally just waiting for the generator queue; drawing a
     * neutral tile keeps the map continuous until the exact height/material arrives.
     */
    private int renderMissingChunkPlaceholders(GeoSink builder, FreeCamera3D camera, long seed,
                                                int centerChunkX, int centerChunkZ,
                                                int dimension, int renderedVertices,
                                                int renderedChunks) {
        // A placeholder was useful while the original frame mesh was rebuilt,
        // but it is a misleading floating green slab once persistent GPU batches
        // are active. It also follows the camera when the stream anchor changes,
        // which is the source of the moving green chunk/empty wake. Keep the code
        // for diagnostics, but never draw it in the normal renderer.
        return 0;
        /*
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        BlockState state = fallbackTerrainMaterial(dimension);
        TextureRegion region = atlas.region(state);
        if (region == null) {
            terrainDiagnostic("placeholder-no-atlas-region", seed, dimension, centerChunkX,
                centerChunkZ, 16, null, null, "fallbackMaterial=" + state);
            return 0;
        }
        refreshMissingPlaceholderCache(camera, centerChunkX, centerChunkZ);
        int emitted = 0;
        int maxVertices = MAX_TERRAIN_VERTICES_PER_FRAME - renderedVertices;
        for (long key : missingPlaceholderCache) {
            if (emitted >= MAX_MISSING_PLACEHOLDERS_PER_FRAME
                || renderedChunks + emitted >= MAX_TERRAIN_CHUNKS_PER_FRAME
                || emitted * 4 + 4 > maxVertices) break;
            if (chunkCache.containsKey(key)) continue;
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (!isChunkVisible(camera, cx, cz)) continue;
            double x = cx * (double) CHUNK_SIZE;
            double z = cz * (double) CHUNK_SIZE;
            renderSurfaceTop(builder, atlas, x, 63, z, CHUNK_SIZE, CHUNK_SIZE, state);
            emitted++;
        }
        return emitted * 4;
        */
    }

    private void refreshMissingPlaceholderCache(FreeCamera3D camera,
                                                 int centerChunkX, int centerChunkZ) {
        long now = System.nanoTime();
        boolean moved = Math.abs(centerChunkX - missingPlaceholderCenterX) >= 8
            || Math.abs(centerChunkZ - missingPlaceholderCenterZ) >= 8;
        if (!missingPlaceholderCache.isEmpty() && !moved
            && now - missingPlaceholderRefreshNanos < PLACEHOLDER_REFRESH_NANOS) return;

        List<Long> missing = new ArrayList<>(MAX_MISSING_PLACEHOLDERS_PER_FRAME);
        TerrainStreamingCursor cursor = new TerrainStreamingCursor();
        for (int examined = 0; examined < MAX_PLACEHOLDER_POSITIONS_EXAMINED
            && missing.size() < MAX_MISSING_PLACEHOLDERS_PER_FRAME; examined++) {
            TerrainStreamingCursor.Coordinate coordinate = cursor.next(centerChunkX, centerChunkZ);
            if (coordinate.ring() > MISSING_PLACEHOLDER_RADIUS) break;
            long key = ChunkPos.pack(coordinate.chunkX(), coordinate.chunkZ());
            if (!chunkCache.containsKey(key)
                && isChunkVisible(camera, coordinate.chunkX(), coordinate.chunkZ())) {
                missing.add(key);
            }
        }
        missingPlaceholderCache = List.copyOf(missing);
        missingPlaceholderCenterX = centerChunkX;
        missingPlaceholderCenterZ = centerChunkZ;
        missingPlaceholderRefreshNanos = now;
    }

    private CoarseSurface coarseSurface(ChunkData chunk, int dimension, BlockTextureAtlas atlas) {
        BlockState state = null;
        int y = 64;
        // Water/lava is the visible surface of a low-LOD ocean or lava sea. The
        // previous solid-first selection painted every distant ocean as stone.
        for (int i = 0; i < chunk.fluidStates.length; i++) {
            BlockState candidate = chunk.fluidStates[i];
            if (candidate != null && !candidate.isAir() && !atlas.usesFallback(candidate)
                && chunk.fluidHeights[i] >= chunk.heights[i]) {
                state = candidate;
                y = chunk.fluidHeights[i];
                break;
            }
        }
        int selected = -1;
        if (state == null) {
            // Pick the highest column, then the most common material at that
            // height. ConcurrentHashMap iteration order used to pick the first
            // array slot, which is why whole chunks could turn into one dark
            // green/stone slab even when their actual surface was sand or snow.
            int highest = Integer.MIN_VALUE;
            for (int i = 0; i < chunk.states.length; i++) {
                BlockState candidate = chunk.states[i];
                if (candidate == null || candidate.isAir() || atlas.usesFallback(candidate)
                    || !TerrainMaterialPolicy.terrainSupportingSurface(blockId(candidate))) continue;
                if (chunk.heights[i] > highest) highest = chunk.heights[i];
            }
            if (highest != Integer.MIN_VALUE) {
                Map<String, Integer> counts = new java.util.HashMap<>();
                for (int i = 0; i < chunk.states.length; i++) {
                    BlockState candidate = chunk.states[i];
                    if (candidate == null || candidate.isAir() || atlas.usesFallback(candidate)
                        || !TerrainMaterialPolicy.terrainSupportingSurface(blockId(candidate))) continue;
                    if (chunk.heights[i] < highest - 2) continue;
                    counts.merge(blockId(candidate), 1, Integer::sum);
                }
                String selectedId = counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
                for (int i = 0; i < chunk.states.length; i++) {
                    BlockState candidate = chunk.states[i];
                    if (candidate != null && selectedId != null
                        && blockId(candidate).equals(selectedId)
                        && chunk.heights[i] >= highest - 2) {
                        selected = i;
                        break;
                    }
                }
            }
        }
        if (state == null && selected < 0) {
            for (int i = 0; i < chunk.fluidStates.length; i++) {
                BlockState candidate = chunk.fluidStates[i];
                if (candidate != null && !candidate.isAir() && !atlas.usesFallback(candidate)) {
                    selected = i;
                    break;
                }
            }
        }
        if (state == null) {
            state = selected < 0
                ? fallbackTerrainMaterial(dimension)
                : chunk.states[selected] != null ? chunk.states[selected] : chunk.fluidStates[selected];
            y = selected < 0 ? 64
                : chunk.states[selected] != null ? chunk.heights[selected] : chunk.fluidHeights[selected];
        }
        return state == null ? null : new CoarseSurface(state, y);
    }

    private boolean canRenderCoarseSkirt(VisibleChunk visible, int renderedVertices,
                                          int renderedChunks, int visibleChunks) {
        double skirtRadius = COARSE_SKIRT_DISTANCE_CHUNKS * (double) CHUNK_SIZE;
        if (visible.distanceSq() > skirtRadius * skirtRadius) return false;
        int chunksAfterThis = Math.max(0, visibleChunks - renderedChunks - 1);
        long required = (long) renderedVertices + 20L + (long) chunksAfterThis * 4L;
        return required <= MAX_TERRAIN_VERTICES_PER_FRAME;
    }

    /**
     * Extends a coarse tile downward so height changes at an LOD boundary cannot
     * expose the black clear color. The skirt is bounded to nearby terrain and uses
     * the selected block's real side texture; farther tiles remain a single quad.
     */
    private int renderCoarseTransitionSides(GeoSink builder, BlockTextureAtlas atlas,
                                            ChunkData chunk, int dimension, CoarseSurface surface) {
        TextureRegion sideRegion = atlas.sideRegion(surface.state);
        if (sideRegion == null
            || !TerrainMaterialPolicy.renderAtlasMaterial(atlas.sideUsesFallback(surface.state))) return 0;

        RgbaColor tint = sideTextureTint(surface.state);
        RgbaColor sideTint = new RgbaColor((int) (tint.r * 0.68), (int) (tint.g * 0.68),
            (int) (tint.b * 0.68), tint.a);
        double x = chunk.chunkX * CHUNK_SIZE;
        double z = chunk.chunkZ * CHUNK_SIZE;
        double x2 = x + CHUNK_SIZE;
        double z2 = z + CHUNK_SIZE;
        int vertices = 0;
        vertices += renderCoarseEdge(builder, atlas, sideRegion, sideTint, surface.state, surface.y,
            chunk.chunkX, chunk.chunkZ - 1, dimension, x, z, x2, z);
        vertices += renderCoarseEdge(builder, atlas, sideRegion, sideTint, surface.state, surface.y,
            chunk.chunkX + 1, chunk.chunkZ, dimension, x2, z, x2, z2);
        vertices += renderCoarseEdge(builder, atlas, sideRegion, sideTint, surface.state, surface.y,
            chunk.chunkX, chunk.chunkZ + 1, dimension, x2, z2, x, z2);
        vertices += renderCoarseEdge(builder, atlas, sideRegion, sideTint, surface.state, surface.y,
            chunk.chunkX - 1, chunk.chunkZ, dimension, x, z2, x, z);
        return vertices;
    }

    private int renderLodTransitionSkirts(GeoSink builder, List<VisibleChunk> visibleChunks,
                                          int dimension, int vertexBudget) {
        if (vertexBudget < 4) return 0;
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        int emitted = 0;
        for (VisibleChunk visible : visibleChunks) {
            ChunkData chunk = visible.chunk();
            long key = ChunkPos.pack(chunk.chunkX, chunk.chunkZ);
            Integer step = displayedSurfaceSteps.get(key);
            if (step == null) continue;
            ChunkData east = chunkCache.get(ChunkPos.pack(chunk.chunkX + 1, chunk.chunkZ));
            Integer eastStep = displayedSurfaceSteps.get(
                ChunkPos.pack(chunk.chunkX + 1, chunk.chunkZ));
            if (east != null && TerrainLodPolicy.requiresTransitionSkirt(step, eastStep)) {
                emitted += renderLodBoundary(builder, atlas, chunk, east, true,
                    Math.min(step, eastStep), vertexBudget - emitted);
            }
            if (emitted + 4 > vertexBudget) break;
            ChunkData south = chunkCache.get(ChunkPos.pack(chunk.chunkX, chunk.chunkZ + 1));
            Integer southStep = displayedSurfaceSteps.get(
                ChunkPos.pack(chunk.chunkX, chunk.chunkZ + 1));
            if (south != null && TerrainLodPolicy.requiresTransitionSkirt(step, southStep)) {
                emitted += renderLodBoundary(builder, atlas, chunk, south, false,
                    Math.min(step, southStep), vertexBudget - emitted);
            }
            if (emitted + 4 > vertexBudget) break;
        }
        return emitted;
    }

    private int renderLodBoundary(GeoSink builder, BlockTextureAtlas atlas,
                                  ChunkData first, ChunkData second, boolean eastWest,
                                  int segmentStep, int vertexBudget) {
        int emitted = 0;
        int baseX = first.chunkX * CHUNK_SIZE;
        int baseZ = first.chunkZ * CHUNK_SIZE;
        for (int offset = 0; offset < CHUNK_SIZE && emitted + 4 <= vertexBudget;
             offset += Math.max(1, segmentStep)) {
            int firstX = eastWest ? CHUNK_SIZE - 1 : offset;
            int firstZ = eastWest ? offset : CHUNK_SIZE - 1;
            int secondX = eastWest ? 0 : offset;
            int secondZ = eastWest ? offset : 0;
            SurfaceCell a = surfaceCell(first, firstX, firstZ);
            SurfaceCell b = surfaceCell(second, secondX, secondZ);
            if (a == null || b == null) continue;
            int top = Math.max(a.y(), b.y());
            int bottom = Math.min(a.y(), b.y());
            // Equal surfaces already meet exactly. The old forced two-block wall
            // drew a continuous colored ring at every LOD handoff.
            if (bottom >= top) continue;
            bottom = Math.max(bottom, top - MAX_SIDE_FACE_HEIGHT);
            BlockState state = a.y() >= b.y() ? a.state() : b.state();
            TextureRegion region = atlas.sideRegion(state);
            if (region == null
                || !TerrainMaterialPolicy.renderAtlasMaterial(atlas.sideUsesFallback(state))) continue;
            RgbaColor tint = sideTextureTint(state);
            RgbaColor sideTint = new RgbaColor((int) (tint.r * 0.7), (int) (tint.g * 0.7),
                (int) (tint.b * 0.7), tint.a);
            int length = Math.min(Math.max(1, segmentStep), CHUNK_SIZE - offset);
            int emittedSideVertices;
            if (eastWest) {
                double x = baseX + CHUNK_SIZE;
                emittedSideVertices = emitSide(builder, region, sideTint, x, bottom, baseZ + offset,
                    x, top, baseZ + offset + length);
            } else {
                double z = baseZ + CHUNK_SIZE;
                emittedSideVertices = emitSide(builder, region, sideTint, baseX + offset, bottom, z,
                    baseX + offset + length, top, z);
            }
            emitted += emittedSideVertices;
        }
        return emitted;
    }

    private SurfaceCell surfaceCell(ChunkData chunk, int localX, int localZ) {
        int step = Math.max(1, chunk.generatedStep);
        int sampledX = Math.min(CHUNK_SIZE - 1, Math.floorDiv(localX, step) * step);
        int sampledZ = Math.min(CHUNK_SIZE - 1, Math.floorDiv(localZ, step) * step);
        int index = sampledZ * CHUNK_SIZE + sampledX;
        BlockState state = chunk.states[index];
        int y = chunk.heights[index];
        if (chunk.fluidStates[index] != null && chunk.fluidHeights[index] >= y) {
            state = chunk.fluidStates[index];
            y = chunk.fluidHeights[index];
        }
        return state == null || state.isAir() ? null : new SurfaceCell(y, state);
    }

    private int renderCoarseEdge(GeoSink builder, BlockTextureAtlas atlas,
                                 TextureRegion sideRegion, RgbaColor sideTint, BlockState material, int topY,
                                 int neighborChunkX, int neighborChunkZ, int dimension,
                                 double x1, double z1, double x2, double z2) {
        int bottomY = topY - COARSE_SKIRT_DEPTH;
        ChunkData neighbor = chunkCache.get(ChunkPos.pack(neighborChunkX, neighborChunkZ));
        if (neighbor != null) {
            CoarseSurface neighborSurface = coarseSurface(neighbor, dimension, atlas);
            if (neighborSurface != null) {
                if (neighborSurface.y >= topY) return 0;
                bottomY = Math.max(bottomY, neighborSurface.y);
            }
        }
        if (bottomY >= topY) return 0;
        int emitted = emitSide(builder, sideRegion, sideTint, x1, bottomY, z1, x2, topY, z2);
        if (material != null && material.is(Blocks.GRASS_BLOCK)) {
            emitted += emitGrassSideOverlay(builder, atlas, x1, topY - 1, z1, x2, topY, z2,
                (x1 + x2) * 0.5, (z1 + z2) * 0.5);
        }
        return emitted;
    }

    private static BlockState fallbackTerrainMaterial(int dimension) {
        if (dimension == -1) return Blocks.NETHERRACK.defaultBlockState();
        if (dimension == 1) return Blocks.END_STONE.defaultBlockState();
        return Blocks.STONE.defaultBlockState();
    }

    private void renderStatusOverlay(String title, String detail) {
        Minecraft mc = Minecraft.getInstance();
        double screenWidth = mc.getWindow().getWidth();
        double screenHeight = mc.getWindow().getHeight();
        double titleWidth = overlayRenderer.textWidth(title);
        double detailWidth = overlayRenderer.textWidth(detail);
        double width = Math.max(titleWidth, detailWidth) + 36;
        double x = (screenWidth - width) / 2.0;
        double y = screenHeight / 2.0 - 24;

        try (TerrainOverlayRenderer.ColorCanvas canvas = overlayRenderer.beginColors()) {
            canvas.quad(x, y, width, 48, new RgbaColor(8, 12, 16, 210));
            canvas.boxLines(x, y, width, 48, new RgbaColor(80, 120, 130, 220));
        }

        overlayRenderer.text(frameGraphics, title, x + (width - titleWidth) / 2.0, y + 10,
            new RgbaColor(235, 240, 245));
        overlayRenderer.text(frameGraphics, detail, x + (width - detailWidth) / 2.0, y + 28,
            failedChunkGenerations.get() > 0
                ? new RgbaColor(245, 180, 120) : new RgbaColor(165, 178, 188));
    }

    private String undergroundStatusDetail() {
        int baking = inFlightBakes.size();
        int building = activeGpuBuild == null ? 0 : 1;
        int queued = queuedGpuBatches.size();
        int packing = gpuUploadPackingJobs.get();
        int uploads = pendingGpuUploads.size();
        long ready = caveBakesWithGeometry.get();
        long empty = caveBakesWithoutGeometry.get();
        if (baking > 0) {
            return baking + " baking | " + ready + " ready | GPU "
                + queued + '/' + building + '/' + packing + '/' + uploads;
        }
        if (queued + building + packing + uploads > 0) {
            return ready + " cave meshes | GPU "
                + queued + '/' + building + '/' + packing + '/' + uploads;
        }
        if (ready == 0L && empty > 0L) {
            return empty + " chunks produced no cave faces; see terrain log";
        }
        return "Waiting for visible cave meshes";
    }

    private void renderHeightFaceX(GeoSink builder, BlockTextureAtlas atlas, ChunkData chunk,
                                   ChunkAccess generatedBlocks,
                                   int dx, int dz, int step, int baseX, int baseZ,
                                   double wx, double wz, int width, int depth, boolean textureDebugView) {
        int nx = dx + step;
        int index = dz * CHUNK_SIZE + dx;
        ChunkData neighborChunk = chunk;
        int neighborIndex;
        int neighborWorldX;
        if (nx >= CHUNK_SIZE) {
            neighborChunk = chunkCache.get(ChunkPos.pack(chunk.chunkX + 1, chunk.chunkZ));
            neighborWorldX = baseX + CHUNK_SIZE;
            if (neighborChunk == null) {
                // Missing exact neighbours are streaming holes, not cliffs. A
                // temporary vertical wall is far more distracting than open sky.
                if (!isExactSurface(chunk)) {
                    renderExteriorHeightFace(builder, atlas, neighborWorldX, wz, neighborWorldX,
                        wz + depth, chunk.heights[index], chunk.states[index], chunk, generatedBlocks,
                        baseX + dx, baseZ + dz, textureDebugView);
                }
                return;
            }
            SurfaceCell neighbor = surfaceCell(neighborChunk, 0, dz);
            if (neighbor == null) return;
            neighborIndex = sampledSurfaceIndex(neighborChunk, 0, dz);
        } else {
            neighborIndex = dz * CHUNK_SIZE + nx;
            neighborWorldX = baseX + nx;
        }
        int materialX = chunk.heights[index] >= neighborChunk.heights[neighborIndex]
            ? baseX + dx : neighborWorldX;
        int materialZ = baseZ + dz;
        renderVerticalHeightFace(builder, atlas,
            neighborWorldX, wz, neighborWorldX, wz + depth,
            chunk.heights[index], chunk.states[index],
            neighborChunk.heights[neighborIndex], neighborChunk.states[neighborIndex],
            chunk, generatedBlocks, materialX, materialZ, textureDebugView);
    }

    private void renderHeightFaceZ(GeoSink builder, BlockTextureAtlas atlas, ChunkData chunk,
                                   ChunkAccess generatedBlocks,
                                   int dx, int dz, int step, int baseX, int baseZ,
                                   double wx, double wz, int width, int depth, boolean textureDebugView) {
        int nz = dz + step;
        int index = dz * CHUNK_SIZE + dx;
        ChunkData neighborChunk = chunk;
        int neighborIndex;
        int neighborWorldZ;
        if (nz >= CHUNK_SIZE) {
            neighborChunk = chunkCache.get(ChunkPos.pack(chunk.chunkX, chunk.chunkZ + 1));
            neighborWorldZ = baseZ + CHUNK_SIZE;
            if (neighborChunk == null) {
                if (!isExactSurface(chunk)) {
                    renderExteriorHeightFace(builder, atlas, wx, neighborWorldZ, wx + width,
                        neighborWorldZ, chunk.heights[index], chunk.states[index], chunk,
                        generatedBlocks, baseX + dx, baseZ + dz, textureDebugView);
                }
                return;
            }
            SurfaceCell neighbor = surfaceCell(neighborChunk, dx, 0);
            if (neighbor == null) return;
            neighborIndex = sampledSurfaceIndex(neighborChunk, dx, 0);
        } else {
            neighborIndex = nz * CHUNK_SIZE + dx;
            neighborWorldZ = baseZ + nz;
        }
        int materialX = baseX + dx;
        int materialZ = chunk.heights[index] >= neighborChunk.heights[neighborIndex]
            ? baseZ + dz : neighborWorldZ;
        renderVerticalHeightFace(builder, atlas,
            wx, neighborWorldZ, wx + width, neighborWorldZ,
            chunk.heights[index], chunk.states[index],
            neighborChunk.heights[neighborIndex], neighborChunk.states[neighborIndex],
            chunk, generatedBlocks, materialX, materialZ, textureDebugView);
    }

    private void renderExteriorHeightFace(GeoSink builder, BlockTextureAtlas atlas,
                                          double x1, double z1, double x2, double z2,
                                          int height, BlockState state, ChunkData chunk,
                                          ChunkAccess generatedBlocks, int materialX, int materialZ,
                                          boolean textureDebugView) {
        if (state == null || state.isAir()) return;
        renderVerticalHeightFace(builder, atlas, x1, z1, x2, z2, height, state,
            height - COARSE_SKIRT_DEPTH, state, chunk, generatedBlocks, materialX, materialZ,
            textureDebugView);
    }

    private static int sampledSurfaceIndex(ChunkData chunk, int localX, int localZ) {
        int step = Math.max(1, chunk.generatedStep);
        int sampledX = Math.min(CHUNK_SIZE - 1, Math.floorDiv(localX, step) * step);
        int sampledZ = Math.min(CHUNK_SIZE - 1, Math.floorDiv(localZ, step) * step);
        return sampledZ * CHUNK_SIZE + sampledX;
    }

    private void renderVerticalHeightFace(GeoSink builder, BlockTextureAtlas atlas,
                                           double x1, double z1, double x2, double z2,
                                           int heightA, BlockState stateA,
                                           int heightB, BlockState stateB,
                                           ChunkData chunk, ChunkAccess generatedBlocks,
                                           int materialX, int materialZ, boolean textureDebugView) {
        if (stateA == null || stateB == null || stateA.isAir() || stateB.isAir()) return;
        int diff = Math.abs(heightA - heightB);
        if (!TerrainLodPolicy.shouldRenderHeightTransition(diff)) return;

        int top = Math.max(heightA, heightB);
        int bottom = Math.min(heightA, heightB);
        BlockState fallbackState = heightA >= heightB ? stateA : stateB;
        int verticalStep = Math.max(1, (int) Math.round(
            Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1))));

        // A cliff exposes a vertical stack of different blocks. Sampling every
        // visible Y level at step 1 preserves vanilla-looking layers. Distant
        // step-2/4 meshes group the same number of vertical blocks as their
        // horizontal footprint so the retained minimum mesh stays bounded.
        for (int y = bottom; y < top; y += verticalStep) {
            BlockState state = surfaceLayerState(chunk, generatedBlocks, materialX, y, materialZ);
            if (state == null || state.isAir() || isFluid(state)) state = fallbackState;
            // A heightfield only knows its top material. Grass is a one-block
            // surface cap; never stretch it through an entire cliff or chunk.
            if (state != null && state.is(Blocks.GRASS_BLOCK) && y < top - 1) {
                state = Blocks.DIRT.defaultBlockState();
            }
            if (state == null || state.isAir() || isFluid(state)) continue;

            TextureRegion region = atlas.sideRegion(state);
            if (region == null || !TerrainMaterialPolicy.renderAtlasMaterial(atlas.sideUsesFallback(state))) continue;
            RgbaColor tint = sideTextureTint(state);
            RgbaColor sideTint = new RgbaColor((int) (tint.r * 0.72), (int) (tint.g * 0.72),
                (int) (tint.b * 0.72), tint.a);

            builder.ensureQuadCapacity();
            int a = builder.vec3(x1, y, z1).vec2(region.x1, region.y2).color(sideTint).next();
            int b = builder.vec3(x2, y, z2).vec2(region.x2, region.y2).color(sideTint).next();
            int segmentTop = Math.min(top, y + verticalStep);
            int c = builder.vec3(x2, segmentTop, z2).vec2(region.x2, region.y1).color(sideTint).next();
            int d = builder.vec3(x1, segmentTop, z1).vec2(region.x1, region.y1).color(sideTint).next();
            builder.quad(a, b, c, d);
            if (state.is(Blocks.GRASS_BLOCK) && segmentTop >= top) {
                // The overlay is a transparent second model element. It covers
                // only the upper block, exactly like grass_block.json.
                emitGrassSideOverlay(builder, atlas, x1, y, z1, x2, segmentTop, z2,
                    (x1 + x2) * 0.5, (z1 + z2) * 0.5);
            }
        }
    }

    private BlockState surfaceLayerState(ChunkData chunk, ChunkAccess generatedBlocks,
                                         int worldX, int y, int worldZ) {
        if (chunk.observedSnapshot != null) {
            int localX = worldX - chunk.chunkX * CHUNK_SIZE;
            int localZ = worldZ - chunk.chunkZ * CHUNK_SIZE;
            if (localX >= 0 && localX < CHUNK_SIZE && localZ >= 0 && localZ < CHUNK_SIZE) {
                return chunk.observedSnapshot.block(localX, y, localZ);
            }
        }
        if (generatedBlocks == null || y < generatedBlocks.getMinY() || y >= generatedBlocks.getMaxY()) {
            return null;
        }
        return generatedBlocks.getBlockState(new BlockPos(worldX, y, worldZ));
    }

    /**
     * Coarse height samples can span a cliff and turn a single guessed column into
     * an obvious textured tower. Small discontinuities preserve a useful terrain
     * silhouette; larger coarse discontinuities wait for a detail upgrade.
     */
    private static int stepAwareSideHeightLimit(double x1, double z1, double x2, double z2) {
        double footprint = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        return footprint <= 2.0 ? MAX_SIDE_FACE_HEIGHT : 12;
    }

    private void renderSurfaceCube(GeoSink builder, BlockTextureAtlas atlas,
                                   double x, double y, double z, int width, int depth, BlockState state,
                                   boolean textureDebugView) {
        // A heightfield needs a top for every sample and vertical faces only where
        // adjacent heights differ. Emitting all four sides for every sample created
        // millions of completely buried quads and exhausted the frame budget after
        // only a small center patch. renderHeightFaceX/Z add the visible sides.
        renderSurfaceTop(builder, atlas, x, y, z, width, depth, state);
    }

    private void renderSurfaceTop(GeoSink builder, BlockTextureAtlas atlas,
                                  double x, double y, double z, int width, int depth, BlockState state) {
        TextureRegion topRegion = atlas.region(state);
        if (topRegion == null || !TerrainMaterialPolicy.renderAtlasMaterial(atlas.usesFallback(state))) return;
        // Sample the biome at the actual surface height. A fixed Y=64 lookup
        // made high plateaus and deep valleys inherit the wrong biome tint.
        RgbaColor tint = textureTintAt(state, x + width * 0.5, y + 1.0, z + depth * 0.5);
        double x2 = x + width;
        double y2 = y + 1;
        double z2 = z + depth;
        // Every atlas sprite also has a small repeated variant. Splitting a
        // coarse cell into bounded tiles preserves block-scale texels without
        // the 256 quads previously needed by a grass chunk.
        TextureRegion tiledRegion = atlas.tiledRegion(state);
        int repeatSpan = tiledRegion == null ? 1 : BlockTextureAtlas.TILED_REPEAT_SPAN;
        TextureRegion region = tiledRegion == null ? topRegion : tiledRegion;
        int tilesX = Math.max(1, (width + repeatSpan - 1) / repeatSpan);
        int tilesZ = Math.max(1, (depth + repeatSpan - 1) / repeatSpan);
        for (int dz = 0; dz < tilesZ; dz++) {
            for (int dx = 0; dx < tilesX; dx++) {
                double tx = x + dx * repeatSpan;
                double tz = z + dz * repeatSpan;
                double tileWidth = Math.min(repeatSpan, x2 - tx);
                double tileDepth = Math.min(repeatSpan, z2 - tz);
                double u2 = region.x1 + (region.x2 - region.x1) * tileWidth / repeatSpan;
                double v2 = region.y1 + (region.y2 - region.y1) * tileDepth / repeatSpan;
                builder.ensureQuadCapacity();
                builder.quad(
                    builder.vec3(tx, y2, tz).vec2(region.x1, region.y1).color(tint).next(),
                    builder.vec3(tx + tileWidth, y2, tz).vec2(u2, region.y1).color(tint).next(),
                    builder.vec3(tx + tileWidth, y2, tz + tileDepth).vec2(u2, v2).color(tint).next(),
                    builder.vec3(tx, y2, tz + tileDepth).vec2(region.x1, v2).color(tint).next());
            }
        }
    }

    private double[] project(FreeCamera3D cam, double worldX, double worldY, double worldZ,
                             double screenCenterX, double screenCenterY) {
        Matrix4f viewProjection = new Matrix4f(cam.getProjectionMatrix()).mul(cam.getViewMatrix());
        Vector4f clip = new Vector4f((float) worldX, (float) worldY, (float) worldZ, 1.0f);
        viewProjection.transform(clip);
        if (clip.w == 0) {
            return new double[]{Double.NaN, Double.NaN, Double.POSITIVE_INFINITY};
        }
        double ndcX = clip.x / clip.w;
        double ndcY = clip.y / clip.w;
        double ndcZ = clip.z / clip.w;
        double screenWidth = screenCenterX * 2.0;
        double screenHeight = screenCenterY * 2.0;
        return new double[]{
            (ndcX * 0.5 + 0.5) * screenWidth,
            (0.5 - ndcY * 0.5) * screenHeight,
            ndcZ
        };
    }

    private void renderStructureIcons(FreeCamera3D camera, List<GeneratedStructure> structures,
                                       GeneratedStructure hovered, double centerX, double centerY,
                                       Minecraft mc, MapRenderContext context) {
        if (structures == null || structures.isEmpty()) return;
        double zoom = 200.0 / Math.max(1.0, camera.distance());
        double metersPerPixel = zoom <= 0 ? Double.POSITIVE_INFINITY : 1.0 / zoom;
        List<ScreenIcon> icons = new ArrayList<>();
        for (GeneratedStructure s : structures) {
            if (!shouldRender(s.type, metersPerPixel)) continue;
            if (context.structureSearchFilter() != null && s.type != context.structureSearchFilter()) continue;
            double[] p = project(camera, structureAnchorX(s), estimatedStructureY(camera, s), structureAnchorZ(s), centerX, centerY);
            if (!Double.isFinite(p[0]) || !Double.isFinite(p[1]) || p[2] < -1.0 || p[2] > 1.0) continue;
            double margin = 200;
            if (p[0] < -margin || p[0] > mc.getWindow().getWidth() + margin
                || p[1] < -margin || p[1] > mc.getWindow().getHeight() + margin) continue;
            icons.add(new ScreenIcon(s, p[0], p[1], p[2]));
        }
        icons.sort((a, b) -> Double.compare(b.screenZ, a.screenZ));
        for (ScreenIcon icon : icons) {
            GeneratedStructure s = icon.structure;
            RgbaColor color = StructureColors.get(s.type);
            double size = 20.0;
            double half = size / 2.0;
            try (TerrainOverlayRenderer.ColorCanvas canvas = overlayRenderer.beginColors()) {
                canvas.quad(icon.sx - half + 1, icon.sy - half + 2, size, size,
                    new RgbaColor(0, 0, 0, 100));
            }
            var tex = StructureIcons.get(s);
            if (tex != null) {
                RgbaColor tint = new RgbaColor(255, 255, 255, color.a);
                double inset = 1.5;
                overlayRenderer.textureQuad(tex, icon.sx - half + inset, icon.sy - half + inset,
                    size - inset * 2, size - inset * 2, tint);
            } else {
                try (TerrainOverlayRenderer.ColorCanvas canvas = overlayRenderer.beginColors()) {
                    canvas.triangle(icon.sx, icon.sy - half, icon.sx - half, icon.sy,
                        icon.sx, icon.sy + half, color);
                    canvas.triangle(icon.sx, icon.sy - half, icon.sx + half, icon.sy,
                        icon.sx, icon.sy + half, color);
                }
            }
            try (TerrainOverlayRenderer.ColorCanvas canvas = overlayRenderer.beginColors()) {
                canvas.quad(icon.sx - half, icon.sy - half, size, size,
                    new RgbaColor(9, 12, 17, 120));
                canvas.boxLines(icon.sx - half, icon.sy - half, size, size,
                    new RgbaColor(255, 255, 255, 180));
            }
            if (s == hovered) {
                String label = s.displayName();
                double tw = overlayRenderer.textWidth(label);
                double tx = icon.sx - tw / 2.0;
                double ty = icon.sy - half - 12;
                try (TerrainOverlayRenderer.ColorCanvas canvas = overlayRenderer.beginColors()) {
                    canvas.quad(tx - 2, ty - 1, tw + 4, 10, new RgbaColor(0, 0, 0, 160));
                }
                overlayRenderer.text(frameGraphics, label, tx, ty, color);
            }
        }
    }

    private void renderWaypointIcons(FreeCamera3D camera, List<SeedWaypoint> waypoints,
                                      SeedWaypoint hovered, double centerX, double centerY, Minecraft mc) {
        if (waypoints == null || waypoints.isEmpty()) return;
        for (SeedWaypoint wp : waypoints) {
            double waypointY = terrainSurfaceY(wp.x, wp.z, 64.0) + 2.0;
            double[] p = project(camera, wp.x, waypointY, wp.z, centerX, centerY);
            if (!Double.isFinite(p[0]) || !Double.isFinite(p[1]) || p[2] < -1.0 || p[2] > 1.0) continue;
            double margin = 100;
            if (p[0] < -margin || p[0] > mc.getWindow().getWidth() + margin
                || p[1] < -margin || p[1] > mc.getWindow().getHeight() + margin) continue;
            boolean hovering = wp == hovered;
            RgbaColor color = hovering ? new RgbaColor(255, 255, 180, 255) : new RgbaColor(255, 255, 100, 220);
            double size = 12.0;
            double half = size / 2.0;
            try (TerrainOverlayRenderer.ColorCanvas canvas = overlayRenderer.beginColors()) {
                canvas.quad(p[0] - half, p[1] - half, size, size, color);
                canvas.quad(p[0] - half + 2, p[1] - 1, size - 4, 2,
                    new RgbaColor(40, 40, 40, 180));
                canvas.quad(p[0] - 1, p[1] - half + 2, 2, size - 4,
                    new RgbaColor(40, 40, 40, 180));
            }
            if (hovering) {
                String name = wp.name;
                double tw = overlayRenderer.textWidth(name);
                double tx = p[0] - tw / 2.0;
                double ty = p[1] - half - 12;
                try (TerrainOverlayRenderer.ColorCanvas canvas = overlayRenderer.beginColors()) {
                    canvas.quad(tx - 2, ty - 1, tw + 4, 10, new RgbaColor(0, 0, 0, 160));
                }
                overlayRenderer.text(frameGraphics, name, tx, ty, color);
            }
        }
    }

    public void renderChestMarkers(FreeCamera3D camera, List<ChestLootOutput> chests,
                                   ChestLootOutput selected, Set<ChestLootOutput> highlighted,
                                   double centerX, double centerY,
                                   Minecraft mc) {
        if (chests == null || chests.isEmpty()) return;
        for (ChestLootOutput chest : chests) {
            double[] p = project(camera, chest.blockX() + 0.5, chest.blockY() + 0.85, chest.blockZ() + 0.5, centerX, centerY);
            if (!Double.isFinite(p[0]) || !Double.isFinite(p[1]) || p[2] < -1.0 || p[2] > 1.0) continue;
            double margin = 80;
            if (p[0] < -margin || p[0] > mc.getWindow().getWidth() + margin
                || p[1] < -margin || p[1] > mc.getWindow().getHeight() + margin) continue;

            boolean active = chest == selected;
            boolean searchMatch = highlighted != null && highlighted.contains(chest);
            double size = active ? 18 : 14;
            double half = size / 2.0;
            RgbaColor fill = active ? new RgbaColor(255, 219, 106, 245)
                : searchMatch ? new RgbaColor(74, 201, 232, 240)
                : new RgbaColor(166, 118, 62, 225);
            RgbaColor edge = active ? RgbaColor.WHITE : new RgbaColor(15, 18, 22, 230);

            try (TerrainOverlayRenderer.ColorCanvas canvas = overlayRenderer.beginColors()) {
                canvas.quad(p[0] - half + 1, p[1] - half + 2, size, size,
                    new RgbaColor(0, 0, 0, 120));
                canvas.quad(p[0] - half, p[1] - half, size, size, fill);
                canvas.boxLines(p[0] - half, p[1] - half, size, size, edge);
                canvas.quad(p[0] - half + 3, p[1] - 1, size - 6, 2,
                    new RgbaColor(70, 45, 18, 190));
            }

            if (active) {
                String label = chest.blockX() + ", " + chest.blockY() + ", " + chest.blockZ();
                double tw = overlayRenderer.textWidth(label);
                double tx = p[0] - tw / 2.0;
                double ty = p[1] - half - 13;
                try (TerrainOverlayRenderer.ColorCanvas canvas = overlayRenderer.beginColors()) {
                    canvas.quad(tx - 3, ty - 1, tw + 6, 10, new RgbaColor(0, 0, 0, 165));
                }
                overlayRenderer.text(frameGraphics, label, tx, ty, fill);
            }
        }
    }

    public ChestLootOutput pickChest(FreeCamera3D camera, double mouseX, double mouseY,
                                     List<ChestLootOutput> chests,
                                     double screenCenterX, double screenCenterY) {
        if (chests == null || chests.isEmpty()) return null;
        ChestLootOutput picked = null;
        double bestDist = Double.MAX_VALUE;
        for (ChestLootOutput chest : chests) {
            double[] p = project(camera, chest.blockX() + 0.5, chest.blockY() + 0.85, chest.blockZ() + 0.5,
                screenCenterX, screenCenterY);
            if (!Double.isFinite(p[0]) || !Double.isFinite(p[1]) || p[2] < -1.0 || p[2] > 1.0) continue;
            double dx = mouseX - p[0];
            double dy = mouseY - p[1];
            double dist = dx * dx + dy * dy;
            double hitRadius = 16.0;
            if (dist <= hitRadius * hitRadius && dist < bestDist) {
                bestDist = dist;
                picked = chest;
            }
        }
        return picked;
    }

    public SeedWaypoint pickWaypoint(FreeCamera3D camera, double mouseX, double mouseY,
                                     List<SeedWaypoint> waypoints,
                                     double screenCenterX, double screenCenterY) {
        if (waypoints == null || waypoints.isEmpty()) return null;
        SeedWaypoint picked = null;
        double bestDist = Double.MAX_VALUE;
        for (SeedWaypoint waypoint : waypoints) {
            double waypointY = terrainSurfaceY(waypoint.x, waypoint.z, 64.0) + 2.0;
            double[] projected = project(camera, waypoint.x, waypointY, waypoint.z,
                screenCenterX, screenCenterY);
            if (!Double.isFinite(projected[0]) || !Double.isFinite(projected[1])
                || projected[2] < -1.0 || projected[2] > 1.0) continue;
            double dx = mouseX - projected[0];
            double dy = mouseY - projected[1];
            double distance = dx * dx + dy * dy;
            double hitRadius = 14.0;
            if (distance <= hitRadius * hitRadius && distance < bestDist) {
                bestDist = distance;
                picked = waypoint;
            }
        }
        return picked;
    }

    public TerrainFocusTarget waypointFocusTarget(SeedWaypoint waypoint) {
        if (waypoint == null) return null;
        double y = terrainSurfaceY(waypoint.x, waypoint.z, 64.0) + 2.0;
        return new TerrainFocusTarget(waypoint.x + 0.5, y, waypoint.z + 0.5, 28.0);
    }

    public GeneratedStructure pickStructure(FreeCamera3D camera, double mouseX, double mouseY,
                                             List<GeneratedStructure> structures,
                                             MapRenderContext context,
                                             double screenCenterX, double screenCenterY) {
        if (structures == null || structures.isEmpty()) return null;
        double zoom = 200.0 / Math.max(1.0, camera.distance());
        double metersPerPixel = zoom <= 0 ? Double.POSITIVE_INFINITY : 1.0 / zoom;
        GeneratedStructure picked = null;
        double bestDist = Double.MAX_VALUE;
        for (GeneratedStructure s : structures) {
            if (!shouldRender(s.type, metersPerPixel)) continue;
            if (context.structureSearchFilter() != null && s.type != context.structureSearchFilter()) continue;
            double[] p = project(camera, structureAnchorX(s), estimatedStructureY(camera, s), structureAnchorZ(s), screenCenterX, screenCenterY);
            if (!Double.isFinite(p[0]) || !Double.isFinite(p[1]) || p[2] < -1.0 || p[2] > 1.0) continue;
            double dx = mouseX - p[0];
            double dy = mouseY - p[1];
            double dist = dx * dx + dy * dy;
            double hitRadius = 15.0;
            if (dist <= hitRadius * hitRadius && dist < bestDist) {
                bestDist = dist;
                picked = s;
            }
        }
        return picked;
    }

    private boolean shouldRender(StructureType type, double metersPerPixel) {
        return metersPerPixel <= maxVisibleMetersPerPixel(type);
    }

    private double maxVisibleMetersPerPixel(StructureType type) {
        return switch (type) {
            case TREASURE -> 2.8;
            case END_GATEWAY -> 14.0;
            case NETHER_FOSSIL -> 42.0;
            case MINESHAFT, DUNGEON -> 20.0;
            case SHIPWRECK, OCEAN_RUIN, RUINED_PORTAL, TRAIL_RUINS -> 18.0;
            case DESERT_PYRAMID, JUNGLE_TEMPLE, WITCH_HUT, IGLOO, OUTPOST,
                 ABANDONED_CAMP -> 45.0;
            case VILLAGE -> 85.0;
            case MONUMENT, MANSION, ANCIENT_CITY, TRIAL_CHAMBER, STRONGHOLD,
                 FORTRESS, BASTION, END_CITY -> 160.0;
            default -> 30.0;
        };
    }

    private double estimatedStructureY(FreeCamera3D camera, GeneratedStructure structure) {
        if (structure.y != GeneratedStructure.UNKNOWN_Y) return structure.y + 1.5;
        StructurePreviewModel model = previewCache.get(previewKey(lastSeed, lastDimension, structure));
        if (model != null && !model.isEmpty()) {
            return model.maxY() + 6;
        }
        long key = ChunkPos.pack(Math.floorDiv(structure.x, CHUNK_SIZE), Math.floorDiv(structure.z, CHUNK_SIZE));
        ChunkData chunk = chunkCache.get(key);
        if (chunk == null) return lastDimension == 1 ? 80.0 : 68.0;
        int dx = Math.floorMod(structure.x, CHUNK_SIZE);
        int dz = Math.floorMod(structure.z, CHUNK_SIZE);
        int index = dz * CHUNK_SIZE + dx;
        if (index < 0 || index >= chunk.heights.length) return lastDimension == 1 ? 80.0 : 68.0;
        int y = chunk.heights[index];
        return y == 0 ? (lastDimension == 1 ? 80.0 : 68.0) : y + 8;
    }

    /** Exact preview bounds when available, terrain-height fallback while loading. */
    public TerrainFocusTarget structureFocusTarget(GeneratedStructure structure) {
        if (structure == null) return null;
        PreviewKey key = previewKey(lastSeed, lastDimension, structure);
        StructurePreviewModel model = previewCache.get(key);
        if (model == null && lastDimension != Integer.MIN_VALUE) {
            if (previewCache.size() >= MAX_CACHED_PREVIEWS) {
                previewCache.keySet().stream().filter(existing -> !existing.equals(key))
                    .findFirst().ifPresent(previewCache::remove);
            }
            submitPreviewJobs(List.of(structure), lastSeed, lastDimension);
        }
        if (model != null && !model.isEmpty()) {
            double spanX = model.maxX() - model.minX() + 1.0;
            double spanY = model.maxY() - model.minY() + 1.0;
            double spanZ = model.maxZ() - model.minZ() + 1.0;
            double span = Math.max(spanY, Math.max(spanX, spanZ));
            double distance = Math.max(18.0, Math.min(112.0, span * 1.15 + 10.0));
            return new TerrainFocusTarget(
                (model.minX() + model.maxX() + 1.0) * 0.5,
                (model.minY() + model.maxY() + 1.0) * 0.5,
                (model.minZ() + model.maxZ() + 1.0) * 0.5,
                distance);
        }

        double y = structure.y != GeneratedStructure.UNKNOWN_Y
            ? structure.y + 2.0 : terrainSurfaceY(structure.x, structure.z, 64.0) + 4.0;
        double distance = switch (structure.type) {
            case VILLAGE, MONUMENT, MANSION, ANCIENT_CITY, TRIAL_CHAMBER,
                 ABANDONED_CAMP,
                 STRONGHOLD, FORTRESS, BASTION, END_CITY -> 48.0;
            default -> 25.0;
        };
        return new TerrainFocusTarget(
            structure.x + 0.5, y, structure.z + 0.5, distance);
    }

    private double terrainSurfaceY(int worldX, int worldZ, double fallback) {
        ChunkData chunk = chunkCache.get(ChunkPos.pack(
            Math.floorDiv(worldX, CHUNK_SIZE), Math.floorDiv(worldZ, CHUNK_SIZE)));
        if (chunk == null) return fallback;
        int index = Math.floorMod(worldZ, CHUNK_SIZE) * CHUNK_SIZE
            + Math.floorMod(worldX, CHUNK_SIZE);
        int y = index >= 0 && index < chunk.heights.length ? chunk.heights[index] : 0;
        if (y == 0) {
            for (int candidate : chunk.heights) {
                if (candidate != 0) {
                    y = candidate;
                    break;
                }
            }
        }
        return y == 0 ? fallback : y;
    }

    private double structureAnchorX(GeneratedStructure structure) {
        StructurePreviewModel model = previewCache.get(previewKey(lastSeed, lastDimension, structure));
        return model != null && !model.isEmpty() ? model.centerX() : structure.x;
    }

    private double structureAnchorZ(GeneratedStructure structure) {
        StructurePreviewModel model = previewCache.get(previewKey(lastSeed, lastDimension, structure));
        return model != null && !model.isEmpty() ? model.centerZ() : structure.z;
    }

    private void renderStructurePreviews(GeoSink builder, FreeCamera3D camera,
                                         List<GeneratedStructure> structures,
                                         GeneratedStructure hoveredStructure,
                                         GeneratedStructure inspectionStructure,
                                         long seed, int dimension, boolean undergroundView,
                                         boolean textureDebugView,
                                         GeoSink vanillaBuilder) {
        if (structures == null || structures.isEmpty()) return;

        List<GeneratedStructure> nearby = new ArrayList<>();
        for (GeneratedStructure structure : structures) {
            if (structure.type.dimension != dimension) continue;
            double dx = structure.x - camera.targetX();
            double dz = structure.z - camera.targetZ();
            double distSq = dx * dx + dz * dz;
            // Keep full block previews limited to the selected/hovered model.
            // Expanding every nearby prediction at once produces the repeated
            // mossy-cobblestone-looking clutter visible in the map screenshots.
            if (sameStructure(structure, inspectionStructure)) {
                nearby.add(structure);
            }
        }
        nearby.sort((a, b) -> {
            if (sameStructure(a, inspectionStructure)) return -1;
            if (sameStructure(b, inspectionStructure)) return 1;
            double adx = a.x - camera.targetX();
            double adz = a.z - camera.targetZ();
            double bdx = b.x - camera.targetX();
            double bdz = b.z - camera.targetZ();
            return Double.compare(adx * adx + adz * adz, bdx * bdx + bdz * bdz);
        });

        submitPreviewJobs(nearby, seed, dimension);

        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        int renderedBlocks = 0;
        for (GeneratedStructure structure : nearby) {
            StructurePreviewModel model = previewCache.get(previewKey(seed, dimension, structure));
            if (model == null || model.isEmpty()) continue;
            Map<Long, BlockState> previewStates = new java.util.HashMap<>(model.blocks().size() * 2);
            for (StructurePreviewModel.PreviewBlock candidate : model.blocks()) {
                if (candidate.state() != null && !candidate.state().isAir()) {
                    previewStates.put(BlockPos.asLong(candidate.x(), candidate.y(), candidate.z()), candidate.state());
                }
            }
            int step = previewStep(camera, structure);
            boolean inspectInterior = sameStructure(structure, inspectionStructure);
            for (StructurePreviewModel.PreviewBlock block : model.blocks()) {
                if (renderedBlocks >= MAX_STRUCTURE_PREVIEW_BLOCKS_PER_FRAME) return;
                if (step > 1 && (Math.floorMod(block.x(), step) != 0 || Math.floorMod(block.z(), step) != 0)) continue;
                if (!undergroundView && isPreviewBlockBuried(block, step)) continue;
                if (inspectInterior && isInspectionShellBlock(block, model, camera, step)) continue;
                BlockState state = block.state();
                if (state == null || state.isAir()) continue;
                boolean baked = renderBakedPreviewBlock(vanillaBuilder, block.x(), block.y(), block.z(),
                    state, step, previewStates);
                if (!baked) {
                    renderPreviewCube(builder, atlas, block.x(), block.y(), block.z(), state, step,
                        previewStates, textureDebugView, false);
                }
                renderedBlocks++;
            }
        }
    }

    /** In focus mode the outside-facing roof/walls are omitted, leaving floors and rooms visible. */
    private static boolean isInspectionShellBlock(StructurePreviewModel.PreviewBlock block,
                                                   StructurePreviewModel model,
                                                   FreeCamera3D camera, int step) {
        int margin = Math.max(1, step);
        boolean hasEnclosedHeight = model.maxY() - model.minY() >= 4;
        boolean onRoof = hasEnclosedHeight && block.y() >= model.maxY() - margin;
        if (onRoof) return true;
        boolean onBoundary = block.x() <= model.minX() + margin
            || block.x() >= model.maxX() - margin
            || block.z() <= model.minZ() + margin
            || block.z() >= model.maxZ() - margin;
        if (!onBoundary) return false;
        // In inspection mode the whole outside shell is omitted, not just the
        // wall facing the current camera. Keeping the lowest layer preserves
        // the structure floor while every orbit angle sees the rooms clearly.
        return block.y() > model.minY() + margin;
    }

    private static boolean sameStructure(GeneratedStructure first, GeneratedStructure second) {
        return first == second || (first != null && second != null
            && first.type == second.type
            && first.startChunkX == second.startChunkX
            && first.startChunkZ == second.startChunkZ
            && first.hasShip == second.hasShip
            && first.variant.equals(second.variant));
    }

    private boolean isPreviewBlockBuried(StructurePreviewModel.PreviewBlock block, int step) {
        long chunkKey = ChunkPos.pack(Math.floorDiv(block.x(), CHUNK_SIZE), Math.floorDiv(block.z(), CHUNK_SIZE));
        ChunkData chunk = chunkCache.get(chunkKey);
        if (chunk == null) return false;
        int localX = Math.floorMod(block.x(), CHUNK_SIZE);
        int localZ = Math.floorMod(block.z(), CHUNK_SIZE);
        int index = localZ * CHUNK_SIZE + localX;
        if (index < 0 || index >= chunk.heights.length) return false;
        int surfaceY = chunk.heights[index];
        return block.y() + Math.max(1, step) <= surfaceY;
    }

    private void submitPreviewJobs(List<GeneratedStructure> nearby, long seed, int dimension) {
        int submitted = 0;
        long generation = worldGeneration;
        for (GeneratedStructure structure : nearby) {
            if (submitted >= PREVIEW_SUBMISSIONS_PER_FRAME) return;
            if (inFlightPreviews.size() >= MAX_IN_FLIGHT_PREVIEWS
                || previewCache.size() >= MAX_CACHED_PREVIEWS) return;
            PreviewKey key = previewKey(seed, dimension, structure);
            PreviewJobKey jobKey = new PreviewJobKey(generation, key);
            if (previewCache.containsKey(key) || !inFlightPreviews.add(jobKey)) continue;
            submitted++;
            previewExecutor.execute(() -> {
                try {
                    StructurePreviewModel model = StructurePreviewSimulator.preview(structure, seed);
                    if (isCurrent(generation, seed, dimension)) {
                        previewCache.put(key, model);
                    }
                } catch (Throwable ignored) {
                    if (isCurrent(generation, seed, dimension)) {
                        previewCache.put(key, new StructurePreviewModel(List.of(), 0, 0, 0, 0, 0, 0, false));
                    }
                } finally {
                    inFlightPreviews.remove(jobKey);
                }
            });
        }
    }

    private int previewStep(FreeCamera3D camera, GeneratedStructure structure) {
        double dx = structure.x - camera.targetX();
        double dz = structure.z - camera.targetZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 128) return 1;
        if (distance <= 256) return 2;
        return 4;
    }

    private void renderPreviewCube(GeoSink builder, BlockTextureAtlas atlas,
                                   int x, int y, int z, BlockState state, int step,
                                   Map<Long, BlockState> previewStates, boolean textureDebugView,
                                   boolean allowBaked) {
        String stateId = blockId(state);
        if (!TerrainMaterialPolicy.renderPreviewMaterial(stateId)) return;

        // Use Minecraft's own baked model for preview blocks whenever the block
        // has a normal MODEL render shape. This preserves stairs, slabs, panes,
        // doors, foliage and directional variants instead of collapsing them to
        // a guessed cube. The atlas adapter below remaps each baked sprite onto
        // the addon's shared texture atlas.
        if (allowBaked && renderBakedPreviewBlock(builder, x, y, z, state, step,
            previewStates)) return;

        if (!TerrainMaterialPolicy.renderAtlasMaterial(atlas.usesFallback(state))) return;
        PreviewTexture texture = previewTextureCache.get(state);
        if (texture == null) {
            TextureRegion topRegion = atlas.region(state);
            TextureRegion sideRegion = atlas.sideRegion(state);
            if (topRegion == null || sideRegion == null) return;
            RgbaColor top = textureTint(state);
            RgbaColor side = sideTextureTint(state);
            texture = new PreviewTexture(topRegion, sideRegion,
                top.r, top.g, top.b, side.r, side.g, side.b,
                atlas.usesFallback(state), atlas.sideUsesFallback(state));
            PreviewTexture existing = previewTextureCache.putIfAbsent(state, texture);
            if (existing != null) texture = existing;
        }

        int topR = texture.r;
        int topG = texture.g;
        int topB = texture.b;
        if (textureDebugView && texture.topFallback) {
            topR = 255;
            topG = 52;
            topB = 214;
        }
        int sideR = texture.sideR;
        int sideG = texture.sideG;
        int sideB = texture.sideB;
        if (textureDebugView && texture.sideFallback) {
            sideR = 255;
            sideG = 135;
            sideB = 32;
        }
        previewTopTint = new RgbaColor(topR, topG, topB, 255);
        previewSideTint = new RgbaColor((int) (sideR * 0.74),
            (int) (sideG * 0.74), (int) (sideB * 0.74), 255);

        TextureRegion topRegion = texture.top;
        TextureRegion sideRegion = texture.side;
        double size = Math.max(1, step);
        // Use the block's vanilla occlusion shape instead of forcing every
        // mansion block into a full cube. Stairs, slabs, panes and doors now
        // retain their real bounds. A null world is safe for the static shape
        // implementations; blocks that need world context fall back to the
        // occlusion shape. Blocks with no geometric shape were already offered
        // to the baked-model path; if that emitted nothing, skip them instead
        // of calling bounds() on an empty shape or inventing a full cube.
        List<net.minecraft.world.phys.AABB> boxes = List.of();
        try {
            boxes = previewBoxesForShape(state.getShape(null, new BlockPos(x, y, z)));
        } catch (Throwable ignored) {
            // Some shapes require world context; use the static occlusion shape.
        }
        if (boxes.isEmpty()) {
            try {
                boxes = previewBoxesForShape(state.getOcclusionShape());
            } catch (Throwable ignored) {
                return;
            }
        }
        if (boxes.isEmpty()) return;
        for (net.minecraft.world.phys.AABB box : boxes) {
            double x1 = x + box.minX * size, x2 = x + box.maxX * size;
            double y1 = y + box.minY * size, y2 = y + box.maxY * size;
            double z1 = z + box.minZ * size, z2 = z + box.maxZ * size;
            if (x2 <= x1 || y2 <= y1 || z2 <= z1) continue;
            if (previewFaceVisible(previewStates, x, y, z, step, net.minecraft.core.Direction.UP)) {
                emitPreviewTop(builder, topRegion, x1, y2, z1, x2, z2);
            }
            if (!TerrainMaterialPolicy.renderPreviewMaterial(stateId)) continue;
            if (previewFaceVisible(previewStates, x, y, z, step, net.minecraft.core.Direction.NORTH))
                emitPreviewSide(builder, sideRegion, x1, y1, z1, x2, y2, z1, false);
            if (previewFaceVisible(previewStates, x, y, z, step, net.minecraft.core.Direction.SOUTH))
                emitPreviewSide(builder, sideRegion, x2, y1, z2, x1, y2, z2, false);
            if (previewFaceVisible(previewStates, x, y, z, step, net.minecraft.core.Direction.WEST))
                emitPreviewSide(builder, sideRegion, x1, y1, z2, x1, y2, z1, true);
            if (previewFaceVisible(previewStates, x, y, z, step, net.minecraft.core.Direction.EAST))
                emitPreviewSide(builder, sideRegion, x2, y1, z1, x2, y2, z2, true);
        }
    }

    static List<net.minecraft.world.phys.AABB> previewBoxesForShape(
        net.minecraft.world.phys.shapes.VoxelShape shape) {
        if (shape == null || shape.isEmpty()) return List.of();
        List<net.minecraft.world.phys.AABB> boxes = shape.toAabbs();
        if (boxes.isEmpty()) return List.of();
        return boxes.size() <= 8 ? boxes : List.of(shape.bounds());
    }

    private boolean renderBakedPreviewBlock(GeoSink builder, int x, int y, int z, BlockState state, int step,
                                            Map<Long, BlockState> previewStates) {
        if (state == null || state.getRenderShape() != RenderShape.MODEL) return false;
        try {
            BlockStateModel model = Minecraft.getInstance().getModelManager()
                .getBlockStateModelSet().get(state);
            if (model == null) return false;

            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create((long) BlockPos.asLong(x, y, z)), parts);
            boolean emitted = false;
            int stride = Math.max(1, step);
            for (BlockStateModelPart part : parts) {
                for (Direction direction : Direction.values()) {
                    if (!previewFaceVisible(previewStates, x, y, z, stride, direction)) continue;
                    for (BakedQuad quad : part.getQuads(direction)) {
                        emitted |= emitBakedPreviewQuadLive(builder, quad, x, y, z, stride, state);
                    }
                }
                // Null-direction quads are unculled model geometry (crossed
                // plants, particles and other special shapes).
                for (BakedQuad quad : part.getQuads(null)) {
                    emitted |= emitBakedPreviewQuadLive(builder, quad, x, y, z, stride, state);
                }
            }
            return emitted;
        } catch (Throwable ignored) {
            // Some special block models require a live BlockAndTintGetter or
            // block entity. Leave those to the existing conservative fallback.
            return false;
        }
    }

    private boolean emitBakedPreviewQuadLive(GeoSink builder, BakedQuad quad,
                                             int x, int y, int z, int stride) {
        return emitBakedPreviewQuadLive(builder, quad, x, y, z, stride, null);
    }

    private boolean emitBakedPreviewQuadLive(GeoSink builder, BakedQuad quad,
                                             int x, int y, int z, int stride,
                                             BlockState state) {
        if (quad == null || quad.materialInfo() == null || quad.materialInfo().sprite() == null) return false;
        var sprite = quad.materialInfo().sprite();
        RgbaColor tint = bakedQuadTint(quad.direction());
        if (state != null) tint = vanillaTintAndLight(state, quad, x, y, z, tint);
        int packedLight = LightCoordsUtil.FULL_BRIGHT;
        Minecraft mc = Minecraft.getInstance();
        if (state != null && mc.level != null) {
            try {
                packedLight = LightCoordsUtil.getLightCoords(mc.level, new BlockPos(x, y, z));
            } catch (Throwable ignored) {
                // Keep full-bright for predicted positions outside loaded sections.
            }
        }
        int[] vertices = new int[4];
        for (int i = 0; i < 4; i++) {
            org.joml.Vector3fc pos = quad.position(i);
            long packed = quad.packedUV(i);
            float localU = Float.intBitsToFloat((int) (packed >>> 32));
            float localV = Float.intBitsToFloat((int) packed);
            vertices[i] = builder.vec3(x + pos.x() * stride, y + pos.y() * stride,
                z + pos.z() * stride).vec2(sprite.getU(localU), sprite.getV(localV))
                .color(tint).light(packedLight).next();
        }
        builder.quad(vertices[0], vertices[1], vertices[2], vertices[3]);
        return true;
    }

    /** Resolve biome tint and live light for baked quads. Predicted chunks do not
     * have a ClientLevel, so they retain the neutral directional model tint. */
    private static RgbaColor vanillaTintAndLight(BlockState state, BakedQuad quad,
                                             int x, int y, int z, RgbaColor faceTint) {
        Minecraft mc = Minecraft.getInstance();
        int tint = 0xFFFFFF;
        if (quad.materialInfo().isTinted() && mc.level != null) {
            try {
                BlockTintSource source = mc.getBlockColors().getTintSource(state,
                    quad.materialInfo().tintIndex());
                if (source != null) {
                    tint = source.colorInWorld(state, mc.level, new BlockPos(x, y, z));
                }
            } catch (Throwable ignored) {
                // Leave the baked model neutral when a tint source cannot resolve.
            }
        }
        float block = 1.0f;
        float sky = 1.0f;
        if (mc.level != null) {
            try {
                BlockPos pos = new BlockPos(x, y, z);
                block = mc.level.getBrightness(LightLayer.BLOCK, pos) / 15.0f;
                sky = mc.level.getBrightness(LightLayer.SKY, pos) / 15.0f;
            } catch (Throwable ignored) {
                // Positions outside the live level use ambient light.
            }
        }
        float light = Math.max(0.35f, Math.min(1.0f, 0.22f + 0.78f * Math.max(block, sky)));
        int r = ((tint >>> 16) & 0xFF) * faceTint.r / 255;
        int g = ((tint >>> 8) & 0xFF) * faceTint.g / 255;
        int b = (tint & 0xFF) * faceTint.b / 255;
        return new RgbaColor(Math.round(r * light), Math.round(g * light),
            Math.round(b * light), faceTint.a);
    }

    private void renderVanillaPreviewGeometry(BakedGeometry geometry) {
        if (geometry == null || geometry.isEmpty()) return;
        // AtlasManager's definition key is the logical atlas id ("minecraft:blocks")
        // in 26.1.2. The JSON path and uploaded texture location are not accepted.
        TextureAtlas atlas = Minecraft.getInstance().getAtlasManager()
            .getAtlasOrThrow(AtlasIds.BLOCKS);
        // Keep the preview on the proven atlas pipeline until a live client
        // lightmap is available for every predicted position. The lightmap
        // pipeline remains defined for the next guarded rollout, but binding it
        // here can reject the custom buffer on some renderer backends.
        renderGpuGeometry(SeedExplorerRenderPipelines.WORLD_VANILLA_TEXTURED,
            atlas.getTextureView(), atlas.getSampler(), geometry,
            "Seed Explorer vanilla baked structure preview");
    }

    /**
     * Emits Minecraft's baked block models for a small exact ring around the
     * free camera. It deliberately uses the same generated/observed block states
     * as the underground renderer and skips fully occluded neighbours. The ring
     * is bounded so far-distance streaming remains cheap and continuous.
     */
    private void renderVanillaTerrainRing(GeoSink builder, FreeCamera3D camera,
                                          long seed, int dimension, boolean caveView) {
        int centerX = (int) Math.floor(camera.targetX());
        int centerZ = (int) Math.floor(camera.targetZ());
        if (caveView) {
            if (vanillaRingComplete) {
                invalidateVanillaRingBatches(vanillaRingCenterChunkX, vanillaRingCenterChunkZ);
            }
            vanillaRingComplete = false;
            vanillaRingCache = EMPTY_GEOMETRY;
            vanillaRingKey = Long.MIN_VALUE;
            return;
        }
        long ringKey = ChunkPos.pack(Math.floorDiv(centerX, CHUNK_SIZE),
            Math.floorDiv(centerZ, CHUNK_SIZE));
        int yBucket = 0;
        int centerChunkX = Math.floorDiv(centerX, CHUNK_SIZE);
        int centerChunkZ = Math.floorDiv(centerZ, CHUNK_SIZE);
        int radius = VANILLA_SURFACE_RADIUS_CHUNKS * CHUNK_SIZE;
        int minChunkX = Math.floorDiv(centerX - radius, CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(centerX + radius, CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(centerZ - radius, CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(centerZ + radius, CHUNK_SIZE);
        long revision = vanillaRingRevision(minChunkX, minChunkZ, maxChunkX, maxChunkZ);
        if (ringKey == vanillaRingKey && vanillaRingDimension == dimension
            && vanillaRingSeed == seed && vanillaRingRevision == revision
            && vanillaRingYBucket == yBucket
            && vanillaRingCenterChunkX == centerChunkX
            && vanillaRingCenterChunkZ == centerChunkZ
            && vanillaRingCaveView == caveView
            && vanillaRingCache != null) {
            if (builder instanceof BakingSink sink) sink.append(vanillaRingCache);
            return;
        }

        if (vanillaRingComplete) {
            invalidateVanillaRingBatches(vanillaRingCenterChunkX, vanillaRingCenterChunkZ);
        }
        vanillaRingComplete = false;
        vanillaRingCache = EMPTY_GEOMETRY;
        rememberVanillaRingIdentity(ringKey, seed, dimension, revision, yBucket,
            centerChunkX, centerChunkZ, caveView);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ChunkData required = chunkCache.get(ChunkPos.pack(chunkX, chunkZ));
                if (required == null || !isExactSurface(required)) return;
            }
        }

        WorldgenEngine.TerrainAccessor terrain = terrainAccessor(seed, dimension);
        GeneratedTerrainHeightmap carved = carvedTerrain(seed, dimension);
        BakingSink ring = new BakingSink();
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                ChunkData chunk = chunkCache.get(ChunkPos.pack(
                    Math.floorDiv(x, CHUNK_SIZE), Math.floorDiv(z, CHUNK_SIZE)));
                if (chunk == null || !isExactSurface(chunk)) continue;
                int top = surfaceHeight(chunk, Math.floorMod(x, CHUNK_SIZE), Math.floorMod(z, CHUNK_SIZE));
                int minY = Math.max(-64, top - VANILLA_SURFACE_DEPTH_BLOCKS);
                int maxY = Math.min(384, top + 2);
                for (int y = minY; y <= maxY; y++) {
                    BlockState state = sampleBlock(terrain, carved, chunk,
                        Math.floorMod(x, CHUNK_SIZE), y, Math.floorMod(z, CHUNK_SIZE), x, z);
                    if (state == null || state.isAir() || isFluid(state)
                        || state.getRenderShape() != RenderShape.MODEL) continue;
                    if (!hasExposedNeighbour(terrain, carved, chunk, x, y, z)) continue;
                    emitVanillaBlockModel(ring, x, y, z, state, terrain,
                        carved, chunk);
                }
            }
        }
        vanillaRingCache = ring.finish();
        vanillaRingComplete = true;
        invalidateVanillaRingBatches(centerChunkX, centerChunkZ);
        if (builder instanceof BakingSink sink) sink.append(vanillaRingCache);
    }

    private void invalidateVanillaRingBatches(int centerChunkX, int centerChunkZ) {
        int minChunkX = centerChunkX - VANILLA_SURFACE_RADIUS_CHUNKS - 1;
        int maxChunkX = centerChunkX + VANILLA_SURFACE_RADIUS_CHUNKS + 1;
        int minChunkZ = centerChunkZ - VANILLA_SURFACE_RADIUS_CHUNKS - 1;
        int maxChunkZ = centerChunkZ + VANILLA_SURFACE_RADIUS_CHUNKS + 1;
        Set<Long> batches = new HashSet<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                batches.add(ChunkPos.pack(Math.floorDiv(chunkX, GPU_BATCH_SIZE_CHUNKS),
                    Math.floorDiv(chunkZ, GPU_BATCH_SIZE_CHUNKS)));
            }
        }
        for (long batchKey : batches) {
            gpuBatchRevisions.put(batchKey, nextGpuBatchRevision.incrementAndGet());
            queueGpuBatchPriority(batchKey);
        }
    }

    private long vanillaRingRevision(int minChunkX, int minChunkZ,
                                     int maxChunkX, int maxChunkZ) {
        long signature = 0xCBF29CE484222325L;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long key = ChunkPos.pack(chunkX, chunkZ);
                ChunkData chunk = chunkCache.get(key);
                signature ^= key;
                signature *= 0x100000001B3L;
                if (chunk == null) continue;
                signature ^= System.identityHashCode(chunk);
                signature *= 0x100000001B3L;
                signature ^= geometryRevisions.getOrDefault(key, 0L);
                signature *= 0x100000001B3L;
            }
        }
        return signature;
    }

    private void rememberVanillaRingIdentity(long ringKey, long seed, int dimension,
                                             long revision, int yBucket,
                                             int centerChunkX, int centerChunkZ,
                                             boolean caveView) {
        vanillaRingKey = ringKey;
        vanillaRingDimension = dimension;
        vanillaRingSeed = seed;
        vanillaRingRevision = revision;
        vanillaRingYBucket = yBucket;
        vanillaRingCenterChunkX = centerChunkX;
        vanillaRingCenterChunkZ = centerChunkZ;
        vanillaRingCaveView = caveView;
    }

    private int surfaceHeight(ChunkData chunk, int localX, int localZ) {
        int index = localZ * CHUNK_SIZE + localX;
        return index >= 0 && index < chunk.heights.length ? chunk.heights[index] : 0;
    }

    private boolean hasExposedNeighbour(WorldgenEngine.TerrainAccessor terrain,
                                         GeneratedTerrainHeightmap carved, ChunkData chunk,
                                         int x, int y, int z) {
        for (Direction direction : Direction.values()) {
            BlockState neighbour = sampleBlock(terrain, carved, chunk,
                Math.floorMod(x + direction.getStepX(), CHUNK_SIZE), y + direction.getStepY(),
                Math.floorMod(z + direction.getStepZ(), CHUNK_SIZE),
                x + direction.getStepX(), z + direction.getStepZ());
            if (neighbour == null || neighbour.isAir() || isFluid(neighbour)
                || !isFullPreviewOccluder(neighbour)) return true;
        }
        return false;
    }

    private void emitVanillaBlockModel(GeoSink builder, int x, int y, int z,
                                       BlockState state, WorldgenEngine.TerrainAccessor terrain,
                                       GeneratedTerrainHeightmap carved, ChunkData chunk) {
        BlockStateModel model = Minecraft.getInstance().getModelManager()
            .getBlockStateModelSet().get(state);
        if (model == null) return;
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(BlockPos.asLong(x, y, z)), parts);
        for (BlockStateModelPart part : parts) {
            for (Direction direction : Direction.values()) {
                BlockState neighbour = sampleBlock(terrain, carved, chunk,
                    Math.floorMod(x + direction.getStepX(), CHUNK_SIZE), y + direction.getStepY(),
                    Math.floorMod(z + direction.getStepZ(), CHUNK_SIZE),
                    x + direction.getStepX(), z + direction.getStepZ());
                if (neighbour != null && isFullPreviewOccluder(neighbour)) continue;
                for (BakedQuad quad : part.getQuads(direction)) {
                    emitBakedPreviewQuadLive(builder, quad, x, y, z, 1, state);
                }
            }
            for (BakedQuad quad : part.getQuads(null)) {
                emitBakedPreviewQuadLive(builder, quad, x, y, z, 1, state);
            }
        }
    }

    /** Emits vanilla's selected block-state model while remapping each sprite
     * into the map's packaged official-texture atlas. This preserves crossed
     * plants, leaf/log models, state variants, and per-face UVs. */
    private void renderNaturalDecorations(GeoSink builder, ChunkData chunk,
                                          ChunkAccess generatedBlocks,
                                          long seed, int dimension) {
        List<GeneratedTerrainHeightmap.NaturalDecorationBlock> decorations =
            chunk.naturalDecorations.isEmpty() && chunk.observedSnapshot != null
                ? observedNaturalDecorations(chunk.observedSnapshot)
                : chunk.naturalDecorations;
        if (decorations.isEmpty()) return;
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        Map<Long, BlockState> decorated = new java.util.HashMap<>(
            decorations.size() * 2);
        for (GeneratedTerrainHeightmap.NaturalDecorationBlock block
            : decorations) {
            BlockState state = block.state();
            // Excluded or non-model decorations must not hide faces belonging to
            // a neighbouring model that will actually be emitted.
            if (naturalDecorationCanRender(state)) {
                decorated.put(BlockPos.asLong(block.x(), block.y(), block.z()), state);
            }
        }

        for (GeneratedTerrainHeightmap.NaturalDecorationBlock block
            : decorations) {
            BlockState state = block.state();
            if (!naturalDecorationCanRender(state)) continue;
            try {
                BlockStateModel model = Minecraft.getInstance().getModelManager()
                    .getBlockStateModelSet().get(state);
                if (model == null) continue;
                List<BlockStateModelPart> parts = new ArrayList<>();
                model.collectParts(RandomSource.create(BlockPos.asLong(
                    block.x(), block.y(), block.z())), parts);
                for (BlockStateModelPart part : parts) {
                    for (Direction direction : Direction.values()) {
                        BlockState neighbour = naturalDecorationNeighbour(chunk,
                            generatedBlocks, decorated,
                            block.x() + direction.getStepX(),
                            block.y() + direction.getStepY(),
                            block.z() + direction.getStepZ());
                        if (naturalDecorationFaceOccluded(state, neighbour)) continue;
                        for (BakedQuad quad : part.getQuads(direction)) {
                            emitNaturalBakedQuad(builder, atlas, quad,
                                block.x(), block.y(), block.z(), state, seed, dimension);
                        }
                    }
                    for (BakedQuad quad : part.getQuads(null)) {
                        emitNaturalBakedQuad(builder, atlas, quad,
                            block.x(), block.y(), block.z(), state, seed, dimension);
                    }
                }
            } catch (Throwable error) {
                // A special dynamic model can require a live block entity. Natural
                // placed features are overwhelmingly ordinary baked block models;
                // omit an unsupported state instead of substituting a stone cube.
                terrainDiagnostic("decoration-model-failed", seed, dimension,
                    chunk.chunkX, chunk.chunkZ, 1, chunk, error,
                    "state=" + blockId(state));
            }
        }
    }

    static boolean naturalDecorationCanRender(BlockState state) {
        return state != null && !state.isAir()
            && TerrainMaterialPolicy.renderNaturalDecoration(blockId(state))
            && state.getRenderShape() == RenderShape.MODEL;
    }

    private static BlockState naturalDecorationNeighbour(
        ChunkData chunk, ChunkAccess generatedBlocks, Map<Long, BlockState> decorated,
        int x, int y, int z) {
        BlockState natural = decorated.get(BlockPos.asLong(x, y, z));
        if (natural != null) return natural;
        if (Math.floorDiv(x, CHUNK_SIZE) != chunk.chunkX
            || Math.floorDiv(z, CHUNK_SIZE) != chunk.chunkZ) return Blocks.AIR.defaultBlockState();
        int localX = Math.floorMod(x, CHUNK_SIZE);
        int localZ = Math.floorMod(z, CHUNK_SIZE);
        if (chunk.observedSnapshot != null) {
            BlockState observed = chunk.observedSnapshot.block(localX, y, localZ);
            return observed == null ? Blocks.AIR.defaultBlockState() : observed;
        }
        return generatedBlocks == null ? Blocks.AIR.defaultBlockState()
            : generatedBlocks.getBlockState(new BlockPos(x, y, z));
    }

    private static boolean naturalDecorationFaceOccluded(BlockState state, BlockState neighbour) {
        if (neighbour == null || neighbour.isAir()) return false;
        if (isFullPreviewOccluder(neighbour)) return true;
        // Vanilla translucent leaves do not report as full occluders, but internal
        // faces in a predicted canopy are never visible. Culling them makes a much
        // larger vegetation radius affordable.
        return state != null && state.is(BlockTags.LEAVES) && neighbour.is(BlockTags.LEAVES);
    }

    private static void emitNaturalBakedQuad(GeoSink builder, BlockTextureAtlas atlas,
                                             BakedQuad quad, int x, int y, int z,
                                             BlockState state, long seed, int dimension) {
        if (quad == null || quad.materialInfo() == null
            || quad.materialInfo().sprite() == null) return;
        var contents = quad.materialInfo().sprite().contents();
        TextureRegion region = contents == null ? null : atlas.region(contents.name());
        if (region == null) return;
        RgbaColor tint = naturalDecorationTint(state, quad, x, y, z, seed, dimension);
        int[] vertices = new int[4];
        for (int i = 0; i < 4; i++) {
            org.joml.Vector3fc pos = quad.position(i);
            long packed = quad.packedUV(i);
            float modelU = Float.intBitsToFloat((int) (packed >>> 32));
            float modelV = Float.intBitsToFloat((int) packed);
            double u = region.x1 + (region.x2 - region.x1)
                * Math.max(0.0, Math.min(1.0, modelU / 16.0));
            double v = region.y1 + (region.y2 - region.y1)
                * Math.max(0.0, Math.min(1.0, modelV / 16.0));
            vertices[i] = builder.vec3(x + pos.x(), y + pos.y(), z + pos.z())
                .vec2(u, v).color(tint).light(LightCoordsUtil.FULL_BRIGHT).next();
        }
        builder.quad(vertices[0], vertices[1], vertices[2], vertices[3]);
    }

    private static RgbaColor naturalDecorationTint(BlockState state, BakedQuad quad,
                                                int x, int y, int z,
                                                long seed, int dimension) {
        RgbaColor face = bakedQuadTint(quad.direction());
        String stateId = blockId(state);
        if (!quad.materialInfo().isTinted() && !requiresFoliageTint(stateId)) return face;
        int rgb = 0xFFFFFF;
        try {
            var holder = WorldgenEngine.getBiomeHolder(seed, dimension, x, y, z);
            if (holder != null && holder.value() != null) {
                String id = stateId;
                if (usesDryFoliageTint(state)) {
                    rgb = holder.value().getDryFoliageColor();
                } else if (id.contains("water") || id.contains("seagrass")
                    || id.contains("kelp")) {
                    rgb = holder.value().getWaterColor();
                } else if (id.contains("leaves") || id.contains("vine")) {
                    if (id.contains("spruce_leaves")) rgb = 0x619961;
                    else if (id.contains("birch_leaves")) rgb = 0x80A755;
                    else rgb = holder.value().getFoliageColor();
                } else {
                    rgb = holder.value().getGrassColor(x, z);
                }
            }
        } catch (Throwable ignored) {
            // Neutral tint is preferable to dropping a model when biome lookup fails.
        }
        return new RgbaColor(((rgb >>> 16) & 0xFF) * face.r / 255,
            ((rgb >>> 8) & 0xFF) * face.g / 255,
            (rgb & 0xFF) * face.b / 255, face.a);
    }

    static boolean usesDryFoliageTint(BlockState state) {
        return state != null && usesDryFoliageTintId(blockId(state));
    }

    static boolean usesDryFoliageTintId(String id) {
        return "leaf_litter".equals(id) || "minecraft:leaf_litter".equals(id);
    }

    static boolean requiresFoliageTint(String id) {
        if (id == null) return false;
        return id.endsWith("oak_leaves")
            || id.endsWith("birch_leaves")
            || id.endsWith("spruce_leaves")
            || id.endsWith("jungle_leaves")
            || id.endsWith("acacia_leaves")
            || id.endsWith("mangrove_leaves")
            || id.endsWith(":vine") || id.equals("vine");
    }

    static boolean naturalDecorationsUseDepthWritingCutoutPass() {
        return true;
    }

    private static RgbaColor bakedQuadTint(Direction direction) {
        if (direction == Direction.UP) return new RgbaColor(255, 255, 255, 255);
        if (direction == Direction.DOWN) return new RgbaColor(105, 105, 105, 255);
        if (direction == Direction.NORTH) return new RgbaColor(175, 175, 175, 255);
        if (direction == Direction.SOUTH) return new RgbaColor(205, 205, 205, 255);
        if (direction == Direction.WEST) return new RgbaColor(185, 185, 185, 255);
        return new RgbaColor(195, 195, 195, 255);
    }

    private static boolean previewFaceVisible(Map<Long, BlockState> states, int x, int y, int z, int step,
                                              net.minecraft.core.Direction direction) {
        int stride = Math.max(1, step);
        BlockState neighbour = states.get(BlockPos.asLong(x + direction.getStepX() * stride,
            y + direction.getStepY() * stride, z + direction.getStepZ() * stride));
        return neighbour == null || !isFullPreviewOccluder(neighbour);
    }

    private static boolean isFullPreviewOccluder(BlockState state) {
        if (state == null || state.isAir() || !state.isSolidRender()) return false;
        try {
            net.minecraft.world.phys.AABB bounds = state.getOcclusionShape().bounds();
            return bounds.minX <= 0.001 && bounds.minY <= 0.001 && bounds.minZ <= 0.001
                && bounds.maxX >= 0.999 && bounds.maxY >= 0.999 && bounds.maxZ >= 0.999;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void emitPreviewTop(GeoSink builder, TextureRegion region,
                                double x1, double y, double z1, double x2, double z2) {
        builder.ensureQuadCapacity();
        builder.quad(builder.vec3(x1, y, z1).vec2(region.x1, region.y1).color(previewTopTint).next(),
            builder.vec3(x2, y, z1).vec2(region.x2, region.y1).color(previewTopTint).next(),
            builder.vec3(x2, y, z2).vec2(region.x2, region.y2).color(previewTopTint).next(),
            builder.vec3(x1, y, z2).vec2(region.x1, region.y2).color(previewTopTint).next());
    }

    private void emitPreviewSide(GeoSink builder, TextureRegion region,
                                 double x1, double y1, double z1,
                                 double x2, double y2, double z2, boolean reverse) {
        builder.ensureQuadCapacity();
        builder.quad(builder.vec3(x1, y1, z1).vec2(region.x1, region.y2).color(previewSideTint).next(),
            builder.vec3(x2, y1, z2).vec2(region.x2, region.y2).color(previewSideTint).next(),
            builder.vec3(x2, y2, z2).vec2(region.x2, region.y1).color(previewSideTint).next(),
            builder.vec3(x1, y2, z1).vec2(region.x1, region.y1).color(previewSideTint).next());
    }

    private PreviewKey previewKey(long seed, int dimension, GeneratedStructure structure) {
        return new PreviewKey(seed, dimension, structure.type, structure.startChunkX, structure.startChunkZ,
            structure.variant, structure.hasShip);
    }

    private boolean isCurrent(WorkKey key) {
        return isCurrent(key.generation(), key.seed(), key.dimension());
    }

    private boolean isCurrent(long generation, long seed, int dimension) {
        return generation == worldGeneration && seed == lastSeed && dimension == lastDimension;
    }

    private static RgbaColor blockColor(BlockState state) {
        if (state.is(Blocks.GRASS_BLOCK)) return new RgbaColor(107, 142, 35);
        if (state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)) return new RgbaColor(210, 180, 140);
        if (state.is(Blocks.STONE) || state.is(Blocks.ANDESITE)
            || state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE))
            return new RgbaColor(128, 128, 128);
        if (state.is(Blocks.WATER)) return new RgbaColor(65, 105, 225);
        if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)
            || state.is(Blocks.POWDER_SNOW)) return new RgbaColor(210, 224, 235);
        if (state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
            || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.PODZOL))
            return new RgbaColor(139, 119, 69);
        if (state.is(Blocks.GRAVEL)) return new RgbaColor(136, 131, 122);
        if (state.is(Blocks.OAK_LOG) || state.is(Blocks.BIRCH_LOG)
            || state.is(Blocks.SPRUCE_LOG) || state.is(Blocks.DARK_OAK_LOG)
            || state.is(Blocks.JUNGLE_LOG) || state.is(Blocks.ACACIA_LOG)
            || state.is(Blocks.CHERRY_LOG))
            return new RgbaColor(101, 67, 33);
        if (state.is(Blocks.OAK_LEAVES) || state.is(Blocks.BIRCH_LEAVES)
            || state.is(Blocks.SPRUCE_LEAVES) || state.is(Blocks.DARK_OAK_LEAVES)
            || state.is(Blocks.JUNGLE_LEAVES) || state.is(Blocks.ACACIA_LEAVES)
            || state.is(Blocks.CHERRY_LEAVES))
            return new RgbaColor(34, 85, 34);
        if (state.is(Blocks.COBBLESTONE) || state.is(Blocks.MOSSY_COBBLESTONE))
            return new RgbaColor(115, 115, 115);
        if (state.is(Blocks.OAK_PLANKS) || state.is(Blocks.BIRCH_PLANKS)
            || state.is(Blocks.SPRUCE_PLANKS))
            return new RgbaColor(175, 150, 100);
        if (state.is(Blocks.CLAY)) return new RgbaColor(160, 164, 180);
        if (state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)
            || state.is(Blocks.BLUE_ICE)) return new RgbaColor(180, 220, 255);
        if (state.is(Blocks.MUD)) return new RgbaColor(80, 60, 40);
        if (state.is(Blocks.MYCELIUM)) return new RgbaColor(130, 110, 130);
        if (state.is(Blocks.END_STONE)) return new RgbaColor(218, 209, 142);
        if (state.is(Blocks.END_STONE_BRICKS)) return new RgbaColor(205, 197, 134);
        if (state.is(Blocks.PURPUR_BLOCK) || state.is(Blocks.PURPUR_PILLAR)
            || state.is(Blocks.PURPUR_STAIRS) || state.is(Blocks.PURPUR_SLAB))
            return new RgbaColor(174, 122, 190);
        if (state.is(Blocks.OBSIDIAN)) return new RgbaColor(38, 26, 56);
        if (state.is(Blocks.END_ROD)) return new RgbaColor(230, 222, 206);
        if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().contains("shulker_box")) {
            return new RgbaColor(150, 95, 170);
        }
        return new RgbaColor(150, 154, 158);
    }

    private static RgbaColor textureTint(BlockState state) {
        if (state != null && state.is(Blocks.WATER)) return new RgbaColor(63, 118, 228, 255);
        if (state != null && state.is(Blocks.LAVA)) return new RgbaColor(255, 255, 255, 255);
        // The packaged PNGs are already authored in vanilla's neutral texture
        // space. Multiplying grass/leaves by a fixed green tint made every
        // biome look over-saturated instead of matching the client renderer.
        return new RgbaColor(255, 255, 255, 255);
    }

    /**
     * Vanilla grass textures are grayscale and receive the biome's grass color
     * during model rendering. Apply the same tint to predicted terrain instead
     * of painting every grass block the same saturated green.
     */
    private RgbaColor textureTintAt(BlockState state, double x, double z) {
        return textureTintAt(state, x, 64.0, z);
    }

    private RgbaColor textureTintAt(BlockState state, double x, double y, double z) {
        RgbaColor base = textureTint(state);
        boolean grass = state != null && state.is(Blocks.GRASS_BLOCK);
        boolean water = state != null && state.is(Blocks.WATER);
        if (!grass && !water) return base;
        try {
            var biome = WorldgenEngine.getBiomeHolder(lastSeed, lastDimension,
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (biome != null && biome.value() != null) {
                int rgb = water ? biome.value().getWaterColor()
                    : biome.value().getGrassColor(x, z);
                return new RgbaColor((rgb >>> 16) & 0xFF, (rgb >>> 8) & 0xFF, rgb & 0xFF, base.a);
            }
        } catch (Throwable ignored) {
            // Keep the neutral texture if a bootstrap biome is unavailable.
        }
        return base;
    }

    /** Grass side textures already contain their brown dirt base and must not be
     * multiplied by the green top tint. */
    private static RgbaColor sideTextureTint(BlockState state) {
        if (state != null && state.is(Blocks.GRASS_BLOCK)) {
            return new RgbaColor(255, 255, 255, 255);
        }
        return textureTint(state);
    }

    private SurfaceSample resolvePredictedSurface(int dimension, WorldgenEngine.TerrainAccessor terrain,
                                                   int wx, int wz, int topY) {
        int y = topY - 1;
        BlockState top = terrain.block(wx, y, wz);
        if (!TerrainMaterialPolicy.validPredictedSurface(dimension, blockId(top))) {
            if (dimension == 1) {
                for (int candidateY = y - 1; candidateY >= 0; candidateY--) {
                    BlockState candidate = terrain.block(wx, candidateY, wz);
                    if (TerrainMaterialPolicy.validPredictedSurface(dimension, blockId(candidate))) {
                        return new SurfaceSample(candidateY + 1, candidateY, candidate, candidate);
                    }
                }
            }
            return new SurfaceSample(topY, y, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState());
        }

        if (isFluid(top) || isIce(top)) {
            SolidSample solid = findPredictedSolidBelow(terrain, wx, wz, y - 1);
            return new SurfaceSample(topY, solid.y(), top, solid.state());
        }
        return new SurfaceSample(y + 1, y, top, top);
    }

    /** Finds the highest exposed base-density surface below the Nether roof band. */
    private SurfaceSample resolveNetherSurface(WorldgenEngine.TerrainAccessor terrain, int wx, int wz) {
        for (int y = TerrainMaterialPolicy.NETHER_ROOF_CUTOFF_Y - 1; y > 0; y--) {
            BlockState state = terrain.block(wx, y, wz);
            if (state == null || state.isAir()) continue;
            BlockState above = terrain.block(wx, y + 1, wz);
            if (!isAirLike(above)) continue;
            if (isFluid(state)) {
                SolidSample solid = findPredictedSolidBelow(terrain, wx, wz, y - 1);
                return new SurfaceSample(y + 1, solid.y(), state, solid.state());
            }
            return new SurfaceSample(y + 1, y, state, state);
        }
        return new SurfaceSample(0, 0, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState());
    }

    private SurfaceSample resolveObservedSurface(LiveChunkSnapshot snapshot, int dx, int dz, int topY) {
        int waterCoverY = Integer.MIN_VALUE;
        for (int y = topY - 1; y >= snapshot.minY(); y--) {
            BlockState state = snapshot.block(dx, y, dz);
            if (state == null || state.isAir()) continue;
            if (isFluid(state) || isIce(state)) {
                SolidSample solid = findObservedSolidBelow(snapshot, dx, dz, y - 1);
                return new SurfaceSample(y + 1, solid.y(), state, solid.state());
            }
            if (isWaterCover(state)) {
                if (waterCoverY == Integer.MIN_VALUE) waterCoverY = y + 1;
                continue;
            }
            if (!isTerrainSupportingSurface(state)) continue;
            if (waterCoverY != Integer.MIN_VALUE) {
                return new SurfaceSample(waterCoverY, y,
                    Blocks.WATER.defaultBlockState(), state);
            }
            return new SurfaceSample(y + 1, y, state, state);
        }
        return new SurfaceSample(snapshot.minY(), snapshot.minY(),
            Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState());
    }

    private SolidSample findPredictedSolidBelow(WorldgenEngine.TerrainAccessor terrain, int wx, int wz, int startY) {
        for (int y = startY; y > -64; y--) {
            BlockState state = terrain.block(wx, y, wz);
            if (state != null && !state.isAir() && !isFluid(state) && !isNonSolidCover(state)) {
                return new SolidSample(y, state);
            }
        }
        return new SolidSample(startY, Blocks.AIR.defaultBlockState());
    }

    private SolidSample findObservedSolidBelow(LiveChunkSnapshot snapshot, int dx, int dz, int startY) {
        for (int y = startY; y >= snapshot.minY(); y--) {
            BlockState state = snapshot.block(dx, y, dz);
            if (isTerrainSupportingSurface(state) && !isFluid(state)) {
                return new SolidSample(y, state);
            }
        }
        return new SolidSample(startY, Blocks.AIR.defaultBlockState());
    }

    private static boolean isNonSolidCover(BlockState state) {
        return state != null && (state.is(Blocks.SNOW)
            || state.is(Blocks.SHORT_GRASS)
            || state.is(Blocks.TALL_GRASS)
            || state.is(Blocks.FERN)
            || state.is(Blocks.LARGE_FERN)
            || state.is(Blocks.VINE)
            || state.is(Blocks.SEAGRASS)
            || state.is(Blocks.TALL_SEAGRASS)
            || state.is(Blocks.KELP)
            || state.is(Blocks.KELP_PLANT));
    }

    private static boolean isTerrainSupportingSurface(BlockState state) {
        return state != null && !state.isAir() && !isNonSolidCover(state)
            && TerrainMaterialPolicy.terrainSupportingSurface(blockId(state));
    }

    private static boolean isWaterCover(BlockState state) {
        return state != null && (state.is(Blocks.SEAGRASS)
            || state.is(Blocks.TALL_SEAGRASS)
            || state.is(Blocks.KELP)
            || state.is(Blocks.KELP_PLANT));
    }

    private int renderUndergroundCaveShells(GeoSink builder, ChunkData chunk,
                                             WorldgenEngine.TerrainAccessor terrain,
                                             GeneratedTerrainHeightmap carved,
                                             int dimension, int step, int remainingBudget,
                                             boolean textureDebugView) {
        if (remainingBudget <= 0) return 0;
        int baseX = chunk.chunkX * CHUNK_SIZE;
        int baseZ = chunk.chunkZ * CHUNK_SIZE;
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        int rendered = 0;
        // Cave openings are often only one or two blocks tall. Skipping vertical
        // layers made whole passages disappear even when X/Z used a coarse LOD.
        int yStep = 1;
        int footprint = Math.max(1, Math.min(step, CHUNK_SIZE));
        int minY = dimension == -1 ? 1 : CAVE_MIN_Y;
        // Coarse surface cache entries only fill the sampled cells in heights[].
        // Cave shells deliberately run at one-block resolution, so using that
        // sparse array as a surface limit skipped nearly every X/Z column. The
        // carver replay is already the source of predicted cave blocks; reuse
        // its completed chunk for a correct world-surface bound as well.
        ChunkAccess carvedChunk = chunk.observedSnapshot == null && carved != null
            ? carved.getChunk(new ChunkPos(chunk.chunkX, chunk.chunkZ))
            : null;

        for (int dx = 0; dx < CHUNK_SIZE; dx += step) {
            for (int dz = 0; dz < CHUNK_SIZE; dz += step) {
                int index = dz * CHUNK_SIZE + dx;
                int surfaceY = chunk.observedSnapshot != null
                    ? chunk.heights[index]
                    : carvedChunk != null
                        ? carvedChunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, dx, dz) + 1
                        : chunk.heights[index];
                int maxY = dimension == -1
                    ? TerrainMaterialPolicy.NETHER_ROOF_CUTOFF_Y - 1
                    : Math.min(CAVE_MAX_Y, surfaceY - 2);
                if (chunk.observedSnapshot != null) {
                    minY = Math.max(minY, chunk.observedSnapshot.minY());
                    maxY = Math.min(maxY, chunk.observedSnapshot.maxY() - 1);
                }
                if (maxY <= minY) continue;

                int wx = baseX + dx;
                int wz = baseZ + dz;
                for (int y = minY; y <= maxY; y += yStep) {
                    if (rendered >= remainingBudget) return rendered;
                    BlockState state = sampleBlock(terrain, carved, chunk, dx, y, dz, wx, wz);
                    if (state == null || state.isAir() || isFluid(state)) continue;
                    if (!TerrainMaterialPolicy.includeCaveSample(dimension, y, blockId(state))) continue;
                    int width = Math.min(footprint, CHUNK_SIZE - dx);
                    int depth = Math.min(footprint, CHUNK_SIZE - dz);
                    int exposure = caveCellExposure(terrain, carved, chunk, dx, y, dz,
                        wx, wz, width, depth, state, dimension);
                    if (exposure == 0) continue;

                    renderCaveFaces(builder, atlas, wx, y, wz, state, width, depth, exposure);
                    rendered++;
                }
            }
        }
        return rendered;
    }

    private int caveExposure(WorldgenEngine.TerrainAccessor terrain, GeneratedTerrainHeightmap carved,
                             ChunkData chunk,
                             int localX, int y, int localZ, int worldX, int worldZ) {
        int neighbor = TerrainMaterialPolicy.exposureNeighborDistance();
        int mask = 0;
        if (isAirLike(sampleBlock(terrain, carved, chunk, localX, y + neighbor, localZ, worldX, worldZ))) mask |= FACE_TOP;
        if (isAirLike(sampleBlock(terrain, carved, chunk, localX, y - neighbor, localZ, worldX, worldZ))) mask |= FACE_BOTTOM;
        if (isAirLike(sampleBlock(terrain, carved, chunk, localX - neighbor, y, localZ, worldX - neighbor, worldZ))) mask |= FACE_WEST;
        if (isAirLike(sampleBlock(terrain, carved, chunk, localX + neighbor, y, localZ, worldX + neighbor, worldZ))) mask |= FACE_EAST;
        if (isAirLike(sampleBlock(terrain, carved, chunk, localX, y, localZ - neighbor, worldX, worldZ - neighbor))) mask |= FACE_NORTH;
        if (isAirLike(sampleBlock(terrain, carved, chunk, localX, y, localZ + neighbor, worldX, worldZ + neighbor))) mask |= FACE_SOUTH;
        return mask;
    }

    /**
     * A coarse cave quad is safe only when its whole cell has one material and
     * every block along an emitted boundary is exposed. Sampling one corner and
     * stretching it across the cell produced floating and backwards cave faces.
     */
    private int caveCellExposure(WorldgenEngine.TerrainAccessor terrain,
                                 GeneratedTerrainHeightmap carved, ChunkData chunk,
                                 int localX, int y, int localZ, int worldX, int worldZ,
                                 int width, int depth, BlockState state, int dimension) {
        if (width <= 1 && depth <= 1) {
            return caveExposure(terrain, carved, chunk, localX, y, localZ, worldX, worldZ);
        }
        String material = blockId(state);
        for (int dz = 0; dz < depth; dz++) {
            for (int dx = 0; dx < width; dx++) {
                BlockState sampled = sampleBlock(terrain, carved, chunk,
                    localX + dx, y, localZ + dz, worldX + dx, worldZ + dz);
                if (sampled == null || sampled.isAir() || isFluid(sampled)
                    || !TerrainMaterialPolicy.includeCaveSample(dimension, y, blockId(sampled))
                    || !TerrainMaterialPolicy.sameCaveMaterial(material, blockId(sampled))) {
                    return 0;
                }
            }
        }

        int mask = 0;
        if (caveHorizontalBoundaryExposed(terrain, carved, chunk, localX, y + 1,
            localZ, worldX, worldZ, width, depth)) mask |= FACE_TOP;
        if (caveHorizontalBoundaryExposed(terrain, carved, chunk, localX, y - 1,
            localZ, worldX, worldZ, width, depth)) mask |= FACE_BOTTOM;
        if (caveZBoundaryExposed(terrain, carved, chunk, localX, y, localZ - 1,
            worldX, worldZ - 1, width)) mask |= FACE_NORTH;
        if (caveZBoundaryExposed(terrain, carved, chunk, localX, y, localZ + depth,
            worldX, worldZ + depth, width)) mask |= FACE_SOUTH;
        if (caveXBoundaryExposed(terrain, carved, chunk, localX - 1, y, localZ,
            worldX - 1, worldZ, depth)) mask |= FACE_WEST;
        if (caveXBoundaryExposed(terrain, carved, chunk, localX + width, y, localZ,
            worldX + width, worldZ, depth)) mask |= FACE_EAST;
        return mask;
    }

    private boolean caveHorizontalBoundaryExposed(WorldgenEngine.TerrainAccessor terrain,
                                                   GeneratedTerrainHeightmap carved,
                                                   ChunkData chunk, int localX, int y,
                                                   int localZ, int worldX, int worldZ,
                                                   int width, int depth) {
        for (int dz = 0; dz < depth; dz++) {
            for (int dx = 0; dx < width; dx++) {
                if (!isAirLike(sampleBlock(terrain, carved, chunk, localX + dx, y,
                    localZ + dz, worldX + dx, worldZ + dz))) return false;
            }
        }
        return true;
    }

    private boolean caveZBoundaryExposed(WorldgenEngine.TerrainAccessor terrain,
                                          GeneratedTerrainHeightmap carved, ChunkData chunk,
                                          int localX, int y, int localZ, int worldX,
                                          int worldZ, int width) {
        for (int dx = 0; dx < width; dx++) {
            if (!isAirLike(sampleBlock(terrain, carved, chunk, localX + dx, y,
                localZ, worldX + dx, worldZ))) return false;
        }
        return true;
    }

    private boolean caveXBoundaryExposed(WorldgenEngine.TerrainAccessor terrain,
                                          GeneratedTerrainHeightmap carved, ChunkData chunk,
                                          int localX, int y, int localZ, int worldX,
                                          int worldZ, int depth) {
        for (int dz = 0; dz < depth; dz++) {
            if (!isAirLike(sampleBlock(terrain, carved, chunk, localX, y,
                localZ + dz, worldX, worldZ + dz))) return false;
        }
        return true;
    }

    private BlockState sampleBlock(WorldgenEngine.TerrainAccessor terrain, GeneratedTerrainHeightmap carved,
                                   ChunkData chunk,
                                   int localX, int y, int localZ, int worldX, int worldZ) {
        int sampledChunkX = Math.floorDiv(worldX, CHUNK_SIZE);
        int sampledChunkZ = Math.floorDiv(worldZ, CHUNK_SIZE);
        ChunkData sampled = chunkCache.get(ChunkPos.pack(sampledChunkX, sampledChunkZ));
        if (sampled != null && sampled.observedSnapshot != null) {
            return sampled.observedSnapshot.block(Math.floorMod(worldX, CHUNK_SIZE), y,
                Math.floorMod(worldZ, CHUNK_SIZE));
        }
        if (chunk.observedSnapshot != null && localX >= 0 && localX < CHUNK_SIZE
            && localZ >= 0 && localZ < CHUNK_SIZE) {
            return chunk.observedSnapshot.block(localX, y, localZ);
        }
        if (carved != null) {
            try {
                return carved.blockState(0L, worldX, y, worldZ);
            } catch (Throwable ignored) {
                // Fall back to the cheap density accessor if carver replay fails.
            }
        }
        return terrain.block(worldX, y, worldZ);
    }

    private boolean isAirLike(BlockState state) {
        return state == null || state.isAir() || isFluid(state);
    }

    private void renderCaveFaces(GeoSink builder, BlockTextureAtlas atlas,
                                 int x, int y, int z, BlockState state,
                                 int width, int depth, int exposure) {
        TextureRegion topRegion = atlas.region(state);
        TextureRegion sideRegion = atlas.sideRegion(state);
        boolean exactTop = topRegion != null
            && TerrainMaterialPolicy.renderAtlasMaterial(atlas.usesFallback(state));
        boolean exactSide = sideRegion != null
            && TerrainMaterialPolicy.renderAtlasMaterial(atlas.sideUsesFallback(state));
        if (!exactTop && !exactSide) return;
        RgbaColor topBase = textureTint(state);
        RgbaColor sideBase = sideTextureTint(state);
        // Minecraft's terrain shader normally supplies face and sky lighting.
        // This independent map mesh has no chunk lightmap, so retain a generous
        // ambient term and directional face light while keeping the cave shell opaque.
        RgbaColor topTint = caveLightTint(topBase, 0.92);
        RgbaColor bottomTint = caveLightTint(sideBase, 0.38);
        RgbaColor northTint = caveLightTint(sideBase, 0.62);
        RgbaColor eastTint = caveLightTint(sideBase, 0.54);
        RgbaColor southTint = caveLightTint(sideBase, 0.76);
        RgbaColor westTint = caveLightTint(sideBase, 0.68);
        double x2 = x + width;
        double y2 = y + TerrainMaterialPolicy.caveFaceHeight();
        double z2 = z + depth;

        if (exactTop && (exposure & FACE_TOP) != 0) {
            builder.ensureQuadCapacity();
            builder.quad(
                builder.vec3(x, y2, z).vec2(topRegion.x1, topRegion.y1).color(topTint).next(),
                builder.vec3(x2, y2, z).vec2(topRegion.x2, topRegion.y1).color(topTint).next(),
                builder.vec3(x2, y2, z2).vec2(topRegion.x2, topRegion.y2).color(topTint).next(),
                builder.vec3(x, y2, z2).vec2(topRegion.x1, topRegion.y2).color(topTint).next());
        }
        if (exactSide && (exposure & FACE_BOTTOM) != 0) {
            builder.ensureQuadCapacity();
            builder.quad(
                builder.vec3(x, y, z2).vec2(sideRegion.x1, sideRegion.y2).color(bottomTint).next(),
                builder.vec3(x2, y, z2).vec2(sideRegion.x2, sideRegion.y2).color(bottomTint).next(),
                builder.vec3(x2, y, z).vec2(sideRegion.x2, sideRegion.y1).color(bottomTint).next(),
                builder.vec3(x, y, z).vec2(sideRegion.x1, sideRegion.y1).color(bottomTint).next());
        }
        if (!exactSide) return;
        if ((exposure & FACE_NORTH) != 0) emitSide(builder, sideRegion, northTint, x, y, z, x2, y2, z);
        if ((exposure & FACE_EAST) != 0) emitSide(builder, sideRegion, eastTint, x2, y, z, x2, y2, z2);
        if ((exposure & FACE_SOUTH) != 0) emitSide(builder, sideRegion, southTint, x2, y, z2, x, y2, z2);
        if ((exposure & FACE_WEST) != 0) emitSide(builder, sideRegion, westTint, x, y, z2, x, y2, z);
    }

    private static RgbaColor caveLightTint(RgbaColor base, double directionalLight) {
        // Ambient cave light prevents the far shell from collapsing into black;
        // the rest is a stable sky/sun-facing directional contribution.
        double light = 0.30 + 0.70 * Math.max(0.0, Math.min(1.0, directionalLight));
        return new RgbaColor((int) Math.round(base.r * light),
            (int) Math.round(base.g * light),
            (int) Math.round(base.b * light), 255);
    }

    private int emitSide(GeoSink builder, TextureRegion region, RgbaColor tint,
                          double x1, double y1, double z1, double x2, double y2, double z2) {
        return emitSideTiled(builder, region, tint, x1, y1, z1, x2, y2, z2);
    }

    /** Repeats the block sprite at one-block intervals instead of stretching it. */
    private int emitSideTiled(GeoSink builder, TextureRegion region, RgbaColor tint,
                               double x1, double y1, double z1, double x2, double y2, double z2) {
        double length = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        // A vanilla sprite is 16 blocks wide. Repeating at one-block
        // intervals removes the stretched/smeared cliff texture while still
        // keeping a hard upper bound for pathological fallback geometry.
        int segments = Math.min(CHUNK_SIZE, Math.max(1, (int) Math.ceil(length)));
        int verticalSegments = Math.min(8, Math.max(1, (int) Math.ceil(Math.abs(y2 - y1))));
        boolean alongX = Math.abs(x2 - x1) >= Math.abs(z2 - z1);
        int emitted = 0;
        for (int i = 0; i < segments; i++) {
            double t0 = i / (double) segments;
            double t1 = (i + 1) / (double) segments;
            double ax = alongX ? x1 + (x2 - x1) * t0 : x1;
            double az = alongX ? z1 : z1 + (z2 - z1) * t0;
            double bx = alongX ? x1 + (x2 - x1) * t1 : x2;
            double bz = alongX ? z1 + (z2 - z1) * t1 : z2;
            for (int j = 0; j < verticalSegments; j++) {
                double y0 = y1 + (y2 - y1) * (j / (double) verticalSegments);
                double yTop = y1 + (y2 - y1) * ((j + 1) / (double) verticalSegments);
                builder.ensureQuadCapacity();
                builder.quad(
                    builder.vec3(ax, y0, az).vec2(region.x1, region.y2).color(tint).next(),
                    builder.vec3(bx, y0, bz).vec2(region.x2, region.y2).color(tint).next(),
                    builder.vec3(bx, yTop, bz).vec2(region.x2, region.y1).color(tint).next(),
                    builder.vec3(ax, yTop, az).vec2(region.x1, region.y1).color(tint).next());
                emitted += 4;
            }
        }
        return emitted;
    }

    private int emitGrassSideOverlay(GeoSink builder, BlockTextureAtlas atlas,
                                       double x1, double y1, double z1,
                                       double x2, double y2, double z2, double tintX, double tintZ) {
        if (!atlas.hasFile("grass_block_side_overlay.png")) return 0;
        TextureRegion overlay = atlas.regionFile("grass_block_side_overlay.png");
        RgbaColor tint = textureTintAt(Blocks.GRASS_BLOCK.defaultBlockState(), tintX, y2, tintZ);
        return emitSideTiled(builder, overlay, tint, x1, y1, z1, x2, Math.min(y2, y1 + 1.0), z2);
    }

    private static boolean isFluid(BlockState state) {
        return state != null && (state.is(Blocks.WATER) || state.is(Blocks.LAVA));
    }

    private static boolean isIce(BlockState state) {
        return state != null && (state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE));
    }

    private record SolidSample(int y, BlockState state) {
    }

    private record SurfaceSample(int surfaceY, int solidY, BlockState surfaceState, BlockState solidState) {
    }

    private record CoarseSurface(BlockState state, int y) {
    }

    private record SurfaceCell(int y, BlockState state) {
    }

    public enum TerrainAccuracy {
        UNAVAILABLE,
        /** Base noise/aquifer density only; surface rules, carvers, and features are not claimed. */
        PREDICTED_BASE_DENSITY,
        /** Experimental coarse density sampling with approximate biome materials. */
        EXPERIMENTAL_HORIZON,
        /** Vanilla noise, surface rules and carvers; decoration features are not claimed. */
        PREDICTED_VANILLA_SURFACE,
        /** Captured directly from the client's loaded chunk snapshot. */
        OBSERVED_EXACT
    }

    public record PreparationStatus(int cachedChunks, int pendingChunks, int inFlightChunks,
                                    int detailHorizonChunks, TerrainAccuracy centerAccuracy) {
        public boolean centerReady() {
            return centerAccuracy != TerrainAccuracy.UNAVAILABLE;
        }
    }

    public record BulkLoadStatus(long completedExactChunks, long targetExactChunks,
                                 int coverageRing, int detailRing, int cachedChunks,
                                 int inFlightChunks, int retryChunks, int failedChunks,
                                 boolean restoringCache, String lastError,
                                 double chunksPerSecond, long etaSeconds,
                                 int pendingCacheWrites, int terrainWorkers,
                                 int activeTerrainWorkers, int queuedGenerationJobs,
                                 long stalledSeconds) {
        public double progress() {
            if (targetExactChunks <= 0L) return 0.0;
            return Math.max(0.0, Math.min(1.0,
                completedExactChunks / (double) targetExactChunks));
        }

        public boolean exactTargetComplete() {
            return completedExactChunks >= targetExactChunks;
        }
    }

    public record DebugChunkInfo(int chunkX, int chunkZ, int requestedLod, int displayedLod,
                                 int generatedStep, String cacheSource, String bakeState,
                                 String failureReason) {
    }

    private static final class ChunkData {
        private final int chunkX;
        private final int chunkZ;
        private final short[] heights;
        private final BlockState[] states;
        private final short[] fluidHeights;
        private final BlockState[] fluidStates;
        private final LiveChunkSnapshot observedSnapshot;
        private final long observedAt;
        private final int generatedStep;
        private final TerrainAccuracy accuracy;
        private final List<GeneratedTerrainHeightmap.NaturalDecorationBlock> naturalDecorations;
        private final boolean naturalDecorationsResolved;

        private ChunkData(int chunkX, int chunkZ, int[] heights, BlockState[] states,
                          int[] fluidHeights, BlockState[] fluidStates,
                          LiveChunkSnapshot observedSnapshot, long observedAt,
                          int generatedStep, TerrainAccuracy accuracy,
                          List<GeneratedTerrainHeightmap.NaturalDecorationBlock> naturalDecorations,
                          boolean naturalDecorationsResolved) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.heights = compactHeights(heights);
            this.states = states;
            this.fluidHeights = compactHeights(fluidHeights);
            this.fluidStates = fluidStates;
            this.observedSnapshot = observedSnapshot;
            this.observedAt = observedAt;
            this.generatedStep = generatedStep;
            this.accuracy = accuracy;
            this.naturalDecorations = naturalDecorations == null
                ? List.of() : List.copyOf(naturalDecorations);
            this.naturalDecorationsResolved = naturalDecorationsResolved;
        }

        private ChunkData withNaturalDecorations(
            List<GeneratedTerrainHeightmap.NaturalDecorationBlock> decorations) {
            return new ChunkData(chunkX, chunkZ, copyHeights(), states.clone(),
                copyFluidHeights(), fluidStates.clone(), observedSnapshot, observedAt,
                generatedStep, accuracy, decorations, true);
        }

        private static short[] compactHeights(int[] source) {
            short[] compact = new short[source.length];
            for (int i = 0; i < source.length; i++) {
                int value = source[i];
                if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                    throw new IllegalArgumentException("Terrain height outside 16-bit range: " + value);
                }
                compact[i] = (short) value;
            }
            return compact;
        }

        private int[] copyHeights() { return expandHeights(heights); }
        private int[] copyFluidHeights() { return expandHeights(fluidHeights); }

        private static int[] expandHeights(short[] source) {
            int[] expanded = new int[source.length];
            for (int i = 0; i < source.length; i++) expanded[i] = source[i];
            return expanded;
        }
    }

    private record WorkKey(long seed, int dimension, long chunkKey, long generation) {
    }

    private static final class TerrainAccessorSlot {
        private long seed = Long.MIN_VALUE;
        private int dimension = Integer.MIN_VALUE;
        private WorldgenEngine.TerrainAccessor accessor;
    }

    private static final class CarvedTerrainSlot {
        private long seed = Long.MIN_VALUE;
        private int dimension = Integer.MIN_VALUE;
        private GeneratedTerrainHeightmap terrain;
    }

    private record VisibleChunk(ChunkData chunk, double distanceSq) {
    }

    private record TerrainRenderStats(int visibleChunks, int renderedChunks,
                                      int renderedVertices, int missingBakes,
                                      int budgetFallbackChunks) {
    }

    private enum TerrainGpuLayer {
        SOLID {
            @Override GpuBuffer vertices(TerrainGpuBatch batch) { return batch.solidVertices(); }
            @Override GpuBuffer indices(TerrainGpuBatch batch) { return batch.solidIndices(); }
            @Override int indexCount(TerrainGpuBatch batch) { return batch.solidIndexCount(); }
        },
        CUTOUT {
            @Override GpuBuffer vertices(TerrainGpuBatch batch) { return batch.cutoutVertices(); }
            @Override GpuBuffer indices(TerrainGpuBatch batch) { return batch.cutoutIndices(); }
            @Override int indexCount(TerrainGpuBatch batch) { return batch.cutoutIndexCount(); }
        },
        FLUID {
            @Override GpuBuffer vertices(TerrainGpuBatch batch) { return batch.fluidVertices(); }
            @Override GpuBuffer indices(TerrainGpuBatch batch) { return batch.fluidIndices(); }
            @Override int indexCount(TerrainGpuBatch batch) { return batch.fluidIndexCount(); }
        };

        abstract GpuBuffer vertices(TerrainGpuBatch batch);
        abstract GpuBuffer indices(TerrainGpuBatch batch);
        abstract int indexCount(TerrainGpuBatch batch);
    }

    private record TerrainGpuBatch(long key, long revision, long lodSignature,
                                   GpuBuffer solidVertices, GpuBuffer solidIndices,
                                   int solidIndexCount,
                                   GpuBuffer cutoutVertices, GpuBuffer cutoutIndices,
                                   int cutoutIndexCount,
                                   GpuBuffer fluidVertices, GpuBuffer fluidIndices,
                                   int fluidIndexCount,
                                   int renderedChunks, int vertexCount,
                                   int missingBakes, int budgetFallbackChunks,
                                   Map<Long, Integer> displayedSteps) {
        void close() {
            if (solidVertices != null) solidVertices.close();
            if (solidIndices != null) solidIndices.close();
            if (cutoutVertices != null) cutoutVertices.close();
            if (cutoutIndices != null) cutoutIndices.close();
            if (fluidVertices != null) fluidVertices.close();
            if (fluidIndices != null) fluidIndices.close();
        }
    }

    private record PersistentGpuGeometry(GpuBuffer vertices, GpuBuffer indices,
                                         int indexCount) {
        private static final PersistentGpuGeometry EMPTY =
            new PersistentGpuGeometry(null, null, 0);

        void close() {
            if (vertices != null) vertices.close();
            if (indices != null) indices.close();
        }
    }

    private record PackedGpuGeometry(ByteBuffer vertices, ByteBuffer indices,
                                     int indexCount) {
        private static final PackedGpuGeometry EMPTY = new PackedGpuGeometry(
            ByteBuffer.allocateDirect(0), ByteBuffer.allocateDirect(0), 0);

        static PackedGpuGeometry pack(BakedGeometry geometry) {
            return geometry.isEmpty() ? EMPTY : new PackedGpuGeometry(
                geometry.vertexBuffer(), geometry.indexBuffer(), geometry.indexCount());
        }

        boolean isEmpty() { return indexCount <= 0; }
        long byteSize() { return (long) vertices.remaining() + indices.remaining(); }
        void close() {
            if (isEmpty()) return;
            MemoryUtil.memFree(vertices);
            MemoryUtil.memFree(indices);
        }
    }

    private record PreparedGpuUpload(long key, long revision, long lifecycleGeneration,
                                     PackedGpuGeometry solid, PackedGpuGeometry cutout,
                                     PackedGpuGeometry fluid,
                                     int renderedChunks, int vertexCount, int missingBakes,
                                     int budgetFallbackChunks,
                                     Map<Long, Integer> displayedSteps,
                                     long lodSignature, long packNanos) {
        long byteSize() { return solid.byteSize() + cutout.byteSize() + fluid.byteSize(); }
        void close() {
            solid.close();
            cutout.close();
            fluid.close();
        }
    }

    private static final class TerrainGpuBuild {
        private final long key;
        private final long revision;
        private final List<ChunkData> chunks;
        private final BakingSink solid;
        private final BakingSink cutout;
        private final BakingSink fluid;
        private final Map<Long, Integer> requestedSteps;
        private final long lodSignature;
        private final CaveCutaway caveCutaway;
        private final Map<Long, Integer> displayedSteps = new java.util.HashMap<>();
        private int nextChunk;
        private int renderedChunks;
        private int vertexCount;
        private int missingBakes;
        private int budgetFallbackChunks;
        private long remainingMinimumVertices;
        private boolean skirtsAdded;

        TerrainGpuBuild(long key, long revision, List<ChunkData> chunks,
                        BakingSink solid, BakingSink cutout, BakingSink fluid,
                        Map<Long, Integer> requestedSteps, long lodSignature,
                        long remainingMinimumVertices, CaveCutaway caveCutaway) {
            this.key = key;
            this.revision = revision;
            this.chunks = chunks;
            this.solid = solid;
            this.cutout = cutout;
            this.fluid = fluid;
            this.requestedSteps = requestedSteps;
            this.lodSignature = lodSignature;
            this.remainingMinimumVertices = remainingMinimumVertices;
            this.caveCutaway = caveCutaway;
        }

        long key() { return key; }
        long revision() { return revision; }
        List<ChunkData> chunks() { return chunks; }
        BakingSink solid() { return solid; }
        BakingSink cutout() { return cutout; }
        BakingSink fluid() { return fluid; }
        Map<Long, Integer> requestedSteps() { return requestedSteps; }
        long lodSignature() { return lodSignature; }
        CaveCutaway caveCutaway() { return caveCutaway; }
        Map<Long, Integer> displayedSteps() { return displayedSteps; }
        int nextChunk() { return nextChunk; }
        void nextChunk(int nextChunk) { this.nextChunk = nextChunk; }
        int renderedChunks() { return renderedChunks; }
        void incrementRenderedChunks() { renderedChunks++; }
        int vertexCount() { return vertexCount; }
        void addVertices(int vertices) { vertexCount += Math.max(0, vertices); }
        int missingBakes() { return missingBakes; }
        void incrementMissingBakes() { missingBakes++; }
        int budgetFallbackChunks() { return budgetFallbackChunks; }
        void incrementBudgetFallbackChunks() { budgetFallbackChunks++; }
        long remainingMinimumVertices() { return remainingMinimumVertices; }
        void remainingMinimumVertices(long value) { remainingMinimumVertices = value; }
        boolean skirtsAdded() { return skirtsAdded; }
        void skirtsAdded(boolean value) { skirtsAdded = value; }
    }

    private record PreviewKey(long seed, int dimension, StructureType type, int startChunkX, int startChunkZ,
                              String variant, boolean hasShip) {
    }

    private record PreviewJobKey(long generation, PreviewKey key) {
    }

    private record PreviewTexture(TextureRegion top, TextureRegion side,
                                  int r, int g, int b,
                                  int sideR, int sideG, int sideB,
                                  boolean topFallback, boolean sideFallback) {
    }

    private record ScreenIcon(GeneratedStructure structure, double sx, double sy, double screenZ) {
    }

    /** Minimal geometry sink shared by the terrain and preview emitters. */
    private interface GeoSink {
        GeoSink vec3(double x, double y, double z);
        GeoSink vec2(double u, double v);
        GeoSink color(RgbaColor c);
        GeoSink light(int packedLight);
        int next();
        void quad(int i1, int i2, int i3, int i4);
        void ensureQuadCapacity();
    }

    /**
     * Records the emitted vertex stream (position, UV, packed RGBA) and quad index
     * tuples into growable primitive arrays for persistent GPU upload.
     */
    private static final class BakingSink implements GeoSink {
        private float[] px = new float[256];
        private float[] py = new float[256];
        private float[] pz = new float[256];
        private float[] u = new float[256];
        private float[] v = new float[256];
        private int[] rgba = new int[256];
        private int[] light = new int[256];
        private int vcount;

        private int[] quads = new int[384];
        private int qcount; // number of ints written (4 per quad)

        // Pending vertex attributes between vec3()/vec2()/color()/next().
        private float pxT, pyT, pzT, uT, vT;
        private int rgbaT;
        private int lightT = LightCoordsUtil.FULL_BRIGHT;

        @Override public GeoSink vec3(double x, double y, double z) {
            pxT = (float) x; pyT = (float) y; pzT = (float) z; return this;
        }

        @Override public GeoSink vec2(double uu, double vv) {
            uT = (float) uu; vT = (float) vv; return this;
        }

        @Override public GeoSink color(RgbaColor c) {
            rgbaT = ((c.r & 0xFF) << 24) | ((c.g & 0xFF) << 16) | ((c.b & 0xFF) << 8) | (c.a & 0xFF);
            return this;
        }

        @Override public GeoSink light(int packedLight) {
            lightT = packedLight;
            return this;
        }

        @Override public int next() {
            if (vcount == px.length) growVerts();
            px[vcount] = pxT; py[vcount] = pyT; pz[vcount] = pzT;
            u[vcount] = uT; v[vcount] = vT; rgba[vcount] = rgbaT;
            light[vcount] = lightT;
            return vcount++;
        }

        @Override public void quad(int i1, int i2, int i3, int i4) {
            if (qcount + 4 > quads.length) quads = java.util.Arrays.copyOf(quads, quads.length * 2);
            quads[qcount] = i1; quads[qcount + 1] = i2; quads[qcount + 2] = i3; quads[qcount + 3] = i4;
            qcount += 4;
        }

        @Override public void ensureQuadCapacity() {
            // Capacity is handled by the growable arrays; nothing to pre-reserve here.
        }

        private void growVerts() {
            int n = px.length * 2;
            px = java.util.Arrays.copyOf(px, n);
            py = java.util.Arrays.copyOf(py, n);
            pz = java.util.Arrays.copyOf(pz, n);
            u = java.util.Arrays.copyOf(u, n);
            v = java.util.Arrays.copyOf(v, n);
            rgba = java.util.Arrays.copyOf(rgba, n);
            light = java.util.Arrays.copyOf(light, n);
        }

        void append(BakedGeometry geometry) {
            if (geometry == null || geometry.isEmpty()) return;
            int vertexBase = vcount;
            ensureVertexCapacity(vcount + geometry.px().length);
            System.arraycopy(geometry.px(), 0, px, vcount, geometry.px().length);
            System.arraycopy(geometry.py(), 0, py, vcount, geometry.py().length);
            System.arraycopy(geometry.pz(), 0, pz, vcount, geometry.pz().length);
            System.arraycopy(geometry.u(), 0, u, vcount, geometry.u().length);
            System.arraycopy(geometry.v(), 0, v, vcount, geometry.v().length);
            System.arraycopy(geometry.rgba(), 0, rgba, vcount, geometry.rgba().length);
            System.arraycopy(geometry.light(), 0, light, vcount, geometry.light().length);
            vcount += geometry.px().length;

            ensureQuadArrayCapacity(qcount + geometry.quads().length);
            for (int index : geometry.quads()) quads[qcount++] = vertexBase + index;
        }

        int appendFiltered(BakedGeometry geometry, CaveCutaway cutaway) {
            if (geometry == null || geometry.isEmpty()) return 0;
            if (cutaway == null) {
                int appended = geometry.px().length;
                append(geometry);
                return appended;
            }

            int appended = 0;
            int[] sourceQuads = geometry.quads();
            for (int q = 0; q < sourceQuads.length; q += 4) {
                int i1 = sourceQuads[q];
                int i2 = sourceQuads[q + 1];
                int i3 = sourceQuads[q + 2];
                int i4 = sourceQuads[q + 3];
                double centerX = (geometry.px()[i1] + geometry.px()[i2]
                    + geometry.px()[i3] + geometry.px()[i4]) * 0.25;
                double centerY = (geometry.py()[i1] + geometry.py()[i2]
                    + geometry.py()[i3] + geometry.py()[i4]) * 0.25;
                double centerZ = (geometry.pz()[i1] + geometry.pz()[i2]
                    + geometry.pz()[i3] + geometry.pz()[i4]) * 0.25;
                if (cutaway.withinCutaway(centerX, centerY, centerZ)
                    || cutaway.withinCutaway(geometry.px()[i1], geometry.py()[i1], geometry.pz()[i1])
                    || cutaway.withinCutaway(geometry.px()[i2], geometry.py()[i2], geometry.pz()[i2])
                    || cutaway.withinCutaway(geometry.px()[i3], geometry.py()[i3], geometry.pz()[i3])
                    || cutaway.withinCutaway(geometry.px()[i4], geometry.py()[i4], geometry.pz()[i4])) continue;

                ensureVertexCapacity(vcount + 4);
                ensureQuadArrayCapacity(qcount + 4);
                int destinationBase = vcount;
                copyVertex(geometry, i1);
                copyVertex(geometry, i2);
                copyVertex(geometry, i3);
                copyVertex(geometry, i4);
                quads[qcount++] = destinationBase;
                quads[qcount++] = destinationBase + 1;
                quads[qcount++] = destinationBase + 2;
                quads[qcount++] = destinationBase + 3;
                appended += 4;
            }
            return appended;
        }

        private void copyVertex(BakedGeometry source, int index) {
            px[vcount] = source.px()[index];
            py[vcount] = source.py()[index];
            pz[vcount] = source.pz()[index];
            u[vcount] = source.u()[index];
            v[vcount] = source.v()[index];
            rgba[vcount] = source.rgba()[index];
            light[vcount] = source.light()[index];
            vcount++;
        }

        private void ensureVertexCapacity(int required) {
            if (required <= px.length) return;
            int n = px.length;
            while (n < required) n = Math.max(n * 2, required);
            px = java.util.Arrays.copyOf(px, n);
            py = java.util.Arrays.copyOf(py, n);
            pz = java.util.Arrays.copyOf(pz, n);
            u = java.util.Arrays.copyOf(u, n);
            v = java.util.Arrays.copyOf(v, n);
            rgba = java.util.Arrays.copyOf(rgba, n);
            light = java.util.Arrays.copyOf(light, n);
        }

        private void ensureQuadArrayCapacity(int required) {
            if (required <= quads.length) return;
            int n = quads.length;
            while (n < required) n = Math.max(n * 2, required);
            quads = java.util.Arrays.copyOf(quads, n);
        }

        BakedGeometry finish() {
            return new BakedGeometry(
                java.util.Arrays.copyOf(px, vcount),
                java.util.Arrays.copyOf(py, vcount),
                java.util.Arrays.copyOf(pz, vcount),
                java.util.Arrays.copyOf(u, vcount),
                java.util.Arrays.copyOf(v, vcount),
                java.util.Arrays.copyOf(rgba, vcount),
                java.util.Arrays.copyOf(light, vcount),
                java.util.Arrays.copyOf(quads, qcount));
        }
    }

    /** Immutable baked vertex/quad arrays for one mesh layer (solid or fluid). */
    private record BakedGeometry(float[] px, float[] py, float[] pz,
                                 float[] u, float[] v, int[] rgba, int[] light, int[] quads) {
        private static final int VERTEX_SIZE_BYTES = 24;

        boolean isEmpty() { return quads.length == 0; }

        int indexCount() {
            return quads.length / 4 * 6;
        }

        ByteBuffer vertexBuffer() {
            ByteBuffer buffer = MemoryUtil.memAlloc(px.length * VERTEX_SIZE_BYTES)
                .order(ByteOrder.nativeOrder());
            for (int i = 0; i < px.length; i++) {
                buffer.putFloat(px[i]);
                buffer.putFloat(py[i]);
                buffer.putFloat(pz[i]);
                buffer.putFloat(u[i]);
                buffer.putFloat(v[i]);
                int color = rgba[i];
                buffer.put((byte) ((color >>> 24) & 0xFF));
                buffer.put((byte) ((color >>> 16) & 0xFF));
                buffer.put((byte) ((color >>> 8) & 0xFF));
                buffer.put((byte) (color & 0xFF));
            }
            return buffer.flip();
        }

        ByteBuffer vanillaVertexBuffer() {
            ByteBuffer buffer = MemoryUtil.memAlloc(px.length * 28).order(ByteOrder.nativeOrder());
            for (int i = 0; i < px.length; i++) {
                buffer.putFloat(px[i]);
                buffer.putFloat(py[i]);
                buffer.putFloat(pz[i]);
                buffer.putFloat(u[i]);
                buffer.putFloat(v[i]);
                int packed = light[i];
                buffer.putShort((short) (packed & 0xFFFF));
                buffer.putShort((short) ((packed >>> 16) & 0xFFFF));
                int color = rgba[i];
                buffer.put((byte) ((color >>> 24) & 0xFF));
                buffer.put((byte) ((color >>> 16) & 0xFF));
                buffer.put((byte) ((color >>> 8) & 0xFF));
                buffer.put((byte) (color & 0xFF));
            }
            return buffer.flip();
        }

        ByteBuffer indexBuffer() {
            ByteBuffer buffer = MemoryUtil.memAlloc(indexCount() * Integer.BYTES)
                .order(ByteOrder.nativeOrder());
            for (int q = 0; q < quads.length; q += 4) {
                int i1 = quads[q];
                int i2 = quads[q + 1];
                int i3 = quads[q + 2];
                int i4 = quads[q + 3];
                buffer.putInt(i1).putInt(i2).putInt(i3);
                buffer.putInt(i3).putInt(i4).putInt(i1);
            }
            return buffer.flip();
        }

        long estimatedBytes() {
            return ((long) px.length * 6L + quads.length) * Integer.BYTES;
        }

    }

    /** Surface geometry retained at one sampling step. */
    private record SurfaceLod(int step, BakedGeometry solid, BakedGeometry fluid) {
        int vertexCount() {
            return solid.px().length + fluid.px().length;
        }

        long estimatedBytes() {
            return 48L + solid.estimatedBytes() + fluid.estimatedBytes();
        }
    }

    /** Baked geometry for a chunk at a given detail step, plus coarser fallbacks. */
    private record BakedChunk(long seed, int dimension, int chunkX, int chunkZ, int step,
                              int caveStep, long observedAt, long geometryRevision,
                              boolean textureDebugView, boolean cavesIncluded,
                              boolean naturalFeaturesIncluded,
                              List<SurfaceLod> surfaceLods, BakedGeometry naturalSolid,
                              BakedGeometry caveSolid) {
        SurfaceLod surfaceLodAtLeast(int minimumStep) {
            for (SurfaceLod lod : surfaceLods) {
                if (lod.step() >= minimumStep) return lod;
            }
            return surfaceLods.isEmpty() ? null : surfaceLods.get(surfaceLods.size() - 1);
        }

        boolean hasSurfaceLod(int requestedStep) {
            for (SurfaceLod lod : surfaceLods) {
                if (lod.step() == requestedStep) return true;
            }
            return false;
        }

        String surfaceSteps() {
            StringBuilder steps = new StringBuilder();
            for (SurfaceLod lod : surfaceLods) {
                if (!steps.isEmpty()) steps.append(',');
                steps.append(lod.step());
            }
            return steps.toString();
        }

        SurfaceLod surfaceLodForBudget(int minimumStep, int maximumStep, int maximumVertices) {
            SurfaceLod selected = null;
            for (SurfaceLod lod : surfaceLods) {
                if (lod.step() < minimumStep || lod.step() > maximumStep) continue;
                selected = lod;
                if (lod.vertexCount() <= maximumVertices) return lod;
            }
            return selected != null && selected.vertexCount() <= maximumVertices ? selected : null;
        }

        boolean hasNoSurfaceGeometry() {
            if (surfaceLods.isEmpty()) return true;
            for (SurfaceLod lod : surfaceLods) {
                if (lod.vertexCount() > 0) return false;
            }
            return true;
        }

        BakedChunk minimumSurfaceCopy() {
            if (isMinimumSurfaceOnly()) return this;
            SurfaceLod minimum = null;
            for (SurfaceLod lod : surfaceLods) {
                if (lod.step() == 4) {
                    minimum = lod;
                    break;
                }
            }
            if (minimum == null && !surfaceLods.isEmpty()) {
                minimum = surfaceLods.get(surfaceLods.size() - 1);
            }
            List<SurfaceLod> retained = minimum == null ? List.of() : List.of(minimum);
            return new BakedChunk(seed, dimension, chunkX, chunkZ,
                minimum == null ? step : minimum.step(), caveStep, observedAt, geometryRevision,
                textureDebugView, false, naturalFeaturesIncluded, retained, naturalSolid,
                EMPTY_GEOMETRY);
        }

        boolean isMinimumSurfaceOnly() {
            return surfaceLods.size() <= 1 && !cavesIncluded && caveSolid.px().length == 0;
        }

        int caveVertexCount() {
            return dimension != 1 ? caveSolid.px().length : 0;
        }

        long estimatedBytes() {
            long bytes = 320L + naturalSolid.estimatedBytes() + caveSolid.estimatedBytes();
            for (SurfaceLod lod : surfaceLods) bytes += lod.estimatedBytes();
            return bytes;
        }
    }

    private static final BakedGeometry EMPTY_GEOMETRY = new BakedGeometry(
        new float[0], new float[0], new float[0], new float[0], new float[0],
        new int[0], new int[0], new int[0]);

    /**
     * Camera-local cutaway identity used while assembling cave GPU batches. The
     * canonical baked cave mesh remains complete, so orbit movement only replaces
     * the small set of batches touched by the old or new cutaway.
     */
    private record CaveCutaway(int bucketX, int bucketY, int bucketZ,
                               double centerX, double centerY, double centerZ,
                               double targetX, double targetY, double targetZ,
                               int targetChunkX, int targetChunkZ,
                               boolean focused) {
        static CaveCutaway at(FreeCamera3D camera,
                              TerrainFocusTarget inspectionTarget) {
            double x = camera.eyeX();
            double y = camera.eyeY();
            double z = camera.eyeZ();
            boolean focused = inspectionTarget != null;
            int bucketSize = focused ? CAVE_FOCUS_CUTAWAY_BUCKET_BLOCKS
                : CAVE_CUTAWAY_BUCKET_BLOCKS;
            int bucketX = Math.floorDiv((int) Math.floor(x), bucketSize);
            int bucketY = Math.floorDiv((int) Math.floor(y), bucketSize);
            int bucketZ = Math.floorDiv((int) Math.floor(z), bucketSize);
            double centerX = bucketX * (double) bucketSize + bucketSize * 0.5;
            double centerY = bucketY * (double) bucketSize + bucketSize * 0.5;
            double centerZ = bucketZ * (double) bucketSize + bucketSize * 0.5;
            double targetX = focused ? inspectionTarget.x() : centerX;
            double targetY = focused ? inspectionTarget.y() : centerY;
            double targetZ = focused ? inspectionTarget.z() : centerZ;
            return new CaveCutaway(bucketX, bucketY, bucketZ, centerX, centerY, centerZ,
                targetX, targetY, targetZ,
                Math.floorDiv((int) Math.floor(targetX), CHUNK_SIZE),
                Math.floorDiv((int) Math.floor(targetZ), CHUNK_SIZE), focused);
        }

        static boolean sameBucket(CaveCutaway first, CaveCutaway second) {
            return first == second || (first != null && second != null
                && first.bucketX == second.bucketX
                && first.bucketY == second.bucketY
                && first.bucketZ == second.bucketZ
                && first.targetChunkX == second.targetChunkX
                && first.targetChunkZ == second.targetChunkZ
                && first.focused == second.focused);
        }

        boolean withinCutaway(double x, double y, double z) {
            double dx = x - centerX;
            double dy = y - centerY;
            double dz = z - centerZ;
            if (dx * dx + dy * dy + dz * dz
                <= (double) CAVE_CUTAWAY_RADIUS_BLOCKS * CAVE_CUTAWAY_RADIUS_BLOCKS) return true;
            return focused && distanceToSegmentSquared(x, y, z) <=
                (double) CAVE_FOCUS_CORRIDOR_RADIUS_BLOCKS * CAVE_FOCUS_CORRIDOR_RADIUS_BLOCKS;
        }

        boolean affectsChunk(int chunkX, int chunkZ) {
            double minX = chunkX * (double) CHUNK_SIZE;
            double minZ = chunkZ * (double) CHUNK_SIZE;
            double maxX = minX + CHUNK_SIZE;
            double maxZ = minZ + CHUNK_SIZE;
            double horizontalPadding = focused ? CAVE_FOCUS_CORRIDOR_RADIUS_BLOCKS
                : CAVE_CUTAWAY_RADIUS_BLOCKS;
            double segmentMinX = Math.min(centerX, targetX) - horizontalPadding;
            double segmentMaxX = Math.max(centerX, targetX) + horizontalPadding;
            double segmentMinZ = Math.min(centerZ, targetZ) - horizontalPadding;
            double segmentMaxZ = Math.max(centerZ, targetZ) + horizontalPadding;
            return maxX >= segmentMinX && minX <= segmentMaxX
                && maxZ >= segmentMinZ && minZ <= segmentMaxZ;
        }

        private double distanceToSegmentSquared(double x, double y, double z) {
            double vx = targetX - centerX;
            double vy = targetY - centerY;
            double vz = targetZ - centerZ;
            double lengthSq = vx * vx + vy * vy + vz * vz;
            if (lengthSq <= 1.0e-6) return Double.POSITIVE_INFINITY;
            double t = ((x - centerX) * vx + (y - centerY) * vy + (z - centerZ) * vz)
                / lengthSq;
            t = Math.max(0.0, Math.min(1.0, t));
            double closestX = centerX + vx * t;
            double closestY = centerY + vy * t;
            double closestZ = centerZ + vz * t;
            double dx = x - closestX;
            double dy = y - closestY;
            double dz = z - closestZ;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
