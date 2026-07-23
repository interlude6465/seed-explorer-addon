/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.core.BlockPos;

import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructurePredictor;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.waypoints.SeedWaypoint;
import me.seedexplorer.addon.waypoints.WaypointManager;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class WaypointCommand extends Command {
    public WaypointCommand() {
        super("wp", "Manage seed explorer waypoints. Alias: waypoint, seedwp");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        // List all waypoints
        builder.then(literal("list")
            .executes(ctx -> {
                List<SeedWaypoint> waypoints = WaypointManager.get().getSeedWaypoints();
                if (waypoints.isEmpty()) {
                    info("No seed explorer waypoints.");
                    return SINGLE_SUCCESS;
                }
                info("Seed Explorer waypoints (highlight)%d(default):", waypoints.size());
                for (SeedWaypoint sw : waypoints) {
                    info("  (highlight)%s(default) - %s (%s)", sw.name, sw.getCoordsString(), sw.getDimensionName());
                }
                return SINGLE_SUCCESS;
            })
            .then(literal("dimension")
                .then(literal("overworld").executes(ctx -> listByDimension(0)))
                .then(literal("nether").executes(ctx -> listByDimension(-1)))
                .then(literal("end").executes(ctx -> listByDimension(1)))
            )
            .then(literal("type")
                .then(argument("structureType", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String type = ctx.getArgument("structureType", String.class);
                        List<SeedWaypoint> waypoints = WaypointManager.get().getSeedWaypointsByType(type);
                        if (waypoints.isEmpty()) {
                            info("No waypoints of type (highlight)%s(default).", type);
                        } else {
                            info("Waypoints of type (highlight)%s(default):", type);
                            for (SeedWaypoint sw : waypoints) {
                                info("  (highlight)%s(default) - %s", sw.name, sw.getCoordsString());
                            }
                        }
                        return SINGLE_SUCCESS;
                    })
                )
            )
        );

        // Add waypoint at current position
        builder.then(literal("add")
            .executes(ctx -> {
                BlockPos pos = mc.player.blockPosition();
                int dimension = getCurrentDimension();
                String name = "Waypoint [" + pos.getX() + ", " + pos.getZ() + "]";
                Waypoint wp = WaypointManager.get().createWaypoint(name, pos.getX(), pos.getZ(), dimension, "square");
                if (wp != null) {
                    info("Created waypoint (highlight)%s(default) at %d, %d.", name, pos.getX(), pos.getZ());
                } else {
                    info("A waypoint already exists at this position.");
                }
                return SINGLE_SUCCESS;
            })
            .then(argument("name", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String name = ctx.getArgument("name", String.class);
                    BlockPos pos = mc.player.blockPosition();
                    int dimension = getCurrentDimension();
                    Waypoint wp = WaypointManager.get().createWaypoint(name, pos.getX(), pos.getZ(), dimension, "square");
                    if (wp != null) {
                        info("Created waypoint (highlight)%s(default) at %d, %d.", name, pos.getX(), pos.getZ());
                    } else {
                        info("A waypoint with that name already exists.");
                    }
                    return SINGLE_SUCCESS;
                })
            )
        );

        // Remove waypoint
        builder.then(literal("remove")
            .then(argument("name", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String name = ctx.getArgument("name", String.class);
                    List<SeedWaypoint> all = WaypointManager.get().getSeedWaypoints();
                    int removed = 0;
                    for (SeedWaypoint sw : all) {
                        if (sw.name.equalsIgnoreCase(name)) {
                            WaypointManager.get().removeWaypoint(sw);
                            removed++;
                        }
                    }
                    if (removed > 0) {
                        info("Removed (highlight)%d(default) waypoint(s) named (highlight)%s(default).", removed, name);
                    } else {
                        info("No waypoint found with name (highlight)%s(default).", name);
                    }
                    return SINGLE_SUCCESS;
                })
            )
        );

        // Remove all waypoints
        builder.then(literal("clear")
            .executes(ctx -> {
                int count = WaypointManager.get().removeAll();
                info("Removed all (highlight)%d(default) seed explorer waypoints.", count);
                return SINGLE_SUCCESS;
            })
        );

        // Remove waypoints by type
        builder.then(literal("remove-type")
            .then(argument("structureType", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String type = ctx.getArgument("structureType", String.class);
                    int count = WaypointManager.get().removeByStructureType(type);
                    info("Removed (highlight)%d(default) waypoints of type (highlight)%s(default).", count, type);
                    return SINGLE_SUCCESS;
                })
            )
        );

        // Batch create waypoints for predicted structures in a region
        builder.then(literal("scan")
            .then(argument("radius", IntegerArgumentType.integer(1, 20))
                .executes(ctx -> {
                    int radius = ctx.getArgument("radius", Integer.class);
                    BlockPos playerPos = mc.player.blockPosition();
                    int dimension = getCurrentDimension();

                    // Scan a square of 32-chunk (512-block) regions around the player.
                    int playerRegionX = Math.floorDiv(playerPos.getX(), 512);
                    int playerRegionZ = Math.floorDiv(playerPos.getZ(), 512);

                    List<GeneratedStructure> structures = StructurePredictor.predict(
                        playerRegionX - radius, playerRegionZ - radius,
                        playerRegionX + radius, playerRegionZ + radius, dimension);

                    // Group predictions by their actual type so each waypoint is labelled correctly.
                    Map<StructureType, List<int[]>> coordsByType = new EnumMap<>(StructureType.class);
                    for (GeneratedStructure gs : structures) {
                        coordsByType.computeIfAbsent(gs.type, k -> new ArrayList<>()).add(new int[]{gs.x, gs.z});
                    }

                    int count = 0;
                    for (Map.Entry<StructureType, List<int[]>> entry : coordsByType.entrySet()) {
                        StructureType type = entry.getKey();
                        count += WaypointManager.get().createWaypointsBulk(
                            type.displayName, entry.getValue(), dimension, getIconForType(type), type.displayName
                        );
                    }

                    info("Created (highlight)%d(default) waypoints in a (highlight)%d(default)-region radius.", count, radius);
                    return SINGLE_SUCCESS;
                })
            )
        );

        // Count waypoints
        builder.then(literal("count")
            .executes(ctx -> {
                int total = WaypointManager.get().getWaypointCount();
                int ow = WaypointManager.get().getSeedWaypoints(0).size();
                int nether = WaypointManager.get().getSeedWaypoints(-1).size();
                int end = WaypointManager.get().getSeedWaypoints(1).size();
                info("Waypoint count:");
                info("  Total: (highlight)%d", total);
                info("  Overworld: (highlight)%d", ow);
                info("  Nether: (highlight)%d", nether);
                info("  End: (highlight)%d", end);
                return SINGLE_SUCCESS;
            })
        );

        // Refresh from Meteor waypoints
        builder.then(literal("refresh")
            .executes(ctx -> {
                WaypointManager.get().refreshFromMeteorWaypoints();
                info("Refreshed from Meteor waypoints. Total: (highlight)%d(default).", WaypointManager.get().getWaypointCount());
                return SINGLE_SUCCESS;
            })
        );

        // Sync to Meteor waypoints
        builder.then(literal("sync")
            .executes(ctx -> {
                WaypointManager.get().syncToMeteorWaypoints();
                info("Synced seed waypoints to Meteor waypoints.");
                return SINGLE_SUCCESS;
            })
        );
    }

    private int listByDimension(int dimension) {
        List<SeedWaypoint> waypoints = WaypointManager.get().getSeedWaypoints(dimension);
        String dimName = switch (dimension) {
            case -1 -> "Nether";
            case 1 -> "End";
            default -> "Overworld";
        };
        if (waypoints.isEmpty()) {
            info("No seed explorer waypoints in %s.", dimName);
        } else {
            info("Seed Explorer waypoints in %s (highlight)%d(default):", dimName, waypoints.size());
            for (SeedWaypoint sw : waypoints) {
                info("  (highlight)%s(default) - %s", sw.name, sw.getCoordsString());
            }
        }
        return SINGLE_SUCCESS;
    }

    private int getCurrentDimension() {
        return switch (PlayerUtils.getDimension()) {
            case Overworld -> 0;
            case Nether -> -1;
            case End -> 1;
        };
    }

    private String getIconForType(StructureType type) {
        return switch (type) {
            case VILLAGE -> "square";
            case DESERT_PYRAMID, JUNGLE_TEMPLE, WITCH_HUT, IGLOO -> "triangle";
            case OUTPOST -> "skull";
            case MONUMENT, MANSION -> "diamond";
            case ANCIENT_CITY -> "star";
            case FORTRESS, BASTION -> "skull";
            case END_CITY -> "star";
            default -> "circle";
        };
    }
}