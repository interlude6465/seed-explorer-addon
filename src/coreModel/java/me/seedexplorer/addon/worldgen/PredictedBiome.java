/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.worldgen;

/** Biome prediction result used by the map, search, and debug tools. */
public record PredictedBiome(int legacyId, String id, String displayName, int mapColorAbgr) {
    public static final PredictedBiome UNKNOWN = new PredictedBiome(-1, "unknown", "Unknown", 0xFF2A2A2A);
}
