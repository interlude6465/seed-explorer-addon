package me.seedexplorer.seedcracking;

import java.util.List;

/**
 * Version-matched feature oracle. An implementation delegates to Minecraft's
 * configured feature pipeline and returns every anchor generated in a chunk.
 */
@FunctionalInterface
public interface FeaturePlacementOracle {
    List<FeaturePlacementObservation> placements(long worldSeed, String featureId,
                                                  int chunkX, int chunkZ);

    default FeaturePlacementFilter filter(List<FeaturePlacementObservation> observations,
                                          boolean requireNoUnexpectedPlacements) {
        return new FeaturePlacementFilter(this, observations, requireNoUnexpectedPlacements);
    }
}
