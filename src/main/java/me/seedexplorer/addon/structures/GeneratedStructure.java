/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.structures;

/**
 * Represents a generated structure location found during seed analysis.
 * Contains position, type, and whether it has been verified.
 */
public class GeneratedStructure {
    public static final int UNKNOWN_Y = Integer.MIN_VALUE;

    public final int x, y, z;
    public final int startChunkX, startChunkZ;
    public final StructureType type;
    public final String variant;
    public final boolean hasShip;
    public boolean verified;

    public GeneratedStructure(int x, int z, StructureType type) {
        this(x, z, type, "", false);
    }

    public GeneratedStructure(int x, int z, StructureType type, String variant) {
        this(x, z, type, variant, false);
    }

    public GeneratedStructure(int x, int z, StructureType type, String variant, boolean hasShip) {
        this(x, z, Math.floorDiv(x, 16), Math.floorDiv(z, 16), type, variant, hasShip);
    }

    public GeneratedStructure(int x, int z, int startChunkX, int startChunkZ,
                              StructureType type, String variant, boolean hasShip) {
        this(x, UNKNOWN_Y, z, startChunkX, startChunkZ, type, variant, hasShip);
    }

    public GeneratedStructure(int x, int y, int z, int startChunkX, int startChunkZ,
                              StructureType type, String variant, boolean hasShip) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.startChunkX = startChunkX;
        this.startChunkZ = startChunkZ;
        this.type = type;
        this.variant = variant == null ? "" : variant;
        this.hasShip = hasShip;
        this.verified = false;
    }

    public String getCoordsString() {
        return y == UNKNOWN_Y ? x + ", " + z : x + ", " + y + ", " + z;
    }

    public String displayName() {
        if (type == StructureType.END_CITY && hasShip) return type.displayName + " (Ship)";
        if (type == StructureType.DUNGEON && variant.startsWith("minecraft:")) {
            String mob = variant.substring("minecraft:".length()).replace('_', ' ');
            return type.displayName + " (" + Character.toUpperCase(mob.charAt(0)) + mob.substring(1) + " Spawner)";
        }
        if (variant.startsWith("minecraft:")) return type.displayName;
        if (!variant.isBlank()) return type.displayName + " (" + variant + ")";
        return type.displayName;
    }
}
