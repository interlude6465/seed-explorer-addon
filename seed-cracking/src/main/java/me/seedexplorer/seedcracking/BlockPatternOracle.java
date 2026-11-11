package me.seedexplorer.seedcracking;

/** Reads a generated block from a version-matched worldgen oracle. */
@FunctionalInterface
public interface BlockPatternOracle {
    String blockAt(long worldSeed, int blockX, int blockY, int blockZ);
}
