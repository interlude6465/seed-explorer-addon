package me.seedexplorer.standalone;

/** A persisted marker owned by the Meteor-free map frontend. */
public record StandaloneWaypoint(String id, long worldSeed, String name, int x, int z,
                                 int dimension, String structureType) {
    public StandaloneWaypoint {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Waypoint id is required");
        name = name == null || name.isBlank() ? "Waypoint [" + x + ", " + z + "]" : name.trim();
        structureType = structureType == null ? "" : structureType.trim();
    }
}
