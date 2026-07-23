/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.structures;

/**
 * Minecraft structure types supported by the predictor.
 * Constants derived from Cubiomes library (finders.h).
 */
public enum StructureType {
    // Overworld structures
    VILLAGE("Village", 0, 10387312, 34, 26, 0),
    DESERT_PYRAMID("Desert Pyramid", 0, 14357617, 32, 24, 0),
    JUNGLE_TEMPLE("Jungle Temple", 0, 14357619, 32, 24, 0),
    WITCH_HUT("Witch Hut", 0, 14357620, 32, 24, 0),
    IGLOO("Igloo", 0, 14357618, 32, 24, 0),
    OUTPOST("Pillager Outpost", 0, 165745296, 32, 24, 0),
    MONUMENT("Ocean Monument", 0, 10387313, 32, 27, 0),
    MANSION("Woodland Mansion", 0, 10387319, 80, 60, 0),
    ANCIENT_CITY("Ancient City", 0, 20083232, 24, 16, 0),
    TRIAL_CHAMBER("Trial Chamber", 0, 94251327, 34, 22, 0),
    TRAIL_RUINS("Trail Ruins", 0, 83469867, 34, 26, 0),
    STRONGHOLD("Stronghold", 0, 0, 1, 1, 0),
    RUINED_PORTAL("Ruined Portal", 0, 34222645, 40, 25, 0),
    SHIPWRECK("Shipwreck", 0, 165745295, 24, 20, 0),
    OCEAN_RUIN("Ocean Ruin", 0, 14357621, 20, 12, 0),
    MINESHAFT("Mineshaft", 0, 0, 1, 1, 0),
    DUNGEON("Dungeon", 0, 0, 1, 1, 0),
    TREASURE("Buried Treasure", 0, 10387320, 1, 1, 0),
    AMETHYST_GEODE("Amethyst Geode", 0, 0, 1, 1, 0),

    // Nether structures
    FORTRESS("Nether Fortress", -1, 30084232, 27, 23, 0),
    BASTION("Bastion Remnant", -1, 30084232, 27, 23, 0),
    NETHER_RUINED_PORTAL("Ruined Portal", -1, 34222645, 25, 15, 0),
    NETHER_FOSSIL("Nether Fossil", -1, 14357921, 2, 1, 0),

    // End structures
    END_CITY("End City", 1, 10387313, 20, 9, 0),
    END_GATEWAY("End Gateway", 1, 40000, 1, 1, 1.0f / 700.0f);

    public final String displayName;
    public final int dimension;  // 0=Overworld, -1=Nether, 1=End
    public final int salt;
    public final int regionSize;
    public final int chunkRange;
    public final float rarity;

    StructureType(String displayName, int dimension, int salt, int regionSize, int chunkRange, float rarity) {
        this.displayName = displayName;
        this.dimension = dimension;
        this.salt = salt;
        this.regionSize = regionSize;
        this.chunkRange = chunkRange;
        this.rarity = rarity;
    }

    public boolean isLargeStructure() {
        return this == MONUMENT || this == MANSION || this == END_CITY;
    }

    public boolean hasMapPrediction() {
        return switch (this) {
            case VILLAGE, DESERT_PYRAMID, JUNGLE_TEMPLE, WITCH_HUT, IGLOO,
                 OUTPOST, MONUMENT, MANSION, ANCIENT_CITY, TRIAL_CHAMBER,
                 TRAIL_RUINS, STRONGHOLD, RUINED_PORTAL, SHIPWRECK, OCEAN_RUIN,
                 MINESHAFT, TREASURE, FORTRESS, BASTION, NETHER_RUINED_PORTAL,
                 NETHER_FOSSIL, END_CITY, END_GATEWAY -> true;
            default -> false;
        };
    }
}
