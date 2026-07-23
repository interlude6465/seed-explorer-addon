/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.render;

import me.seedexplorer.addon.ore.OrePatch;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.waypoints.SeedWaypoint;

import java.util.List;

/** Data produced by one map render frame for UI interactions. */
public record MapRenderResult(
    List<GeneratedStructure> structures,
    List<SeedWaypoint> waypoints,
    List<OrePatch> ores,
    GeneratedStructure hoveredStructure,
    SeedWaypoint hoveredWaypoint
) {
}
