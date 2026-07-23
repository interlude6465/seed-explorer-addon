/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.modules;

import me.seedexplorer.addon.SeedExplorerAddon;
import me.seedexplorer.addon.events.SeedChangeEvent;
import me.seedexplorer.addon.gui.SeedExplorerScreen;
import me.seedexplorer.addon.ore.OrePatch;
import me.seedexplorer.addon.ore.OrePredictor;
import me.seedexplorer.addon.ore.OreType;
import me.seedexplorer.addon.ore.OreCache;
import me.seedexplorer.addon.render.MapLayer;
import me.seedexplorer.addon.render.MinimapOverlay;
import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.workers.WorkerManager;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class SeedExplorerModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMap = settings.createGroup("Map");
    private final SettingGroup sgStructures = settings.createGroup("Structures");
    private final SettingGroup sgBiomes = settings.createGroup("Biomes");
    private final SettingGroup sgOres = settings.createGroup("Ores");
    private final SettingGroup sgWaypoints = settings.createGroup("Waypoints");
    private final SettingGroup sgRendering = settings.createGroup("Rendering");
    private final SettingGroup sgPlayerTracking = settings.createGroup("Player Tracking");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");

    private volatile List<OrePatch> cachedOreEspPatches = List.of();
    private volatile long cachedOreEspKey = Long.MIN_VALUE;
    private volatile long pendingOreEspKey = Long.MIN_VALUE;
    private volatile boolean oreEspWorkerActive;
    private volatile long lastOreEspRequestMs;
    private volatile int lastOreEspPatchCount = -1;
    private volatile String lastOreEspStatus = "idle";
    private volatile int cachedOreEspCenterChunkX = Integer.MIN_VALUE;
    private volatile int cachedOreEspCenterChunkZ = Integer.MIN_VALUE;
    private volatile int pendingOreEspCenterChunkX = Integer.MIN_VALUE;
    private volatile int pendingOreEspCenterChunkZ = Integer.MIN_VALUE;
    private boolean mapKeyWasDown;
    private final MinimapOverlay minimapOverlay = new MinimapOverlay();
    private List<OrePatch> touchedOreZone = List.of();
    private int touchedOreZoneDimension = Integer.MIN_VALUE;
    private OreType touchedOreZoneType = null;
    private OreType lastOreEspType = null;
    private int lastOreEspDimension = Integer.MIN_VALUE;
    private static final long ORE_ESP_REFRESH_MS = 1200L;

    public final Setting<Double> zoomStep = sgMap.add(new DoubleSetting.Builder()
        .name("zoom-step")
        .description("How quickly the Seed Explorer map zooms with the mouse wheel.")
        .defaultValue(1.2)
        .range(1.01, 2.0)
        .sliderRange(1.05, 1.5)
        .decimalPlaces(2)
        .build()
    );

    public final Setting<Double> minZoom = sgMap.add(new DoubleSetting.Builder()
        .name("min-zoom")
        .description("Minimum map zoom level.")
        .defaultValue(0.01)
        .range(0.001, 10.0)
        .sliderRange(0.001, 1.0)
        .decimalPlaces(3)
        .build()
    );

    public final Setting<Double> maxZoom = sgMap.add(new DoubleSetting.Builder()
        .name("max-zoom")
        .description("Maximum map zoom level.")
        .defaultValue(100.0)
        .range(1.0, 200.0)
        .sliderRange(10.0, 200.0)
        .decimalPlaces(1)
        .build()
    );

    public final Setting<Boolean> structureTooltips = sgStructures.add(new BoolSetting.Builder()
        .name("structure-tooltips")
        .description("Shows tooltips when hovering predicted structures.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> dimWaypointStructures = sgStructures.add(new BoolSetting.Builder()
        .name("dim-waypoint-structures")
        .description("Dims structure markers that already have Seed Explorer waypoints.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> structureMarkerScale = sgStructures.add(new DoubleSetting.Builder()
        .name("structure-marker-scale")
        .description("Scales predicted structure markers on the map.")
        .defaultValue(1.0)
        .range(0.5, 3.0)
        .sliderRange(0.5, 2.0)
        .decimalPlaces(2)
        .build()
    );

    public final Setting<Integer> biomeOpacity = sgBiomes.add(new IntSetting.Builder()
        .name("biome-opacity")
        .description("Opacity for biome map tiles.")
        .defaultValue(255)
        .range(40, 255)
        .sliderRange(40, 255)
        .build()
    );

    public final Setting<Boolean> terrainLayer = sgRendering.add(new BoolSetting.Builder()
        .name("terrain-layer")
        .description("Shows the base map background.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> loadedTerrainOnMap = sgMap.add(new BoolSetting.Builder()
        .name("loaded-terrain-on-map")
        .description("Overlays loaded client terrain on the full Seed Explorer map when chunks are available.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> biomeLayer = sgRendering.add(new BoolSetting.Builder()
        .name("biome-layer")
        .description("Shows seed-predicted biome map tiles.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> structureLayer = sgRendering.add(new BoolSetting.Builder()
        .name("structure-layer")
        .description("Shows predicted structure markers.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> oreOverlay = sgGeneral.add(new BoolSetting.Builder()
        .name("map-ore-overlay")
        .description("Shows predicted ore locations on the Seed Explorer map. This is heavy and is off by default.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> oreEsp = sgOres.add(new BoolSetting.Builder()
        .name("ore-esp")
        .description("Shows nearby seed-predicted ore positions as colored in-world boxes.")
        .defaultValue(false)
        .build()
    );

    public final Setting<OreType> oreEspType = sgOres.add(new EnumSetting.Builder<OreType>()
        .name("ore-esp-type")
        .description("The ore type to highlight with in-world boxes.")
        .defaultValue(OreType.DIAMOND)
        .build()
    );

    public final Setting<Integer> oreEspRadius = sgOres.add(new IntSetting.Builder()
        .name("ore-esp-radius")
        .description("Chunk radius around the player to predict ore boxes.")
        .defaultValue(2)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    public final Setting<Integer> oreEspMaxBoxes = sgOres.add(new IntSetting.Builder()
        .name("ore-esp-max-boxes")
        .description("Maximum number of ore boxes rendered at once.")
        .defaultValue(80)
        .range(1, 500)
        .sliderRange(20, 200)
        .build()
    );

    public final Setting<ShapeMode> oreEspShapeMode = sgOres.add(new EnumSetting.Builder<ShapeMode>()
        .name("ore-esp-shape-mode")
        .description("How in-world predicted ore boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    public final Setting<Integer> oreOpacity = sgOres.add(new IntSetting.Builder()
        .name("ore-opacity")
        .description("Opacity for predicted ore markers and in-world ore box fills.")
        .defaultValue(55)
        .range(20, 255)
        .sliderRange(20, 255)
        .build()
    );

    public final Setting<Double> oreMarkerScale = sgOres.add(new DoubleSetting.Builder()
        .name("ore-marker-scale")
        .description("Scales predicted ore markers on the map.")
        .defaultValue(1.0)
        .range(0.5, 3.0)
        .sliderRange(0.5, 2.0)
        .decimalPlaces(2)
        .build()
    );

    public final Setting<Boolean> waypointTooltips = sgWaypoints.add(new BoolSetting.Builder()
        .name("waypoint-tooltips")
        .description("Shows tooltips when hovering Seed Explorer waypoints.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> waypointMarkerScale = sgWaypoints.add(new DoubleSetting.Builder()
        .name("waypoint-marker-scale")
        .description("Scales Seed Explorer waypoint markers on the map.")
        .defaultValue(1.0)
        .range(0.5, 3.0)
        .sliderRange(0.5, 2.0)
        .decimalPlaces(2)
        .build()
    );

    public final Setting<Boolean> waypointLayer = sgRendering.add(new BoolSetting.Builder()
        .name("waypoint-layer")
        .description("Shows Seed Explorer waypoints on the map.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> playerLayer = sgRendering.add(new BoolSetting.Builder()
        .name("player-layer")
        .description("Shows the current player position on the map.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> chunkBordersLayer = sgRendering.add(new BoolSetting.Builder()
        .name("chunk-borders-layer")
        .description("Shows chunk border grid lines.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> coordinatesLayer = sgRendering.add(new BoolSetting.Builder()
        .name("coordinates-layer")
        .description("Shows cursor coordinates and the center crosshair.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> showPlayerInfo = sgPlayerTracking.add(new BoolSetting.Builder()
        .name("show-player-info")
        .description("Shows the player coordinates and dimension on the Seed Explorer map.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> autoCenterPlayer = sgPlayerTracking.add(new BoolSetting.Builder()
        .name("auto-center-player")
        .description("Keeps the Seed Explorer map centered on the player when viewing the player dimension.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> minimap = sgMap.add(new BoolSetting.Builder()
        .name("minimap")
        .description("Shows a compact Seed Explorer minimap in the top-left corner.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Keybind> openMapKey = sgMap.add(new KeybindSetting.Builder()
        .name("open-map-key")
        .description("Opens the full Seed Explorer map while this module is enabled.")
        .defaultValue(Keybind.fromKey(88))
        .build()
    );

    public final Setting<Integer> minimapSize = sgMap.add(new IntSetting.Builder()
        .name("minimap-size")
        .description("Size of the Seed Explorer minimap.")
        .defaultValue(150)
        .range(90, 260)
        .sliderRange(110, 220)
        .build()
    );

    public final Setting<Integer> generationMargin = sgPerformance.add(new IntSetting.Builder()
        .name("generation-margin")
        .description("Extra chunk margin to preload around the visible map for structures and ores.")
        .defaultValue(10)
        .range(0, 48)
        .sliderRange(0, 32)
        .build()
    );

    public final Setting<Integer> biomeTileMargin = sgPerformance.add(new IntSetting.Builder()
        .name("biome-tile-margin")
        .description("Extra chunk margin to preload around the visible map for biome tiles.")
        .defaultValue(2)
        .range(0, 16)
        .sliderRange(0, 8)
        .build()
    );

    public SeedExplorerModule() {
        super(SeedExplorerAddon.SEED_EXPLORER, "seed-explorer", "Base module for the Seed Explorer addon.");
    }

    @Override
    public void onActivate() {
        resetOreEspState();
    }

    @Override
    public void onDeactivate() {
        resetOreEspState();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!oreEsp.get() || mc.player == null || mc.level == null) return;

        OreType type = oreEspType.get();
        int dimension = currentDimensionId();
        if (type.dimension != dimension) {
            cachedOreEspPatches = List.of();
            return;
        }
        if (type != lastOreEspType || dimension != lastOreEspDimension) {
            resetOreEspState();
            lastOreEspType = type;
            lastOreEspDimension = dimension;
        }

        int playerChunkX = Math.floorDiv(mc.player.blockPosition().getX(), 16);
        int playerChunkZ = Math.floorDiv(mc.player.blockPosition().getZ(), 16);
        int allowedDrift = Math.max(1, oreEspRadius.get() / 2);
        if (cachedOreEspKey == Long.MIN_VALUE
            || Math.abs(playerChunkX - cachedOreEspCenterChunkX) > allowedDrift
            || Math.abs(playerChunkZ - cachedOreEspCenterChunkZ) > allowedDrift) {
            return;
        }

        for (OrePatch patch : cachedOreEspPatches) {
            Color lineColor = type.color;
            Color sideColor = new Color(lineColor.r, lineColor.g, lineColor.b, oreOpacity.get());
            event.renderer.box(patch.x, patch.y, patch.z, patch.x + 1, patch.y + 1, patch.z + 1, sideColor, lineColor, oreEspShapeMode.get(), 0);
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!minimap.get() || mc.player == null || mc.level == null || mc.screen != null) return;
        minimapOverlay.render(event, this);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null || mc.screen != null) {
            mapKeyWasDown = false;
            if (mc.player == null || mc.level == null) return;
        }

        updateOreEsp();
        updateTouchedOreZone();

        boolean down = mc.screen == null && openMapKey.get().isPressed();
        if (down && !mapKeyWasDown) {
            mc.setScreen(new SeedExplorerScreen(meteordevelopment.meteorclient.gui.GuiThemes.get()));
        }
        mapKeyWasDown = down;
    }

    @EventHandler
    private void onSeedChanged(SeedChangeEvent event) {
        resetOreEspState();
        OreCache.get().clear();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WButton openScreen = theme.button("Open Seed Explorer");
        openScreen.action = () -> mc.setScreen(new SeedExplorerScreen(theme));
        return openScreen;
    }

    public boolean isLayerEnabled(MapLayer layer) {
        return switch (layer) {
            case TERRAIN -> SeedManager.get().peekProfileLayer(layer.name(), terrainLayer.get());
            case BIOMES -> SeedManager.get().peekProfileLayer(layer.name(), biomeLayer.get());
            case STRUCTURES -> SeedManager.get().peekProfileLayer(layer.name(), structureLayer.get());
            case ORES -> SeedManager.get().peekProfileLayer(layer.name(), oreOverlay.get());
            case WAYPOINTS -> SeedManager.get().peekProfileLayer(layer.name(), waypointLayer.get());
            case PLAYER -> SeedManager.get().peekProfileLayer(layer.name(), playerLayer.get());
            case CHUNK_BORDERS -> SeedManager.get().peekProfileLayer(layer.name(), chunkBordersLayer.get());
            case COORDINATES -> SeedManager.get().peekProfileLayer(layer.name(), coordinatesLayer.get());
        };
    }

    public void setLayerEnabled(MapLayer layer, boolean enabled) {
        SeedManager.get().setProfileLayer(layer.name(), enabled);
        switch (layer) {
            case TERRAIN -> terrainLayer.set(enabled);
            case BIOMES -> biomeLayer.set(enabled);
            case STRUCTURES -> structureLayer.set(enabled);
            case ORES -> oreOverlay.set(enabled);
            case WAYPOINTS -> waypointLayer.set(enabled);
            case PLAYER -> playerLayer.set(enabled);
            case CHUNK_BORDERS -> chunkBordersLayer.set(enabled);
            case COORDINATES -> coordinatesLayer.set(enabled);
        }
    }

    private int currentDimensionId() {
        if (mc.level == null) return 0;
        if (mc.level.dimension() == Level.NETHER) return -1;
        if (mc.level.dimension() == Level.END) return 1;
        return 0;
    }

    private void updateOreEsp() {
        if (!oreEsp.get() || mc.player == null || mc.level == null) return;

        OreType type = oreEspType.get();
        int dimension = currentDimensionId();
        if (type.dimension != dimension) {
            if (lastOreEspPatchCount != -2) {
                cachedOreEspPatches = List.of();
                lastOreEspPatchCount = -2;
                lastOreEspStatus = "wrong_dimension";
                ChatUtils.warning("Seed Explorer: " + type.displayName + " ore ESP is not available in this dimension.");
            }
            return;
        }

        if (type != lastOreEspType || dimension != lastOreEspDimension) {
            resetOreEspState();
            lastOreEspType = type;
            lastOreEspDimension = dimension;
        }

        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0L) {
            if (!"no_seed".equals(lastOreEspStatus)) {
                lastOreEspStatus = "no_seed";
                ChatUtils.warning("Seed Explorer: set a seed before using ore ESP.");
            }
            return;
        }

        int blockX = mc.player.blockPosition().getX();
        int blockY = mc.player.blockPosition().getY();
        int blockZ = mc.player.blockPosition().getZ();
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        int radius = oreEspRadius.get();
        int maxBoxes = oreEspMaxBoxes.get();
        long key = oreEspKey(seed, type, dimension, chunkX, chunkZ, radius, maxBoxes);

        if (oreEspWorkerActive && (chunkX != pendingOreEspCenterChunkX || chunkZ != pendingOreEspCenterChunkZ)) {
            pendingOreEspKey = Long.MIN_VALUE;
            lastOreEspStatus = "stale";
            lastOreEspRequestMs = 0L;
            return;
        }

        if (key == cachedOreEspKey || key == pendingOreEspKey || oreEspWorkerActive) return;

        long now = System.currentTimeMillis();
        if (now - lastOreEspRequestMs < ORE_ESP_REFRESH_MS) return;

        pendingOreEspKey = key;
        pendingOreEspCenterChunkX = chunkX;
        pendingOreEspCenterChunkZ = chunkZ;
        oreEspWorkerActive = true;
        lastOreEspRequestMs = now;
        lastOreEspStatus = "queued";
        if (!WorkerManager.get().submit(() -> {
            try {
                for (int stageRadius : oreEspStageRadii(radius)) {
                    if (pendingOreEspKey != key) return;

                    List<OrePatch> patches = OrePredictor.predictInChunkRadius(chunkX, chunkZ, stageRadius, dimension, type, maxBoxes, blockX, blockY, blockZ, seed);
                    patches = sortForStableEsp(patches, blockX, blockY, blockZ);
                    patches = filterClearedOrePatches(patches, dimension);
                    if (pendingOreEspKey == key) {
                        int patchCount = patches.size();
                        cachedOreEspPatches = patches;
                        cachedOreEspKey = key;
                        cachedOreEspCenterChunkX = chunkX;
                        cachedOreEspCenterChunkZ = chunkZ;
                        lastOreEspStatus = stageRadius >= radius ? "ready" : "partial";
                        if (patchCount != lastOreEspPatchCount && (lastOreEspPatchCount < 0 || stageRadius >= radius)) {
                            lastOreEspPatchCount = patchCount;
                            int reportedRadius = stageRadius;
                            mc.execute(() -> ChatUtils.info("Seed Explorer: ore ESP " + (reportedRadius >= radius ? "ready" : "warming up") + ", showing (highlight)" + patchCount + "(default) predicted " + type.displayName + " blocks."));
                        }
                    }
                }
                if (pendingOreEspKey == key) pendingOreEspKey = Long.MIN_VALUE;
            } catch (Throwable throwable) {
                lastOreEspStatus = "error";
                mc.execute(() -> ChatUtils.warning("Seed Explorer: ore ESP prediction failed: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage()));
            } finally {
                if (pendingOreEspKey == key) pendingOreEspKey = Long.MIN_VALUE;
                pendingOreEspCenterChunkX = Integer.MIN_VALUE;
                pendingOreEspCenterChunkZ = Integer.MIN_VALUE;
                oreEspWorkerActive = false;
            }
        })) {
            oreEspWorkerActive = false;
            pendingOreEspKey = Long.MIN_VALUE;
            pendingOreEspCenterChunkX = Integer.MIN_VALUE;
            pendingOreEspCenterChunkZ = Integer.MIN_VALUE;
            lastOreEspStatus = "queue_full";
        }
    }

    private void updateTouchedOreZone() {
        if (!oreEsp.get() || mc.player == null || mc.level == null) {
            touchedOreZone = List.of();
            touchedOreZoneDimension = Integer.MIN_VALUE;
            touchedOreZoneType = null;
            return;
        }

        int dimension = currentDimensionId();
        OreType type = oreEspType.get();
        if (type.dimension != dimension) {
            touchedOreZone = List.of();
            touchedOreZoneDimension = Integer.MIN_VALUE;
            touchedOreZoneType = null;
            return;
        }

        if (!touchedOreZone.isEmpty()) {
            if (dimension == touchedOreZoneDimension && type == touchedOreZoneType && touchesAnyOrePatch(touchedOreZone)) return;

            int cleared = SeedManager.get().markClearedOrePatches(touchedOreZoneDimension, touchedOreZone);
            if (cleared > 0) {
                cachedOreEspPatches = filterClearedOrePatches(cachedOreEspPatches, dimension);
                OreCache.get().clear();
                ChatUtils.info("Seed Explorer: saved " + cleared + " cleared " + touchedOreZoneType.displayName + " ore boxes.");
            }

            touchedOreZone = List.of();
            touchedOreZoneDimension = Integer.MIN_VALUE;
            touchedOreZoneType = null;
        }

        OrePatch touched = firstTouchedOrePatch(cachedOreEspPatches, dimension);
        if (touched == null) return;

        touchedOreZone = connectedOreZone(cachedOreEspPatches, touched);
        touchedOreZoneDimension = dimension;
        touchedOreZoneType = touched.type;
    }

    private List<OrePatch> filterClearedOrePatches(List<OrePatch> patches, int dimension) {
        if (patches.isEmpty()) return patches;

        List<OrePatch> filtered = new ArrayList<>(patches.size());
        for (OrePatch patch : patches) {
            if (!SeedManager.get().isClearedOre(patch, dimension)) filtered.add(patch);
        }
        return filtered.size() == patches.size() ? patches : List.copyOf(filtered);
    }

    private OrePatch firstTouchedOrePatch(List<OrePatch> patches, int dimension) {
        if (patches.isEmpty() || mc.player == null) return null;

        AABB playerBox = mc.player.getBoundingBox().inflate(0.04);
        for (OrePatch patch : patches) {
            if (SeedManager.get().isClearedOre(patch, dimension)) continue;
            if (playerBox.intersects(blockBox(patch))) return patch;
        }

        return null;
    }

    private boolean touchesAnyOrePatch(List<OrePatch> patches) {
        if (patches.isEmpty() || mc.player == null) return false;

        AABB playerBox = mc.player.getBoundingBox().inflate(0.04);
        for (OrePatch patch : patches) {
            if (playerBox.intersects(blockBox(patch))) return true;
        }

        return false;
    }

    private AABB blockBox(OrePatch patch) {
        return new AABB(patch.x, patch.y, patch.z, patch.x + 1.0, patch.y + 1.0, patch.z + 1.0);
    }

    private List<OrePatch> connectedOreZone(List<OrePatch> patches, OrePatch start) {
        List<OrePatch> zone = new ArrayList<>();
        ArrayDeque<OrePatch> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();

        queue.add(start);
        visited.add(oreBlockKey(start));

        while (!queue.isEmpty()) {
            OrePatch current = queue.removeFirst();
            zone.add(current);

            for (OrePatch patch : patches) {
                long key = oreBlockKey(patch);
                if (visited.contains(key) || patch.type != start.type) continue;
                if (!isConnectedOreBlock(current, patch)) continue;

                visited.add(key);
                queue.addLast(patch);
            }
        }

        return List.copyOf(zone);
    }

    private boolean isConnectedOreBlock(OrePatch a, OrePatch b) {
        return Math.abs(a.x - b.x) <= 1
            && Math.abs(a.y - b.y) <= 1
            && Math.abs(a.z - b.z) <= 1;
    }

    private long oreBlockKey(OrePatch patch) {
        long x = patch.x & 0x3FFFFFFL;
        long z = patch.z & 0x3FFFFFFL;
        long y = (patch.y + 2048L) & 0xFFFL;
        return (x << 38) | (z << 12) | y;
    }

    private int[] oreEspStageRadii(int radius) {
        radius = Math.max(0, radius);
        if (radius <= 0) return new int[]{0};
        if (radius == 1) return new int[]{0, 1};
        return new int[]{0, 1, radius};
    }

    private List<OrePatch> sortForStableEsp(List<OrePatch> patches, int blockX, int blockY, int blockZ) {
        return patches.stream()
            .sorted(Comparator
                .comparingLong((OrePatch patch) -> distanceSquared(patch, blockX, blockY, blockZ))
                .thenComparingInt(patch -> patch.x)
                .thenComparingInt(patch -> patch.y)
                .thenComparingInt(patch -> patch.z)
            )
            .toList();
    }

    private long distanceSquared(OrePatch patch, int blockX, int blockY, int blockZ) {
        long dx = patch.x - blockX;
        long dy = patch.y - blockY;
        long dz = patch.z - blockZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private long oreEspKey(long seed, OreType type, int dimension, int chunkX, int chunkZ, int radius, int maxBoxes) {
        long key = seed;
        key = key * 31 + type.ordinal();
        key = key * 31 + dimension;
        key = key * 31 + chunkX;
        key = key * 31 + chunkZ;
        key = key * 31 + radius;
        key = key * 31 + maxBoxes;
        return key;
    }

    private void resetOreEspState() {
        cachedOreEspPatches = List.of();
        cachedOreEspKey = Long.MIN_VALUE;
        pendingOreEspKey = Long.MIN_VALUE;
        oreEspWorkerActive = false;
        lastOreEspRequestMs = 0L;
        lastOreEspPatchCount = -1;
        lastOreEspStatus = "idle";
        cachedOreEspCenterChunkX = Integer.MIN_VALUE;
        cachedOreEspCenterChunkZ = Integer.MIN_VALUE;
        pendingOreEspCenterChunkX = Integer.MIN_VALUE;
        pendingOreEspCenterChunkZ = Integer.MIN_VALUE;
        touchedOreZone = List.of();
        touchedOreZoneDimension = Integer.MIN_VALUE;
        touchedOreZoneType = null;
    }
}
