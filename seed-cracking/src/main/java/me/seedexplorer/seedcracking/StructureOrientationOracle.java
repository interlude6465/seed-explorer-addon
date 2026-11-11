package me.seedexplorer.seedcracking;

/** Predicts a version-matched structure orientation for a full world seed. */
@FunctionalInterface
public interface StructureOrientationOracle {
    /** Returns the observed orientation, or {@code null} if no start exists. */
    StructureGeometry.Orientation orientationAt(long worldSeed, String structureId,
                                                 int chunkX, int chunkZ);

    default StructureOrientationFilter filter(
        StructureOrientationObservation... observations) {
        return new StructureOrientationFilter(this, java.util.List.of(observations));
    }
}
