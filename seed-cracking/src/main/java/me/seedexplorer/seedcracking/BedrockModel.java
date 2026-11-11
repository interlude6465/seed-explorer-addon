package me.seedexplorer.seedcracking;

import java.util.Objects;

/**
 * Immutable description of a vanilla-style bedrock floor/roof model.
 *
 * <p>The model only stores geometry: world bottom, world height, and whether
 * the floor and/or roof vertical-gradient rule is active. The random-name and
 * gradient behavior lives in {@link VanillaBedrockOracle}. Use
 * {@link BedrockPreset} for the audited vanilla presets, or {@link #custom} for
 * a datapack with different anchors.</p>
 *
 * @param id human-readable identifier used in diagnostics
 * @param minY lowest block Y of the noise-settings height
 * @param height number of blocks in the noise-settings height
 * @param floor whether the {@code bedrock_floor} rule is active
 * @param roof whether the {@code bedrock_roof} rule is active
 */
public record BedrockModel(
    String id,
    int minY,
    int height,
    boolean floor,
    boolean roof
) {
    /** Number of blocks spanned by each vanilla vertical gradient. */
    public static final int GRADIENT_SPAN = 5;

    public BedrockModel {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive: " + height);
        }
        long maxY = (long) minY + height - 1L;
        if (maxY < Integer.MIN_VALUE || maxY > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "minY + height - 1 overflows an int: minY=" + minY + ", height=" + height);
        }
        if ((floor || roof) && height <= GRADIENT_SPAN) {
            throw new IllegalArgumentException(
                "height must exceed the gradient span " + GRADIENT_SPAN + " when a rule is active: "
                    + height);
        }
        if (floor && roof && height < 2 * GRADIENT_SPAN + 1) {
            throw new IllegalArgumentException(
                "floor and roof gradients overlap; height must be at least "
                    + (2 * GRADIENT_SPAN + 1) + ": " + height);
        }
    }

    public static BedrockModel vanilla(BedrockPreset preset) {
        Objects.requireNonNull(preset, "preset");
        return new BedrockModel(preset.id(), preset.minY(), preset.height(),
            preset.floor(), preset.roof());
    }

    public static BedrockModel custom(String id, int minY, int height,
                                      boolean floor, boolean roof) {
        return new BedrockModel(id, minY, height, floor, roof);
    }

    public static BedrockModel overworld() {
        return vanilla(BedrockPreset.OVERWORLD);
    }

    public static BedrockModel overworldCaves() {
        return vanilla(BedrockPreset.OVERWORLD_CAVES);
    }

    public static BedrockModel nether() {
        return vanilla(BedrockPreset.NETHER);
    }

    public static BedrockModel end() {
        return vanilla(BedrockPreset.END);
    }

    public int maxY() {
        return minY + height - 1;
    }

    public boolean hasAnyRule() {
        return floor || roof;
    }

    public String describe() {
        return id + "[minY=" + minY + ", height=" + height + ", maxY=" + maxY()
            + ", floor=" + floor + ", roof=" + roof + "]";
    }
}
