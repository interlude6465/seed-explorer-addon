/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.events;

/** Fired when the world seed or Minecraft version changes. */
public class SeedChangeEvent extends SeedExplorerEvent {
    private static final SeedChangeEvent INSTANCE = new SeedChangeEvent();

    public long seed;
    public String version;

    public static SeedChangeEvent get(long seed, String version) {
        INSTANCE.seed = seed;
        INSTANCE.version = version;
        return INSTANCE;
    }
}