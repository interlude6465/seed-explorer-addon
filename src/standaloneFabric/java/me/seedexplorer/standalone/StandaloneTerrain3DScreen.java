package me.seedexplorer.standalone;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.FilterMode;
import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.render.FreeCamera3D;
import me.seedexplorer.addon.render.MapLayer;
import me.seedexplorer.addon.render.MapRenderContext;
import me.seedexplorer.addon.render.MapViewport;
import me.seedexplorer.addon.render.Terrain3DRenderer;
import me.seedexplorer.addon.render.TerrainFocusTarget;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.waypoints.SeedWaypoint;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Native standalone host for the shared asynchronous 3D terrain renderer. */
public final class StandaloneTerrain3DScreen extends Screen {
    private static final int COMPACT_LAYOUT_WIDTH = 760;
    private static final int NARROW_LAYOUT_WIDTH = 400;
    private static final ThreadPoolExecutor STRUCTURE_GENERATOR = new ThreadPoolExecutor(1, 1,
        30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "Seed Explorer 3D structures");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final AtomicLong STRUCTURE_REQUESTS = new AtomicLong();
    private static final int KEY_W = InputConstants.KEYCODE_W;
    private static final int KEY_S = 's';
    private static final int KEY_A = InputConstants.KEYCODE_A;
    private static final int KEY_D = 'd';
    private static final int KEY_SPACE = InputConstants.KEYCODE_SPACE;
    private static final int KEY_C = InputConstants.KEYCODE_C;
    private static final int KEY_LEFT_SHIFT = (1 << 30) | InputConstants.KEY_LSHIFT;
    private static final int KEY_RIGHT_SHIFT = (1 << 30) | InputConstants.KEY_RSHIFT;
    private static final int KEY_LEFT_CONTROL = InputConstants.KEYCODE_LCONTROL;
    private static final int KEY_RIGHT_CONTROL = InputConstants.KEYCODE_RCONTROL;
    private static final int KEY_ENTER = InputConstants.KEYCODE_RETURN;
    private static final int KEY_NUMPAD_ENTER = InputConstants.KEYCODE_NUMPADENTER;
    private static final int KEY_ESCAPE = 27;
    private static final int KEY_F = InputConstants.KEYCODE_F;
    private static final int KEY_T = 't';

    private final Screen parent;
    private final StandaloneSeedExplorerConfig config;
    private final long seed;
    private int dimension;
    private volatile List<GeneratedStructure> structures;
    private final FreeCamera3D camera = new FreeCamera3D();
    private final Terrain3DRenderer renderer = Terrain3DRenderer.shared();
    private RenderTarget terrainTarget;
    private Standalone3DSelectionController selection;
    private final StandaloneNavigationCapability navigation = new ReflectiveBaritoneNavigation();
    private final boolean navigationAvailable;
    private Button undergroundButton;
    private Button dimensionButton;
    private Button bulkButton;
    private Button focusButton;
    private Button detailButton;
    private Button teleportButton;
    private Button waypointButton;
    private Button previousMatchButton;
    private Button nextMatchButton;
    private Button completedButton;
    private Button navigateButton;
    private EditBox searchField;
    private boolean cameraInitialized;
    private boolean underground;
    private final Set<Integer> heldMovementKeys = new HashSet<>();
    private int dragButton = -1;
    private double rightDragDistance;
    private double rightPressX;
    private double rightPressY;
    private List<ContextAction> contextActions = List.of();
    private int contextX;
    private int contextY;
    private int contextWidth;
    private long lastFrameNanos;
    private volatile long activeStructureRequest;
    private int structureAnchorChunkX = Integer.MIN_VALUE;
    private int structureAnchorChunkZ = Integer.MIN_VALUE;
    private String renderError = "";
    private String loggedRenderError = "";
    private GeneratedStructure hoveredStructure;
    private StandaloneWaypoint hoveredWaypoint;
    private StandaloneWaypoint selectedWaypoint;
    private String searchQuery = "";
    private boolean focusActive;
    private GeneratedStructure focusedStructure;
    private StandaloneWaypoint focusedWaypoint;
    private double orbitYawDegrees;
    private CameraState returnCamera;
    private boolean bulkLoading;
    private double bulkCenterX;
    private double bulkCenterZ;
    private Terrain3DRenderer.BulkLoadStatus bulkStatus;
    private StandaloneSearch.Result pendingSearchResult;
    private boolean initialViewRequested;
    private double initialViewX;
    private double initialViewZ;
    private GeneratedStructure initialStructure;
    private StandaloneWaypoint initialWaypoint;
    private List<ChestLootOutput> initialChests;
    private int initialChestIndex;
    private String notice = "";
    private long noticeUntil;

    public StandaloneTerrain3DScreen(Screen parent, StandaloneSeedExplorerConfig config,
                                     long seed, int dimension,
                                     List<GeneratedStructure> structures) {
        super(Component.literal("Seed Explorer 3D"));
        this.parent = parent;
        this.config = config;
        this.seed = seed;
        this.dimension = dimension;
        this.structures = structures == null ? List.of() : List.copyOf(structures);
        this.selection = new Standalone3DSelectionController(seed, dimension, config);
        this.navigationAvailable = navigation.available();
    }

    public StandaloneTerrain3DScreen openAt(double x, double z,
                                             GeneratedStructure selected,
                                             List<ChestLootOutput> knownChests,
                                             int selectedChestIndex) {
        initialViewRequested = true;
        initialViewX = x;
        initialViewZ = z;
        initialStructure = selected;
        initialChests = knownChests == null ? null : List.copyOf(knownChests);
        initialChestIndex = selectedChestIndex;
        return this;
    }

    public StandaloneTerrain3DScreen selectInitialWaypoint(StandaloneWaypoint waypoint) {
        initialWaypoint = waypoint;
        return this;
    }

    @Override
    protected void init() {
        if (!cameraInitialized) {
            if (initialViewRequested) {
                double y = dimension == -1 ? 64.0 : dimension == 1 ? 80.0 : 96.0;
                camera.viewOf(initialViewX, y, initialViewZ, 112.0, 60.0, 0.0);
                lastFrameNanos = System.nanoTime();
                if (initialStructure != null) {
                    if (!structures.contains(initialStructure)) {
                        List<GeneratedStructure> expanded = new java.util.ArrayList<>(structures);
                        expanded.add(initialStructure);
                        structures = List.copyOf(expanded);
                    }
                    if (initialChests == null) selection.select(initialStructure);
                    else selection.selectKnown(initialStructure, initialChests, initialChestIndex);
                } else if (initialWaypoint != null) {
                    selectWaypoint(initialWaypoint, false);
                }
            } else {
                recenterCamera();
            }
            cameraInitialized = true;
        }
        boolean narrow = width < NARROW_LAYOUT_WIDTH;

        addRenderableWidget(Button.builder(Component.literal("2D Map"), button -> onClose())
            .bounds(10, 10, narrow ? 66 : 76, 20).build());
        undergroundButton = addRenderableWidget(Button.builder(undergroundLabel(), button -> {
            underground = !underground;
            undergroundButton.setMessage(undergroundLabel());
        }).bounds(narrow ? 82 : 92, 10, narrow ? 94 : 106, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Recenter"), button -> recenterCamera())
            .bounds(narrow ? 182 : 204, 10, narrow ? 66 : 76, 20).build());
        dimensionButton = addRenderableWidget(Button.builder(Component.literal(dimensionName()), button ->
            cycleDimension()).bounds(narrow ? 10 : 226, narrow ? 66 : 38,
                90, 20).build());

        Button settings = Button.builder(Component.literal("\u2699"), button ->
            minecraft.gui.setScreen(new StandaloneSeedExplorerSettingsScreen(this, config)))
            .bounds(width - 34, 10, 24, 20).build();
        settings.setTooltip(Tooltip.create(Component.literal("Seed Explorer settings")));
        addRenderableWidget(settings);

        int searchWidth = narrow ? Math.max(80, width - 80) : 150;
        searchField = addRenderableWidget(new EditBox(font, 10, 38, searchWidth, 20,
            Component.literal("Find structure")));
        searchField.setMaxLength(80);
        searchField.setValue(searchQuery);
        searchField.setHint(Component.literal("Find structure"));
        searchField.setResponder(value -> {
            searchQuery = value;
            updateActionButtons();
        });
        int matchX = narrow ? width - 64 : 166;
        previousMatchButton = addRenderableWidget(Button.builder(Component.literal("<"), button ->
            navigateMatch(-1)).bounds(matchX, 38, 24, 20).build());
        nextMatchButton = addRenderableWidget(Button.builder(Component.literal(">"), button ->
            navigateMatch(1)).bounds(matchX + 30, 38, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Actions"), button -> openActions())
            .bounds(narrow ? 106 : 322, narrow ? 66 : 38, 68, 20).build());
        updateActionButtons();
        if (pendingSearchResult != null) {
            StandaloneSearch.Result pending = pendingSearchResult;
            pendingSearchResult = null;
            acceptSearchResult(pending);
        }
    }

    @Override
    protected void setInitialFocus() {
        // This is a flight-control screen. Keyboard focus should only move to
        // the search field after the player explicitly clicks or tabs to it.
        setFocused(null);
        if (searchField != null) searchField.setFocused(false);
    }

    private Component undergroundLabel() {
        return Component.literal(underground ? "Surface view" : "Underground");
    }

    private Component bulkLabel() {
        return Component.literal(bulkLoading ? "Stop load" : "Load area");
    }

    private int contentTop() {
        return width < NARROW_LAYOUT_WIDTH ? 90 : 64;
    }

    private void recenterCamera() {
        double x = 0.0;
        double y = dimension == -1 ? 64.0 : 96.0;
        double z = 0.0;
        if (minecraft != null && minecraft.player != null) {
            x = minecraft.player.getX();
            z = minecraft.player.getZ();
            y = dimension == -1 ? 64.0 : dimension == 1 ? 80.0 : minecraft.player.getY();
        }
        camera.viewOf(x, y, z, 112.0, 60.0, 0.0);
        focusActive = false;
        focusedStructure = null;
        focusedWaypoint = null;
        lastFrameNanos = System.nanoTime();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int framebufferWidth = minecraft.getWindow().getWidth();
        int framebufferHeight = minecraft.getWindow().getHeight();
        ensureTerrainTarget(framebufferWidth, framebufferHeight);
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            terrainTarget.getColorTexture(), new Vector4f(0.025f, 0.04f, 0.045f, 1.0f),
            terrainTarget.getDepthTexture(), 1.0);
        graphics.blit(terrainTarget.getColorTextureView(),
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            0, 0, graphics.guiWidth(), graphics.guiHeight(),
            0.0f, 1.0f, 1.0f, 0.0f);
        camera.aspectRatio(framebufferWidth / (double) Math.max(1, framebufferHeight));
        updateFlight();
        updateStructureWindow();

        double renderMouseX = toRenderX(mouseX);
        double renderMouseY = toRenderY(mouseY);
        EnumSet<MapLayer> layers = EnumSet.noneOf(MapLayer.class);
        if (config.booleanValue("structure-layer", true)) layers.add(MapLayer.STRUCTURES);
        List<WaypointView> waypointViews = List.of();
        List<SeedWaypoint> waypoints = List.of();
        if (config.booleanValue("waypoint-layer", true)) {
            layers.add(MapLayer.WAYPOINTS);
            waypointViews = config.waypoints(seed, dimension).stream()
                .map(waypoint -> new WaypointView(waypoint,
                    new SeedWaypoint(waypoint.name(), waypoint.x(), waypoint.z(),
                        waypoint.dimension(), "square", waypoint.structureType())))
                .toList();
            waypoints = waypointViews.stream().map(WaypointView::rendered).toList();
        }
        MapViewport viewport = MapViewport.create(camera.targetX(), camera.targetZ(), 1.0,
            framebufferWidth, framebufferHeight);
        MapRenderContext context = new MapRenderContext(viewport,
            (int) Math.round(renderMouseX), (int) Math.round(renderMouseY), delta,
            dimension, layers, config.booleanValue("show-player-info", true),
            config.intValue("generation-margin", 10, 0, 48),
            config.intValue("biome-tile-margin", 2, 0, 16),
            config.intValue("biome-opacity", 255, 40, 255),
            config.intValue("ore-opacity", 55, 20, 255),
            config.doubleValue("ore-marker-scale", 1.0, 0.5, 3.0),
            config.doubleValue("structure-marker-scale", 1.0, 0.5, 3.0),
            config.doubleValue("waypoint-marker-scale", 1.0, 0.5, 3.0),
            config.booleanValue("dim-waypoint-structures", true),
            config.booleanValue("loaded-terrain-on-map", true), null, null);

        List<GeneratedStructure> visibleStructures = visibleStructures();
        double centerX = framebufferWidth / 2.0;
        double centerY = framebufferHeight / 2.0;
        SeedWaypoint hoveredRenderedWaypoint = config.booleanValue("waypoint-layer", true)
            ? renderer.pickWaypoint(camera, renderMouseX, renderMouseY, waypoints, centerX, centerY)
            : null;
        hoveredWaypoint = standaloneWaypoint(hoveredRenderedWaypoint, waypointViews);
        SeedWaypoint emphasizedWaypoint = hoveredRenderedWaypoint != null
            ? hoveredRenderedWaypoint : renderedWaypoint(selectedWaypoint, waypointViews);
        GeneratedStructure hovered = hoveredWaypoint == null
            && config.booleanValue("structure-layer", true)
            ? renderer.pickStructure(camera, renderMouseX, renderMouseY, visibleStructures, context,
                centerX, centerY)
            : null;
        hoveredStructure = hovered;

        try {
            renderer.renderToTarget(terrainTarget, graphics, camera, context,
                visibleStructures, waypoints,
                hovered != null ? hovered : selection.structure(), emphasizedWaypoint,
                underground, false, false,
                focusActive ? focusedStructure : null, seed);
            renderer.renderChestMarkers(camera, selection.chests(), selection.chest(), null,
                framebufferWidth / 2.0, framebufferHeight / 2.0, minecraft);
            renderError = "";
        } catch (RuntimeException | LinkageError failure) {
            renderError = failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
            if (!renderError.equals(loggedRenderError)) {
                loggedRenderError = renderError;
                System.err.println("[Seed Explorer] Standalone 3D rendering failed");
                failure.printStackTrace(System.err);
            }
        }

        if (bulkLoading) bulkStatus = renderer.bulkLoad(seed, dimension, bulkCenterX, bulkCenterZ);

        graphics.fill(0, 0, width, contentTop(), 0xC0101517);
        String status = dimensionName() + "  |  Seed " + seed
            + "  |  WASD move, drag to look, Space/C up/down";
        if (width >= COMPACT_LAYOUT_WIDTH) {
            graphics.text(font, Component.literal(ellipsize(status, Math.max(30, width - 580))),
                536, 16, 0xFFE4EAE5);
        }
        drawSelectionPanel(graphics);
        drawBulkStatus(graphics);
        if (!notice.isBlank() && System.currentTimeMillis() < noticeUntil) {
            int noticeWidth = Math.min(width - 24, font.width(notice) + 16);
            graphics.fill((width - noticeWidth) / 2, height - 30,
                (width + noticeWidth) / 2, height - 10, 0xE8152328);
            graphics.centeredText(font, Component.literal(ellipsize(notice, noticeWidth - 12)),
                width / 2, height - 24, 0xFFE4EAE5);
        }
        updateActionButtons();
        if (!renderError.isBlank()) {
            int panelWidth = Math.min(width - 40, 460);
            int x = (width - panelWidth) / 2;
            graphics.fill(x, 48, x + panelWidth, 88, 0xE0351515);
            graphics.centeredText(font, Component.literal("3D renderer error"), width / 2, 57,
                0xFFFFA198);
            graphics.centeredText(font, Component.literal(ellipsize(renderError, panelWidth - 20)),
                width / 2, 72, 0xFFFFFFFF);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        drawContextMenu(graphics, mouseX, mouseY);
    }

    private void drawContextMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (contextActions.isEmpty()) return;
        graphics.nextStratum();
        int menuHeight = contextActions.size() * 22;
        graphics.fill(contextX - 1, contextY - 1, contextX + contextWidth + 1,
            contextY + menuHeight + 1, 0xFF658878);
        for (int index = 0; index < contextActions.size(); index++) {
            int y = contextY + index * 22;
            boolean hovered = mouseX >= contextX && mouseX < contextX + contextWidth
                && mouseY >= y && mouseY < y + 22;
            graphics.fill(contextX, y, contextX + contextWidth, y + 22,
                hovered ? 0xFF324F43 : 0xFF15251E);
            graphics.text(font, contextActions.get(index).label(), contextX + 8, y + 7,
                0xFFFFFFFF);
        }
    }

    private void ensureTerrainTarget(int framebufferWidth, int framebufferHeight) {
        int targetWidth = Math.max(1, framebufferWidth);
        int targetHeight = Math.max(1, framebufferHeight);
        if (terrainTarget == null) {
            terrainTarget = new TextureTarget("Seed Explorer standalone 3D",
                targetWidth, targetHeight, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        } else if (terrainTarget.width != targetWidth || terrainTarget.height != targetHeight) {
            terrainTarget.resize(targetWidth, targetHeight);
        }
    }

    private void drawSelectionPanel(GuiGraphicsExtractor graphics) {
        GeneratedStructure selected = selection.structure();
        StandaloneWaypoint waypoint = selectedWaypoint;
        if (selected == null && waypoint == null) return;
        int panelWidth = Math.min(330, Math.max(190, width - 32));
        int panelHeight = selected == null ? 58 : selection.loading() ? 58 : 76;
        int x = width - panelWidth - 12;
        int y = contentTop() + 10;
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xE8101714);
        graphics.fill(x, y, x + 3, y + panelHeight, 0xFF45B6A6);
        String title = selected != null ? selected.displayName() : waypoint.name();
        String coordinates = selected != null ? selected.getCoordsString()
            : waypoint.x() + ", " + waypoint.z();
        graphics.text(font, ellipsize(title, panelWidth - 18),
            x + 9, y + 8, 0xFFFFFFFF);
        graphics.text(font, ellipsize(coordinates, panelWidth - 18),
            x + 9, y + 22, 0xFF9AA8A0);
        if (selected == null) {
            graphics.text(font, "Waypoint  |  " + dimensionName(), x + 9, y + 36,
                0xFFE2B64C);
            return;
        }
        String loot;
        if (selection.loading()) loot = "Predicting containers...";
        else if (!selection.error().isBlank()) loot = "Loot error: " + selection.error();
        else if (!selection.supported()) loot = "Loot prediction unavailable";
        else loot = selection.chests().size() + " predicted container"
            + (selection.chests().size() == 1 ? "" : "s");
        graphics.text(font, ellipsize(loot, panelWidth - 18), x + 9, y + 36,
            selection.error().isBlank() ? 0xFFE2B64C : 0xFFFF9388);
        ChestLootOutput chest = selection.chest();
        if (!selection.loading() && chest != null) {
            int index = selection.selectedChestIndex();
            String chestLine = "Selected " + (index + 1) + "/" + selection.chests().size()
                + ": " + chest.summary();
            graphics.text(font, ellipsize(chestLine, panelWidth - 18),
                x + 9, y + 52, 0xFFE7EDF3);
        }
    }

    private void drawBulkStatus(GuiGraphicsExtractor graphics) {
        if (!bulkLoading || bulkStatus == null) return;
        int panelWidth = Math.min(460, width - 32);
        int x = (width - panelWidth) / 2;
        int y = height - 74;
        graphics.fill(x, y, x + panelWidth, y + 52, 0xE8101714);
        double progress = bulkStatus.progress();
        int filled = (int) Math.round((panelWidth - 20) * progress);
        graphics.fill(x + 10, y + 30, x + panelWidth - 10, y + 39, 0xFF26312D);
        graphics.fill(x + 10, y + 30, x + 10 + filled, y + 39, 0xFF45B6A6);
        String line = String.format(java.util.Locale.ROOT,
            "Loading terrain %.1f%%  |  %.1f chunks/s  |  %d / %d",
            progress * 100.0, bulkStatus.chunksPerSecond(),
            bulkStatus.completedExactChunks(), bulkStatus.targetExactChunks());
        graphics.centeredText(font, Component.literal(ellipsize(line, panelWidth - 20)),
            width / 2, y + 10, 0xFFE7EDF3);
        String detail = "Detail " + bulkStatus.detailRing() + "  |  Coverage "
            + bulkStatus.coverageRing() + "  |  Queue " + bulkStatus.queuedGenerationJobs();
        graphics.centeredText(font, Component.literal(ellipsize(detail, panelWidth - 20)),
            width / 2, y + 42, 0xFF9AA8A0);
    }

    private String dimensionName() {
        return switch (dimension) {
            case -1 -> "Nether";
            case 1 -> "End";
            default -> "Overworld";
        };
    }

    private void cycleDimension() {
        int target = switch (dimension) {
            case 0 -> -1;
            case -1 -> 1;
            default -> 0;
        };
        switchDimension(target);
    }

    private void switchDimension(int target) {
        if (target == dimension || target != -1 && target != 0 && target != 1) return;
        if (bulkLoading) renderer.stopBulkLoading();
        bulkLoading = false;
        bulkStatus = null;
        selection.close();
        selection = new Standalone3DSelectionController(seed, target, config);
        dimension = target;
        structures = List.of();
        selectedWaypoint = null;
        hoveredWaypoint = null;
        hoveredStructure = null;
        structureAnchorChunkX = Integer.MIN_VALUE;
        structureAnchorChunkZ = Integer.MIN_VALUE;
        activeStructureRequest = -1L;
        recenterCamera();
        if (dimensionButton != null) dimensionButton.setMessage(Component.literal(dimensionName()));
        updateActionButtons();
        showNotice("Viewing " + dimensionName());
    }

    private void toggleBulkLoading() {
        if (bulkLoading) {
            renderer.stopBulkLoading();
            bulkLoading = false;
            bulkStatus = null;
        } else {
            bulkCenterX = camera.targetX();
            bulkCenterZ = camera.targetZ();
            bulkLoading = true;
        }
        if (bulkButton != null) bulkButton.setMessage(bulkLabel());
    }

    private void openSearch() {
        minecraft.gui.setScreen(new StandaloneSearchScreen(this, config, selection.context(),
            (int) Math.floor(camera.targetX()), (int) Math.floor(camera.targetZ()),
            this::acceptSearchResult));
    }

    private void openActions() {
        GeneratedStructure structure = selection.structure();
        boolean hasStructure = structure != null;
        boolean hasWaypoint = selectedWaypoint != null;
        boolean hasSelection = hasStructure || hasWaypoint;
        boolean waypointExists = hasStructure
            && config.hasWaypointAt(seed, dimension, structure.x, structure.z);
        boolean completed = hasStructure && config.completed(dimension, structure.type.name(),
            structure.x, structure.z);
        List<Standalone3DActionsScreen.Action> actions = List.of(
            new Standalone3DActionsScreen.Action("Search", true, this::openSearch),
            new Standalone3DActionsScreen.Action(bulkLoading ? "Stop load" : "Load area",
                true, this::toggleBulkLoading),
            new Standalone3DActionsScreen.Action(focusActive ? "Back from focus" : "Focus",
                hasSelection, this::toggleFocus),
            new Standalone3DActionsScreen.Action("Inspect loot", hasStructure, this::openDetails),
            new Standalone3DActionsScreen.Action("Teleport", hasSelection, this::teleportSelection),
            new Standalone3DActionsScreen.Action("Copy coordinates", hasSelection, this::copySelection),
            new Standalone3DActionsScreen.Action(hasWaypoint ? "Edit waypoint"
                : hasStructure ? waypointExists ? "Unpin waypoint" : "Add waypoint" : "Add here",
                true, this::waypointAction),
            new Standalone3DActionsScreen.Action(hasWaypoint ? "Delete waypoint"
                : completed ? "Mark incomplete" : "Mark completed",
                hasSelection, this::completionAction),
            new Standalone3DActionsScreen.Action("Navigate",
                hasSelection && navigationAvailable && playerDimension() == dimension,
                this::navigateSelection));
        minecraft.gui.setScreen(new Standalone3DActionsScreen(this, actions));
    }

    private void acceptSearchResult(StandaloneSearch.Result result) {
        if (result == null) return;
        if (result.dimension() != dimension) {
            switchDimension(result.dimension());
        }
        if (result.structure() != null) {
            if (!structures.contains(result.structure())) {
                List<GeneratedStructure> expanded = new java.util.ArrayList<>(structures);
                expanded.add(result.structure());
                structures = List.copyOf(expanded);
            }
            selectStructure(result.structure(), true);
        } else {
            double y = result.y() == GeneratedStructure.UNKNOWN_Y ? 80.0 : result.y();
            camera.viewOf(result.x(), y, result.z(), 64.0, 55.0, Math.toDegrees(camera.yaw()));
        }
        showNotice(result.label());
    }

    private static String dimensionName(int dimension) {
        return switch (dimension) {
            case -1 -> "Nether";
            case 1 -> "End";
            default -> "Overworld";
        };
    }

    private List<GeneratedStructure> matchingStructures() {
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase(java.util.Locale.ROOT);
        List<GeneratedStructure> matches = visibleStructures().stream()
            .filter(structure -> {
                if (query.isBlank()) return true;
                String haystack = (structure.displayName() + " " + structure.type.name() + " "
                    + structure.type.displayName + " " + structure.variant)
                    .toLowerCase(java.util.Locale.ROOT);
                for (String token : query.split("\\s+")) {
                    if (!haystack.contains(token)) return false;
                }
                return true;
            })
            .sorted(Comparator.comparingDouble(this::distanceSquaredToCamera))
            .toList();
        return matches;
    }

    private List<GeneratedStructure> visibleStructures() {
        return structures.stream()
            .filter(structure -> config.structureVisible(structure.type.name()))
            .toList();
    }

    private static SeedWaypoint renderedWaypoint(StandaloneWaypoint waypoint) {
        return waypoint == null ? null : new SeedWaypoint(waypoint.name(), waypoint.x(), waypoint.z(),
            waypoint.dimension(), "square", waypoint.structureType());
    }

    private static SeedWaypoint renderedWaypoint(StandaloneWaypoint waypoint,
                                                   List<WaypointView> views) {
        if (waypoint == null) return null;
        for (WaypointView view : views) {
            if (view.waypoint().id().equals(waypoint.id())) return view.rendered();
        }
        return null;
    }

    private static StandaloneWaypoint standaloneWaypoint(SeedWaypoint rendered,
                                                           List<WaypointView> views) {
        if (rendered == null) return null;
        for (WaypointView view : views) {
            if (view.rendered() == rendered) return view.waypoint();
        }
        return null;
    }

    private double distanceSquaredToCamera(GeneratedStructure structure) {
        double dx = structure.x - camera.targetX();
        double dz = structure.z - camera.targetZ();
        return dx * dx + dz * dz;
    }

    private void navigateMatch(int direction) {
        List<GeneratedStructure> matches = matchingStructures();
        if (matches.isEmpty()) {
            showNotice("No matching structures in the generated window");
            return;
        }
        int index = matches.indexOf(selection.structure());
        index = index < 0 ? (direction < 0 ? matches.size() - 1 : 0)
            : Math.floorMod(index + direction, matches.size());
        selectStructure(matches.get(index), true);
        showNotice("Match " + (index + 1) + " / " + matches.size());
    }

    private void selectStructure(GeneratedStructure structure, boolean focus) {
        if (!focus && focusActive) releaseFocusForManualControl();
        selectedWaypoint = null;
        selection.select(structure);
        if (focus) focusOn(structure);
        updateActionButtons();
    }

    private void selectWaypoint(StandaloneWaypoint waypoint, boolean focus) {
        if (!focus && focusActive) releaseFocusForManualControl();
        selection.select(null);
        selectedWaypoint = waypoint;
        if (focus && waypoint != null) focusOn(waypoint);
        updateActionButtons();
    }

    private void toggleFocus() {
        if (focusActive) {
            returnFromFocus();
        } else focusSelection();
    }

    private void focusSelection() {
        if (selection.structure() != null) focusOn(selection.structure());
        else if (selectedWaypoint != null) focusOn(selectedWaypoint);
    }

    private void focusOn(GeneratedStructure structure) {
        if (structure == null) return;
        if (!focusActive) {
            returnCamera = new CameraState(camera.targetX(), camera.targetY(), camera.targetZ(),
                camera.distance(), Math.toDegrees(camera.pitch()), Math.toDegrees(camera.yaw()));
        }
        TerrainFocusTarget target = renderer.structureFocusTarget(structure);
        if (target == null) {
            double y = structure.y == GeneratedStructure.UNKNOWN_Y ? 68.0 : structure.y + 2.0;
            target = new TerrainFocusTarget(structure.x + 0.5, y, structure.z + 0.5, 28.0);
        }
        focusedStructure = structure;
        focusedWaypoint = null;
        focusActive = true;
        orbitYawDegrees = Math.toDegrees(camera.yaw());
        camera.animateViewOf(target.x(), target.y(), target.z(), target.distance(),
            55.0, Math.toDegrees(camera.yaw()), 34);
        updateActionButtons();
    }

    private void focusOn(StandaloneWaypoint waypoint) {
        if (waypoint == null) return;
        if (!focusActive) {
            returnCamera = new CameraState(camera.targetX(), camera.targetY(), camera.targetZ(),
                camera.distance(), Math.toDegrees(camera.pitch()), Math.toDegrees(camera.yaw()));
        }
        TerrainFocusTarget target = renderer.waypointFocusTarget(renderedWaypoint(waypoint));
        focusedStructure = null;
        focusedWaypoint = waypoint;
        focusActive = true;
        orbitYawDegrees = Math.toDegrees(camera.yaw());
        camera.animateViewOf(target.x(), target.y(), target.z(), target.distance(),
            55.0, orbitYawDegrees, 34);
        updateActionButtons();
    }

    private void returnFromFocus() {
        CameraState previous = returnCamera;
        focusActive = false;
        focusedStructure = null;
        focusedWaypoint = null;
        if (previous != null) {
            camera.animateTo(previous.x, previous.y, previous.z, previous.distance,
                previous.pitchDegrees, previous.yawDegrees, 28);
        }
        updateActionButtons();
    }

    private void releaseFocusForManualControl() {
        if (!focusActive) return;
        focusActive = false;
        focusedStructure = null;
        focusedWaypoint = null;
        camera.stopFlight();
        updateActionButtons();
    }

    private void openDetails() {
        GeneratedStructure structure = selection.structure();
        if (structure == null) return;
        if (!selection.loading() && selection.supported()) {
            minecraft.gui.setScreen(new StandaloneStructureDetailScreen(this, config,
                selection.context(), structure, selection.chests(), selection.selectedChestIndex()));
        } else {
            minecraft.gui.setScreen(new StandaloneStructureDetailScreen(this, config,
                selection.context(), structure));
        }
    }

    private void teleportSelection() {
        ChestLootOutput chest = selection.chest();
        if (chest != null) {
            sendTeleport(chest.blockX(), chest.blockY(), chest.blockZ());
            return;
        }
        GeneratedStructure structure = selection.structure();
        if (structure != null) {
            int y = structure.y == GeneratedStructure.UNKNOWN_Y ? 100 : structure.y + 1;
            sendTeleport(structure.x, y, structure.z);
        } else if (selectedWaypoint != null) {
            TerrainFocusTarget target = renderer.waypointFocusTarget(
                renderedWaypoint(selectedWaypoint));
            sendTeleport(selectedWaypoint.x(), (int) Math.ceil(target.y() + 1.0),
                selectedWaypoint.z());
        }
    }

    private void sendTeleport(int x, int y, int z) {
        if (minecraft == null || minecraft.player == null || minecraft.player.connection == null) {
            showNotice("Teleport unavailable outside a world");
            return;
        }
        minecraft.player.connection.sendCommand("execute in " + dimensionId()
            + " run tp @s " + x + " " + y + " " + z);
        showNotice("Sent teleport to " + x + ", " + y + ", " + z);
    }

    private String dimensionId() {
        return switch (dimension) {
            case -1 -> "minecraft:the_nether";
            case 1 -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
    }

    private void copySelection() {
        if (minecraft == null || minecraft.keyboardHandler == null) return;
        ChestLootOutput chest = selection.chest();
        String text;
        if (chest != null) {
            text = chest.blockX() + " " + chest.blockY() + " " + chest.blockZ();
        } else {
            GeneratedStructure structure = selection.structure();
            if (structure != null) text = structure.getCoordsString();
            else if (selectedWaypoint != null) {
                text = selectedWaypoint.x() + ", " + selectedWaypoint.z();
            } else return;
        }
        minecraft.keyboardHandler.setClipboard(text);
        showNotice("Copied coordinates");
    }

    private void waypointAction() {
        GeneratedStructure structure = selection.structure();
        if (selectedWaypoint != null) {
            minecraft.gui.setScreen(new StandaloneWaypointEditScreen(this, config,
                selectedWaypoint, updated -> {
                    selectedWaypoint = updated;
                    if (focusActive) focusedWaypoint = updated;
                    updateActionButtons();
                }));
            return;
        }
        if (structure == null) {
            int x = (int) Math.floor(camera.targetX());
            int z = (int) Math.floor(camera.targetZ());
            StandaloneWaypoint added = config.addWaypoint(seed, dimension, x, z,
                null, null);
            config.save();
            selectWaypoint(added, false);
            showNotice("Waypoint added at " + x + ", " + z);
            return;
        }
        StandaloneWaypoint waypoint = config.waypointAt(seed, dimension, structure.x, structure.z);
        if (waypoint == null) {
            config.addWaypoint(seed, dimension, structure.x, structure.z,
                structure.displayName() + " [" + structure.x + ", " + structure.z + "]",
                structure.type.name());
            showNotice("Waypoint added");
        } else {
            config.removeWaypoint(waypoint.id());
            showNotice("Waypoint removed");
        }
        config.save();
        updateActionButtons();
    }

    private void completionAction() {
        if (selectedWaypoint != null) {
            StandaloneWaypoint removed = selectedWaypoint;
            if (config.removeWaypoint(removed.id())) {
                config.save();
                selectedWaypoint = null;
                releaseFocusForManualControl();
                showNotice("Waypoint deleted");
            }
            updateActionButtons();
            return;
        }
        GeneratedStructure structure = selection.structure();
        if (structure == null) return;
        boolean completed = config.toggleCompleted(dimension, structure.type.name(),
            structure.x, structure.z);
        showNotice(completed ? "Marked complete" : "Marked incomplete");
        updateActionButtons();
    }

    private void navigateSelection() {
        if (minecraft == null || minecraft.player == null || playerDimension() != dimension) {
            showNotice("Enter " + dimensionName() + " before navigating");
            return;
        }
        ChestLootOutput chest = selection.chest();
        GeneratedStructure structure = selection.structure();
        if (chest == null && structure == null && selectedWaypoint == null) return;
        int x = chest != null ? chest.blockX()
            : structure != null ? structure.x : selectedWaypoint.x();
        int y;
        if (chest != null) y = chest.blockY();
        else if (structure != null && structure.y != GeneratedStructure.UNKNOWN_Y) y = structure.y;
        else if (selectedWaypoint != null) {
            TerrainFocusTarget target = renderer.waypointFocusTarget(
                renderedWaypoint(selectedWaypoint));
            y = (int) Math.ceil(target.y());
        } else y = minecraft.player.getBlockY();
        int z = chest != null ? chest.blockZ()
            : structure != null ? structure.z : selectedWaypoint.z();
        showNotice(navigation.navigate(new BlockPos(x, y, z), 2).message());
    }

    private int playerDimension() {
        if (minecraft == null || minecraft.level == null) return Integer.MIN_VALUE;
        if (minecraft.level.dimension() == Level.NETHER) return -1;
        if (minecraft.level.dimension() == Level.END) return 1;
        return 0;
    }

    private void updateActionButtons() {
        GeneratedStructure structure = selection.structure();
        boolean hasStructure = structure != null;
        boolean hasWaypoint = selectedWaypoint != null;
        boolean hasSelection = hasStructure || hasWaypoint;
        if (focusButton != null) {
            focusButton.active = hasSelection;
            focusButton.setMessage(Component.literal(focusActive ? "Back" : "Focus"));
        }
        if (detailButton != null) detailButton.active = hasStructure;
        if (teleportButton != null) teleportButton.active = hasSelection;
        if (waypointButton != null) {
            waypointButton.active = true;
            boolean exists = hasStructure
                && config.hasWaypointAt(seed, dimension, structure.x, structure.z);
            waypointButton.setMessage(Component.literal(hasWaypoint ? "Edit"
                : hasStructure ? exists ? "Unpin" : "Waypoint" : "Add here"));
        }
        if (completedButton != null) {
            completedButton.active = hasSelection;
            boolean completed = hasStructure && config.completed(dimension, structure.type.name(),
                structure.x, structure.z);
            completedButton.setMessage(Component.literal(hasWaypoint ? "Delete"
                : completed ? "Undo" : "Complete"));
        }
        if (navigateButton != null) navigateButton.active = hasSelection && navigationAvailable;
        boolean hasMatches = !structures.isEmpty();
        if (previousMatchButton != null) previousMatchButton.active = hasMatches;
        if (nextMatchButton != null) nextMatchButton.active = hasMatches;
        if (bulkButton != null) bulkButton.setMessage(bulkLabel());
    }

    private void showNotice(String message) {
        notice = message;
        noticeUntil = System.currentTimeMillis() + 3_000L;
    }

    private void updateStructureWindow() {
        int chunkX = Math.floorDiv((int) Math.floor(camera.targetX()), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(camera.targetZ()), 16);
        if (structureAnchorChunkX != Integer.MIN_VALUE
            && Math.abs(chunkX - structureAnchorChunkX) < 12
            && Math.abs(chunkZ - structureAnchorChunkZ) < 12) return;

        structureAnchorChunkX = chunkX;
        structureAnchorChunkZ = chunkZ;
        int radius = 48 + config.intValue("generation-margin", 10, 0, 48);
        long request = STRUCTURE_REQUESTS.incrementAndGet();
        activeStructureRequest = request;
        PredictionContext prediction = new PredictionContext(seed, config.predictionVersion(), dimension);
        STRUCTURE_GENERATOR.execute(() -> {
            try {
                List<GeneratedStructure> generated = StandalonePredictionCache.predictDimension(
                    prediction, chunkX - radius, chunkZ - radius, chunkX + radius,
                    chunkZ + radius, true, true,
                    () -> activeStructureRequest != request
                        || STRUCTURE_REQUESTS.get() != request);
                if (activeStructureRequest == request && STRUCTURE_REQUESTS.get() == request) {
                    GeneratedStructure selected = selection.structure();
                    if (selected != null && !generated.contains(selected)) {
                        List<GeneratedStructure> retained = new java.util.ArrayList<>(generated);
                        retained.add(selected);
                        structures = List.copyOf(retained);
                    } else {
                        structures = List.copyOf(generated);
                    }
                }
            } catch (RuntimeException failure) {
                if (activeStructureRequest == request) {
                    renderError = "Structure prediction: " + failure.getClass().getSimpleName();
                }
            }
        });
    }

    private String ellipsize(String text, int maximumWidth) {
        if (font.width(text) <= maximumWidth) return text;
        String suffix = "...";
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end) + suffix) > maximumWidth) end--;
        return text.substring(0, end) + suffix;
    }

    private void updateFlight() {
        long now = System.nanoTime();
        double seconds = lastFrameNanos == 0L ? 0.0
            : Math.min(0.05, Math.max(0.0, (now - lastFrameNanos) / 1_000_000_000.0));
        lastFrameNanos = now;
        if (minecraft == null || !minecraft.isWindowActive()
            || searchField != null && searchField.isFocused()) {
            heldMovementKeys.clear();
            camera.stopFlight();
            return;
        }
        double forward = held(KEY_W) - held(KEY_S);
        double strafe = held(KEY_D) - held(KEY_A);
        double up = Math.max(held(KEY_SPACE), Math.max(held(KEY_LEFT_SHIFT), held(KEY_RIGHT_SHIFT)));
        double down = Math.max(held(KEY_C), Math.max(held(KEY_LEFT_CONTROL), held(KEY_RIGHT_CONTROL)));
        if (focusActive && (forward != 0.0 || strafe != 0.0 || up != 0.0 || down != 0.0)) {
            releaseFocusForManualControl();
        }
        camera.updateFlight(forward, strafe, up - down, seconds);
    }

    private double held(int key) {
        return heldMovementKeys.contains(key) ? 1.0 : 0.0;
    }

    private double toRenderX(double guiX) {
        return guiX * minecraft.getWindow().getWidth()
            / Math.max(1.0, minecraft.getWindow().getGuiScaledWidth());
    }

    private double toRenderY(double guiY) {
        return guiY * minecraft.getWindow().getHeight()
            / Math.max(1.0, minecraft.getWindow().getGuiScaledHeight());
    }

    private TerrainPick pickAt(double guiX, double guiY) {
        double renderX = toRenderX(guiX);
        double renderY = toRenderY(guiY);
        double centerX = minecraft.getWindow().getWidth() / 2.0;
        double centerY = minecraft.getWindow().getHeight() / 2.0;
        StandaloneWaypoint waypoint = null;
        if (config.booleanValue("waypoint-layer", true)) {
            List<WaypointView> waypointViews = config.waypoints(seed, dimension).stream()
                .map(candidate -> new WaypointView(candidate, renderedWaypoint(candidate)))
                .toList();
            SeedWaypoint picked = renderer.pickWaypoint(camera, renderX, renderY,
                waypointViews.stream().map(WaypointView::rendered).toList(), centerX, centerY);
            waypoint = standaloneWaypoint(picked, waypointViews);
        }
        ChestLootOutput chest = waypoint == null
            ? renderer.pickChest(camera, renderX, renderY, selection.chests(), centerX, centerY)
            : null;
        GeneratedStructure structure = waypoint == null && chest == null
            && config.booleanValue("structure-layer", true)
            ? renderer.pickStructure(camera, renderX, renderY, visibleStructures(),
                pickContext(renderX, renderY), centerX, centerY)
            : null;
        return new TerrainPick(waypoint, chest, structure);
    }

    private void openContextMenu(double mouseX, double mouseY) {
        TerrainPick picked = pickAt(mouseX, mouseY);
        if (picked.waypoint() != null) {
            selectWaypoint(picked.waypoint(), false);
        } else if (picked.structure() != null) {
            selectStructure(picked.structure(), false);
        } else if (picked.chest() != null) {
            selection.selectChest(picked.chest());
        } else {
            selection.select(null);
            selectedWaypoint = null;
            updateActionButtons();
        }

        GeneratedStructure structure = selection.structure();
        boolean hasStructure = structure != null;
        boolean hasWaypoint = selectedWaypoint != null;
        boolean waypointExists = hasStructure
            && config.hasWaypointAt(seed, dimension, structure.x, structure.z);
        List<ContextAction> actions = new ArrayList<>();
        actions.add(new ContextAction(hasWaypoint ? "Edit waypoint"
            : hasStructure && waypointExists ? "Unpin waypoint" : "Add waypoint",
            this::waypointAction));
        if (hasStructure || hasWaypoint) {
            actions.add(new ContextAction("View in 3D", this::focusSelection));
        }
        if (hasStructure) {
            actions.add(new ContextAction("Inspect loot", this::openDetails));
        }
        contextActions = List.copyOf(actions);
        contextWidth = actions.stream().mapToInt(action -> font.width(action.label()) + 16)
            .max().orElse(130);
        contextX = Math.max(2, Math.min((int) mouseX, width - contextWidth - 2));
        contextY = Math.max(contentTop() + 2,
            Math.min((int) mouseY, height - actions.size() * 22 - 2));
    }

    private boolean handleContextMenuClick(MouseButtonEvent click) {
        if (contextActions.isEmpty()) return false;
        int row = (int) ((click.y() - contextY) / 22);
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && click.x() >= contextX
            && click.x() < contextX + contextWidth && click.y() >= contextY
            && row >= 0 && row < contextActions.size()) {
            Runnable action = contextActions.get(row).command();
            contextActions = List.of();
            action.run();
            return true;
        }
        contextActions = List.of();
        return click.button() == InputConstants.MOUSE_BUTTON_LEFT
            || click.button() == InputConstants.MOUSE_BUTTON_RIGHT;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (handleContextMenuClick(click)) return true;
        if (super.mouseClicked(click, doubled)) return true;
        if ((click.button() == InputConstants.MOUSE_BUTTON_LEFT
            || click.button() == InputConstants.MOUSE_BUTTON_RIGHT)
            && click.y() > contentTop()) {
            setFocused(null);
            if (click.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                dragButton = InputConstants.MOUSE_BUTTON_RIGHT;
                rightDragDistance = 0.0;
                rightPressX = click.x();
                rightPressY = click.y();
                return true;
            }
            if ((selection.structure() != null || selectedWaypoint != null)
                && click.x() >= width - 350
                && click.x() <= width - 12 && click.y() >= contentTop() + 10
                && click.y() < contentTop() + 96) {
                if (click.button() == InputConstants.MOUSE_BUTTON_LEFT
                    && !selection.chests().isEmpty()) {
                    int selectedIndex = selection.selectedChestIndex();
                    selection.selectChest(selectedIndex < 0 ? 0
                        : Math.floorMod(selectedIndex + 1, selection.chests().size()));
                }
                return true;
            }
            TerrainPick picked = pickAt(click.x(), click.y());
            if (picked.waypoint() != null) {
                selectWaypoint(picked.waypoint(), true);
                return true;
            }
            if (picked.chest() != null) {
                selection.selectChest(picked.chest());
                if (doubled) openDetails();
                return true;
            }
            if (picked.structure() != null) {
                selectStructure(picked.structure(), true);
                return true;
            }
            dragButton = InputConstants.MOUSE_BUTTON_LEFT;
            selection.select(null);
            selectedWaypoint = null;
            releaseFocusForManualControl();
            updateActionButtons();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (click.button() == dragButton) {
            int releasedButton = dragButton;
            dragButton = -1;
            if (releasedButton == InputConstants.MOUSE_BUTTON_RIGHT
                && rightDragDistance < 3.0) {
                openContextMenu(rightPressX, rightPressY);
            }
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (click.button() == dragButton && (dragButton == InputConstants.MOUSE_BUTTON_LEFT
            || dragButton == InputConstants.MOUSE_BUTTON_RIGHT)) {
            releaseFocusForManualControl();
            if (dragButton == InputConstants.MOUSE_BUTTON_RIGHT) {
                rightDragDistance += Math.hypot(deltaX, deltaY);
            }
            double scaleX = minecraft.getWindow().getWidth()
                / Math.max(1.0, minecraft.getWindow().getGuiScaledWidth());
            double scaleY = minecraft.getWindow().getHeight()
                / Math.max(1.0, minecraft.getWindow().getGuiScaledHeight());
            camera.mouseDrag(deltaX * scaleX * 0.25, deltaY * scaleY * 0.25);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        releaseFocusForManualControl();
        camera.scroll(verticalAmount);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent key) {
        int code = key.keycode();
        if (code == KEY_ESCAPE && !contextActions.isEmpty()) {
            contextActions = List.of();
            return true;
        }
        if (searchField != null && searchField.isFocused()) {
            if (code == KEY_ENTER || code == KEY_NUMPAD_ENTER) {
                navigateMatch(1);
                searchField.setFocused(false);
                return true;
            }
            return super.keyPressed(key);
        }
        if (code == KEY_F) {
            if (selection.structure() != null) openDetails();
            return true;
        }
        if (code == KEY_T) {
            teleportSelection();
            return true;
        }
        if (code == KEY_ESCAPE) {
            if (focusActive) returnFromFocus();
            else onClose();
            return true;
        }
        if (isMovementKey(code)) {
            heldMovementKeys.add(code);
            return true;
        }
        return super.keyPressed(key);
    }

    @Override
    public boolean keyReleased(KeyEvent key) {
        int code = key.keycode();
        boolean movementKey = isMovementKey(code);
        if (movementKey) heldMovementKeys.remove(code);
        if (searchField != null && searchField.isFocused()) return super.keyReleased(key);
        if (movementKey) return true;
        return super.keyReleased(key);
    }

    private static boolean isMovementKey(int key) {
        return key == KEY_W || key == KEY_S || key == KEY_A || key == KEY_D
            || key == KEY_SPACE || key == KEY_C || key == KEY_LEFT_SHIFT
            || key == KEY_RIGHT_SHIFT || key == KEY_LEFT_CONTROL || key == KEY_RIGHT_CONTROL;
    }

    private MapRenderContext pickContext(double mouseX, double mouseY) {
        int framebufferWidth = minecraft.getWindow().getWidth();
        int framebufferHeight = minecraft.getWindow().getHeight();
        EnumSet<MapLayer> layers = EnumSet.of(MapLayer.STRUCTURES);
        return new MapRenderContext(MapViewport.create(camera.targetX(), camera.targetZ(), 1.0,
            framebufferWidth, framebufferHeight), (int) Math.round(mouseX), (int) Math.round(mouseY),
            0.0f, dimension, layers, false, 0, 0, 255, 55,
            1.0, 1.0, 1.0, false, false, null, null);
    }

    @Override
    public void tick() {
        super.tick();
        camera.tick();
        if (focusActive && !camera.isAnimating()) {
            TerrainFocusTarget target = focusedStructure != null
                ? renderer.structureFocusTarget(focusedStructure)
                : focusedWaypoint != null
                    ? renderer.waypointFocusTarget(renderedWaypoint(focusedWaypoint)) : null;
            if (target != null) {
                orbitYawDegrees += 0.35;
                camera.viewOf(target.x(), target.y(), target.z(), target.distance(),
                    55.0, orbitYawDegrees);
            }
        }
    }

    @Override
    public void onClose() {
        heldMovementKeys.clear();
        dragButton = -1;
        contextActions = List.of();
        camera.stopFlight();
        if (bulkLoading) renderer.stopBulkLoading();
        bulkLoading = false;
        if (parent instanceof StandaloneSeedExplorerScreen map) {
            map.accept3DState(dimension, camera.targetX(), camera.targetZ(),
                selection.structure(), selectedWaypoint, selection.chests());
        }
        selection.close();
        activeStructureRequest = -1L;
        config.save();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void removed() {
        heldMovementKeys.clear();
        dragButton = -1;
        contextActions = List.of();
        if (terrainTarget != null) {
            terrainTarget.destroyBuffers();
            terrainTarget = null;
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record CameraState(double x, double y, double z, double distance,
                               double pitchDegrees, double yawDegrees) {
    }

    private record WaypointView(StandaloneWaypoint waypoint, SeedWaypoint rendered) {
    }

    private record TerrainPick(StandaloneWaypoint waypoint, ChestLootOutput chest,
                               GeneratedStructure structure) {
    }

    private record ContextAction(String label, Runnable command) {
    }
}
