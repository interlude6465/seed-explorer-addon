package me.seedexplorer.addon.render;

/** Camera target and orbit distance resolved from rendered terrain or structure bounds. */
public record TerrainFocusTarget(double x, double y, double z, double distance) {
}
