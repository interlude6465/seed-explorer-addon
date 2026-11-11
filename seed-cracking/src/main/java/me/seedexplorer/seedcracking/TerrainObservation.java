package me.seedexplorer.seedcracking;

/**
 * One observed terrain surface height.
 *
 * <p>Terrain is generated from the full 64-bit seed through the target
 * version's noise router, density functions, and surface rules. A surface
 * height sample is a strong upper-bit verifier, but it requires a faithful
 * version-matched terrain oracle; a heightmap or a block-column scan from a
 * generated world is ideal.</p>
 *
 * <p>{@code tolerance} absorbs small differences between a heightmap query and
 * a particular surface rule (for example, snow layers or water). Use zero for
 * an exact block-column query.</p>
 */
public record TerrainObservation(int blockX, int blockZ, int expectedSurfaceY, int tolerance) {
    public TerrainObservation {
        if (tolerance < 0) {
            throw new IllegalArgumentException("tolerance must be non-negative: " + tolerance);
        }
    }

    public boolean matches(int actualSurfaceY) {
        if (actualSurfaceY == TerrainOracle.UNKNOWN) {
            return false;
        }
        long difference = (long) actualSurfaceY - expectedSurfaceY;
        return Math.abs(difference) <= tolerance;
    }

    public String describe() {
        return "surface Y " + expectedSurfaceY + " +/- " + tolerance
            + " at (" + blockX + ", " + blockZ + ")";
    }
}
