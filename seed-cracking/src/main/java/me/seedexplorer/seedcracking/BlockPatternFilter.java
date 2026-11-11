package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/** Full-seed filter for exact-coordinate block observations. */
public final class BlockPatternFilter implements WorldSeedFilter {
    private final BlockPatternOracle oracle;
    private final List<BlockPatternObservation> observations;

    public BlockPatternFilter(BlockPatternOracle oracle, List<BlockPatternObservation> observations) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(observations, "observations");
        if (observations.isEmpty()) throw new IllegalArgumentException("At least one block pattern is required");
        this.observations = List.copyOf(observations);
    }

    @Override
    public boolean matches(long worldSeed) {
        for (BlockPatternObservation pattern : observations) {
            for (BlockPatternObservation.BlockSample sample : pattern.samples()) {
                if (!sample.blockId().equals(oracle.blockAt(worldSeed,
                    sample.blockX(), sample.blockY(), sample.blockZ()))) return false;
            }
        }
        return true;
    }

    public List<BlockPatternObservation> observations() { return observations; }

    @Override
    public String description() {
        return "block-pattern filter (" + observations.size() + " pattern(s))";
    }
}
