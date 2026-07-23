/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.gui;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ChestLootPredictor;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.BundledLootTableLoader;
import me.seedexplorer.addon.map.BiomeGenerator;
import me.seedexplorer.addon.map.MinimapManager;
import me.seedexplorer.addon.modules.SeedExplorerModule;
import me.seedexplorer.addon.ore.OreCache;
import me.seedexplorer.addon.render.FreeCamera3D;
import me.seedexplorer.addon.render.MapLayer;
import me.seedexplorer.addon.render.MapRenderContext;
import me.seedexplorer.addon.render.MapRenderResult;
import me.seedexplorer.addon.render.MapViewport;
import me.seedexplorer.addon.render.SeedRenderer;
import me.seedexplorer.addon.render.StructureColors;
import me.seedexplorer.addon.render.StructureFocusController;
import me.seedexplorer.addon.render.StructureIcons;
import me.seedexplorer.addon.render.Terrain3DRenderer;
import me.seedexplorer.addon.render.UiIcons;
import me.seedexplorer.addon.render.BlockTextureAtlas;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureCache;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.waypoints.SeedWaypoint;
import me.seedexplorer.addon.waypoints.WaypointManager;
import me.seedexplorer.addon.workers.WorkerManager;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.Texture;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fullscreen infinite map screen for seed exploration.
 * Supports panning with left-click drag and zooming with scroll wheel.
 * Renders map layers through SeedRenderer.
 * Provides click popups for structures and waypoints.
 */
public class SeedExplorerScreen extends WidgetScreen {
    private static final int KEY_ENTER = 257;
    private static final int KEY_KP_ENTER = 335;
    private static final int KEY_SPACE = 32;
    private static final int KEY_A = 65;
    private static final int KEY_C = 67;
    private static final int KEY_D = 68;
    private static final int KEY_S = 83;
    private static final int KEY_W = 87;

    private static final int DIMENSION_BUTTON_WIDTH = 80;
    private static final int DIMENSION_BUTTON_HEIGHT = 20;
    private static final int DIMENSION_BUTTON_X = 10;
    private static final int DIMENSION_BUTTON_Y = 10;
    private static final int LAYER_BUTTON_WIDTH = 104;
    private static final int LAYER_BUTTON_COMPACT_WIDTH = 84;
    private static final int LAYER_BUTTON_HEIGHT = 22;
    private static final int LAYER_BUTTON_GAP = 5;
    private static final int LAYER_BUTTON_X = 10;
    private static final int LAYER_BUTTON_Y = 40;
    private static final int RIGHT_CONTROLS_WIDTH = 270;
    private static final int RIGHT_CONTROLS_PADDING = 10;
    private static final int STRUCTURE_POPUP_WIDTH = 220;
    private static final int STRUCTURE_POPUP_TOP_HEIGHT = 94;
    private static final int STRUCTURE_POPUP_GAP = 6;
    private static final int STRUCTURE_POPUP_ACTION_HEIGHT = 22;
    private static final int SEARCH_SUGGESTION_HEIGHT = 23;
    private static final Color UI_BG = new Color(9, 13, 18, 218);
    private static final Color UI_SURFACE = new Color(16, 24, 31, 232);
    private static final Color UI_SURFACE_2 = new Color(21, 32, 40, 225);
    private static final Color UI_LINE = new Color(54, 74, 88, 185);
    private static final Color UI_ACCENT = new Color(69, 182, 166, 230);
    private static final Color UI_ACCENT_SOFT = new Color(35, 93, 91, 225);
    private static final Color UI_TEXT = new Color(231, 237, 243);
    private static final Color UI_MUTED = new Color(141, 154, 166);
    private static final Color UI_DISABLED = new Color(88, 96, 104);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final SeedRenderer seedRenderer = new SeedRenderer();

    private boolean mode3d;
    private boolean undergroundView;
    private boolean textureDebugView;
    private final FreeCamera3D camera3d = new FreeCamera3D();
    private final StructureFocusController focusController = new StructureFocusController();
    private final Terrain3DRenderer terrain3DRenderer = new Terrain3DRenderer();
    private boolean dragging3d;
    private boolean manual3dNavigation;
    private double drag3dStartX, drag3dStartY;

    private double offsetX, offsetZ;
    private double zoom = 1.0;
    private double targetZoom = 1.0;

    private boolean dragging;
    private double dragStartX, dragStartY;
    private double dragOffsetX, dragOffsetZ;

    // Dimension selector
    private int selectedDimension = 0; // 0=Overworld, -1=Nether, 1=End

    // Structure data
    private List<GeneratedStructure> visibleStructures = new ArrayList<>();
    private GeneratedStructure hoveredStructure;
    private GeneratedStructure selectedStructure;
    private GeneratedStructure chestOverlayStructure;
    private List<ChestLootOutput> chestOverlayChests = List.of();
    private ChestLootOutput selected3dChest;
    private int selected3dChestIndex = -1;
    private boolean chestOverlayLoading;
    private long chestOverlayRequestId;
    private int chestPanelPrevX, chestPanelPrevY, chestPanelPrevW, chestPanelPrevH;
    private int chestPanelNextX, chestPanelNextY, chestPanelNextW, chestPanelNextH;
    private int chestPanelCopyX, chestPanelCopyY, chestPanelCopyW, chestPanelCopyH;
    private int chestPanelExportX, chestPanelExportY, chestPanelExportW, chestPanelExportH;

    // Waypoint data
    private List<SeedWaypoint> visibleWaypoints = new ArrayList<>();
    private SeedWaypoint hoveredWaypoint;
    private SeedWaypoint selectedWaypoint;

    // Context menu
    private boolean contextMenuOpen;
    private double contextMenuX, contextMenuY;
    private boolean isWaypointContextMenu; // true if context menu is for a waypoint, false for structure
    private boolean structurePanelOpen;

    // Top controls
    private WTextBox seedBox;
    private WDropdown<String> versionDropdown;
    private WTextBox searchBar;
    private boolean suppressNextEnterAction;
    private boolean applyingSeed;
    private String lastSearchText = "";
    private StructureType searchStructureFilter;
    private String searchSuggestion = "";
    private final Set<String> lootItemFilters = new LinkedHashSet<>();
    private final Map<Long, Boolean> lootFilterCache = new HashMap<>();
    private final Set<Long> lootFilterInFlight = new HashSet<>();
    private String lastLootFilterSignature = "";
    private long lastLootFilterSeed = Long.MIN_VALUE;

    // Biome name to ID mapping for search
    private static final Map<String, Integer> BIOME_NAME_TO_ID = new HashMap<>();
    static {
        BIOME_NAME_TO_ID.put("ocean", 0);
        BIOME_NAME_TO_ID.put("deep ocean", 1);
        BIOME_NAME_TO_ID.put("warm ocean", 2);
        BIOME_NAME_TO_ID.put("lukewarm ocean", 3);
        BIOME_NAME_TO_ID.put("cold ocean", 4);
        BIOME_NAME_TO_ID.put("deep lukewarm ocean", 5);
        BIOME_NAME_TO_ID.put("deep cold ocean", 6);
        BIOME_NAME_TO_ID.put("deep frozen ocean", 7);
        BIOME_NAME_TO_ID.put("plains", 8);
        BIOME_NAME_TO_ID.put("sunflower plains", 9);
        BIOME_NAME_TO_ID.put("forest", 10);
        BIOME_NAME_TO_ID.put("dark forest", 11);
        BIOME_NAME_TO_ID.put("birch forest", 12);
        BIOME_NAME_TO_ID.put("old growth birch forest", 13);
        BIOME_NAME_TO_ID.put("taiga", 14);
        BIOME_NAME_TO_ID.put("old growth taiga", 15);
        BIOME_NAME_TO_ID.put("snowy taiga", 16);
        BIOME_NAME_TO_ID.put("snowy plains", 17);
        BIOME_NAME_TO_ID.put("snowy slopes", 18);
        BIOME_NAME_TO_ID.put("ice spikes", 19);
        BIOME_NAME_TO_ID.put("savanna", 20);
        BIOME_NAME_TO_ID.put("savanna plateau", 21);
        BIOME_NAME_TO_ID.put("desert", 22);
        BIOME_NAME_TO_ID.put("badlands", 23);
        BIOME_NAME_TO_ID.put("windswept hills", 24);
        BIOME_NAME_TO_ID.put("windswept gravelly hills", 25);
        BIOME_NAME_TO_ID.put("windswept forest", 26);
        BIOME_NAME_TO_ID.put("stony peaks", 27);
        BIOME_NAME_TO_ID.put("meadow", 28);
        BIOME_NAME_TO_ID.put("jungle", 29);
        BIOME_NAME_TO_ID.put("sparse jungle", 30);
        BIOME_NAME_TO_ID.put("bamboo jungle", 31);
        BIOME_NAME_TO_ID.put("swamp", 32);
        BIOME_NAME_TO_ID.put("beach", 33);
        BIOME_NAME_TO_ID.put("river", 34);
        BIOME_NAME_TO_ID.put("mushroom fields", 35);
        BIOME_NAME_TO_ID.put("nether wastes", 36);
        BIOME_NAME_TO_ID.put("crimson forest", 37);
        BIOME_NAME_TO_ID.put("warped forest", 38);
        BIOME_NAME_TO_ID.put("soul sand valley", 39);
        BIOME_NAME_TO_ID.put("basalt deltas", 40);
        BIOME_NAME_TO_ID.put("the end", 41);
        BIOME_NAME_TO_ID.put("end midlands", 42);
        BIOME_NAME_TO_ID.put("end barrens", 43);
        BIOME_NAME_TO_ID.put("small end islands", 44);
        BIOME_NAME_TO_ID.put("cherry grove", 45);
        BIOME_NAME_TO_ID.put("pale garden", 46);
        BIOME_NAME_TO_ID.put("frozen ocean", 47);
        BIOME_NAME_TO_ID.put("frozen river", 48);
    }

    public SeedExplorerScreen(GuiTheme theme) {
        super(theme, "Seed Explorer");
        centerOnCurrentPlayer();
        focusController.bindCamera(camera3d);
    }

    @Override
    public void initWidgets() {
        WVerticalList topRightControls = theme.verticalList();
        topRightControls.spacing = 4;

        seedBox = theme.textBox(seedBoxInitialValue(), "Seed (number or text)...");
        seedBox.actionOnUnfocused = this::onSeedChanged;
        topRightControls.add(seedBox).padTop(10).padRight(RIGHT_CONTROLS_PADDING).minWidth(RIGHT_CONTROLS_WIDTH);

        versionDropdown = theme.dropdown(supportedVersionLabels(), currentVersionSelection());
        versionDropdown.action = this::onVersionChanged;
        versionDropdown.tooltip = "Loot/profile version selector. Terrain and structures use the running Minecraft worldgen engine.";
        topRightControls.add(versionDropdown).padRight(RIGHT_CONTROLS_PADDING).minWidth(RIGHT_CONTROLS_WIDTH);

        searchBar = theme.textBox("", "Search (X Z, structure, biome, loot:item)...");
        searchBar.action = this::updateSearchState;
        searchBar.actionOnUnfocused = this::updateSearchState;
        enterAction = this::onSearch;
        topRightControls.add(searchBar).padRight(RIGHT_CONTROLS_PADDING).minWidth(RIGHT_CONTROLS_WIDTH);

        add(topRightControls).top().right();
    }

    private String seedBoxInitialValue() {
        long seed = SeedManager.get().getWorldSeed();
        return seed == 0 ? "" : Long.toString(seed);
    }

    private String[] supportedVersionLabels() {
        BundledLootTableLoader.VersionProfile[] profiles = BundledLootTableLoader.supportedVersions();
        String[] versions = new String[profiles.length];
        for (int i = 0; i < profiles.length; i++) {
            versions[i] = profiles[i].minecraftVersion();
        }
        return versions;
    }

    private String currentVersionSelection() {
        String current = SeedManager.get().getMcVersion();
        if (current == null || current.isBlank()) {
            return BundledLootTableLoader.runtimeProfile().minecraftVersion();
        }
        String[] supported = supportedVersionLabels();
        for (String version : supported) {
            if (version.equals(current)) return version;
        }
        return BundledLootTableLoader.VersionProfile.forVersion(current).minecraftVersion();
    }

    @Override
    protected void onRenderBefore(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Minecraft mc = Minecraft.getInstance();
        double screenWidth = mc.getWindow().getWidth();
        double screenHeight = mc.getWindow().getHeight();
        SeedExplorerModule module = Modules.get().get(SeedExplorerModule.class);

        syncDimensionToPlayer(mc);
        updateSearchState();

        Renderer2D clear = Renderer2D.COLOR;
        clear.begin();
        clear.quad(0, 0, screenWidth, screenHeight, new Color(0, 0, 0, 255));
        clear.render();

        if (mode3d) {
            render3dView(graphics, mouseX, mouseY, delta, screenWidth, screenHeight, module, mc);
        } else {
            render2dView(graphics, mouseX, mouseY, delta, screenWidth, screenHeight, module, mc);
        }

        // Empty state: with no seed set the map is blank; prompt the user instead.
        if (SeedManager.get().getWorldSeed() == 0) {
            drawNoSeedState(screenWidth, screenHeight);
        }

        drawTopControlBackground(screenWidth);
        drawDimensionSelector();
        drawLayerToggles(screenWidth);
        drawSearchSuggestion();
        drawLootFilterStatus(screenWidth);
        if (mode3d) drawAtlasStatus(screenWidth);
        if (structurePanelOpen) drawStructurePanel(screenWidth, screenHeight);
        if (mode3d) drawSelected3dChestPanel(screenWidth, screenHeight);

        if (hoveredWaypoint != null && !contextMenuOpen && shouldShowWaypointTooltips(module)) {
            String tooltip = "Waypoint: " + hoveredWaypoint.name + " [" + hoveredWaypoint.x + ", " + hoveredWaypoint.z + "]";
            theme.textRenderer().begin(1.0);
            double tx = mouseX + 12;
            double ty = mouseY + 12;
            if (tx + 200 > screenWidth) tx = mouseX - 200;
            if (ty + 20 > screenHeight) ty = mouseY - 30;
            theme.textRenderer().render(tooltip, tx, ty, SeedRenderer.WAYPOINT_MARKER_COLOR);
            theme.textRenderer().end();
        }

        if (contextMenuOpen) {
            drawContextMenu();
        }
    }

    private void render2dView(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta,
                               double screenWidth, double screenHeight,
                               SeedExplorerModule module, Minecraft mc) {
        if (module != null) applyPlayerAutoCenter(mc, module);
        updateSmoothZoom(delta);

        // The viewport is built in physical (framebuffer) pixels, so the mouse must be
        // converted from GUI-scaled coordinates the same way mouseClicked() does. Otherwise
        // hover detection and the coordinate readout drift at any GUI scale other than 1.
        MapViewport viewport = MapViewport.create(offsetX, offsetZ, zoom, screenWidth, screenHeight);
        MapRenderContext baseContext = new MapRenderContext(
            viewport,
            (int) Math.round(toRenderX(mouseX)),
            (int) Math.round(toRenderY(mouseY)),
            delta,
            selectedDimension,
            getEnabledLayers(module),
            shouldShowPlayerInfo(module),
            generationMargin(module),
            biomeTileMargin(module),
            biomeOpacity(module),
            oreOpacity(module),
            oreMarkerScale(module),
            structureMarkerScale(module),
            waypointMarkerScale(module),
            dimWaypointStructures(module),
            loadedTerrainOnMap(module),
            searchStructureFilter,
            null
        );
        if (!lootItemFilters.isEmpty()) {
            scheduleLootFilterChecks(seedRenderer.queryStructures(baseContext));
        }
        MapRenderContext context = new MapRenderContext(
            viewport,
            (int) Math.round(toRenderX(mouseX)),
            (int) Math.round(toRenderY(mouseY)),
            delta,
            selectedDimension,
            getEnabledLayers(module),
            shouldShowPlayerInfo(module),
            generationMargin(module),
            biomeTileMargin(module),
            biomeOpacity(module),
            oreOpacity(module),
            oreMarkerScale(module),
            structureMarkerScale(module),
            waypointMarkerScale(module),
            dimWaypointStructures(module),
            loadedTerrainOnMap(module),
            searchStructureFilter,
            lootMatchedStructureKeys()
        );
        MapRenderResult result = seedRenderer.render(context, theme);

        visibleStructures = result.structures();
        visibleWaypoints = result.waypoints();
        hoveredStructure = result.hoveredStructure();
        hoveredWaypoint = result.hoveredWaypoint();
    }

    private void render3dView(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta,
                               double screenWidth, double screenHeight,
                               SeedExplorerModule module, Minecraft mc) {
        focusController.tick();

        // Follow player in 3D mode when not in focus mode. Underground view aims
        // at a subsurface band (Y=32) instead of the surface (Y=60); without this,
        // auto-center would snap the underground camera back to Y=60 every frame.
        if (!manual3dNavigation && !focusController.isActive()
            && mc.player != null && module != null && module.autoCenterPlayer.get()) {
            double targetY = undergroundView ? 32 : 60;
            camera3d.target(mc.player.getX(), targetY, mc.player.getZ());
        }

        EnumSet<MapLayer> layers3d = EnumSet.noneOf(MapLayer.class);
        if (module != null) {
            if (module.isLayerEnabled(MapLayer.STRUCTURES)) layers3d.add(MapLayer.STRUCTURES);
            if (module.isLayerEnabled(MapLayer.WAYPOINTS)) layers3d.add(MapLayer.WAYPOINTS);
        }

        MapViewport dummyViewport = MapViewport.create(camera3d.targetX(), camera3d.targetZ(), 1.0, screenWidth, screenHeight);
        MapRenderContext baseContext3d = new MapRenderContext(
            dummyViewport,
            (int) Math.round(toRenderX(mouseX)),
            (int) Math.round(toRenderY(mouseY)),
            delta,
            selectedDimension,
            layers3d,
            false, 0, 0, 255, 200, 1.0, 1.0, 1.0, false, false, searchStructureFilter,
            null
        );
        if (!lootItemFilters.isEmpty()) {
            scheduleLootFilterChecks(seedRenderer.queryStructures(baseContext3d));
        }
        MapRenderContext context3d = new MapRenderContext(
            dummyViewport,
            (int) Math.round(toRenderX(mouseX)),
            (int) Math.round(toRenderY(mouseY)),
            delta,
            selectedDimension,
            layers3d,
            false, 0, 0, 255, 200, 1.0, 1.0, 1.0, false, false, searchStructureFilter,
            lootMatchedStructureKeys()
        );

        visibleStructures = seedRenderer.queryStructures(context3d);
        visibleWaypoints = seedRenderer.queryWaypoints(context3d);

        double centerX = screenWidth / 2.0;
        double centerY = screenHeight / 2.0;

        hoveredStructure = terrain3DRenderer.pickStructure(
            camera3d, toRenderX(mouseX), toRenderY(mouseY),
            visibleStructures, context3d, centerX, centerY
        );

        terrain3DRenderer.render(camera3d, context3d, visibleStructures, visibleWaypoints,
            hoveredStructure, hoveredWaypoint, undergroundView, textureDebugView);

        terrain3DRenderer.renderChestMarkers(camera3d, chestOverlayChests, selected3dChest,
            centerX, centerY, Minecraft.getInstance());

        if (focusController.isActive()) {
            focusController.renderOverlay(graphics);
        }
    }

    private EnumSet<MapLayer> getEnabledLayers(SeedExplorerModule module) {
        EnumSet<MapLayer> layers = EnumSet.allOf(MapLayer.class);
        if (module == null) return layers;

        for (MapLayer layer : MapLayer.values()) {
            if (!module.isLayerEnabled(layer)) layers.remove(layer);
        }
        return layers;
    }

    private boolean shouldShowPlayerInfo(SeedExplorerModule module) {
        return module == null || module.showPlayerInfo.get();
    }

    private int generationMargin(SeedExplorerModule module) {
        return module == null ? 10 : module.generationMargin.get();
    }

    private int biomeTileMargin(SeedExplorerModule module) {
        return module == null ? 2 : module.biomeTileMargin.get();
    }

    private int biomeOpacity(SeedExplorerModule module) {
        return module == null ? 255 : module.biomeOpacity.get();
    }

    private int oreOpacity(SeedExplorerModule module) {
        return module == null ? 200 : module.oreOpacity.get();
    }

    private double oreMarkerScale(SeedExplorerModule module) {
        return module == null ? 1.0 : module.oreMarkerScale.get();
    }

    private double structureMarkerScale(SeedExplorerModule module) {
        return module == null ? 1.0 : module.structureMarkerScale.get();
    }

    private double waypointMarkerScale(SeedExplorerModule module) {
        return module == null ? 1.0 : module.waypointMarkerScale.get();
    }

    private boolean dimWaypointStructures(SeedExplorerModule module) {
        return module == null || module.dimWaypointStructures.get();
    }

    private boolean loadedTerrainOnMap(SeedExplorerModule module) {
        return module != null && module.loadedTerrainOnMap.get();
    }

    private boolean shouldShowWaypointTooltips(SeedExplorerModule module) {
        return module == null || module.waypointTooltips.get();
    }

    private void syncDimensionToPlayer(Minecraft mc) {
        int playerDimension = currentDimensionId(mc);
        if (selectedDimension == playerDimension) return;

        selectedDimension = playerDimension;
        if (mc.player != null) {
            offsetX = mc.player.getX();
            offsetZ = mc.player.getZ();
            camera3d.target(offsetX, 0, offsetZ);
        }
        selectedStructure = null;
        selectedWaypoint = null;
        contextMenuOpen = false;
        lastSearchText = "";
        searchStructureFilter = null;
        searchSuggestion = "";
        StructureCache.get().clear();
        OreCache.get().clear();
    }

    private void centerOnCurrentPlayer() {
        Minecraft mc = Minecraft.getInstance();
        selectedDimension = currentDimensionId(mc);
        if (mc.player == null) return;

        offsetX = mc.player.getX();
        offsetZ = mc.player.getZ();
    }

    private void toggle3dMode() {
        mode3d = !mode3d;
        if (mode3d) {
            Minecraft mc = Minecraft.getInstance();
            double px = (mc.player != null) ? mc.player.getX() : offsetX;
            double pz = (mc.player != null) ? mc.player.getZ() : offsetZ;
            camera3d.target(px, 60, pz);
            camera3d.pitch(Math.toRadians(60));
            camera3d.yaw(0);
            camera3d.distance(80);
            manual3dNavigation = false;
            focusController.cancelFocus();
        } else {
            undergroundView = false;
            textureDebugView = false;
        }
    }

    private void toggleUndergroundView() {
        undergroundView = !undergroundView;
        if (undergroundView) {
            // Look at a subsurface band so the stacked layers are centered.
            camera3d.target(camera3d.targetX(), 32, camera3d.targetZ());
            camera3d.pitch(Math.toRadians(70));
            camera3d.distance(60);
            manual3dNavigation = true;
            focusController.cancelFocus();
        } else {
            camera3d.target(camera3d.targetX(), 60, camera3d.targetZ());
            camera3d.pitch(Math.toRadians(60));
        }
    }

    private void toggleTextureDebugView() {
        textureDebugView = !textureDebugView;
    }

    private void applyPlayerAutoCenter(Minecraft mc, SeedExplorerModule module) {
        if (module == null || !module.autoCenterPlayer.get() || mc.player == null || mc.level == null) return;
        if (currentDimensionId(mc) != selectedDimension) return;

        offsetX = mc.player.getX();
        offsetZ = mc.player.getZ();
    }

    private int currentDimensionId(Minecraft mc) {
        if (mc.level == null) return 0;
        if (mc.level.dimension() == Level.NETHER) return -1;
        if (mc.level.dimension() == Level.END) return 1;
        return 0;
    }

    private void drawDimensionSelector() {
        int wpCount = WaypointManager.get().getSeedWaypoints(selectedDimension).size();
        String label = dimensionName(selectedDimension);
        String count = wpCount + " WP";
        int width = DIMENSION_BUTTON_WIDTH * 2 + 28;

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(DIMENSION_BUTTON_X + 1, DIMENSION_BUTTON_Y + 2, width, DIMENSION_BUTTON_HEIGHT, new Color(0, 0, 0, 90));
        r.quad(DIMENSION_BUTTON_X, DIMENSION_BUTTON_Y, width, DIMENSION_BUTTON_HEIGHT, UI_SURFACE);
        r.quad(DIMENSION_BUTTON_X, DIMENSION_BUTTON_Y, 3, DIMENSION_BUTTON_HEIGHT, UI_ACCENT);
        r.boxLines(DIMENSION_BUTTON_X, DIMENSION_BUTTON_Y, width, DIMENSION_BUTTON_HEIGHT, UI_LINE);
        r.render();
        theme.textRenderer().begin(1.0);
        theme.textRenderer().render(label, DIMENSION_BUTTON_X + 9, DIMENSION_BUTTON_Y + 4, UI_TEXT);
        theme.textRenderer().render(count, DIMENSION_BUTTON_X + width - theme.textWidth(count) - 8, DIMENSION_BUTTON_Y + 4, UI_MUTED);
        theme.textRenderer().end();
    }

    private void drawLootFilterStatus(double screenWidth) {
        if (lootItemFilters.isEmpty()) return;

        String items = String.join(" + ", lootItemFilters).replace("minecraft:", "");
        long matched = lootFilterCache.values().stream().filter(Boolean.TRUE::equals).count();
        String text = "Loot " + items + "  " + matched + "/" + lootFilterCache.size();
        if (!lootFilterInFlight.isEmpty()) text += " +" + lootFilterInFlight.size();

        double width = Math.min(screenWidth - 20, Math.max(150, theme.textWidth(text) + 24));
        double x = 10;
        double y = topControlHeight(screenWidth) + 8;

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(x + 1, y + 2, width, 23, new Color(0, 0, 0, 90));
        r.quad(x, y, width, 23, new Color(31, 58, 54, 230));
        r.quad(x, y, 3, 23, UI_ACCENT);
        r.boxLines(x, y, width, 23, UI_ACCENT);
        r.render();

        theme.textRenderer().begin(1.0);
        theme.textRenderer().render(trimToWidth(text, width - 16), x + 9, y + 6, UI_TEXT);
        theme.textRenderer().end();
    }

    private void drawAtlasStatus(double screenWidth) {
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        boolean fallbackOnly = atlas.loadedCount() == 0;
        String text = fallbackOnly
            ? "3D atlas fallback"
            : "3D atlas " + atlas.loadedCount() + "/" + atlas.requestedCount();
        double width = Math.max(104, theme.textWidth(text) + 24);
        double x = screenWidth - width - 10;
        double y = topControlHeight(screenWidth) + 8;

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(x + 1, y + 2, width, 19, new Color(0, 0, 0, 80));
        r.quad(x, y, width, 19, fallbackOnly ? new Color(64, 56, 27, 230) : new Color(20, 34, 40, 230));
        r.quad(x, y, 3, 19, fallbackOnly ? new Color(224, 180, 84, 235) : UI_ACCENT);
        r.boxLines(x, y, width, 19, fallbackOnly ? new Color(232, 196, 108, 235) : UI_LINE);
        r.render();

        theme.textRenderer().begin(1.0);
        theme.textRenderer().render(text, x + 9, y + 5, fallbackOnly ? new Color(242, 208, 139) : UI_TEXT);
        theme.textRenderer().end();
    }

    private void drawLayerToggles(double screenWidth) {
        SeedExplorerModule module = Modules.get().get(SeedExplorerModule.class);
        if (module == null) return;

        int maxPerRow = maxLayerButtonsPerRow(screenWidth);
        int buttonWidth = layerButtonWidth(screenWidth);
        int layerCount = MapLayer.values().length;
        Renderer2D r = Renderer2D.COLOR;
        r.begin();

        int i = 0;
        for (MapLayer layer : MapLayer.values()) {
            int row = i / maxPerRow;
            int col = i % maxPerRow;
            int x = LAYER_BUTTON_X + col * (buttonWidth + LAYER_BUTTON_GAP);
            int y = LAYER_BUTTON_Y + row * (LAYER_BUTTON_HEIGHT + LAYER_BUTTON_GAP);
            boolean enabled = module.isLayerEnabled(layer);
            boolean panelOpen = layer == MapLayer.STRUCTURES && structurePanelOpen;
            Color shadow = new Color(0, 0, 0, 75);
            Color bgColor = enabled ? UI_ACCENT_SOFT : UI_SURFACE;
            Color topLine = enabled ? UI_ACCENT : UI_LINE;
            Color border = panelOpen
                ? new Color(226, 182, 76, 235)
                : enabled ? UI_ACCENT : UI_LINE;

            r.quad(x + 1, y + 2, buttonWidth, LAYER_BUTTON_HEIGHT, shadow);
            r.quad(x, y, buttonWidth, LAYER_BUTTON_HEIGHT, bgColor);
            r.quad(x, y, buttonWidth, 2, topLine);
            r.boxLines(x, y, buttonWidth, LAYER_BUTTON_HEIGHT, border);
            i++;
        }

        // 3D Map toggle button
        int modeRow = layerCount / maxPerRow;
        int modeCol = layerCount % maxPerRow;
        int modeX = LAYER_BUTTON_X + modeCol * (buttonWidth + LAYER_BUTTON_GAP);
        int modeY = LAYER_BUTTON_Y + modeRow * (LAYER_BUTTON_HEIGHT + LAYER_BUTTON_GAP);
        boolean modeEnabled = mode3d;
        Color modeShadow = new Color(0, 0, 0, 80);
        Color modeBgColor = modeEnabled ? new Color(45, 78, 62, 228) : UI_SURFACE;
        Color modeTopLine = modeEnabled ? new Color(120, 220, 150, 225) : UI_LINE;
        Color modeBorder = modeEnabled ? new Color(120, 220, 150, 235) : UI_LINE;

        r.quad(modeX + 1, modeY + 2, buttonWidth, LAYER_BUTTON_HEIGHT, modeShadow);
        r.quad(modeX, modeY, buttonWidth, LAYER_BUTTON_HEIGHT, modeBgColor);
        r.quad(modeX, modeY, buttonWidth, 2, modeTopLine);
        r.boxLines(modeX, modeY, buttonWidth, LAYER_BUTTON_HEIGHT, modeBorder);

        // Underground and texture-debug toggles are only shown in 3D mode.
        int underY = modeY + LAYER_BUTTON_HEIGHT + LAYER_BUTTON_GAP;
        int textureY = underY + LAYER_BUTTON_HEIGHT + LAYER_BUTTON_GAP;
        if (mode3d) {
            boolean underEnabled = undergroundView;
            Color underBg = underEnabled ? new Color(43, 54, 84, 228) : UI_SURFACE;
            Color underTop = underEnabled ? new Color(98, 138, 220, 225) : UI_LINE;
            Color underBorder = underEnabled ? new Color(126, 164, 240, 235) : UI_LINE;
            r.quad(modeX + 1, underY + 2, buttonWidth, LAYER_BUTTON_HEIGHT, modeShadow);
            r.quad(modeX, underY, buttonWidth, LAYER_BUTTON_HEIGHT, underBg);
            r.quad(modeX, underY, buttonWidth, 2, underTop);
            r.boxLines(modeX, underY, buttonWidth, LAYER_BUTTON_HEIGHT, underBorder);

            Color textureBg = textureDebugView ? new Color(82, 36, 74, 228) : UI_SURFACE;
            Color textureTop = textureDebugView ? new Color(255, 90, 215, 225) : UI_LINE;
            Color textureBorder = textureDebugView ? new Color(255, 135, 32, 235) : UI_LINE;
            r.quad(modeX + 1, textureY + 2, buttonWidth, LAYER_BUTTON_HEIGHT, modeShadow);
            r.quad(modeX, textureY, buttonWidth, LAYER_BUTTON_HEIGHT, textureBg);
            r.quad(modeX, textureY, buttonWidth, 2, textureTop);
            r.boxLines(modeX, textureY, buttonWidth, LAYER_BUTTON_HEIGHT, textureBorder);
        }

        r.render();

        drawLayerIcons(maxPerRow, module, buttonWidth);

        theme.textRenderer().begin(1.0);
        i = 0;
        for (MapLayer layer : MapLayer.values()) {
            int row = i / maxPerRow;
            int col = i % maxPerRow;
            int x = LAYER_BUTTON_X + col * (buttonWidth + LAYER_BUTTON_GAP);
            int y = LAYER_BUTTON_Y + row * (LAYER_BUTTON_HEIGHT + LAYER_BUTTON_GAP);
            boolean enabled = module.isLayerEnabled(layer);
            Color textColor = enabled ? UI_TEXT : UI_MUTED;
            double textX = x + (layerIcon(layer) == null ? 8 : 27);
            double maxTextWidth = buttonWidth - (layerIcon(layer) == null ? 15 : 34);
            theme.textRenderer().render(trimToWidth(layerLabel(layer), maxTextWidth), textX, y + 5, textColor);
            i++;
        }

        // 3D Map label
        Color modeTextColor = modeEnabled ? new Color(185, 245, 195) : UI_MUTED;
        theme.textRenderer().render("3D Map", modeX + 8, modeY + 5, modeTextColor);

        // Underground label
        if (mode3d) {
            Color underTextColor = undergroundView ? new Color(170, 200, 255) : UI_MUTED;
            theme.textRenderer().render("Underground", modeX + 8, underY + 5, underTextColor);
            Color textureTextColor = textureDebugView ? new Color(255, 172, 230) : UI_MUTED;
            theme.textRenderer().render("Texture Debug", modeX + 8, textureY + 5, textureTextColor);
        }

        theme.textRenderer().end();
    }

    private void drawLayerIcons(int maxPerRow, SeedExplorerModule module, int buttonWidth) {
        Renderer2D tex = Renderer2D.TEXTURE;
        int i = 0;
        for (MapLayer layer : MapLayer.values()) {
            Texture icon = layerIcon(layer);
            if (icon == null) {
                i++;
                continue;
            }

            int row = i / maxPerRow;
            int col = i % maxPerRow;
            int x = LAYER_BUTTON_X + col * (buttonWidth + LAYER_BUTTON_GAP);
            int y = LAYER_BUTTON_Y + row * (LAYER_BUTTON_HEIGHT + LAYER_BUTTON_GAP);
            boolean enabled = module.isLayerEnabled(layer);
            Color tint = enabled ? Color.WHITE : new Color(130, 136, 142, 120);
            tex.begin();
            tex.texQuad(x + 6, y + 4, 14, 14, tint);
            tex.render(icon.getTextureView(), icon.getSampler());
            i++;
        }
    }

    private String layerLabel(MapLayer layer) {
        return switch (layer) {
            case CHUNK_BORDERS -> "Chunks";
            case COORDINATES -> "Coords";
            default -> layer.title;
        };
    }

    private Texture layerIcon(MapLayer layer) {
        return switch (layer) {
            case TERRAIN -> UiIcons.get("terrain");
            case ORES -> UiIcons.get("ores");
            case WAYPOINTS -> UiIcons.get("waypoints");
            case PLAYER -> UiIcons.get("player");
            default -> null;
        };
    }

    private void drawTopControlBackground(double screenWidth) {
        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        double height = topControlHeight(screenWidth);
        r.quad(0, 0, screenWidth, height, UI_BG);
        r.quad(0, height - 1, screenWidth, 1, UI_LINE);
        r.render();
    }

    private void drawNoSeedState(double screenWidth, double screenHeight) {
        Renderer2D r = Renderer2D.COLOR;
        String title = "No world seed set";
        String hint = "Enter a seed in the box at the top right to begin exploring.";
        var text = theme.textRenderer();
        double titleW = text.getWidth(title);
        double hintW = text.getWidth(hint);
        double boxW = Math.max(titleW, hintW) + 48;
        double boxH = 58;
        double boxX = (screenWidth - boxW) / 2.0;
        double boxY = (screenHeight - boxH) / 2.0;

        r.begin();
        r.quad(boxX, boxY, boxW, boxH, UI_SURFACE);
        r.quad(boxX, boxY, 4, boxH, UI_ACCENT);
        r.boxLines(boxX, boxY, boxW, boxH, UI_LINE);
        r.render();

        text.begin(1.0, false, true);
        text.render(title, boxX + (boxW - titleW) / 2.0, boxY + 12, UI_TEXT);
        text.render(hint, boxX + (boxW - hintW) / 2.0, boxY + 30, UI_MUTED);
        text.end();
    }

    private void drawSearchSuggestion() {
        if (searchBar == null || searchSuggestion.isBlank()) return;

        double x = searchBar.x;
        double y = searchBar.y + searchBar.height + 3;
        double width = Math.max(searchBar.width, RIGHT_CONTROLS_WIDTH);

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(x, y, width, SEARCH_SUGGESTION_HEIGHT, UI_SURFACE);
        r.quad(x, y, 3, SEARCH_SUGGESTION_HEIGHT, UI_ACCENT);
        r.boxLines(x, y, width, SEARCH_SUGGESTION_HEIGHT, UI_LINE);
        r.render();

        Texture icon = searchStructureFilter == null ? null : StructureIcons.get(searchStructureFilter);
        if (icon != null) {
            Renderer2D tex = Renderer2D.TEXTURE;
            tex.begin();
            tex.texQuad(x + 5, y + 4, 15, 15, Color.WHITE);
            tex.render(icon.getTextureView(), icon.getSampler());
        }

        String text = searchSuggestion;
        if (!lootItemFilters.isEmpty()) {
            long matched = lootFilterCache.values().stream().filter(Boolean.TRUE::equals).count();
            text = searchSuggestion + "  |  matched " + matched + ", checking " + lootFilterInFlight.size();
        }

        theme.textRenderer().begin(1.0);
        theme.textRenderer().render(text, x + (icon == null ? 8 : 25), y + 6, UI_TEXT);
        theme.textRenderer().end();
    }

    private boolean handleSearchSuggestionClick(double mouseX, double mouseY) {
        if (searchBar == null || searchSuggestion.isBlank()) return false;

        double x = searchBar.x;
        double y = searchBar.y + searchBar.height + 3;
        double width = Math.max(searchBar.width, RIGHT_CONTROLS_WIDTH);
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + SEARCH_SUGGESTION_HEIGHT) return false;

        if (searchStructureFilter != null) jumpToSearchResult();
        return true;
    }

    private int topControlHeight(double screenWidth) {
        // Mirror drawLayerToggles: the 3D Map button sits at modeRow = layerCount /
        // maxPerRow (integer division) in the same column slot just past the layer
        // grid; in 3D mode extra 3D diagnostic buttons sit below it. The control
        // bar must cover the lowest button row.
        int maxPerRow = maxLayerButtonsPerRow(screenWidth);
        int layerCount = MapLayer.values().length;
        int modeRow = layerCount / maxPerRow;
        int rows = modeRow + 1 + (mode3d ? 2 : 0);
        return Math.max(68, LAYER_BUTTON_Y + rows * (LAYER_BUTTON_HEIGHT + LAYER_BUTTON_GAP) + 8);
    }

    private int maxLayerButtonsPerRow(double screenWidth) {
        int buttonWidth = layerButtonWidth(screenWidth);
        double availableWidth = screenWidth - LAYER_BUTTON_X - RIGHT_CONTROLS_WIDTH - RIGHT_CONTROLS_PADDING * 2;
        if (availableWidth < buttonWidth) availableWidth = screenWidth - LAYER_BUTTON_X * 2;
        return Math.max(1, (int) ((availableWidth + LAYER_BUTTON_GAP) / (buttonWidth + LAYER_BUTTON_GAP)));
    }

    private int layerButtonWidth(double screenWidth) {
        return screenWidth < 760 ? LAYER_BUTTON_COMPACT_WIDTH : LAYER_BUTTON_WIDTH;
    }

    private void drawContextMenu() {
        if (!isWaypointContextMenu && selectedStructure != null) {
            drawStructurePopup();
            return;
        }

        String[] items;
        int itemHeight = 20;
        int menuWidth;

        if (isWaypointContextMenu) {
            items = new String[]{"Copy Coords", "Delete Waypoint", "Center Map"};
            menuWidth = 160;
        } else {
            boolean hasWaypoint = selectedStructure != null && WaypointManager.get().hasWaypointAt(
                selectedStructure.x, selectedStructure.z, selectedDimension);
            if (hasWaypoint) {
                items = new String[]{"Copy Coords", "Remove Waypoint", "Center Map", "TP"};
            } else {
                items = new String[]{"Copy Coords", "Create Waypoint", "Center Map", "TP"};
            }
            menuWidth = 160;
        }

        int menuHeight = items.length * itemHeight;

        // Clamp to screen
        Minecraft mc = Minecraft.getInstance();
        double screenWidth = mc.getWindow().getWidth();
        double screenHeight = mc.getWindow().getHeight();

        double mx = Math.min(contextMenuX, screenWidth - menuWidth);
        double my = Math.min(contextMenuY, screenHeight - menuHeight);
        if (mx < 0) mx = 0;
        if (my < 0) my = 0;

        // Background
        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(mx, my, menuWidth, menuHeight, new Color(20, 20, 30, 230));
        r.render();

        // Items
        theme.textRenderer().begin(1.0);
        for (int i = 0; i < items.length; i++) {
            double iy = my + i * itemHeight;
            Color itemColor = new Color(200, 200, 200);
            theme.textRenderer().render(items[i], mx + 8, iy + 4, itemColor);
        }
        theme.textRenderer().end();

        // Item dividers
        r.begin();
        for (int i = 1; i < items.length; i++) {
            double iy = my + i * itemHeight;
            r.quad(mx, iy, menuWidth, 1, new Color(60, 60, 80, 200));
        }
        r.render();
    }

    private void drawStructurePopup() {
        GeneratedStructure structure = selectedStructure;
        if (structure == null) return;

        boolean hasWaypoint = WaypointManager.get().hasWaypointAt(structure.x, structure.z, selectedDimension);
        boolean completed = isStructureCompleted(structure);
        String[] actions = structurePopupActions(hasWaypoint);
        int menuWidth = STRUCTURE_POPUP_WIDTH;
        int menuHeight = STRUCTURE_POPUP_TOP_HEIGHT + STRUCTURE_POPUP_GAP + actions.length * STRUCTURE_POPUP_ACTION_HEIGHT;

        Minecraft mc = Minecraft.getInstance();
        double screenWidth = mc.getWindow().getWidth();
        double screenHeight = mc.getWindow().getHeight();
        double mx = Math.min(contextMenuX - menuWidth / 2.0, screenWidth - menuWidth - 4);
        double my = Math.min(contextMenuY - menuHeight - 12, screenHeight - menuHeight - 4);
        if (mx < 4) mx = 4;
        if (my < 4) my = 4;

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(mx, my, menuWidth, STRUCTURE_POPUP_TOP_HEIGHT, UI_SURFACE);
        r.quad(mx, my, 4, STRUCTURE_POPUP_TOP_HEIGHT, completed ? new Color(72, 205, 150, 235) : UI_ACCENT);
        r.boxLines(mx, my, menuWidth, STRUCTURE_POPUP_TOP_HEIGHT, UI_LINE);
        double pointerX = Math.max(mx + 22, Math.min(mx + menuWidth - 22, contextMenuX));
        r.triangle(pointerX - 8, my + STRUCTURE_POPUP_TOP_HEIGHT, pointerX + 8, my + STRUCTURE_POPUP_TOP_HEIGHT, pointerX, my + STRUCTURE_POPUP_TOP_HEIGHT + 8, UI_SURFACE);
        r.render();

        Texture icon = StructureIcons.get(structure);
        if (icon != null) {
            Renderer2D tex = Renderer2D.TEXTURE;
            tex.begin();
            tex.texQuad(mx + 8, my + 8, 20, 20, Color.WHITE);
            tex.render(icon.getTextureView(), icon.getSampler());
        }

        String title = trimToWidth(structure.displayName(), menuWidth - 42);
        String coords = "X: " + structure.x + " Z: " + structure.z;
        theme.textRenderer().begin(1.0);
        theme.textRenderer().render(title, mx + 32, my + 9, UI_TEXT);
        theme.textRenderer().render(coords, mx + 32, my + 31, UI_MUTED);
        theme.textRenderer().end();

        double completedY = my + 52;
        r.begin();
        r.quad(mx + 10, completedY, menuWidth - 20, 25, completed ? new Color(32, 92, 74, 230) : new Color(18, 24, 31, 235));
        r.boxLines(mx + 10, completedY, menuWidth - 20, 25, completed ? new Color(75, 220, 174, 230) : UI_LINE);
        r.boxLines(mx + 20, completedY + 7, 11, 11, completed ? Color.WHITE : UI_DISABLED);
        if (completed) {
            r.quad(mx + 23, completedY + 10, 5, 5, Color.WHITE);
        }
        r.render();

        String completedText = "Completed";
        theme.textRenderer().begin(1.0);
        theme.textRenderer().render(completedText, mx + 40, completedY + 8, completed ? Color.WHITE : UI_MUTED);
        theme.textRenderer().end();

        double actionY = my + STRUCTURE_POPUP_TOP_HEIGHT + STRUCTURE_POPUP_GAP;
        for (int i = 0; i < actions.length; i++) {
            double y = actionY + i * STRUCTURE_POPUP_ACTION_HEIGHT;
            Color bg = i == 1 && hasWaypoint ? new Color(72, 42, 48, 220) : UI_SURFACE;
            r.begin();
            r.quad(mx, y, menuWidth, STRUCTURE_POPUP_ACTION_HEIGHT - 1, bg);
            r.boxLines(mx, y, menuWidth, STRUCTURE_POPUP_ACTION_HEIGHT - 1, UI_LINE);
            r.render();
            theme.textRenderer().begin(1.0);
            theme.textRenderer().render(actions[i], mx + 10, y + 5, UI_TEXT);
            theme.textRenderer().end();
        }
    }

    private String[] structurePopupActions(boolean hasWaypoint) {
        return new String[]{"Copy Coordinates", hasWaypoint ? "Remove Waypoint" : "Create Waypoint", "Center Map", "TP", "Show Loot"};
    }

    private String trimToWidth(String text, double maxWidth) {
        if (theme.textWidth(text) <= maxWidth) return text;
        String suffix = "...";
        for (int i = text.length() - 1; i > 0; i--) {
            String candidate = text.substring(0, i) + suffix;
            if (theme.textWidth(candidate) <= maxWidth) return candidate;
        }
        return suffix;
    }

    private void drawStructurePanel(double screenWidth, double screenHeight) {
        List<StructureType> types = structureTypesForDimension(selectedDimension);
        int rowHeight = 23;
        int panelWidth = 260;
        int panelX = 10;
        int panelY = topControlHeight(screenWidth) + 8;
        int panelHeight = Math.min((int) screenHeight - panelY - 12, 30 + types.size() * rowHeight);

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(panelX, panelY, panelWidth, panelHeight, UI_SURFACE);
        r.quad(panelX, panelY, 4, panelHeight, UI_ACCENT);
        r.boxLines(panelX, panelY, panelWidth, panelHeight, UI_LINE);
        r.render();

        theme.textRenderer().begin(1.0);
        theme.textRenderer().render(dimensionName(selectedDimension) + " Structures", panelX + 12, panelY + 8, UI_TEXT);
        theme.textRenderer().end();

        int visibleRows = Math.max(0, (panelHeight - 30) / rowHeight);
        for (int i = 0; i < Math.min(types.size(), visibleRows); i++) {
            StructureType type = types.get(i);
            int y = panelY + 28 + i * rowHeight;
            boolean enabled = SeedManager.get().getProfileStructure(type.name(), true);
            Color rowColor = enabled ? UI_SURFACE_2 : new Color(18, 22, 28, 150);
            r.begin();
            r.quad(panelX + 6, y, panelWidth - 12, rowHeight - 2, rowColor);
            r.boxLines(panelX + 12, y + 4, 10, 10, enabled ? UI_ACCENT : UI_DISABLED);
            if (enabled) r.quad(panelX + 15, y + 7, 4, 4, UI_ACCENT);
            r.render();

            Texture icon = StructureIcons.get(type);
            if (icon != null) {
                Renderer2D tex = Renderer2D.TEXTURE;
                tex.begin();
                tex.texQuad(panelX + 30, y + 3, 16, 16, enabled ? Color.WHITE : new Color(135, 140, 146, 115));
                tex.render(icon.getTextureView(), icon.getSampler());
            }

            theme.textRenderer().begin(1.0);
            theme.textRenderer().render(type.displayName, panelX + 52, y + 5, enabled ? UI_TEXT : UI_MUTED);
            theme.textRenderer().end();
        }
    }

    private void handleContextMenuClick(double mouseX, double mouseY) {
        if (!isWaypointContextMenu && selectedStructure != null) {
            handleStructurePopupClick(mouseX, mouseY);
            return;
        }

        int itemHeight = 20;
        int menuWidth = 160;

        String[] items;
        if (isWaypointContextMenu) {
            items = new String[]{"Copy Coords", "Delete Waypoint", "Center Map"};
        } else {
            boolean hasWaypoint = selectedStructure != null && WaypointManager.get().hasWaypointAt(
                selectedStructure.x, selectedStructure.z, selectedDimension);
            items = new String[]{"Copy Coords", hasWaypoint ? "Remove Waypoint" : "Create Waypoint", "Center Map", "TP"};
        }
        int menuHeight = items.length * itemHeight;

        double mx = Math.min(contextMenuX, Minecraft.getInstance().getWindow().getWidth() - menuWidth);
        double my = Math.min(contextMenuY, Minecraft.getInstance().getWindow().getHeight() - menuHeight);
        if (mx < 0) mx = 0;
        if (my < 0) my = 0;

        int clickedIndex = -1;
        for (int i = 0; i < items.length; i++) {
            double ix = mx;
            double iy = my + i * itemHeight;
            if (mouseX >= ix && mouseX <= ix + menuWidth && mouseY >= iy && mouseY <= iy + itemHeight) {
                clickedIndex = i;
                break;
            }
        }

        if (clickedIndex >= 0) {
            if (isWaypointContextMenu) {
                handleWaypointContextAction(clickedIndex);
            } else {
                handleStructureContextAction(clickedIndex);
            }
        }

        contextMenuOpen = false;
        selectedStructure = null;
        selectedWaypoint = null;
    }

    private void handleStructurePopupClick(double mouseX, double mouseY) {
        boolean hasWaypoint = WaypointManager.get().hasWaypointAt(selectedStructure.x, selectedStructure.z, selectedDimension);
        String[] actions = structurePopupActions(hasWaypoint);
        int menuWidth = STRUCTURE_POPUP_WIDTH;
        int menuHeight = STRUCTURE_POPUP_TOP_HEIGHT + STRUCTURE_POPUP_GAP + actions.length * STRUCTURE_POPUP_ACTION_HEIGHT;

        Minecraft mc = Minecraft.getInstance();
        double mx = Math.min(contextMenuX - menuWidth / 2.0, mc.getWindow().getWidth() - menuWidth - 4);
        double my = Math.min(contextMenuY - menuHeight - 12, mc.getWindow().getHeight() - menuHeight - 4);
        if (mx < 4) mx = 4;
        if (my < 4) my = 4;

        double completedY = my + 52;
        if (mouseX >= mx + 10 && mouseX <= mx + menuWidth - 10 && mouseY >= completedY && mouseY <= completedY + 25) {
            toggleStructureCompleted(selectedStructure);
            return;
        }

        double actionY = my + STRUCTURE_POPUP_TOP_HEIGHT + STRUCTURE_POPUP_GAP;
        int clickedIndex = -1;
        for (int i = 0; i < actions.length; i++) {
            double y = actionY + i * STRUCTURE_POPUP_ACTION_HEIGHT;
            if (mouseX >= mx && mouseX <= mx + menuWidth && mouseY >= y && mouseY <= y + STRUCTURE_POPUP_ACTION_HEIGHT) {
                clickedIndex = i;
                break;
            }
        }

        if (clickedIndex >= 0) {
            handleStructureContextAction(clickedIndex);
            if (clickedIndex == 1) return;
        }

        contextMenuOpen = false;
        selectedStructure = null;
        selectedWaypoint = null;
    }

    private void handleWaypointContextAction(int index) {
        if (selectedWaypoint == null) return;

        switch (index) {
            case 0 -> copyCoords(selectedWaypoint.x, selectedWaypoint.z);
            case 1 ->
                // The visibleWaypoints list is rebuilt every frame from the render result and
                // may be an immutable List.of() when the waypoint layer is disabled, so removing
                // from it here would throw. Removing from the manager is enough.
                WaypointManager.get().removeWaypoint(selectedWaypoint);
            case 2 -> centerMap(selectedWaypoint.x, selectedWaypoint.z);
        }
    }

    private void handleStructureContextAction(int index) {
        if (selectedStructure == null) return;

        switch (index) {
            case 0 -> copyCoords(selectedStructure.x, selectedStructure.z);
            case 1 -> {
                if (WaypointManager.get().hasWaypointAt(selectedStructure.x, selectedStructure.z, selectedDimension)) {
                    WaypointManager.get().removeWaypointAt(selectedStructure.x, selectedStructure.z, selectedDimension);
                } else {
                    createWaypoint(selectedStructure);
                }
            }
            case 2 -> centerMap(selectedStructure.x, selectedStructure.z);
            case 3 -> teleportToStructure(selectedStructure);
            case 4 -> showLootForStructure(selectedStructure);
        }
    }

    private boolean isStructureCompleted(GeneratedStructure structure) {
        return SeedManager.get().getCompletedStructure(structure.type.name(), selectedDimension, structure.x, structure.z);
    }

    private void toggleStructureCompleted(GeneratedStructure structure) {
        boolean completed = SeedManager.get().toggleCompletedStructure(structure.type.name(), selectedDimension, structure.x, structure.z);
        ChatUtils.info("Seed Explorer: Marked (highlight)" + structure.displayName() + "(default) as " + (completed ? "(highlight)completed(default)." : "(highlight)not completed(default)."));
    }

    private void teleportToStructure(GeneratedStructure gs) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) return;

        mc.player.connection.sendCommand("tp " + gs.x + " 100 " + gs.z);
        ChatUtils.info("Seed Explorer: Sent teleport to (highlight)" + gs.x + ", 100, " + gs.z + "(default).");
    }

    private void openStructureViewer(GeneratedStructure gs) {
        if (!ChestLootPredictor.canPredict(gs.type)) {
            openStructureViewer(gs, List.of());
            return;
        }

        WorkerManager.get().submit(() -> {
            List<ChestLootOutput> results = ChestLootPredictor.predictForStructure(gs);
            Minecraft.getInstance().execute(() -> openStructureViewer(gs, results));
        });
    }

    private void openStructureViewer(GeneratedStructure gs, List<ChestLootOutput> results) {
        long worldSeed = SeedManager.get().getWorldSeed();
        Minecraft.getInstance().setScreen(new Structure3DViewerScreen(
            worldSeed, gs, results));
    }

    private void showLootForStructure(GeneratedStructure gs) {
        if (!ChestLootPredictor.canPredict(gs.type)) {
            openStructureViewer(gs, List.of());
            return;
        }

        ChatUtils.info("Seed Explorer: Predicting loot for (highlight)" + gs.displayName() + "(default)...");
        WorkerManager.get().submit(() -> {
            List<ChestLootOutput> results = ChestLootPredictor.predictForStructure(gs);
            Minecraft.getInstance().execute(() -> openStructureViewer(gs, results));
        });
    }

    private void load3dChestOverlay(GeneratedStructure structure) {
        if (structure == null) return;
        chestOverlayStructure = structure;
        selected3dChest = null;
        selected3dChestIndex = -1;
        chestOverlayChests = List.of();
        if (!ChestLootPredictor.canPredict(structure.type)) {
            chestOverlayLoading = false;
            return;
        }

        chestOverlayLoading = true;
        long request = ++chestOverlayRequestId;
        WorkerManager.get().submit(() -> {
            List<ChestLootOutput> results = ChestLootPredictor.predictForStructure(structure);
            Minecraft.getInstance().execute(() -> {
                if (request != chestOverlayRequestId) return;
                chestOverlayLoading = false;
                chestOverlayChests = results == null ? List.of() : results.stream()
                    .filter(chest -> chest.lootTableId() == null || !chest.lootTableId().startsWith("minecraft:dispensers/"))
                    .toList();
                if (!chestOverlayChests.isEmpty()) setSelected3dChest(0);
            });
        });
    }

    private void clear3dChestOverlay() {
        chestOverlayRequestId++;
        chestOverlayStructure = null;
        chestOverlayChests = List.of();
        selected3dChest = null;
        selected3dChestIndex = -1;
        chestOverlayLoading = false;
    }

    private void drawSelected3dChestPanel(double screenWidth, double screenHeight) {
        if (chestOverlayStructure == null) return;

        int panelW = 236;
        int panelX = (int) Math.max(8, screenWidth - panelW - 12);
        int panelY = (int) Math.max(topControlHeight(screenWidth) + 10, 92);
        int panelH = selected3dChest == null ? 96 : 252;
        if (panelY + panelH > screenHeight - 8) panelY = (int) Math.max(8, screenHeight - panelH - 8);

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(panelX + 2, panelY + 3, panelW, panelH, new Color(0, 0, 0, 105));
        r.quad(panelX, panelY, panelW, panelH, new Color(34, 34, 42, 238));
        r.quad(panelX, panelY, 4, panelH, selected3dChest != null && selected3dChest.exact()
            ? new Color(86, 215, 156, 245) : new Color(218, 172, 82, 235));
        r.boxLines(panelX, panelY, panelW, panelH, new Color(116, 122, 130, 230));
        r.render();

        String title = chestOverlayStructure.displayName() + " chests";
        theme.textRenderer().begin(1.0);
        theme.textRenderer().render(trimToWidth(title, panelW - 28), panelX + 12, panelY + 9, UI_TEXT);
        theme.textRenderer().render("x", panelX + panelW - 16, panelY + 9, UI_MUTED);
        theme.textRenderer().end();

        if (chestOverlayLoading) {
            theme.textRenderer().begin(1.0);
            theme.textRenderer().render("Predicting chest positions...", panelX + 12, panelY + 34, UI_MUTED);
            theme.textRenderer().end();
            return;
        }

        if (chestOverlayChests.isEmpty()) {
            theme.textRenderer().begin(1.0);
            theme.textRenderer().render("No predicted chests for this structure.", panelX + 12, panelY + 34, UI_MUTED);
            theme.textRenderer().end();
            return;
        }

        chestPanelPrevX = panelX + 12;
        chestPanelPrevY = panelY + 32;
        chestPanelPrevW = 20;
        chestPanelPrevH = 16;
        chestPanelNextX = chestPanelPrevX + 24;
        chestPanelNextY = chestPanelPrevY;
        chestPanelNextW = 20;
        chestPanelNextH = 16;
        chestPanelCopyX = chestPanelNextX + 28;
        chestPanelCopyY = chestPanelPrevY;
        chestPanelCopyW = 34;
        chestPanelCopyH = 16;
        chestPanelExportX = chestPanelCopyX + 38;
        chestPanelExportY = chestPanelPrevY;
        chestPanelExportW = 46;
        chestPanelExportH = 16;
        drawSmallButton(chestPanelPrevX, chestPanelPrevY, chestPanelPrevW, chestPanelPrevH, selected3dChestIndex > 0, "<");
        drawSmallButton(chestPanelNextX, chestPanelNextY, chestPanelNextW, chestPanelNextH, selected3dChestIndex + 1 < chestOverlayChests.size(), ">");
        drawSmallButton(chestPanelCopyX, chestPanelCopyY, chestPanelCopyW, chestPanelCopyH, true, "Copy");
        drawSmallButton(chestPanelExportX, chestPanelExportY, chestPanelExportW, chestPanelExportH, true, "Export");

        if (selected3dChest == null) {
            theme.textRenderer().begin(1.0);
            theme.textRenderer().render("Click a highlighted chest marker.", panelX + 12, panelY + 34, UI_MUTED);
            theme.textRenderer().end();
            return;
        }

        drawChestGrid(panelX + 12, panelY + 56, selected3dChest);
        drawChestItemList(panelX + 12, panelY + 120, panelW - 24, panelY + panelH - 10, selected3dChest);
    }

    private boolean handleChestPanelClick(double mouseX, double mouseY) {
        if (chestOverlayStructure == null) return false;
        Minecraft mc = Minecraft.getInstance();
        double screenWidth = mc.getWindow().getWidth();
        double screenHeight = mc.getWindow().getHeight();
        int panelW = 236;
        int panelX = (int) Math.max(8, screenWidth - panelW - 12);
        int panelY = (int) Math.max(topControlHeight(screenWidth) + 10, 92);
        int panelH = selected3dChest == null ? 96 : 252;
        if (panelY + panelH > screenHeight - 8) panelY = (int) Math.max(8, screenHeight - panelH - 8);

        if (mouseX >= panelX + panelW - 24 && mouseX <= panelX + panelW
            && mouseY >= panelY && mouseY <= panelY + 28) {
            clear3dChestOverlay();
            return true;
        }
        if (selected3dChest != null) {
            if (inChestButton(mouseX, mouseY, chestPanelPrevX, chestPanelPrevY, chestPanelPrevW, chestPanelPrevH)) {
                setSelected3dChest(selected3dChestIndex - 1);
                return true;
            }
            if (inChestButton(mouseX, mouseY, chestPanelNextX, chestPanelNextY, chestPanelNextW, chestPanelNextH)) {
                setSelected3dChest(selected3dChestIndex + 1);
                return true;
            }
            if (inChestButton(mouseX, mouseY, chestPanelCopyX, chestPanelCopyY, chestPanelCopyW, chestPanelCopyH)) {
                if (mc.keyboardHandler != null) mc.keyboardHandler.setClipboard(buildChestReport(selected3dChest));
                ChatUtils.info("Seed Explorer: copied predicted chest report to clipboard.");
                return true;
            }
            if (inChestButton(mouseX, mouseY, chestPanelExportX, chestPanelExportY, chestPanelExportW, chestPanelExportH)) {
                exportSelectedChestReport();
                return true;
            }
        }
        return mouseX >= panelX && mouseX <= panelX + panelW
            && mouseY >= panelY && mouseY <= panelY + panelH;
    }

    private void setSelected3dChest(int index) {
        if (index < 0 || index >= chestOverlayChests.size()) return;
        selected3dChestIndex = index;
        selected3dChest = chestOverlayChests.get(index);
    }

    private void exportSelectedChestReport() {
        if (selected3dChest == null) return;
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("seed-explorer-reports");
            Files.createDirectories(dir);
            Path path = dir.resolve("chest-" + sanitize(chestOverlayStructure.displayName()) + "-" + selected3dChestIndex
                + "-" + LocalDateTime.now().format(FILE_TIME) + ".txt");
            Files.writeString(path, buildChestReport(selected3dChest), StandardCharsets.UTF_8);
            ChatUtils.info("Seed Explorer: saved chest report to (highlight)" + path + "(default).");
        } catch (Exception e) {
            ChatUtils.warning("Seed Explorer: could not export chest report: " + e.getMessage());
        }
    }

    private String buildChestReport(ChestLootOutput chest) {
        StringBuilder sb = new StringBuilder();
        sb.append("Seed Explorer chest report\n");
        sb.append("structure=").append(chestOverlayStructure == null ? "" : chestOverlayStructure.displayName()).append('\n');
        sb.append("index=").append(selected3dChestIndex + 1).append('/').append(chestOverlayChests.size()).append('\n');
        sb.append("pos=").append(chest.blockX()).append(',').append(chest.blockY()).append(',').append(chest.blockZ()).append('\n');
        sb.append("table=").append(chest.lootTableId()).append('\n');
        sb.append("seed=").append(chest.seed()).append('\n');
        sb.append("exact=").append(chest.exact()).append('\n');
        sb.append("summary=").append(chest.summary()).append('\n');
        return sb.toString();
    }

    private static String sanitize(String text) {
        String clean = text == null ? "report" : text.replaceAll("[^A-Za-z0-9._-]+", "-");
        return clean.isBlank() ? "report" : clean;
    }

    private boolean inChestButton(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void drawSmallButton(int x, int y, int w, int h, boolean enabled, String text) {
        Renderer2D r = Renderer2D.COLOR;
        Color bg = enabled ? new Color(26, 40, 47, 245) : new Color(22, 26, 30, 210);
        Color border = enabled ? new Color(69, 182, 166, 220) : new Color(64, 74, 84, 180);
        r.begin();
        r.quad(x, y, w, h, bg);
        r.boxLines(x, y, w, h, border);
        r.render();
        theme.textRenderer().begin(1.0);
        theme.textRenderer().render(text, x + Math.max(1, (w - theme.textWidth(text)) / 2), y + 4,
            enabled ? UI_TEXT : UI_MUTED);
        theme.textRenderer().end();
    }

    private void drawChestGrid(int x, int y, ChestLootOutput chest) {
        int slot = 18;
        int cols = 9;
        int rows = 3;
        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(x - 4, y - 4, cols * slot + 8, rows * slot + 8, new Color(198, 198, 198, 235));
        r.boxLines(x - 4, y - 4, cols * slot + 8, rows * slot + 8, new Color(72, 72, 72, 240));
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int sx = x + col * slot;
                int sy = y + row * slot;
                r.quad(sx, sy, 16, 16, new Color(112, 112, 112, 245));
                r.quad(sx + 1, sy + 1, 14, 14, new Color(40, 40, 46, 245));
            }
        }
        r.render();

        if (chest.predictedItems() == null) return;
        theme.textRenderer().begin(0.85);
        int i = 0;
        for (ItemLoot item : chest.predictedItems()) {
            if (i >= cols * rows) break;
            int col = i % cols;
            int row = i / cols;
            int sx = x + col * slot;
            int sy = y + row * slot;
            String name = item.itemId() == null ? "?" : item.itemId().substring(item.itemId().lastIndexOf(':') + 1);
            String mark = name.isBlank() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            Color itemColor = item.randomEnchantment() || item.enchantmentId() != null
                || (item.enchantmentSummary() != null && !item.enchantmentSummary().isBlank())
                ? new Color(214, 168, 255) : new Color(232, 237, 243);
            theme.textRenderer().render(mark, sx + 5, sy + 3, itemColor);
            String count = item.maxCount() > 1 ? String.valueOf(item.maxCount()) : "";
            if (!count.isEmpty()) theme.textRenderer().render(count, sx + 9, sy + 9, Color.WHITE);
            i++;
        }
        theme.textRenderer().end();
    }

    private void drawChestItemList(int x, int y, int width, int maxY, ChestLootOutput chest) {
        String subtitle = chest.blockX() + ", " + chest.blockY() + ", " + chest.blockZ()
            + "  " + (chest.exact() ? "exact" : "approx");
        theme.textRenderer().begin(1.0);
        theme.textRenderer().render(trimToWidth(subtitle, width), x, y, chest.exact() ? new Color(126, 216, 138) : new Color(216, 180, 90));
        y += 14;
        if (chest.predictedItems() == null || chest.predictedItems().isEmpty()) {
            theme.textRenderer().render("No item prediction", x, y, UI_MUTED);
            theme.textRenderer().end();
            return;
        }
        for (ItemLoot item : chest.predictedItems()) {
            if (y > maxY - 12) break;
            String count = item.minCount() == item.maxCount()
                ? " x" + item.minCount()
                : " x" + item.minCount() + "-" + item.maxCount();
            boolean enchanted = item.randomEnchantment() || item.enchantmentId() != null
                || (item.enchantmentSummary() != null && !item.enchantmentSummary().isBlank());
            theme.textRenderer().render(trimToWidth(item.displayName() + count, width), x, y,
                enchanted ? new Color(214, 168, 255) : UI_TEXT);
            y += 12;
            if (item.enchantmentSummary() != null && !item.enchantmentSummary().isBlank() && y <= maxY - 12) {
                theme.textRenderer().render(trimToWidth("[" + item.enchantmentSummary() + "]", width), x + 8, y,
                    new Color(214, 168, 255));
                y += 12;
            }
        }
        theme.textRenderer().end();
    }

    private void copyCoords(int x, int z) {
        String coords = x + ", " + z;
        Minecraft.getInstance().keyboardHandler.setClipboard(coords);
    }

    private void createWaypoint(GeneratedStructure gs) {
        String name = gs.displayName() + " [" + gs.x + ", " + gs.z + "]";
        String icon = switch (gs.type) {
            case VILLAGE -> "square";
            case DESERT_PYRAMID, JUNGLE_TEMPLE, WITCH_HUT, IGLOO -> "triangle";
            case OUTPOST -> "skull";
            case MONUMENT, MANSION -> "diamond";
            case ANCIENT_CITY -> "star";
            case TRIAL_CHAMBER -> "diamond";
            case TRAIL_RUINS, RUINED_PORTAL, OCEAN_RUIN, MINESHAFT -> "circle";
            case FORTRESS, BASTION -> "skull";
            case END_CITY -> "star";
            default -> "circle";
        };
        String structureType = gs.displayName();
        WaypointManager.get().createWaypoint(name, gs.x, gs.z, selectedDimension, icon, structureType);
    }

    private void centerMap(int x, int z) {
        offsetX = x;
        offsetZ = z;
    }

    private double distanceSquared(int x1, int z1, int x2, int z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    // ---- Search functionality ----

    private void updateSearchState() {
        if (searchBar == null) return;

        String query = searchBar.get().trim();
        if (query.equals(lastSearchText)) return;
        lastSearchText = query;

        Set<String> parsedLootFilters = parseLootItemFilters(query);
        if (!parsedLootFilters.equals(lootItemFilters)) {
            lootItemFilters.clear();
            lootItemFilters.addAll(parsedLootFilters);
            lootFilterCache.clear();
            lootFilterInFlight.clear();
            lastLootFilterSignature = String.join(",", lootItemFilters);
        }

        if (!lootItemFilters.isEmpty()) {
            searchStructureFilter = null;
            searchSuggestion = "Loot: " + String.join(" + ", lootItemFilters);
            return;
        }

        if (query.isEmpty() || isCoordinateQuery(query)) {
            searchStructureFilter = null;
            searchSuggestion = "";
            return;
        }

        StructureType match = bestStructureMatch(query);
        if (match == null) {
            searchStructureFilter = null;
            searchSuggestion = "";
            return;
        }

        searchStructureFilter = match;
        searchSuggestion = "Nearest " + match.displayName;
    }

    private void onSearch() {
        if (searchBar == null) return;
        updateSearchState();
        String query = searchBar.get().trim();
        if (query.isEmpty()) return;

        if (!lootItemFilters.isEmpty()) {
            ChatUtils.info("Seed Explorer: Filtering structures by loot item(s): (highlight)" + String.join(", ", lootItemFilters) + "(default).");
            return;
        }

        // Try coordinate search first
        if (tryCoordinateSearch(query)) return;

        // Try structure search
        if (jumpToSearchResult()) return;

        // Try biome search
        if (tryBiomeSearch(query)) return;

        // No results found
        ChatUtils.info("Seed Explorer: No results found for \"(highlight)" + query + "(default)\".");
    }

    private Set<String> parseLootItemFilters(String query) {
        if (query == null) return Set.of();
        String q = query.trim();
        String lower = q.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("loot:") || lower.startsWith("item:"))) return Set.of();

        String body = q.substring(q.indexOf(':') + 1).trim();
        if (body.isEmpty()) return Set.of();

        Set<String> out = new LinkedHashSet<>();
        for (String raw : body.split("[,;+]")) {
            String normalized = normalizeItemQuery(raw);
            if (!normalized.isBlank()) out.add(normalized);
        }
        return out;
    }

    private String normalizeItemQuery(String raw) {
        if (raw == null) return "";
        String id = raw.trim().toLowerCase(Locale.ROOT)
            .replace(' ', '_')
            .replace('-', '_');
        if (id.isBlank()) return "";
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private Set<Long> lootMatchedStructureKeys() {
        if (lootItemFilters.isEmpty()) return null;
        Set<Long> matched = new HashSet<>();
        for (Map.Entry<Long, Boolean> entry : lootFilterCache.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) matched.add(entry.getKey());
        }
        return matched;
    }

    private void scheduleLootFilterChecks(List<GeneratedStructure> structures) {
        if (lootItemFilters.isEmpty() || structures == null || structures.isEmpty()) return;
        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) return;

        Set<String> filters = Set.copyOf(lootItemFilters);
        String signature = String.join(",", filters);
        if (!signature.equals(lastLootFilterSignature) || seed != lastLootFilterSeed) {
            lootFilterCache.clear();
            lootFilterInFlight.clear();
            lastLootFilterSignature = signature;
            lastLootFilterSeed = seed;
        }

        int scheduled = 0;
        for (GeneratedStructure structure : structures) {
            if (!ChestLootPredictor.canPredict(structure.type)) continue;
            long key = lootCacheKey(selectedDimension, structure);
            if (lootFilterCache.containsKey(key) || lootFilterInFlight.contains(key)) continue;
            lootFilterInFlight.add(key);
            scheduled++;

            WorkerManager.get().submit(() -> {
                boolean matched = false;
                try {
                    List<ChestLootOutput> chests = ChestLootPredictor.predictForStructure(structure);
                    matched = structureContainsAllItems(chests, filters);
                } catch (Throwable ignored) {
                }
                boolean finalMatched = matched;
                Minecraft.getInstance().execute(() -> {
                    lootFilterInFlight.remove(key);
                    lootFilterCache.put(key, finalMatched);
                });
            });

            if (scheduled >= 6) break;
        }
    }

    private long lootCacheKey(int dimension, GeneratedStructure structure) {
        return SeedRenderer.structureKey(dimension, structure);
    }

    private boolean structureContainsAllItems(List<ChestLootOutput> chests, Set<String> filters) {
        if (chests == null || chests.isEmpty()) return false;
        Set<String> found = new HashSet<>();
        for (ChestLootOutput chest : chests) {
            if (chest.predictedItems() == null) continue;
            for (var item : chest.predictedItems()) {
                String id = normalizeItemQuery(item.itemId());
                if (filters.contains(id)) found.add(id);
            }
        }
        return found.containsAll(filters);
    }

    private void onSeedChanged() {
        if (applyingSeed) return;
        if (seedBox == null) return;

        applyingSeed = true;
        try {
            String seedText = seedBox.get().trim();
            if (seedText.isEmpty()) return;

            long seed = parseSeed(seedText);
            if (SeedManager.get().getWorldSeed() == seed) return;

            SeedManager.get().set(seed, selectedVersion());
            clearPredictionCaches();

            ChatUtils.info("Seed Explorer: Set seed to (highlight)" + seedText + "(default) (numeric: (highlight)" + seed + "(default)).");
        } finally {
            applyingSeed = false;
        }
    }

    private void onVersionChanged() {
        String version = selectedVersion();
        if (version.isBlank() || version.equals(SeedManager.get().getMcVersion())) return;
        SeedManager.get().setMcVersion(version);
        clearPredictionCaches();
        ChatUtils.info("Seed Explorer: Set prediction version profile to (highlight)" + version
            + "(default). Loot profiles use this selector; terrain and structures still use the active Minecraft runtime.");
    }

    private String selectedVersion() {
        if (versionDropdown == null || versionDropdown.get() == null) {
            return currentVersionSelection();
        }
        return versionDropdown.get();
    }

    private void clearPredictionCaches() {
        StructureCache.get().clear();
        OreCache.get().clear();
        MinimapManager.get().clear();
        ChestLootPredictor.clearCache();
    }

    private long parseSeed(String seedText) {
        try {
            return Long.parseLong(seedText);
        } catch (NumberFormatException ignored) {
            return seedText.hashCode();
        }
    }

    private boolean tryCoordinateSearch(String query) {
        int[] coords = parseCoordinateSearch(query);
        if (coords == null) return false;

        centerMap(coords[0], coords[1]);
        ChatUtils.info("Seed Explorer: Centered on coordinates (highlight)" + coords[0] + ", " + coords[1] + "(default).");
        return true;
    }

    private boolean isCoordinateQuery(String query) {
        return parseCoordinateSearch(query) != null;
    }

    private int[] parseCoordinateSearch(String query) {
        String normalized = query.trim()
            .replace(",", " ")
            .replace("~", " ");
        if (normalized.isEmpty()) return null;

        List<Integer> numbers = new ArrayList<>();
        for (String part : normalized.split("\\s+")) {
            if (part.isBlank()) continue;
            try {
                numbers.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        if (numbers.size() == 2) return new int[]{numbers.get(0), numbers.get(1)};
        if (numbers.size() == 3) return new int[]{numbers.get(0), numbers.get(2)};
        return null;
    }

    private StructureType bestStructureMatch(String query) {
        String normalizedQuery = normalizeSearchText(query);
        if (normalizedQuery.isBlank()) return null;

        StructureType best = null;
        int bestScore = Integer.MIN_VALUE;
        for (StructureType type : StructureType.values()) {
            if (!type.hasMapPrediction()) continue;
            if (type.dimension != selectedDimension) continue;

            int score = structureMatchScore(normalizedQuery, type);
            if (score > bestScore) {
                bestScore = score;
                best = type;
            }
        }

        return bestScore >= 18 ? best : null;
    }

    private int structureMatchScore(String normalizedQuery, StructureType type) {
        int best = Integer.MIN_VALUE;
        for (String alias : structureAliases(type)) {
            String normalizedAlias = normalizeSearchText(alias);
            if (normalizedAlias.isBlank()) continue;
            if (normalizedAlias.equals(normalizedQuery)) return 1000;
            if (normalizedAlias.startsWith(normalizedQuery)) best = Math.max(best, 260 - normalizedAlias.length());
            if (normalizedAlias.contains(normalizedQuery)) best = Math.max(best, 210 - normalizedAlias.length());
            if (isSubsequence(normalizedQuery, normalizedAlias)) best = Math.max(best, 120 - normalizedAlias.length());

            int distance = levenshtein(normalizedQuery, normalizedAlias);
            int maxLength = Math.max(normalizedQuery.length(), normalizedAlias.length());
            int fuzzy = 100 - distance * 12 - Math.max(0, maxLength - normalizedQuery.length()) * 2;
            best = Math.max(best, fuzzy);
        }
        return best;
    }

    private List<String> structureAliases(StructureType type) {
        List<String> aliases = new ArrayList<>();
        aliases.add(type.displayName);
        aliases.add(type.name());
        aliases.add(type.name().replace('_', ' '));

        switch (type) {
            case DESERT_PYRAMID -> {
                aliases.add("desert temple");
                aliases.add("temple");
                aliases.add("pyramid");
            }
            case JUNGLE_TEMPLE -> {
                aliases.add("jungle pyramid");
                aliases.add("jungle temple");
                aliases.add("temple");
            }
            case WITCH_HUT -> aliases.add("swamp hut");
            case OUTPOST -> aliases.add("pillager tower");
            case MONUMENT -> aliases.add("ocean monument");
            case MANSION -> aliases.add("woodland mansion");
            case TREASURE -> aliases.add("buried treasure chest");
            case FORTRESS -> aliases.add("nether fortress");
            case BASTION -> {
                aliases.add("bastion");
                aliases.add("bastion remnant");
            }
            case NETHER_RUINED_PORTAL -> aliases.add("nether portal");
            default -> {
            }
        }

        return aliases;
    }

    private String normalizeSearchText(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private boolean isSubsequence(String query, String target) {
        int qi = 0;
        for (int ti = 0; ti < target.length() && qi < query.length(); ti++) {
            if (query.charAt(qi) == target.charAt(ti)) qi++;
        }
        return qi == query.length();
    }

    private int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }

        return prev[b.length()];
    }

    private boolean jumpToSearchResult() {
        StructureType matchedType = searchStructureFilter;
        if (matchedType == null && searchBar != null) matchedType = bestStructureMatch(searchBar.get());
        if (matchedType == null) return false;

        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) {
            ChatUtils.info("Seed Explorer: No seed set.");
            return false;
        }
        // Start from current view center and search expanding regions
        int centerBlockX = (int) offsetX;
        int centerBlockZ = (int) offsetZ;
        int radiusChunks = matchedType == StructureType.STRONGHOLD ? 1024 : 320;
        List<GeneratedStructure> candidates = VanillaStructurePredictor.predictDimension(
            seed,
            selectedDimension,
            Math.floorDiv(centerBlockX, 16) - radiusChunks,
            Math.floorDiv(centerBlockZ, 16) - radiusChunks,
            Math.floorDiv(centerBlockX, 16) + radiusChunks,
            Math.floorDiv(centerBlockZ, 16) + radiusChunks,
            true,
            matchedType == StructureType.MINESHAFT
        );
        StructureType searchType = matchedType;
        GeneratedStructure closest = candidates.stream()
            .filter(s -> s.type == searchType)
            .min((a, b) -> Double.compare(distanceSquared(a.x, a.z, centerBlockX, centerBlockZ), distanceSquared(b.x, b.z, centerBlockX, centerBlockZ)))
            .orElse(null);

        if (closest != null) {
            centerMap(closest.x, closest.z);
            searchStructureFilter = matchedType;
            searchSuggestion = "Nearest " + matchedType.displayName;
            ChatUtils.info("Seed Explorer: Found (highlight)" + closest.displayName() + "(default) at (highlight)" + closest.x + ", " + closest.z + "(default).");
            return true;
        }

        ChatUtils.info("Seed Explorer: No (highlight)" + matchedType.displayName + "(default) found within search range.");
        return false;
    }

    private boolean tryBiomeSearch(String query) {
        // Match query against biome name map (case-insensitive)
        String lowerQuery = query.toLowerCase();
        Integer biomeId = null;

        // Try exact match first
        if (BIOME_NAME_TO_ID.containsKey(lowerQuery)) {
            biomeId = BIOME_NAME_TO_ID.get(lowerQuery);
        } else {
            // Try partial match
            for (Map.Entry<String, Integer> entry : BIOME_NAME_TO_ID.entrySet()) {
                if (entry.getKey().contains(lowerQuery)) {
                    biomeId = entry.getValue();
                    break;
                }
            }
        }

        if (biomeId == null) return false;

        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0) {
            ChatUtils.info("Seed Explorer: No seed set.");
            return false;
        }

        int centerBlockX = (int) offsetX;
        int centerBlockZ = (int) offsetZ;

        // Search in expanding squares with step of 16 blocks (1 chunk)
        int maxRadius = 10000; // Max search radius in blocks
        int step = 16; // Check every chunk

        for (int radius = 0; radius <= maxRadius; radius += step) {
            // Search the perimeter of the current radius
            // Top and bottom edges
            for (int bx = -radius; bx <= radius; bx += step) {
                if (radius == 0) {
                    int id = BiomeGenerator.getBiome(centerBlockX, centerBlockZ, seed);
                    if (id == biomeId) {
                        centerMap(centerBlockX, centerBlockZ);
                        ChatUtils.info("Seed Explorer: Found biome at (highlight)" + centerBlockX + ", " + centerBlockZ + "(default).");
                        return true;
                    }
                    continue;
                }

                // Top edge: z = -radius
                int idTop = BiomeGenerator.getBiome(centerBlockX + bx, centerBlockZ - radius, seed);
                if (idTop == biomeId) {
                    centerMap(centerBlockX + bx, centerBlockZ - radius);
                    ChatUtils.info("Seed Explorer: Found biome at (highlight)" + (centerBlockX + bx) + ", " + (centerBlockZ - radius) + "(default).");
                    return true;
                }

                // Bottom edge: z = radius
                int idBottom = BiomeGenerator.getBiome(centerBlockX + bx, centerBlockZ + radius, seed);
                if (idBottom == biomeId) {
                    centerMap(centerBlockX + bx, centerBlockZ + radius);
                    ChatUtils.info("Seed Explorer: Found biome at (highlight)" + (centerBlockX + bx) + ", " + (centerBlockZ + radius) + "(default).");
                    return true;
                }
            }

            // Left and right edges (excluding corners already checked)
            for (int bz = -radius + step; bz <= radius - step; bz += step) {
                // Left edge: x = -radius
                int idLeft = BiomeGenerator.getBiome(centerBlockX - radius, centerBlockZ + bz, seed);
                if (idLeft == biomeId) {
                    centerMap(centerBlockX - radius, centerBlockZ + bz);
                    ChatUtils.info("Seed Explorer: Found biome at (highlight)" + (centerBlockX - radius) + ", " + (centerBlockZ + bz) + "(default).");
                    return true;
                }

                // Right edge: x = radius
                int idRight = BiomeGenerator.getBiome(centerBlockX + radius, centerBlockZ + bz, seed);
                if (idRight == biomeId) {
                    centerMap(centerBlockX + radius, centerBlockZ + bz);
                    ChatUtils.info("Seed Explorer: Found biome at (highlight)" + (centerBlockX + radius) + ", " + (centerBlockZ + bz) + "(default).");
                    return true;
                }
            }
        }

        ChatUtils.info("Seed Explorer: No (highlight)" + query + "(default) biome found within " + maxRadius + " blocks.");
        return false;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubled) {
        double mouseX = toRenderX(click.x());
        double mouseY = toRenderY(click.y());

        if (contextMenuOpen) {
            handleContextMenuClick(mouseX, mouseY);
            return true;
        }

        if (click.button() == 0 && handleSearchSuggestionClick(mouseX, mouseY)) {
            return true;
        }

        if (click.button() == 0 && handleDimensionSelectorClick(mouseX, mouseY)) {
            return true;
        }

        if (click.button() == 0 && handleLayerToggleClick(mouseX, mouseY)) {
            return true;
        }

        if (click.button() == 0 && handleStructurePanelClick(mouseX, mouseY)) {
            return true;
        }

        if (mode3d) {
            if (click.button() == 0 && handleChestPanelClick(mouseX, mouseY)) {
                return true;
            }

            if (click.button() == 0) {
                Minecraft mc3d = Minecraft.getInstance();
                double sw = mc3d.getWindow().getWidth();
                double sh = mc3d.getWindow().getHeight();
                double ctrlH = topControlHeight(sw);

                // Check for structure click in 3D mode (skip if in control bar)
                if (!(mouseY >= 0 && mouseY <= ctrlH && mouseX >= 0 && mouseX <= sw)) {
                    double centerX = sw / 2.0;
                    double centerY = sh / 2.0;
                    ChestLootOutput clickedChest = terrain3DRenderer.pickChest(
                        camera3d, mouseX, mouseY, chestOverlayChests, centerX, centerY
                    );
                    if (clickedChest != null) {
                        int chestIndex = chestOverlayChests.indexOf(clickedChest);
                        if (chestIndex >= 0) setSelected3dChest(chestIndex);
                        contextMenuOpen = false;
                        return true;
                    }

                    GeneratedStructure clicked3d = terrain3DRenderer.pickStructure(
                        camera3d, mouseX, mouseY,
                        visibleStructures, make3dContext(mouseX, mouseY, 0), centerX, centerY
                    );
                    if (clicked3d != null) {
                        focusController.startFocus(clicked3d);
                        load3dChestOverlay(clicked3d);
                        return true;
                    }

                    // Start orbit drag
                    dragging3d = true;
                    manual3dNavigation = true;
                    focusController.disableAutoRotate();
                    drag3dStartX = mouseX;
                    drag3dStartY = mouseY;
                    return true;
                }
            }

            if (click.button() == 1) {
                GeneratedStructure clicked3d = pickStructure3d(mouseX, mouseY);
                if (clicked3d != null) {
                    selectedStructure = clicked3d;
                    load3dChestOverlay(clicked3d);
                    selectedWaypoint = null;
                    isWaypointContextMenu = false;
                    contextMenuOpen = true;
                    contextMenuX = mouseX;
                    contextMenuY = mouseY;
                    return true;
                }
                if (hoveredWaypoint != null) {
                    selectedWaypoint = hoveredWaypoint;
                    selectedStructure = null;
                    isWaypointContextMenu = true;
                    contextMenuOpen = true;
                    contextMenuX = mouseX;
                    contextMenuY = mouseY;
                    return true;
                }
            }
            return super.mouseClicked(click, doubled);
        }

        GeneratedStructure clickedStructure = pickStructureAt(mouseX, mouseY);
        if (click.button() == 0 && clickedStructure != null) {
            openStructureViewer(clickedStructure);
            return true;
        }

        // Let widgets (e.g. seed and search boxes) handle clicks after map controls/icons.
        if (super.mouseClicked(click, doubled)) {
            return true;
        }

        if (click.button() == 0) { // LEFT button - pan
            dragging = true;
            dragStartX = mouseX;
            dragStartY = mouseY;
            dragOffsetX = offsetX;
            dragOffsetZ = offsetZ;
            return true;
        }

        if (click.button() == 1) { // RIGHT button - context menu
            if (clickedStructure != null) {
                selectedStructure = clickedStructure;
                selectedWaypoint = null;
                isWaypointContextMenu = false;
                contextMenuOpen = true;
                contextMenuX = mouseX;
                contextMenuY = mouseY;
                return true;
            }
            if (hoveredWaypoint != null) {
                selectedWaypoint = hoveredWaypoint;
                selectedStructure = null;
                isWaypointContextMenu = true;
                contextMenuOpen = true;
                contextMenuX = mouseX;
                contextMenuY = mouseY;
                return true;
            }
        }

        return false;
    }

    private GeneratedStructure pickStructureAt(double mouseX, double mouseY) {
        if (visibleStructures.isEmpty()) return hoveredStructure;

        Minecraft mc = Minecraft.getInstance();
        MapViewport viewport = MapViewport.create(offsetX, offsetZ, zoom, mc.getWindow().getWidth(), mc.getWindow().getHeight());
        SeedExplorerModule module = Modules.get().get(SeedExplorerModule.class);
        MapRenderContext context = new MapRenderContext(
            viewport,
            (int) Math.round(mouseX),
            (int) Math.round(mouseY),
            0,
            selectedDimension,
            getEnabledLayers(module),
            shouldShowPlayerInfo(module),
            generationMargin(module),
            biomeTileMargin(module),
            biomeOpacity(module),
            oreOpacity(module),
            oreMarkerScale(module),
            structureMarkerScale(module),
            waypointMarkerScale(module),
            dimWaypointStructures(module),
            loadedTerrainOnMap(module),
            searchStructureFilter,
            lootMatchedStructureKeys()
        );

        return seedRenderer.pickStructure(context, visibleStructures);
    }

    private MapRenderContext make3dContext(double mouseX, double mouseY, float delta) {
        double sw = Minecraft.getInstance().getWindow().getWidth();
        double sh = Minecraft.getInstance().getWindow().getHeight();
        MapViewport vp = MapViewport.create(camera3d.targetX(), camera3d.targetZ(), 1.0, sw, sh);
        SeedExplorerModule module = Modules.get().get(SeedExplorerModule.class);
        EnumSet<MapLayer> layers = EnumSet.noneOf(MapLayer.class);
        if (module != null) {
            if (module.isLayerEnabled(MapLayer.STRUCTURES)) layers.add(MapLayer.STRUCTURES);
            if (module.isLayerEnabled(MapLayer.WAYPOINTS)) layers.add(MapLayer.WAYPOINTS);
        }
        return new MapRenderContext(
            vp, (int) Math.round(mouseX), (int) Math.round(mouseY), delta, selectedDimension,
            layers, false, 0, 0, 255, 200, 1.0, 1.0, 1.0, false, false, searchStructureFilter,
            lootMatchedStructureKeys()
        );
    }

    private GeneratedStructure pickStructure3d(double mouseX, double mouseY) {
        if (visibleStructures.isEmpty()) return hoveredStructure;
        Minecraft mc = Minecraft.getInstance();
        double centerX = mc.getWindow().getWidth() / 2.0;
        double centerY = mc.getWindow().getHeight() / 2.0;
        MapRenderContext ctx = make3dContext(mouseX, mouseY, 0);
        return terrain3DRenderer.pickStructure(camera3d, mouseX, mouseY, visibleStructures, ctx, centerX, centerY);
    }

    private boolean handleDimensionSelectorClick(double mouseX, double mouseY) {
        return false;
    }

    private boolean handleLayerToggleClick(double mouseX, double mouseY) {
        SeedExplorerModule module = Modules.get().get(SeedExplorerModule.class);
        if (module == null) return false;

        double screenWidth = Minecraft.getInstance().getWindow().getWidth();
        int maxPerRow = maxLayerButtonsPerRow(screenWidth);
        int buttonWidth = layerButtonWidth(screenWidth);

        int i = 0;
        for (MapLayer layer : MapLayer.values()) {
            int row = i / maxPerRow;
            int col = i % maxPerRow;
            int x = LAYER_BUTTON_X + col * (buttonWidth + LAYER_BUTTON_GAP);
            int y = LAYER_BUTTON_Y + row * (LAYER_BUTTON_HEIGHT + LAYER_BUTTON_GAP);
            if (mouseX >= x && mouseX <= x + buttonWidth && mouseY >= y && mouseY <= y + LAYER_BUTTON_HEIGHT) {
                if (layer == MapLayer.STRUCTURES) {
                    structurePanelOpen = !structurePanelOpen;
                } else {
                    module.setLayerEnabled(layer, !module.isLayerEnabled(layer));
                }
                return true;
            }
            i++;
        }

        // 3D Map toggle button
        int layerCount = MapLayer.values().length;
        int modeRow = layerCount / maxPerRow;
        int modeCol = layerCount % maxPerRow;
        int modeX = LAYER_BUTTON_X + modeCol * (buttonWidth + LAYER_BUTTON_GAP);
        int modeY = LAYER_BUTTON_Y + modeRow * (LAYER_BUTTON_HEIGHT + LAYER_BUTTON_GAP);
        if (mouseX >= modeX && mouseX <= modeX + buttonWidth && mouseY >= modeY && mouseY <= modeY + LAYER_BUTTON_HEIGHT) {
            toggle3dMode();
            return true;
        }

        // Underground spectator toggle button (only active in 3D mode)
        if (mode3d) {
            int underY = modeY + LAYER_BUTTON_HEIGHT + LAYER_BUTTON_GAP;
            if (mouseX >= modeX && mouseX <= modeX + buttonWidth
                && mouseY >= underY && mouseY <= underY + LAYER_BUTTON_HEIGHT) {
                toggleUndergroundView();
                return true;
            }
            int textureY = underY + LAYER_BUTTON_HEIGHT + LAYER_BUTTON_GAP;
            if (mouseX >= modeX && mouseX <= modeX + buttonWidth
                && mouseY >= textureY && mouseY <= textureY + LAYER_BUTTON_HEIGHT) {
                toggleTextureDebugView();
                return true;
            }
        }

        return false;
    }

    private boolean handleStructurePanelClick(double mouseX, double mouseY) {
        if (!structurePanelOpen) return false;
        Minecraft mc = Minecraft.getInstance();
        double screenWidth = mc.getWindow().getWidth();
        double screenHeight = mc.getWindow().getHeight();
        List<StructureType> types = structureTypesForDimension(selectedDimension);
        int rowHeight = 23;
        int panelWidth = 260;
        int panelX = 10;
        int panelY = topControlHeight(screenWidth) + 8 + (lootItemFilters.isEmpty() ? 0 : 31);
        int panelHeight = Math.min((int) screenHeight - panelY - 12, 30 + types.size() * rowHeight);
        if (mouseX < panelX || mouseX > panelX + panelWidth || mouseY < panelY || mouseY > panelY + panelHeight) return false;

        int index = (int) ((mouseY - panelY - 28) / rowHeight);
        if (index >= 0 && index < types.size()) {
            StructureType type = types.get(index);
            boolean enabled = SeedManager.get().getProfileStructure(type.name(), true);
            SeedManager.get().setProfileStructure(type.name(), !enabled);
            return true;
        }

        return true;
    }

    private List<StructureType> structureTypesForDimension(int dimension) {
        List<StructureType> result = new ArrayList<>();
        for (StructureType type : StructureType.values()) {
            if (type.dimension == dimension && type.hasMapPrediction()) result.add(type);
        }
        return result;
    }

    private String dimensionName(int dimension) {
        return switch (dimension) {
            case -1 -> "Nether";
            case 1 -> "End";
            default -> "Overworld";
        };
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent click) {
        if (mode3d) {
            if (click.button() == 0) {
                dragging3d = false;
                return true;
            }
            return super.mouseReleased(click);
        }
        if (click.button() == 0) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);

        if (mode3d) {
            if (dragging3d) {
                double renderX = toRenderX(mouseX);
                double renderY = toRenderY(mouseY);
                double dx = renderX - drag3dStartX;
                double dy = renderY - drag3dStartY;
                camera3d.mouseDrag(dx, dy);
                drag3dStartX = renderX;
                drag3dStartY = renderY;
            }
            return;
        }

        if (dragging) {
            double renderX = toRenderX(mouseX);
            double renderY = toRenderY(mouseY);
            double dx = renderX - dragStartX;
            double dy = renderY - dragStartY;
            offsetX = dragOffsetX - dx / zoom;
            offsetZ = dragOffsetZ - dy / zoom;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mode3d) {
            camera3d.scroll(verticalAmount);
            manual3dNavigation = true;
            focusController.disableAutoRotate();
            return true;
        }

        double renderX = toRenderX(mouseX);
        double renderY = toRenderY(mouseY);
        SeedExplorerModule module = Modules.get().get(SeedExplorerModule.class);
        double zoomStep = module == null ? 1.2 : module.zoomStep.get();
        double oldZoom = targetZoom;
        if (verticalAmount > 0) targetZoom *= zoomStep;
        else targetZoom /= zoomStep;
        double minZoom = module == null ? 0.01 : module.minZoom.get();
        double maxZoom = module == null ? 100.0 : module.maxZoom.get();
        targetZoom = Mth.clamp(targetZoom, Math.min(minZoom, maxZoom), Math.max(minZoom, maxZoom));

        Minecraft mc = Minecraft.getInstance();
        double screenWidth = mc.getWindow().getWidth();
        double screenHeight = mc.getWindow().getHeight();

        // Adjust offset so we zoom into the mouse position
        offsetX += (renderX - screenWidth / 2.0) / oldZoom - (renderX - screenWidth / 2.0) / targetZoom;
        offsetZ += (renderY - screenHeight / 2.0) / oldZoom - (renderY - screenHeight / 2.0) / targetZoom;

        return true;
    }

    private void updateSmoothZoom(float delta) {
        if (Math.abs(targetZoom - zoom) < 0.0001) {
            zoom = targetZoom;
            return;
        }

        double t = Math.min(1.0, Math.max(0.1, delta * 0.35));
        zoom += (targetZoom - zoom) * t;
    }

    private double toRenderX(double guiX) {
        return guiX * Minecraft.getInstance().getWindow().getGuiScale();
    }

    private double toRenderY(double guiY) {
        return guiY * Minecraft.getInstance().getWindow().getGuiScale();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent key) {
        if (contextMenuOpen) {
            contextMenuOpen = false;
            selectedStructure = null;
            selectedWaypoint = null;
            return true;
        }

        if (seedBox != null && seedBox.isFocused() && isEnterKey(key)) {
            suppressNextEnterAction = true;
        }

        if (searchBar != null && searchBar.isFocused() && isEnterKey(key)) {
            suppressNextEnterAction = true;
            onSearch();
            searchBar.setFocused(false);
            return true;
        }

        if (mode3d && !isTextInputFocused() && handle3dNavigationKey(key)) {
            return true;
        }

        // Let widgets handle keys first
        if (super.keyPressed(key)) {
            return true;
        }

        return false;
    }

    @Override
    public boolean keyReleased(net.minecraft.client.input.KeyEvent key) {
        if (suppressNextEnterAction && isEnterKey(key)) {
            suppressNextEnterAction = false;
            return true;
        }

        return super.keyReleased(key);
    }

    private boolean isEnterKey(net.minecraft.client.input.KeyEvent key) {
        return key.key() == KEY_ENTER || key.key() == KEY_KP_ENTER;
    }

    private boolean isTextInputFocused() {
        return (seedBox != null && seedBox.isFocused())
            || (searchBar != null && searchBar.isFocused());
    }

    private boolean handle3dNavigationKey(net.minecraft.client.input.KeyEvent key) {
        int code = key.key();
        double step = Math.max(8.0, camera3d.distance() * 0.12);
        double yaw = camera3d.yaw();
        double forwardX = Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = Math.cos(yaw);
        double rightZ = -Math.sin(yaw);
        double dx = 0;
        double dz = 0;
        double dy = 0;

        if (code == KEY_W) {
            dx += forwardX * step;
            dz += forwardZ * step;
        } else if (code == KEY_S) {
            dx -= forwardX * step;
            dz -= forwardZ * step;
        } else if (code == KEY_D) {
            dx += rightX * step;
            dz += rightZ * step;
        } else if (code == KEY_A) {
            dx -= rightX * step;
            dz -= rightZ * step;
        } else if (code == KEY_SPACE) {
            dy += step * 0.5;
        } else if (code == KEY_C) {
            dy -= step * 0.5;
        } else {
            return false;
        }

        manual3dNavigation = true;
        focusController.disableAutoRotate();
        camera3d.target(camera3d.targetX() + dx, camera3d.targetY() + dy, camera3d.targetZ() + dz);
        return true;
    }
}
