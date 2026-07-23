/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.seed;

/** Represents a Minecraft world seed and its associated data. */
public class Seed {
    private final long seed;

    public Seed(long seed) {
        this.seed = seed;
    }

    public long getValue() {
        return seed;
    }
}