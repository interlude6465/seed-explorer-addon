/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.waypoints;

import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.utils.misc.ISerializable;
import meteordevelopment.meteorclient.utils.world.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Extended waypoint data for seed-explorer waypoints.
 * Tracks additional metadata for waypoints created from predicted structures.
 */
public class SeedWaypoint implements ISerializable<SeedWaypoint> {
    public String name;
    public int x, z;
    public int dimension; // 0=Overworld, -1=Nether, 1=End
    public String icon;
    public String structureType; // e.g., "Village", "Desert Pyramid", etc.

    public SeedWaypoint(String name, int x, int z, int dimension, String icon) {
        this(name, x, z, dimension, icon, "");
    }

    public SeedWaypoint(String name, int x, int z, int dimension, String icon, String structureType) {
        this.name = name;
        this.x = x;
        this.z = z;
        this.dimension = dimension;
        this.icon = icon;
        this.structureType = structureType;
    }

    public SeedWaypoint(Tag tag) {
        fromTag((CompoundTag) tag);
    }

    /**
     * Converts this seed waypoint to a Meteor Client Waypoint.
     */
    public Waypoint toWaypoint() {
        Dimension dim = switch (dimension) {
            case 0 -> Dimension.Overworld;
            case -1 -> Dimension.Nether;
            case 1 -> Dimension.End;
            default -> Dimension.Overworld;
        };

        return new Waypoint.Builder()
            .name(name)
            .pos(new BlockPos(x, 64, z))
            .dimension(dim)
            .icon(icon)
            .build();
    }

    /**
     * Creates a SeedWaypoint from a Meteor Client Waypoint.
     */
    public static SeedWaypoint fromWaypoint(Waypoint waypoint) {
        int dim = switch (waypoint.dimension.get()) {
            case Overworld -> 0;
            case Nether -> -1;
            case End -> 1;
        };

        return new SeedWaypoint(
            waypoint.name.get(),
            waypoint.pos.get().getX(),
            waypoint.pos.get().getZ(),
            dim,
            waypoint.icon.get(),
            ""
        );
    }

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putInt("x", x);
        tag.putInt("z", z);
        tag.putInt("dimension", dimension);
        tag.putString("icon", icon);
        tag.putString("structureType", structureType);
        return tag;
    }

    @Override
    public SeedWaypoint fromTag(CompoundTag tag) {
        name = tag.getStringOr("name", "");
        x = tag.getIntOr("x", 0);
        z = tag.getIntOr("z", 0);
        dimension = tag.getIntOr("dimension", 0);
        icon = tag.getStringOr("icon", "square");
        structureType = tag.getStringOr("structureType", "");
        return this;
    }

    /**
     * Returns the dimension as a string for display.
     */
    public String getDimensionName() {
        return switch (dimension) {
            case -1 -> "Nether";
            case 1 -> "End";
            default -> "Overworld";
        };
    }

    /**
     * Returns the position as a formatted coordinate string.
     */
    public String getCoordsString() {
        return x + ", " + z;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SeedWaypoint that = (SeedWaypoint) o;
        return x == that.x && z == that.z && dimension == that.dimension && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + x;
        result = 31 * result + z;
        result = 31 * result + dimension;
        return result;
    }

    @Override
    public String toString() {
        return name + " [" + getCoordsString() + "] (" + getDimensionName() + ")";
    }
}