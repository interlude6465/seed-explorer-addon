package me.seedexplorer.addon.render;

/** Selects the fast far-terrain sampler without adding a UI control. */
public final class TerrainGenerationMode {
    public static final String PROPERTY = "seedexplorer.terrainMode";

    private TerrainGenerationMode() {
    }

    /**
     * Horizon sampling is the interactive default. Set
     * {@code -Dseedexplorer.terrainMode=vanilla} to force the slower column path.
     */
    public static boolean usesHorizonSampling() {
        return !"vanilla".equalsIgnoreCase(System.getProperty(PROPERTY, "horizon"));
    }
}
