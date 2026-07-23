/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.render;

import me.seedexplorer.addon.structures.StructureType;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** Shared structure icon palette for map rendering and tooltips. */
public final class StructureColors {
    private static final Color VILLAGE = new Color(180, 60, 60, 220);
    private static final Color DESERT = new Color(220, 200, 80, 220);
    private static final Color JUNGLE = new Color(60, 200, 60, 220);
    private static final Color WITCH = new Color(120, 40, 160, 220);
    private static final Color IGLOO = new Color(200, 220, 240, 220);
    private static final Color OUTPOST = new Color(120, 120, 120, 220);
    private static final Color MONUMENT = new Color(40, 100, 220, 220);
    private static final Color MANSION = new Color(160, 80, 40, 220);
    private static final Color ANCIENT_CITY = new Color(40, 200, 180, 220);
    private static final Color TRIAL_CHAMBER = new Color(80, 170, 210, 220);
    private static final Color TRAIL_RUINS = new Color(170, 120, 70, 220);
    private static final Color STRONGHOLD = new Color(140, 160, 90, 220);
    private static final Color PORTAL = new Color(180, 40, 200, 220);
    private static final Color SHIPWRECK = new Color(160, 120, 60, 220);
    private static final Color RUIN = new Color(100, 140, 180, 220);
    private static final Color MINESHAFT = new Color(150, 110, 70, 220);
    private static final Color DUNGEON = new Color(130, 130, 130, 220);
    private static final Color TREASURE = new Color(255, 215, 0, 220);
    private static final Color GEODE = new Color(175, 120, 230, 220);
    private static final Color FORTRESS = new Color(180, 40, 40, 220);
    private static final Color BASTION = new Color(120, 60, 140, 220);
    private static final Color END_CITY = new Color(160, 60, 200, 220);

    private StructureColors() {
    }

    public static Color get(StructureType type) {
        return switch (type) {
            case VILLAGE -> VILLAGE;
            case DESERT_PYRAMID -> DESERT;
            case JUNGLE_TEMPLE -> JUNGLE;
            case WITCH_HUT -> WITCH;
            case IGLOO -> IGLOO;
            case OUTPOST -> OUTPOST;
            case MONUMENT -> MONUMENT;
            case MANSION -> MANSION;
            case ANCIENT_CITY -> ANCIENT_CITY;
            case TRIAL_CHAMBER -> TRIAL_CHAMBER;
            case TRAIL_RUINS -> TRAIL_RUINS;
            case STRONGHOLD -> STRONGHOLD;
            case RUINED_PORTAL -> PORTAL;
            case SHIPWRECK -> SHIPWRECK;
            case OCEAN_RUIN -> RUIN;
            case MINESHAFT -> MINESHAFT;
            case DUNGEON -> DUNGEON;
            case TREASURE -> TREASURE;
            case AMETHYST_GEODE -> GEODE;
            case FORTRESS -> FORTRESS;
            case BASTION -> BASTION;
            case NETHER_RUINED_PORTAL -> PORTAL;
            case NETHER_FOSSIL -> DUNGEON;
            case END_CITY -> END_CITY;
            case END_GATEWAY -> STRONGHOLD;
        };
    }
}
