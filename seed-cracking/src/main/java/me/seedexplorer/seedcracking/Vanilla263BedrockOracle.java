package me.seedexplorer.seedcracking;

/**
 * 26.3 compatibility facade for {@link VanillaBedrockOracle}.
 *
 * <p>The no-argument constructor now models the actual default 26.3 Overworld
 * material rule, which is <strong>floor only</strong>. The previous baseline
 * also ORed in the roof rule and therefore produced wrong answers above
 * {@code maxY - 5}. Use {@link BedrockPreset#OVERWORLD_CAVES} explicitly for
 * the caves preset, which really does use both gradients.</p>
 *
 * <p>New code should use
 * {@link VersionProfile#bedrockOracle(Dimension)} or
 * {@link VanillaBedrockOracle} directly.</p>
 */
public final class Vanilla263BedrockOracle extends VanillaBedrockOracle {
    /** Default 26.3 Overworld: floor-only, minY -64, height 384. */
    public Vanilla263BedrockOracle() {
        super(MinecraftVersion.V26_3, BedrockPreset.OVERWORLD);
    }

    /**
     * Custom-height model with both floor and roof enabled, matching the
     * original constructor's intent. Prefer a {@link BedrockPreset} when the
     * world uses vanilla material rules.
     */
    public Vanilla263BedrockOracle(int minY, int height) {
        super(MinecraftVersion.V26_3, BedrockModel.custom(
            "minecraft:bedrock_custom", minY, height, true, true));
    }
}
