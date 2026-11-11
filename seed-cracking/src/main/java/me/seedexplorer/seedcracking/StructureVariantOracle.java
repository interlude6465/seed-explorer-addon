package me.seedexplorer.seedcracking;

/** Predicts a version-matched structure variant for a candidate full seed. */
@FunctionalInterface
public interface StructureVariantOracle {
    /** Returns a stable variant identifier, or {@code null} when unavailable. */
    String variantAt(long worldSeed, String structureId, int chunkX, int chunkZ);

    default StructureVariantFilter filter(StructureVariantObservation... observations) {
        return new StructureVariantFilter(this, java.util.List.of(observations));
    }
}
