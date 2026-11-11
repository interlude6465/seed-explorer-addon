package me.seedexplorer.seedcracking;

import java.util.List;

/** Result of a structure-seed lifting run. */
public record LiftResult(
    List<Long> structureSeeds,
    Status status,
    int liftingObservations,
    int exactOnlyObservations,
    int lowSeedBits,
    long lowCandidates,
    long highCandidatesTested,
    long placementChecks,
    long elapsedMillis,
    String message
) {
    public enum Status {
        /** At least one candidate survived every configured filter. */
        FOUND,
        /** The searched space was exhausted without a match. */
        NO_MATCH,
        /** Too little lifting information or too large a search; nothing was run. */
        NEEDS_MORE_OBSERVATIONS,
        /** A time or candidate budget stopped the search early. */
        BUDGET_EXHAUSTED
    }

    public LiftResult {
        structureSeeds = List.copyOf(structureSeeds);
        message = message == null ? "" : message;
    }

    public boolean found() {
        return status == Status.FOUND && !structureSeeds.isEmpty();
    }
}
