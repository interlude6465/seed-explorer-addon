package me.seedexplorer.addon.render;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.LootSearchTerm;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructurePredictionStore;
import me.seedexplorer.addon.structures.StructureType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.shapes.Shapes;

/** Deterministic smoke probe for LOD, material filters, and disk cache policy. */
public final class TerrainRendererPolicyProbe {
    private TerrainRendererPolicyProbe() {
    }

    public static void main(String[] args) throws Exception {
        verifySharedRendererInitialization();
        verifyStandaloneAtlasTexture();
        verifyCameraZoom();
        verifyLod();
        verifyMaterials();
        verifyNaturalDecorationTintPolicy();
        verifyNaturalDecorationSchedulingPolicy();
        verifyDiskCache();
        verifyStreamPersistence();
        verifyStructurePersistence();
        System.out.println("Terrain renderer policy probe: PASS");
    }

    private static void verifyStandaloneAtlasTexture() throws Exception {
        Class<?> textureType = BlockTextureAtlas.class.getDeclaredField("texture").getType();
        require(textureType == GpuTextureResource.class,
            "terrain atlas regressed to an external renderer texture type");
    }

    private static void verifyNaturalDecorationTintPolicy() {
        require(Terrain3DRenderer.usesDryFoliageTintId("leaf_litter")
                && Terrain3DRenderer.usesDryFoliageTintId("minecraft:leaf_litter"),
            "leaf litter lost its vanilla dry-foliage tint channel");
        require(!Terrain3DRenderer.usesDryFoliageTintId("short_grass"),
            "ordinary grass was incorrectly assigned the dry-foliage tint channel");
        require(Terrain3DRenderer.requiresFoliageTint("minecraft:pale_oak_leaves")
                && Terrain3DRenderer.requiresFoliageTint("oak_leaves"),
            "grayscale oak-family leaves lost their biome foliage tint");
        require(!Terrain3DRenderer.requiresFoliageTint("minecraft:azalea_leaves")
                && !Terrain3DRenderer.requiresFoliageTint("minecraft:cherry_leaves"),
            "pre-colored leaves were incorrectly assigned a second biome tint");
        require(!TerrainMaterialPolicy.renderNaturalDecoration("minecraft:pale_oak_leaves")
                && !TerrainMaterialPolicy.renderNaturalDecoration("minecraft:leaf_litter")
                && TerrainMaterialPolicy.renderNaturalDecoration("minecraft:oak_leaves"),
            "excluded leaf materials can re-enter the map vegetation pass");
        require(BlockTextureAtlas.excludedTextureFile("pale_oak_leaves.png")
                && BlockTextureAtlas.excludedTextureFile("leaf_litter.png")
                && !BlockTextureAtlas.excludedTextureFile("oak_leaves.png"),
            "excluded leaf materials can re-enter the map texture atlas");
        require(Terrain3DRenderer.naturalDecorationsUseTranslucentPass(),
            "natural decorations can write rectangular transparent texels into the depth buffer");
    }

    private static void verifyNaturalDecorationSchedulingPolicy() {
        require(Terrain3DRenderer.withinNaturalFeatureRadius(10, -20, 22, -8),
            "natural decoration omitted a chunk inside the camera inspection window");
        require(!Terrain3DRenderer.withinNaturalFeatureRadius(10, -20, 23, -20),
            "natural decoration escaped the bounded camera inspection window");
        require(!Terrain3DRenderer.withinNaturalFeatureRadius(10, -20, 10, -33),
            "natural decoration escaped the bounded camera inspection window on Z");
    }

    private static void verifySharedRendererInitialization() {
        require(Terrain3DRenderer.shared() != null,
            "shared terrain renderer failed to initialize");
    }

    private static void verifyCameraZoom() {
        FreeCamera3D camera = new FreeCamera3D();
        for (int i = 0; i < 20; i++) camera.scroll(-1.0);
        require(camera.distance() > 500.0,
            "3D camera still has the old 500-block zoom cap");
        camera.scroll(-10_000.0);
        require(Double.isFinite(camera.distance()) && camera.distance() <= 1_000_000_000D,
            "3D camera zoom became numerically unstable");
        float[] projection = camera.getProjectionMatrix().get(new float[16]);
        for (float value : projection) {
            require(Float.isFinite(value), "dynamic far plane produced a non-finite projection");
        }

        camera.position(0.0, 80.0, 0.0);
        camera.pitch(Math.toRadians(90.0));
        camera.yaw(0.0);
        double beforeLookX = camera.eyeX();
        double beforeLookY = camera.eyeY();
        double beforeLookZ = camera.eyeZ();
        camera.mouseDrag(12.0, -4.0);
        require(camera.eyeX() == beforeLookX && camera.eyeY() == beforeLookY
                && camera.eyeZ() == beforeLookZ,
            "mouse look still moves the camera around an orbit anchor");

        camera.pitch(Math.toRadians(90.0));
        camera.yaw(0.0);
        camera.stopFlight();
        camera.position(0.0, 80.0, 0.0);
        camera.updateFlight(1.0, 0.0, 0.0, 0.1);
        double firstStep = -camera.eyeZ();
        for (int i = 0; i < 19; i++) camera.updateFlight(1.0, 0.0, 0.0, 0.1);
        double beforeCruise = camera.eyeZ();
        camera.updateFlight(1.0, 0.0, 0.0, 0.1);
        double cruiseStep = beforeCruise - camera.eyeZ();
        require(camera.eyeZ() < -20.0 && Math.abs(camera.eyeY() - 80.0) < 1.0e-6,
            "W does not fly forward along the horizontal look direction");
        require(cruiseStep > firstStep * 4.0,
            "held-key movement does not accelerate smoothly");

        camera.viewOf(10.0, 20.0, 30.0, 25.0, 72.0, 15.0);
        require(Math.abs(camera.eyeX() + camera.forwardX() * 25.0 - 10.0) < 1.0e-6
                && Math.abs(camera.eyeY() + camera.forwardY() * 25.0 - 20.0) < 1.0e-6
                && Math.abs(camera.eyeZ() + camera.forwardZ() * 25.0 - 30.0) < 1.0e-6,
            "structure orbit view is not centered on its actual focus point");
    }

    private static void verifyLod() {
        require(TerrainLodPolicy.sampleStep(0) == 1, "center must be full detail");
        require(TerrainLodPolicy.sampleStep(5_012) == 1,
            "10,025-wide exact square must include ring 5012");
        require(TerrainLodPolicy.sampleStep(5_013) == 8,
            "mid-quality ring must begin after the exact square");
        require(TerrainLodPolicy.sampleStep(15_012) == 8,
            "mid-quality boundary must include the next 10,000 chunks");
        require(TerrainLodPolicy.sampleStep(15_013) == 16,
            "outside the mid-quality boundary must be coarse");
        require(TerrainLodPolicy.interactiveSampleStep(4) == 1,
            "interactive camera ring lost exact terrain");
        require(TerrainLodPolicy.interactiveSampleStep(5) == 4
                && TerrainLodPolicy.interactiveSampleStep(16) == 4,
            "interactive near terrain does not use step 4");
        require(TerrainLodPolicy.interactiveSampleStep(17) == 8
                && TerrainLodPolicy.interactiveSampleStep(32) == 8,
            "interactive middle terrain does not use step 8");
        require(TerrainLodPolicy.interactiveSampleStep(33) == 16,
            "interactive far terrain is not bounded to coarse sampling");
        require(TerrainLodPolicy.coverageSampleStep(0) == 16
                && TerrainLodPolicy.coverageSampleStep(1_875_000) == 16,
            "leading coverage no longer advances at constant coarse cost");
        require(TerrainLodPolicy.sourceSampleStep(32, -32, 5_044, -32) == 1,
            "source quality is no longer anchored to the saved square origin");
        require(TerrainLodPolicy.sourceSampleStep(32, -32, 5_045, -32) == 8,
            "anchored exact square extends beyond its requested edge");
        require(TerrainLodPolicy.minimumSurfaceVertexReserve(1) == 16,
            "exact fallback does not reserve its four step-8 top quads");
        require(TerrainLodPolicy.minimumSurfaceVertexReserve(8) == 4,
            "coarse fallback does not reserve one top quad");
        require(TerrainLodPolicy.insideExactSquare(5_012, 5_012),
            "exact area must be a square, including its corner");
        require(TerrainLodPolicy.squareChunkCount(5_012) == 100_500_625L,
            "exact-square progress target does not represent 10,025 x 10,025 chunks");
        require(!TerrainLodPolicy.insideExactSquare(5_013, 0),
            "exact square extends beyond its requested half-width");
        int progressiveStep = 1;
        for (int expected : new int[]{2, 4, 8, 16}) {
            progressiveStep = TerrainLodPolicy.nextCoarserStep(progressiveStep);
            require(progressiveStep == expected,
                "surface fallback skipped progressive LOD step " + expected);
        }
        require(TerrainLodPolicy.nextCoarserStep(16) == 16,
            "coarsest surface fallback must remain stable");
        for (int surfaceStep : new int[]{1, 2, 4, 8, 16}) {
            require(TerrainLodPolicy.shouldBakeUndergroundGeometry(true, 0, surfaceStep),
                "underground bake was skipped at surface LOD step " + surfaceStep);
        }
        require(!TerrainLodPolicy.shouldBakeUndergroundGeometry(false, 0, 8),
            "surface-only bake unexpectedly generated underground geometry");
        require(!TerrainLodPolicy.shouldBakeUndergroundGeometry(true, 1, 1),
            "End terrain unexpectedly requested an underground cave shell");
        require(TerrainLodPolicy.shouldPrebakeUndergroundGeometry(0, 8, 8, true),
            "nearby exact Overworld terrain did not pre-bake its cave shell");
        require(TerrainLodPolicy.shouldPrebakeUndergroundGeometry(-1, 0, 8, true),
            "nearby exact Nether terrain did not pre-bake its cave shell");
        require(!TerrainLodPolicy.shouldPrebakeUndergroundGeometry(0, 9, 8, true),
            "terrain outside the cave window unexpectedly pre-baked cave geometry");
        require(!TerrainLodPolicy.shouldPrebakeUndergroundGeometry(0, 4, 8, false),
            "coarse surface terrain unexpectedly pre-baked cave geometry");
        require(!TerrainLodPolicy.shouldPrebakeUndergroundGeometry(1, 0, 8, true),
            "End terrain unexpectedly pre-baked cave geometry");
        require(TerrainLodPolicy.protectsPrebakedUndergroundGeometry(
                10, -10, 18, -2, 8, true),
            "active cave-window geometry was not protected from cache compaction");
        require(!TerrainLodPolicy.protectsPrebakedUndergroundGeometry(
                10, -10, 19, -10, 8, true),
            "distant cave geometry was incorrectly pinned in the cache");
        require(!TerrainLodPolicy.protectsPrebakedUndergroundGeometry(
                10, -10, 10, -10, 8, false),
            "surface-only geometry was incorrectly protected as a cave bake");
        require(!TerrainLodPolicy.bakeAffectsActiveView(true, false),
            "surface-only completion can still starve an underground GPU batch");
        require(TerrainLodPolicy.bakeAffectsActiveView(true, true),
            "completed cave geometry did not refresh its underground GPU batch");
        require(TerrainLodPolicy.bakeAffectsActiveView(false, false),
            "surface-only completion did not refresh the normal terrain view");
        require(TerrainLodPolicy.admitProgressiveGpuBatch(true, true, false),
            "same-view terrain cannot upload while a newer revision is queued");
        require(!TerrainLodPolicy.admitProgressiveGpuBatch(false, true, true),
            "GPU batch from an obsolete view lifecycle was admitted");
        require(!TerrainLodPolicy.admitProgressiveGpuBatch(true, false, true),
            "GPU batch outside the visible window was admitted");
        require(TerrainLodPolicy.exactDisplayStep(512.0) == 1,
            "near exact terrain lost its block-level display mesh");
        require(TerrainLodPolicy.exactDisplayStep(513.0) == 2,
            "exact terrain did not transition smoothly from step 1 to step 2");
        require(TerrainLodPolicy.exactDisplayStep(1_024.0) == 2,
            "intermediate exact display band lost step 2");
        require(TerrainLodPolicy.exactDisplayStep(1_025.0) == 4,
            "exact terrain did not transition smoothly to step 4");
        require(TerrainLodPolicy.exactDisplayStep(4_097.0) == 8,
            "exact terrain did not transition smoothly to step 8");
        require(TerrainLodPolicy.exactDisplayStep(Double.MAX_VALUE)
                == TerrainLodPolicy.EXACT_DISPLAY_MAX_STEP,
            "exact terrain exceeded its maximum display aggregation");
        require(TerrainLodPolicy.exactDisplayStepWithHysteresis(1, 560.0) == 1,
            "step-1 LOD did not retain its hysteresis band");
        require(TerrainLodPolicy.exactDisplayStepWithHysteresis(1, 564.0) == 2,
            "step-1 LOD did not leave its hysteresis band");
        require(TerrainLodPolicy.exactDisplayStepWithHysteresis(2, 513.0) == 2,
            "step-2 LOD oscillates outside the exact-detail boundary");
        require(TerrainLodPolicy.exactDisplayStepWithHysteresis(2, 512.0) == 1,
            "coarse LOD remained inside the exact-detail boundary");
        require(!TerrainLodPolicy.batchLodChanged(91L, 91L)
                && TerrainLodPolicy.batchLodChanged(91L, 92L),
            "batch LOD signature comparison is unstable");
        require(TerrainLodPolicy.admitGpuUpload(0L, 12L, 8L),
            "one oversized GPU upload cannot make forward progress");
        require(!TerrainLodPolicy.admitGpuUpload(4L, 5L, 8L),
            "GPU upload budget admitted a later over-budget upload");
        require(TerrainLodPolicy.admitGpuUpload(4L, 4L, 8L),
            "GPU upload budget rejected an upload that exactly fits");
        require(!TerrainLodPolicy.gpuUploadHasBuffers(0L),
            "empty cave upload can still block the packed GPU queue");
        require(TerrainLodPolicy.gpuUploadHasBuffers(1L),
            "non-empty packed GPU upload was misclassified as empty");
        require(TerrainLodPolicy.flightVisibleRadius(200.0, 64.0, 150, 157) == 150,
            "normal flight altitude changed the minimum visible square");
        require(TerrainLodPolicy.flightVisibleRadius(200.0, 2_048.0, 150, 249) == 249,
            "high-altitude flight did not expand the visible square");
        int visibleChunks = 30_000;
        int frameVertices = 1_250_000;
        int skirtReserve = TerrainLodPolicy.coarseSkirtVertexBudget(
            visibleChunks, frameVertices, 250_000);
        require(skirtReserve == visibleChunks * 16,
            "normal zoom must retain exposed sides for every coarse tile");
        int detailedBudget = TerrainLodPolicy.detailedVertexBudget(
            visibleChunks, frameVertices, skirtReserve);
        require((long) detailedBudget + (long) visibleChunks * 4L + skirtReserve <= frameVertices,
            "detailed terrain can starve the coarse LOD band");
        int maximumHorizonSkirts = TerrainLodPolicy.coarseSkirtVertexBudget(
            250_000, frameVertices, 250_000);
        require(maximumHorizonSkirts == 0,
            "maximum horizon must preserve the minimum detailed center");
        require(TerrainLodPolicy.detailedVertexBudget(
            250_000, frameVertices, maximumHorizonSkirts) == 250_000,
            "maximum retained horizon lost its coarse geometry reserve");
        require(TerrainLodPolicy.currentChunkVertexBudget(100, 10, 1_000) == 860,
            "per-visible-chunk reservation was not subtracted before LOD selection");
        require(TerrainLodPolicy.coarseTopIsGuaranteed(956, 10, 1_000),
            "the current cached chunk lost its reserved coarse top");
        require(!TerrainLodPolicy.coarseTopIsGuaranteed(957, 10, 1_000),
            "coarse-top guarantee accepted an overcommitted frame");
        require(TerrainLodPolicy.displayedStepOrPlaceholder(null, true) == 16,
            "missing stored LOD did not resolve to the coarse placeholder");
        require(TerrainLodPolicy.displayedStepOrPlaceholder(null, false) == -1,
            "missing geometry was incorrectly classified as renderable");
        require(TerrainLodPolicy.requiresTransitionSkirt(4, 16),
            "different displayed LODs did not request an edge skirt");
        require(!TerrainLodPolicy.requiresTransitionSkirt(8, 8),
            "equal displayed LODs requested an unnecessary edge skirt");
        require(TerrainLodPolicy.shouldRenderHeightTransition(1),
            "one-block terrain transition lost its visible side");
        require(TerrainLodPolicy.shouldRenderHeightTransition(96),
            "tall cliffs were incorrectly rejected as implausible");
        require(!TerrainLodPolicy.shouldRenderHeightTransition(0),
            "equal heights emitted a buried side face");
        require(TerrainLodPolicy.tiledSurfaceQuadCount(16, 16,
                BlockTextureAtlas.TILED_REPEAT_SPAN) == 64
                && TerrainLodPolicy.tiledSurfaceQuadCount(8, 8,
                    BlockTextureAtlas.TILED_REPEAT_SPAN) == 16
                && TerrainLodPolicy.tiledSurfaceQuadCount(4, 4,
                    BlockTextureAtlas.TILED_REPEAT_SPAN) == 4,
            "pre-tiled surface sprites no longer bound coarse terrain geometry");
        double firstLodScale = TerrainLodPolicy.lodThresholdScale(17, -9);
        double repeatedLodScale = TerrainLodPolicy.lodThresholdScale(17, -9);
        double adjacentLodScale = TerrainLodPolicy.lodThresholdScale(18, -9);
        require(firstLodScale == repeatedLodScale,
            "LOD transition staggering changes between frames");
        require(firstLodScale >= 1.0 - TerrainLodPolicy.LOD_TRANSITION_VARIATION
                && firstLodScale <= 1.0 + TerrainLodPolicy.LOD_TRANSITION_VARIATION,
            "LOD transition staggering escaped its bounded blend band");
        require(firstLodScale != adjacentLodScale,
            "adjacent chunks retained one hard LOD threshold ring");
        require(!TerrainLodPolicy.permitsCoarsePlaceholder(1),
            "exact terrain accepted a fat coarse placeholder");
        require(TerrainLodPolicy.permitsCoarsePlaceholder(8),
            "mid-distance terrain cannot use its bounded loading placeholder");
        require(TerrainLodPolicy.insideActiveWindow(25_013, 0),
            "active window lost its requested horizon");
        require(!TerrainLodPolicy.insideActiveWindow(25_014, 0),
            "active window extends beyond its padding");
        int persistentAnchor = TerrainLodPolicy.persistentStreamAnchor(32, 96, 64);
        require(persistentAnchor == 32,
            "camera crossing a 64-chunk boundary reset the persistent stream anchor");
        require(TerrainLodPolicy.persistentStreamAnchor(Integer.MIN_VALUE, 96, 64) == 96,
            "uninitialized stream anchor did not use the camera's 64-chunk region");
        require(!TerrainLodPolicy.bakedCacheRequiresEviction(80_000, 900L, 1_000L),
            "more than 30,000 baked chunks still triggers count-based eviction");
        require(TerrainLodPolicy.bakedCacheRequiresEviction(1, 1_001L, 1_000L),
            "baked byte limit is no longer enforced");
        require(TerrainLodPolicy.performanceThrottleLevel(16.7, 2.0) == 0,
            "smooth frames incorrectly throttle continuous generation");
        require(TerrainLodPolicy.performanceThrottleLevel(30.0, 2.0) == 2,
            "slow frames do not apply render backpressure");
        require(TerrainLodPolicy.performanceThrottleLevel(16.7, 20.0) == 3,
            "expensive terrain rendering does not apply maximum backpressure");
        require(TerrainLodPolicy.adaptiveBackgroundBudget(12, 0, 1) == 12
                && TerrainLodPolicy.adaptiveBackgroundBudget(12, 2, 1) == 3,
            "adaptive worker budget does not scale with frame pressure");
        require(TerrainLodPolicy.adaptiveBackgroundBudget(2, 3, 1) == 1,
            "slow-frame backpressure stopped background generation completely");
        require(Terrain3DRenderer.canQueueBackgroundBake(0),
            "empty background bake queue rejected work");
        require(Terrain3DRenderer.canQueueBackgroundBake(8_191),
            "background bake queue rejected its final bounded slot");
        require(!Terrain3DRenderer.canQueueBackgroundBake(8_192),
            "background bake queue exceeded its hard bound");
        int fixedRing = TerrainLodPolicy.retentionRing(32, 32, -40, 50);
        require(fixedRing == TerrainLodPolicy.retentionRing(32, 32, -40, 50),
            "retention ordering depends on camera movement");
        require(TerrainLodPolicy.retentionRing(32, 32, 96, 96) == 64,
            "square-ring retention stopped using Chebyshev distance");
        require(TerrainLodPolicy.visibleInAnchoredOrCameraWindow(
                0, 0, 300, 0, -100, 0, 150, 150),
            "anchored terrain disappeared when the camera moved away");
        require(TerrainLodPolicy.visibleInAnchoredOrCameraWindow(
                0, 0, 300, 0, 400, 0, 150, 150),
            "camera-local spectator terrain was not retained");
        require(!TerrainLodPolicy.visibleInAnchoredOrCameraWindow(
                0, 0, 300, 0, 151, 151, 150, 150),
            "visibility union leaked outside both square windows");

        TerrainStreamingCursor cursor = new TerrainStreamingCursor();
        require(cursor.next(40, -20).equals(
            new TerrainStreamingCursor.Coordinate(40, -20, 0)),
            "streaming cursor did not begin at the camera anchor");
        TerrainStreamingCursor.Coordinate[] firstRing = new TerrainStreamingCursor.Coordinate[8];
        for (int i = 0; i < firstRing.length; i++) firstRing[i] = cursor.next(40, -20);
        require(cursor.snapshot().completedPositions() == 9L,
            "streaming frontier progress does not match the completed center square");
        TerrainStreamingCursor.Coordinate[] expectedFirstRing = {
            new TerrainStreamingCursor.Coordinate(41, -20, 1),
            new TerrainStreamingCursor.Coordinate(41, -19, 1),
            new TerrainStreamingCursor.Coordinate(40, -19, 1),
            new TerrainStreamingCursor.Coordinate(39, -19, 1),
            new TerrainStreamingCursor.Coordinate(39, -20, 1),
            new TerrainStreamingCursor.Coordinate(39, -21, 1),
            new TerrainStreamingCursor.Coordinate(40, -21, 1),
            new TerrainStreamingCursor.Coordinate(41, -21, 1)
        };
        require(java.util.Arrays.equals(firstRing, expectedFirstRing),
            "first terrain ring is not a contiguous clockwise spiral");
        cursor.reset();
        Set<Long> coordinates = new HashSet<>();
        TerrainStreamingCursor.Coordinate previous = null;
        for (int i = 0; i < 225; i++) {
            TerrainStreamingCursor.Coordinate coordinate = cursor.next(40, -20);
            long packed = ((long) coordinate.chunkX() << 32)
                ^ (coordinate.chunkZ() & 0xFFFFFFFFL);
            require(coordinates.add(packed), "streaming spiral emitted a duplicate coordinate");
            require(coordinate.ring() <= 7, "streaming spiral skipped a ring");
            if (previous != null) {
                int movement = Math.abs(coordinate.chunkX() - previous.chunkX())
                    + Math.abs(coordinate.chunkZ() - previous.chunkZ());
                require(movement == 1, "streaming spiral emitted a non-adjacent chunk");
            }
            previous = coordinate;
        }
        require(cursor.ring() == 8, "streaming spiral did not finish the expected square");
        TerrainStreamingCursor.State saved = cursor.snapshot();
        TerrainStreamingCursor.Coordinate expectedNext = cursor.next(40, -20);
        TerrainStreamingCursor resumed = new TerrainStreamingCursor();
        resumed.restore(saved);
        require(resumed.next(40, -20).equals(expectedNext),
            "streaming cursor did not resume at its saved frontier");

        TerrainPresentationFrontier frontier = new TerrainPresentationFrontier();
        require(frontier.sequenceFor(10L) == 0L
                && frontier.sequenceFor(20L) == 1L
                && frontier.sequenceFor(30L) == 2L,
            "presentation frontier did not preserve dispatch order");
        frontier.markReady(30L);
        require(frontier.revealedThrough() == -1L && !frontier.isRevealed(30L),
            "out-of-order terrain became visible before the spiral frontier");
        frontier.markReady(10L);
        require(frontier.revealedThrough() == 0L && frontier.pollNewlyRevealed() == 10L,
            "first completed terrain chunk did not advance the presentation frontier");
        frontier.markReady(20L);
        require(frontier.revealedThrough() == 2L && frontier.trackedChunks() == 0,
            "presentation frontier retained an already visible contiguous prefix");
        require(
                frontier.pollNewlyRevealed() == 20L
                && frontier.pollNewlyRevealed() == 30L,
            "presentation frontier did not release the contiguous ready prefix");
        frontier.reset();
        require(frontier.revealedThrough() == -1L && frontier.sequenceFor(40L) == 0L,
            "presentation frontier did not reset for a new world");
    }

    private static void verifyMaterials() {
        require(BlockTextureAtlas.firstFrameHeight(16, 512) == 16,
            "animated texture sheet was stretched across a block face");
        require(BlockTextureAtlas.firstFrameHeight(16, 16) == 16,
            "square block texture changed dimensions");
        require(BlockTextureAtlas.firstFrameHeight(16, 24) == 24,
            "non-frame texture was incorrectly cropped");
        require(BlockTextureAtlas.textureFrameHeight("water_still.png", 16, 512, true) == 16,
            "animated texture metadata did not select the first frame");
        require(BlockTextureAtlas.textureFrameHeight("oak_door_bottom.png", 16, 32, false) == 32,
            "ordinary tall texture was mistaken for an animation strip");
        require(!TerrainMaterialPolicy.renderPreviewMaterial("minecraft:oak_leaves"),
            "leaf preview must not create opaque white quads");
        require(!TerrainMaterialPolicy.validPredictedSurface(1, "minecraft:bedrock"),
            "End bedrock roof artifact must be rejected");
        require(!TerrainMaterialPolicy.includeCaveSample(-1, 124, "minecraft:netherrack"),
            "Nether roof band must be filtered");
        require(TerrainMaterialPolicy.includeCaveSample(-1, 80, "minecraft:netherrack"),
            "Nether caves below the roof must remain visible");
        require(!TerrainMaterialPolicy.renderAtlasMaterial(true),
            "unsupported atlas material must be gated, not drawn as grey fallback");
        require(TerrainMaterialPolicy.caveFaceHeight() == 1,
            "cave LOD must not create vertical sampling pillars");
        require(TerrainMaterialPolicy.exposureNeighborDistance() == 1,
            "cave exposure must inspect actual adjacent blocks");
        require(TerrainMaterialPolicy.sameCaveMaterial(
                "minecraft:stone", "minecraft:stone"),
            "equal cave materials cannot form a conservative coarse cell");
        require(!TerrainMaterialPolicy.sameCaveMaterial(
                "minecraft:stone", "minecraft:deepslate"),
            "different cave materials were merged into one oversized face");
        require(TerrainMaterialPolicy.terrainSupportingSurface("minecraft:grass_block"),
            "grass terrain was rejected as an observed surface");
        require(TerrainMaterialPolicy.terrainSupportingSurface("minecraft:stone"),
            "stone terrain was rejected as an observed surface");
        require(!TerrainMaterialPolicy.terrainSupportingSurface("minecraft:birch_log"),
            "tree logs can still become observed terrain columns");
        require(!TerrainMaterialPolicy.terrainSupportingSurface("minecraft:oak_leaves"),
            "tree leaves can still become observed terrain columns");
        require(!TerrainMaterialPolicy.terrainSupportingSurface("minecraft:torch"),
            "decorative blocks can still become observed terrain columns");
        require(!TerrainMaterialPolicy.terrainSupportingSurface("minecraft:short_grass")
                && !TerrainMaterialPolicy.terrainSupportingSurface("minecraft:tall_grass")
                && !TerrainMaterialPolicy.terrainSupportingSurface("minecraft:fern")
                && !TerrainMaterialPolicy.terrainSupportingSurface("minecraft:leaf_litter")
                && !TerrainMaterialPolicy.terrainSupportingSurface("minecraft:wildflowers")
                && !TerrainMaterialPolicy.terrainSupportingSurface("minecraft:firefly_bush")
                && !TerrainMaterialPolicy.terrainSupportingSurface("minecraft:flowering_azalea")
                && !TerrainMaterialPolicy.terrainSupportingSurface("minecraft:big_dripleaf")
                && !TerrainMaterialPolicy.terrainSupportingSurface("minecraft:brain_coral")
                && !TerrainMaterialPolicy.terrainSupportingSurface("minecraft:warped_fungus")
                && !TerrainMaterialPolicy.terrainSupportingSurface("minecraft:pumpkin")
                && !TerrainMaterialPolicy.terrainSupportingSurface("minecraft:nether_wart_block"),
            "ground vegetation can still become heightfield cubes");
        require(Terrain3DRenderer.previewBoxesForShape(Shapes.empty()).isEmpty(),
            "empty preview shape attempted to resolve bounds");
        require(Terrain3DRenderer.previewBoxesForShape(Shapes.block()).size() == 1,
            "full preview shape lost its render bounds");
    }

    private static void verifyDiskCache() throws Exception {
        Path root = Files.createTempDirectory("seed-explorer-terrain-cache-probe-");
        try {
            TerrainDiskCache cache = new TerrainDiskCache(root, 1024L);
            cache.awaitIdle();
            long seed = 42L;
            for (int chunkX = 0; chunkX < 24; chunkX++) {
                int[] heights = new int[256];
                String[] states = new String[256];
                int[] fluidHeights = new int[256];
                String[] fluids = new String[256];
                for (int i = 0; i < 256; i++) {
                    heights[i] = 60 + (i & 7);
                    states[i] = "minecraft:probe_material_" + chunkX + "_" + i;
                }
                cache.storeNow(seed, 0, new TerrainDiskCache.CachedChunk(
                    chunkX, 0, 1, heights, states, fluidHeights, fluids));
            }
            long bytes = cache.enforceLimitNow();
            require(bytes <= 1024L, "cache cap exceeded: " + bytes);

            int[] heights = new int[256];
            String[] states = new String[256];
            int[] fluidHeights = new int[256];
            String[] fluids = new String[256];
            states[0] = "minecraft:stone";
            heights[0] = 64;
            TerrainDiskCache.CachedChunk fine = new TerrainDiskCache.CachedChunk(
                100, -2, 2, heights, states, fluidHeights, fluids);
            cache.storeNow(seed, -1, fine);
            require(cache.load(seed, -1, 100, -2, 2).isPresent(), "cache round trip failed");
            require(cache.load(seed, -1, 100, -2, 1).isEmpty(), "coarse cache used as full detail");
            TerrainDiskCache.CachedChunk exact = new TerrainDiskCache.CachedChunk(
                101, -2, 1, heights, states, fluidHeights, fluids, true);
            cache.storeNow(seed, -1, exact);
            TerrainDiskCache.CachedChunk exactLoaded = cache.load(
                seed, -1, 101, -2, 1).orElseThrow();
            require(exactLoaded.exactSurface(),
                "exact surface identity was lost in the V3 disk-cache round trip");
            cache.storeNow(seed, -1, new TerrainDiskCache.CachedChunk(
                101, -2, 1, heights, states, fluidHeights, fluids, false));
            require(cache.load(seed, -1, 101, -2, 1).orElseThrow().exactSurface(),
                "same-step base-density data replaced an exact cached surface");
            TerrainDiskCache.CachedChunk isolated = new TerrainDiskCache.CachedChunk(
                200, 4, 2, heights, states, fluidHeights, fluids);
            cache.storeNow("save-a", seed, 0, isolated);
            require(cache.load("save-a", seed, 0, 200, 4, 2).isPresent(),
                "per-world terrain cache round trip failed");
            require(cache.load("save-b", seed, 0, 200, 4, 2).isEmpty(),
                "terrain cache leaked between saves with the same seed");
            TerrainDiskCache.CachedChunk legacy = new TerrainDiskCache.CachedChunk(
                300, 5, 2, heights, states, fluidHeights, fluids);
            cache.storeNow(seed, 0, legacy);
            require(cache.load("save-a", seed, 0, 300, 5, 2).isPresent(),
                "legacy terrain cache was not claimed by the current save");
            require(cache.load("save-b", seed, 0, 300, 5, 2).isEmpty(),
                "legacy terrain cache claim leaked to a second save");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void verifyStreamPersistence() throws Exception {
        Path root = Files.createTempDirectory("seed-explorer-stream-state-probe-");
        try {
            TerrainStreamStateStore store = new TerrainStreamStateStore(root);
            TerrainStreamStateStore.State state = new TerrainStreamStateStore.State(
                64, -128, new TerrainStreamingCursor.State(25, 17),
                new TerrainStreamingCursor.State(12, 3), List.of(11L, 22L), Set.of(33L, 44L));
            store.storeNow("save-a", 42L, 0, state);
            require(store.load("save-a", 42L, 0).orElseThrow().equals(state),
                "stream frontier round trip failed");
            require(store.load("save-b", 42L, 0).isEmpty(),
                "stream frontier leaked between saves with the same seed");
            require(store.load("save-a", 42L, -1).isEmpty(),
                "stream frontier leaked between dimensions");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void verifyStructurePersistence() throws Exception {
        Path root = Files.createTempDirectory("seed-explorer-structure-cache-probe-");
        try {
            StructurePredictionStore store = new StructurePredictionStore(root);
            GeneratedStructure structure = new GeneratedStructure(160, 72, -80, 10, -5,
                StructureType.DESERT_PYRAMID, "minecraft:desert_pyramid", false);
            ItemLoot item = new ItemLoot("minecraft:diamond", 2, 4, 1, "Diamond",
                false, null, 0, null)
                .withFixedEnchantment("minecraft:fortune", 3);
            ChestLootOutput chest = new ChestLootOutput(161, 70, -79,
                "minecraft:chests/desert_pyramid", List.of(item), 99L, true);
            long regionKey = ((long) 3 << 32) | 7L;
            StructurePredictionStore.RegionPrediction region =
                new StructurePredictionStore.RegionPrediction(regionKey,
                    List.of(new StructurePredictionStore.StructurePrediction(structure,
                        158, 68, -82, 166, 76, -74, List.of(chest))));
            store.storeNow("save-a", 123L, 0, true, true, region);
            StructurePredictionStore.RegionPrediction loaded = store.load(
                "save-a", 123L, 0, regionKey, true, true).orElseThrow();
            require(loaded.structures().size() == 1
                    && loaded.structures().get(0).chests().get(0).predictedItems().get(0)
                        .enchantmentLevel() == 3,
                "structure/loot persistence round trip failed");
            LootSearchTerm term = new LootSearchTerm("minecraft:diamond", null, 0, "Diamond");
            require(store.findMatches("save-a", 123L, Set.of(0), Set.of(term)).size() == 1,
                "persistent item index lookup failed");
            require(store.findMatches("save-b", 123L, Set.of(0), Set.of(term)).isEmpty(),
                "persistent structure index leaked between saves");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
