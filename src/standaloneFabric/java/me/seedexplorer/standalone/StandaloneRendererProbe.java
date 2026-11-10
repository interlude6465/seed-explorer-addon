package me.seedexplorer.standalone;

import me.seedexplorer.addon.live.LiveChunkSnapshot;
import me.seedexplorer.addon.render.Terrain3DRenderer;
import me.seedexplorer.addon.render.TerrainOverlayRenderer;
import me.seedexplorer.addon.render.TerrainWorldDataSource;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import java.util.ServiceLoader;

/** Verifies the packaged renderer can link and initialize without Meteor. */
public final class StandaloneRendererProbe {
    private StandaloneRendererProbe() {
    }

    public static void main(String[] args) {
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
        String identity = Terrain3DRenderer.worldCacheIdentity(42L);
        if (!"standalone-renderer-probe".equals(identity)) {
            throw new AssertionError("unexpected renderer cache identity: " + identity);
        }
        System.out.println("standalone renderer probe: linked and initialized");
    }
}
