package me.seedexplorer.seedcracking;

/**
 * Checks whether an observed random-spread structure actually generated.
 *
 * <p>{@link StructureObservation} only records a potential placement chunk. A
 * potential chunk can fail the structure's biome tag, frequency reducer,
 * exclusion zone, or terrain checks. A structure-start oracle replays those
 * gates for a candidate full world seed. This is a strong full-seed verifier
 * for any structure that the player has actually found.</p>
 */
@FunctionalInterface
public interface StructureStartOracle {
    boolean hasStructureStart(long worldSeed, StructureObservation observation);

    default StructureStartFilter filter(StructureObservation... observations) {
        return new StructureStartFilter(this, java.util.List.of(observations));
    }
}
