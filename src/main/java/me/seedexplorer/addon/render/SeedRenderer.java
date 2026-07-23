/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.render;

import me.seedexplorer.addon.map.BiomeGenerator;
import me.seedexplorer.addon.map.ChunkTile;
import me.seedexplorer.addon.map.MinimapManager;
import me.seedexplorer.addon.ore.OreCache;
import me.seedexplorer.addon.ore.OrePatch;
import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.structures.StructureCache;
import me.seedexplorer.addon.waypoints.SeedWaypoint;
import me.seedexplorer.addon.waypoints.WaypointManager;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.Texture;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Handles ordered map layer rendering for the Seed Explorer screen. */
public class SeedRenderer {
    public static final Color WAYPOINT_MARKER_COLOR = new Color(255, 255, 100, 220);
    public static final Color WAYPOINT_HOVER_COLOR = new Color(255, 255, 180, 255);

    private static final Color TERRAIN_BACKGROUND = new Color(18, 20, 24, 255);
    private static final Color CHUNK_BORDER_COLOR = new Color(255, 255, 255, 40);
    private static final Color CENTER_CROSSHAIR_COLOR = new Color(255, 255, 255, 210);
    private static final Color PLAYER_OUTLINE_COLOR = new Color(12, 16, 22, 220);
    private static final Color STATUS_BAR_COLOR = new Color(12, 14, 18, 185);
    private static final int MAX_DETAILED_BIOME_TILES = 8192;
    private static final int MAX_OVERVIEW_COLOR_CACHE = 65536;
    private static final double OVERVIEW_TARGET_CELL_SIZE = 9.0;
    private static final int MAX_STRUCTURE_CACHE_REGIONS = 256;
    private static final double TREASURE_MAX_METERS_PER_PIXEL = 2.8;
    private static final int MAX_LOADED_TERRAIN_SAMPLES = 12000;
    private static final double LOADED_TERRAIN_MIN_ZOOM = 0.14;
    private static final int MIN_NETHER_FOSSIL_CLUSTER_BLOCKS = 192;
    private static final double ICON_TEXTURE_PADDING = 1.18;

    private final OreOverlay oreOverlay = new OreOverlay();
    private final Map<OverviewBiomeKey, Integer> overviewBiomeColorCache = new HashMap<>();

    public MapRenderResult render(MapRenderContext context, GuiTheme theme) {
        List<GeneratedStructure> structures = context.enabled(MapLayer.STRUCTURES)
            ? getVisibleStructures(context)
            : List.of();
        List<SeedWaypoint> waypoints = context.enabled(MapLayer.WAYPOINTS)
            ? getVisibleWaypoints(context)
            : List.of();
        List<OrePatch> ores = context.enabled(MapLayer.ORES)
            ? getVisibleOres(context)
            : List.of();

        GeneratedStructure hoveredStructure = null;
        SeedWaypoint hoveredWaypoint = null;

        if (context.enabled(MapLayer.TERRAIN)) renderTerrain(context);
        if (context.enabled(MapLayer.BIOMES)) renderBiomes(context);
        if (context.enabled(MapLayer.TERRAIN) && context.loadedTerrainOnMap()) renderLoadedTerrain(context);
        if (context.enabled(MapLayer.ORES)) oreOverlay.render(context, ores);
        if (context.enabled(MapLayer.STRUCTURES)) hoveredStructure = renderStructures(context, structures);
        if (context.enabled(MapLayer.WAYPOINTS)) hoveredWaypoint = renderWaypoints(context, waypoints, hoveredStructure);
        if (context.enabled(MapLayer.PLAYER)) {
            renderPlayer(context);
            if (context.showPlayerInfo()) renderPlayerInfo(context, theme);
        }
        if (context.enabled(MapLayer.CHUNK_BORDERS)) renderChunkBorders(context);
        if (context.enabled(MapLayer.COORDINATES)) renderCoordinates(context, theme);

        return new MapRenderResult(structures, waypoints, ores, hoveredStructure, hoveredWaypoint);
    }

    public List<GeneratedStructure> queryStructures(MapRenderContext context) {
        return context.enabled(MapLayer.STRUCTURES) ? getVisibleStructures(context) : List.of();
    }

    public List<SeedWaypoint> queryWaypoints(MapRenderContext context) {
        return context.enabled(MapLayer.WAYPOINTS) ? getVisibleWaypoints(context) : List.of();
    }

    public GeneratedStructure pickStructure(MapRenderContext context, List<GeneratedStructure> structures) {
        if (structures == null || structures.isEmpty()) return null;

        MapViewport viewport = context.viewport();
        double metersPerPixel = metersPerPixel(viewport);
        Map<Long, Integer> overlapCounts = new HashMap<>();
        GeneratedStructure picked = null;
        double bestDistanceSquared = Double.MAX_VALUE;

        List<GeneratedStructure> ordered = new ArrayList<>(structures);
        ordered.sort((a, b) -> {
            int z = Integer.compare(a.z, b.z);
            if (z != 0) return z;
            int x = Integer.compare(a.x, b.x);
            if (x != 0) return x;
            return Integer.compare(a.type.ordinal(), b.type.ordinal());
        });

        for (GeneratedStructure structure : ordered) {
            if (!shouldRenderStructure(structure, metersPerPixel)) continue;
            if (context.structureSearchFilter() != null && structure.type != context.structureSearchFilter()) continue;
            if (!SeedManager.get().getProfileStructure(structure.type.name(), true)) continue;

            double sx = viewport.screenX(structure.x);
            double sy = viewport.screenY(structure.z);
            double iconSize = structureIconSize(structure.type, metersPerPixel, context.structureMarkerScale());
            int overlapIndex = overlapCounts.merge(overlapKey(structure, iconSize, viewport), 1, Integer::sum) - 1;
            double displayX = sx + overlapOffset(overlapIndex, iconSize);
            double displayY = sy;
            double paddedSize = iconSize * ICON_TEXTURE_PADDING;
            double paddedHalf = paddedSize / 2.0;
            if (!viewport.isVisible(displayX, displayY, paddedHalf + 4)) continue;

            double dx = context.mouseX() - displayX;
            double dy = context.mouseY() - displayY;
            double hitRadius = 10.0 + paddedHalf;
            double distanceSquared = dx * dx + dy * dy;
            if (distanceSquared <= hitRadius * hitRadius && distanceSquared < bestDistanceSquared) {
                picked = structure;
                bestDistanceSquared = distanceSquared;
            }
        }

        return picked;
    }

    private List<GeneratedStructure> getVisibleStructures(MapRenderContext context) {
        MapViewport viewport = context.viewport();
        int margin = Math.max(0, context.generationMargin());
        boolean includeBuriedTreasure = shouldIncludeBuriedTreasure(context);
        boolean includeMineshafts = shouldIncludeMineshafts(context);
        int rMinX = Math.floorDiv(viewport.startChunkX() - margin, 64);
        int rMinZ = Math.floorDiv(viewport.startChunkZ() - margin, 64);
        int rMaxX = Math.floorDiv(viewport.endChunkX() + margin, 64);
        int rMaxZ = Math.floorDiv(viewport.endChunkZ() + margin, 64);
        int regionsX = rMaxX - rMinX + 1;
        int regionsZ = rMaxZ - rMinZ + 1;
        if (Math.max(0, regionsX) * Math.max(0, regionsZ) > MAX_STRUCTURE_CACHE_REGIONS) return List.of();

        List<GeneratedStructure> structures = StructureCache.get().getStructures(
            viewport.startChunkX() - margin,
            viewport.startChunkZ() - margin,
            viewport.endChunkX() + margin,
            viewport.endChunkZ() + margin,
            context.dimension(),
            includeBuriedTreasure,
            includeMineshafts
        );
        if (context.lootMatchedStructureKeys() != null) {
            structures = structures.stream()
                .filter(s -> context.lootMatchedStructureKeys().contains(structureKey(context.dimension(), s)))
                .toList();
        }
        return context.dimension() == -1 ? clusterNetherFossils(structures, viewport) : structures;
    }

    public static long structureKey(int dimension, GeneratedStructure structure) {
        long key = 1469598103934665603L;
        key = (key ^ dimension) * 1099511628211L;
        key = (key ^ structure.type.ordinal()) * 1099511628211L;
        key = (key ^ structure.x) * 1099511628211L;
        key = (key ^ structure.z) * 1099511628211L;
        key = (key ^ structure.startChunkX) * 1099511628211L;
        key = (key ^ structure.startChunkZ) * 1099511628211L;
        return key;
    }

    private boolean shouldIncludeMineshafts(MapRenderContext context) {
        if (context.dimension() != 0) return false;
        if (context.structureSearchFilter() == StructureType.MINESHAFT) return true;
        if (!SeedManager.get().getProfileStructure(StructureType.MINESHAFT.name(), true)) return false;
        return metersPerPixel(context.viewport()) <= maxVisibleMetersPerPixel(StructureType.MINESHAFT);
    }

    private List<GeneratedStructure> clusterNetherFossils(List<GeneratedStructure> structures, MapViewport viewport) {
        if (structures.isEmpty()) return structures;

        List<GeneratedStructure> result = new ArrayList<>();
        Map<Long, FossilCluster> clusters = new HashMap<>();
        int clusterBlocks = netherFossilClusterBlocks(viewport);
        for (GeneratedStructure structure : structures) {
            if (structure.type != StructureType.NETHER_FOSSIL) {
                result.add(structure);
                continue;
            }

            int cellX = Math.floorDiv(structure.x, clusterBlocks);
            int cellZ = Math.floorDiv(structure.z, clusterBlocks);
            long key = ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
            clusters.computeIfAbsent(key, ignored -> new FossilCluster()).add(structure);
        }

        for (FossilCluster cluster : clusters.values()) {
            result.add(cluster.toStructure());
        }

        return result;
    }

    private int netherFossilClusterBlocks(MapViewport viewport) {
        double metersPerPixel = metersPerPixel(viewport);
        if (metersPerPixel >= 36.0) {
            double visibleBlocks = Math.max(viewport.width(), viewport.height()) * metersPerPixel;
            return Math.max(1536, (int) Math.ceil(visibleBlocks));
        }
        if (metersPerPixel >= 16.0) return 1024;
        if (metersPerPixel >= 6.0) return 512;
        return MIN_NETHER_FOSSIL_CLUSTER_BLOCKS;
    }

    private boolean shouldIncludeBuriedTreasure(MapRenderContext context) {
        return context.dimension() == 0 && metersPerPixel(context.viewport()) <= TREASURE_MAX_METERS_PER_PIXEL;
    }

    private List<SeedWaypoint> getVisibleWaypoints(MapRenderContext context) {
        MapViewport viewport = context.viewport();
        int margin = 10;
        int blockMinX = (viewport.startChunkX() - margin) * 16;
        int blockMinZ = (viewport.startChunkZ() - margin) * 16;
        int blockMaxX = (viewport.endChunkX() + margin) * 16;
        int blockMaxZ = (viewport.endChunkZ() + margin) * 16;

        List<SeedWaypoint> result = new ArrayList<>();
        for (SeedWaypoint waypoint : WaypointManager.get().getSeedWaypoints(context.dimension())) {
            if (waypoint.x >= blockMinX && waypoint.x <= blockMaxX && waypoint.z >= blockMinZ && waypoint.z <= blockMaxZ) {
                result.add(waypoint);
            }
        }
        return result;
    }

    private List<OrePatch> getVisibleOres(MapRenderContext context) {
        MapViewport viewport = context.viewport();
        if (metersPerPixel(viewport) > 4.0) return List.of();

        int margin = Math.max(0, context.generationMargin());
        int chunksX = viewport.endChunkX() - viewport.startChunkX() + 1 + margin * 2;
        int chunksZ = viewport.endChunkZ() - viewport.startChunkZ() + 1 + margin * 2;
        if (Math.max(0, chunksX) * Math.max(0, chunksZ) > 256) return List.of();

        return OreCache.get().getOres(
            viewport.startChunkX() - margin,
            viewport.startChunkZ() - margin,
            viewport.endChunkX() + margin,
            viewport.endChunkZ() + margin,
            context.dimension()
        );
    }

    private void renderTerrain(MapRenderContext context) {
        MapViewport viewport = context.viewport();
        Renderer2D renderer = Renderer2D.COLOR;
        renderer.begin();
        Color background = context.dimension() == 1 ? new Color(18, 10, 23, 255) : TERRAIN_BACKGROUND;
        renderer.quad(0, 0, viewport.width(), viewport.height(), background);
        renderer.render();
    }

    private void renderLoadedTerrain(MapRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || dimensionId() != context.dimension()) return;

        MapViewport viewport = context.viewport();
        if (viewport.zoom() < LOADED_TERRAIN_MIN_ZOOM) return;

        double stepBlocks = loadedTerrainStep(viewport.zoom());
        int startX = (int) Math.floor(viewport.worldX(0) / stepBlocks) * (int) stepBlocks;
        int startZ = (int) Math.floor(viewport.worldZ(0) / stepBlocks) * (int) stepBlocks;
        int endX = (int) Math.ceil(viewport.worldX(viewport.width()) / stepBlocks) * (int) stepBlocks;
        int endZ = (int) Math.ceil(viewport.worldZ(viewport.height()) / stepBlocks) * (int) stepBlocks;

        int samplesX = Math.max(0, (int) ((endX - startX) / stepBlocks) + 1);
        int samplesZ = Math.max(0, (int) ((endZ - startZ) / stepBlocks) + 1);
        if (samplesX * samplesZ > MAX_LOADED_TERRAIN_SAMPLES) return;

        Renderer2D renderer = Renderer2D.COLOR;
        renderer.begin();
        for (int z = startZ; z <= endZ; z += (int) stepBlocks) {
            for (int x = startX; x <= endX; x += (int) stepBlocks) {
                Color color = loadedTerrainColor(mc.level, x, z);
                if (color == null) continue;

                double sx = viewport.screenX(x);
                double sy = viewport.screenY(z);
                double size = Math.ceil(stepBlocks * viewport.zoom()) + 1.0;
                renderer.quad(sx, sy, size, size, color);
            }
        }
        renderer.render();
    }

    private int loadedTerrainStep(double zoom) {
        if (zoom >= 2.0) return 1;
        if (zoom >= 0.8) return 2;
        if (zoom >= 0.35) return 4;
        return 8;
    }

    private Color loadedTerrainColor(Level level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        if (chunk == null) return null;

        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (y < level.getMinY()) return null;

        BlockState state = chunk.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
        int shade = Math.max(-24, Math.min(34, y - 64));
        if (state.is(Blocks.WATER)) return new Color(34, 86, 145 + Math.max(0, shade / 2), 185);
        if (state.is(Blocks.LAVA)) return new Color(232, 94, 34, 210);
        if (state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)) return shade(new Color(194, 178, 106, 210), shade);
        if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)) return new Color(214, 228, 236, 215);
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MOSS_BLOCK)) return shade(new Color(72, 132, 76, 205), shade);
        if (state.is(Blocks.NETHERRACK) || state.is(Blocks.CRIMSON_NYLIUM)) return shade(new Color(116, 43, 44, 215), shade);
        if (state.is(Blocks.WARPED_NYLIUM)) return shade(new Color(42, 105, 106, 215), shade);
        if (state.is(Blocks.BASALT) || state.is(Blocks.BLACKSTONE)) return shade(new Color(55, 55, 60, 220), shade);
        if (state.is(Blocks.END_STONE)) return shade(new Color(188, 184, 124, 215), shade);
        return shade(new Color(92, 94, 88, 190), shade);
    }

    private Color shade(Color base, int shade) {
        int r = Math.max(0, Math.min(255, base.r + shade));
        int g = Math.max(0, Math.min(255, base.g + shade));
        int b = Math.max(0, Math.min(255, base.b + shade));
        return new Color(r, g, b, base.a);
    }

    private void renderBiomes(MapRenderContext context) {
        MapViewport viewport = context.viewport();
        int chunksX = viewport.endChunkX() - viewport.startChunkX() + 1;
        int chunksZ = viewport.endChunkZ() - viewport.startChunkZ() + 1;
        int tileCount = Math.max(0, chunksX) * Math.max(0, chunksZ);
        if (tileCount > MAX_DETAILED_BIOME_TILES || 16.0 * viewport.zoom() < 8.0) {
            renderBiomeOverview(context);
            return;
        }

        Renderer2D renderer = Renderer2D.TEXTURE;
        int margin = Math.max(0, context.biomeTileMargin());
        Color tileColor = new Color(255, 255, 255, context.biomeOpacity());

        for (int cz = viewport.startChunkZ() - margin; cz <= viewport.endChunkZ() + margin; cz++) {
            for (int cx = viewport.startChunkX() - margin; cx <= viewport.endChunkX() + margin; cx++) {
                ChunkTile tile = MinimapManager.get().getTile(cx, cz, context.dimension());
                if (tile.texture == null) continue;

                double x = viewport.screenX(cx * 16.0);
                double y = viewport.screenY(cz * 16.0);
                double size = 16.0 * viewport.zoom();

                renderer.begin();
                renderer.texQuad(x, y, size, size, tileColor);
                renderer.render(tile.texture.getTextureView(), tile.texture.getSampler());
            }
        }
    }

    private void renderBiomeOverview(MapRenderContext context) {
        MapViewport viewport = context.viewport();
        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) return;

        int cellBlocks = overviewCellBlocks(viewport.zoom());
        int startX = Math.floorDiv((int) Math.floor(viewport.worldX(0)), cellBlocks) * cellBlocks;
        int startZ = Math.floorDiv((int) Math.floor(viewport.worldZ(0)), cellBlocks) * cellBlocks;
        int endX = Math.floorDiv((int) Math.ceil(viewport.worldX(viewport.width())), cellBlocks) * cellBlocks + cellBlocks;
        int endZ = Math.floorDiv((int) Math.ceil(viewport.worldZ(viewport.height())), cellBlocks) * cellBlocks + cellBlocks;

        if (overviewBiomeColorCache.size() > MAX_OVERVIEW_COLOR_CACHE) {
            overviewBiomeColorCache.clear();
        }

        Renderer2D renderer = Renderer2D.COLOR;
        renderer.begin();
        for (int z = startZ; z <= endZ; z += cellBlocks) {
            for (int x = startX; x <= endX; x += cellBlocks) {
                int sampleX = x + cellBlocks / 2;
                int sampleZ = z + cellBlocks / 2;
                OverviewBiomeKey key = new OverviewBiomeKey(seed, context.dimension(), cellBlocks, Math.floorDiv(x, cellBlocks), Math.floorDiv(z, cellBlocks));
                int abgr = overviewBiomeColorCache.computeIfAbsent(key, ignored -> BiomeGenerator.getBiomeColor(sampleX, sampleZ, context.dimension()));
                Color color = colorFromAbgr(abgr, context.biomeOpacity());

                double sx = viewport.screenX(x);
                double sy = viewport.screenY(z);
                double ex = viewport.screenX(x + cellBlocks);
                double ey = viewport.screenY(z + cellBlocks);
                renderer.quad(sx, sy, Math.ceil(ex - sx) + 1.0, Math.ceil(ey - sy) + 1.0, color);
            }
        }
        renderer.render();
    }

    private int overviewCellBlocks(double zoom) {
        int cellBlocks = 8;
        while (cellBlocks * zoom < OVERVIEW_TARGET_CELL_SIZE && cellBlocks < 4096) {
            cellBlocks *= 2;
        }
        return cellBlocks;
    }

    private Color colorFromAbgr(int abgr, int alpha) {
        int r = abgr & 0xFF;
        int g = (abgr >> 8) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        return new Color(r, g, b, Math.max(0, Math.min(255, alpha)));
    }

    private GeneratedStructure renderStructures(MapRenderContext context, List<GeneratedStructure> structures) {
        MapViewport viewport = context.viewport();
        GeneratedStructure hovered = null;
        double hoverThreshold = 8.0;
        double metersPerPixel = metersPerPixel(viewport);
        Map<Long, Integer> overlapCounts = new HashMap<>();

        List<GeneratedStructure> ordered = new ArrayList<>(structures);
        ordered.sort((a, b) -> {
            int z = Integer.compare(a.z, b.z);
            if (z != 0) return z;
            int x = Integer.compare(a.x, b.x);
            if (x != 0) return x;
            return Integer.compare(a.type.ordinal(), b.type.ordinal());
        });

        for (GeneratedStructure structure : ordered) {
            if (!shouldRenderStructure(structure, metersPerPixel)) continue;
            if (context.structureSearchFilter() != null && structure.type != context.structureSearchFilter()) continue;
            if (!SeedManager.get().getProfileStructure(structure.type.name(), true)) continue;

            double sx = viewport.screenX(structure.x);
            double sy = viewport.screenY(structure.z);

            Color color = StructureColors.get(structure.type);
            if (context.dimWaypointStructures() && WaypointManager.get().hasWaypointAt(structure.x, structure.z, context.dimension())) {
                color = new Color(color.r / 2, color.g / 2, color.b / 2, color.a / 2);
            }

            double iconSize = structureIconSize(structure.type, metersPerPixel, context.structureMarkerScale());
            color = withAlpha(color, structureAlpha(structure.type, metersPerPixel));
            if (SeedManager.get().getCompletedStructure(structure.type.name(), context.dimension(), structure.x, structure.z)) {
                color = withAlpha(color, 0.36);
            }
            color = withPulse(color, context.delta(), structure.x, structure.z, 0.78, 1.0);
            double half = iconSize / 2.0;
            int overlapIndex = overlapCounts.merge(overlapKey(structure, iconSize, viewport), 1, Integer::sum) - 1;
            double displayX = sx + overlapOffset(overlapIndex, iconSize);
            double displayY = sy;
            double paddedSize = iconSize * ICON_TEXTURE_PADDING;
            double paddedHalf = paddedSize / 2.0;
            if (!viewport.isVisible(displayX, displayY, paddedHalf + 4)) continue;

            renderStructureIcon(structure, displayX - paddedHalf, displayY - paddedHalf, paddedSize, color);

            double dx = context.mouseX() - displayX;
            double dy = context.mouseY() - displayY;
            if (Math.sqrt(dx * dx + dy * dy) < hoverThreshold + paddedSize / 2.0) {
                hovered = structure;
            }
        }

        return hovered;
    }

    private long overlapKey(GeneratedStructure structure, double iconSize, MapViewport viewport) {
        double cellBlocks = Math.max(1.0, iconSize * 0.68 / Math.max(0.001, viewport.zoom()));
        int cellX = (int) Math.floor(structure.x / cellBlocks);
        int cellY = (int) Math.floor(structure.z / cellBlocks);
        return ((long) cellX << 32) | (cellY & 0xFFFFFFFFL);
    }

    private double overlapOffset(int index, double iconSize) {
        if (index <= 0) return 0.0;
        int pair = (index + 1) / 2;
        double direction = index % 2 == 1 ? -1.0 : 1.0;
        return direction * pair * iconSize * 0.72;
    }

    private boolean shouldRenderStructure(GeneratedStructure structure, double metersPerPixel) {
        double maxMetersPerPixel = maxVisibleMetersPerPixel(structure.type);
        if (metersPerPixel > maxMetersPerPixel) return false;

        if (metersPerPixel > 55.0 && isCommonFarStructure(structure.type)) {
            return Math.floorMod(structure.x * 31 + structure.z * 17 + structure.type.ordinal() * 13, 3) == 0;
        }

        return true;
    }

    private double structureIconSize(StructureType type, double metersPerPixel, double scale) {
        double size;
        if (metersPerPixel <= 2.5) size = 31.0;
        else if (metersPerPixel <= 6.0) size = 28.0;
        else if (metersPerPixel <= 18.0) size = 25.0;
        else if (metersPerPixel <= 50.0) size = 22.0;
        else size = 19.0;

        if (type == StructureType.TREASURE) size *= 0.85;
        return size * scale;
    }

    private double structureAlpha(StructureType type, double metersPerPixel) {
        double maxMetersPerPixel = maxVisibleMetersPerPixel(type);
        if (maxMetersPerPixel == Double.POSITIVE_INFINITY) return 1.0;

        double fadeStart = maxMetersPerPixel * 0.58;
        if (metersPerPixel <= fadeStart) return 1.0;
        if (metersPerPixel >= maxMetersPerPixel) return 0.0;
        return 0.18 + 0.82 * (maxMetersPerPixel - metersPerPixel) / (maxMetersPerPixel - fadeStart);
    }

    private double maxVisibleMetersPerPixel(StructureType type) {
        return switch (type) {
            case TREASURE -> TREASURE_MAX_METERS_PER_PIXEL;
            case END_GATEWAY -> 14.0;
            case NETHER_FOSSIL -> 42.0;
            case MINESHAFT -> 20.0;
            case SHIPWRECK, OCEAN_RUIN, RUINED_PORTAL, TRAIL_RUINS -> 18.0;
            case DESERT_PYRAMID, JUNGLE_TEMPLE, WITCH_HUT, IGLOO, OUTPOST -> 45.0;
            case VILLAGE -> 85.0;
            case MONUMENT, MANSION, ANCIENT_CITY, TRIAL_CHAMBER, STRONGHOLD,
                 FORTRESS, BASTION, END_CITY -> 160.0;
            default -> 30.0;
        };
    }

    private boolean isCommonFarStructure(StructureType type) {
        return switch (type) {
            case VILLAGE, OUTPOST, MINESHAFT -> true;
            default -> false;
        };
    }

    private double metersPerPixel(MapViewport viewport) {
        return viewport.zoom() <= 0.0 ? Double.POSITIVE_INFINITY : 1.0 / viewport.zoom();
    }

    private Color withAlpha(Color color, double alphaMultiplier) {
        int alpha = (int) Math.max(0, Math.min(255, color.a * alphaMultiplier));
        return new Color(color.r, color.g, color.b, alpha);
    }

    private void renderStructureIcon(GeneratedStructure structure, double x, double y, double size, Color fallbackColor) {
        renderIconBackplate(x, y, size, fallbackColor);

        Texture texture = StructureIcons.get(structure);
        if (texture != null) {
            Color tint = new Color(255, 255, 255, fallbackColor.a);
            Renderer2D renderer = Renderer2D.TEXTURE;
            renderer.begin();
            double inset = Math.max(1.5, size * 0.055);
            renderer.texQuad(x + inset, y + inset, size - inset * 2.0, size - inset * 2.0, tint);
            renderer.render(texture.getTextureView(), texture.getSampler());
            return;
        }

        double half = size / 2.0;
        double sx = x + half;
        double sy = y + half;
        Renderer2D renderer = Renderer2D.COLOR;
        renderer.begin();
        renderer.triangle(sx, sy - half, sx - half, sy, sx, sy + half, fallbackColor);
        renderer.triangle(sx, sy - half, sx + half, sy, sx, sy + half, fallbackColor);
        renderer.render();
    }

    private void renderIconBackplate(double x, double y, double size, Color fallbackColor) {
        int alpha = Math.max(70, Math.min(235, fallbackColor.a));
        Renderer2D renderer = Renderer2D.COLOR;
        renderer.begin();
        renderer.quad(x + 2.0, y + 3.0, size, size, new Color(0, 0, 0, Math.min(135, alpha)));
        renderer.quad(x, y, size, size, new Color(9, 12, 17, Math.min(190, alpha)));
        renderer.quad(x + 1.0, y + 1.0, size - 2.0, size - 2.0, new Color(18, 24, 29, Math.min(150, alpha)));
        renderer.boxLines(x, y, size, size, new Color(255, 255, 255, Math.min(235, alpha)));
        renderer.boxLines(x + 1.0, y + 1.0, size - 2.0, size - 2.0, new Color(fallbackColor.r, fallbackColor.g, fallbackColor.b, Math.min(230, alpha)));
        renderer.render();
    }

    private SeedWaypoint renderWaypoints(MapRenderContext context, List<SeedWaypoint> waypoints, GeneratedStructure hoveredStructure) {
        MapViewport viewport = context.viewport();
        SeedWaypoint hovered = null;
        // Hover tolerance is in screen pixels (like the marker sizes and mouse delta below),
        // matching the structure hover logic. It must not be scaled by zoom.
        double hoverThreshold = 8.0;

        Renderer2D renderer = Renderer2D.COLOR;
        renderer.begin();

        for (SeedWaypoint waypoint : waypoints) {
            double sx = viewport.screenX(waypoint.x);
            double sy = viewport.screenY(waypoint.z);
            if (!viewport.isVisible(sx, sy, 20)) continue;
            if (isCoveredByHoveredStructure(viewport, hoveredStructure, sx, sy)) continue;

            double iconSize = Math.min(24, Math.max(6, 12 * viewport.zoom())) * context.waypointMarkerScale();
            double dx = context.mouseX() - sx;
            double dy = context.mouseY() - sy;
            boolean hovering = Math.sqrt(dx * dx + dy * dy) < hoverThreshold + iconSize / 2.0;
            Color color = hovering ? WAYPOINT_HOVER_COLOR : WAYPOINT_MARKER_COLOR;
            if (hovering) hovered = waypoint;
            color = withPulse(color, context.delta(), waypoint.x, waypoint.z, 0.82, 1.0);

            double half = iconSize / 2.0;
            renderer.quad(sx - half, sy - half, iconSize, iconSize, color);
            renderer.quad(sx - half + 2, sy - 1, iconSize - 4, 2, new Color(40, 40, 40, 180));
            renderer.quad(sx - 1, sy - half + 2, 2, iconSize - 4, new Color(40, 40, 40, 180));
        }

        renderer.render();
        return hovered;
    }

    private boolean isCoveredByHoveredStructure(MapViewport viewport, GeneratedStructure hoveredStructure, double waypointX, double waypointY) {
        if (hoveredStructure == null) return false;

        double structureX = viewport.screenX(hoveredStructure.x);
        double structureY = viewport.screenY(hoveredStructure.z);
        double dx = waypointX - structureX;
        double dy = waypointY - structureY;
        return Math.sqrt(dx * dx + dy * dy) < 10;
    }

    private void renderPlayer(MapRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || dimensionId() != context.dimension()) return;

        MapViewport viewport = context.viewport();
        double sx = viewport.screenX(mc.player.getX());
        double sy = viewport.screenY(mc.player.getZ());
        double rotation = -mc.player.getYRot();

        if (viewport.isVisible(sx, sy, 24)) {
            drawPlayerArrow(sx, sy, rotation, 21);
            return;
        }

        double cx = viewport.width() / 2.0;
        double cy = viewport.height() / 2.0;
        double dx = sx - cx;
        double dy = sy - cy;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < 0.001) return;

        double edgePadding = 24.0;
        double scaleX = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? (viewport.width() - edgePadding - cx) / dx : (edgePadding - cx) / dx);
        double scaleY = dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? (viewport.height() - edgePadding - cy) / dy : (edgePadding - cy) / dy);
        double scale = Math.min(Math.abs(scaleX), Math.abs(scaleY));

        double edgeX = cx + dx * scale;
        double edgeY = cy + dy * scale;
        double playerDirection = Math.toDegrees(Math.atan2(dy, dx)) + 90.0;
        drawPlayerArrow(edgeX, edgeY, playerDirection, 20);
    }

    private void drawPlayerArrow(double sx, double sy, double rotation, double size) {
        Texture texture = UiIcons.get("player");
        if (texture != null) {
            renderPlayerBackplate(sx, sy, size + 5.0);
            Renderer2D renderer = Renderer2D.TEXTURE;
            renderer.begin();
            renderer.texQuad(sx - size / 2.0, sy - size / 2.0, size, size, rotation, 0, 0, 1, 1, Color.WHITE);
            renderer.render(texture.getTextureView(), texture.getSampler());
            return;
        }

        double yaw = Math.toRadians(-rotation);
        Renderer2D renderer = Renderer2D.COLOR;
        renderer.begin();
        renderer.triangle(
            sx - Math.sin(yaw) * size * 0.58,
            sy + Math.cos(yaw) * size * 0.58,
            sx - Math.sin(yaw + 2.35) * (size * 0.49),
            sy + Math.cos(yaw + 2.35) * (size * 0.49),
            sx - Math.sin(yaw - 2.35) * (size * 0.49),
            sy + Math.cos(yaw - 2.35) * (size * 0.49),
            PLAYER_OUTLINE_COLOR
        );
        renderer.triangle(
            sx - Math.sin(yaw) * size * 0.5,
            sy + Math.cos(yaw) * size * 0.5,
            sx - Math.sin(yaw + 2.35) * (size * 0.42),
            sy + Math.cos(yaw + 2.35) * (size * 0.42),
            sx - Math.sin(yaw - 2.35) * (size * 0.42),
            sy + Math.cos(yaw - 2.35) * (size * 0.42),
            new Color(240, 32, 36, 245)
        );
        renderer.render();
    }

    private void renderPlayerBackplate(double sx, double sy, double size) {
        double x = sx - size / 2.0;
        double y = sy - size / 2.0;
        Renderer2D renderer = Renderer2D.COLOR;
        renderer.begin();
        renderer.quad(x + 2.0, y + 3.0, size, size, new Color(0, 0, 0, 105));
        renderer.quad(x, y, size, size, new Color(12, 16, 22, 190));
        renderer.boxLines(x, y, size, size, new Color(255, 255, 255, 215));
        renderer.boxLines(x + 1.0, y + 1.0, size - 2.0, size - 2.0, new Color(240, 42, 45, 225));
        renderer.render();
    }

    private void renderPlayerInfo(MapRenderContext context, GuiTheme theme) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        int playerDimension = dimensionId();
        String info = String.format("Player: X %.1f, Y %.1f, Z %.1f | %s | Facing %s",
            mc.player.getX(), mc.player.getY(), mc.player.getZ(), dimensionName(playerDimension), facingName(mc.player.getYRot()));
        if (playerDimension != context.dimension()) {
            info += " | Map: " + dimensionName(context.dimension());
        }

        Color color = playerDimension == context.dimension() ? new Color(240, 32, 36, 245) : new Color(165, 165, 170, 220);
        theme.textRenderer().begin(1.0);
        theme.textRenderer().render(info, 10, context.viewport().height() - 40, color);
        theme.textRenderer().end();
    }

    private String facingName(float yaw) {
        float wrapped = yaw % 360.0f;
        if (wrapped < 0) wrapped += 360.0f;

        if (wrapped >= 315.0f || wrapped < 45.0f) return "South";
        if (wrapped < 135.0f) return "West";
        if (wrapped < 225.0f) return "North";
        return "East";
    }

    private String dimensionName(int dimension) {
        return switch (dimension) {
            case -1 -> "Nether";
            case 1 -> "End";
            default -> "Overworld";
        };
    }

    private int dimensionId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 0;
        if (mc.level.dimension() == Level.NETHER) return -1;
        if (mc.level.dimension() == Level.END) return 1;
        return 0;
    }

    private void renderChunkBorders(MapRenderContext context) {
        MapViewport viewport = context.viewport();
        int chunksX = viewport.endChunkX() - viewport.startChunkX() + 1;
        int chunksZ = viewport.endChunkZ() - viewport.startChunkZ() + 1;
        double chunkScreenSize = 16.0 * viewport.zoom();
        if (chunkScreenSize < 8.0 || Math.max(0, chunksX) * Math.max(0, chunksZ) > MAX_DETAILED_BIOME_TILES) return;

        Renderer2D renderer = Renderer2D.COLOR;
        renderer.begin();

        for (int cz = viewport.startChunkZ(); cz <= viewport.endChunkZ(); cz++) {
            for (int cx = viewport.startChunkX(); cx <= viewport.endChunkX(); cx++) {
                double x = viewport.screenX(cx * 16.0);
                double y = viewport.screenY(cz * 16.0);
                double size = 16.0 * viewport.zoom();
                renderer.boxLines(x, y, size, size, CHUNK_BORDER_COLOR);
            }
        }

        renderer.render();
    }

    private void renderCoordinates(MapRenderContext context, GuiTheme theme) {
        MapViewport viewport = context.viewport();
        double worldX = viewport.worldX(context.mouseX());
        double worldZ = viewport.worldZ(context.mouseY());
        long seed = SeedManager.get().getWorldSeed();
        String biome = seed == 0 ? "Unknown" : BiomeGenerator.getPredictedBiome((int) worldX, (int) worldZ, seed, context.dimension()).displayName();
        String coords = String.format("X: %.1f, Z: %.1f | Biome: %s | Zoom: %.2f", worldX, worldZ, biome, viewport.zoom());

        Renderer2D renderer = Renderer2D.COLOR;
        renderer.begin();
        renderer.quad(0, viewport.height() - 46, viewport.width(), 46, STATUS_BAR_COLOR);
        renderer.render();

        theme.textRenderer().begin(1.0);
        theme.textRenderer().render(coords, 10, viewport.height() - 20, theme.textColor());
        theme.textRenderer().render("+", viewport.width() / 2.0 - 3, viewport.height() / 2.0 - 4, CENTER_CROSSHAIR_COLOR);
        theme.textRenderer().end();
    }

    private Color withPulse(Color color, float delta, int x, int z, double min, double max) {
        double phase = (System.nanoTime() / 1_000_000_000.0) * 2.4 + (x * 31 + z * 17) * 0.001 + delta;
        double factor = min + (Math.sin(phase) * 0.5 + 0.5) * (max - min);
        int alpha = (int) Math.max(0, Math.min(255, color.a * factor));
        return new Color(color.r, color.g, color.b, alpha);
    }

    private record OverviewBiomeKey(long seed, int dimension, int cellBlocks, int cellX, int cellZ) {
    }

    private static final class FossilCluster {
        private int count;
        private long sumX;
        private long sumZ;

        private void add(GeneratedStructure structure) {
            count++;
            sumX += structure.x;
            sumZ += structure.z;
        }

        private GeneratedStructure toStructure() {
            if (count <= 0) return new GeneratedStructure(0, 0, StructureType.NETHER_FOSSIL);
            String variant = count > 1 ? "Cluster x" + count : "";
            return new GeneratedStructure(
                (int) Math.round(sumX / (double) count),
                (int) Math.round(sumZ / (double) count),
                StructureType.NETHER_FOSSIL,
                variant
            );
        }
    }
}
