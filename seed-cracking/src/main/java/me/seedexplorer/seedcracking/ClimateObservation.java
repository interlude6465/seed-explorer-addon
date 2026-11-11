package me.seedexplorer.seedcracking;

/** A sampled climate/temperature value from a version-matched biome oracle. */
public record ClimateObservation(int blockX, int blockY, int blockZ,
                                 double expectedTemperature, double tolerance) {
    public ClimateObservation {
        if (!Double.isFinite(expectedTemperature) || !Double.isFinite(tolerance)
            || tolerance < 0.0) {
            throw new IllegalArgumentException("temperature and tolerance must be finite; tolerance non-negative");
        }
    }

    public boolean matches(double actual) {
        return Double.isFinite(actual)
            && Math.abs(actual - expectedTemperature) <= tolerance;
    }
}
