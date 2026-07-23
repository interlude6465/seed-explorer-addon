/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.render;

/** Immutable viewport coordinates shared by map render layers. */
public record MapViewport(
    double centerX,
    double centerZ,
    double zoom,
    double width,
    double height,
    int startChunkX,
    int startChunkZ,
    int endChunkX,
    int endChunkZ
) {
    public static MapViewport create(double centerX, double centerZ, double zoom, double width, double height) {
        int startChunkX = (int) Math.floor((centerX - (width / 2.0 / zoom)) / 16.0);
        int startChunkZ = (int) Math.floor((centerZ - (height / 2.0 / zoom)) / 16.0);
        int endChunkX = (int) Math.ceil((centerX + (width / 2.0 / zoom)) / 16.0);
        int endChunkZ = (int) Math.ceil((centerZ + (height / 2.0 / zoom)) / 16.0);

        return new MapViewport(centerX, centerZ, zoom, width, height, startChunkX, startChunkZ, endChunkX, endChunkZ);
    }

    public double screenX(double blockX) {
        return (blockX - centerX) * zoom + width / 2.0;
    }

    public double screenY(double blockZ) {
        return (blockZ - centerZ) * zoom + height / 2.0;
    }

    public double worldX(double screenX) {
        return centerX + (screenX - width / 2.0) / zoom;
    }

    public double worldZ(double screenY) {
        return centerZ + (screenY - height / 2.0) / zoom;
    }

    public boolean isVisible(double screenX, double screenY, double margin) {
        return screenX >= -margin && screenX <= width + margin && screenY >= -margin && screenY <= height + margin;
    }
}
