/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.map;

import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.worldgen.PredictedBiome;
import me.seedexplorer.addon.worldgen.WorldgenEngine;

import java.util.HashMap;
import java.util.Map;

/**
 * Generates biome colors for the infinite map using noise-based biome
 * distribution seeded from SeedManager. Mimics Minecraft's overworld biome layout
 * using multi-octave value noise.
 *
 * Colors are returned in ABGR format (0xAABBGGRR) suitable for
 * NativeImage.setPixelABGR.
 */
public class BiomeGenerator {
    // Populated once in a static initializer so it is fully built and safely published
    // before any thread can read it (the map is read from render/worker threads).
    private static final Map<Integer, Integer> BIOME_COLORS = new HashMap<>();

    static {
        // Colors in ABGR format (0xAABBGGRR)
        // Ocean biomes
        BIOME_COLORS.put(0, 0xFF5C2A1A);   // Ocean (dark blue)
        BIOME_COLORS.put(1, 0xFF3A1A0E);   // Deep Ocean (very dark blue)
        BIOME_COLORS.put(2, 0xFF8C5E3B);   // Warm Ocean (lighter blue)
        BIOME_COLORS.put(3, 0xFF6C3A1A);   // Lukewarm Ocean
        BIOME_COLORS.put(4, 0xFF5C2A1A);   // Cold Ocean
        BIOME_COLORS.put(5, 0xFF4A1A0E);   // Deep Lukewarm Ocean
        BIOME_COLORS.put(6, 0xFF3A1A0E);   // Deep Cold Ocean
        BIOME_COLORS.put(7, 0xFF3A1A0E);   // Deep Frozen Ocean

        // Plains & forests
        BIOME_COLORS.put(8, 0xFF6DB86D);   // Plains (green)
        BIOME_COLORS.put(9, 0xFF4D7A4D);   // Sunflower Plains
        BIOME_COLORS.put(10, 0xFF3A6B3A);  // Forest (dark green)
        BIOME_COLORS.put(11, 0xFF2D5A2D);  // Dark Forest
        BIOME_COLORS.put(12, 0xFF4A8C5A);  // Birch Forest
        BIOME_COLORS.put(13, 0xFF3A7A4A);  // Old Growth Birch Forest

        // Taiga & snowy
        BIOME_COLORS.put(14, 0xFF4A6B4A);  // Taiga
        BIOME_COLORS.put(15, 0xFF3A5A3A);  // Old Growth Taiga
        BIOME_COLORS.put(16, 0xFF6B8A6B);  // Snowy Taiga
        BIOME_COLORS.put(17, 0xFF8A9A8A);  // Snowy Plains
        BIOME_COLORS.put(18, 0xFF9A8A8A);  // Snowy Slopes
        BIOME_COLORS.put(19, 0xFFE0D0D0);  // Ice Spikes (icy white)

        // Dry biomes
        BIOME_COLORS.put(20, 0xFF5AA8B5);  // Savanna (yellow-green)
        BIOME_COLORS.put(21, 0xFF3A7A8A);  // Savanna Plateau
        BIOME_COLORS.put(22, 0xFF3A7AB5);  // Desert (sandy orange)
        BIOME_COLORS.put(23, 0xFF2A6A9A);  // Badlands (terracotta orange)

        // Mountain
        BIOME_COLORS.put(24, 0xFF7A7A7A);  // Windswept Hills (gray)
        BIOME_COLORS.put(25, 0xFF6A6A6A);  // Windswept Gravelly Hills
        BIOME_COLORS.put(26, 0xFF5A6A5A);  // Windswept Forest
        BIOME_COLORS.put(27, 0xFF7A8A8A);  // Stony Peaks (light gray)
        BIOME_COLORS.put(28, 0xFF6A7A6A);  // Meadow (light green)

        // Jungle & swamp
        BIOME_COLORS.put(29, 0xFF2A5A2A);  // Jungle (dark green)
        BIOME_COLORS.put(30, 0xFF3A6A3A);  // Sparse Jungle
        BIOME_COLORS.put(31, 0xFF3A5A4A);  // Bamboo Jungle
        BIOME_COLORS.put(32, 0xFF3A5A4A);  // Swamp (olive green)

        // Beach & river
        BIOME_COLORS.put(33, 0xFF80C0D0);  // Beach (sandy)
        BIOME_COLORS.put(34, 0xFF9A6A3A);  // River (blue)
        BIOME_COLORS.put(35, 0xFF6A8A6A);  // Mushroom Fields (pale green)

        // Nether biomes
        BIOME_COLORS.put(36, 0xFF1A1A3A);  // Nether Wastes (dark red)
        BIOME_COLORS.put(37, 0xFF1A2A5A);  // Crimson Forest (red)
        BIOME_COLORS.put(38, 0xFF5A2A1A);  // Warped Forest (blue-green)
        BIOME_COLORS.put(39, 0xFF1A3A4A);  // Soul Sand Valley (brown)
        BIOME_COLORS.put(40, 0xFF1A4A6A);  // Basalt Deltas (dark brown)

        // End biomes
        BIOME_COLORS.put(41, 0xFF5A3A5A);  // The End (purple)
        BIOME_COLORS.put(42, 0xFF4A6A4A);  // End Midlands
        BIOME_COLORS.put(43, 0xFF3A5A3A);  // End Barrens
        BIOME_COLORS.put(44, 0xFF6A5A6A);  // Small End Islands

        // Cherry Grove (1.20)
        BIOME_COLORS.put(45, 0xFFB0A0D0);  // Cherry Grove (pink)

        // Pale Garden (1.21.4)
        BIOME_COLORS.put(46, 0xFF5A6A5A);  // Pale Garden (pale green-gray)

        // Ocean variants
        BIOME_COLORS.put(47, 0xFF7A4A2A);  // Frozen Ocean (blue-white)
        BIOME_COLORS.put(48, 0xFFD0C8C0);  // Frozen River (icy blue)
    }

    /**
     * Returns the biome color (ABGR format) for the given world coordinates.
     *
     * @param x world block X coordinate
     * @param z world block Z coordinate
     * @return ABGR color value suitable for NativeImage.setPixelABGR
     */
    public static int getBiomeColor(int x, int z) {
        return getBiomeColor(x, z, 0);
    }

    /**
     * Returns the biome color (ABGR format) for the given world coordinates.
     *
     * @param x world block X coordinate
     * @param z world block Z coordinate
     * @param dimension 0=Overworld, -1=Nether, 1=End
     * @return ABGR color value suitable for NativeImage.setPixelABGR
     */
    public static int getBiomeColor(int x, int z, int dimension) {
        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) return 0xFF2A2A2A; // dark gray for no seed

        PredictedBiome biome = getPredictedBiome(x, z, seed, dimension);
        // Use the real biome's palette color whenever the engine identified a biome,
        // even if it has no legacy id (e.g. modded/newer biomes still get a stable color).
        // Only fall back to the handmade noise approximation when worldgen is unavailable.
        if (!"unknown".equals(biome.id())) return biome.mapColorAbgr();

        int biomeId = getFallbackBiome(x, z, seed);
        return BIOME_COLORS.getOrDefault(biomeId, 0xFF2A2A2A);
    }

    /**
     * Returns the biome ID for the given world coordinates using noise-based
     * generation seeded with the world seed.
     */
    public static int getBiome(int x, int z, long seed) {
        PredictedBiome biome = getPredictedBiome(x, z, seed, 0);
        if (biome.legacyId() >= 0) return biome.legacyId();
        return getFallbackBiome(x, z, seed);
    }

    public static PredictedBiome getPredictedBiome(int x, int z, long seed, int dimension) {
        PredictedBiome biome = WorldgenEngine.getBiome(seed, dimension, x, 64, z);
        if (biome.legacyId() >= 0 || !"unknown".equals(biome.id())) return biome;
        return PredictedBiome.UNKNOWN;
    }

    /**
     * Previous handmade biome approximation. Kept only as a no-crash fallback
     * if vanilla worldgen bootstrap is unavailable in a runtime environment.
     */
    private static int getFallbackBiome(int x, int z, long seed) {
        // Use multiple layers of noise to determine biome properties
        double temperature = sampleNoise(x, z, seed, 0, 400, 2);
        double humidity = sampleNoise(x, z, seed, 1, 400, 2);
        double continentalness = sampleNoise(x, z, seed, 2, 800, 3);
        double erosion = sampleNoise(x, z, seed, 3, 600, 2);
        double weirdness = sampleNoise(x, z, seed, 4, 300, 2);

        // Determine base terrain type from continentalness
        boolean ocean = continentalness < -0.15;
        boolean deepOcean = continentalness < -0.4;
        boolean beach = continentalness > -0.15 && continentalness < 0.05;
        boolean mountain = erosion < -0.3 && continentalness > 0.2;
        boolean river = Math.abs(weirdness) > 0.4 && Math.abs(continentalness) < 0.1;

        if (ocean) {
            if (deepOcean) {
                if (temperature < -0.3) return 7;  // Deep Frozen Ocean
                if (temperature < 0.1) return 6;   // Deep Cold Ocean
                if (temperature > 0.5) return 5;   // Deep Lukewarm Ocean
                return 1;                           // Deep Ocean
            }
            if (temperature < -0.3) return 47;      // Frozen Ocean
            if (temperature < 0.1) return 4;        // Cold Ocean
            if (temperature > 0.5) return 2;        // Warm Ocean
            if (temperature > 0.3) return 3;        // Lukewarm Ocean
            return 0;                                // Ocean
        }

        if (beach) {
            if (temperature < -0.2) return 48;      // Frozen River
            return 33;                               // Beach
        }

        if (river) return 34;                       // River

        if (mountain) {
            if (temperature < -0.3) return 18;      // Snowy Slopes
            if (temperature > 0.5) return 22;       // Desert (hot mountains become desert)
            if (humidity > 0.3) return 27;          // Windswept Forest
            return 24;                               // Windswept Hills
        }

        if (temperature < -0.35) {
            // Cold biomes
            if (humidity < -0.2) return 17;         // Snowy Plains
            if (weirdness > 0.3) return 19;         // Ice Spikes
            return 16;                               // Snowy Taiga
        }

        if (temperature > 0.4) {
            // Hot biomes
            if (humidity < -0.3) {
                if (erosion < -0.2) return 23;      // Badlands
                return 22;                           // Desert
            }
            if (humidity < 0.1) return 20;          // Savanna
            if (humidity > 0.5) return 29;          // Jungle
            if (humidity > 0.3) return 30;          // Sparse Jungle
            return 31;                               // Bamboo Jungle
        }

        // Temperate biomes
        if (humidity < -0.3) {
            if (weirdness > 0.1) return 21;         // Savanna Plateau
            return 8;                                // Plains
        }
        if (humidity < 0.1) {
            if (weirdness > 0.2) return 28;         // Meadow
            return 8;                                // Plains
        }
        if (humidity < 0.3) {
            if (temperature > 0.2) return 12;       // Birch Forest
            if (weirdness > 0.2) return 45;         // Cherry Grove
            return 10;                               // Forest
        }
        if (humidity < 0.5) {
            if (temperature > 0.2) return 13;       // Old Growth Birch Forest
            return 11;                               // Dark Forest
        }
        if (weirdness > 0.2) return 46;             // Pale Garden
        return 14;                                   // Taiga
    }

    /**
     * Samples noise at the given coordinates using a seeded hash-based
     * approach with multiple octaves for a true-noise-like result.
     */
    private static double sampleNoise(int x, int z, long seed, int index, double scale, int octaves) {
        double value = 0.0;
        double amplitude = 1.0;
        double maxValue = 0.0;
        double frequency = 1.0 / scale;

        for (int i = 0; i < octaves; i++) {
            double px = x * frequency;
            double pz = z * frequency;
            value += amplitude * noise2D(px, pz, seed + index * 1000L + i * 10000L);
            maxValue += amplitude;
            amplitude *= 0.5;
            frequency *= 2.0;
        }

        return value / maxValue;
    }

    /**
     * 2D value noise using a hash function for deterministic values.
     * Uses integer coordinates internally for repeatable hashing.
     */
    private static double noise2D(double x, double z, long seed) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        double fx = x - ix;
        double fz = z - iz;

        // Smooth interpolation
        double sx = fx * fx * (3 - 2 * fx);
        double sz = fz * fz * (3 - 2 * fz);

        double n00 = hash(ix, iz, seed);
        double n10 = hash(ix + 1, iz, seed);
        double n01 = hash(ix, iz + 1, seed);
        double n11 = hash(ix + 1, iz + 1, seed);

        double nx0 = n00 + (n10 - n00) * sx;
        double nx1 = n01 + (n11 - n01) * sx;

        return nx0 + (nx1 - nx0) * sz;
    }

    /**
     * Hash function that produces a deterministic double in [-1, 1] from
     * integer coordinates and a seed.
     */
    private static double hash(int x, int z, long seed) {
        long h = seed;
        h ^= x * 341873128712L;
        h ^= z * 132897987541L;
        h ^= h >> 16;
        h *= 0x85EBCA6DC2B0DA3BL;
        h ^= h >> 13;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >> 16;

        return (h & 0x7FFFFFFFFFFFFFFFL) / (double) 0x7FFFFFFFFFFFFFFFL * 2.0 - 1.0;
    }
}
