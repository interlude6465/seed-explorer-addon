package me.seedexplorer.seedcracking;

import java.util.List;

/**
 * Replays a container's 26.1+ loot table for a candidate world seed.
 *
 * <p>Implementations must use the complete target-version container seed chain
 * and the exact loot table. The existing addon's loot simulator is not
 * automatically a valid oracle: it must be independently validated against
 * generated worlds of the same version before its output is treated as proof.</p>
 */
@FunctionalInterface
public interface LootOracle {
    /**
     * Returns the predicted item stacks, or {@code null} when prediction is not
     * available for this container.
     */
    List<LootObservation.LootItem> predict(long worldSeed, LootObservation observation);

    default LootFilter filter(LootObservation... observations) {
        return new LootFilter(this, java.util.List.of(observations));
    }
}
