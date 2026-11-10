/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.render;

import me.seedexplorer.addon.structures.StructureType;

/** Shared structure icon palette for map rendering and tooltips. */
public final class StructureColors {
    private static final RgbaColor VILLAGE = new RgbaColor(180, 60, 60, 220);
    private static final RgbaColor DESERT = new RgbaColor(220, 200, 80, 220);
    private static final RgbaColor JUNGLE = new RgbaColor(60, 200, 60, 220);
    private static final RgbaColor WITCH = new RgbaColor(120, 40, 160, 220);
    private static final RgbaColor IGLOO = new RgbaColor(200, 220, 240, 220);
    private static final RgbaColor OUTPOST = new RgbaColor(120, 120, 120, 220);
    private static final RgbaColor MONUMENT = new RgbaColor(40, 100, 220, 220);
    private static final RgbaColor MANSION = new RgbaColor(160, 80, 40, 220);
    private static final RgbaColor ANCIENT_CITY = new RgbaColor(40, 200, 180, 220);
    private static final RgbaColor TRIAL_CHAMBER = new RgbaColor(80, 170, 210, 220);
    private static final RgbaColor TRAIL_RUINS = new RgbaColor(170, 120, 70, 220);
    private static final RgbaColor ABANDONED_CAMP = new RgbaColor(214, 142, 72, 220);
    private static final RgbaColor STRONGHOLD = new RgbaColor(140, 160, 90, 220);
    private static final RgbaColor PORTAL = new RgbaColor(180, 40, 200, 220);
    private static final RgbaColor SHIPWRECK = new RgbaColor(160, 120, 60, 220);
    private static final RgbaColor RUIN = new RgbaColor(100, 140, 180, 220);
    private static final RgbaColor MINESHAFT = new RgbaColor(150, 110, 70, 220);
    private static final RgbaColor DUNGEON = new RgbaColor(130, 130, 130, 220);
    private static final RgbaColor TREASURE = new RgbaColor(255, 215, 0, 220);
    private static final RgbaColor GEODE = new RgbaColor(175, 120, 230, 220);
    private static final RgbaColor FORTRESS = new RgbaColor(180, 40, 40, 220);
    private static final RgbaColor BASTION = new RgbaColor(120, 60, 140, 220);
    private static final RgbaColor END_CITY = new RgbaColor(160, 60, 200, 220);

    private StructureColors() {
    }

    public static RgbaColor get(StructureType type) {
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
            case ABANDONED_CAMP -> ABANDONED_CAMP;
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
