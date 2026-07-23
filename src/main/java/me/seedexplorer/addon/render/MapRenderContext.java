/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.render;

import java.util.EnumSet;
import java.util.Set;
import me.seedexplorer.addon.structures.StructureType;

/** Per-frame state passed to each map render layer. */
public record MapRenderContext(
    MapViewport viewport,
    int mouseX,
    int mouseY,
    float delta,
    int dimension,
    EnumSet<MapLayer> enabledLayers,
    boolean showPlayerInfo,
    int generationMargin,
    int biomeTileMargin,
    int biomeOpacity,
    int oreOpacity,
    double oreMarkerScale,
    double structureMarkerScale,
    double waypointMarkerScale,
    boolean dimWaypointStructures,
    boolean loadedTerrainOnMap,
    StructureType structureSearchFilter,
    Set<Long> lootMatchedStructureKeys
) {
    public boolean enabled(MapLayer layer) {
        return enabledLayers.contains(layer);
    }
}
