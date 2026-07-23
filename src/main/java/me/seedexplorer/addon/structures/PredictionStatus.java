/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.structures;

import java.util.List;

/** Tracks the Overworld prediction target list and current support level. */
public final class PredictionStatus {
    private PredictionStatus() {
    }

    public static List<Entry> overworld() {
        return List.of(
            live("Villages"),
            live("Pillager Outposts"),
            live("Woodland Mansions"),
            live("Desert Pyramids"),
            live("Jungle Pyramids"),
            live("Swamp Huts"),
            live("Igloos"),
            live("Trial Chambers"),
            live("Ancient Cities"),
            live("Strongholds"),
            live("Mineshafts"),
            planned("Dungeons", "monster rooms are carver/feature attempts, not structure sets"),
            live("Ocean Monuments"),
            live("Shipwrecks"),
            live("Ocean Ruins"),
            live("Buried Treasure"),
            live("Trail Ruins"),
            live("Ruined Portals"),
            planned("Amethyst Geodes", "requires configured-feature/geode algorithm")
        );
    }

    private static Entry live(String name) {
        return new Entry(name, "map+debug", "");
    }

    private static Entry testOnly(String name, String note) {
        return new Entry(name, "not map-enabled", note);
    }

    private static Entry planned(String name, String note) {
        return new Entry(name, "planned", note);
    }

    public record Entry(String name, String status, String note) {
    }
}
