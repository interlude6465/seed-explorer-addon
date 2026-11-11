package me.seedexplorer.seedcracking;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Full-seed filter backed by container loot.
 *
 * <p>Complete observations require exactly the same item multiset. Partial
 * observations require every observed stack to be present in at least the
 * observed quantity; extra predicted items are allowed because the player may
 * not have listed them.</p>
 */
public final class LootFilter implements WorldSeedFilter {
    private final LootOracle oracle;
    private final List<LootObservation> observations;

    public LootFilter(LootOracle oracle, List<LootObservation> observations) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("At least one loot observation is required");
        }
        this.observations = List.copyOf(observations);
    }

    @Override
    public boolean matches(long worldSeed) {
        for (LootObservation observation : observations) {
            List<LootObservation.LootItem> predicted = oracle.predict(worldSeed, observation);
            if (predicted == null) {
                return false;
            }
            Map<String, Integer> actualCounts = counts(predicted);
            Map<String, Integer> observedCounts = observation.observedCounts();
            if (observation.complete()) {
                if (!actualCounts.equals(observedCounts)) {
                    return false;
                }
            } else {
                for (Map.Entry<String, Integer> entry : observedCounts.entrySet()) {
                    if (actualCounts.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static Map<String, Integer> counts(List<LootObservation.LootItem> items) {
        Map<String, Integer> counts = new HashMap<>();
        for (LootObservation.LootItem item : items) {
            counts.merge(item.itemId(), item.count(), Integer::sum);
        }
        return counts;
    }

    public List<LootObservation> observations() {
        return observations;
    }

    @Override
    public String description() {
        return "loot filter (" + observations.size() + " container(s))";
    }
}
