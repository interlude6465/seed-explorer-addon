package me.seedexplorer.seedcracking;

import java.util.List;

/** Result of an upper-16-bit recovery run. */
public record RecoveryResult(
    List<Long> worldSeeds,
    Status status,
    int structureSeedsTested,
    long candidatesTested,
    long filterEvaluations,
    long elapsedMillis,
    String message
) {
    public enum Status {
        /** At least one full world seed passed every filter. */
        FOUND,
        /** All structure seeds and all 65,536 upper halves were tested. */
        NO_MATCH,
        /** No filters were supplied, so the upper bits cannot be constrained. */
        NEEDS_FILTERS,
        /** The structure-seed list was empty. */
        NO_STRUCTURE_SEEDS,
        /** A time or candidate budget stopped the search early. */
        BUDGET_EXHAUSTED
    }

    public RecoveryResult {
        worldSeeds = List.copyOf(worldSeeds);
        message = message == null ? "" : message;
    }

    public boolean found() {
        return status == Status.FOUND && !worldSeeds.isEmpty();
    }

    public static RecoveryResult empty(Status status, String message) {
        return new RecoveryResult(List.of(), status, 0, 0, 0, 0, message);
    }
}
