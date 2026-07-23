/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.ore;

/**
 * Represents a predicted ore patch location.
 * Contains the block position and ore type.
 */
public class OrePatch {
    public final int x, y, z;
    public final OreType type;
    public final boolean exact;
    public final String source;

    public OrePatch(int x, int y, int z, OreType type) {
        this(x, y, z, type, false);
    }

    public OrePatch(int x, int y, int z, OreType type, boolean exact) {
        this(x, y, z, type, exact, exact ? "loaded" : "placed_feature");
    }

    public OrePatch(int x, int y, int z, OreType type, boolean exact, String source) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = type;
        this.exact = exact;
        this.source = source == null || source.isBlank() ? (exact ? "loaded" : "placed_feature") : source;
    }

    public OrePatch(int x, int z, OreType type) {
        this(x, type.minY, z, type);
    }

    public String getCoordsString() {
        return x + ", " + y + ", " + z;
    }
}
