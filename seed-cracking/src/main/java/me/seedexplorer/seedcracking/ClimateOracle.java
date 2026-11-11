package me.seedexplorer.seedcracking;

/** Supplies a version-matched climate value for a candidate full seed. */
@FunctionalInterface
public interface ClimateOracle {
    double temperatureAt(long worldSeed, int blockX, int blockY, int blockZ);
}
