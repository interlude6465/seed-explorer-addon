package me.seedexplorer.seedcracking;

import java.util.Objects;

/**
 * An observed generated feature in one chunk. The block position is the
 * feature's anchor (for example a tree trunk, ore vein origin, or geode
 * origin), not an arbitrary block inside its bounding box.
 */
public record FeaturePlacementObservation(
    String featureId,
    int chunkX,
    int chunkZ,
    int anchorX,
    int anchorY,
    int anchorZ,
    String variantId
) {
    public FeaturePlacementObservation {
        Objects.requireNonNull(featureId, "featureId");
        Objects.requireNonNull(variantId, "variantId");
        if (featureId.isBlank() || variantId.isBlank()) {
            throw new IllegalArgumentException("feature and variant ids must not be blank");
        }
    }

    public static FeaturePlacementObservation anyVariant(String featureId, int chunkX, int chunkZ,
                                                         int anchorX, int anchorY, int anchorZ) {
        return new FeaturePlacementObservation(featureId, chunkX, chunkZ, anchorX, anchorY,
            anchorZ, "*");
    }
}
