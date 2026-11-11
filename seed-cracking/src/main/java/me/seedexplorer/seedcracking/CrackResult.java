package me.seedexplorer.seedcracking;

import java.util.List;

/** End-to-end result of lifting structure seeds and recovering upper bits. */
public record CrackResult(
    List<Long> structureSeeds,
    List<Long> worldSeeds,
    Status status,
    LiftResult liftResult,
    RecoveryResult recoveryResult,
    String message
) {
    public enum Status {
        /** At least one full 64-bit world seed passed every filter. */
        WORLD_SEEDS_FOUND,
        /** Low-48 structure seeds were found, but no full-seed filter was supplied. */
        STRUCTURE_SEEDS_ONLY,
        /** More lifting observations are needed before the search is worthwhile. */
        NEEDS_MORE_OBSERVATIONS,
        /** Upper-bit recovery was requested without any full-seed filter. */
        NEEDS_FILTERS,
        /** The low-48 lifting search found no structure seed. */
        NO_STRUCTURE_MATCH,
        /** Structure seeds were found, but no upper half passed the filters. */
        NO_WORLD_SEED_MATCH,
        /** A time, candidate, or result budget stopped the search early. */
        BUDGET_EXHAUSTED
    }

    public CrackResult {
        structureSeeds = List.copyOf(structureSeeds);
        worldSeeds = List.copyOf(worldSeeds);
        message = message == null ? "" : message;
    }

    public boolean found() {
        return status == Status.WORLD_SEEDS_FOUND && !worldSeeds.isEmpty();
    }

    public boolean hasStructureSeeds() {
        return !structureSeeds.isEmpty();
    }

    /** Full seeds when available, otherwise the low-48 partial seeds. */
    public List<Long> bestSeeds() {
        return worldSeeds.isEmpty() ? structureSeeds : worldSeeds;
    }
}
