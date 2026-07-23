/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.render;

/** Ordered render layers used by the Seed Explorer map renderer. */
public enum MapLayer {
    TERRAIN("Terrain"),
    BIOMES("Biomes"),
    STRUCTURES("Structures"),
    ORES("Ores"),
    WAYPOINTS("Waypoints"),
    PLAYER("Player"),
    CHUNK_BORDERS("Chunk Borders"),
    COORDINATES("Coordinates");

    public final String title;

    MapLayer(String title) {
        this.title = title;
    }
}
