package me.seedexplorer.standalone;

/** Focused invariant probe for the standalone minimap's frame and memory bounds. */
public final class StandaloneMinimapProbe {
    private StandaloneMinimapProbe() {
    }

    public static void main(String[] args) {
        require(StandaloneMinimapOverlay.sampleOrderIsComplete(),
            "center-out sample order must cover every cell exactly once");
        require(StandaloneMinimapOverlay.GRID_SIZE <= 24,
            "HUD terrain raster grew beyond its fixed draw-call budget");
        require(StandaloneMinimapOverlay.MAX_NEW_SAMPLES_PER_FRAME <= 24,
            "per-frame terrain sampling admission limit regressed");
        require(StandaloneMinimapOverlay.SAMPLE_BUDGET_NANOS <= 500_000L,
            "terrain sampling time budget must remain below 0.5ms");
        require(StandaloneMinimapOverlay.MAX_CACHED_SAMPLES <= 16_384,
            "terrain color cache must remain strictly bounded");
        require(StandaloneMinimapOverlay.isPredictionAreaBounded(),
            "predicted minimap area, cache, or worker queue is unbounded");
        require(StandaloneMinimapOverlay.predictionMarginCoversHud(),
            "area prediction margin no longer covers the centered HUD while moving");
        var first = StandaloneMinimapOverlay.predictionKey(0L, "26.3", 0, 1, 1);
        var nearby = StandaloneMinimapOverlay.predictionKey(0L, "26.3", 0, 10, 10);
        require(first.equals(nearby), "nearby movement should reuse the same area prediction");
        require(StandaloneMinimapOverlay.predictionKeyChanges(0L, "26.3", 0,
            1, 1, 65, 1), "crossing an area boundary must invalidate the visible result");
        require(!first.equals(StandaloneMinimapOverlay.predictionKey(1L, "26.3", 0, 1, 1)),
            "seed must participate in the prediction cache key, including seed zero");
        require(!first.equals(StandaloneMinimapOverlay.predictionKey(0L, "26.2", 0, 1, 1)),
            "prediction version must participate in the cache key");
        require(!first.equals(StandaloneMinimapOverlay.predictionKey(0L, "26.3", -1, 1, 1)),
            "dimension must participate in the cache key");
        System.out.println("Standalone minimap probe: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
