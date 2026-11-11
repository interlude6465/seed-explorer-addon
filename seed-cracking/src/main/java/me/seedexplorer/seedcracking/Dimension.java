package me.seedexplorer.seedcracking;

import java.util.Optional;

/**
 * Vanilla dimensions and their default bedrock preset.
 *
 * <p>The default Overworld preset is floor-only. Caves is a world preset, not a
 * dimension, and must be selected explicitly with
 * {@link BedrockPreset#OVERWORLD_CAVES}.</p>
 */
public enum Dimension {
    OVERWORLD("minecraft:overworld", "overworld", BedrockPreset.OVERWORLD),
    NETHER("minecraft:the_nether", "the_nether", BedrockPreset.NETHER),
    END("minecraft:the_end", "the_end", BedrockPreset.END);

    private final String id;
    private final String shortName;
    private final BedrockPreset defaultBedrockPreset;

    Dimension(String id, String shortName, BedrockPreset defaultBedrockPreset) {
        this.id = id;
        this.shortName = shortName;
        this.defaultBedrockPreset = defaultBedrockPreset;
    }

    public String id() {
        return id;
    }

    public String shortName() {
        return shortName;
    }

    public BedrockPreset defaultBedrockPreset() {
        return defaultBedrockPreset;
    }

    /** Accepts both {@code minecraft:the_nether} and {@code the_nether}. */
    public static Optional<Dimension> tryParse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String normalized = text.trim();
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            normalized = normalized.substring(colon + 1);
        }
        normalized = switch (normalized) {
            case "nether" -> "the_nether";
            case "end" -> "the_end";
            default -> normalized;
        };
        for (Dimension dimension : values()) {
            if (dimension.shortName.equals(normalized)) {
                return Optional.of(dimension);
            }
        }
        return Optional.empty();
    }

    public static Dimension parse(String text) {
        return tryParse(text).orElseThrow(() -> new IllegalArgumentException(
            "Unknown dimension '" + text + "'; expected overworld, the_nether, or the_end"));
    }
}
