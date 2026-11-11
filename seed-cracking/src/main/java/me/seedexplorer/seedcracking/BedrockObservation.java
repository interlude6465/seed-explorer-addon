package me.seedexplorer.seedcracking;

/**
 * One observed bedrock/not-bedrock block.
 *
 * <p>Default Overworld bedrock in 26.1+ is produced by the
 * {@code bedrock_floor} vertical-gradient material condition. The caves preset
 * and the Nether add {@code bedrock_roof}. Those conditions use positional
 * random factories derived from the full world seed, so a handful of bedrock
 * blocks is an excellent upper-bit verifier.</p>
 *
 * <p>Use block coordinates. Floor observations are typically Y = -64..-60.
 * Roof observations only make sense in the caves preset or the Nether; the
 * default Overworld has no bedrock roof. A block that is not bedrock in the
 * generated world is just as useful as one that is.</p>
 */
public record BedrockObservation(int blockX, int blockY, int blockZ, boolean bedrock) {
    public String describe() {
        return (bedrock ? "bedrock" : "not bedrock")
            + " at (" + blockX + ", " + blockY + ", " + blockZ + ")";
    }
}
