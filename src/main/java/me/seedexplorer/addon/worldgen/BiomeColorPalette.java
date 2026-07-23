/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.worldgen;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Stable map colors and legacy search ids for vanilla biome resource ids. */
final class BiomeColorPalette {
    private static final Map<String, Integer> COLORS = new HashMap<>();
    private static final Map<String, Integer> LEGACY_IDS = new HashMap<>();

    static {
        put("minecraft:ocean", 0, 0xFF5C2A1A);
        put("minecraft:deep_ocean", 1, 0xFF3A1A0E);
        put("minecraft:warm_ocean", 2, 0xFF8C5E3B);
        put("minecraft:lukewarm_ocean", 3, 0xFF6C3A1A);
        put("minecraft:cold_ocean", 4, 0xFF5C2A1A);
        put("minecraft:deep_lukewarm_ocean", 5, 0xFF4A1A0E);
        put("minecraft:deep_cold_ocean", 6, 0xFF3A1A0E);
        put("minecraft:deep_frozen_ocean", 7, 0xFF3A1A0E);
        put("minecraft:plains", 8, 0xFF6DB86D);
        put("minecraft:sunflower_plains", 9, 0xFF4D7A4D);
        put("minecraft:forest", 10, 0xFF3A6B3A);
        put("minecraft:dark_forest", 11, 0xFF2D5A2D);
        put("minecraft:birch_forest", 12, 0xFF4A8C5A);
        put("minecraft:old_growth_birch_forest", 13, 0xFF3A7A4A);
        put("minecraft:taiga", 14, 0xFF4A6B4A);
        put("minecraft:old_growth_pine_taiga", 15, 0xFF3A5A3A);
        put("minecraft:old_growth_spruce_taiga", 15, 0xFF3A5A3A);
        put("minecraft:snowy_taiga", 16, 0xFF6B8A6B);
        put("minecraft:snowy_plains", 17, 0xFF8A9A8A);
        put("minecraft:snowy_slopes", 18, 0xFF9A8A8A);
        put("minecraft:ice_spikes", 19, 0xFFE0D0D0);
        put("minecraft:savanna", 20, 0xFF5AA8B5);
        put("minecraft:savanna_plateau", 21, 0xFF3A7A8A);
        put("minecraft:desert", 22, 0xFF3A7AB5);
        put("minecraft:badlands", 23, 0xFF2A6A9A);
        put("minecraft:eroded_badlands", 23, 0xFF235F8E);
        put("minecraft:wooded_badlands", 23, 0xFF36745E);
        put("minecraft:windswept_hills", 24, 0xFF7A7A7A);
        put("minecraft:windswept_gravelly_hills", 25, 0xFF6A6A6A);
        put("minecraft:windswept_forest", 26, 0xFF5A6A5A);
        put("minecraft:stony_peaks", 27, 0xFF7A8A8A);
        put("minecraft:meadow", 28, 0xFF6A8A6A);
        put("minecraft:jungle", 29, 0xFF2A5A2A);
        put("minecraft:sparse_jungle", 30, 0xFF3A6A3A);
        put("minecraft:bamboo_jungle", 31, 0xFF3A5A4A);
        put("minecraft:swamp", 32, 0xFF3A5A4A);
        put("minecraft:mangrove_swamp", 32, 0xFF254C45);
        put("minecraft:beach", 33, 0xFF80C0D0);
        put("minecraft:snowy_beach", 33, 0xFFD5D1C5);
        put("minecraft:stony_shore", 33, 0xFF7A827F);
        put("minecraft:river", 34, 0xFF9A6A3A);
        put("minecraft:frozen_river", 48, 0xFFD0C8C0);
        put("minecraft:mushroom_fields", 35, 0xFF6A8A6A);
        put("minecraft:nether_wastes", 36, 0xFF1A1A3A);
        put("minecraft:crimson_forest", 37, 0xFF1A2A5A);
        put("minecraft:warped_forest", 38, 0xFF5A2A1A);
        put("minecraft:soul_sand_valley", 39, 0xFF1A3A4A);
        put("minecraft:basalt_deltas", 40, 0xFF1A4A6A);
        put("minecraft:the_end", 41, 0xFF5A3A5A);
        put("minecraft:end_midlands", 42, 0xFF4A6A4A);
        put("minecraft:end_barrens", 43, 0xFF3A5A3A);
        put("minecraft:small_end_islands", 44, 0xFF6A5A6A);
        put("minecraft:end_highlands", 42, 0xFF586F58);
        put("minecraft:cherry_grove", 45, 0xFFB0A0D0);
        put("minecraft:pale_garden", 46, 0xFF5A6A5A);
        put("minecraft:frozen_ocean", 47, 0xFF7A4A2A);
        put("minecraft:deep_dark", 49, 0xFF1A181F);
        put("minecraft:dripstone_caves", 50, 0xFF5C6A6B);
        put("minecraft:lush_caves", 51, 0xFF377A42);
        put("minecraft:grove", 52, 0xFF8A958A);
        put("minecraft:frozen_peaks", 53, 0xFFC8C8D8);
        put("minecraft:jagged_peaks", 54, 0xFFA8A8B8);
        put("minecraft:flower_forest", 10, 0xFF4F8C62);
        put("minecraft:windswept_savanna", 20, 0xFF4A9A9F);
    }

    private BiomeColorPalette() {
    }

    static PredictedBiome create(String biomeId) {
        String normalized = normalizeId(biomeId);
        return new PredictedBiome(
            LEGACY_IDS.getOrDefault(normalized, -1),
            normalized,
            displayName(normalized),
            COLORS.getOrDefault(normalized, fallbackColor(normalized))
        );
    }

    private static void put(String id, int legacyId, int color) {
        COLORS.put(id, color);
        LEGACY_IDS.put(id, legacyId);
    }

    private static String normalizeId(String id) {
        if (id == null || id.isBlank()) return "unknown";
        String normalized = id.toLowerCase(Locale.ROOT);
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    private static String displayName(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String[] words = path.split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) builder.append(word.substring(1));
        }
        return builder.isEmpty() ? "Unknown" : builder.toString();
    }

    private static int fallbackColor(String id) {
        int hash = id.hashCode();
        int r = 45 + Math.floorMod(hash, 130);
        int g = 55 + Math.floorMod(hash >> 8, 130);
        int b = 45 + Math.floorMod(hash >> 16, 130);
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }
}
