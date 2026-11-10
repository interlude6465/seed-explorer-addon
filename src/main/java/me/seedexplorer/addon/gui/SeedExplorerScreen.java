/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.gui;

import com.mojang.blaze3d.platform.InputConstants;
import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ChestLootPredictor;
import me.seedexplorer.addon.loot.ItemLootRequest;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.BundledLootTableLoader;
import me.seedexplorer.addon.loot.LootSearchParser;
import me.seedexplorer.addon.loot.LootSearchTerm;
import me.seedexplorer.addon.map.BiomeGenerator;
import me.seedexplorer.addon.map.MinimapManager;
import me.seedexplorer.addon.modules.SeedExplorerModule;
import me.seedexplorer.addon.ore.OreCache;
import me.seedexplorer.addon.render.FreeCamera3D;
import me.seedexplorer.addon.render.MapLayer;
import me.seedexplorer.addon.render.MapRenderContext;
import me.seedexplorer.addon.render.MeteorTerrainWorldDataSource;
import me.seedexplorer.addon.render.MapRenderResult;
import me.seedexplorer.addon.render.MapViewport;
import me.seedexplorer.addon.render.MapLoadingMode;
import me.seedexplorer.addon.render.SeedRenderer;
import me.seedexplorer.addon.render.StructureColors;
import me.seedexplorer.addon.render.StructureFocusController;
import me.seedexplorer.addon.render.StructureIcons;
import me.seedexplorer.addon.render.Terrain3DRenderer;
import me.seedexplorer.addon.render.UiIcons;
import me.seedexplorer.addon.render.BlockTextureAtlas;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.DungeonPredictor;
import me.seedexplorer.addon.structures.StructureCache;
import me.seedexplorer.addon.worldgen.PredictionVersionPolicy;
import me.seedexplorer.addon.structures.StructurePredictionStore;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.waypoints.SeedWaypoint;
import me.seedexplorer.addon.waypoints.WaypointManager;
import me.seedexplorer.addon.workers.WorkerManager;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.Texture;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final int KEY_LEFT_SHIFT = 340;
    private static final int KEY_LEFT_CONTROL = 341;
    private static final int KEY_RIGHT_SHIFT = 344;
    private static final int KEY_RIGHT_CONTROL = 345;
    private static final long MOVEMENT_DOUBLE_TAP_NANOS = 360_000_000L;
    private static final long MOVEMENT_TAP_NANOS = 220_000_000L;

    private static final int DIMENSION_BUTTON_WIDTH = 62;
    private static final int DIMENSION_BUTTON_HEIGHT = 22;
    private static final int DIMENSION_BUTTON_X = 112;
    private static final int DIMENSION_BUTTON_Y = 6;
    private static final int MODE_BUTTON_X = 270;
    private static final int MODE_BUTTON_Y = 6;
    private static final int MODE_BUTTON_WIDTH = 78;
    private static final int MODE_BUTTON_HEIGHT = 22;
    private static final int LAYER_BUTTON_HEIGHT = 22;
    private static final int LAYER_BUTTON_Y = 38;
    private static final int TOOLBAR_HEIGHT = 68;
    private static final int RIGHT_CONTROLS_PADDING = 10;
    private static final int STRUCTURE_POPUP_WIDTH = 220;
    private static final int STRUCTURE_POPUP_TOP_HEIGHT = 94;
    private static final int STRUCTURE_POPUP_GAP = 6;
    private static final int STRUCTURE_POPUP_ACTION_HEIGHT = 22;
    private static final int SEARCH_SUGGESTION_HEIGHT = 23;
    private static final int CHEST_PANEL_WIDTH = 260;
    private static final int CHEST_PANEL_HEIGHT = 270;
    private static final int ITEM_DUNGEON_SEARCH_RADIUS_CHUNKS = 4;
    private static final int DIRECT_DUNGEON_SEARCH_RADIUS_CHUNKS = 12;
    private static final Color UI_BG = new Color(9, 13, 18, 218);
    private static final Color UI_SURFACE = new Color(16, 24, 31, 232);
    private static final Color UI_SURFACE_2 = new Color(21, 32, 40, 225);
    private static final Color UI_LINE = new Color(54, 74, 88, 185);
    private static final Color UI_ACCENT = new Color(69, 182, 166, 230);
    private static final Color UI_ACCENT_SOFT = new Color(35, 93, 91, 225);
    private static final Color UI_TEXT = new Color(231, 237, 243);
    private static final Color UI_MUTED = new Color(141, 154, 166);
    private static final Color UI_DISABLED = new Color(88, 96, 104);
    private static final MapLayer[] PRIMARY_LAYERS = {
        MapLayer.TERRAIN, MapLayer.BIOMES, MapLayer.STRUCTURES, MapLayer.ORES, MapLayer.WAYPOINTS
    };
    private static final MapLayer[] SECONDARY_LAYERS = {
        MapLayer.PLAYER, MapLayer.CHUNK_BORDERS, MapLayer.COORDINATES
    };
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final SeedRenderer seedRenderer = new SeedRenderer();
    private GuiGraphicsExtractor frameGraphics;

    private boolean mode3d;
    private boolean undergroundView;
    private boolean automaticUndergroundView;
    private boolean textureDebugView;
    private boolean terrainDebugView;
    private boolean mapLoading;
    private boolean mapLoadingStopRequested;
    private long mapLoadingSeed;
    private int mapLoadingDimension;
    private double mapLoadingCenterX;
    private double mapLoadingCenterZ;
    private Terrain3DRenderer.BulkLoadStatus mapLoadingStatus;
    private int mapLoadingStopX, mapLoadingStopY, mapLoadingStopW, mapLoadingStopH;
    private final FreeCamera3D camera3d = new FreeCamera3D();
    private final StructureFocusController focusController = new StructureFocusController();
    private final Terrain3DRenderer terrain3DRenderer = Terrain3DRenderer.shared();
    private boolean dragging3d;
    private double drag3dStartX, drag3dStartY;
    private long last3dMovementNanos;
    private final Map<Integer, MovementTapState> movementTapStates = new HashMap<>();

    private static final class MovementTapState {
        private boolean held;
        private long pressNanos;
        private long lastTapNanos;
        private int tier = 1;
    }

    private double offsetX, offsetZ;
    private double zoom = 1.0;
    private double targetZoom = 1.0;

    private boolean dragging;
    private double dragStartX, dragStartY;
    private double dragOffsetX, dragOffsetZ;

    // Dimension selector
    private int selectedDimension = 0; // 0=Overworld, -1=Nether, 1=End
    private boolean dimensionPinned;

    // Structure data
    private List<GeneratedStructure> visibleStructures = new ArrayList<>();
    private GeneratedStructure hoveredStructure;
    private GeneratedStructure selectedStructure;
    private GeneratedStructure chestOverlayStructure;
    private List<ChestLootOutput> chestOverlayChests = List.of();
    private List<ChestLootOutput> searchChestMarkers = List.of();
    private ChestLootOutput matchedSearchChest;
    private Map<ChestLootOutput, Set<LootSearchTerm>> searchChestMatches = Map.of();
    private int searchChestDimension = Integer.MIN_VALUE;
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
    private boolean isCameraContextMenu;
    private boolean structurePanelOpen;
    private int structurePanelScroll;
    private boolean displayPanelOpen;

    // Top controls
    private WTextBox seedBox;
    private WDropdown<String> versionDropdown;
    private WTextBox searchBar;
    private WButton modeButton;
    private WHorizontalList topControls;
    private boolean suppressNextEnterAction;
    private boolean applyingSeed;
    private boolean renderer3dFailureNotified;
    private long lastRenderedSeed = Long.MIN_VALUE;
    private String lastSearchText = "";
    private StructureType searchStructureFilter;
    private String searchSuggestion = "";
    private final Set<LootSearchTerm> lootItemFilters = new LinkedHashSet<>();
    private final Map<LootSearchTerm, Integer> lootItemQuantities = new java.util.LinkedHashMap<>();
    private final Map<Long, Boolean> lootFilterCache = new HashMap<>();
    private final Set<Long> lootFilterInFlight = new HashSet<>();
    private String lastLootFilterSignature = "";
    private long lastLootFilterSeed = Long.MIN_VALUE;
    private long lootFilterGeneration;
    private volatile long lootSearchRequestId;
    private volatile boolean lootSearchRunning;
    private volatile int lootSearchChecked;
    private volatile int lootSearchTotal;
    private double searchCancelX, searchCancelY, searchCancelW, searchCancelH;
    private static final ExecutorService LOOT_SEARCH_WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Seed Explorer loot search");
        thread.setDaemon(true);
        return thread;
    });

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
        BIOME_NAME_TO_ID.put("dappled forest", 55);
        BIOME_NAME_TO_ID.put("sulfur caves", 56);
        BIOME_NAME_TO_ID.put("sulfur cave", 56);
        BIOME_NAME_TO_ID.put("sulphur caves", 56);
        BIOME_NAME_TO_ID.put("sulfer caves", 56);
    }

    public SeedExplorerScreen(GuiTheme theme) {
        super(theme, "Seed Explorer");
        terrain3DRenderer.setWorldDataSource(MeteorTerrainWorldDataSource.get());
        centerOnCurrentPlayer();
        focusController.bindCamera(camera3d);
        focusController.bindFocusResolver(terrain3DRenderer::structureFocusTarget);
    }

    @Override
    public void initWidgets() {
        topControls = theme.horizontalList();
        topControls.spacing = 5;
        boolean compactTop = Minecraft.getInstance().getWindow().getWidth() < 520;

        seedBox = theme.textBox(seedBoxInitialValue(), "Seed (number or text)...");
        seedBox.actionOnUnfocused = this::onSeedChanged;
        topControls.add(seedBox).minWidth(compactTop ? 75 : 90);

        versionDropdown = theme.dropdown(supportedVersionLabels(), currentVersionSelection());
        versionDropdown.action = this::onVersionChanged;
        versionDropdown.tooltip = "Gates versioned structures, biome search, and loot. Terrain rendering uses the running Minecraft worldgen registry.";
        topControls.add(versionDropdown).minWidth(compactTop ? 45 : 56);

        modeButton = theme.button(mode3d ? "Back to 2D" : "Open 3D");
        modeButton.action = this::toggle3dMode;
        topControls.add(modeButton).minWidth(compactTop ? 52 : 68);

        searchBar = theme.textBox("", "Coordinates, structure, or biome...");
        searchBar.action = this::updateSearchState;
        searchBar.actionOnUnfocused = this::updateSearchState;
        enterAction = this::onSearch;
        topControls.add(searchBar).minWidth(compactTop ? 120 : 170);

        add(topControls).top().right().padTop(8).padRight(RIGHT_CONTROLS_PADDING);
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
        return BundledLootTableLoader.runtimeProfile().minecraftVersion();
    }

    @Override
    protected void onRenderBefore(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        frameGraphics = graphics;
        Minecraft mc = Minecraft.getInstance();
        double screenWidth = mc.getWindow().getWidth();
        double screenHeight = mc.getWindow().getHeight();
        if (mapLoading) {
            if (mapLoadingStopRequested) {
                finishMapLoadingStop();
                return;
            }
            renderMapLoadingScreen(screenWidth, screenHeight);
            return;
        }
        if (topControls != null) topControls.visible = true;
        SeedExplorerModule module = Modules.get().get(SeedExplorerModule.class);

        long currentSeed = SeedManager.get().getWorldSeed();
        if (lastRenderedSeed != Long.MIN_VALUE && currentSeed != lastRenderedSeed) {
            clearPredictionCaches();
        }
        lastRenderedSeed = currentSeed;

        syncDimensionToPlayer(mc);
        updateSearchState();

        Renderer2D clear = Renderer2D.COLOR;
        clear.begin();
        if (mode3d) draw3dSkyBackground(clear, screenWidth, screenHeight, mc);
        else clear.quad(0, 0, screenWidth, screenHeight, new Color(0, 0, 0, 255));
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
        drawDimensionSelector(screenWidth);
        drawModeSelector(screenWidth);
        drawSearchSuggestion();
        drawLootFilterStatus(screenWidth);
        if (mode3d) drawAtlasStatus(screenWidth);
        drawLayerToggles(screenWidth, toRenderX(mouseX), toRenderY(mouseY));
        if (mode3d && terrainDebugView) {
            drawTerrainDebugInfo(toRenderX(mouseX), toRenderY(mouseY), screenWidth, screenHeight);
        }
        if (structurePanelOpen) drawStructurePanel(screenWidth, screenHeight);
        if (displayPanelOpen) drawDisplayPanel(screenWidth, screenHeight);
        drawSelected3dChestPanel(graphics, screenWidth, screenHeight);

        if (hoveredWaypoint != null && !contextMenuOpen && shouldShowWaypointTooltips(module)) {
            String tooltip = "Waypoint: " + hoveredWaypoint.name + " [" + hoveredWaypoint.x + ", " + hoveredWaypoint.z + "]";
            theme.textRenderer().begin(frameGraphics, 1.0);
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
        MapRenderResult result = seedRenderer.render(context, theme, graphics);
        drawSearchChestMarkers(viewport, toRenderX(mouseX), toRenderY(mouseY));

        visibleStructures = result.structures();
        visibleWaypoints = result.waypoints();
        hoveredStructure = result.hoveredStructure();
        hoveredWaypoint = result.hoveredWaypoint();
    }

    private void render3dView(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta,
                               double screenWidth, double screenHeight,
                               SeedExplorerModule module, Minecraft mc) {
        focusController.tick();
        update3dFlight();
        automaticUndergroundView = terrain3DRenderer.isCameraBelowSurface(camera3d);
        boolean caveView = undergroundView || automaticUndergroundView;

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

        try {
            terrain3DRenderer.render(graphics, camera3d, context3d, visibleStructures, visibleWaypoints,
                hoveredStructure, hoveredWaypoint, caveView, textureDebugView, terrainDebugView,
                focusController.target(), SeedManager.get().getWorldSeed());
        } catch (RuntimeException | LinkageError error) {
            // Keep the user-facing fallback, but retain the real cause in the
            // log so renderer backend failures are actionable instead of only
            // reporting the exception class in chat.
            System.err.println("[Seed Explorer] 3D rendering failed");
            error.printStackTrace(System.err);
            mode3d = false;
            undergroundView = false;
            automaticUndergroundView = false;
            textureDebugView = false;
            terrainDebugView = false;
            terrain3DRenderer.reset();
            if (!renderer3dFailureNotified) {
                renderer3dFailureNotified = true;
                ChatUtils.error("Seed Explorer: 3D rendering failed (" + error.getClass().getSimpleName()
                    + "). Returned to the 2D map.");
            }
            return;
        }

        terrain3DRenderer.renderChestMarkers(camera3d, chestOverlayChests, selected3dChest,
            searchChestMatches.keySet(),
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
        if (selectedDimension == playerDimension || dimensionPinned) return;

        selectedDimension = playerDimension;
        if (mc.player != null) {
            offsetX = mc.player.getX();
            offsetZ = mc.player.getZ();
            camera3d.viewOf(offsetX, mc.player.getY(), offsetZ, 80, 60, 0);
        }
        selectedStructure = null;
        selectedWaypoint = null;
        contextMenuOpen = false;
        isCameraContextMenu = false;
        structurePanelOpen = false;
        displayPanelOpen = false;
        lastSearchText = "";
        searchStructureFilter = null;
        searchSuggestion = "";
        clearPredictionCaches();
    }

    private void centerOnCurrentPlayer() {
        Minecraft mc = Minecraft.getInstance();
        selectedDimension = currentDimensionId(mc);
        dimensionPinned = false;
        if (mc.player == null) return;

        offsetX = mc.player.getX();
        offsetZ = mc.player.getZ();
    }

    private void toggle3dMode() {
        mode3d = !mode3d;
        if (modeButton != null) modeButton.set(mode3d ? "Back to 2D" : "Open 3D");
        if (mode3d) {
            renderer3dFailureNotified = false;
            Minecraft mc = Minecraft.getInstance();
            double px = (mc.player != null) ? mc.player.getX() : offsetX;
            double py = (mc.player != null) ? mc.player.getY() : 64;
            double pz = (mc.player != null) ? mc.player.getZ() : offsetZ;
            camera3d.viewOf(px, py, pz, 80, 60, 0);
            automaticUndergroundView = false;
            last3dMovementNanos = System.nanoTime();
            focusController.cancelFocus();
        } else {
            undergroundView = false;
            automaticUndergroundView = false;
            textureDebugView = false;
            terrainDebugView = false;
            camera3d.stopFlight();
            movementTapStates.clear();
            last3dMovementNanos = 0L;
            // Keep the shared terrain stream alive while the 3D view is closed.
            // The module continues preparing it in the background, and reset()
            // is reserved for real world/seed changes or a renderer failure.
        }
    }

    private void toggleUndergroundView() {
        undergroundView = !undergroundView;
        if (focusController.isActive()) focusController.releaseForManualFlight();
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

    private void drawDimensionSelector(double screenWidth) {
        String label = dimensionName(selectedDimension);
        int width = dimensionButtonTotalWidth(screenWidth);
        int dimensionX = dimensionButtonX(screenWidth);
        Color accent = dimensionAccent(selectedDimension);

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(dimensionX + 1, DIMENSION_BUTTON_Y + 2, width, DIMENSION_BUTTON_HEIGHT, new Color(0, 0, 0, 90));
        r.quad(dimensionX, DIMENSION_BUTTON_Y, width, DIMENSION_BUTTON_HEIGHT, UI_SURFACE);
        r.quad(dimensionX, DIMENSION_BUTTON_Y, 3, DIMENSION_BUTTON_HEIGHT, accent);
        r.quad(dimensionX + 23, DIMENSION_BUTTON_Y + 3, 1, DIMENSION_BUTTON_HEIGHT - 6, UI_LINE);
        r.quad(dimensionX + width - 25, DIMENSION_BUTTON_Y + 3, 1, DIMENSION_BUTTON_HEIGHT - 6, UI_LINE);
        r.boxLines(dimensionX, DIMENSION_BUTTON_Y, width, DIMENSION_BUTTON_HEIGHT, UI_LINE);
        r.render();
        theme.textRenderer().begin(frameGraphics, 1.0);
        if (screenWidth >= 760) theme.textRenderer().render("SEED EXPLORER", 10, DIMENSION_BUTTON_Y + 6, UI_TEXT);
        theme.textRenderer().render("<", dimensionX + 9, DIMENSION_BUTTON_Y + 5, UI_MUTED);
        double labelAreaX = dimensionX + 24;
        double labelAreaWidth = width - 48;
        String fittedLabel = trimToWidth(label, labelAreaWidth - 6);
        theme.textRenderer().render(fittedLabel, labelAreaX + (labelAreaWidth - theme.textWidth(fittedLabel)) / 2.0,
            DIMENSION_BUTTON_Y + 5, UI_TEXT);
        theme.textRenderer().render(">", dimensionX + width - 17, DIMENSION_BUTTON_Y + 5, UI_MUTED);
        theme.textRenderer().end();
    }

    private int dimensionButtonX(double screenWidth) {
        return screenWidth < 520 ? 6 : screenWidth < 760 ? 8 : DIMENSION_BUTTON_X;
    }

    private int dimensionButtonTotalWidth(double screenWidth) {
        return screenWidth < 520 ? 100 : DIMENSION_BUTTON_WIDTH * 2 + 28;
    }

    private Color dimensionAccent(int dimension) {
        return switch (dimension) {
            case -1 -> new Color(206, 92, 88, 235);
            case 1 -> new Color(168, 126, 220, 235);
            default -> UI_ACCENT;
        };
    }

    private void drawModeSelector(double screenWidth) {
        int modeX = modeButtonX(screenWidth);
        int modeWidth = modeButtonWidth(screenWidth);
        int half = modeWidth / 2;
        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(modeX + 1, MODE_BUTTON_Y + 2, modeWidth, MODE_BUTTON_HEIGHT, new Color(0, 0, 0, 90));
        r.quad(modeX, MODE_BUTTON_Y, half, MODE_BUTTON_HEIGHT,
            mode3d ? UI_SURFACE : new Color(34, 83, 78, 235));
        r.quad(modeX + half, MODE_BUTTON_Y, modeWidth - half, MODE_BUTTON_HEIGHT,
            mode3d ? new Color(48, 70, 100, 235) : UI_SURFACE);
        r.quad(modeX + half, MODE_BUTTON_Y + 3, 1, MODE_BUTTON_HEIGHT - 6, UI_LINE);
        r.boxLines(modeX, MODE_BUTTON_Y, modeWidth, MODE_BUTTON_HEIGHT,
            mode3d ? new Color(108, 151, 218, 220) : UI_ACCENT);
        r.render();

        theme.textRenderer().begin(frameGraphics, 1.0);
        theme.textRenderer().render("2D", modeX + (half - theme.textWidth("2D")) / 2.0, MODE_BUTTON_Y + 5,
            mode3d ? UI_MUTED : UI_TEXT);
        theme.textRenderer().render("3D", modeX + half + (modeWidth - half - theme.textWidth("3D")) / 2.0, MODE_BUTTON_Y + 5,
            mode3d ? UI_TEXT : UI_MUTED);
        theme.textRenderer().end();
    }

    private int modeButtonX(double screenWidth) {
        return screenWidth < 760 ? dimensionButtonX(screenWidth) + dimensionButtonTotalWidth(screenWidth) + 6 : MODE_BUTTON_X;
    }

    private int modeButtonWidth(double screenWidth) {
        return screenWidth < 520 ? 44 : screenWidth < 760 ? 52 : MODE_BUTTON_WIDTH;
    }

    private void drawLootFilterStatus(double screenWidth) {
        searchCancelW = 0;
        searchCancelH = 0;
        if (lootItemFilters.isEmpty() && !lootSearchRunning) return;

        String items = lootFilterDisplay(lootItemFilters);
        long matched = lootFilterCache.values().stream().filter(Boolean.TRUE::equals).count();
        String text = lootSearchRunning
            ? (lootItemFilters.isEmpty() ? searchSuggestion : "Searching " + items)
                + "  " + lootSearchChecked + "/" + Math.max(lootSearchTotal, lootSearchChecked)
            : "Loot " + items + "  " + matched + "/" + lootFilterCache.size();
        if (!lootFilterInFlight.isEmpty()) text += " +" + lootFilterInFlight.size();

        double maxWidth = screenWidth - 20;
        if (searchBar != null && !searchSuggestion.isBlank()) {
            maxWidth = Math.min(maxWidth, Math.max(100, searchBar.x - 20));
        }
        double cancelWidth = lootSearchRunning ? 58 : 0;
        double width = Math.min(maxWidth, Math.max(150, theme.textWidth(text) + 24 + cancelWidth));
        double x = 10;
        double y = topControlHeight(screenWidth) + 8;

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(x + 1, y + 2, width, 23, new Color(0, 0, 0, 90));
        r.quad(x, y, width, 23, new Color(31, 58, 54, 230));
        r.quad(x, y, 3, 23, UI_ACCENT);
        r.boxLines(x, y, width, 23, UI_ACCENT);
        if (lootSearchRunning) {
            searchCancelX = x + width - cancelWidth;
            searchCancelY = y;
            searchCancelW = cancelWidth;
            searchCancelH = 23;
            r.quad(searchCancelX, y, cancelWidth, 23, new Color(92, 43, 48, 235));
            r.quad(searchCancelX, y + 4, 1, 15, new Color(204, 98, 106, 220));
        }
        r.render();

        theme.textRenderer().begin(frameGraphics, 1.0);
        theme.textRenderer().render(trimToWidth(text, width - 16 - cancelWidth), x + 9, y + 6, UI_TEXT);
        if (lootSearchRunning) {
            theme.textRenderer().render("Cancel", searchCancelX + 10, y + 6, new Color(255, 214, 218));
        }
        theme.textRenderer().end();
    }

    private boolean handleSearchCancelClick(double mouseX, double mouseY) {
        if (!lootSearchRunning || searchCancelW <= 0 || searchCancelH <= 0) return false;
        if (mouseX < searchCancelX || mouseX > searchCancelX + searchCancelW
            || mouseY < searchCancelY || mouseY > searchCancelY + searchCancelH) return false;
        cancelCurrentSearch(true);
        return true;
    }

    private void cancelCurrentSearch(boolean announce) {
        if (!lootSearchRunning) return;
        lootSearchRequestId++;
        lootSearchRunning = false;
        searchSuggestion = "Search cancelled";
        if (announce) ChatUtils.info("Seed Explorer: Search cancelled.");
    }

    private void drawSearchChestMarkers(MapViewport viewport, double mouseX, double mouseY) {
        if (searchChestMarkers.isEmpty() || selectedDimension != searchChestDimension) return;

        ChestLootOutput hovered = null;
        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        for (ChestLootOutput chest : searchChestMarkers) {
            double x = viewport.screenX(chest.blockX() + 0.5);
            double y = viewport.screenY(chest.blockZ() + 0.5);
            if (!viewport.isVisible(x, y, 16)) continue;
            boolean target = chest == matchedSearchChest;
            double size = target ? 14 : 10;
            double left = x - size / 2.0;
            double top = y - size / 2.0;
            boolean itemMatch = searchChestMatches.containsKey(chest);
            Color border = target ? new Color(246, 202, 86, 255)
                : itemMatch ? new Color(74, 201, 232, 255) : new Color(181, 132, 73, 235);
            Color body = target ? new Color(128, 73, 30, 250)
                : itemMatch ? new Color(30, 96, 116, 245) : new Color(75, 48, 29, 220);
            r.quad(left + 1, top + 2, size, size - 1, new Color(0, 0, 0, 130));
            r.quad(left, top + 2, size, size - 2, body);
            r.quad(left, top, size, 4, new Color(154, 92, 37, 250));
            r.quad(x - 1, y + 1, 3, 3, border);
            r.boxLines(left, top, size, size, border);
            if (Math.abs(mouseX - x) <= size && Math.abs(mouseY - y) <= size) hovered = chest;
        }
        r.render();

        if (hovered == null) return;
        String label = (hovered == matchedSearchChest ? "Best match  "
            : searchChestMatches.containsKey(hovered) ? "Item match  " : "Predicted chest  ")
            + hovered.blockX() + ", " + hovered.blockY() + ", " + hovered.blockZ();
        double maxWidth = Math.max(100, viewport.width() - 16);
        label = trimToWidth(label, maxWidth - 18);
        String loot = trimToWidth(hovered.summary(), maxWidth - 18);
        double width = Math.min(maxWidth, Math.max(theme.textWidth(label), theme.textWidth(loot)) + 18);
        double x = Math.max(8, Math.min(viewport.width() - width - 8, mouseX + 12));
        double y = Math.min(viewport.height() - 42, mouseY + 12);
        r.begin();
        r.quad(x, y, width, 38, UI_SURFACE);
        r.quad(x, y, 3, 38, hovered == matchedSearchChest ? new Color(246, 202, 86)
            : searchChestMatches.containsKey(hovered) ? new Color(74, 201, 232) : UI_ACCENT);
        r.boxLines(x, y, width, 38, UI_LINE);
        r.render();
        theme.textRenderer().begin(frameGraphics, 1.0);
        theme.textRenderer().render(label, x + 9, y + 7, UI_TEXT);
        theme.textRenderer().render(loot, x + 9, y + 22, UI_MUTED);
        theme.textRenderer().end();
    }

    private ChestLootOutput pickSearchChest(double mouseX, double mouseY) {
        if (searchChestMarkers.isEmpty() || selectedDimension != searchChestDimension) return null;
        Minecraft mc = Minecraft.getInstance();
        MapViewport viewport = MapViewport.create(offsetX, offsetZ, zoom,
            mc.getWindow().getWidth(), mc.getWindow().getHeight());
        ChestLootOutput closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (ChestLootOutput chest : searchChestMarkers) {
            double x = viewport.screenX(chest.blockX() + 0.5);
            double y = viewport.screenY(chest.blockZ() + 0.5);
            double dx = mouseX - x;
            double dy = mouseY - y;
            double distance = dx * dx + dy * dy;
            if (distance <= 12 * 12 && distance < closestDistance) {
                closest = chest;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private void drawAtlasStatus(double screenWidth) {
        BlockTextureAtlas atlas = BlockTextureAtlas.get();
        boolean fallbackOnly = atlas.loadedCount() == 0;
        if (!fallbackOnly) return;
        String text = fallbackOnly
            ? "3D atlas fallback"
            : "3D atlas " + atlas.loadedCount() + "/" + atlas.requestedCount();
        double width = Math.max(104, theme.textWidth(text) + 24);
        double x = screenWidth - width - 10;
        double y = Math.max(TOOLBAR_HEIGHT + 8, Minecraft.getInstance().getWindow().getHeight() - 30);

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(x + 1, y + 2, width, 19, new Color(0, 0, 0, 80));
        r.quad(x, y, width, 19, fallbackOnly ? new Color(64, 56, 27, 230) : new Color(20, 34, 40, 230));
        r.quad(x, y, 3, 19, fallbackOnly ? new Color(224, 180, 84, 235) : UI_ACCENT);
        r.boxLines(x, y, width, 19, fallbackOnly ? new Color(232, 196, 108, 235) : UI_LINE);
        r.render();

        theme.textRenderer().begin(frameGraphics, 1.0);
        theme.textRenderer().render(text, x + 9, y + 5, fallbackOnly ? new Color(242, 208, 139) : UI_TEXT);
        theme.textRenderer().end();
    }

    private void drawTerrainDebugInfo(double mouseX, double mouseY,
                                      double screenWidth, double screenHeight) {
        Terrain3DRenderer.DebugChunkInfo info = terrain3DRenderer.pickDebugChunk(
            camera3d, mouseX, mouseY, screenWidth / 2.0, screenHeight / 2.0);
        String[] legend = {
            "Green  generated + baked", "Blue  restored from disk",
            "Yellow  waiting for bake", "Orange  frame LOD fallback",
            "Purple  generation queued", "Cyan  stale upgrade",
            "Red  generation/bake failure"
        };
        Color[] colors = {
            new Color(48, 194, 88), new Color(55, 112, 238), new Color(241, 208, 58),
            new Color(240, 132, 42), new Color(153, 78, 214), new Color(53, 218, 220),
            new Color(235, 55, 62)
        };
        List<String> lines = new ArrayList<>();
        if (info != null) {
            lines.add("Chunk " + info.chunkX() + ", " + info.chunkZ());
            lines.add("Requested " + info.requestedLod() + "  displayed "
                + (info.displayedLod() < 0 ? "none" : info.displayedLod())
                + "  generated " + info.generatedStep());
            lines.add("Source " + info.cacheSource() + "  bake " + info.bakeState());
            if (!info.failureReason().isBlank()) lines.add("Reason " + info.failureReason());
        } else {
            lines.add("Point at a terrain chunk for details");
        }
        int width = 238;
        int height = 12 + legend.length * 14 + lines.size() * 14 + 10;
        double x = 10;
        double y = Math.max(topControlHeight(screenWidth) + 8, screenHeight - height - 10);
        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(x, y, width, height, new Color(10, 15, 20, 232));
        r.boxLines(x, y, width, height, new Color(98, 117, 130, 225));
        for (int i = 0; i < legend.length; i++) {
            r.quad(x + 9, y + 9 + i * 14, 8, 8, colors[i]);
        }
        r.render();
        theme.textRenderer().begin(frameGraphics, 1.0);
        for (int i = 0; i < legend.length; i++) {
            theme.textRenderer().render(legend[i], x + 23, y + 8 + i * 14, UI_MUTED);
        }
        double lineY = y + 10 + legend.length * 14;
        for (String line : lines) {
            theme.textRenderer().render(trimToWidth(line, width - 18), x + 9, lineY, UI_TEXT);
            lineY += 14;
        }
        theme.textRenderer().end();
    }

    private void drawLayerToggles(double screenWidth, double mouseX, double mouseY) {
        SeedExplorerModule module = Modules.get().get(SeedExplorerModule.class);
        if (module == null) return;

        ToolbarLayout layout = toolbarLayout(screenWidth);
        Renderer2D r = Renderer2D.COLOR;
        r.begin();

        for (int i = 0; i < PRIMARY_LAYERS.length; i++) {
            MapLayer layer = PRIMARY_LAYERS[i];
            int x = layout.primaryX(i);
            boolean enabled = module.isLayerEnabled(layer);
            boolean panelOpen = layer == MapLayer.STRUCTURES && structurePanelOpen;
            Color bgColor = enabled ? UI_ACCENT_SOFT : UI_SURFACE;
            Color topLine = enabled ? UI_ACCENT : UI_LINE;
            Color border = panelOpen
                ? new Color(226, 182, 76, 235)
                : enabled ? UI_ACCENT : UI_LINE;
            drawToolbarButton(r, x, LAYER_BUTTON_Y, layout.primaryWidth(), bgColor, topLine, border);
        }

        boolean secondaryEnabled = java.util.Arrays.stream(SECONDARY_LAYERS).anyMatch(module::isLayerEnabled);
        Color displayBorder = displayPanelOpen ? new Color(126, 164, 220, 235)
            : secondaryEnabled ? new Color(90, 126, 160, 220) : UI_LINE;
        drawToolbarButton(r, layout.displayX(), LAYER_BUTTON_Y, layout.displayWidth(),
            displayPanelOpen ? new Color(36, 52, 68, 235) : UI_SURFACE,
            displayPanelOpen ? new Color(126, 164, 220, 225) : UI_LINE, displayBorder);

        boolean hasItemSearch = !lootItemFilters.isEmpty();
        Color searchBg = hasItemSearch ? new Color(69, 58, 31, 232) : UI_SURFACE;
        Color searchAccent = hasItemSearch ? new Color(235, 189, 76, 240) : UI_LINE;
        drawToolbarButton(r, layout.itemSearchX(), LAYER_BUTTON_Y, layout.itemSearchWidth(), searchBg,
            hasItemSearch ? searchAccent : new Color(205, 159, 67, 205),
            hasItemSearch ? searchAccent : new Color(118, 96, 52, 205));

        boolean undergroundActive = undergroundView || automaticUndergroundView;
        if (layout.showUnderground()) {
            Color underBg = undergroundActive ? new Color(43, 54, 84, 228) : UI_SURFACE;
            Color underAccent = undergroundActive ? new Color(126, 164, 240, 235) : UI_LINE;
            drawToolbarButton(r, layout.undergroundX(), LAYER_BUTTON_Y, layout.undergroundWidth(),
                underBg, underAccent, underAccent);
        }

        r.render();

        theme.textRenderer().begin(frameGraphics, 1.0);
        for (int i = 0; i < PRIMARY_LAYERS.length; i++) {
            MapLayer layer = PRIMARY_LAYERS[i];
            int x = layout.primaryX(i);
            boolean enabled = module.isLayerEnabled(layer);
            Color textColor = enabled ? UI_TEXT : UI_MUTED;
            Texture icon = layerIcon(layer);
            boolean iconOnly = layout.mini() && icon != null;
            double textX = x + (icon == null || iconOnly ? 7 : 25);
            if (!iconOnly) {
                theme.textRenderer().render(trimToWidth(compactLayerLabel(layer, layout),
                    layout.primaryWidth() - (textX - x) - 6), textX, LAYER_BUTTON_Y + 5, textColor);
            }
        }
        theme.textRenderer().render(trimToWidth(layout.mini() ? "More" : "Display", layout.displayWidth() - 12),
            layout.displayX() + 6, LAYER_BUTTON_Y + 5, displayPanelOpen ? UI_TEXT : UI_MUTED);
        theme.textRenderer().render(trimToWidth(layout.mini() ? "Items" : "Item Search", layout.itemSearchWidth() - 12),
            layout.itemSearchX() + 6, LAYER_BUTTON_Y + 5,
            hasItemSearch ? new Color(255, 222, 143) : new Color(229, 194, 119));
        if (layout.showUnderground()) {
            theme.textRenderer().render(trimToWidth(layout.mini() ? "Below" : "Underground", layout.undergroundWidth() - 12),
                layout.undergroundX() + 6, LAYER_BUTTON_Y + 5,
                undergroundActive ? new Color(170, 200, 255) : UI_MUTED);
        }
        theme.textRenderer().end();

        drawPrimaryLayerIcons(layout, module);
        if (layout.mini()) drawToolbarHoverLabel(layout, mouseX, mouseY);
    }

    /**
     * The map is an independent GUI render target, so Minecraft's sky pass is not
     * drawn behind it. Recreate a small sky layer from the live world clock and
     * free-camera orientation before terrain is rendered. Cave cutaway openings
     * therefore reveal the same day/night direction instead of a black canvas.
     */
    private void draw3dSkyBackground(Renderer2D renderer, double width, double height,
                                     Minecraft mc) {
        // Use the world's day clock, not gameTime. gameTime is monotonic and does not
        // track the sun after a time skip/sleep; dayTime is the same clock used by
        // LevelRenderer's vanilla celestial angle.
        long dayTime = worldDayTime(mc);
        int dimension = selectedDimension;
        if (dimension == -1) {
            renderer.quad(0, 0, width, height,
                new Color(32, 8, 8, 255), new Color(45, 10, 8, 255),
                new Color(93, 25, 13, 255), new Color(74, 18, 10, 255));
            return;
        }
        if (dimension == 1) {
            renderer.quad(0, 0, width, height,
                new Color(4, 3, 10, 255), new Color(10, 6, 18, 255),
                new Color(18, 10, 28, 255), new Color(11, 7, 18, 255));
            return;
        }
        // Minecraft's celestial angle is offset by -0.25 turns so sunrise is
        // at the horizon and noon places the sun overhead.
        double angle = ((Math.floorMod(dayTime, 24_000L) / 24_000.0) - 0.25) * Math.PI * 2.0;
        double sunX = -Math.cos(angle);
        double sunY = Math.sin(angle);
        double sunZ = 0.0;
        double daylight = clamp01((sunY + 0.12) / 1.12);

        Color top = blendSky(new Color(8, 13, 31, 255), new Color(83, 155, 228, 255), daylight);
        Color horizon = blendSky(new Color(17, 22, 43, 255), new Color(173, 211, 244, 255), daylight);
        renderer.quad(0, 0, width, height, top, top, horizon, horizon);

        double forwardX = camera3d.forwardX();
        double forwardY = camera3d.forwardY();
        double forwardZ = camera3d.forwardZ();
        double rightX = camera3d.rightX();
        double rightZ = camera3d.rightZ();
        // right x forward gives the camera's Y-up direction.
        double upX = rightZ * forwardY;
        double upY = rightZ * forwardX - rightX * forwardZ;
        double upZ = -rightX * forwardY;
        double forwardDot = forwardX * sunX + forwardY * sunY + forwardZ * sunZ;
        if (forwardDot <= 0.08) return;

        double rightDot = rightX * sunX + rightZ * sunZ;
        double upDot = upX * sunX + upY * sunY + upZ * sunZ;
        double projectionScale = height / (2.0 * Math.tan(Math.toRadians(30.0)));
        double sunScreenX = width * 0.5 + rightDot / forwardDot * projectionScale;
        double sunScreenY = height * 0.5 - upDot / forwardDot * projectionScale;
        double radius = Math.max(9.0, Math.min(22.0, height * 0.018));
        if (sunScreenX < -radius * 3 || sunScreenX > width + radius * 3
            || sunScreenY < -radius * 3 || sunScreenY > height + radius * 3) return;

        drawSkyDisc(renderer, sunScreenX, sunScreenY, radius * 2.4,
            new Color(255, 221, 142, (int) Math.round(55 + 65 * daylight)));
        drawSkyDisc(renderer, sunScreenX, sunScreenY, radius,
            new Color(255, 247, 204, (int) Math.round(130 + 125 * daylight)));
    }

    private static void drawSkyDisc(Renderer2D renderer, double x, double y, double radius,
                                    Color color) {
        final int segments = 16;
        for (int index = 0; index < segments; index++) {
            double first = index * Math.PI * 2.0 / segments;
            double second = (index + 1) * Math.PI * 2.0 / segments;
            renderer.triangle(x, y, x + Math.cos(first) * radius, y + Math.sin(first) * radius,
                x + Math.cos(second) * radius, y + Math.sin(second) * radius, color);
        }
    }

    private static Color blendSky(Color night, Color day, double amount) {
        double t = clamp01(amount);
        return new Color((int) Math.round(night.r + (day.r - night.r) * t),
            (int) Math.round(night.g + (day.g - night.g) * t),
            (int) Math.round(night.b + (day.b - night.b) * t), 255);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** 26.1 mappings expose the level clock under different names across loader builds. */
    private static long worldDayTime(Minecraft mc) {
        if (mc.level == null) return 6_000L;
        try {
            java.lang.reflect.Method method;
            try {
                method = mc.level.getClass().getMethod("getDayTime");
            } catch (NoSuchMethodException ignored) {
                method = mc.level.getClass().getMethod("dayTime");
            }
            Object value = method.invoke(mc.level);
            if (value instanceof Number number) return number.longValue();
        } catch (Throwable ignored) {
            // Fall back to the monotonic clock only on mappings without a day clock.
            try { return mc.level.getGameTime(); } catch (Throwable ignoredAgain) { }
        }
        return 6_000L;
    }

    private void drawToolbarButton(Renderer2D r, int x, int y, int width, Color bg, Color accent, Color border) {
        r.quad(x + 1, y + 2, width, LAYER_BUTTON_HEIGHT, new Color(0, 0, 0, 75));
        r.quad(x, y, width, LAYER_BUTTON_HEIGHT, bg);
        r.quad(x, y, width, 2, accent);
        r.boxLines(x, y, width, LAYER_BUTTON_HEIGHT, border);
    }

    private void drawPrimaryLayerIcons(ToolbarLayout layout, SeedExplorerModule module) {
        Renderer2D tex = Renderer2D.TEXTURE;
        for (int i = 0; i < PRIMARY_LAYERS.length; i++) {
            MapLayer layer = PRIMARY_LAYERS[i];
            Texture icon = layerIcon(layer);
            if (icon == null) continue;
            int x = layout.primaryX(i);
            boolean enabled = module.isLayerEnabled(layer);
            Color tint = enabled ? Color.WHITE : new Color(130, 136, 142, 120);
            tex.begin();
            int iconX = x + (layout.mini() ? (layout.primaryWidth() - 14) / 2 : 5);
            tex.texQuad(iconX, LAYER_BUTTON_Y + 4, 14, 14, tint);
            tex.render(icon.getTextureView(), icon.getSampler());
        }
    }

    private String compactLayerLabel(MapLayer layer, ToolbarLayout layout) {
        if (!layout.mini()) return layerLabel(layer);
        return switch (layer) {
            case BIOMES -> "Bio";
            case STRUCTURES -> "Str";
            default -> layerLabel(layer);
        };
    }

    private void drawToolbarHoverLabel(ToolbarLayout layout, double mouseX, double mouseY) {
        if (mouseY < LAYER_BUTTON_Y || mouseY > LAYER_BUTTON_Y + LAYER_BUTTON_HEIGHT) return;
        String tooltip = null;
        for (int i = 0; i < PRIMARY_LAYERS.length; i++) {
            int x = layout.primaryX(i);
            if (mouseX >= x && mouseX <= x + layout.primaryWidth()) {
                tooltip = PRIMARY_LAYERS[i].title;
                break;
            }
        }
        if (tooltip == null) return;
        double width = theme.textWidth(tooltip) + 14;
        double x = Math.max(6, Math.min(layout.screenWidth() - width - 6, mouseX - width / 2));
        double y = TOOLBAR_HEIGHT + 5;
        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(x, y, width, 19, UI_SURFACE);
        r.boxLines(x, y, width, 19, UI_LINE);
        r.render();
        theme.textRenderer().begin(frameGraphics, 1.0);
        theme.textRenderer().render(tooltip, x + 7, y + 5, UI_TEXT);
        theme.textRenderer().end();
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
        r.quad(0, 33, screenWidth, height - 33, new Color(12, 18, 24, 236));
        r.quad(0, 32, screenWidth, 1, new Color(43, 59, 70, 175));
        r.quad(0, height - 1, screenWidth, 1, UI_LINE);
        r.render();
    }

    private void drawNoSeedState(double screenWidth, double screenHeight) {
        Renderer2D r = Renderer2D.COLOR;
        String title = "No world seed set";
        String hint = "Enter a seed in the box at the top right to begin exploring.";
        var text = theme.textRenderer();
        double titleW = text.getWidth(title);
        double boxW = Math.max(180, Math.min(screenWidth - 20, Math.max(titleW, text.getWidth(hint)) + 48));
        String fittedHint = trimToWidth(hint, boxW - 28);
        double hintW = text.getWidth(fittedHint);
        double boxH = 58;
        double boxX = (screenWidth - boxW) / 2.0;
        double boxY = (screenHeight - boxH) / 2.0;

        r.begin();
        r.quad(boxX, boxY, boxW, boxH, UI_SURFACE);
        r.quad(boxX, boxY, 4, boxH, UI_ACCENT);
        r.boxLines(boxX, boxY, boxW, boxH, UI_LINE);
        r.render();

        text.begin(frameGraphics, 1.0, false, true);
        text.render(title, boxX + (boxW - titleW) / 2.0, boxY + 12, UI_TEXT);
        text.render(fittedHint, boxX + (boxW - hintW) / 2.0, boxY + 30, UI_MUTED);
        text.end();
    }

    private void drawSearchSuggestion() {
        if (searchBar == null || searchSuggestion.isBlank() || !lootItemFilters.isEmpty() || lootSearchRunning) return;

        double screenWidth = Minecraft.getInstance().getWindow().getWidth();
        double x = Math.max(8, Math.min(searchBar.x, screenWidth - 128));
        double y = topControlHeight(Minecraft.getInstance().getWindow().getWidth()) + 8;
        double width = Math.max(120, Math.min(searchBar.width, screenWidth - x - 8));

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(x, y, width, SEARCH_SUGGESTION_HEIGHT, UI_SURFACE);
        r.quad(x, y, 3, SEARCH_SUGGESTION_HEIGHT, UI_ACCENT);
        r.boxLines(x, y, width, SEARCH_SUGGESTION_HEIGHT, UI_LINE);
        r.render();

        var icon = searchStructureFilter == null ? null : StructureIcons.get(searchStructureFilter);
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

        theme.textRenderer().begin(frameGraphics, 1.0);
        double textX = x + (icon == null ? 8 : 25);
        theme.textRenderer().render(trimToWidth(text, width - (textX - x) - 8), textX, y + 6, UI_TEXT);
        theme.textRenderer().end();
    }

    private boolean handleSearchSuggestionClick(double mouseX, double mouseY) {
        if (searchBar == null || searchSuggestion.isBlank() || !lootItemFilters.isEmpty() || lootSearchRunning) return false;

        double screenWidth = Minecraft.getInstance().getWindow().getWidth();
        double x = Math.max(8, Math.min(searchBar.x, screenWidth - 128));
        double y = topControlHeight(Minecraft.getInstance().getWindow().getWidth()) + 8;
        double width = Math.max(120, Math.min(searchBar.width, screenWidth - x - 8));
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + SEARCH_SUGGESTION_HEIGHT) return false;

        if (searchStructureFilter != null) jumpToSearchResult();
        return true;
    }

    private int topControlHeight(double screenWidth) {
        return TOOLBAR_HEIGHT;
    }

    private ToolbarLayout toolbarLayout(double screenWidth) {
        int width = Math.max(1, (int) Math.floor(screenWidth));
        int start;
        int gap;
        int primaryWidth;
        int displayWidth;
        int itemWidth;
        int undergroundWidth;
        boolean mini;
        if (width <= 480) {
            start = 6;
            gap = 3;
            primaryWidth = 34;
            displayWidth = 46;
            itemWidth = 70;
            undergroundWidth = 64;
            mini = true;
        } else if (width <= 760) {
            start = 8;
            gap = 4;
            primaryWidth = 58;
            displayWidth = 58;
            itemWidth = 86;
            undergroundWidth = 78;
            mini = false;
        } else {
            start = 10;
            gap = 5;
            primaryWidth = 78;
            displayWidth = 72;
            itemWidth = 96;
            undergroundWidth = 88;
            mini = false;
        }

        int displayX = start + PRIMARY_LAYERS.length * (primaryWidth + gap);
        int itemX = displayX + displayWidth + gap;
        int undergroundX = itemX + itemWidth + gap;
        boolean showUnderground = mode3d && undergroundX + undergroundWidth <= width - start;
        return new ToolbarLayout(width, start, gap, primaryWidth, displayX, displayWidth,
            itemX, itemWidth, undergroundX, undergroundWidth, showUnderground, mini);
    }

    private DisplayPanelLayout displayPanelLayout(double screenWidth, double screenHeight) {
        ToolbarLayout toolbar = toolbarLayout(screenWidth);
        int panelWidth = Math.max(180, Math.min(300, (int) screenWidth - 12));
        int columns = panelWidth >= 250 ? 2 : 1;
        int optionCount = MapLayer.values().length
            + (mode3d && !toolbar.showUnderground() ? 1 : 0)
            + (mode3d ? 1 : 0)
            + 1;
        int rows = Math.max(1, (optionCount + columns - 1) / columns);
        int panelY = floatingPanelY(screenWidth);
        int availableHeight = Math.max(80, (int) screenHeight - panelY - 8);
        int rowHeight = Math.max(18, Math.min(25, (availableHeight - 34) / rows));
        int panelHeight = Math.min(availableHeight, 34 + rows * rowHeight);
        int panelX = Math.max(6, Math.min(toolbar.displayX(), (int) screenWidth - panelWidth - 6));
        int rowWidth = Math.max(60, (panelWidth - 12) / columns);
        return new DisplayPanelLayout(panelX, panelY, panelWidth, panelHeight, columns,
            rowWidth, rowHeight, optionCount);
    }

    private int floatingPanelY(double screenWidth) {
        boolean suggestionVisible = searchBar != null && !searchSuggestion.isBlank()
            && lootItemFilters.isEmpty() && !lootSearchRunning;
        boolean lootStatusVisible = !lootItemFilters.isEmpty() || lootSearchRunning;
        return topControlHeight(screenWidth) + 8 + (suggestionVisible || lootStatusVisible ? 31 : 0);
    }

    private void drawContextMenu() {
        if (isCameraContextMenu) {
            drawSimpleContextMenu(new String[]{"Teleport to Camera"});
            return;
        }
        if (!isWaypointContextMenu && selectedStructure != null) {
            drawStructurePopup();
            return;
        }

        String[] items;

        if (isWaypointContextMenu) {
            items = new String[]{"Copy Coords", "Delete Waypoint", "Center Map"};
        } else {
            boolean hasWaypoint = selectedStructure != null && WaypointManager.get().hasWaypointAt(
                selectedStructure.x, selectedStructure.z, selectedDimension);
            if (hasWaypoint) {
                items = new String[]{"Copy Coords", "Remove Waypoint", "Center Map", "TP"};
            } else {
                items = new String[]{"Copy Coords", "Create Waypoint", "Center Map", "TP"};
            }
        }

        drawSimpleContextMenu(items);
    }

    private void drawSimpleContextMenu(String[] items) {
        int itemHeight = 20;
        int menuWidth = 160;
        int menuHeight = items.length * itemHeight;
        double[] position = contextMenuPosition(menuWidth, menuHeight);
        double mx = position[0];
        double my = position[1];

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(mx, my, menuWidth, menuHeight, new Color(20, 20, 30, 230));
        r.boxLines(mx, my, menuWidth, menuHeight, UI_LINE);
        for (int i = 1; i < items.length; i++) {
            r.quad(mx, my + i * itemHeight, menuWidth, 1, new Color(60, 60, 80, 200));
        }
        r.render();

        theme.textRenderer().begin(frameGraphics, 1.0);
        for (int i = 0; i < items.length; i++) {
            theme.textRenderer().render(items[i], mx + 8, my + i * itemHeight + 4, UI_TEXT);
        }
        theme.textRenderer().end();
    }

    private double[] contextMenuPosition(int menuWidth, int menuHeight) {
        Minecraft mc = Minecraft.getInstance();
        double mx = Math.min(contextMenuX, mc.getWindow().getWidth() - menuWidth);
        double my = Math.min(contextMenuY, mc.getWindow().getHeight() - menuHeight);
        return new double[]{Math.max(0, mx), Math.max(0, my)};
    }

    private void drawStructurePopup() {
        GeneratedStructure structure = selectedStructure;
        if (structure == null) return;

        boolean hasWaypoint = WaypointManager.get().hasWaypointAt(structure.x, structure.z, selectedDimension);
        boolean completed = isStructureCompleted(structure);
        String[] actions = structurePopupActions(hasWaypoint);
        int menuWidth = structurePopupWidth(Minecraft.getInstance().getWindow().getWidth());
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

        var icon = StructureIcons.get(structure);
        if (icon != null) {
            Renderer2D tex = Renderer2D.TEXTURE;
            tex.begin();
            tex.texQuad(mx + 8, my + 8, 20, 20, Color.WHITE);
            tex.render(icon.getTextureView(), icon.getSampler());
        }

        String title = trimToWidth(structure.displayName(), menuWidth - 42);
        String coords = structure.y == GeneratedStructure.UNKNOWN_Y
            ? "X: " + structure.x + " Z: " + structure.z
            : "X: " + structure.x + " Y: " + structure.y + " Z: " + structure.z;
        theme.textRenderer().begin(frameGraphics, 1.0);
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
        theme.textRenderer().begin(frameGraphics, 1.0);
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
            theme.textRenderer().begin(frameGraphics, 1.0);
            theme.textRenderer().render(actions[i], mx + 10, y + 5, UI_TEXT);
            theme.textRenderer().end();
        }
    }

    private String[] structurePopupActions(boolean hasWaypoint) {
        return new String[]{"Copy Coordinates", hasWaypoint ? "Remove Waypoint" : "Create Waypoint", "Center Map", "TP", "Show Loot"};
    }

    private int structurePopupWidth(double screenWidth) {
        return Math.max(176, Math.min(STRUCTURE_POPUP_WIDTH, (int) screenWidth - 8));
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
        int panelWidth = Math.max(180, Math.min(260, (int) screenWidth - 20));
        int panelX = Math.max(6, Math.min(10, (int) screenWidth - panelWidth - 6));
        int panelY = floatingPanelY(screenWidth);
        int panelHeight = Math.max(30, Math.min((int) screenHeight - panelY - 12, 30 + types.size() * rowHeight));

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(panelX, panelY, panelWidth, panelHeight, UI_SURFACE);
        r.quad(panelX, panelY, 4, panelHeight, UI_ACCENT);
        r.boxLines(panelX, panelY, panelWidth, panelHeight, UI_LINE);
        r.render();

        int visibleRows = Math.max(0, (panelHeight - 30) / rowHeight);
        int maxScroll = Math.max(0, types.size() - visibleRows);
        structurePanelScroll = Math.max(0, Math.min(structurePanelScroll, maxScroll));
        String range = types.size() > visibleRows && visibleRows > 0
            ? (structurePanelScroll + 1) + "-" + Math.min(types.size(), structurePanelScroll + visibleRows)
                + " / " + types.size()
            : "";
        theme.textRenderer().begin(frameGraphics, 1.0);
        theme.textRenderer().render(trimToWidth(dimensionName(selectedDimension) + " Structures",
            panelWidth - 24 - (range.isBlank() ? 0 : theme.textWidth(range) + 12)), panelX + 12, panelY + 8, UI_TEXT);
        if (!range.isBlank()) {
            theme.textRenderer().render(range, panelX + panelWidth - theme.textWidth(range) - 10,
                panelY + 8, UI_MUTED);
        }
        theme.textRenderer().end();

        for (int row = 0; row < visibleRows && structurePanelScroll + row < types.size(); row++) {
            StructureType type = types.get(structurePanelScroll + row);
            int y = panelY + 28 + row * rowHeight;
            boolean enabled = SeedManager.get().getProfileStructure(type.name(), true);
            Color rowColor = enabled ? UI_SURFACE_2 : new Color(18, 22, 28, 150);
            r.begin();
            r.quad(panelX + 6, y, panelWidth - 12, rowHeight - 2, rowColor);
            r.boxLines(panelX + 12, y + 4, 10, 10, enabled ? UI_ACCENT : UI_DISABLED);
            if (enabled) r.quad(panelX + 15, y + 7, 4, 4, UI_ACCENT);
            r.render();

            var icon = StructureIcons.get(type);
            if (icon != null) {
                Renderer2D tex = Renderer2D.TEXTURE;
                tex.begin();
                tex.texQuad(panelX + 30, y + 3, 16, 16, enabled ? Color.WHITE : new Color(135, 140, 146, 115));
                tex.render(icon.getTextureView(), icon.getSampler());
            }

            theme.textRenderer().begin(frameGraphics, 1.0);
            theme.textRenderer().render(trimToWidth(type.displayName, panelWidth - 64),
                panelX + 52, y + 5, enabled ? UI_TEXT : UI_MUTED);
            theme.textRenderer().end();
        }
    }

    private void drawDisplayPanel(double screenWidth, double screenHeight) {
        SeedExplorerModule module = Modules.get().get(SeedExplorerModule.class);
        if (module == null) return;
        DisplayPanelLayout panel = displayPanelLayout(screenWidth, screenHeight);

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(panel.x() + 2, panel.y() + 3, panel.width(), panel.height(), new Color(0, 0, 0, 95));
        r.quad(panel.x(), panel.y(), panel.width(), panel.height(), UI_SURFACE);
        r.quad(panel.x(), panel.y(), 4, panel.height(), new Color(108, 151, 218, 230));
        r.boxLines(panel.x(), panel.y(), panel.width(), panel.height(), UI_LINE);
        r.render();

        theme.textRenderer().begin(frameGraphics, 1.0);
        theme.textRenderer().render("Display layers", panel.x() + 12, panel.y() + 8, UI_TEXT);
        theme.textRenderer().end();

        for (int index = 0; index < panel.optionCount(); index++) {
            int col = index % panel.columns();
            int row = index / panel.columns();
            int x = panel.x() + 6 + col * panel.rowWidth();
            int y = panel.y() + 28 + row * panel.rowHeight();
            int extraIndex = index - MapLayer.values().length;
            boolean undergroundIncluded = mode3d && !toolbarLayout(screenWidth).showUnderground();
            boolean undergroundOption = extraIndex == 0 && undergroundIncluded;
            boolean terrainDebugOption = mode3d
                && extraIndex == (undergroundIncluded ? 1 : 0);
            int loadMapIndex = (undergroundIncluded ? 1 : 0) + (mode3d ? 1 : 0);
            boolean loadMapOption = extraIndex == loadMapIndex;
            MapLayer layer = extraIndex < 0 ? MapLayer.values()[index] : null;
            boolean enabled = undergroundOption ? undergroundView || automaticUndergroundView
                : terrainDebugOption ? terrainDebugView
                    : !loadMapOption && module.isLayerEnabled(layer);
            Color accent = undergroundOption ? new Color(126, 164, 240, 235)
                : terrainDebugOption ? new Color(231, 159, 58, 235) : UI_ACCENT;

            r.begin();
            r.quad(x, y, panel.rowWidth() - 4, panel.rowHeight() - 3,
                loadMapOption ? new Color(30, 70, 67, 235)
                    : enabled ? new Color(25, 43, 49, 235) : new Color(15, 21, 27, 205));
            if (loadMapOption) {
                r.boxLines(x, y, panel.rowWidth() - 4, panel.rowHeight() - 3, UI_ACCENT);
            } else {
                r.boxLines(x + 7, y + 5, 10, 10, enabled ? accent : UI_DISABLED);
                if (enabled) r.quad(x + 10, y + 8, 4, 4, accent);
            }
            r.render();

            Texture icon = layer == null ? null : layerIcon(layer);
            if (icon != null) {
                Renderer2D tex = Renderer2D.TEXTURE;
                tex.begin();
                tex.texQuad(x + 24, y + 3, 14, 14, enabled ? Color.WHITE : new Color(130, 136, 142, 120));
                tex.render(icon.getTextureView(), icon.getSampler());
            }

            String label = undergroundOption ? "Underground"
                : terrainDebugOption ? "Terrain debug"
                    : loadMapOption ? "Load map" : layerLabel(layer);
            int textX = loadMapOption ? x + 10 : x + (icon == null ? 25 : 43);
            theme.textRenderer().begin(frameGraphics, 1.0);
            theme.textRenderer().render(trimToWidth(label, panel.rowWidth() - (textX - x) - 9),
                textX, y + 6, loadMapOption || enabled ? UI_TEXT : UI_MUTED);
            theme.textRenderer().end();
        }
    }

    private void startMapLoading() {
        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0L) {
            ChatUtils.error("Seed Explorer: Set a world seed before loading the map.");
            return;
        }

        cancelCurrentSearch(false);
        displayPanelOpen = false;
        structurePanelOpen = false;
        closeContextMenu();
        dragging = false;
        dragging3d = false;
        camera3d.stopFlight();
        if (seedBox != null) seedBox.setFocused(false);
        if (searchBar != null) searchBar.setFocused(false);
        if (versionDropdown != null) versionDropdown.setFocused(false);

        mapLoadingSeed = seed;
        mapLoadingDimension = selectedDimension;
        mapLoadingCenterX = mode3d ? camera3d.targetX() : offsetX;
        mapLoadingCenterZ = mode3d ? camera3d.targetZ() : offsetZ;
        mapLoadingStopRequested = false;
        mapLoading = true;
        if (topControls != null) topControls.visible = false;
        mapLoadingStatus = terrain3DRenderer.bulkLoad(mapLoadingSeed, mapLoadingDimension,
            mapLoadingCenterX, mapLoadingCenterZ);
        MapLoadingMode.activate(this);
    }

    private void stopMapLoading() {
        if (!mapLoading) return;
        mapLoadingStopRequested = true;
    }

    /**
     * Ends bulk loading from the next GUI pass, after the frame that accepted the
     * Stop click has completed. This prevents renderer state from changing in
     * the middle of input dispatch.
     */
    private void finishMapLoadingStop() {
        if (!mapLoading) return;
        terrain3DRenderer.stopBulkLoading();
        mapLoading = false;
        mapLoadingStopRequested = false;
        MapLoadingMode.deactivate(this);
        mapLoadingStatus = null;
        if (topControls != null) topControls.visible = true;
    }

    private void renderMapLoadingScreen(double screenWidth, double screenHeight) {
        if (topControls != null) topControls.visible = false;
        mapLoadingStatus = terrain3DRenderer.bulkLoad(mapLoadingSeed, mapLoadingDimension,
            mapLoadingCenterX, mapLoadingCenterZ);

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(0, 0, screenWidth, screenHeight, new Color(4, 6, 8, 255));

        double barWidth = Math.max(180.0, Math.min(640.0, screenWidth - 64.0));
        double barHeight = 18.0;
        double barX = (screenWidth - barWidth) / 2.0;
        double barY = screenHeight / 2.0;
        double progress = mapLoadingStatus == null ? 0.0 : mapLoadingStatus.progress();
        r.quad(barX, barY, barWidth, barHeight, new Color(15, 23, 29, 255));
        r.quad(barX, barY, barWidth * progress, barHeight, UI_ACCENT);
        r.boxLines(barX, barY, barWidth, barHeight, new Color(72, 96, 108, 230));

        mapLoadingStopW = 92;
        mapLoadingStopH = 24;
        mapLoadingStopX = (int) Math.round((screenWidth - mapLoadingStopW) / 2.0);
        mapLoadingStopY = (int) Math.round(barY + 92);
        r.quad(mapLoadingStopX, mapLoadingStopY, mapLoadingStopW, mapLoadingStopH,
            new Color(54, 31, 35, 245));
        r.boxLines(mapLoadingStopX, mapLoadingStopY, mapLoadingStopW, mapLoadingStopH,
            new Color(177, 84, 91, 235));
        r.render();

        String title = "Loading map";
        String phase = mapLoadingStatus != null && mapLoadingStatus.restoringCache()
            ? "Restoring saved map data"
            : mapLoadingStatus != null && mapLoadingStatus.exactTargetComplete()
                ? "Exact square complete - extending outer coverage"
                : "Generating 10,025 x 10,025 exact-detail square";
        String progressText = mapLoadingStatus == null ? "Starting..."
            : String.format(Locale.ROOT, "%.4f%%  %,d / %,d chunks",
                progress * 100.0, mapLoadingStatus.completedExactChunks(),
                mapLoadingStatus.targetExactChunks());
        String activity = mapLoadingStatus == null ? ""
            : String.format(Locale.ROOT,
                "%,.1f chunks/s  |  ETA %s  |  Running %d / %d  |  Disk queue %,d",
                mapLoadingStatus.chunksPerSecond(), formatMapLoadingEta(mapLoadingStatus.etaSeconds()),
                mapLoadingStatus.activeTerrainWorkers(), mapLoadingStatus.terrainWorkers(),
                mapLoadingStatus.pendingCacheWrites());
        String frontier = mapLoadingStatus == null ? ""
            : "Detail radius " + mapLoadingStatus.detailRing()
                + "  |  Coverage radius " + mapLoadingStatus.coverageRing()
                + "  |  Queued " + mapLoadingStatus.queuedGenerationJobs()
                + "  |  Frontier idle " + mapLoadingStatus.stalledSeconds() + "s"
                + "  |  Retries " + mapLoadingStatus.retryChunks()
                + "  |  Failures " + mapLoadingStatus.failedChunks();

        var text = theme.textRenderer();
        phase = trimToWidth(phase, screenWidth - 32.0);
        progressText = trimToWidth(progressText, screenWidth - 32.0);
        activity = trimToWidth(activity, screenWidth - 32.0);
        frontier = trimToWidth(frontier, screenWidth - 32.0);
        text.begin(frameGraphics, 1.0);
        text.render(title, (screenWidth - text.getWidth(title)) / 2.0,
            barY - 58, UI_TEXT);
        text.render(phase, (screenWidth - text.getWidth(phase)) / 2.0,
            barY - 34, UI_MUTED);
        text.render(progressText, (screenWidth - text.getWidth(progressText)) / 2.0,
            barY + 28, UI_TEXT);
        text.render(activity, Math.max(16.0, (screenWidth - text.getWidth(activity)) / 2.0),
            barY + 48, UI_MUTED);
        text.render(frontier, Math.max(16.0, (screenWidth - text.getWidth(frontier)) / 2.0),
            barY + 64, UI_MUTED);
        text.render("Stop", mapLoadingStopX + (mapLoadingStopW - text.getWidth("Stop")) / 2.0,
            mapLoadingStopY + 7, new Color(255, 220, 222));
        text.end();
    }

    private static String formatMapLoadingEta(long seconds) {
        if (seconds < 0L) return "calculating";
        long days = seconds / 86_400L;
        long hours = seconds % 86_400L / 3_600L;
        long minutes = seconds % 3_600L / 60L;
        if (days > 0L) return days + "d " + hours + "h";
        if (hours > 0L) return hours + "h " + minutes + "m";
        return Math.max(0L, minutes) + "m";
    }

    private void handleContextMenuClick(double mouseX, double mouseY) {
        if (isCameraContextMenu) {
            double[] position = contextMenuPosition(160, 20);
            if (mouseX >= position[0] && mouseX <= position[0] + 160
                && mouseY >= position[1] && mouseY <= position[1] + 20) {
                teleportToCamera();
            }
            closeContextMenu();
            return;
        }
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

        closeContextMenu();
    }

    private void teleportToCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) {
            ChatUtils.error("Seed Explorer: Cannot teleport without an active world connection.");
            return;
        }

        double x = camera3d.eyeX();
        double y = camera3d.eyeY();
        double z = camera3d.eyeZ();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            ChatUtils.error("Seed Explorer: Camera position is invalid.");
            return;
        }

        mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp %.3f %.3f %.3f", x, y, z));
        ChatUtils.info(String.format(Locale.ROOT,
            "Seed Explorer: Teleporting to camera at (highlight)%.1f, %.1f, %.1f(default).", x, y, z));
    }

    private void teleportToChest(ChestLootOutput chest) {
        if (chest == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) {
            ChatUtils.error("Seed Explorer: Cannot teleport without an active world connection.");
            return;
        }
        double x = chest.blockX() + 0.5;
        double y = chest.blockY() + 1.0;
        double z = chest.blockZ() + 0.5;
        String dimension = switch (selectedDimension) {
            case -1 -> "minecraft:the_nether";
            case 1 -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
        mc.player.connection.sendCommand(String.format(Locale.ROOT,
            "execute in %s run tp @s %.1f %.1f %.1f", dimension, x, y, z));
        ChatUtils.info("Seed Explorer: Teleporting to chest at (highlight)"
            + chest.blockX() + ", " + chest.blockY() + ", " + chest.blockZ()
            + "(default) in " + dimensionName(selectedDimension) + ".");
    }

    private void closeContextMenu() {
        contextMenuOpen = false;
        isCameraContextMenu = false;
        selectedStructure = null;
        selectedWaypoint = null;
    }

    private void handleStructurePopupClick(double mouseX, double mouseY) {
        boolean hasWaypoint = WaypointManager.get().hasWaypointAt(selectedStructure.x, selectedStructure.z, selectedDimension);
        String[] actions = structurePopupActions(hasWaypoint);
        int menuWidth = structurePopupWidth(Minecraft.getInstance().getWindow().getWidth());
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
            case 0 -> copyStructureCoords(selectedStructure);
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

        int y = gs.y == GeneratedStructure.UNKNOWN_Y ? 100 : gs.y + 1;
        mc.player.connection.sendCommand("tp " + gs.x + " " + y + " " + gs.z);
        ChatUtils.info("Seed Explorer: Sent teleport to (highlight)" + gs.x + ", " + y + ", " + gs.z + "(default).");
    }

    private void openStructureViewer(GeneratedStructure gs) {
        if (!ChestLootPredictor.canPredict(gs.type)) {
            openStructureViewer(gs, List.of());
            return;
        }

        WorkerManager.get().submit(() -> {
            List<ChestLootOutput> results = new ArrayList<>(ChestLootPredictor.predictForStructure(gs));
            Minecraft.getInstance().execute(() -> openStructureViewer(gs, results));
        });
    }

    private void openStructureViewer(GeneratedStructure gs, List<ChestLootOutput> results) {
        long worldSeed = SeedManager.get().getWorldSeed();
        Minecraft.getInstance().gui.setScreen(new Structure3DViewerScreen(
            worldSeed, gs, results));
    }

    private void showLootForStructure(GeneratedStructure gs) {
        if (!ChestLootPredictor.canPredict(gs.type)) {
            openStructureViewer(gs, List.of());
            return;
        }

        ChatUtils.info("Seed Explorer: Predicting loot for (highlight)" + gs.displayName() + "(default)...");
        WorkerManager.get().submit(() -> {
            List<ChestLootOutput> results = new ArrayList<>(ChestLootPredictor.predictForStructure(gs));
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
            List<ChestLootOutput> results = new ArrayList<>(ChestLootPredictor.predictForStructure(structure));
            Minecraft.getInstance().execute(() -> {
                if (request != chestOverlayRequestId) return;
                chestOverlayLoading = false;
                chestOverlayChests = chestContainers(results);
                if (!chestOverlayChests.isEmpty()) setSelected3dChest(0);
            });
        });
    }

    private void clear3dChestOverlay() {
        chestOverlayRequestId++;
        chestOverlayStructure = null;
        chestOverlayChests = searchChestMarkers.isEmpty() ? List.of() : searchChestMarkers;
        selected3dChest = null;
        selected3dChestIndex = -1;
        chestOverlayLoading = false;
    }

    private void drawSelected3dChestPanel(GuiGraphicsExtractor graphics, double screenWidth, double screenHeight) {
        if (chestOverlayStructure == null) return;

        ChestPanelLayout panel = chestPanelLayout(screenWidth, screenHeight);
        int panelW = panel.width();
        int panelX = panel.x();
        int panelY = panel.y();
        int panelH = panel.height();

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(panelX + 2, panelY + 3, panelW, panelH, new Color(0, 0, 0, 105));
        r.quad(panelX, panelY, panelW, panelH, new Color(34, 34, 42, 238));
        r.quad(panelX, panelY, 4, panelH, selected3dChest != null && selected3dChest.exact()
            ? new Color(86, 215, 156, 245) : new Color(218, 172, 82, 235));
        r.boxLines(panelX, panelY, panelW, panelH, new Color(116, 122, 130, 230));
        r.render();

        String title = chestOverlayStructure.displayName() + " chests";
        theme.textRenderer().begin(frameGraphics, 1.0);
        theme.textRenderer().render(trimToWidth(title, panelW - 28), panelX + 12, panelY + 9, UI_TEXT);
        theme.textRenderer().render("x", panelX + panelW - 16, panelY + 9, UI_MUTED);
        theme.textRenderer().end();

        if (chestOverlayLoading) {
            theme.textRenderer().begin(frameGraphics, 1.0);
            theme.textRenderer().render(trimToWidth("Predicting chest positions...", panelW - 24),
                panelX + 12, panelY + 34, UI_MUTED);
            theme.textRenderer().end();
            return;
        }

        if (chestOverlayChests.isEmpty()) {
            theme.textRenderer().begin(frameGraphics, 1.0);
            theme.textRenderer().render(trimToWidth("No predicted chests for this structure.", panelW - 24),
                panelX + 12, panelY + 34, UI_MUTED);
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
        chestPanelCopyX = panelX + panelW - 116;
        chestPanelCopyY = chestPanelPrevY;
        chestPanelCopyW = 42;
        chestPanelCopyH = 16;
        chestPanelExportX = panelX + panelW - 66;
        chestPanelExportY = chestPanelPrevY;
        chestPanelExportW = 54;
        chestPanelExportH = 16;
        drawSmallButton(chestPanelPrevX, chestPanelPrevY, chestPanelPrevW, chestPanelPrevH, selected3dChestIndex > 0, "<");
        drawSmallButton(chestPanelNextX, chestPanelNextY, chestPanelNextW, chestPanelNextH, selected3dChestIndex + 1 < chestOverlayChests.size(), ">");
        drawSmallButton(chestPanelCopyX, chestPanelCopyY, chestPanelCopyW, chestPanelCopyH, true, "Copy");
        drawSmallButton(chestPanelExportX, chestPanelExportY, chestPanelExportW, chestPanelExportH, true, "Export");

        if (selected3dChest == null) {
            theme.textRenderer().begin(frameGraphics, 1.0);
            theme.textRenderer().render("Click a highlighted chest marker.", panelX + 12, panelY + 34, UI_MUTED);
            theme.textRenderer().end();
            return;
        }

        String indexText = (selected3dChestIndex + 1) + " / " + chestOverlayChests.size();
        theme.textRenderer().begin(frameGraphics, 1.0);
        theme.textRenderer().render(indexText, chestPanelNextX + chestPanelNextW + 10, panelY + 36, UI_MUTED);
        theme.textRenderer().end();

        drawChestGrid(graphics, panelX + 12, panelY + 58, selected3dChest);
        drawChestItemList(panelX + 12, panelY + 126, panelW - 24, panelY + panelH - 10, selected3dChest);
    }

    private boolean handleChestPanelClick(double mouseX, double mouseY) {
        if (chestOverlayStructure == null) return false;
        Minecraft mc = Minecraft.getInstance();
        double screenWidth = mc.getWindow().getWidth();
        double screenHeight = mc.getWindow().getHeight();
        ChestPanelLayout panel = chestPanelLayout(screenWidth, screenHeight);
        int panelW = panel.width();
        int panelX = panel.x();
        int panelY = panel.y();
        int panelH = panel.height();

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

    private ChestPanelLayout chestPanelLayout(double screenWidth, double screenHeight) {
        int panelW = Math.max(200, Math.min(CHEST_PANEL_WIDTH, (int) screenWidth - 16));
        int panelH = selected3dChest == null ? 96
            : Math.max(180, Math.min(CHEST_PANEL_HEIGHT, (int) screenHeight - 16));
        int panelX = Math.max(8, (int) screenWidth - panelW - 12);
        int desiredY = Math.max(floatingPanelY(screenWidth), 92);
        int panelY = Math.max(8, Math.min(desiredY, (int) screenHeight - panelH - 8));
        return new ChestPanelLayout(panelX, panelY, panelW, panelH);
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
        theme.textRenderer().begin(frameGraphics, 1.0);
        theme.textRenderer().render(text, x + Math.max(1, (w - theme.textWidth(text)) / 2), y + 4,
            enabled ? UI_TEXT : UI_MUTED);
        theme.textRenderer().end();
    }

    private void drawChestGrid(GuiGraphicsExtractor graphics, int x, int y, ChestLootOutput chest) {
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
        // The slot chrome is emitted through Meteor's 2D renderer. Queue real
        // Minecraft item models in the next vanilla GUI stratum so predicted loot
        // appears above the custom slot background.
        graphics.nextStratum();
        int i = 0;
        for (ItemLoot item : chest.predictedItems()) {
            if (i >= cols * rows) break;
            int col = i % cols;
            int row = i / cols;
            int sx = x + col * slot;
            int sy = y + row * slot;
            if (item.getItem() != net.minecraft.world.item.Items.AIR) {
                ItemStack stack = new ItemStack(item.getItem(), Math.max(1, item.maxCount()));
                graphics.item(stack, sx, sy);
                graphics.itemDecorations(Minecraft.getInstance().font, stack, sx, sy);
            }
            i++;
        }
    }

    private void drawChestItemList(int x, int y, int width, int maxY, ChestLootOutput chest) {
        String subtitle = chest.blockX() + ", " + chest.blockY() + ", " + chest.blockZ()
            + "  " + (chest.exact() ? "exact" : "approx");
        theme.textRenderer().begin(frameGraphics, 1.0);
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

    private void copyStructureCoords(GeneratedStructure structure) {
        Minecraft.getInstance().keyboardHandler.setClipboard(structure.getCoordsString());
    }

    private void createWaypoint(GeneratedStructure gs) {
        String name = gs.displayName() + " [" + gs.getCoordsString() + "]";
        String icon = switch (gs.type) {
            case VILLAGE -> "square";
            case DESERT_PYRAMID, JUNGLE_TEMPLE, WITCH_HUT, IGLOO -> "triangle";
            case OUTPOST -> "skull";
            case MONUMENT, MANSION -> "diamond";
            case ANCIENT_CITY -> "star";
            case TRIAL_CHAMBER -> "diamond";
            case TRAIL_RUINS, ABANDONED_CAMP, RUINED_PORTAL, OCEAN_RUIN, MINESHAFT -> "circle";
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
        if (mode3d) {
            double focusY = selectedDimension == 1 ? 80 : 64;
            camera3d.viewOf(x, focusY, z, 42, 55, Math.toDegrees(camera3d.yaw()));
            last3dMovementNanos = System.nanoTime();
        }
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

        Set<LootSearchTerm> parsedLootFilters = parseLootItemFilters(query);
        if (!parsedLootFilters.equals(lootItemFilters)) {
            lootSearchRequestId++;
            lootSearchRunning = false;
            lootItemFilters.clear();
            lootItemFilters.addAll(parsedLootFilters);
            lootItemQuantities.clear();
            parsedLootFilters.forEach(term -> lootItemQuantities.put(term, 1));
            lootFilterCache.clear();
            lootFilterInFlight.clear();
            searchChestMarkers = List.of();
            matchedSearchChest = null;
            searchChestMatches = Map.of();
            searchChestDimension = Integer.MIN_VALUE;
            clear3dChestOverlay();
            lastLootFilterSignature = lootFilterSignature(lootItemFilters);
        }

        if (!lootItemFilters.isEmpty()) {
            searchStructureFilter = null;
            searchSuggestion = "Find loot: " + lootFilterDisplay(lootItemFilters);
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
            startLootSearch();
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

    private Set<LootSearchTerm> parseLootItemFilters(String query) {
        if (query == null) return Set.of();
        String q = query.trim();
        String lower = q.toLowerCase(Locale.ROOT);
        boolean explicit = lower.startsWith("loot:") || lower.startsWith("item:");
        if (!explicit) return Set.of();

        String body = explicit ? q.substring(q.indexOf(':') + 1).trim() : q;
        if (body.isEmpty()) return Set.of();

        Set<LootSearchTerm> out = new LinkedHashSet<>();
        for (String raw : body.split("[,;+]")) {
            LootSearchTerm term = LootSearchParser.parseTerm(raw);
            if (term != null) out.add(term);
        }
        return out;
    }

    private boolean isBiomeSearchText(String query) {
        String lower = query.toLowerCase(Locale.ROOT).trim();
        return BIOME_NAME_TO_ID.keySet().stream().anyMatch(name -> name.equals(lower) || name.startsWith(lower));
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

        Set<LootSearchTerm> filters = Set.copyOf(lootItemFilters);
        String signature = lootFilterSignature(filters);
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
            long requestGeneration = lootFilterGeneration;

            if (!WorkerManager.get().submit(() -> {
                boolean matched = false;
                try {
                    List<ChestLootOutput> chests = ChestLootPredictor.predictForStructure(structure);
                    matched = structureContainsAllItems(chests, filters);
                } catch (Throwable ignored) {
                }
                boolean finalMatched = matched;
                Minecraft.getInstance().execute(() -> {
                    if (requestGeneration != lootFilterGeneration) return;
                    lootFilterInFlight.remove(key);
                    lootFilterCache.put(key, finalMatched);
                });
            })) {
                lootFilterInFlight.remove(key);
            }

            if (scheduled >= 6) break;
        }
    }

    private long lootCacheKey(int dimension, GeneratedStructure structure) {
        return SeedRenderer.structureKey(dimension, structure);
    }

    private boolean structureContainsAllItems(List<ChestLootOutput> chests, Set<LootSearchTerm> filters) {
        if (chests == null || chests.isEmpty()) return false;
        Map<LootSearchTerm, Integer> totals = new HashMap<>();
        for (ChestLootOutput chest : chests) {
            if (chest.predictedItems() == null) continue;
            for (var item : chest.predictedItems()) {
                for (LootSearchTerm filter : filters) {
                    if (filter.matches(item)) totals.merge(filter, item.maxCount(), Integer::sum);
                }
            }
        }
        return filters.stream().allMatch(filter -> totals.getOrDefault(filter, 0)
            >= lootItemQuantities.getOrDefault(filter, 1));
    }

    private String lootFilterDisplay(Set<LootSearchTerm> filters) {
        return filters.stream().map(term -> term.display()
                + (lootItemQuantities.getOrDefault(term, 1) > 1
                    ? " x" + lootItemQuantities.get(term) : ""))
            .collect(java.util.stream.Collectors.joining(" + "));
    }

    private String lootFilterSignature(Set<LootSearchTerm> filters) {
        return filters.stream().map(term -> term.signature() + "x" + lootItemQuantities.getOrDefault(term, 1)).sorted()
            .collect(java.util.stream.Collectors.joining(","));
    }

    private void openItemSearch() {
        List<ItemLootRequest> current = lootItemFilters.stream()
            .map(term -> new ItemLootRequest(term, lootItemQuantities.getOrDefault(term, 1)))
            .toList();
        Minecraft.getInstance().gui.setScreen(new ItemSearchScreen(theme, current, this::startItemLootSearch));
    }

    private void startItemLootSearch(List<ItemLootRequest> requests) {
        lootSearchRequestId++;
        lootItemFilters.clear();
        lootItemQuantities.clear();
        if (requests != null) {
            for (ItemLootRequest request : requests) {
                lootItemFilters.add(request.term());
                lootItemQuantities.merge(request.term(), request.quantity(), Integer::sum);
            }
        }
        lootFilterCache.clear();
        lootFilterInFlight.clear();
        searchChestMarkers = List.of();
        matchedSearchChest = null;
        searchChestMatches = Map.of();
        searchChestDimension = Integer.MIN_VALUE;
        clear3dChestOverlay();
        lastLootFilterSignature = lootFilterSignature(lootItemFilters);
        searchStructureFilter = null;
        searchSuggestion = lootItemFilters.isEmpty() ? "" : "Find loot: " + lootFilterDisplay(lootItemFilters);
        startLootSearch();
    }

    private void startLootSearch() {
        long seed = SeedManager.get().getWorldSeed();
        if (seed == 0 || lootItemFilters.isEmpty()) {
            ChatUtils.info("Seed Explorer: Set a seed before searching loot.");
            return;
        }

        long request = ++lootSearchRequestId;
        Set<LootSearchTerm> filters = Set.copyOf(lootItemFilters);
        int originDimension = selectedDimension;
        int originX = (int) Math.round(offsetX);
        int originZ = (int) Math.round(offsetZ);
        Set<Integer> dimensions = ChestLootPredictor.candidateDimensions(filters);
        String scope = dimensionScope(dimensions);
        if (dimensions.isEmpty()) {
            lootSearchRunning = false;
            searchSuggestion = "No supported loot source for " + lootFilterDisplay(filters);
            ChatUtils.info("Seed Explorer: No supported predicted chest can produce (highlight)"
                + lootFilterDisplay(filters) + "(default).");
            return;
        }
        lootSearchRunning = true;
        lootSearchChecked = 0;
        lootSearchTotal = 0;
        searchSuggestion = "Searching " + scope + " chests for " + lootFilterDisplay(filters) + "...";
        ChatUtils.info("Seed Explorer: Searching " + scope + " predicted chests for (highlight)"
            + lootFilterDisplay(filters) + "(default)...");

        LOOT_SEARCH_WORKER.execute(() -> runLootSearch(request, seed, filters,
            originDimension, originX, originZ));
    }

    private void runLootSearch(long request, long seed, Set<LootSearchTerm> filters,
                               int originDimension, int originX, int originZ) {
        try {
            Set<StructureType> sourceTypes = ChestLootPredictor.candidateStructureTypes(filters);
            boolean searchDungeons = sourceTypes.contains(StructureType.DUNGEON);
            List<StructurePredictionStore.StructurePrediction> savedPredictions =
                StructurePredictionStore.get().findMatches(
                    Terrain3DRenderer.worldCacheIdentity(seed), seed,
                    ChestLootPredictor.candidateDimensions(filters), filters);
            List<LootSearchCandidate> candidates = buildLootSearchCandidates(seed, originDimension,
                originX, originZ, sourceTypes);
            int dungeonChunkTotal = searchDungeons
                ? (ITEM_DUNGEON_SEARCH_RADIUS_CHUNKS * 2 + 1)
                    * (ITEM_DUNGEON_SEARCH_RADIUS_CHUNKS * 2 + 1) : 0;
            int total = savedPredictions.size() + candidates.size() + dungeonChunkTotal;
            Minecraft.getInstance().execute(() -> {
                if (request == lootSearchRequestId) lootSearchTotal = total;
            });

            int checked = 0;
            LootSearchResult fallback = null;
            for (StructurePredictionStore.StructurePrediction saved : savedPredictions) {
                GeneratedStructure structure = saved.structure();
                int dimension = structure.type.dimension;
                int dimensionOriginX = coordinateForDimension(originX, originDimension, dimension);
                int dimensionOriginZ = coordinateForDimension(originZ, originDimension, dimension);
                LootSearchCandidate candidate = new LootSearchCandidate(dimension, structure,
                    distanceSquared(structure.x, structure.z, dimensionOriginX, dimensionOriginZ));
                LootSearchResult result = matchingLoot(candidate, saved.chests(), filters);
                checked++;
                fallback = betterLootResult(fallback, result);
                if (result != null && result.combinedMatch() && result.chest().exact()) break;
            }
            for (LootSearchCandidate candidate : candidates) {
                if (request != lootSearchRequestId || SeedManager.get().getWorldSeed() != seed) return;
                List<ChestLootOutput> chests;
                try {
                    chests = chestContainers(ChestLootPredictor.predictForStructure(candidate.structure()));
                } catch (Throwable ignored) {
                    chests = List.of();
                }
                checked++;
                LootSearchResult result = matchingLoot(candidate, chests, filters);
                int progress = checked;
                if (result != null) {
                    if (result.combinedMatch() && result.chest().exact()) {
                        fallback = result;
                        break;
                    }
                    fallback = betterLootResult(fallback, result);
                }
                if ((checked & 7) == 0 || checked == candidates.size()) {
                    Minecraft.getInstance().execute(() -> {
                        if (request != lootSearchRequestId) return;
                        lootSearchChecked = progress;
                        searchSuggestion = "Searching " + lootFilterDisplay(filters) + "  "
                            + progress + "/" + total;
                    });
                }
            }

            if (searchDungeons && request == lootSearchRequestId && SeedManager.get().getWorldSeed() == seed) {
                int dungeonOriginX = coordinateForDimension(originX, originDimension, 0);
                int dungeonOriginZ = coordinateForDimension(originZ, originDimension, 0);
                int fixedChecked = checked;
                LootSearchResult[] dungeonResult = {null};
                DungeonPredictor.ScanResult scan = DungeonPredictor.scanAround(seed,
                    Math.floorDiv(dungeonOriginX, 16), Math.floorDiv(dungeonOriginZ, 16),
                    ITEM_DUNGEON_SEARCH_RADIUS_CHUNKS, 0, dungeon -> {
                        GeneratedStructure structure = dungeon.structure();
                        LootSearchCandidate candidate = new LootSearchCandidate(0, structure,
                            distanceSquared(structure.x, structure.z, dungeonOriginX, dungeonOriginZ));
                        LootSearchResult match = matchingLoot(candidate,
                            ChestLootPredictor.predictDungeonContainers(dungeon.simulated(), 64), filters);
                        if (match == null) return false;
                        dungeonResult[0] = betterLootResult(dungeonResult[0], match);
                        return match.combinedMatch() && match.chest().exact();
                    }, () -> request != lootSearchRequestId || SeedManager.get().getWorldSeed() != seed,
                    dungeonChecked -> {
                        if ((dungeonChecked & 7) != 0) return;
                        int progress = fixedChecked + dungeonChecked;
                        Minecraft.getInstance().execute(() -> {
                            if (request != lootSearchRequestId) return;
                            lootSearchChecked = progress;
                            searchSuggestion = "Searching dungeons for " + lootFilterDisplay(filters);
                        });
                    });
                if (scan.cancelled()) return;
                checked = fixedChecked + scan.checkedChunks();
                fallback = betterLootResult(fallback, dungeonResult[0]);
            }

            LootSearchResult finalFallback = fallback;
            int finalChecked = checked;
            Minecraft.getInstance().execute(() -> finishLootSearch(request, filters, finalFallback,
                finalChecked, total));
        } catch (Throwable throwable) {
            Minecraft.getInstance().execute(() -> {
                if (request != lootSearchRequestId) return;
                lootSearchRunning = false;
                searchSuggestion = "Loot search failed";
                ChatUtils.warning("Seed Explorer: Loot search failed: " + throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage()));
            });
        }
    }

    private List<LootSearchCandidate> buildLootSearchCandidates(long seed, int originDimension,
                                                                 int originX, int originZ,
                                                                 Set<StructureType> sourceTypes) {
        int[] dimensions = originDimension == 0 ? new int[]{0, -1, 1}
            : originDimension == -1 ? new int[]{-1, 0, 1} : new int[]{1, 0, -1};
        Map<String, LootSearchCandidate> unique = new HashMap<>();
        for (int dimension : dimensions) {
            if (sourceTypes.stream().noneMatch(type -> type.dimension == dimension)) continue;
            int radius = dimension == originDimension ? 320 : 240;
            int dimensionOriginX = coordinateForDimension(originX, originDimension, dimension);
            int dimensionOriginZ = coordinateForDimension(originZ, originDimension, dimension);
            int centerChunkX = Math.floorDiv(dimensionOriginX, 16);
            int centerChunkZ = Math.floorDiv(dimensionOriginZ, 16);
            List<GeneratedStructure> predicted = VanillaStructurePredictor.predictDimension(seed, dimension,
                centerChunkX - radius, centerChunkZ - radius, centerChunkX + radius, centerChunkZ + radius,
                true, false);
            for (GeneratedStructure structure : predicted) {
                if (!sourceTypes.contains(structure.type) || structure.type == StructureType.DUNGEON) continue;
                String key = dimension + ":" + structure.type + ":" + structure.startChunkX + ":" + structure.startChunkZ;
                unique.putIfAbsent(key, new LootSearchCandidate(dimension, structure,
                    distanceSquared(structure.x, structure.z, dimensionOriginX, dimensionOriginZ)));
            }
        }
        List<LootSearchCandidate> result = new ArrayList<>(unique.values());
        result.sort(java.util.Comparator
            .comparingInt((LootSearchCandidate candidate) -> candidate.dimension() == originDimension ? 0 : 1)
            .thenComparingDouble(LootSearchCandidate::originDistanceSquared)
            .thenComparingInt(candidate ->
                ChestLootPredictor.isStrictlyValidated(candidate.structure().type) ? 0 : 1));
        return List.copyOf(result);
    }

    private int coordinateForDimension(int coordinate, int fromDimension, int toDimension) {
        if (fromDimension == toDimension) return coordinate;
        if (fromDimension == 0 && toDimension == -1) return Math.floorDiv(coordinate, 8);
        if (fromDimension == -1 && toDimension == 0) return coordinate * 8;
        return coordinate;
    }

    private String dimensionScope(Set<Integer> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) return "no supported dimensions";
        List<String> names = new ArrayList<>();
        for (int dimension : new int[]{0, -1, 1}) {
            if (dimensions.contains(dimension)) names.add(dimensionName(dimension));
        }
        return String.join(" + ", names);
    }

    private LootSearchResult betterLootResult(LootSearchResult current, LootSearchResult candidate) {
        if (current == null) return candidate;
        int currentScore = (current.chest().exact() ? 2 : 0) + (current.combinedMatch() ? 1 : 0);
        int candidateScore = (candidate.chest().exact() ? 2 : 0) + (candidate.combinedMatch() ? 1 : 0);
        if (candidateScore != currentScore) return candidateScore > currentScore ? candidate : current;
        return candidate.originDistanceSquared() < current.originDistanceSquared()
            ? candidate : current;
    }

    private LootSearchResult matchingLoot(LootSearchCandidate candidate, List<ChestLootOutput> chests,
                                           Set<LootSearchTerm> filters) {
        if (!structureContainsAllItems(chests, filters)) return null;
        Map<ChestLootOutput, Set<LootSearchTerm>> matches = new java.util.LinkedHashMap<>();
        ChestLootOutput combined = null;
        int bestMatchedTypes = -1;
        ChestLootOutput best = null;
        for (ChestLootOutput chest : chests) {
            if (chest.predictedItems() == null) continue;
            Map<LootSearchTerm, Integer> counts = new HashMap<>();
            for (ItemLoot item : chest.predictedItems()) {
                for (LootSearchTerm filter : filters) {
                    if (filter.matches(item)) counts.merge(filter, item.maxCount(), Integer::sum);
                }
            }
            Set<LootSearchTerm> chestMatches = filters.stream()
                .filter(filter -> counts.getOrDefault(filter, 0) > 0)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (chestMatches.isEmpty()) continue;
            matches.put(chest, Set.copyOf(chestMatches));
            boolean containsAll = filters.stream().allMatch(filter -> counts.getOrDefault(filter, 0)
                >= lootItemQuantities.getOrDefault(filter, 1));
            if (combined == null && containsAll) combined = chest;
            if (chestMatches.size() > bestMatchedTypes) {
                bestMatchedTypes = chestMatches.size();
                best = chest;
            }
        }
        ChestLootOutput primary = combined != null ? combined : best;
        if (primary == null) return null;
        return new LootSearchResult(candidate.dimension(), candidate.structure(), primary,
            List.copyOf(chests), Map.copyOf(matches), combined != null,
            candidate.originDistanceSquared());
    }

    private void finishLootSearch(long request, Set<LootSearchTerm> filters, LootSearchResult result,
                                  int checked, int total) {
        if (request != lootSearchRequestId) return;
        lootSearchRunning = false;
        lootSearchChecked = checked;
        lootSearchTotal = total;
        String wanted = lootFilterDisplay(filters);
        if (result == null) {
            searchSuggestion = "No predicted " + wanted + " found (" + checked + " chests checked)";
            ChatUtils.info("Seed Explorer: No predicted (highlight)" + wanted
                + "(default) found after checking " + checked
                + " supported structure candidates or dungeon chunks.");
            return;
        }

        lootFilterCache.put(lootCacheKey(result.dimension(), result.structure()), true);
        String location = result.structure().displayName() + " at " + result.structure().getCoordsString()
            + " (" + dimensionName(result.dimension()) + ")";
        searchSuggestion = "Found " + wanted + " in " + location
            + (result.combinedMatch() ? " (one chest)" : " (split across chests)");
        if (Minecraft.getInstance().gui.screen() == this) {
            selectedDimension = result.dimension();
            dimensionPinned = true;
            centerMap(result.chest().blockX(), result.chest().blockZ());
            SeedExplorerModule module = Modules.get().get(SeedExplorerModule.class);
            if (module != null) {
                module.setLayerEnabled(MapLayer.TERRAIN, true);
                module.setLayerEnabled(MapLayer.BIOMES, true);
            }
            MinimapManager.get().prewarm(result.chest().blockX(), result.chest().blockZ(), result.dimension(), 5);
            selectedStructure = result.structure();
            searchChestMarkers = result.chests();
            matchedSearchChest = result.chest();
            searchChestMatches = result.matches();
            searchChestDimension = result.dimension();
            chestOverlayRequestId++;
            chestOverlayStructure = result.structure();
            chestOverlayChests = result.chests();
            selected3dChestIndex = Math.max(0, chestOverlayChests.indexOf(result.chest()));
            selected3dChest = result.chest();
            chestOverlayLoading = false;
            if (mode3d) {
                focusController.cancelFocus();
                camera3d.viewOf(result.chest().blockX() + 0.5, result.chest().blockY() + 0.5,
                    result.chest().blockZ() + 0.5, 42, 55, Math.toDegrees(camera3d.yaw()));
            } else {
                double maxZoom = module == null ? 100.0 : module.maxZoom.get();
                targetZoom = Math.min(maxZoom, Math.max(targetZoom, 6.0));
            }
            contextMenuOpen = false;
        } else {
            ChatUtils.info("Seed Explorer: Found (highlight)" + wanted + "(default) in "
                + location + "; chest (highlight)" + result.chest().blockX() + ", "
                + result.chest().blockY() + ", " + result.chest().blockZ() + "(default).");
        }
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
            + "(default). Versioned structures, biome search, and loot are gated; terrain rendering still uses the active Minecraft runtime.");
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
        DungeonPredictor.clear();
        visibleStructures = List.of();
        visibleWaypoints = List.of();
        hoveredStructure = null;
        hoveredWaypoint = null;
        selectedStructure = null;
        selectedWaypoint = null;
        closeContextMenu();
        lootFilterCache.clear();
        lootFilterInFlight.clear();
        lastLootFilterSeed = Long.MIN_VALUE;
        lootFilterGeneration++;
        lootSearchRequestId++;
        lootSearchRunning = false;
        searchChestMarkers = List.of();
        matchedSearchChest = null;
        searchChestMatches = Map.of();
        searchChestDimension = Integer.MIN_VALUE;
        clear3dChestOverlay();
        terrain3DRenderer.reset();
    }

    @Override
    protected void onClosed() {
        // A screen close does not receive another GUI frame, so complete the
        // deferred Stop transition here instead of leaving bulk mode active.
        if (mapLoading) finishMapLoadingStop();
        cancelCurrentSearch(false);
        super.onClosed();
    }

    private static List<ChestLootOutput> chestContainers(List<ChestLootOutput> results) {
        if (results == null || results.isEmpty()) return List.of();
        return results.stream()
            .filter(chest -> chest.lootTableId() == null
                || !chest.lootTableId().startsWith("minecraft:dispensers/"))
            .toList();
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
            case ABANDONED_CAMP -> {
                aliases.add("camp");
                aliases.add("abandoned campsite");
            }
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
        if (matchedType == StructureType.DUNGEON) {
            startDungeonStructureSearch(seed);
            return true;
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

    private void startDungeonStructureSearch(long seed) {
        long request = ++lootSearchRequestId;
        int originDimension = selectedDimension;
        int originX = coordinateForDimension((int) Math.round(offsetX), originDimension, 0);
        int originZ = coordinateForDimension((int) Math.round(offsetZ), originDimension, 0);
        int centerChunkX = Math.floorDiv(originX, 16);
        int centerChunkZ = Math.floorDiv(originZ, 16);
        int total = (DIRECT_DUNGEON_SEARCH_RADIUS_CHUNKS * 2 + 1)
            * (DIRECT_DUNGEON_SEARCH_RADIUS_CHUNKS * 2 + 1);
        lootSearchRunning = true;
        lootSearchChecked = 0;
        lootSearchTotal = total;
        searchStructureFilter = StructureType.DUNGEON;
        searchSuggestion = "Searching for nearest dungeon";
        ChatUtils.info("Seed Explorer: Searching for the nearest predicted dungeon in the Overworld...");

        LOOT_SEARCH_WORKER.execute(() -> {
            try {
                DungeonPredictor.ScanResult scan = DungeonPredictor.scanAround(seed, centerChunkX, centerChunkZ,
                    DIRECT_DUNGEON_SEARCH_RADIUS_CHUNKS, 1, null,
                    () -> request != lootSearchRequestId || SeedManager.get().getWorldSeed() != seed,
                    checked -> {
                        if ((checked & 3) != 0) return;
                        Minecraft.getInstance().execute(() -> {
                            if (request != lootSearchRequestId) return;
                            lootSearchChecked = checked;
                            searchSuggestion = "Searching for nearest dungeon  " + checked + "/" + total;
                        });
                    });
                if (scan.cancelled()) return;
                Minecraft.getInstance().execute(() -> finishDungeonStructureSearch(request, seed, scan));
            } catch (Throwable throwable) {
                Minecraft.getInstance().execute(() -> {
                    if (request != lootSearchRequestId) return;
                    lootSearchRunning = false;
                    searchSuggestion = "Dungeon search failed";
                    ChatUtils.warning("Seed Explorer: Dungeon search failed: "
                        + throwable.getClass().getSimpleName());
                });
            }
        });
    }

    private void finishDungeonStructureSearch(long request, long seed, DungeonPredictor.ScanResult scan) {
        if (request != lootSearchRequestId || SeedManager.get().getWorldSeed() != seed) return;
        lootSearchRunning = false;
        lootSearchChecked = scan.checkedChunks();
        if (scan.dungeons().isEmpty()) {
            searchSuggestion = "No dungeon found in " + scan.checkedChunks() + " chunks";
            ChatUtils.info("Seed Explorer: No dungeon was found within "
                + DIRECT_DUNGEON_SEARCH_RADIUS_CHUNKS + " chunks of the search center.");
            return;
        }

        DungeonPredictor.PredictedDungeon dungeon = scan.dungeons().get(0);
        GeneratedStructure structure = dungeon.structure();
        List<ChestLootOutput> chests = ChestLootPredictor.predictDungeonContainers(dungeon.simulated(), 64);
        selectedDimension = 0;
        dimensionPinned = true;
        centerMap(structure.x, structure.z);
        selectedStructure = structure;
        searchSuggestion = "Nearest " + structure.displayName() + " at " + structure.getCoordsString();
        searchChestMarkers = chests;
        matchedSearchChest = null;
        searchChestMatches = Map.of();
        searchChestDimension = 0;
        chestOverlayRequestId++;
        chestOverlayStructure = structure;
        chestOverlayChests = chests;
        selected3dChestIndex = chests.isEmpty() ? -1 : 0;
        selected3dChest = chests.isEmpty() ? null : chests.get(0);
        chestOverlayLoading = false;
        MinimapManager.get().prewarm(structure.x, structure.z, 0, 5);

        if (mode3d) {
            focusController.cancelFocus();
            camera3d.viewOf(structure.x + 0.5, structure.y + 0.5, structure.z + 0.5,
                42, 55, Math.toDegrees(camera3d.yaw()));
        } else {
            SeedExplorerModule module = Modules.get().get(SeedExplorerModule.class);
            double maxZoom = module == null ? 100.0 : module.maxZoom.get();
            targetZoom = Math.min(maxZoom, Math.max(targetZoom, 6.0));
        }

        ChatUtils.info("Seed Explorer: Found (highlight)" + structure.displayName()
            + "(default) at (highlight)" + structure.getCoordsString() + "(default) with "
            + chests.size() + " chest" + (chests.size() == 1 ? "" : "s") + ".");
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
        String versionedBiome = biomeId == 55 ? "minecraft:dappled_forest"
            : biomeId == 56 ? "minecraft:sulfur_caves" : null;
        if (versionedBiome != null
            && !PredictionVersionPolicy.supportsBiome(selectedVersion(), versionedBiome)) {
            ChatUtils.info("Seed Explorer: (highlight)" + query
                + "(default) is not available in prediction profile (highlight)"
                + selectedVersion() + "(default).");
            return true;
        }

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
                    if (matchesBiome(centerBlockX, centerBlockZ, seed, biomeId)) {
                        centerMap(centerBlockX, centerBlockZ);
                        ChatUtils.info("Seed Explorer: Found biome at (highlight)" + centerBlockX + ", " + centerBlockZ + "(default).");
                        return true;
                    }
                    continue;
                }

                // Top edge: z = -radius
                if (matchesBiome(centerBlockX + bx, centerBlockZ - radius, seed, biomeId)) {
                    centerMap(centerBlockX + bx, centerBlockZ - radius);
                    ChatUtils.info("Seed Explorer: Found biome at (highlight)" + (centerBlockX + bx) + ", " + (centerBlockZ - radius) + "(default).");
                    return true;
                }

                // Bottom edge: z = radius
                if (matchesBiome(centerBlockX + bx, centerBlockZ + radius, seed, biomeId)) {
                    centerMap(centerBlockX + bx, centerBlockZ + radius);
                    ChatUtils.info("Seed Explorer: Found biome at (highlight)" + (centerBlockX + bx) + ", " + (centerBlockZ + radius) + "(default).");
                    return true;
                }
            }

            // Left and right edges (excluding corners already checked)
            for (int bz = -radius + step; bz <= radius - step; bz += step) {
                // Left edge: x = -radius
                if (matchesBiome(centerBlockX - radius, centerBlockZ + bz, seed, biomeId)) {
                    centerMap(centerBlockX - radius, centerBlockZ + bz);
                    ChatUtils.info("Seed Explorer: Found biome at (highlight)" + (centerBlockX - radius) + ", " + (centerBlockZ + bz) + "(default).");
                    return true;
                }

                // Right edge: x = radius
                if (matchesBiome(centerBlockX + radius, centerBlockZ + bz, seed, biomeId)) {
                    centerMap(centerBlockX + radius, centerBlockZ + bz);
                    ChatUtils.info("Seed Explorer: Found biome at (highlight)" + (centerBlockX + radius) + ", " + (centerBlockZ + bz) + "(default).");
                    return true;
                }
            }
        }

        ChatUtils.info("Seed Explorer: No (highlight)" + query + "(default) biome found within " + maxRadius + " blocks.");
        return false;
    }

    private static boolean matchesBiome(int blockX, int blockZ, long seed, int biomeId) {
        if (biomeId != 56) return BiomeGenerator.getBiome(blockX, blockZ, seed) == biomeId;
        // Cave biomes vary vertically. Sample the underground quart bands rather
        // than the surface-only Y=64 lookup used by the 2D biome layer.
        for (int y = 48; y >= -48; y -= 16) {
            if (BiomeGenerator.getPredictedBiome(blockX, y, blockZ, seed, 0).legacyId() == biomeId) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubled) {
        double mouseX = toRenderX(click.x());
        double mouseY = toRenderY(click.y());

        if (mapLoading) {
            if (click.button() == 0 && mouseX >= mapLoadingStopX
                && mouseX <= mapLoadingStopX + mapLoadingStopW
                && mouseY >= mapLoadingStopY
                && mouseY <= mapLoadingStopY + mapLoadingStopH) {
                stopMapLoading();
            }
            return true;
        }

        if (contextMenuOpen) {
            handleContextMenuClick(mouseX, mouseY);
            return true;
        }

        if (click.button() == 0 && handleSearchCancelClick(mouseX, mouseY)) {
            return true;
        }

        if (click.button() == 0 && handleSearchSuggestionClick(mouseX, mouseY)) {
            return true;
        }

        if (mode3d && click.button() == 0) {
            ChestLootOutput focusedChest = focusController.chestAt(mouseX, mouseY);
            if (focusedChest != null) {
                GeneratedStructure focusedStructure = focusController.target();
                if (focusedStructure != null) chestOverlayStructure = focusedStructure;
                chestOverlayChests = focusController.chests();
                chestOverlayLoading = false;
                int chestIndex = chestOverlayChests.indexOf(focusedChest);
                if (chestIndex >= 0) setSelected3dChest(chestIndex);
                teleportToChest(focusedChest);
                return true;
            }
        }

        if (click.button() == 0 && handleDimensionSelectorClick(mouseX, mouseY)) {
            return true;
        }

        if (click.button() == 0 && handleModeSelectorClick(mouseX, mouseY)) {
            return true;
        }

        if (click.button() == 0 && handleLayerToggleClick(mouseX, mouseY)) {
            return true;
        }

        if (click.button() == 0 && handleDisplayPanelClick(mouseX, mouseY)) {
            return true;
        }

        if (click.button() == 0 && handleStructurePanelClick(mouseX, mouseY)) {
            return true;
        }

        if (click.button() == 0 && (displayPanelOpen || structurePanelOpen)) {
            displayPanelOpen = false;
            structurePanelOpen = false;
        }

        if (click.button() == 0 && handleChestPanelClick(mouseX, mouseY)) {
            return true;
        }

        if (mode3d) {
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
                        if (chestOverlayStructure == null) chestOverlayStructure = selectedStructure;
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
                    isCameraContextMenu = false;
                    contextMenuOpen = true;
                    contextMenuX = mouseX;
                    contextMenuY = mouseY;
                    return true;
                }
                if (hoveredWaypoint != null) {
                    selectedWaypoint = hoveredWaypoint;
                    selectedStructure = null;
                    isWaypointContextMenu = true;
                    isCameraContextMenu = false;
                    contextMenuOpen = true;
                    contextMenuX = mouseX;
                    contextMenuY = mouseY;
                    return true;
                }

                Minecraft mc3d = Minecraft.getInstance();
                double controlHeight = topControlHeight(mc3d.getWindow().getWidth());
                if (mouseY > controlHeight) {
                    selectedStructure = null;
                    selectedWaypoint = null;
                    isWaypointContextMenu = false;
                    isCameraContextMenu = true;
                    contextMenuOpen = true;
                    contextMenuX = mouseX;
                    contextMenuY = mouseY;
                    camera3d.stopFlight();
                    return true;
                }
            }
            return super.mouseClicked(click, doubled);
        }

        if (click.button() == 0) {
            ChestLootOutput clickedChest = pickSearchChest(mouseX, mouseY);
            if (clickedChest != null) {
                if (chestOverlayChests.indexOf(clickedChest) < 0) {
                    chestOverlayStructure = selectedStructure;
                    chestOverlayChests = searchChestMarkers;
                    chestOverlayLoading = false;
                } else if (chestOverlayStructure == null) {
                    chestOverlayStructure = selectedStructure;
                }
                int index = chestOverlayChests.indexOf(clickedChest);
                if (index >= 0) setSelected3dChest(index);
                contextMenuOpen = false;
                return true;
            }
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
        double screenWidth = Minecraft.getInstance().getWindow().getWidth();
        int dimensionX = dimensionButtonX(screenWidth);
        int width = dimensionButtonTotalWidth(screenWidth);
        if (mouseX < dimensionX || mouseX > dimensionX + width
            || mouseY < DIMENSION_BUTTON_Y || mouseY > DIMENSION_BUTTON_Y + DIMENSION_BUTTON_HEIGHT) {
            return false;
        }

        int[] dimensions = {0, -1, 1};
        int current = 0;
        for (int i = 0; i < dimensions.length; i++) {
            if (dimensions[i] == selectedDimension) {
                current = i;
                break;
            }
        }

        boolean previous = mouseX < dimensionX + 26;
        boolean next = mouseX > dimensionX + width - 26;
        int direction = previous ? -1 : 1;
        if (!previous && !next) direction = 1;
        int target = dimensions[Math.floorMod(current + direction, dimensions.length)];
        selectDimension(target);
        return true;
    }

    private void selectDimension(int dimension) {
        if (dimension != -1 && dimension != 0 && dimension != 1) return;
        if (selectedDimension == dimension) return;

        selectedDimension = dimension;
        structurePanelScroll = 0;
        Minecraft mc = Minecraft.getInstance();
        dimensionPinned = currentDimensionId(mc) != dimension;
        if (mc.player != null) {
            offsetX = mc.player.getX();
            offsetZ = mc.player.getZ();
            double focusY = dimension == 1 ? 80 : dimension == -1 ? 64 : mc.player.getY();
            camera3d.viewOf(offsetX, focusY, offsetZ, 80, 60, 0);
            last3dMovementNanos = System.nanoTime();
        }
        selectedStructure = null;
        selectedWaypoint = null;
        contextMenuOpen = false;
        structurePanelOpen = false;
        displayPanelOpen = false;
        clearPredictionCaches();
    }

    private boolean handleLayerToggleClick(double mouseX, double mouseY) {
        SeedExplorerModule module = Modules.get().get(SeedExplorerModule.class);
        if (module == null) return false;

        double screenWidth = Minecraft.getInstance().getWindow().getWidth();
        ToolbarLayout layout = toolbarLayout(screenWidth);
        for (int i = 0; i < PRIMARY_LAYERS.length; i++) {
            MapLayer layer = PRIMARY_LAYERS[i];
            int x = layout.primaryX(i);
            if (mouseX >= x && mouseX <= x + layout.primaryWidth()
                && mouseY >= LAYER_BUTTON_Y && mouseY <= LAYER_BUTTON_Y + LAYER_BUTTON_HEIGHT) {
                if (layer == MapLayer.STRUCTURES) {
                    structurePanelOpen = !structurePanelOpen;
                    displayPanelOpen = false;
                    if (structurePanelOpen && !module.isLayerEnabled(layer)) module.setLayerEnabled(layer, true);
                } else {
                    module.setLayerEnabled(layer, !module.isLayerEnabled(layer));
                }
                return true;
            }
        }

        if (mouseX >= layout.displayX() && mouseX <= layout.displayX() + layout.displayWidth()
            && mouseY >= LAYER_BUTTON_Y && mouseY <= LAYER_BUTTON_Y + LAYER_BUTTON_HEIGHT) {
            displayPanelOpen = !displayPanelOpen;
            structurePanelOpen = false;
            return true;
        }

        if (mouseX >= layout.itemSearchX() && mouseX <= layout.itemSearchX() + layout.itemSearchWidth()
            && mouseY >= LAYER_BUTTON_Y && mouseY <= LAYER_BUTTON_Y + LAYER_BUTTON_HEIGHT) {
            displayPanelOpen = false;
            structurePanelOpen = false;
            openItemSearch();
            return true;
        }

        if (layout.showUnderground()
            && mouseX >= layout.undergroundX() && mouseX <= layout.undergroundX() + layout.undergroundWidth()
            && mouseY >= LAYER_BUTTON_Y && mouseY <= LAYER_BUTTON_Y + LAYER_BUTTON_HEIGHT) {
            toggleUndergroundView();
            return true;
        }

        return false;
    }

    private boolean handleModeSelectorClick(double mouseX, double mouseY) {
        double screenWidth = Minecraft.getInstance().getWindow().getWidth();
        int modeX = modeButtonX(screenWidth);
        int modeWidth = modeButtonWidth(screenWidth);
        if (mouseX >= modeX && mouseX <= modeX + modeWidth
            && mouseY >= MODE_BUTTON_Y && mouseY <= MODE_BUTTON_Y + MODE_BUTTON_HEIGHT) {
            boolean want3d = mouseX >= modeX + modeWidth / 2.0;
            if (want3d != mode3d) toggle3dMode();
            return true;
        }
        return false;
    }

    /**
     * Minecraft 26.3 can lose the normal Screen mouse callback when another
     * Meteor listener consumes the raw event. Handle this independent selector
     * before module keybind listeners and cancel only clicks inside its bounds.
     */
    @EventHandler(priority = EventPriority.HIGHEST + 1_000)
    private void onRawModeSelectorClick(MouseClickEvent event) {
        if (event.action != KeyAction.Press || event.button() != 0
            || Minecraft.getInstance().gui.screen() != this) return;
        if (handleModeSelectorClick(toRenderX(event.click.x()), toRenderY(event.click.y()))) {
            event.cancel();
        }
    }

    private boolean handleDisplayPanelClick(double mouseX, double mouseY) {
        if (!displayPanelOpen) return false;
        Minecraft mc = Minecraft.getInstance();
        DisplayPanelLayout panel = displayPanelLayout(mc.getWindow().getWidth(), mc.getWindow().getHeight());
        if (mouseX < panel.x() || mouseX > panel.x() + panel.width()
            || mouseY < panel.y() || mouseY > panel.y() + panel.height()) return false;
        if (mouseY < panel.y() + 28) return true;

        int col = Math.max(0, Math.min(panel.columns() - 1,
            (int) ((mouseX - panel.x() - 6) / panel.rowWidth())));
        int row = (int) ((mouseY - panel.y() - 28) / panel.rowHeight());
        int index = row * panel.columns() + col;
        if (index < 0 || index >= panel.optionCount()) return true;

        if (index >= MapLayer.values().length) {
            ToolbarLayout toolbar = toolbarLayout(mc.getWindow().getWidth());
            boolean undergroundIncluded = mode3d && !toolbar.showUnderground();
            int extraIndex = index - MapLayer.values().length;
            int loadMapIndex = (undergroundIncluded ? 1 : 0) + (mode3d ? 1 : 0);
            if (extraIndex == loadMapIndex) startMapLoading();
            else if (undergroundIncluded && extraIndex == 0) toggleUndergroundView();
            else terrainDebugView = !terrainDebugView;
        } else {
            SeedExplorerModule module = Modules.get().get(SeedExplorerModule.class);
            if (module != null) {
                MapLayer layer = MapLayer.values()[index];
                module.setLayerEnabled(layer, !module.isLayerEnabled(layer));
            }
        }
        return true;
    }

    private boolean handleStructurePanelClick(double mouseX, double mouseY) {
        if (!structurePanelOpen) return false;
        Minecraft mc = Minecraft.getInstance();
        double screenWidth = mc.getWindow().getWidth();
        double screenHeight = mc.getWindow().getHeight();
        List<StructureType> types = structureTypesForDimension(selectedDimension);
        int rowHeight = 23;
        int panelWidth = Math.max(180, Math.min(260, (int) screenWidth - 20));
        int panelX = Math.max(6, Math.min(10, (int) screenWidth - panelWidth - 6));
        int panelY = floatingPanelY(screenWidth);
        int panelHeight = Math.max(30, Math.min((int) screenHeight - panelY - 12, 30 + types.size() * rowHeight));
        if (mouseX < panelX || mouseX > panelX + panelWidth || mouseY < panelY || mouseY > panelY + panelHeight) return false;

        int index = structurePanelScroll + (int) ((mouseY - panelY - 28) / rowHeight);
        if (index >= 0 && index < types.size()) {
            StructureType type = types.get(index);
            boolean enabled = SeedManager.get().getProfileStructure(type.name(), true);
            SeedManager.get().setProfileStructure(type.name(), !enabled);
            return true;
        }

        return true;
    }

    private boolean handleStructurePanelScroll(double mouseX, double mouseY, double amount) {
        if (!structurePanelOpen || amount == 0) return false;
        Minecraft mc = Minecraft.getInstance();
        double screenWidth = mc.getWindow().getWidth();
        double screenHeight = mc.getWindow().getHeight();
        int rowHeight = 23;
        int panelWidth = Math.max(180, Math.min(260, (int) screenWidth - 20));
        int panelX = Math.max(6, Math.min(10, (int) screenWidth - panelWidth - 6));
        int panelY = floatingPanelY(screenWidth);
        List<StructureType> types = structureTypesForDimension(selectedDimension);
        int panelHeight = Math.max(30, Math.min((int) screenHeight - panelY - 12, 30 + types.size() * rowHeight));
        if (mouseX < panelX || mouseX > panelX + panelWidth
            || mouseY < panelY || mouseY > panelY + panelHeight) return false;

        int visibleRows = Math.max(0, (panelHeight - 30) / rowHeight);
        int maxScroll = Math.max(0, types.size() - visibleRows);
        structurePanelScroll = Math.max(0, Math.min(maxScroll,
            structurePanelScroll + (amount > 0 ? -1 : 1)));
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
        if (mapLoading) return;
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
        if (mapLoading) return true;
        double renderX = toRenderX(mouseX);
        double renderY = toRenderY(mouseY);
        if (handleStructurePanelScroll(renderX, renderY, verticalAmount)) return true;

        if (mode3d) {
            camera3d.scroll(verticalAmount);
            focusController.disableAutoRotate();
            return true;
        }

        SeedExplorerModule module = Modules.get().get(SeedExplorerModule.class);
        double zoomStep = module == null ? 1.2 : module.zoomStep.get();
        zoomStep = Mth.clamp(zoomStep, 1.01, 4.0);
        double oldZoom = targetZoom;
        double wheelMagnitude = Math.max(0.25, Math.min(8.0, Math.abs(verticalAmount)));
        double scale = Math.pow(zoomStep, wheelMagnitude);
        if (verticalAmount > 0) targetZoom *= scale;
        else if (verticalAmount < 0) targetZoom /= scale;
        double minZoom = module == null ? 0.01 : module.minZoom.get();
        double maxZoom = module == null ? 100.0 : module.maxZoom.get();
        // The lower bound is only a floating-point safety floor. The former
        // configurable minimum made repeated scroll-out appear to snap back.
        targetZoom = Mth.clamp(targetZoom, 1.0e-6, Math.max(1.0e6, Math.max(minZoom, maxZoom)));

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
        var window = Minecraft.getInstance().getWindow();
        return guiX * window.getWidth() / Math.max(1.0, window.getGuiScaledWidth());
    }

    private double toRenderY(double guiY) {
        var window = Minecraft.getInstance().getWindow();
        return guiY * window.getHeight() / Math.max(1.0, window.getGuiScaledHeight());
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent key) {
        if (mapLoading) {
            if (key.key() == 256) stopMapLoading();
            return true;
        }
        if (contextMenuOpen) {
            closeContextMenu();
            return true;
        }

        if ((displayPanelOpen || structurePanelOpen) && key.key() == 256) {
            displayPanelOpen = false;
            structurePanelOpen = false;
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
    public boolean isPauseScreen() {
        return mapLoading;
    }

    @Override
    public boolean keyReleased(net.minecraft.client.input.KeyEvent key) {
        if (mapLoading) return true;
        if (suppressNextEnterAction && isEnterKey(key)) {
            suppressNextEnterAction = false;
            return true;
        }

        // Release bookkeeping must run even when focus changed between the key
        // press and release. Otherwise a panel opened mid-flight can leave the
        // logical key held and poison the next tap tier.
        if (mode3d) {
            int movementKey = movementKeyGroup(key.key());
            if (movementKey != -1) {
                MovementTapState state = movementTapStates.get(movementKey);
                if (state != null && state.held) {
                    long now = System.nanoTime();
                    state.held = false;
                    if (now - state.pressNanos <= MOVEMENT_TAP_NANOS) {
                        state.lastTapNanos = now;
                    } else {
                        state.tier = 1;
                        state.lastTapNanos = 0L;
                    }
                }
                return true;
            }
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
        if (!is3dMovementKey(code)) return false;
        int movementKey = movementKeyGroup(code);
        if (movementKey != -1) {
            MovementTapState state = movementTapStates.computeIfAbsent(movementKey,
                ignored -> new MovementTapState());
            // GLFW may send repeat key-press events while a key is held. Only the
            // initial transition starts or advances a tap tier.
            if (!state.held) {
                long now = System.nanoTime();
                if (state.lastTapNanos != 0L
                    && now - state.lastTapNanos <= MOVEMENT_DOUBLE_TAP_NANOS) {
                    state.tier = Math.min(3, state.tier + 1);
                } else {
                    state.tier = 1;
                }
                state.pressNanos = now;
                state.held = true;
            }
        }
        if (focusController.isActive()) focusController.releaseForManualFlight();
        return true;
    }

    private void update3dFlight() {
        long now = System.nanoTime();
        double deltaSeconds = last3dMovementNanos == 0L
            ? 0.0 : Math.min(0.05, Math.max(0.0, (now - last3dMovementNanos) / 1_000_000_000.0));
        last3dMovementNanos = now;

        if (isTextInputFocused() || contextMenuOpen || displayPanelOpen || structurePanelOpen) {
            camera3d.stopFlight();
            return;
        }

        double forward = movementInput(KEY_W) - movementInput(KEY_S);
        double strafe = movementInput(KEY_D) - movementInput(KEY_A);
        double up = Math.max(movementInput(KEY_SPACE),
            Math.max(movementInput(KEY_LEFT_SHIFT), movementInput(KEY_RIGHT_SHIFT)));
        double down = Math.max(movementInput(KEY_C),
            Math.max(movementInput(KEY_LEFT_CONTROL), movementInput(KEY_RIGHT_CONTROL)));
        double vertical = up - down;
        boolean held = forward != 0.0 || strafe != 0.0 || vertical != 0.0;
        if (held) {
            if (focusController.isActive()) focusController.releaseForManualFlight();
        }
        camera3d.updateFlight(forward, strafe, vertical, deltaSeconds);
    }

    private boolean isKeyHeld(int key) {
        return InputConstants.isKeyDown(key);
    }

    private double movementInput(int key) {
        if (!isKeyHeld(key)) return 0.0;
        int movementKey = movementKeyGroup(key);
        if (movementKey == -1) return 1.0;
        MovementTapState state = movementTapStates.get(movementKey);
        return state != null && state.held ? state.tier : 1.0;
    }

    private int movementKeyGroup(int code) {
        if (code == KEY_RIGHT_SHIFT) return KEY_LEFT_SHIFT;
        if (code == KEY_RIGHT_CONTROL) return KEY_LEFT_CONTROL;
        return is3dMovementKey(code) ? code : -1;
    }

    private boolean is3dMovementKey(int code) {
        return code == KEY_W || code == KEY_S || code == KEY_A || code == KEY_D
            || code == KEY_SPACE || code == KEY_C
            || code == KEY_LEFT_SHIFT || code == KEY_RIGHT_SHIFT
            || code == KEY_LEFT_CONTROL || code == KEY_RIGHT_CONTROL;
    }

    private record LootSearchCandidate(int dimension, GeneratedStructure structure,
                                       double originDistanceSquared) {
    }

    private record ToolbarLayout(int screenWidth, int startX, int gap, int primaryWidth,
                                 int displayX, int displayWidth, int itemSearchX, int itemSearchWidth,
                                 int undergroundX, int undergroundWidth, boolean showUnderground,
                                 boolean mini) {
        int primaryX(int index) {
            return startX + index * (primaryWidth + gap);
        }
    }

    private record DisplayPanelLayout(int x, int y, int width, int height, int columns,
                                      int rowWidth, int rowHeight, int optionCount) {
    }

    private record ChestPanelLayout(int x, int y, int width, int height) {
    }

    private record LootSearchResult(int dimension, GeneratedStructure structure,
                                    ChestLootOutput chest, List<ChestLootOutput> chests,
                                    Map<ChestLootOutput, Set<LootSearchTerm>> matches,
                                    boolean combinedMatch, double originDistanceSquared) {
    }
}
