package me.seedexplorer.seedcracking;

import java.util.List;

/** Outcome of a bounded parallel pillar time-machine search. */
public record PillarSearchResult(
    List<Long> structureSeeds,
    long candidatesTested,
    boolean rangeExhausted,
    boolean timeBudgetExceeded
) {
    public PillarSearchResult {
        structureSeeds = List.copyOf(structureSeeds);
    }
}
