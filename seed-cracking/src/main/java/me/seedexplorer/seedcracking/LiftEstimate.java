package me.seedexplorer.seedcracking;

/**
 * Cheap estimate of a lifting search before running it.
 *
 * @param lowSeedBits          low bits constrained by the offset sieve
 * @param lowCandidates        number of low partial seeds surviving the sieve
 * @param highBits             remaining structure-seed bits
 * @param highWorkPerLow       high values per surviving low partial seed
 * @param totalHighCandidates  lowCandidates * highWorkPerLow (saturated)
 * @param maxAllowed           configured budget
 */
public record LiftEstimate(
    int lowSeedBits,
    int lowCandidates,
    int highBits,
    long highWorkPerLow,
    long totalHighCandidates,
    long maxAllowed
) {
    public boolean withinBudget() {
        return totalHighCandidates <= maxAllowed;
    }

    public boolean feasible() {
        return lowSeedBits > 0 && lowCandidates > 0 && withinBudget();
    }
}
