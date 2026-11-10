package me.seedexplorer.addon.worldgen;

import me.seedexplorer.addon.structures.StructureType;

/** Pure introduction-version policy for prediction content. Terrain generation
 * remains owned by the running Minecraft registry and is not backported here. */
public final class PredictionVersionPolicy {
    private PredictionVersionPolicy() {
    }

    public static boolean supportsStructure(String version, StructureType type) {
        if (type == null) return false;
        String minimum = switch (type) {
            case TRAIL_RUINS -> "1.20";
            case TRIAL_CHAMBER -> "1.21";
            case ABANDONED_CAMP -> "26.3";
            default -> "1.19.4";
        };
        return compareVersions(version, minimum) >= 0;
    }

    public static boolean supportsBiome(String version, String biomeId) {
        if (biomeId == null) return false;
        String minimum = switch (normalizeId(biomeId)) {
            case "minecraft:sulfur_caves" -> "26.2";
            case "minecraft:dappled_forest" -> "26.3";
            default -> "1.19.4";
        };
        return compareVersions(version, minimum) >= 0;
    }

    public static boolean supportsPoplar(String version) {
        return compareVersions(version, "26.3") >= 0;
    }

    public static int compareVersions(String left, String right) {
        String[] a = components(left);
        String[] b = components(right);
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            int av = i < a.length ? parseComponent(a[i]) : 0;
            int bv = i < b.length ? parseComponent(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static String normalizeId(String id) {
        String normalized = id.trim().toLowerCase(java.util.Locale.ROOT)
            .replace(' ', '_');
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    private static String[] components(String version) {
        return version == null || version.isBlank()
            ? new String[] { "0" }
            : version.trim().split("\\.");
    }

    private static int parseComponent(String value) {
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Integer.parseInt(value.substring(0, end));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
