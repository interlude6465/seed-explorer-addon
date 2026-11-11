package me.seedexplorer.seedcracking;

/**
 * Vanilla surface-rule presets that can generate bedrock.
 *
 * <p>The heights here are the <em>noise-settings</em> heights used by the
 * chunk generator, not the dimension-type heights. They happen to match for
 * the Overworld, but the Nether and End dimension types are 256 blocks tall
 * while their noise settings are 128 blocks tall. Bedrock generation follows
 * the noise settings.</p>
 *
 * <p>Verified for 26.1.2, 26.2, and 26.3:</p>
 *
 * <ul>
 *   <li>{@link #OVERWORLD}: minY -64, height 384, floor only. The default
 *       Overworld material rule contains only {@code bedrock_floor}.</li>
 *   <li>{@link #OVERWORLD_CAVES}: minY -64, height 192, floor and roof. The
 *       caves noise settings are shorter and include {@code bedrock_roof}.</li>
 *   <li>{@link #NETHER}: minY 0, height 128, floor and roof.</li>
 *   <li>{@link #END}: no bedrock rule. Included for completeness.</li>
 * </ul>
 */
public enum BedrockPreset {
    OVERWORLD("overworld", -64, 384, true, false),
    OVERWORLD_CAVES("overworld_caves", -64, 192, true, true),
    NETHER("nether", 0, 128, true, true),
    END("the_end", 0, 128, false, false);

    private final String id;
    private final int minY;
    private final int height;
    private final boolean floor;
    private final boolean roof;

    BedrockPreset(String id, int minY, int height, boolean floor, boolean roof) {
        this.id = id;
        this.minY = minY;
        this.height = height;
        this.floor = floor;
        this.roof = roof;
    }

    public String id() {
        return id;
    }

    public int minY() {
        return minY;
    }

    public int height() {
        return height;
    }

    public int maxY() {
        return minY + height - 1;
    }

    public boolean floor() {
        return floor;
    }

    public boolean roof() {
        return roof;
    }

    public BedrockModel model() {
        return BedrockModel.vanilla(this);
    }
}
