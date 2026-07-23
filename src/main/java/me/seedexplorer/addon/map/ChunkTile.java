/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.map;

import meteordevelopment.meteorclient.renderer.Texture;

/** Represents a single chunk tile on the map. */
public class ChunkTile {
    public final int x, z;
    public final int dimension;
    public Texture texture;
    volatile boolean generating;

    public ChunkTile(int x, int z) {
        this(x, z, 0);
    }

    public ChunkTile(int x, int z, int dimension) {
        this.x = x;
        this.z = z;
        this.dimension = dimension;
        this.generating = false;
    }

    /** Whether the tile has been fully generated and its texture is ready. */
    public boolean isLoaded() {
        return texture != null;
    }

    /** Whether this tile is currently being generated in the background. */
    public boolean isGenerating() {
        return generating;
    }

    /** Marks the start of async generation. Returns false if already generating. */
    public boolean markGenerating() {
        if (generating) return false;
        generating = true;
        return true;
    }

    /** Releases the GPU texture resource. */
    public void dispose() {
        if (texture != null) {
            texture.close();
            texture = null;
        }
        generating = false;
    }
}
