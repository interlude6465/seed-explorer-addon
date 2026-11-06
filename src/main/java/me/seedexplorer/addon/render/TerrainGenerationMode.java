package me.seedexplorer.addon.render;

/** Selects the experimental far-terrain sampler without adding a UI control. */
public final class TerrainGenerationMode {
    public static final String PROPERTY = "seedexplorer.terrainMode";

    private TerrainGenerationMode() {
    }

    /**
     * Horizon-inspired density sampling is opt-in. Set
     * {@code -Dseedexplorer.terrainMode=horizon} in the Minecraft JVM arguments.
     */
    public static boolean usesHorizonSampling() {
        return "horizon".equalsIgnoreCase(System.getProperty(PROPERTY, "vanilla"));
    }
}
