package me.seedexplorer.seedcracking;

/**
 * Immutable progress snapshot. Implementations must not block the search for
 * long; a UI or logger should copy the values and return quickly.
 */
public record CrackingProgress(
    String stage,
    long tested,
    long total,
    long matches,
    long elapsedMillis
) {
    public double fraction() {
        return total <= 0 ? 0.0 : Math.min(1.0, (double) tested / (double) total);
    }
}
