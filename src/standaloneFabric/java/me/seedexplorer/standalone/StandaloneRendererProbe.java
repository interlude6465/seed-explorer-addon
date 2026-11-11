package me.seedexplorer.standalone;

import me.seedexplorer.addon.live.LiveChunkSnapshot;
import me.seedexplorer.addon.render.Terrain3DRenderer;
import me.seedexplorer.addon.render.TerrainOverlayRenderer;
import me.seedexplorer.addon.render.TerrainWorldDataSource;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.util.ServiceLoader;

/** Verifies the packaged renderer can link and initialize without Meteor. */
public final class StandaloneRendererProbe {
    private StandaloneRendererProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (!"Bamboo Abandoned Camp".equals(new GeneratedStructure(0, 0,
                StructureType.ABANDONED_CAMP, "minecraft:abandoned_camp_bamboo_jungle").displayName())
            || !"Plains Village".equals(new GeneratedStructure(0, 0,
                StructureType.VILLAGE, "minecraft:village_plains").displayName())) {
            throw new AssertionError("Structure hover names lost their generated variants");
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        System.setProperty("seedexplorer.worldIdentity", "standalone-renderer-probe");
        Terrain3DRenderer renderer = Terrain3DRenderer.shared();
        if (renderer == null) throw new AssertionError("renderer singleton is unavailable");
        renderer.setWorldDataSource(TerrainWorldDataSource.NONE);
        renderer.reset();
        TerrainOverlayRenderer overlay = ServiceLoader.load(TerrainOverlayRenderer.class)
            .findFirst().orElseThrow(() -> new AssertionError("standalone overlay provider is unavailable"));
        if (!(overlay instanceof StandaloneTerrainOverlayRenderer)) {
            throw new AssertionError("unexpected overlay provider: " + overlay.getClass().getName());
        }
        if (!StandaloneLiveChunkCapture.captureOrderIsComplete()
            || StandaloneLiveChunkCapture.captureOrderSize() != 49) {
            throw new AssertionError("live chunk capture order is incomplete");
        }
        LiveChunkSnapshot.CaptureBuilder capture = LiveChunkSnapshot.captureBuilder(0, 3, -4, -64, 320);
        capture.addBlock(1, 70, 2, Blocks.STONE.defaultBlockState());
        capture.addBlock(1, 71, 2, Blocks.GRASS_BLOCK.defaultBlockState());
        LiveChunkSnapshot snapshot = capture.build();
        if (snapshot.nonAirBlockCount() != 2 || snapshot.surfaceY(1, 2) != 72
            || !snapshot.block(1, 70, 2).is(Blocks.STONE)
            || snapshot.blocks().size() != 2) {
            throw new AssertionError("dense live chunk capture is inconsistent");
        }
        verifyLootObservations();
        String identity = Terrain3DRenderer.worldCacheIdentity(42L);
        if (!"standalone-renderer-probe".equals(identity)) {
            throw new AssertionError("unexpected renderer cache identity: " + identity);
        }
        if (StandaloneStructureIcons.supportedTypes().size()
            != me.seedexplorer.addon.structures.StructureType.values().length) {
            throw new AssertionError("standalone structure icon catalog is incomplete");
        }
        for (String fileName : StandaloneStructureIcons.requiredFileNames()) {
            String resource = StandaloneStructureIcons.resourcePath(fileName);
            try (var stream = StandaloneRendererProbe.class.getResourceAsStream(resource)) {
                if (stream == null || stream.read() < 0) {
                    throw new AssertionError("missing standalone structure icon: " + resource);
                }
            } catch (IOException exception) {
                throw new AssertionError("could not read standalone structure icon: " + resource, exception);
            }
        }
        me.seedexplorer.addon.render.TerrainRendererPolicyProbe.main(new String[0]);
        System.out.println("standalone renderer probe: linked, initialized and renderer policies verified");
    }

    private static void verifyLootObservations() {
        var builder = LiveChunkSnapshot.captureBuilder(0, -2, -1, -64, 320);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            builder.addBlock(x, 0, z, Blocks.STONE.defaultBlockState());
        builder.addBlock(15, 70, 15, Blocks.CHEST.defaultBlockState());
        builder.addBlock(1, 90, 2, Blocks.CHEST.defaultBlockState());
        builder.addBlock(3, 69, 5, Blocks.SUSPICIOUS_SAND.defaultBlockState());
        builder.addBlock(3, 70, 5, Blocks.SAND.defaultBlockState());
        builder.addBlock(4, 69, 5, Blocks.SUSPICIOUS_GRAVEL.defaultBlockState());
        var complete = builder.build();
        var sparse = new LiveChunkSnapshot(0, -2, -1, -64, 320);
        sparse.setBlock(15, 70, 15, Blocks.CHEST.defaultBlockState());
        var canonical = java.util.List.of(new me.seedexplorer.addon.loot.ChestLootOutput(
            -17, 68, -1, "minecraft:chests/shipwreck_treasure", java.util.List.of(), 7L, false));
        var observations = StandaloneObservedLootRefinement.observedContainers(
            0, canonical, java.util.List.of(complete), complete.updatedAt());
        if (!observations.equals(java.util.List.of(
            new me.seedexplorer.addon.loot.VanillaLootStructureSimulator.ObservedContainerPosition(
                -17, 70, -1)))) {
            throw new AssertionError("wreck observations lost negative-coordinate geometry or included an unrelated chest");
        }
        var locationOnly = StandaloneObservedLootRefinement.reconcileObservedPositions(
            0, canonical, java.util.List.of(complete), complete.updatedAt(), false);
        if (locationOnly.size() != 1 || locationOnly.getFirst().blockY() != 70
            || !locationOnly.getFirst().lootTableId().isBlank() || locationOnly.getFirst().exact()) {
            throw new AssertionError("ambiguous wreck history retained guessed loot or the wrong observed height");
        }
        var archaeology = java.util.List.of(new me.seedexplorer.addon.loot.ChestLootOutput(
            -29, 68, -11, "minecraft:archaeology/desert_pyramid", java.util.List.of(), 7L, false));
        var observedArchaeology = StandaloneObservedLootRefinement.reconcileObservedPositions(
            0, archaeology, java.util.List.of(complete), complete.updatedAt(), false);
        if (observedArchaeology.size() != 1 || observedArchaeology.getFirst().blockY() != 69
            || !observedArchaeology.getFirst().lootTableId().isBlank()) {
            throw new AssertionError("archaeology refinement included ordinary sand, unrelated columns, or guessed loot");
        }
        var belowCapture = java.util.List.of(new me.seedexplorer.addon.loot.ChestLootOutput(
            -17, -65, -1, "minecraft:chests/desert_pyramid", java.util.List.of(), 7L, false));
        if (!StandaloneObservedLootRefinement.reconcileObservedPositions(0, belowCapture,
            java.util.List.of(complete), complete.updatedAt(), false).equals(belowCapture)) {
            throw new AssertionError("out-of-capture vertical range was treated as negative evidence");
        }
        var emptyBuilder = LiveChunkSnapshot.captureBuilder(0, -2, -1, -64, 320);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            emptyBuilder.addBlock(x, 0, z, Blocks.STONE.defaultBlockState());
        var empty = emptyBuilder.build();
        if (!StandaloneObservedLootRefinement.reconcileObservedPositions(
                0, canonical, java.util.List.of(empty), empty.updatedAt(), false).isEmpty()
            || !StandaloneObservedLootRefinement.reconcileObservedPositions(
                1, canonical, java.util.List.of(empty), empty.updatedAt(), false).equals(canonical)) {
            throw new AssertionError("known-empty and unobserved chest columns were conflated");
        }
        if (!StandaloneObservedLootRefinement.observedContainers(1, canonical,
                java.util.List.of(complete), complete.updatedAt()).isEmpty()
            || !StandaloneObservedLootRefinement.observedContainers(0, canonical,
                java.util.List.of(complete), complete.updatedAt()
                    + StandaloneLiveChunkCapture.RECAPTURE_MILLIS + 1).isEmpty()
            || !StandaloneObservedLootRefinement.observedContainers(0, canonical,
                java.util.List.of(sparse), sparse.updatedAt()).isEmpty()) {
            throw new AssertionError("wrong-dimension, stale, or incomplete captures influenced loot refinement");
        }
    }
}
