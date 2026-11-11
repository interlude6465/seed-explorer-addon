package me.seedexplorer.seedcracking;

import java.util.Objects;

/**
 * An observed structure orientation at its placement/start chunk.
 *
 * <p>Orientation is generated after random-spread placement, so it is a
 * full-seed constraint rather than a low-48 placement constraint. The
 * orientation values are deliberately represented with the shared geometry
 * enums so screenshot or block-derived observations can use the same model.</p>
 */
public record StructureOrientationObservation(
    String structureId,
    int chunkX,
    int chunkZ,
    StructureGeometry.Orientation orientation
) {
    public StructureOrientationObservation {
        Objects.requireNonNull(structureId, "structureId");
        if (structureId.isBlank()) {
            throw new IllegalArgumentException("structureId must not be blank");
        }
        Objects.requireNonNull(orientation, "orientation");
    }
}
