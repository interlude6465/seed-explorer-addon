package me.seedexplorer.seedcracking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Checks partial or complete feature-anchor observations using a versioned oracle. */
public final class FeaturePlacementFilter implements WorldSeedFilter {
    private final FeaturePlacementOracle oracle;
    private final List<FeaturePlacementObservation> observations;
    private final boolean requireNoUnexpectedPlacements;

    public FeaturePlacementFilter(FeaturePlacementOracle oracle,
                                  List<FeaturePlacementObservation> observations,
                                  boolean requireNoUnexpectedPlacements) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("At least one feature observation is required");
        }
        this.observations = List.copyOf(observations);
        this.requireNoUnexpectedPlacements = requireNoUnexpectedPlacements;
    }

    @Override
    public boolean matches(long worldSeed) {
        Map<ChunkKey, List<FeaturePlacementObservation>> grouped = new HashMap<>();
        for (FeaturePlacementObservation observation : observations) {
            ChunkKey key = new ChunkKey(observation.featureId(), observation.chunkX(),
                observation.chunkZ());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(observation);
        }
        for (Map.Entry<ChunkKey, List<FeaturePlacementObservation>> entry : grouped.entrySet()) {
            ChunkKey key = entry.getKey();
            List<FeaturePlacementObservation> actual = oracle.placements(worldSeed, key.featureId,
                key.chunkX, key.chunkZ);
            if (actual == null || actual.stream().anyMatch(item ->
                !key.featureId.equals(item.featureId()) || key.chunkX != item.chunkX()
                    || key.chunkZ != item.chunkZ()) || !containsAll(entry.getValue(), actual)) {
                return false;
            }
            if (requireNoUnexpectedPlacements && !sameMultiset(entry.getValue(), actual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAll(List<FeaturePlacementObservation> expected,
                                       List<FeaturePlacementObservation> actual) {
        return expected.stream().allMatch(item -> actual.stream().anyMatch(candidate ->
            item.anchorX() == candidate.anchorX() && item.anchorY() == candidate.anchorY()
                && item.anchorZ() == candidate.anchorZ()
                && (item.variantId().equals("*") || item.variantId().equals(candidate.variantId()))));
    }

    private static boolean sameMultiset(List<FeaturePlacementObservation> expected,
                                        List<FeaturePlacementObservation> actual) {
        if (expected.size() != actual.size()) return false;
        List<FeaturePlacementObservation> unmatched = new ArrayList<>(actual);
        for (FeaturePlacementObservation item : expected) {
            int match = -1;
            for (int index = 0; index < unmatched.size(); index++) {
                if (sameAnchor(item, unmatched.get(index))) {
                    match = index;
                    break;
                }
            }
            if (match < 0) return false;
            unmatched.remove(match);
        }
        return unmatched.isEmpty();
    }

    private static boolean sameAnchor(FeaturePlacementObservation first,
                                      FeaturePlacementObservation second) {
        return first.anchorX() == second.anchorX() && first.anchorY() == second.anchorY()
            && first.anchorZ() == second.anchorZ()
            && (first.variantId().equals("*") || first.variantId().equals(second.variantId()));
    }

    public List<FeaturePlacementObservation> observations() { return observations; }
    public boolean requireNoUnexpectedPlacements() { return requireNoUnexpectedPlacements; }

    @Override
    public String description() {
        return "feature-placement filter (" + observations.size() + " observation(s))";
    }

    private record ChunkKey(String featureId, int chunkX, int chunkZ) { }
}
