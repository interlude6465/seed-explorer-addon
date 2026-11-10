package me.seedexplorer.addon.waypoints;

import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.utils.world.Dimension;
import net.minecraft.core.BlockPos;

/** Keeps Meteor's waypoint API outside the shared waypoint data model. */
public final class MeteorWaypointAdapter {
    private MeteorWaypointAdapter() {
    }

    public static Waypoint toMeteor(SeedWaypoint waypoint) {
        Dimension dimension = switch (waypoint.dimension) {
            case -1 -> Dimension.Nether;
            case 1 -> Dimension.End;
            default -> Dimension.Overworld;
        };
        return new Waypoint.Builder()
            .name(waypoint.name)
            .pos(new BlockPos(waypoint.x, 64, waypoint.z))
            .dimension(dimension)
            .icon(waypoint.icon)
            .build();
    }

    public static SeedWaypoint fromMeteor(Waypoint waypoint) {
        int dimension = switch (waypoint.dimension.get()) {
            case Overworld -> 0;
            case Nether -> -1;
            case End -> 1;
        };
        return new SeedWaypoint(
            waypoint.name.get(),
            waypoint.pos.get().getX(),
            waypoint.pos.get().getZ(),
            dimension,
            waypoint.icon.get(),
            ""
        );
    }
}
