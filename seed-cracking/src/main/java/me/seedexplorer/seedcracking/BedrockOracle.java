package me.seedexplorer.seedcracking;

/**
 * Returns whether a block is bedrock for a candidate world seed.
 *
 * <p>{@link VanillaBedrockOracle} is an exact, dependency-free implementation
 * of the vanilla floor and roof gradient conditions for 26.1+. Select the
 * world preset with {@link BedrockPreset}: the default Overworld is
 * <strong>floor only</strong>, while the caves preset and the Nether use both
 * floor and roof.</p>
 *
 * <p>Use {@link VersionProfile#bedrockOracle(Dimension)} for default
 * dimension behavior, or construct {@link VanillaBedrockOracle} with an
 * explicit preset.</p>
 */
@FunctionalInterface
public interface BedrockOracle {
    boolean isBedrock(long worldSeed, int blockX, int blockY, int blockZ);

    default BedrockFilter filter(BedrockObservation... observations) {
        return new BedrockFilter(this, java.util.List.of(observations));
    }
}
