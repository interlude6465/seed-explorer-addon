/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.render;

import com.mojang.blaze3d.textures.FilterMode;
import meteordevelopment.meteorclient.renderer.Texture;

import java.util.HashMap;
import java.util.Map;

/** Loads small UI icons packaged with the addon. */
public final class UiIcons {
    private static final String ROOT = "/assets/meteor-seed-explorer/textures/ui/";
    private static final Map<String, Texture> ICONS = new HashMap<>();
    private static boolean loaded;

    private UiIcons() {
    }

    public static Texture get(String key) {
        if (!loaded) load();
        return ICONS.get(key);
    }

    private static void load() {
        loaded = true;
        add("terrain", "terrain.png");
        add("ores", "ore_diamond.png");
        add("waypoints", "waypoint.png");
        add("player", "player.png");
    }

    private static void add(String key, String fileName) {
        Texture texture = Texture.readResource(ROOT + fileName, false, FilterMode.NEAREST);
        if (texture != null) ICONS.put(key, texture);
    }
}
