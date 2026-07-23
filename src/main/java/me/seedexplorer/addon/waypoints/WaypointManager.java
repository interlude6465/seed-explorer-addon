/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.waypoints;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages seed-explorer specific waypoints with persistence and batch operations.
 * Integrates with Meteor Client's Waypoints system to add/remove/update waypoints
 * created from predicted structure locations.
 */
public class WaypointManager {
    private static final WaypointManager INSTANCE = new WaypointManager();

    private final List<SeedWaypoint> seedWaypoints = new CopyOnWriteArrayList<>();

    private WaypointManager() {
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public static WaypointManager get() {
        return INSTANCE;
    }

    /**
     * Creates a Meteor Client waypoint at the given structure position and
     * records it in the seed waypoints list.
     *
     * @param name      The waypoint name (e.g., "Village at -500, 200")
     * @param x         Block X coordinate
     * @param z         Block Z coordinate
     * @param dimension 0=Overworld, -1=Nether, 1=End
     * @param icon      Icon name (e.g., "square", "diamond", "star")
     * @return The created Waypoint, or null if it already exists
     */
    public Waypoint createWaypoint(String name, int x, int z, int dimension, String icon) {
        return createWaypoint(name, x, z, dimension, icon, "");
    }

    /**
     * Creates a Meteor Client waypoint with optional structure type tracking.
     *
     * @param name          The waypoint name
     * @param x             Block X coordinate
     * @param z             Block Z coordinate
     * @param dimension     0=Overworld, -1=Nether, 1=End
     * @param icon          Icon name
     * @param structureType The type of structure (e.g., "Village")
     * @return The created Waypoint, or null if it already exists
     */
    public Waypoint createWaypoint(String name, int x, int z, int dimension, String icon, String structureType) {
        // Check if a seed waypoint with these coordinates already exists
        for (SeedWaypoint sw : seedWaypoints) {
            if (sw.x == x && sw.z == z && sw.dimension == dimension) {
                return null;
            }
        }

        SeedWaypoint seedWp = new SeedWaypoint(name, x, z, dimension, icon, structureType);
        Waypoint waypoint = seedWp.toWaypoint();

        boolean alreadyExists = Waypoints.get().add(waypoint);
        if (!alreadyExists) {
            seedWaypoints.add(seedWp);
        }

        return alreadyExists ? null : waypoint;
    }

    /**
     * Creates waypoints in bulk for a list of structure positions.
     * Skips positions that already have waypoints.
     *
     * @param baseName     Base name prefix for waypoints
     * @param coordinates  List of [x, z] coordinate pairs
     * @param dimension    0=Overworld, -1=Nether, 1=End
     * @param icon         Icon name to use for all waypoints
     * @param structureType Type of structure
     * @return The number of waypoints created
     */
    public int createWaypointsBulk(String baseName, List<int[]> coordinates, int dimension, String icon, String structureType) {
        int count = 0;
        for (int[] coord : coordinates) {
            String name = baseName + " [" + coord[0] + ", " + coord[1] + "]";
            Waypoint wp = createWaypoint(name, coord[0], coord[1], dimension, icon, structureType);
            if (wp != null) count++;
        }
        return count;
    }

    /**
     * Removes a seed waypoint and its corresponding Meteor Client waypoint.
     *
     * @param seedWaypoint The seed waypoint to remove
     * @return true if the waypoint was removed
     */
    public boolean removeWaypoint(SeedWaypoint seedWaypoint) {
        boolean removed = seedWaypoints.remove(seedWaypoint);
        if (removed) {
            Waypoints waypoints = Waypoints.get();
            Waypoint wp = waypoints.get(seedWaypoint.name);
            if (wp != null) {
                waypoints.remove(wp);
            }
        }
        return removed;
    }

    /**
     * Removes a waypoint at the exact seed-explorer structure position.
     *
     * @param x         Block X coordinate
     * @param z         Block Z coordinate
     * @param dimension 0=Overworld, -1=Nether, 1=End
     * @return true if a matching waypoint was removed
     */
    public boolean removeWaypointAt(int x, int z, int dimension) {
        boolean removed = false;

        for (SeedWaypoint sw : new ArrayList<>(seedWaypoints)) {
            if (sw.x == x && sw.z == z && sw.dimension == dimension) {
                removed |= removeWaypoint(sw);
            }
        }

        Waypoints waypoints = Waypoints.get();
        List<Waypoint> meteorWaypoints = new ArrayList<>();
        for (Waypoint wp : waypoints) {
            meteorWaypoints.add(wp);
        }

        for (Waypoint wp : meteorWaypoints) {
            BlockPos pos = wp.pos.get();
            int wpDim = switch (wp.dimension.get()) {
                case Overworld -> 0;
                case Nether -> -1;
                case End -> 1;
            };
            if (pos.getX() == x && pos.getZ() == z && wpDim == dimension) {
                removed |= waypoints.remove(wp);
            }
        }

        return removed;
    }

    /**
     * Removes a Meteor Client waypoint and its corresponding seed waypoint.
     *
     * @param waypoint The Meteor Client waypoint to remove
     * @return true if the waypoint was removed
     */
    public boolean removeWaypoint(Waypoint waypoint) {
        boolean removed = Waypoints.get().remove(waypoint);
        if (removed) {
            seedWaypoints.removeIf(sw ->
                sw.name.equals(waypoint.name.get()) &&
                sw.x == waypoint.pos.get().getX() &&
                sw.z == waypoint.pos.get().getZ()
            );
        }
        return removed;
    }

    /**
     * Removes all seed waypoints of a specific structure type.
     *
     * @param structureType The structure type to remove
     * @return The number of waypoints removed
     */
    public int removeByStructureType(String structureType) {
        List<SeedWaypoint> toRemove = new ArrayList<>();
        for (SeedWaypoint sw : seedWaypoints) {
            if (sw.structureType.equalsIgnoreCase(structureType)) {
                toRemove.add(sw);
            }
        }
        for (SeedWaypoint sw : toRemove) {
            removeWaypoint(sw);
        }
        return toRemove.size();
    }

    /**
     * Removes all seed waypoints in a specific dimension.
     *
     * @param dimension 0=Overworld, -1=Nether, 1=End
     * @return The number of waypoints removed
     */
    public int removeByDimension(int dimension) {
        List<SeedWaypoint> toRemove = new ArrayList<>();
        for (SeedWaypoint sw : seedWaypoints) {
            if (sw.dimension == dimension) {
                toRemove.add(sw);
            }
        }
        for (SeedWaypoint sw : toRemove) {
            removeWaypoint(sw);
        }
        return toRemove.size();
    }

    /**
     * Removes all seed waypoints.
     *
     * @return The number of waypoints removed
     */
    public int removeAll() {
        int count = seedWaypoints.size();
        for (SeedWaypoint sw : seedWaypoints) {
            Waypoints waypoints = Waypoints.get();
            Waypoint wp = waypoints.get(sw.name);
            if (wp != null) {
                waypoints.remove(wp);
            }
        }
        seedWaypoints.clear();
        return count;
    }

    /**
     * Checks if a waypoint already exists at the given coordinates.
     *
     * @param x         Block X coordinate
     * @param z         Block Z coordinate
     * @param dimension 0=Overworld, -1=Nether, 1=End
     * @return true if a waypoint exists at this position
     */
    public boolean hasWaypointAt(int x, int z, int dimension) {
        for (SeedWaypoint sw : seedWaypoints) {
            if (sw.x == x && sw.z == z && sw.dimension == dimension) {
                return true;
            }
        }
        // Also check Meteor Client's waypoints directly
        for (Waypoint wp : Waypoints.get()) {
            BlockPos pos = wp.pos.get();
            int wpDim = switch (wp.dimension.get()) {
                case Overworld -> 0;
                case Nether -> -1;
                case End -> 1;
            };
            if (pos.getX() == x && pos.getZ() == z && wpDim == dimension) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns all tracked seed waypoints.
     */
    public List<SeedWaypoint> getSeedWaypoints() {
        return new ArrayList<>(seedWaypoints);
    }

    /**
     * Returns seed waypoints filtered by dimension.
     *
     * @param dimension 0=Overworld, -1=Nether, 1=End
     */
    public List<SeedWaypoint> getSeedWaypoints(int dimension) {
        List<SeedWaypoint> result = new ArrayList<>();
        for (SeedWaypoint sw : seedWaypoints) {
            if (sw.dimension == dimension) {
                result.add(sw);
            }
        }
        return result;
    }

    /**
     * Returns seed waypoints filtered by structure type.
     *
     * @param structureType The structure type name
     */
    public List<SeedWaypoint> getSeedWaypointsByType(String structureType) {
        List<SeedWaypoint> result = new ArrayList<>();
        for (SeedWaypoint sw : seedWaypoints) {
            if (sw.structureType.equalsIgnoreCase(structureType)) {
                result.add(sw);
            }
        }
        return result;
    }

    /**
     * Returns the total number of tracked seed waypoints.
     */
    public int getWaypointCount() {
        return seedWaypoints.size();
    }

    /**
     * Refreshes the internal list by scanning Meteor Client's waypoints
     * for any we haven't tracked yet. This handles waypoints that may have
     * been added by other means.
     */
    public void refreshFromMeteorWaypoints() {
        for (Waypoint wp : Waypoints.get()) {
            SeedWaypoint sw = SeedWaypoint.fromWaypoint(wp);
            if (!seedWaypoints.contains(sw)) {
                seedWaypoints.add(sw);
            }
        }
    }

    /**
     * Synchronizes our seed waypoints to Meteor Client's waypoints.
     * Creates any missing Meteor waypoints for tracked seed waypoints.
     */
    public void syncToMeteorWaypoints() {
        for (SeedWaypoint sw : seedWaypoints) {
            Waypoint wp = sw.toWaypoint();
            Waypoints.get().add(wp);
        }
    }

    /**
     * Clears the seed waypoint list when leaving a game.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    private void onGameDisconnected(GameLeftEvent event) {
        seedWaypoints.clear();
    }

    /**
     * Refreshes from Meteor waypoints when joining a game.
     */
    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        refreshFromMeteorWaypoints();
    }
}
