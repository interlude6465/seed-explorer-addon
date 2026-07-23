package me.seedexplorer.addon.render;

import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.live.LiveChunkRecorder;
import me.seedexplorer.addon.live.LiveChunkSnapshot;
import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.preview.StructurePreviewModel;
import me.seedexplorer.addon.preview.StructurePreviewSimulator;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.waypoints.SeedWaypoint;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import meteordevelopment.meteorclient.gui.renderer.packer.TextureRegion;
import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.Texture;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class Terrain3DRenderer {
    private static final int LOAD_DIST = 64; // chunks generated around the camera in every direction
    private static final int RENDER_DIST = 80; // chunks visible around the camera in every direction
    private static final int CACHE_RETAIN_DIST = 144;
    private static final int CHUNK_SIZE = 16;
    /** Vertical spacing between underground spectator slices. */
    private static final int LAYER_STEP = 16;
    private static final int CHUNK_SUBMISSIONS_PER_FRAME = 64;
    private static final int MAX_IN_FLIGHT_CHUNKS = 160;
    private static final int OBSERVED_REFRESHES_PER_FRAME = 4;
    private static final int TERRAIN_THREADS = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 3));
    private static final int MAX_SIDE_FACE_HEIGHT = 32;
    private static final int MAX_TERRAIN_CHUNKS_PER_FRAME = 5200;
    private static final int PREVIEW_RENDER_RADIUS_BLOCKS = 384;
    private static final int PREVIEW_SUBMISSIONS_PER_FRAME = 2;
    private static final int MAX_STRUCTURE_PREVIEW_BLOCKS_PER_FRAME = 28000;
    private static final int CAVE_MIN_Y = -48;
    private static final int CAVE_MAX_Y = 112;
    private static final int END_SHELL_MIN_Y = 0;
    private static final int END_SHELL_MAX_Y = 128;
    private static final int END_SHELL_RENDER_DIST = 24;
    /** Per-chunk voxel caps used while baking (bounds memory of a single baked chunk). */
    private static final int MAX_CAVE_VOXELS_PER_CHUNK = 3000;
    private static final int MAX_END_VOXELS_PER_CHUNK = 2200;
    /** Chunk geometry bakes started per frame. Baking runs off the render thread. */
    private static final int BAKES_PER_FRAME = 12;
    private static final int MAX_IN_FLIGHT_BAKES = 96;

    private final ExecutorService terrainExecutor = Executors.newFixedThreadPool(TERRAIN_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "Seed Explorer Terrain Generator " + TERRAIN_THREAD_IDS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicInteger TERRAIN_THREAD_IDS = new AtomicInteger();

    private final Map<Long, ChunkData> chunkCache = new ConcurrentHashMap<>();
    /** Pre-baked, ready-to-replay geometry per chunk. Built off the render thread so
     *  each frame only copies vertices into the frame mesh instead of regenerating
     *  (and re-sampling worldgen noise for) the whole visible world. */
    private final Map<Long, BakedChunk> bakedCache = new ConcurrentHashMap<>();
    private final Set<Long> inFlightBakes = ConcurrentHashMap.newKeySet();
    private final Set<WorkKey> inFlightChunks = ConcurrentHashMap.newKeySet();
    private final Map<PreviewKey, StructurePreviewModel> previewCache = new ConcurrentHashMap<>();
    private final Set<PreviewKey> inFlightPreviews = ConcurrentHashMap.newKeySet();
    /** Chunk positions still to generate for the current center, in spiral order
     *  (nearest first). Refilled when the seed or center chunk changes. */
    private java.util.ArrayDeque<long[]> pendingChunks = new java.util.ArrayDeque<>();
    private volatile long lastSeed;
    private volatile int lastDimension = Integer.MIN_VALUE;
    private volatile String lastTerrainError = "";
    private final AtomicInteger failedChunkGenerations = new AtomicInteger();
    private int lastCenterChunkX = Integer.MIN_VALUE;
    private int lastCenterChunkZ = Integer.MIN_VALUE;

    public void render(FreeCamera3D camera, MapRenderContext context,
                       List<GeneratedStructure> structures, List<SeedWaypoint> waypoints,
                       GeneratedStructure hoveredStructure, SeedWaypoint hoveredWaypoint) {
        render(camera, context, structures, waypoints, hoveredStructure, hoveredWaypoint, false);
    }

    public void render(FreeCamera3D camera, MapRenderContext context,
                       List<GeneratedStructure> structures, List<SeedWaypoint> waypoints,
                       GeneratedStructure hoveredStructure, SeedWaypoint hoveredWaypoint,
                       boolean undergroundView) {
        render(camera, context, structures, waypoints, hoveredStructure, hoveredWaypoint, undergroundView, false);
    }

    public void render(FreeCamera3D camera, MapRenderContext context,
                       List<GeneratedStructure> structures, List<SeedWaypoint> waypoints,
                       GeneratedStructure hoveredStructure, SeedWaypoint hoveredWaypoint,
                       boolean undergroundView, boolean textureDebugView) {
        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) return;

        int centerChunkX = (int) Math.floor(camera.targetX() / CHUNK_SIZE);
        int centerChunkZ = (int) Math.floor(camera.targetZ() / CHUNK_SIZE);

        int dimension = context.dimension();
        boolean worldChanged = seed != lastSeed || dimension != lastDimension;
        if (worldChanged || centerChunkX != lastCenterChunkX || centerChunkZ != lastCenterChunkZ) {
            if (worldChanged) {
                lastSeed = seed;
                lastDimension = dimension;
                chunkCache.clear();
                bakedCache.clear();
                inFlightBakes.clear();
                inFlightChunks.clear();
                previewCache.clear();
                inFlightPreviews.clear();
                lastTerrainError = "";
                failedChunkGenerations.set(0);
            }
            rebuildPending(centerChunkX, centerChunkZ);
            lastCenterChunkX = centerChunkX;
            lastCenterChunkZ = centerChunkZ;
        }
        submitPendingChunks(seed, dimension);
        if (chunkCache.isEmpty()) {
            BlockTextureAtlas atlas = BlockTextureAtlas.get();
            renderStatusOverlay("Loading 3D terrain...",
                inFlightChunks.size() + " chunks queued"
                    + " | " + atlas.status()
                    + (lastTerrainError.isBlank() ? "" : " | last error: " + lastTerrainError));
            return;
        }
        refreshObservedChunks(dimension);

        Matrix4f savedProj = new Matrix4f(RenderUtils.projection);
        RenderUtils.projection.set(camera.getProjectionMatrix());

        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(camera.getViewMatrix());

        MeshBuilder solidBuilder = new MeshBuilder(SeedExplorerRenderPipelines.WORLD_TEXTURED);
        solidBuilder.begin();
        MeshBuilder fluidBuilder = new MeshBuilder(SeedExplorerRenderPipelines.WORLD_TEXTURED_TRANSLUCENT);
        fluidBuilder.begin();

        List<VisibleChunk> visibleChunks = new ArrayList<>();
        for (Map.Entry<Long, ChunkData> entry : chunkCache.entrySet()) {
            ChunkData chunk = entry.getValue();
            if (isChunkVisible(camera, chunk.chunkX, chunk.chunkZ)) {
                visibleChunks.add(new VisibleChunk(chunk, chunkDistanceSq(camera, chunk.chunkX, chunk.chunkZ)));
            }
        }
        visibleChunks.sort(java.util.Comparator.comparingDouble(VisibleChunk::distanceSq));

        // Replay pre-baked geometry (built off-thread). Each frame only copies cached
        // vertices into the mesh instead of regenerating terrain and re-sampling
        // worldgen noise for every visible chunk. Chunks whose baked geometry is
        // missing or stale (detail step / view mode / observed data changed) are
        // queued for a background re-bake, capped per frame.
        Color scratch = new Color(255, 255, 255, 255);
        int bakesSubmitted = 0;
        int terrainChunksRendered = 0;
        for (VisibleChunk visible : visibleChunks) {
            if (terrainChunksRendered >= MAX_TERRAIN_CHUNKS_PER_FRAME) break;
            ChunkData chunk = visible.chunk();
            maybeUpgradeChunkDetail(chunk);

            long key = ChunkPos.pack(chunk.chunkX, chunk.chunkZ);
            BakedChunk baked = bakedCache.get(key);
            if (isBakeStale(baked, seed, dimension, chunk, camera, undergroundView, textureDebugView)) {
                if (bakesSubmitted < BAKES_PER_FRAME) {
                    if (submitBake(seed, dimension, chunk, camera, undergroundView, textureDebugView)) bakesSubmitted++;
                }
                // Replay the stale mesh this frame if we still have one, so terrain
                // doesn't flicker out while the fresh bake is in flight.
                if (baked == null) continue;
            }
            baked.solid().replay(solidBuilder, scratch);
            baked.fluid().replay(fluidBuilder, scratch);
            terrainChunksRendered++;
        }
        renderStructurePreviews(new MeshSink(solidBuilder), camera, structures, hoveredStructure, seed, dimension, undergroundView, textureDebugView);

        solidBuilder.end();
        MeshRenderer renderer = MeshRenderer.begin()
            .attachments(Minecraft.getInstance().getMainRenderTarget())
            .pipeline(SeedExplorerRenderPipelines.WORLD_TEXTURED)
            .mesh(solidBuilder, new PoseStack());
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        if (atlas.texture() != null) {
            renderer.sampler("u_Texture", atlas.texture().getTextureView(), atlas.texture().getSampler());
        }
        renderer.end();

        fluidBuilder.end();
        MeshRenderer fluidRenderer = MeshRenderer.begin()
            .attachments(Minecraft.getInstance().getMainRenderTarget())
            .pipeline(SeedExplorerRenderPipelines.WORLD_TEXTURED_TRANSLUCENT)
            .mesh(fluidBuilder, new PoseStack());
        if (atlas.texture() != null) {
            fluidRenderer.sampler("u_Texture", atlas.texture().getTextureView(), atlas.texture().getSampler());
        }
        fluidRenderer.end();

        modelView.popMatrix();
        RenderUtils.projection.set(savedProj);

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
     * Refills the pending-queue with every chunk in the render ring around the new
     * center, ordered nearest-first (spiral). Already-cached chunks are skipped.
     * Chunks outside a much larger retained ring are dropped only as a memory cap,
     * so flying forward does not make the world vanish directly behind the camera.
     */
    private void rebuildPending(int centerChunkX, int centerChunkZ) {
        pendingChunks.clear();
        // Spiral outward by Chebyshev ring so the camera's surroundings fill first.
        for (int ring = 0; ring <= LOAD_DIST; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int cx = centerChunkX + dx;
                    int cz = centerChunkZ + dz;
                    long key = ChunkPos.pack(cx, cz);
                    if (!chunkCache.containsKey(key)) {
                        pendingChunks.add(new long[]{key, cx, cz});
                    }
                }
            }
        }
        chunkCache.entrySet().removeIf(entry -> {
            int cx = entry.getValue().chunkX;
            int cz = entry.getValue().chunkZ;
            return Math.abs(cx - centerChunkX) > CACHE_RETAIN_DIST || Math.abs(cz - centerChunkZ) > CACHE_RETAIN_DIST;
        });
        bakedCache.entrySet().removeIf(entry -> {
            int cx = entry.getValue().chunkX();
            int cz = entry.getValue().chunkZ();
            return Math.abs(cx - centerChunkX) > CACHE_RETAIN_DIST || Math.abs(cz - centerChunkZ) > CACHE_RETAIN_DIST;
        });
    }

    /** Queues a capped number of chunks for background prediction, nearest first. */
    private void submitPendingChunks(long seed, int dimension) {
        int budget = CHUNK_SUBMISSIONS_PER_FRAME;
        while (budget-- > 0 && inFlightChunks.size() < MAX_IN_FLIGHT_CHUNKS && !pendingChunks.isEmpty()) {
            long[] entry = pendingChunks.poll();
            long key = entry[0];
            int cx = (int) entry[1];
            int cz = (int) entry[2];
            if (chunkCache.containsKey(key)) continue;

            WorkKey workKey = new WorkKey(seed, dimension, key);
            if (!inFlightChunks.add(workKey)) continue;

            terrainExecutor.execute(() -> {
                try {
                    ChunkData chunk = generateChunkData(seed, dimension, cx, cz, generationStepForChunk(cx, cz));
                    if (seed == lastSeed && dimension == lastDimension) {
                        chunkCache.put(key, chunk);
                    }
                } catch (Throwable ignored) {
                    failedChunkGenerations.incrementAndGet();
                    lastTerrainError = ignored.getClass().getSimpleName()
                        + (ignored.getMessage() == null ? "" : ": " + ignored.getMessage());
                } finally {
                    inFlightChunks.remove(workKey);
                }
            });
        }
    }

    /** The detail step a chunk's geometry should be baked at, given the camera. */
    private int bakeStep(FreeCamera3D camera, ChunkData chunk) {
        return Math.max(sampleStep(camera, chunk), chunk.generatedStep);
    }

    /**
     * A baked mesh is stale (and must be re-baked) when it is missing, was baked for
     * a different world, detail step, view mode, or before newer observed chunk data.
     */
    private boolean isBakeStale(BakedChunk baked, long seed, int dimension, ChunkData chunk,
                                FreeCamera3D camera, boolean undergroundView, boolean textureDebugView) {
        if (baked == null) return true;
        if (baked.seed() != seed || baked.dimension() != dimension) return true;
        if (baked.observedAt() != chunk.observedAt) return true;
        return baked.step() != bakeStep(camera, chunk);
    }

    /**
     * Bakes one chunk's geometry off the render thread and stores it. Returns true if
     * a bake was actually queued (i.e. it wasn't already in flight). All the expensive
     * work — surface mesh, cave/End voxel shells and their worldgen noise sampling —
     * happens here, once per detail level, instead of every frame.
     */
    private boolean submitBake(long seed, int dimension, ChunkData chunk, FreeCamera3D camera,
                               boolean undergroundView, boolean textureDebugView) {
        if (inFlightBakes.size() >= MAX_IN_FLIGHT_BAKES) return false;
        long key = ChunkPos.pack(chunk.chunkX, chunk.chunkZ);
        if (!inFlightBakes.add(key)) return false;

        int step = bakeStep(camera, chunk);
        int caveStep = undergroundSampleStepFor(chunk, step);
        int endStep = endShellSampleStepFor(chunk, step);
        long observedAt = chunk.observedAt;
        terrainExecutor.execute(() -> {
            try {
                BakedChunk baked = bakeChunk(seed, dimension, chunk, step, caveStep, endStep,
                    undergroundView, textureDebugView, observedAt);
                if (seed == lastSeed && dimension == lastDimension) {
                    bakedCache.put(key, baked);
                }
            } catch (Throwable ignored) {
                lastTerrainError = ignored.getClass().getSimpleName()
                    + (ignored.getMessage() == null ? "" : ": " + ignored.getMessage());
            } finally {
                inFlightBakes.remove(key);
            }
        });
        return true;
    }

    /** Builds the solid + fluid geometry for a chunk into reusable arrays. */
    private BakedChunk bakeChunk(long seed, int dimension, ChunkData chunk, int step, int caveStep, int endStep,
                                 boolean undergroundView, boolean textureDebugView, long observedAt) {
        BakingSink solid = new BakingSink();
        BakingSink fluid = new BakingSink();

        renderChunkMesh(solid, fluid, chunk, dimension, step, textureDebugView);

        if (undergroundView || dimension == 1) {
            // Underground/End shells sample worldgen noise per voxel; do it here (once
            // per bake) rather than on the render thread every frame.
            WorldgenEngine.TerrainAccessor terrain = chunk.observedSnapshot != null
                ? null : WorldgenEngine.terrainAccessor(seed, dimension);
            if (undergroundView) {
                renderUndergroundCaveShells(solid, chunk, terrain, caveStep,
                    MAX_CAVE_VOXELS_PER_CHUNK, textureDebugView);
            } else {
                int chunkDistance = Math.max(Math.abs(chunk.chunkX - lastCenterChunkX),
                    Math.abs(chunk.chunkZ - lastCenterChunkZ));
                if (chunkDistance <= END_SHELL_RENDER_DIST) {
                    renderEndIslandShells(solid, chunk, terrain, endStep,
                        MAX_END_VOXELS_PER_CHUNK, textureDebugView);
                }
            }
        }

        return new BakedChunk(seed, dimension, chunk.chunkX, chunk.chunkZ, step, observedAt,
            solid.finish(), fluid.finish());
    }

    private int undergroundSampleStepFor(ChunkData chunk, int surfaceStep) {
        int chunkDistance = Math.max(Math.abs(chunk.chunkX - lastCenterChunkX), Math.abs(chunk.chunkZ - lastCenterChunkZ));
        if (chunkDistance <= 2) return 2;
        if (chunkDistance <= 5) return 4;
        if (chunkDistance <= 9) return 8;
        return 16;
    }

    private int endShellSampleStepFor(ChunkData chunk, int surfaceStep) {
        int chunkDistance = Math.max(Math.abs(chunk.chunkX - lastCenterChunkX), Math.abs(chunk.chunkZ - lastCenterChunkZ));
        if (chunkDistance <= 3) return 2;
        if (chunkDistance <= 7) return 4;
        if (chunkDistance <= 14) return 8;
        return 16;
    }

    private ChunkData generateChunkData(long seed, int dimension, int cx, int cz, int generatedStep) {
        LiveChunkSnapshot snapshot = LiveChunkRecorder.get().snapshot(dimension, cx, cz);
        if (snapshot != null) return generateObservedChunkData(snapshot);

        int[] heights = new int[CHUNK_SIZE * CHUNK_SIZE];
        BlockState[] states = new BlockState[CHUNK_SIZE * CHUNK_SIZE];
        int[] fluidHeights = new int[CHUNK_SIZE * CHUNK_SIZE];
        BlockState[] fluidStates = new BlockState[CHUNK_SIZE * CHUNK_SIZE];
        int baseX = cx * CHUNK_SIZE;
        int baseZ = cz * CHUNK_SIZE;
        WorldgenEngine.TerrainAccessor terrain = WorldgenEngine.terrainAccessor(seed, dimension);

        for (int dx = 0; dx < CHUNK_SIZE; dx += generatedStep) {
            for (int dz = 0; dz < CHUNK_SIZE; dz += generatedStep) {
                int wx = baseX + dx;
                int wz = baseZ + dz;
                int y = terrain.oceanFloorHeight(wx, wz);
                int index = dz * CHUNK_SIZE + dx;
                SurfaceSample surface = resolvePredictedSurface(seed, dimension, terrain, wx, wz, y);
                BlockState topState = surface.surfaceState();
                if (isFluid(topState)) {
                    fluidHeights[index] = y;
                    fluidStates[index] = topState;
                    heights[index] = surface.solidY() + 1;
                    states[index] = surface.solidState();
                    continue;
                }
                heights[index] = surface.surfaceY();
                states[index] = topState;
            }
        }
        return new ChunkData(cx, cz, heights, states, fluidHeights, fluidStates, null, 0L, generatedStep);
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
                    fluidHeights[index] = y;
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
            snapshot, snapshot.updatedAt(), 1);
    }

    private void refreshObservedChunks(int dimension) {
        int refreshed = 0;
        int probes = 0;
        int maxProbes = OBSERVED_REFRESHES_PER_FRAME * 4;
        for (Map.Entry<Long, ChunkData> entry : chunkCache.entrySet()) {
            if (refreshed >= OBSERVED_REFRESHES_PER_FRAME || probes++ >= maxProbes) return;
            ChunkData chunk = entry.getValue();
            LiveChunkSnapshot snapshot = LiveChunkRecorder.get().snapshot(dimension, chunk.chunkX, chunk.chunkZ);
            if (snapshot == null || snapshot.updatedAt() <= chunk.observedAt) continue;
            chunkCache.put(entry.getKey(), generateObservedChunkData(snapshot));
            refreshed++;
        }
    }

    private boolean isChunkVisible(FreeCamera3D camera, int chunkX, int chunkZ) {
        double distSq = chunkDistanceSq(camera, chunkX, chunkZ);
        double maxDist = RENDER_DIST * CHUNK_SIZE;
        if (distSq > maxDist * maxDist) return false;
        return true;
    }

    private double chunkDistanceSq(FreeCamera3D camera, int chunkX, int chunkZ) {
        double chunkWorldX = chunkX * CHUNK_SIZE + CHUNK_SIZE / 2.0;
        double chunkWorldZ = chunkZ * CHUNK_SIZE + CHUNK_SIZE / 2.0;
        double dx = chunkWorldX - camera.targetX();
        double dz = chunkWorldZ - camera.targetZ();
        return dx * dx + dz * dz;
    }

    private int chunkDistanceChunks(FreeCamera3D camera, int chunkX, int chunkZ) {
        int centerChunkX = (int) Math.floor(camera.targetX() / CHUNK_SIZE);
        int centerChunkZ = (int) Math.floor(camera.targetZ() / CHUNK_SIZE);
        return Math.max(Math.abs(chunkX - centerChunkX), Math.abs(chunkZ - centerChunkZ));
    }

    private int sampleStep(FreeCamera3D camera, ChunkData chunk) {
        int chunkDistance = chunkDistanceChunks(camera, chunk.chunkX, chunk.chunkZ);
        if (chunkDistance <= 5) return 1;
        if (chunkDistance <= 10) return 2;
        if (chunkDistance <= 18) return 4;
        if (chunkDistance <= 36) return 8;
        return 16;
    }

    private int generationStepForChunk(int chunkX, int chunkZ) {
        int chunkDistance = Math.max(Math.abs(chunkX - lastCenterChunkX), Math.abs(chunkZ - lastCenterChunkZ));
        if (chunkDistance <= 5) return 1;
        if (chunkDistance <= 10) return 2;
        if (chunkDistance <= 18) return 4;
        if (chunkDistance <= 36) return 8;
        return 16;
    }

    private void maybeUpgradeChunkDetail(ChunkData chunk) {
        if (chunk.observedSnapshot != null) return;
        int desiredStep = generationStepForChunk(chunk.chunkX, chunk.chunkZ);
        if (desiredStep >= chunk.generatedStep) return;

        long key = ChunkPos.pack(chunk.chunkX, chunk.chunkZ);
        long seed = lastSeed;
        int dimension = lastDimension;
        WorkKey workKey = new WorkKey(seed, dimension, key);
        if (!inFlightChunks.add(workKey)) return;

        terrainExecutor.execute(() -> {
            try {
                ChunkData upgraded = generateChunkData(seed, dimension, chunk.chunkX, chunk.chunkZ, desiredStep);
                if (seed == lastSeed && dimension == lastDimension) {
                    chunkCache.put(key, upgraded);
                }
                } catch (Throwable ignored) {
                    failedChunkGenerations.incrementAndGet();
                    lastTerrainError = ignored.getClass().getSimpleName()
                        + (ignored.getMessage() == null ? "" : ": " + ignored.getMessage());
                } finally {
                    inFlightChunks.remove(workKey);
                }
        });
    }

    private void renderChunkMesh(GeoSink builder, GeoSink fluidBuilder, ChunkData chunk, int dimension, int step,
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

                double wx = baseX + dx;
                double wz = baseZ + dz;
                int width = Math.min(step, CHUNK_SIZE - dx);
                int depth = Math.min(step, CHUNK_SIZE - dz);
                Color tint = debugTint(atlas, state, false, blockColor(state), textureDebugView);
                TextureRegion region = atlas.region(state);
                if (region == null) continue;

                if (step <= 2) {
                    renderSurfaceCube(builder, atlas, wx, y - 1, wz, width, depth, state, textureDebugView);
                    if (dimension != 1) {
                        renderHeightFaceX(builder, atlas, chunk, dx, dz, step, baseX, baseZ, wx, wz, width, depth, textureDebugView);
                        renderHeightFaceZ(builder, atlas, chunk, dx, dz, step, baseX, baseZ, wx, wz, width, depth, textureDebugView);
                    }
                } else {
                    builder.ensureQuadCapacity();
                    builder.quad(
                        builder.vec3(wx, y, wz).vec2(region.x1, region.y1).color(tint).next(),
                        builder.vec3(wx + width, y, wz).vec2(region.x2, region.y1).color(tint).next(),
                        builder.vec3(wx + width, y, wz + depth).vec2(region.x2, region.y2).color(tint).next(),
                        builder.vec3(wx, y, wz + depth).vec2(region.x1, region.y2).color(tint).next()
                    );
                }

                BlockState fluidState = chunk.fluidStates[index];
                int fluidY = chunk.fluidHeights[index];
                if (fluidState != null && fluidY > y) {
                    TextureRegion fluidRegion = atlas.region(fluidState);
                    if (fluidRegion != null) {
                        Color fluidTint = debugTint(atlas, fluidState, false, blockColor(fluidState), textureDebugView);
                        Color transparentFluid = new Color(fluidTint.r, fluidTint.g, fluidTint.b, 150);
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

    private void renderStatusOverlay(String title, String detail) {
        Minecraft mc = Minecraft.getInstance();
        double screenWidth = mc.getWindow().getWidth();
        double screenHeight = mc.getWindow().getHeight();
        TextRenderer text = TextRenderer.get();
        double titleWidth = text.getWidth(title);
        double detailWidth = text.getWidth(detail);
        double width = Math.max(titleWidth, detailWidth) + 36;
        double x = (screenWidth - width) / 2.0;
        double y = screenHeight / 2.0 - 24;

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(x, y, width, 48, new Color(8, 12, 16, 210));
        r.boxLines(x, y, width, 48, new Color(80, 120, 130, 220));
        r.render();

        text.begin(1.0, false, true);
        text.render(title, x + (width - titleWidth) / 2.0, y + 10, new Color(235, 240, 245));
        text.render(detail, x + (width - detailWidth) / 2.0, y + 28,
            failedChunkGenerations.get() > 0 ? new Color(245, 180, 120) : new Color(165, 178, 188));
        text.end();
    }

    private void renderHeightFaceX(GeoSink builder, BlockTextureAtlas atlas, ChunkData chunk,
                                   int dx, int dz, int step, int baseX, int baseZ,
                                   double wx, double wz, int width, int depth, boolean textureDebugView) {
        int nx = dx + step;
        if (nx >= CHUNK_SIZE) return;
        int index = dz * CHUNK_SIZE + dx;
        int neighborIndex = dz * CHUNK_SIZE + nx;
        renderVerticalHeightFace(builder, atlas,
            baseX + nx, wz, baseX + nx, wz + depth,
            chunk.heights[index], chunk.states[index],
            chunk.heights[neighborIndex], chunk.states[neighborIndex], textureDebugView);
    }

    private void renderHeightFaceZ(GeoSink builder, BlockTextureAtlas atlas, ChunkData chunk,
                                   int dx, int dz, int step, int baseX, int baseZ,
                                   double wx, double wz, int width, int depth, boolean textureDebugView) {
        int nz = dz + step;
        if (nz >= CHUNK_SIZE) return;
        int index = dz * CHUNK_SIZE + dx;
        int neighborIndex = nz * CHUNK_SIZE + dx;
        renderVerticalHeightFace(builder, atlas,
            wx, baseZ + nz, wx + width, baseZ + nz,
            chunk.heights[index], chunk.states[index],
            chunk.heights[neighborIndex], chunk.states[neighborIndex], textureDebugView);
    }

    private void renderVerticalHeightFace(GeoSink builder, BlockTextureAtlas atlas,
                                          double x1, double z1, double x2, double z2,
                                          int heightA, BlockState stateA,
                                          int heightB, BlockState stateB, boolean textureDebugView) {
        int diff = Math.abs(heightA - heightB);
        if (diff <= 1) return;

        int top = Math.max(heightA, heightB);
        int bottom = Math.max(Math.min(heightA, heightB), top - MAX_SIDE_FACE_HEIGHT);
        BlockState state = heightA >= heightB ? stateA : stateB;
        if (state == null || state.isAir()) return;

        TextureRegion region = atlas.sideRegion(state);
        if (region == null) return;
        Color tint = debugTint(atlas, state, true, blockColor(state), textureDebugView);
        Color sideTint = new Color((int) (tint.r * 0.72), (int) (tint.g * 0.72), (int) (tint.b * 0.72), tint.a);

        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x1, bottom, z1).vec2(region.x1, region.y2).color(sideTint).next(),
            builder.vec3(x2, bottom, z2).vec2(region.x2, region.y2).color(sideTint).next(),
            builder.vec3(x2, top, z2).vec2(region.x2, region.y1).color(sideTint).next(),
            builder.vec3(x1, top, z1).vec2(region.x1, region.y1).color(sideTint).next()
        );
    }

    private void renderSurfaceCube(GeoSink builder, BlockTextureAtlas atlas,
                                   double x, double y, double z, int width, int depth, BlockState state,
                                   boolean textureDebugView) {
        TextureRegion topRegion = atlas.region(state);
        TextureRegion sideRegion = atlas.sideRegion(state);
        if (topRegion == null || sideRegion == null) return;
        Color tint = debugTint(atlas, state, false, blockColor(state), textureDebugView);
        Color sideBase = debugTint(atlas, state, true, blockColor(state), textureDebugView);
        Color sideTint = new Color((int) (sideBase.r * 0.68), (int) (sideBase.g * 0.68), (int) (sideBase.b * 0.68), sideBase.a);
        double x2 = x + width;
        double y2 = y + 1;
        double z2 = z + depth;

        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x, y2, z).vec2(topRegion.x1, topRegion.y1).color(tint).next(),
            builder.vec3(x2, y2, z).vec2(topRegion.x2, topRegion.y1).color(tint).next(),
            builder.vec3(x2, y2, z2).vec2(topRegion.x2, topRegion.y2).color(tint).next(),
            builder.vec3(x, y2, z2).vec2(topRegion.x1, topRegion.y2).color(tint).next()
        );
        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x, y, z).vec2(sideRegion.x1, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x2, y, z).vec2(sideRegion.x2, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x2, y2, z).vec2(sideRegion.x2, sideRegion.y1).color(sideTint).next(),
            builder.vec3(x, y2, z).vec2(sideRegion.x1, sideRegion.y1).color(sideTint).next()
        );
        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x2, y, z).vec2(sideRegion.x1, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x2, y, z2).vec2(sideRegion.x2, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x2, y2, z2).vec2(sideRegion.x2, sideRegion.y1).color(sideTint).next(),
            builder.vec3(x2, y2, z).vec2(sideRegion.x1, sideRegion.y1).color(sideTint).next()
        );
        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x2, y, z2).vec2(sideRegion.x1, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x, y, z2).vec2(sideRegion.x2, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x, y2, z2).vec2(sideRegion.x2, sideRegion.y1).color(sideTint).next(),
            builder.vec3(x2, y2, z2).vec2(sideRegion.x1, sideRegion.y1).color(sideTint).next()
        );
        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x, y, z2).vec2(sideRegion.x1, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x, y, z).vec2(sideRegion.x2, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x, y2, z).vec2(sideRegion.x2, sideRegion.y1).color(sideTint).next(),
            builder.vec3(x, y2, z2).vec2(sideRegion.x1, sideRegion.y1).color(sideTint).next()
        );
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
        Renderer2D r = Renderer2D.COLOR;
        for (ScreenIcon icon : icons) {
            GeneratedStructure s = icon.structure;
            Color color = StructureColors.get(s.type);
            double size = 20.0;
            double half = size / 2.0;
            r.begin();
            r.quad(icon.sx - half + 1, icon.sy - half + 2, size, size, new Color(0, 0, 0, 100));
            r.render();
            Texture tex = StructureIcons.get(s);
            if (tex != null) {
                Color tint = new Color(255, 255, 255, color.a);
                double inset = 1.5;
                Renderer2D texR = Renderer2D.TEXTURE;
                texR.begin();
                texR.texQuad(icon.sx - half + inset, icon.sy - half + inset, size - inset * 2, size - inset * 2, tint);
                texR.render(tex.getTextureView(), tex.getSampler());
            } else {
                r.begin();
                r.triangle(icon.sx, icon.sy - half, icon.sx - half, icon.sy, icon.sx, icon.sy + half, color);
                r.triangle(icon.sx, icon.sy - half, icon.sx + half, icon.sy, icon.sx, icon.sy + half, color);
                r.render();
            }
            r.begin();
            r.quad(icon.sx - half, icon.sy - half, size, size, new Color(9, 12, 17, 120));
            r.boxLines(icon.sx - half, icon.sy - half, size, size, new Color(255, 255, 255, 180));
            r.render();
            if (s == hovered) {
                String label = s.displayName();
                TextRenderer text = TextRenderer.get();
                double tw = text.getWidth(label);
                double tx = icon.sx - tw / 2.0;
                double ty = icon.sy - half - 12;
                r.begin();
                r.quad(tx - 2, ty - 1, tw + 4, 10, new Color(0, 0, 0, 160));
                r.render();
                text.begin(1.0, false, true);
                text.render(label, tx, ty, color);
                text.end();
            }
        }
    }

    private void renderWaypointIcons(FreeCamera3D camera, List<SeedWaypoint> waypoints,
                                      SeedWaypoint hovered, double centerX, double centerY, Minecraft mc) {
        if (waypoints == null || waypoints.isEmpty()) return;
        Renderer2D r = Renderer2D.COLOR;
        for (SeedWaypoint wp : waypoints) {
            double[] p = project(camera, wp.x, camera.targetY(), wp.z, centerX, centerY);
            if (!Double.isFinite(p[0]) || !Double.isFinite(p[1]) || p[2] < -1.0 || p[2] > 1.0) continue;
            double margin = 100;
            if (p[0] < -margin || p[0] > mc.getWindow().getWidth() + margin
                || p[1] < -margin || p[1] > mc.getWindow().getHeight() + margin) continue;
            boolean hovering = wp == hovered;
            Color color = hovering ? new Color(255, 255, 180, 255) : new Color(255, 255, 100, 220);
            double size = 12.0;
            double half = size / 2.0;
            r.begin();
            r.quad(p[0] - half, p[1] - half, size, size, color);
            r.quad(p[0] - half + 2, p[1] - 1, size - 4, 2, new Color(40, 40, 40, 180));
            r.quad(p[0] - 1, p[1] - half + 2, 2, size - 4, new Color(40, 40, 40, 180));
            r.render();
            if (hovering) {
                String name = wp.name;
                TextRenderer text = TextRenderer.get();
                double tw = text.getWidth(name);
                double tx = p[0] - tw / 2.0;
                double ty = p[1] - half - 12;
                r.begin();
                r.quad(tx - 2, ty - 1, tw + 4, 10, new Color(0, 0, 0, 160));
                r.render();
                text.begin(1.0, false, true);
                text.render(name, tx, ty, color);
                text.end();
            }
        }
    }

    public void renderChestMarkers(FreeCamera3D camera, List<ChestLootOutput> chests,
                                   ChestLootOutput selected, double centerX, double centerY,
                                   Minecraft mc) {
        if (chests == null || chests.isEmpty()) return;
        Renderer2D r = Renderer2D.COLOR;
        TextRenderer text = TextRenderer.get();
        for (ChestLootOutput chest : chests) {
            double[] p = project(camera, chest.blockX() + 0.5, chest.blockY() + 0.85, chest.blockZ() + 0.5, centerX, centerY);
            if (!Double.isFinite(p[0]) || !Double.isFinite(p[1]) || p[2] < -1.0 || p[2] > 1.0) continue;
            double margin = 80;
            if (p[0] < -margin || p[0] > mc.getWindow().getWidth() + margin
                || p[1] < -margin || p[1] > mc.getWindow().getHeight() + margin) continue;

            boolean active = chest == selected;
            double size = active ? 18 : 14;
            double half = size / 2.0;
            Color fill = active ? new Color(255, 219, 106, 245)
                : chest.exact() ? new Color(86, 215, 156, 230) : new Color(218, 172, 82, 225);
            Color edge = active ? Color.WHITE : new Color(15, 18, 22, 230);

            r.begin();
            r.quad(p[0] - half + 1, p[1] - half + 2, size, size, new Color(0, 0, 0, 120));
            r.quad(p[0] - half, p[1] - half, size, size, fill);
            r.boxLines(p[0] - half, p[1] - half, size, size, edge);
            r.quad(p[0] - half + 3, p[1] - 1, size - 6, 2, new Color(70, 45, 18, 190));
            r.render();

            if (active) {
                String label = chest.blockX() + ", " + chest.blockY() + ", " + chest.blockZ();
                double tw = text.getWidth(label);
                double tx = p[0] - tw / 2.0;
                double ty = p[1] - half - 13;
                r.begin();
                r.quad(tx - 3, ty - 1, tw + 6, 10, new Color(0, 0, 0, 165));
                r.render();
                text.begin(1.0, false, true);
                text.render(label, tx, ty, fill);
                text.end();
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
            case MINESHAFT -> 20.0;
            case SHIPWRECK, OCEAN_RUIN, RUINED_PORTAL, TRAIL_RUINS -> 18.0;
            case DESERT_PYRAMID, JUNGLE_TEMPLE, WITCH_HUT, IGLOO, OUTPOST -> 45.0;
            case VILLAGE -> 85.0;
            case MONUMENT, MANSION, ANCIENT_CITY, TRIAL_CHAMBER, STRONGHOLD,
                 FORTRESS, BASTION, END_CITY -> 160.0;
            default -> 30.0;
        };
    }

    private double estimatedStructureY(FreeCamera3D camera, GeneratedStructure structure) {
        StructurePreviewModel model = previewCache.get(previewKey(lastSeed, lastDimension, structure));
        if (model != null && !model.isEmpty()) {
            return model.maxY() + 6;
        }
        long key = ChunkPos.pack(Math.floorDiv(structure.x, CHUNK_SIZE), Math.floorDiv(structure.z, CHUNK_SIZE));
        ChunkData chunk = chunkCache.get(key);
        if (chunk == null) return camera.targetY();
        int dx = Math.floorMod(structure.x, CHUNK_SIZE);
        int dz = Math.floorMod(structure.z, CHUNK_SIZE);
        int index = dz * CHUNK_SIZE + dx;
        if (index < 0 || index >= chunk.heights.length) return camera.targetY();
        int y = chunk.heights[index];
        return y == 0 ? camera.targetY() : y + 8;
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
                                         long seed, int dimension, boolean undergroundView,
                                         boolean textureDebugView) {
        if (structures == null || structures.isEmpty()) return;

        List<GeneratedStructure> nearby = new ArrayList<>();
        for (GeneratedStructure structure : structures) {
            if (structure.type.dimension != dimension) continue;
            double dx = structure.x - camera.targetX();
            double dz = structure.z - camera.targetZ();
            double distSq = dx * dx + dz * dz;
            if (structure == hoveredStructure || distSq <= PREVIEW_RENDER_RADIUS_BLOCKS * PREVIEW_RENDER_RADIUS_BLOCKS) {
                nearby.add(structure);
            }
        }
        nearby.sort((a, b) -> {
            if (a == hoveredStructure) return -1;
            if (b == hoveredStructure) return 1;
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
            int step = previewStep(camera, structure);
            for (StructurePreviewModel.PreviewBlock block : model.blocks()) {
                if (renderedBlocks >= MAX_STRUCTURE_PREVIEW_BLOCKS_PER_FRAME) return;
                if (step > 1 && (Math.floorMod(block.x(), step) != 0 || Math.floorMod(block.z(), step) != 0)) continue;
                if (!undergroundView && isPreviewBlockBuried(block, step)) continue;
                BlockState state = block.state();
                if (state == null || state.isAir()) continue;
                renderPreviewCube(builder, atlas, block.x(), block.y(), block.z(), state, step, textureDebugView);
                renderedBlocks++;
            }
        }
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
        for (GeneratedStructure structure : nearby) {
            if (submitted >= PREVIEW_SUBMISSIONS_PER_FRAME) return;
            PreviewKey key = previewKey(seed, dimension, structure);
            if (previewCache.containsKey(key) || !inFlightPreviews.add(key)) continue;
            submitted++;
            terrainExecutor.execute(() -> {
                try {
                    StructurePreviewModel model = StructurePreviewSimulator.preview(structure, seed);
                    if (seed == lastSeed && dimension == lastDimension) {
                        previewCache.put(key, model);
                    }
                } catch (Throwable ignored) {
                    previewCache.put(key, new StructurePreviewModel(List.of(), 0, 0, 0, 0, 0, 0, false));
                } finally {
                    inFlightPreviews.remove(key);
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
                                   int x, int y, int z, BlockState state, int step, boolean textureDebugView) {
        TextureRegion topRegion = atlas.region(state);
        TextureRegion sideRegion = atlas.sideRegion(state);
        if (topRegion == null || sideRegion == null) return;
        Color tint = debugTint(atlas, state, false, blockColor(state), textureDebugView);
        Color sideBase = debugTint(atlas, state, true, blockColor(state), textureDebugView);
        Color sideTint = new Color((int) (sideBase.r * 0.74), (int) (sideBase.g * 0.74), (int) (sideBase.b * 0.74), sideBase.a);
        double size = Math.max(1, step);
        double x2 = x + size;
        double y2 = y + size;
        double z2 = z + size;

        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x, y2, z).vec2(topRegion.x1, topRegion.y1).color(tint).next(),
            builder.vec3(x2, y2, z).vec2(topRegion.x2, topRegion.y1).color(tint).next(),
            builder.vec3(x2, y2, z2).vec2(topRegion.x2, topRegion.y2).color(tint).next(),
            builder.vec3(x, y2, z2).vec2(topRegion.x1, topRegion.y2).color(tint).next()
        );
        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x, y, z).vec2(sideRegion.x1, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x2, y, z).vec2(sideRegion.x2, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x2, y2, z).vec2(sideRegion.x2, sideRegion.y1).color(sideTint).next(),
            builder.vec3(x, y2, z).vec2(sideRegion.x1, sideRegion.y1).color(sideTint).next()
        );
        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x2, y, z).vec2(sideRegion.x1, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x2, y, z2).vec2(sideRegion.x2, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x2, y2, z2).vec2(sideRegion.x2, sideRegion.y1).color(sideTint).next(),
            builder.vec3(x2, y2, z).vec2(sideRegion.x1, sideRegion.y1).color(sideTint).next()
        );
        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x2, y, z2).vec2(sideRegion.x1, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x, y, z2).vec2(sideRegion.x2, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x, y2, z2).vec2(sideRegion.x2, sideRegion.y1).color(sideTint).next(),
            builder.vec3(x2, y2, z2).vec2(sideRegion.x1, sideRegion.y1).color(sideTint).next()
        );
        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x, y, z2).vec2(sideRegion.x1, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x, y, z).vec2(sideRegion.x2, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x, y2, z).vec2(sideRegion.x2, sideRegion.y1).color(sideTint).next(),
            builder.vec3(x, y2, z2).vec2(sideRegion.x1, sideRegion.y1).color(sideTint).next()
        );
    }

    private PreviewKey previewKey(long seed, int dimension, GeneratedStructure structure) {
        return new PreviewKey(seed, dimension, structure.type, structure.startChunkX, structure.startChunkZ,
            structure.variant, structure.hasShip);
    }

    private static Color blockColor(BlockState state) {
        if (state.is(Blocks.GRASS_BLOCK)) return new Color(107, 142, 35);
        if (state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)) return new Color(210, 180, 140);
        if (state.is(Blocks.STONE) || state.is(Blocks.ANDESITE)
            || state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE))
            return new Color(128, 128, 128);
        if (state.is(Blocks.WATER)) return new Color(65, 105, 225);
        if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)
            || state.is(Blocks.POWDER_SNOW)) return new Color(210, 224, 235);
        if (state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
            || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.PODZOL))
            return new Color(139, 119, 69);
        if (state.is(Blocks.GRAVEL)) return new Color(136, 131, 122);
        if (state.is(Blocks.OAK_LOG) || state.is(Blocks.BIRCH_LOG)
            || state.is(Blocks.SPRUCE_LOG) || state.is(Blocks.DARK_OAK_LOG)
            || state.is(Blocks.JUNGLE_LOG) || state.is(Blocks.ACACIA_LOG)
            || state.is(Blocks.CHERRY_LOG))
            return new Color(101, 67, 33);
        if (state.is(Blocks.OAK_LEAVES) || state.is(Blocks.BIRCH_LEAVES)
            || state.is(Blocks.SPRUCE_LEAVES) || state.is(Blocks.DARK_OAK_LEAVES)
            || state.is(Blocks.JUNGLE_LEAVES) || state.is(Blocks.ACACIA_LEAVES)
            || state.is(Blocks.CHERRY_LEAVES))
            return new Color(34, 85, 34);
        if (state.is(Blocks.COBBLESTONE) || state.is(Blocks.MOSSY_COBBLESTONE))
            return new Color(115, 115, 115);
        if (state.is(Blocks.OAK_PLANKS) || state.is(Blocks.BIRCH_PLANKS)
            || state.is(Blocks.SPRUCE_PLANKS))
            return new Color(175, 150, 100);
        if (state.is(Blocks.CLAY)) return new Color(160, 164, 180);
        if (state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)
            || state.is(Blocks.BLUE_ICE)) return new Color(180, 220, 255);
        if (state.is(Blocks.MUD)) return new Color(80, 60, 40);
        if (state.is(Blocks.MYCELIUM)) return new Color(130, 110, 130);
        if (state.is(Blocks.END_STONE)) return new Color(218, 209, 142);
        if (state.is(Blocks.END_STONE_BRICKS)) return new Color(205, 197, 134);
        if (state.is(Blocks.PURPUR_BLOCK) || state.is(Blocks.PURPUR_PILLAR)
            || state.is(Blocks.PURPUR_STAIRS) || state.is(Blocks.PURPUR_SLAB))
            return new Color(174, 122, 190);
        if (state.is(Blocks.OBSIDIAN)) return new Color(38, 26, 56);
        if (state.is(Blocks.END_ROD)) return new Color(230, 222, 206);
        if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().contains("shulker_box")) {
            return new Color(150, 95, 170);
        }
        return new Color(150, 154, 158);
    }

    private SurfaceSample resolvePredictedSurface(long seed, int dimension, WorldgenEngine.TerrainAccessor terrain,
                                                  int wx, int wz, int topY) {
        int y = topY - 1;
        BlockState top = terrain.block(wx, y, wz);
        if (top == null || top.isAir()) {
            return new SurfaceSample(topY, y, Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState());
        }

        if (isFluid(top) || isIce(top)) {
            SolidSample solid = findPredictedSolidBelow(terrain, wx, wz, y - 1);
            return new SurfaceSample(topY, solid.y(), top, normalizePredictedSurfaceState(seed, dimension, wx, wz, solid.state(), solid.y(), top, true));
        }

        if (isIgnorableSurface(top)) {
            SolidSample solid = findPredictedSolidBelow(terrain, wx, wz, y - 1);
            BlockState normalized = normalizePredictedSurfaceState(seed, dimension, wx, wz, solid.state(), solid.y(), top, false);
            return new SurfaceSample(solid.y() + 1, solid.y(), normalized, normalized);
        }

        BlockState normalized = normalizePredictedSurfaceState(seed, dimension, wx, wz, top, y, null, false);
        return new SurfaceSample(y + 1, y, normalized, normalized);
    }

    private SurfaceSample resolveObservedSurface(LiveChunkSnapshot snapshot, int dx, int dz, int topY) {
        int y = topY - 1;
        BlockState top = snapshot.block(dx, y, dz);
        if (top == null || top.isAir()) {
            return new SurfaceSample(topY, y, Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState());
        }

        if (isFluid(top) || isIce(top)) {
            SolidSample solid = findObservedSolidBelow(snapshot, dx, dz, y - 1);
            return new SurfaceSample(topY, solid.y(), top, normalizeSurfaceState(solid.state(), solid.y(), top, true));
        }

        if (isIgnorableSurface(top)) {
            SolidSample solid = findObservedSolidBelow(snapshot, dx, dz, y - 1);
            BlockState normalized = normalizeSurfaceState(solid.state(), solid.y(), top, false);
            return new SurfaceSample(solid.y() + 1, solid.y(), normalized, normalized);
        }

        BlockState normalized = normalizeSurfaceState(top, y, null, false);
        return new SurfaceSample(y + 1, y, normalized, normalized);
    }

    private SolidSample findPredictedSolidBelow(WorldgenEngine.TerrainAccessor terrain, int wx, int wz, int startY) {
        for (int y = startY; y > -64; y--) {
            BlockState state = terrain.block(wx, y, wz);
            if (state != null && !state.isAir() && !isFluid(state) && !isIgnorableSurface(state)) {
                return new SolidSample(y, state);
            }
        }
        return new SolidSample(startY, Blocks.STONE.defaultBlockState());
    }

    private SolidSample findObservedSolidBelow(LiveChunkSnapshot snapshot, int dx, int dz, int startY) {
        for (int y = startY; y >= snapshot.minY(); y--) {
            BlockState state = snapshot.block(dx, y, dz);
            if (state != null && !state.isAir() && !isFluid(state) && !isIgnorableSurface(state)) {
                return new SolidSample(y, state);
            }
        }
        return new SolidSample(startY, Blocks.STONE.defaultBlockState());
    }

    private static boolean isIgnorableSurface(BlockState state) {
        return state != null && (state.is(Blocks.SNOW)
            || state.is(BlockTags.LEAVES)
            || state.is(Blocks.SHORT_GRASS)
            || state.is(Blocks.TALL_GRASS)
            || state.is(Blocks.FERN)
            || state.is(Blocks.LARGE_FERN)
            || state.is(Blocks.VINE)
            || state.is(Blocks.SEAGRASS)
            || state.is(Blocks.TALL_SEAGRASS)
            || state.is(Blocks.KELP)
            || state.is(Blocks.KELP_PLANT)
            || state.is(BlockTags.LOGS)
            || isWoodLike(state)
            || BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().contains("mushroom"));
    }

    private static boolean isWoodLike(BlockState state) {
        if (state == null) return false;
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return id.contains("_planks") || id.contains("_wood") || id.contains("_stem")
            || id.contains("_hyphae") || id.contains("mushroom_stem");
    }

    private static BlockState normalizeSurfaceState(BlockState state, int y, BlockState cover, boolean underFluid) {
        if (state == null || state.isAir()) return Blocks.STONE.defaultBlockState();
        if (state.is(BlockTags.LEAVES)) return Blocks.GRASS_BLOCK.defaultBlockState();
        if (cover != null && (cover.is(Blocks.SNOW) || cover.is(Blocks.SNOW_BLOCK))) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        if (underFluid && (cover != null && (cover.is(Blocks.ICE) || cover.is(Blocks.PACKED_ICE) || cover.is(Blocks.BLUE_ICE)))) {
            return Blocks.WATER.defaultBlockState();
        }
        if (state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT)) {
            return y >= 58 && !underFluid ? Blocks.GRASS_BLOCK.defaultBlockState() : state;
        }
        return state;
    }

    private static BlockState normalizePredictedSurfaceState(long seed, int dimension, int wx, int wz,
                                                             BlockState state, int y, BlockState cover,
                                                             boolean underFluid) {
        BlockState normalized = normalizeSurfaceState(state, y, cover, underFluid);
        if (dimension != 0 || underFluid) return normalized;

        String biome = WorldgenEngine.getBiome(seed, dimension, wx, Math.max(y, 64), wz).id();
        if (biome == null) return normalized;
        if (biome.contains("ocean") || biome.contains("river")) return normalized;
        if (biome.contains("desert") || biome.contains("beach")) return Blocks.SAND.defaultBlockState();
        if (biome.contains("badlands")) return Blocks.RED_SAND.defaultBlockState();
        if (biome.contains("snow") || biome.contains("frozen") || biome.contains("ice_spikes")
            || biome.contains("grove") || biome.contains("jagged_peaks")) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        if (normalized.is(Blocks.STONE) || normalized.is(Blocks.DEEPSLATE)
            || normalized.is(Blocks.DIRT) || normalized.is(Blocks.COARSE_DIRT)
            || normalized.is(Blocks.ROOTED_DIRT)) {
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }
        return normalized;
    }

    /**
     * Renders stacked horizontal slices at Y = 0, 16, 32, ... up to the surface,
     * each a textured quad sampled at that Y. Deeper slices get lower alpha so
     * caves and underground structure footprints show through the surface.
     * AIR slices are skipped. The shared depth-tested textured pipeline sorts the
     * layers; alpha only controls translucency.
     */
    private void renderUndergroundLayers(GeoSink builder, ChunkData chunk,
                                         WorldgenEngine.TerrainAccessor terrain, int step) {
        step = Math.max(step, chunk.generatedStep);
        int baseX = chunk.chunkX * CHUNK_SIZE;
        int baseZ = chunk.chunkZ * CHUNK_SIZE;
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        for (int dx = 0; dx < CHUNK_SIZE; dx += step) {
            for (int dz = 0; dz < CHUNK_SIZE; dz += step) {
                int index = dz * CHUNK_SIZE + dx;
                int surfaceY = chunk.heights[index];
                int wx = baseX + dx;
                int wz = baseZ + dz;
                int width = Math.min(step, CHUNK_SIZE - dx);
                int depth = Math.min(step, CHUNK_SIZE - dz);

                for (int layerY = 0; layerY < surfaceY; layerY += LAYER_STEP) {
                    BlockState state = chunk.observedSnapshot != null
                        ? chunk.observedSnapshot.block(dx, layerY, dz)
                        : terrain.block(wx, layerY, wz);
                    if (state == null || state.isAir()) continue;
                    if (state.is(Blocks.WATER) || state.is(Blocks.LAVA)) continue;
                    Color tint = blockColor(state);
                    TextureRegion region = atlas.region(state);
                    if (region == null) continue;

                    // Alpha falls off with depth below the surface; floor at 40/255.
                    int depthBelow = Math.max(0, surfaceY - layerY);
                    int alpha = Math.max(40, 255 - depthBelow * 3);
                    Color layerTint = new Color(tint.r, tint.g, tint.b, alpha);

                    builder.ensureQuadCapacity();
                    builder.quad(
                        builder.vec3(wx, layerY, wz).vec2(region.x1, region.y1).color(layerTint).next(),
                        builder.vec3(wx + width, layerY, wz).vec2(region.x2, region.y1).color(layerTint).next(),
                        builder.vec3(wx + width, layerY, wz + depth).vec2(region.x2, region.y2).color(layerTint).next(),
                        builder.vec3(wx, layerY, wz + depth).vec2(region.x1, region.y2).color(layerTint).next()
                    );
                }
            }
        }
    }

    private int renderUndergroundCaveShells(GeoSink builder, ChunkData chunk,
                                            WorldgenEngine.TerrainAccessor terrain,
                                            int step, int remainingBudget, boolean textureDebugView) {
        if (remainingBudget <= 0 || terrain == null) return 0;
        int baseX = chunk.chunkX * CHUNK_SIZE;
        int baseZ = chunk.chunkZ * CHUNK_SIZE;
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        int rendered = 0;
        int yStep = Math.max(2, step);
        int footprint = Math.max(1, Math.min(step, 8));

        for (int dx = 0; dx < CHUNK_SIZE; dx += step) {
            for (int dz = 0; dz < CHUNK_SIZE; dz += step) {
                int index = dz * CHUNK_SIZE + dx;
                int surfaceY = chunk.heights[index];
                int maxY = Math.min(CAVE_MAX_Y, surfaceY - 2);
                if (maxY <= CAVE_MIN_Y) continue;

                int wx = baseX + dx;
                int wz = baseZ + dz;
                for (int y = CAVE_MIN_Y; y <= maxY; y += yStep) {
                    if (rendered >= remainingBudget) return rendered;
                    BlockState state = chunk.observedSnapshot != null
                        ? chunk.observedSnapshot.block(dx, y, dz)
                        : terrain.block(wx, y, wz);
                    if (state == null || state.isAir() || isFluid(state)) continue;
                    if (!isCaveExposed(terrain, chunk, dx, y, dz, wx, wz, yStep)) continue;

                    renderCaveVoxel(builder, atlas, wx, y, wz, state, footprint, yStep, textureDebugView);
                    rendered++;
                }
            }
        }
        return rendered;
    }

    private int renderEndIslandShells(GeoSink builder, ChunkData chunk,
                                      WorldgenEngine.TerrainAccessor terrain,
                                      int step, int remainingBudget, boolean textureDebugView) {
        if (remainingBudget <= 0 || terrain == null) return 0;
        int baseX = chunk.chunkX * CHUNK_SIZE;
        int baseZ = chunk.chunkZ * CHUNK_SIZE;
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        int rendered = 0;
        int yStep = Math.max(2, step);
        int footprint = Math.max(1, Math.min(step, 8));

        for (int dx = 0; dx < CHUNK_SIZE; dx += step) {
            for (int dz = 0; dz < CHUNK_SIZE; dz += step) {
                int wx = baseX + dx;
                int wz = baseZ + dz;
                for (int y = END_SHELL_MIN_Y; y <= END_SHELL_MAX_Y; y += yStep) {
                    if (rendered >= remainingBudget) return rendered;
                    BlockState state = chunk.observedSnapshot != null
                        ? chunk.observedSnapshot.block(dx, y, dz)
                        : terrain.block(wx, y, wz);
                    if (state == null || state.isAir() || isFluid(state)) continue;
                    if (!isCaveExposed(terrain, chunk, dx, y, dz, wx, wz, yStep)) continue;

                    renderCaveVoxel(builder, atlas, wx, y, wz, state, footprint, yStep, textureDebugView);
                    rendered++;
                }
            }
        }
        return rendered;
    }

    private boolean isCaveExposed(WorldgenEngine.TerrainAccessor terrain, ChunkData chunk,
                                  int localX, int y, int localZ, int worldX, int worldZ, int sample) {
        return isAirLike(sampleBlock(terrain, chunk, localX + sample, y, localZ, worldX + sample, worldZ))
            || isAirLike(sampleBlock(terrain, chunk, localX - sample, y, localZ, worldX - sample, worldZ))
            || isAirLike(sampleBlock(terrain, chunk, localX, y + sample, localZ, worldX, worldZ))
            || isAirLike(sampleBlock(terrain, chunk, localX, y - sample, localZ, worldX, worldZ))
            || isAirLike(sampleBlock(terrain, chunk, localX, y, localZ + sample, worldX, worldZ + sample))
            || isAirLike(sampleBlock(terrain, chunk, localX, y, localZ - sample, worldX, worldZ - sample));
    }

    private BlockState sampleBlock(WorldgenEngine.TerrainAccessor terrain, ChunkData chunk,
                                   int localX, int y, int localZ, int worldX, int worldZ) {
        if (chunk.observedSnapshot != null
            && localX >= 0 && localX < CHUNK_SIZE && localZ >= 0 && localZ < CHUNK_SIZE) {
            return chunk.observedSnapshot.block(localX, y, localZ);
        }
        return terrain.block(worldX, y, worldZ);
    }

    private boolean isAirLike(BlockState state) {
        return state == null || state.isAir() || isFluid(state);
    }

    private void renderCaveVoxel(GeoSink builder, BlockTextureAtlas atlas,
                                 int x, int y, int z, BlockState state, int width, int height,
                                 boolean textureDebugView) {
        TextureRegion topRegion = atlas.region(state);
        TextureRegion sideRegion = atlas.sideRegion(state);
        if (topRegion == null || sideRegion == null) return;
        Color tint = debugTint(atlas, state, false, blockColor(state), textureDebugView);
        Color sideBase = debugTint(atlas, state, true, blockColor(state), textureDebugView);
        Color topTint = new Color((int) (tint.r * 0.86), (int) (tint.g * 0.86), (int) (tint.b * 0.86), 245);
        Color sideTint = new Color((int) (sideBase.r * 0.58), (int) (sideBase.g * 0.58), (int) (sideBase.b * 0.58), 245);
        double x2 = x + width;
        double y2 = y + height;
        double z2 = z + width;

        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x, y2, z).vec2(topRegion.x1, topRegion.y1).color(topTint).next(),
            builder.vec3(x2, y2, z).vec2(topRegion.x2, topRegion.y1).color(topTint).next(),
            builder.vec3(x2, y2, z2).vec2(topRegion.x2, topRegion.y2).color(topTint).next(),
            builder.vec3(x, y2, z2).vec2(topRegion.x1, topRegion.y2).color(topTint).next()
        );
        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x, y, z).vec2(sideRegion.x1, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x2, y, z).vec2(sideRegion.x2, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x2, y2, z).vec2(sideRegion.x2, sideRegion.y1).color(sideTint).next(),
            builder.vec3(x, y2, z).vec2(sideRegion.x1, sideRegion.y1).color(sideTint).next()
        );
        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x2, y, z).vec2(sideRegion.x1, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x2, y, z2).vec2(sideRegion.x2, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x2, y2, z2).vec2(sideRegion.x2, sideRegion.y1).color(sideTint).next(),
            builder.vec3(x2, y2, z).vec2(sideRegion.x1, sideRegion.y1).color(sideTint).next()
        );
        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x2, y, z2).vec2(sideRegion.x1, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x, y, z2).vec2(sideRegion.x2, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x, y2, z2).vec2(sideRegion.x2, sideRegion.y1).color(sideTint).next(),
            builder.vec3(x2, y2, z2).vec2(sideRegion.x1, sideRegion.y1).color(sideTint).next()
        );
        builder.ensureQuadCapacity();
        builder.quad(
            builder.vec3(x, y, z2).vec2(sideRegion.x1, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x, y, z).vec2(sideRegion.x2, sideRegion.y2).color(sideTint).next(),
            builder.vec3(x, y2, z).vec2(sideRegion.x2, sideRegion.y1).color(sideTint).next(),
            builder.vec3(x, y2, z2).vec2(sideRegion.x1, sideRegion.y1).color(sideTint).next()
        );
    }

    private Color debugTint(BlockTextureAtlas atlas, BlockState state, boolean sideFace, Color normal, boolean textureDebugView) {
        if (!textureDebugView) return normal;
        boolean fallback = sideFace ? atlas.sideUsesFallback(state) : atlas.usesFallback(state);
        if (!fallback) return normal;
        return sideFace ? new Color(255, 135, 32, normal.a) : new Color(255, 52, 214, normal.a);
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

    private record ChunkData(int chunkX, int chunkZ, int[] heights, BlockState[] states,
                             int[] fluidHeights, BlockState[] fluidStates,
                             LiveChunkSnapshot observedSnapshot, long observedAt, int generatedStep) {
    }

    private record WorkKey(long seed, int dimension, long chunkKey) {
    }

    private record VisibleChunk(ChunkData chunk, double distanceSq) {
    }

    private record PreviewKey(long seed, int dimension, StructureType type, int startChunkX, int startChunkZ,
                              String variant, boolean hasShip) {
    }

    private record ScreenIcon(GeneratedStructure structure, double sx, double sy, double screenZ) {
    }

    /**
     * A minimal geometry sink mirroring the subset of {@link MeshBuilder}'s fluent API
     * the terrain emitters use. Lets the same emission code either write straight into
     * a frame {@link MeshBuilder} ({@link MeshSink}) or bake into reusable arrays
     * ({@link BakingSink}) that are replayed cheaply every frame.
     */
    private interface GeoSink {
        GeoSink vec3(double x, double y, double z);
        GeoSink vec2(double u, double v);
        GeoSink color(Color c);
        int next();
        void quad(int i1, int i2, int i3, int i4);
        void ensureQuadCapacity();
    }

    /** Immediate sink: forwards directly to a live {@link MeshBuilder}. */
    private static final class MeshSink implements GeoSink {
        private final MeshBuilder builder;

        MeshSink(MeshBuilder builder) {
            this.builder = builder;
        }

        @Override public GeoSink vec3(double x, double y, double z) { builder.vec3(x, y, z); return this; }
        @Override public GeoSink vec2(double u, double v) { builder.vec2(u, v); return this; }
        @Override public GeoSink color(Color c) { builder.color(c); return this; }
        @Override public int next() { return builder.next(); }
        @Override public void quad(int i1, int i2, int i3, int i4) { builder.quad(i1, i2, i3, i4); }
        @Override public void ensureQuadCapacity() { builder.ensureQuadCapacity(); }
    }

    /**
     * Records the emitted vertex stream (position, UV, packed RGBA) and quad index
     * tuples into growable primitive arrays. Built once off the render thread; the
     * frame path replays it into the real {@link MeshBuilder} via {@link BakedChunk}.
     */
    private static final class BakingSink implements GeoSink {
        private float[] px = new float[256];
        private float[] py = new float[256];
        private float[] pz = new float[256];
        private float[] u = new float[256];
        private float[] v = new float[256];
        private int[] rgba = new int[256];
        private int vcount;

        private int[] quads = new int[384];
        private int qcount; // number of ints written (4 per quad)

        // Pending vertex attributes between vec3()/vec2()/color()/next().
        private float pxT, pyT, pzT, uT, vT;
        private int rgbaT;

        @Override public GeoSink vec3(double x, double y, double z) {
            pxT = (float) x; pyT = (float) y; pzT = (float) z; return this;
        }

        @Override public GeoSink vec2(double uu, double vv) {
            uT = (float) uu; vT = (float) vv; return this;
        }

        @Override public GeoSink color(Color c) {
            rgbaT = ((c.r & 0xFF) << 24) | ((c.g & 0xFF) << 16) | ((c.b & 0xFF) << 8) | (c.a & 0xFF);
            return this;
        }

        @Override public int next() {
            if (vcount == px.length) growVerts();
            px[vcount] = pxT; py[vcount] = pyT; pz[vcount] = pzT;
            u[vcount] = uT; v[vcount] = vT; rgba[vcount] = rgbaT;
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
        }

        BakedGeometry finish() {
            return new BakedGeometry(
                java.util.Arrays.copyOf(px, vcount),
                java.util.Arrays.copyOf(py, vcount),
                java.util.Arrays.copyOf(pz, vcount),
                java.util.Arrays.copyOf(u, vcount),
                java.util.Arrays.copyOf(v, vcount),
                java.util.Arrays.copyOf(rgba, vcount),
                java.util.Arrays.copyOf(quads, qcount));
        }
    }

    /** Immutable baked vertex/quad arrays for one mesh layer (solid or fluid). */
    private record BakedGeometry(float[] px, float[] py, float[] pz,
                                 float[] u, float[] v, int[] rgba, int[] quads) {
        boolean isEmpty() { return quads.length == 0; }

        /** Replays the recorded vertices and quads into a live frame mesh. */
        void replay(MeshBuilder mesh, Color scratch) {
            if (quads.length == 0) return;
            mesh.ensureCapacity(px.length, quads.length / 4 * 6);
            for (int i = 0; i < px.length; i++) {
                int c = rgba[i];
                scratch.r = (c >>> 24) & 0xFF;
                scratch.g = (c >>> 16) & 0xFF;
                scratch.b = (c >>> 8) & 0xFF;
                scratch.a = c & 0xFF;
                mesh.vec3(px[i], py[i], pz[i]).vec2(u[i], v[i]).color(scratch).next();
            }
            for (int q = 0; q < quads.length; q += 4) {
                mesh.quad(quads[q], quads[q + 1], quads[q + 2], quads[q + 3]);
            }
        }
    }

    /** Baked geometry for a chunk at a given detail step, plus the identity it was baked for. */
    private record BakedChunk(long seed, int dimension, int chunkX, int chunkZ, int step,
                              long observedAt, BakedGeometry solid, BakedGeometry fluid) {
    }
}
