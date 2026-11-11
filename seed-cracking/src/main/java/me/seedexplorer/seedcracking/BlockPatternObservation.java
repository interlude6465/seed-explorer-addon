package me.seedexplorer.seedcracking;

import java.util.List;
import java.util.Objects;

/** A fixed-coordinate block-ID pattern, such as a vein or surveyed cave slice. */
public record BlockPatternObservation(String id, List<BlockSample> samples, boolean complete) {
    public BlockPatternObservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(samples, "samples");
        if (id.isBlank() || samples.isEmpty()) throw new IllegalArgumentException("pattern id and samples are required");
        samples = List.copyOf(samples);
    }

    public static BlockPatternObservation partial(String id, BlockSample... samples) {
        return new BlockPatternObservation(id, List.of(samples), false);
    }

    public static BlockPatternObservation complete(String id, BlockSample... samples) {
        return new BlockPatternObservation(id, List.of(samples), true);
    }

    public record BlockSample(int blockX, int blockY, int blockZ, String blockId) {
        public BlockSample {
            Objects.requireNonNull(blockId, "blockId");
            if (blockId.isBlank()) throw new IllegalArgumentException("block id must not be blank");
        }
    }
}
