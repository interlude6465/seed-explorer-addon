package me.seedexplorer.standalone;

import com.mojang.blaze3d.platform.InputConstants;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.StandaloneStructureLootPredictor;
import me.seedexplorer.addon.ore.OrePatch;
import me.seedexplorer.addon.ore.OrePredictor;
import me.seedexplorer.addon.ore.OreType;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.PredictedBiome;
import me.seedexplorer.addon.worldgen.PredictionContext;
import me.seedexplorer.addon.worldgen.PredictionVersionPolicy;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Meteor-free seed map backed by vanilla worldgen and structure placement. */
public final class StandaloneSeedExplorerScreen extends Screen {
    private static final int CELL_SIZE = 8;
    private static final ThreadPoolExecutor GENERATOR = new ThreadPoolExecutor(1, 1,
        30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "Seed Explorer map generation");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final ThreadPoolExecutor STRUCTURE_GENERATOR = new ThreadPoolExecutor(1, 1,
        30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "Seed Explorer map structures");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final ThreadPoolExecutor LOOT_GENERATOR = new ThreadPoolExecutor(1, 1,
        30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "Seed Explorer loot prediction");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final AtomicLong LOOT_REQUESTS = new AtomicLong();
    private static final ThreadPoolExecutor ORE_GENERATOR = new ThreadPoolExecutor(1, 1,
        30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "Seed Explorer ore prediction");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final AtomicLong ORE_REQUESTS = new AtomicLong();
    private static final int MAX_LOADED_TERRAIN_SAMPLES = 512;
    private static final java.util.Map<BiomeKey, Integer> BIOME_COLORS = new java.util.LinkedHashMap<>(4096, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<BiomeKey, Integer> eldest) {
            return size() > 32_768;
        }
    };
    private static final int MAX_ORE_CHUNKS = 256;
    private static final int MAX_MAP_ORE_MARKERS = 8_000;

    private final Screen parent;
    private final StandaloneSeedExplorerConfig config;
    private final StandaloneNavigationCapability navigation = new ReflectiveBaritoneNavigation();
    private EditBox seedField;
    private Button dimensionButton;
    private Button versionButton;
    private Button structureFilterButton;
    private Button deleteWaypointButton;
    private StructureType structureFilter;
    private final Set<StructureType> selectedStructureFilters = new LinkedHashSet<>();
    private GeneratedStructure selectedStructure;
    private StandaloneWaypoint selectedWaypoint;
    private volatile List<ChestLootOutput> selectedLoot = List.of();
    private volatile boolean lootLoading;
    private volatile boolean lootSupported;
    private volatile String lootError = "";
    private int dimension;
    private double centerX;
    private double centerZ;
    private double blocksPerPixel = 8.0;
    private boolean initialPositionSet;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;
    private double pressMouseX, pressMouseY;
    private boolean dragMoved;
    private boolean zoomRequestPending;
    private List<MenuAction> contextActions = List.of();
    private int contextX, contextY, contextWidth;
    private int contextScrollOffset;
    private static final int CONTEXT_MENU_VISIBLE_ROWS = 12;
    private volatile MapResult result;
    // One replaceable publication: fast producers cannot queue old cumulative snapshots on the UI.
    private final java.util.concurrent.atomic.AtomicReference<MapPublication> pendingMap =
        new java.util.concurrent.atomic.AtomicReference<>();
    private PredictionContext resultContext;
    private volatile OreResult oreResult;
    private volatile long activeRequest;
    private volatile String error = "";
    private int toolbarX, toolbarY, mapTop = 94;
    private int refreshTicks;
    private int structureRegionsDone, structureRegionsTotal;
    private volatile String oreStatus = "";
    private boolean biomeRequestPending;
    private long lastMapRequestNanos;
    private List<GeneratedStructure> markerSource;
    private int markerCell = -1;
    private List<StandaloneMapPresentation.Marker> mapMarkers = List.of();
    private long lastMapDrawNanos;
    private int lastDrawnMarkers, lastBiomeDrawCalls;

    public StandaloneSeedExplorerScreen(Screen parent, StandaloneSeedExplorerConfig config) {
        super(Component.literal("Seed Explorer"));
        this.parent = parent;
        this.config = config;
        dimension = 0;
    }

    @Override
    protected void init() {
        dragging = false;
        setDragging(false);
        contextActions = List.of();
        if (!initialPositionSet) {
            dimension = config.mapDimension(minecraft.level == null ? 0 : playerDimension());
            restoreStructureFilters();
            centerOnPlayer();
            initialPositionSet = true;
        }
        toolbarX = 10; toolbarY = 10;
        int rowY = 0;
        boolean compact = width < 700;
        seedField = toolbar(new EditBox(font, 0, 0, compact ? 132 : 180, 20,
            Component.literal("World seed")));
        seedField.setMaxLength(32);
        seedField.setValue(Long.toString(effectiveSeed()));
        toolbar(Button.builder(Component.literal("Apply seed"), button -> applySeed())
            .bounds(196, rowY, compact ? 56 : 82, 20).build());
        dimensionButton = toolbar(Button.builder(dimensionName(), button -> openDimensionMenu())
            .bounds(284, rowY, compact ? 70 : 90, 20).build());
        versionButton = toolbar(Button.builder(versionName(), button -> openVersionMenu())
            .bounds(462, rowY, compact ? 52 : 72, 20).build());
        toolbar(Button.builder(Component.literal("Back"), button -> onClose())
            .bounds(width - 82, rowY, compact ? 48 : 72, 20).build());
        structureFilterButton = toolbar(Button.builder(structureFilterName(),
            button -> openStructureMenu()).bounds(10, 38, compact ? 160 : 190, 20).build());
        toolbar(Button.builder(Component.literal("Open 3D"), button -> open3d())
            .bounds(198, 66, compact ? 66 : 82, 20).build());
        toolbar(Button.builder(Component.literal("Search"), button -> openSearch())
            .bounds(286, 66, compact ? 60 : 66, 20).build());
        toolbar(Button.builder(Component.literal("Actions"), button -> openActions())
            .bounds(0, 0, 68, 20).build());
        Button settingsButton = Button.builder(Component.literal("\u2699"), button ->
            minecraft.gui.setScreen(new StandaloneSeedExplorerSettingsScreen(this, config)))
            .bounds(width - 34, 38, 24, 20).build();
        settingsButton.setTooltip(Tooltip.create(Component.literal("Seed Explorer settings")));
        toolbar(settingsButton);
        mapTop = toolbarY + 28;
        updateWaypointButtons();
        blocksPerPixel = clampScale(blocksPerPixel);
        requestMap();
        if (selectedStructure != null && selectedLoot.isEmpty() && !lootLoading) {
            requestSelectedLoot(selectedStructure, parseSeedOrEffective());
        }
    }

    private <T extends AbstractWidget> T toolbar(T widget) {
        widget.setWidth(Math.min(widget.getWidth(), Math.max(20, width - 20)));
        if (toolbarX + widget.getWidth() > width - 10 && toolbarX > 10) {
            toolbarX = 10; toolbarY += 26;
        }
        widget.setX(toolbarX); widget.setY(toolbarY);
        toolbarX += widget.getWidth() + 6;
        return addRenderableWidget(widget);
    }

    private int selectionX() { return selectedStructure != null ? selectedStructure.x
        : selectedWaypoint != null ? selectedWaypoint.x() : (int)Math.floor(centerX); }
    private int selectionZ() { return selectedStructure != null ? selectedStructure.z
        : selectedWaypoint != null ? selectedWaypoint.z() : (int)Math.floor(centerZ); }
    private void copySelection() {
        minecraft.keyboardHandler.setClipboard(selectionX() + " " + selectionZ());
    }
    private void teleportSelection() {
        if (minecraft.player == null || minecraft.player.connection == null) return;
        int y = selectedStructure != null && selectedStructure.y != 0 ? selectedStructure.y : 100;
        String target = (selectionX() + 0.5) + " " + y + " " + (selectionZ() + 0.5);
        String dim = dimension == -1 ? "minecraft:the_nether" : dimension == 1 ? "minecraft:the_end" : "minecraft:overworld";
        minecraft.player.connection.sendCommand(dimension == playerDimension() ? "tp " + target
            : "execute in " + dim + " run tp @s " + target);
    }

    private void openSearch() {
        minecraft.gui.setScreen(new StandaloneSearchScreen(this, config,
            new PredictionContext(parseSeedOrEffective(), config.predictionVersion(), dimension),
            (int) Math.floor(centerX), (int) Math.floor(centerZ), this::selectSearchResult));
    }

    private void openVisibility() {
        minecraft.gui.setScreen(new StandaloneStructureFilterScreen(this, config));
    }

    private void editSelectedWaypoint() {
        if (selectedWaypoint == null) return;
        minecraft.gui.setScreen(new StandaloneWaypointEditScreen(
            this, config, selectedWaypoint, this::selectWaypoint));
    }

    private void navigateSelection() {
        if (minecraft.player == null || dimension != playerDimension()) {
            error = "Enter the target dimension before navigating.";
            return;
        }
        int y = selectedStructure != null && selectedStructure.y != GeneratedStructure.UNKNOWN_Y
            ? selectedStructure.y : minecraft.player.blockPosition().getY();
        error = navigation.navigate(new BlockPos(selectionX(), y, selectionZ()), 2).message();
    }

    private void inspectSelection() {
        if (selectedStructure == null) return;
        minecraft.gui.setScreen(new StandaloneStructureDetailScreen(this, config,
            new PredictionContext(parseSeedOrEffective(), config.predictionVersion(), dimension),
            selectedStructure, lootLoading || selectedLoot.isEmpty() ? null : selectedLoot, 0));
    }

    private void toggleCompletedSelection() {
        if (selectedStructure == null) return;
        config.toggleCompleted(dimension, selectedStructure.type.name(),
            selectedStructure.x, selectedStructure.z);
    }

    private void openActions() {
        boolean hasWaypoint = selectedWaypoint != null;
        boolean hasStructure = selectedStructure != null;
        boolean connected = minecraft.player != null && minecraft.player.connection != null;
        boolean completed = hasStructure && config.completed(dimension,
            selectedStructure.type.name(), selectedStructure.x, selectedStructure.z);
        List<StandaloneMapActionsScreen.Action> actions = List.of(
            new StandaloneMapActionsScreen.Action("Recenter", true, this::recenter),
            new StandaloneMapActionsScreen.Action("Visibility", true, this::openVisibility),
            new StandaloneMapActionsScreen.Action("Add waypoint", result != null,
                this::addWaypointAtSelectionOrCenter),
            new StandaloneMapActionsScreen.Action("Edit waypoint", hasWaypoint,
                this::editSelectedWaypoint),
            new StandaloneMapActionsScreen.Action("Delete waypoint", hasWaypoint,
                this::deleteSelectedWaypoint),
            new StandaloneMapActionsScreen.Action("Copy coordinates", true, this::copySelection),
            new StandaloneMapActionsScreen.Action("Teleport", connected, this::teleportSelection),
            new StandaloneMapActionsScreen.Action("Navigate",
                connected && dimension == playerDimension() && navigation.available(),
                this::navigateSelection),
            new StandaloneMapActionsScreen.Action("Inspect loot", hasStructure,
                this::inspectSelection),
            new StandaloneMapActionsScreen.Action(completed ? "Mark incomplete" : "Mark completed",
                hasStructure, this::toggleCompletedSelection));
        minecraft.gui.setScreen(new StandaloneMapActionsScreen(this, actions));
    }

    private long effectiveSeed() {
        if (config.hasSeed()) return config.seed();
        try {
            if (minecraft == null || !minecraft.hasSingleplayerServer()) return 0L;
            Object server = minecraft.getClass().getMethod("getSingleplayerServer").invoke(minecraft);
            if (server == null) return 0L;
            Object overworld = server.getClass().getMethod("overworld").invoke(server);
            Object seed = overworld.getClass().getMethod("getSeed").invoke(overworld);
            if (seed instanceof Number number) return number.longValue();
        } catch (Throwable ignored) {
            // Keep the editable zero-seed default outside an integrated world.
        }
        return 0L;
    }

    private Component dimensionName() {
        return Component.literal(switch (dimension) {
            case -1 -> "Nether";
            case 1 -> "End";
            default -> "Overworld";
        });
    }

    private Component versionName() {
        return Component.literal(config.predictionVersion());
    }

    private void cycleVersion() {
        String current = config.predictionVersion();
        List<String> versions = StandaloneSeedExplorerConfig.supportedVersions();
        String next = versions.get((versions.indexOf(current) + 1) % versions.size());
        config.setPredictionVersion(next);
        config.save();
        result = null;
        if (structureFilter != null && !PredictionVersionPolicy.supportsStructure(
            next, structureFilter)) structureFilter = null;
        selectedStructure = null;
        selectWaypoint(null);
        clearSelectedLoot();
        versionButton.setMessage(versionName());
        structureFilterButton.setMessage(structureFilterName());
        requestMap();
    }

    private Component structureFilterName() {
        if (selectedStructureFilters.isEmpty()) return Component.literal("Structures: Major");
        if (selectedStructureFilters.size() == 1) {
            return Component.literal("Structures: " + selectedStructureFilters.iterator().next().displayName);
        }
        return Component.literal("Structures: " + selectedStructureFilters.size() + " selected");
    }

    private void openStructureMenu() {
        List<MenuAction> actions = new ArrayList<>();
        for (StructureType type : availableStructureTypes()) {
            boolean selected = selectedStructureFilters.contains(type);
            actions.add(new MenuAction((selected ? "[x] " : "[ ] ") + type.displayName,
                () -> {
                    int scroll = contextScrollOffset;
                    if (!selectedStructureFilters.remove(type)) selectedStructureFilters.add(type);
                    structureFilter = selectedStructureFilters.size() == 1
                        ? selectedStructureFilters.iterator().next() : null;
                    finishFilterChange();
                    openStructureMenu();
                    contextScrollOffset = scroll;
                }));
        }
        actions.add(new MenuAction("Reset structures", () -> {
            selectedStructureFilters.clear();
            structureFilter = null;
            finishFilterChange();
        }));
        showToolbarMenu(actions, structureFilterButton);
    }

    private void openVersionMenu() {
        List<MenuAction> actions = new ArrayList<>();
        for (String version : StandaloneSeedExplorerConfig.supportedVersions()) {
            actions.add(new MenuAction((version.equals(config.predictionVersion()) ? "[x] " : "[ ] ") + version,
                () -> setVersionFromMenu(version)));
        }
        actions.add(new MenuAction("Reset version", () -> setVersionFromMenu("26.3")));
        showToolbarMenu(actions, versionButton);
    }

    private void setVersionFromMenu(String version) {
        config.setPredictionVersion(version);
        config.save();
        selectedStructureFilters.removeIf(type -> !PredictionVersionPolicy.supportsStructure(version, type));
        structureFilter = selectedStructureFilters.size() == 1
            ? selectedStructureFilters.iterator().next() : null;
        versionButton.setMessage(versionName());
        finishFilterChange();
    }

    private void openDimensionMenu() {
        List<MenuAction> actions = new ArrayList<>();
        for (int target : new int[] {0, -1, 1}) {
            String label = switch (target) { case -1 -> "Nether"; case 1 -> "End"; default -> "Overworld"; };
            actions.add(new MenuAction((target == dimension ? "[x] " : "[ ] ") + label,
                () -> setDimensionFromMenu(target)));
        }
        actions.add(new MenuAction("Reset dimension", () -> setDimensionFromMenu(0)));
        showToolbarMenu(actions, dimensionButton);
    }

    private void setDimensionFromMenu(int target) {
        if (target == dimension) {
            contextActions = List.of();
            return;
        }
        saveMapOptions();
        dimension = target;
        restoreStructureFilters();
        dimensionButton.setMessage(dimensionName());
        finishFilterChange();
    }

    private void finishFilterChange() {
        saveMapOptions();
        result = null;
        selectedStructure = null;
        selectWaypoint(null);
        clearSelectedLoot();
        structureFilterButton.setMessage(structureFilterName());
        requestMap();
    }

    private void restoreStructureFilters() {
        selectedStructureFilters.clear();
        for (StructureType type : availableStructureTypes())
            if (config.mapStructureFilters(dimension).contains(type.name())) selectedStructureFilters.add(type);
        structureFilter = selectedStructureFilters.size() == 1 ? selectedStructureFilters.iterator().next() : null;
    }

    private void saveMapOptions() {
        config.setMapDimension(dimension);
        config.setMapStructureFilters(dimension, selectedStructureFilters.stream()
            .map(Enum::name).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        config.save();
    }

    private void showToolbarMenu(List<MenuAction> actions, AbstractWidget anchor) {
        contextActions = List.copyOf(actions);
        contextScrollOffset = 0;
        contextWidth = Math.max(150, actions.stream().mapToInt(action -> font.width(action.label()) + 16).max().orElse(150));
        contextX = Math.max(2, Math.min(anchor.getX(), width - contextWidth - 2));
        contextY = Math.min(anchor.getY() + anchor.getHeight() + 2,
            Math.max(2, height - visibleMenuRows() * 22 - 2));
    }

    private int visibleMenuRows() {
        return Math.min(contextActions.size(), Math.max(1, Math.min(CONTEXT_MENU_VISIBLE_ROWS, (height - 4) / 22)));
    }

    private void cycleStructureFilter() {
        List<StructureType> available = availableStructureTypes();
        if (available.isEmpty()) {
            structureFilter = null;
        } else if (structureFilter == null) {
            structureFilter = available.get(0);
        } else {
            int index = available.indexOf(structureFilter);
            structureFilter = index < 0 || index + 1 >= available.size()
                ? null : available.get(index + 1);
        }
        selectedStructure = null;
        selectWaypoint(null);
        clearSelectedLoot();
        structureFilterButton.setMessage(structureFilterName());
        requestMap();
    }

    private List<StructureType> availableStructureTypes() {
        List<StructureType> available = new ArrayList<>();
        for (StructureType type : StructureType.values()) {
            if (type != StructureType.DUNGEON
                && type.dimension == dimension && type.hasMapPrediction()
                && PredictionVersionPolicy.supportsStructure(config.predictionVersion(), type)) {
                available.add(type);
            }
        }
        return available;
    }

    private void applySeed() {
        try {
            long seed = Long.parseLong(seedField.getValue().trim());
            config.setSeed(seed);
            config.save();
            result = null;
            selectedStructure = null;
            selectWaypoint(null);
            clearSelectedLoot();
            error = "";
            requestMap();
        } catch (NumberFormatException ignored) {
            error = "Enter a valid 64-bit seed";
        }
    }

    private void cycleDimension() {
        result = null;
        dimension = dimension == 0 ? -1 : dimension == -1 ? 1 : 0;
        selectedStructureFilters.clear();
        structureFilter = null;
        selectedStructure = null;
        selectWaypoint(null);
        clearSelectedLoot();
        dimensionButton.setMessage(dimensionName());
        structureFilterButton.setMessage(structureFilterName());
        requestMap();
    }

    private void recenter() {
        centerOnPlayer();
        blocksPerPixel = 8.0;
        requestMap();
    }

    private void open3d() {
        MapResult snapshot = result;
        List<GeneratedStructure> structures = snapshot == null ? List.of() : snapshot.structures;
        minecraft.gui.setScreen(new StandaloneTerrain3DScreen(
            this, config, parseSeedOrEffective(), dimension, structures)
                .openAt(centerX, centerZ, selectedStructure,
                    lootLoading || selectedLoot.isEmpty() ? null : selectedLoot, 0)
                .selectInitialWaypoint(selectedWaypoint));
    }

    void accept3DState(int selectedDimension, double x, double z,
                       GeneratedStructure structure, StandaloneWaypoint waypoint,
                       List<ChestLootOutput> knownLoot) {
        if (dimension != selectedDimension) {
            saveMapOptions();
            dimension = selectedDimension;
            restoreStructureFilters();
        }
        centerX = x;
        centerZ = z;
        selectedStructure = structure;
        selectedWaypoint = structure == null ? waypoint : null;
        LOOT_REQUESTS.incrementAndGet();
        lootLoading = false;
        lootError = "";
        selectedLoot = structure == null || knownLoot == null
            ? List.of() : List.copyOf(knownLoot);
    }

    private void selectSearchResult(StandaloneSearch.Result found) {
        dimension = found.dimension();
        centerX = found.x(); centerZ = found.z();
        dimensionButton.setMessage(dimensionName());
        selectedStructure = found.structure();
        selectedStructureFilters.clear();
        if (selectedStructure != null) selectedStructureFilters.add(selectedStructure.type);
        structureFilter = selectedStructure == null ? null : selectedStructure.type;
        structureFilterButton.setMessage(structureFilterName());
        selectWaypoint(null);
        blocksPerPixel = clampScale(2.0);
        clearSelectedLoot();
        if (selectedStructure != null) requestSelectedLoot(selectedStructure, parseSeedOrEffective());
        requestMap();
    }

    private void centerOnPlayer() {
        if (minecraft != null && minecraft.player != null) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
        } else {
            centerX = 0;
            centerZ = 0;
        }
    }

    private int mapTop() {
        return mapTop;
    }

    private void requestMap() {
        if (width <= 0 || height <= mapTop()) return;
        long request = REQUESTS.incrementAndGet();
        activeRequest = request;
        zoomRequestPending = false;
        pendingMap.set(null);
        biomeRequestPending = true;
        lastMapRequestNanos = System.nanoTime();
        structureRegionsDone = structureRegionsTotal = 0;
        error = "";

        int requestDimension = dimension;

        int visibleColumns = Math.max(2, (width - 20 + CELL_SIZE - 1) / CELL_SIZE);
        int visibleRows = Math.max(2, (height - mapTop() - 10 + CELL_SIZE - 1) / CELL_SIZE);
        double step = blocksPerPixel * CELL_SIZE;
        int extraPadding = Math.min(16, (int) Math.ceil(config.intValue("biome-tile-margin", 2, 0, 16) * 16.0 / step));
        int columns = ((visibleColumns + 1) / 2) * 2 + 2 * (StandaloneMapPresentation.paddingCells(visibleColumns) + extraPadding);
        int rows = ((visibleRows + 1) / 2) * 2 + 2 * (StandaloneMapPresentation.paddingCells(visibleRows) + extraPadding);
        // A stable sample grid makes overlapping windows reuse biome cache entries.
        double requestCenterX = Math.floor(centerX / step) * step;
        double requestCenterZ = Math.floor(centerZ / step) * step;
        int minBlockX = (int) Math.floor(requestCenterX - columns * step / 2.0);
        int minBlockZ = (int) Math.floor(requestCenterZ - rows * step / 2.0);
        int generationMargin = config.intValue("generation-margin", 10, 0, 48);
        int minChunkX = Math.floorDiv(minBlockX, 16) - generationMargin;
        int minChunkZ = Math.floorDiv(minBlockZ, 16) - generationMargin;
        int maxChunkX = Math.floorDiv((int) Math.ceil(requestCenterX + columns * step / 2.0), 16)
            + generationMargin;
        int maxChunkZ = Math.floorDiv((int) Math.ceil(requestCenterZ + rows * step / 2.0), 16)
            + generationMargin;
        long seed = parseSeedOrEffective();
        boolean requestTreasure = selectedStructureFilters.contains(StructureType.TREASURE);
        boolean requestMineshaft = selectedStructureFilters.contains(StructureType.MINESHAFT);
        PredictionContext context = new PredictionContext(seed, config.predictionVersion(), requestDimension);
        if (!context.equals(resultContext)) result = null;
        resultContext = context;
        LoadedTerrainOverlay loadedTerrain = captureLoadedTerrain(
            columns, rows, requestCenterX, requestCenterZ, step, requestDimension);

        requestOres(request, seed, requestDimension, requestCenterX, requestCenterZ);
        MapResult previousMap = result;

        GENERATOR.execute(() -> {
            try {
                int[] colors = new int[columns * rows];
                for (int row = 0; row < rows; row++) {
                    if (Thread.currentThread().isInterrupted() || REQUESTS.get() != request) return;
                    int z = (int) Math.floor(requestCenterZ + (row - rows / 2.0 + 0.5) * step);
                    for (int col = 0; col < columns; col++) {
                        int x = (int) Math.floor(requestCenterX + (col - columns / 2.0 + 0.5) * step);
                        colors[row * columns + col] = biomeColor(seed, requestDimension, x, z);
                    }
                }
                List<GeneratedStructure> retained = previousMap == null ? List.of()
                    : StandaloneMapPresentation.retainWindow(previousMap.structures,
                        minChunkX, minChunkZ, maxChunkX, maxChunkZ);
                if (REQUESTS.get() == request) publishMap(new MapPublication(
                    new MapResult(request, columns, rows, colors, retained,
                        StandaloneMapPresentation.markers(retained, step / CELL_SIZE),
                        requestCenterX, requestCenterZ, step, seed, loadedTerrain, false), 0, 0));
                STRUCTURE_GENERATOR.execute(() -> {
                    try {
                        StandalonePredictionCache.predictDimensionProgressive(
                            context, minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                            requestTreasure, requestMineshaft,
                            () -> REQUESTS.get() != request, progress -> {
                                if (REQUESTS.get() != request) return;
                                // Merge only the prior window, not every growing intermediate snapshot.
                                // All scans/allocations and grouping stay on this worker.
                                var structures = progress.complete() ? progress.structures()
                                    : mergeStructures(retained, progress.structures());
                                var markers = StandaloneMapPresentation.markers(structures, step / CELL_SIZE);
                                if (REQUESTS.get() == request) publishMap(new MapPublication(
                                    new MapResult(request, columns, rows, colors, structures, markers,
                                        requestCenterX, requestCenterZ, step, seed, loadedTerrain, progress.complete()),
                                    progress.completedRegions(), progress.totalRegions()));
                            });
                    } catch (Throwable failure) {
                        if (REQUESTS.get() == request) {
                            error = "Structure prediction failed: "
                                + failure.getClass().getSimpleName();
                        }
                    }
                });
            } catch (Throwable failure) {
                minecraft.execute(() -> {
                    if (REQUESTS.get() == request) {
                        biomeRequestPending = false;
                        error = "Map generation failed: " + failure.getClass().getSimpleName();
                    }
                });
            }
        });
    }

    private void publishMap(MapPublication publication) {
        pendingMap.updateAndGet(previous -> previous == null
            || previous.map.request <= publication.map.request ? publication : previous);
    }

    private void applyMapPublication() {
        MapPublication publication = pendingMap.getAndSet(null);
        if (publication == null || publication.map.request != activeRequest
            || REQUESTS.get() != activeRequest) return;
        result = publication.map;
        biomeRequestPending = false;
        structureRegionsDone = publication.completed;
        structureRegionsTotal = publication.total;
        markerSource = result.structures;
        markerCell = StandaloneMapPresentation.fossilCellSize(result.step / CELL_SIZE);
        mapMarkers = result.markers;
    }

    private long parseSeedOrEffective() {
        try {
            return Long.parseLong(seedField.getValue().trim());
        } catch (RuntimeException ignored) {
            return effectiveSeed();
        }
    }

    private static int toArgb(int abgr) {
        return (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >>> 16) & 0xFF);
    }

    private static int biomeColor(long seed, int dimension, int x, int z) {
        BiomeKey key = new BiomeKey(seed, dimension, x, z);
        return BIOME_COLORS.computeIfAbsent(key,
            ignored -> toArgb(WorldgenEngine.getBiome(seed, dimension, x, 64, z).mapColorAbgr()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        applyMapPublication();
        long drawStart = System.nanoTime();
        lastDrawnMarkers = lastBiomeDrawCalls = 0;
        graphics.fill(0, 0, width, height, 0xFF101619);
        if (config.booleanValue("terrain-layer", true)) {
            graphics.fill(0, mapTop() - 1, width, height, 0xFF19221F);
        }

        MapResult snapshot = result;
        if (snapshot == null) {
            graphics.centeredText(font, Component.literal(error.isEmpty() ? "Generating map..." : error),
                width / 2, mapTop() + 20, error.isEmpty() ? 0xFFD5DED8 : 0xFFFF9388);
        } else {
            int left = 10;
            int top = mapTop();
            int offsetX = panOffsetX(snapshot), offsetY = panOffsetY(snapshot);
            graphics.enableScissor(left, top, width - 10, height - 10);
            graphics.pose().pushMatrix();
            graphics.pose().translate(offsetX, offsetY);
            if (config.booleanValue("biome-layer", true)) {
                int opacity = config.intValue("biome-opacity", 255, 40, 255);
                int rasterX = rasterLeft(snapshot), rasterY = rasterTop(snapshot);
                double cellSize = rasterCellSize(snapshot);
                int firstRow = Math.max(0, (int) Math.floor((top - offsetY - rasterY) / cellSize));
                int lastRow = Math.min(snapshot.rows, (int) Math.floor((height - 10 - offsetY - rasterY) / cellSize) + 1);
                int firstCol = Math.max(0, (int) Math.floor((left - offsetX - rasterX) / cellSize));
                int lastCol = Math.min(snapshot.columns, (int) Math.floor((width - 10 - offsetX - rasterX) / cellSize) + 1);
                for (int row = firstRow; row < lastRow; row++) {
                    int y0 = rasterY + (int) Math.round(row * cellSize);
                    for (int col = firstCol; col < lastCol;) {
                        int start = col++;
                        int color = (opacity << 24)
                            | (snapshot.colors[row * snapshot.columns + start] & 0x00FFFFFF);
                        while (col < lastCol && snapshot.colors[row * snapshot.columns + col]
                            == snapshot.colors[row * snapshot.columns + start]) col++;
                        graphics.fill(rasterX + (int) Math.round(start * cellSize), y0,
                            rasterX + (int) Math.round(col * cellSize),
                            rasterY + (int) Math.round((row + 1) * cellSize), color);
                        lastBiomeDrawCalls++;
                    }
                }
            }
            drawLoadedTerrain(graphics, snapshot);
            drawOreOverlay(graphics, snapshot);
            drawChunkBorders(graphics, snapshot);
            if (config.booleanValue("structure-layer", true)) {
                drawStructures(graphics, snapshot, mouseX - offsetX, mouseY - offsetY);
            }
            if (config.booleanValue("waypoint-layer", true)) {
                drawWaypoints(graphics, snapshot, mouseX - offsetX, mouseY - offsetY);
            }
            drawPlayerMarker(graphics, snapshot);
            graphics.pose().popMatrix();
            graphics.disableScissor();
            drawCoordinates(graphics, snapshot, mouseX, mouseY);
            drawSelectedStructure(graphics);
            drawSelectedWaypoint(graphics);
            if (snapshot.request != activeRequest || !snapshot.structuresReady) {
                String progress = snapshot.request != activeRequest
                    ? "Updating..." : "Predicting structures..."
                        + (structureRegionsTotal > 0 ? " " + structureRegionsDone + "/" + structureRegionsTotal : "");
                int progressWidth = font.width(progress) + 16;
                graphics.fill(width / 2 - progressWidth / 2, mapTop() + 8,
                    width / 2 + progressWidth / 2, mapTop() + 26, 0xD0101714);
                graphics.centeredText(font, Component.literal(progress), width / 2,
                    mapTop() + 13, 0xFFE4EAE5);
            }
            String footer = "Seed " + snapshot.seed + "  |  "
                + visibleStructureCount(snapshot) + " structures  |  "
                + formatScale(blocksPerPixel) + " blocks/px";
            OreResult ores = oreResult;
            if (ores != null && ores.request == snapshot.request && !ores.patches.isEmpty()) {
                footer += "  |  " + ores.patches.size() + " ore markers";
            }
            if (!oreStatus.isBlank()) footer += "  |  " + oreStatus;
            if (!error.isBlank()) footer = error;
            if (config.booleanValue("show-player-info", true)
                && minecraft != null && minecraft.player != null) {
                footer += "  |  Player " + (int) Math.floor(minecraft.player.getX())
                    + ", " + (int) Math.floor(minecraft.player.getZ());
            }
            graphics.text(font, Component.literal(footer), 12, height - 18, 0xFFE4EAE5);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        drawContextMenu(graphics, mouseX, mouseY);
        lastMapDrawNanos = System.nanoTime() - drawStart;
    }

    private int panOffsetX(MapResult map) {
        return (int) Math.round((map.centerX - centerX) / blocksPerPixel);
    }

    private double rasterCellSize(MapResult map) { return map.step / blocksPerPixel; }

    private int rasterLeft(MapResult map) { return (int) Math.round((width - map.columns * rasterCellSize(map)) / 2); }
    private int rasterTop(MapResult map) { return (int) Math.round(mapTop() + (height - mapTop() - 10 - map.rows * rasterCellSize(map)) / 2); }

    private int panOffsetY(MapResult map) {
        return (int) Math.round((map.centerZ - centerZ) / blocksPerPixel);
    }

    private void drawContextMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (contextActions.isEmpty()) return;
        graphics.nextStratum();
        int visibleRows = visibleMenuRows();
        graphics.fill(contextX - 1, contextY - 1, contextX + contextWidth + 1,
            contextY + visibleRows * 22 + 1, 0xFF658878);
        for (int i = 0; i < visibleRows; i++) {
            int index = contextScrollOffset + i;
            int y = contextY + i * 22;
            boolean hovered = mouseX >= contextX && mouseX < contextX + contextWidth
                && mouseY >= y && mouseY < y + 22;
            graphics.fill(contextX, y, contextX + contextWidth, y + 22,
                hovered ? 0xFF324F43 : 0xFF15251E);
            graphics.text(font, contextActions.get(index).label(), contextX + 8, y + 7, 0xFFFFFFFF);
        }
    }

    private void openContextMenu(MapResult snapshot, double mouseX, double mouseY) {
        double hitX = mouseX - panOffsetX(snapshot), hitY = mouseY - panOffsetY(snapshot);
        StandaloneWaypoint waypoint = config.booleanValue("waypoint-layer", true)
            ? waypointAt(snapshot, hitX, hitY) : null;
        GeneratedStructure structure = waypoint == null && config.booleanValue("structure-layer", true)
            ? structureAt(snapshot, hitX, hitY) : null;
        int x = waypoint != null ? waypoint.x() : structure != null ? structure.x : worldX(snapshot, mouseX);
        int z = waypoint != null ? waypoint.z() : structure != null ? structure.z : worldZ(snapshot, mouseY);
        selectedStructure = structure;
        selectWaypoint(waypoint);
        clearSelectedLoot();
        List<MenuAction> actions = new ArrayList<>();
        if (waypoint != null) {
            actions.add(new MenuAction("Edit waypoint", this::editSelectedWaypoint));
            actions.add(new MenuAction("Delete waypoint", this::deleteSelectedWaypoint));
        } else {
            actions.add(new MenuAction("Add waypoint", () -> addWaypoint(snapshot, x, z,
                structure == null ? null : structure.displayName(), structure == null ? null : structure.type.name())));
        }
        actions.add(new MenuAction("View in 3D", () -> minecraft.gui.setScreen(
            new StandaloneTerrain3DScreen(this, config, snapshot.seed, dimension, snapshot.structures)
                .openAt(x, z, structure, null, 0).selectInitialWaypoint(waypoint))));
        if (structure != null) actions.add(new MenuAction("Inspect loot", () -> minecraft.gui.setScreen(
            new StandaloneStructureDetailScreen(this, config,
                new PredictionContext(snapshot.seed, config.predictionVersion(), dimension), structure))));
        contextActions = List.copyOf(actions);
        contextScrollOffset = 0;
        contextWidth = actions.stream().mapToInt(action -> font.width(action.label()) + 16).max().orElse(130);
        contextX = Math.max(2, Math.min((int) mouseX, width - contextWidth - 2));
        contextY = Math.max(mapTop(), Math.min((int) mouseY, height - actions.size() * 22 - 2));
    }

    private record MenuAction(String label, Runnable action) { }

    private LoadedTerrainOverlay captureLoadedTerrain(int columns, int rows,
                                                       double requestCenterX, double requestCenterZ,
                                                       double step, int requestDimension) {
        if (!config.booleanValue("loaded-terrain-on-map", true)
            || minecraft == null || minecraft.level == null
            || playerDimension() != requestDimension) {
            return LoadedTerrainOverlay.EMPTY;
        }

        int stride = Math.max(1, (int) Math.ceil(Math.sqrt(
            (columns * (double) rows) / MAX_LOADED_TERRAIN_SAMPLES)));
        int[] colors = new int[columns * rows];
        Level level = minecraft.level;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int row = 0; row < rows; row += stride) {
            int sampleRow = Math.min(rows - 1, row + stride / 2);
            int z = (int) Math.floor(requestCenterZ
                + (sampleRow - rows / 2.0 + 0.5) * step);
            for (int col = 0; col < columns; col += stride) {
                int sampleCol = Math.min(columns - 1, col + stride / 2);
                int x = (int) Math.floor(requestCenterX
                    + (sampleCol - columns / 2.0 + 0.5) * step);
                LevelChunk chunk = level.getChunkSource().getChunkNow(
                    Math.floorDiv(x, 16), Math.floorDiv(z, 16));
                if (chunk == null) continue;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE,
                    Math.floorMod(x, 16), Math.floorMod(z, 16)) - 1;
                if (y < level.getMinY()) continue;
                BlockState state = chunk.getBlockState(pos.set(x, y, z));
                colors[row * columns + col] = loadedTerrainColor(state, y);
            }
        }
        return new LoadedTerrainOverlay(stride, colors);
    }

    private void drawLoadedTerrain(GuiGraphicsExtractor graphics, MapResult map) {
        if (!config.booleanValue("loaded-terrain-on-map", true)) return;
        LoadedTerrainOverlay overlay = map.loadedTerrain;
        if (overlay == null || overlay.colors.length == 0) return;
        int left = rasterLeft(map);
        int top = rasterTop(map);
        double cellSize = rasterCellSize(map);
        int size = (int) Math.ceil(cellSize * overlay.stride);
        for (int row = 0; row < map.rows; row += overlay.stride) {
            for (int col = 0; col < map.columns; col += overlay.stride) {
                int color = overlay.colors[row * map.columns + col];
                if (color == 0) continue;
                int x0 = left + (int) Math.round(col * cellSize);
                int y0 = top + (int) Math.round(row * cellSize);
                if (!visibleWhilePanning(map, x0 + size / 2, y0 + size / 2, size)) continue;
                graphics.fill(x0, y0, x0 + size, y0 + size, color);
            }
        }
    }

    private static int loadedTerrainColor(BlockState state, int y) {
        int shade = Math.max(-24, Math.min(34, y - 64));
        if (state.is(Blocks.WATER)) return 0xC0225691;
        if (state.is(Blocks.LAVA)) return 0xD2E85E22;
        if (state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)) return shade(0xD2C2B26A, shade);
        if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)
            || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)) return 0xD7D6E4EC;
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MOSS_BLOCK)) return shade(0xCD48844C, shade);
        if (state.is(Blocks.NETHERRACK) || state.is(Blocks.CRIMSON_NYLIUM)) return shade(0xD7742B2C, shade);
        if (state.is(Blocks.WARPED_NYLIUM)) return shade(0xD72A696A, shade);
        if (state.is(Blocks.BASALT) || state.is(Blocks.BLACKSTONE)) return shade(0xDC37373C, shade);
        if (state.is(Blocks.END_STONE)) return shade(0xD7BCB87C, shade);
        return shade(0xBE5C5E58, shade);
    }

    private static int shade(int argb, int amount) {
        int r = Math.max(0, Math.min(255, ((argb >>> 16) & 0xFF) + amount));
        int g = Math.max(0, Math.min(255, ((argb >>> 8) & 0xFF) + amount));
        int b = Math.max(0, Math.min(255, (argb & 0xFF) + amount));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private void requestOres(long mapRequest, long seed, int requestDimension,
                             double requestCenterX, double requestCenterZ) {
        long oreRequest = ORE_REQUESTS.incrementAndGet();
        oreResult = null;
        oreStatus = "";
        if (!config.booleanValue("map-ore-overlay", false)) return;
        if (blocksPerPixel > 4.0) { oreStatus = "Zoom in for ores"; return; }

        OreType type = selectedOreType(requestDimension);
        if (type == null) return;
        int mapWidth = Math.max(1, width - 20);
        int mapHeight = Math.max(1, height - mapTop() - 10);
        int minChunkX = Math.floorDiv((int) Math.floor(
            requestCenterX - mapWidth * blocksPerPixel / 2.0), 16);
        int maxChunkX = Math.floorDiv((int) Math.ceil(
            requestCenterX + mapWidth * blocksPerPixel / 2.0), 16);
        int minChunkZ = Math.floorDiv((int) Math.floor(
            requestCenterZ - mapHeight * blocksPerPixel / 2.0), 16);
        int maxChunkZ = Math.floorDiv((int) Math.ceil(
            requestCenterZ + mapHeight * blocksPerPixel / 2.0), 16);
        long chunkCount = (long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        if (chunkCount > MAX_ORE_CHUNKS) { oreStatus = "Zoom in for ores"; return; }
        oreStatus = "Predicting ores...";

        ORE_GENERATOR.execute(() -> {
            try {
                if (ORE_REQUESTS.get() != oreRequest) return;
                List<OrePatch> predicted = OrePredictor.predictInChunkRange(
                    minChunkX, minChunkZ, maxChunkX, maxChunkZ, seed, requestDimension);
                if (predicted.size() > MAX_MAP_ORE_MARKERS) {
                    predicted = List.copyOf(predicted.subList(0, MAX_MAP_ORE_MARKERS));
                }
                if (ORE_REQUESTS.get() == oreRequest && activeRequest == mapRequest) {
                    oreResult = new OreResult(mapRequest, type, predicted);
                    oreStatus = "";
                }
            } catch (Throwable failure) {
                if (ORE_REQUESTS.get() == oreRequest && activeRequest == mapRequest) {
                    error = "Ore prediction failed: " + failure.getClass().getSimpleName();
                }
            }
        });
    }

    private OreType selectedOreType(int requestDimension) {
        String selected = config.stringValue("ore-esp-type", "Diamond");
        for (OreType type : OreType.values()) {
            if (type.dimension == requestDimension && type.displayName.equals(selected)) return type;
        }
        for (OreType type : OreType.values()) {
            if (type.dimension == requestDimension) return type;
        }
        return null;
    }

    private void drawOreOverlay(GuiGraphicsExtractor graphics, MapResult map) {
        double blocksPerPixel = this.blocksPerPixel;
        if (!config.booleanValue("map-ore-overlay", false)) return;
        OreResult ores = oreResult;
        if (ores == null || ores.request != map.request) return;
        int left = 10;
        int top = mapTop();
        int mapWidth = width - 20;
        int mapHeight = height - top - 10;
        int alpha = config.intValue("ore-opacity", 55, 20, 255);
        int radius = Math.max(1, Math.min(5, (int) Math.round(
            config.doubleValue("ore-marker-scale", 1.0, 0.5, 3.0) * 1.5)));
        for (OrePatch patch : ores.patches) {
            int color = (alpha << 24) | (patch.type.color.r << 16)
                | (patch.type.color.g << 8) | patch.type.color.b;
            int x = left + (int) Math.round(mapWidth / 2.0
                + (patch.x - map.centerX) / blocksPerPixel);
            int y = top + (int) Math.round(mapHeight / 2.0
                + (patch.z - map.centerZ) / blocksPerPixel);
            if (x < left || x >= width - 10 || y < top || y >= height - 10) continue;
            graphics.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, color);
        }
    }

    private void drawStructures(GuiGraphicsExtractor graphics, MapResult map, int mouseX, int mouseY) {
        int left = 10;
        int top = mapTop();
        int mapWidth = width - 20;
        int mapHeight = height - top - 10;
        GeneratedStructure hovered = null;
        int hoveredCount = 1;
        Set<Long> waypointPositions = Set.of();
        if (config.booleanValue("dim-waypoint-structures", true)) {
            waypointPositions = new HashSet<>();
            for (StandaloneWaypoint waypoint : config.waypoints(map.seed, dimension)) {
                waypointPositions.add(packedPosition(waypoint.x(), waypoint.z()));
            }
        }
        int radius = structureIconRadius();
        for (StandaloneMapPresentation.Marker marker : markers(map)) {
            GeneratedStructure structure = marker.structure();
            if (!isStructureVisible(structure)) continue;
            int x = left + (int) Math.round(mapWidth / 2.0 + (structure.x - map.centerX) / blocksPerPixel);
            int y = top + (int) Math.round(mapHeight / 2.0 + (structure.z - map.centerZ) / blocksPerPixel);
            if (!visibleWhilePanning(map, x, y, radius)) continue;
            lastDrawnMarkers++;
            boolean dimmed = waypointPositions.contains(packedPosition(structure.x, structure.z))
                || config.completed(dimension, structure.type.name(), structure.x, structure.z);
            boolean selected = structure.equals(selectedStructure);
            graphics.fill(x - radius - 1, y - radius - 1, x + radius + 1, y + radius + 1,
                selected ? 0xFFE7FFF0 : 0xA5151918);
            graphics.blit(StandaloneStructureIcons.texture(structure),
                x - radius, y - radius, x + radius, y + radius,
                0.0f, 1.0f, 0.0f, 1.0f);
            if (dimmed) graphics.fill(x - radius, y - radius, x + radius, y + radius, 0x800A1110);
            if (marker.count() > 1) graphics.text(font, Integer.toString(marker.count()),
                x + radius - 2, y + radius - 4, 0xFFFFFFFF);
            if (Math.abs(mouseX - x) <= radius + 3 && Math.abs(mouseY - y) <= radius + 3) {
                hovered = structure;
                hoveredCount = marker.count();
            }
        }
        if (hovered != null && config.booleanValue("structure-tooltips", true)) {
            String label = hovered.displayName() + (hoveredCount > 1 ? " x" + hoveredCount + " (zoom in)" : "")
                + "  " + hovered.getCoordsString();
            int labelWidth = font.width(label) + 10;
            int x = Math.min(width - labelWidth - 8, Math.max(8, mouseX + 10));
            int y = Math.max(top + 4, mouseY - 22);
            graphics.fill(x, y, x + labelWidth, y + 18, 0xE8101714);
            graphics.text(font, label, x + 5, y + 5, 0xFFFFFFFF);
        }
    }

    private List<StandaloneMapPresentation.Marker> markers(MapResult map) {
        int cell = StandaloneMapPresentation.fossilCellSize(blocksPerPixel);
        if (markerSource != map.structures || markerCell != cell) {
            markerSource = map.structures;
            markerCell = cell;
            mapMarkers = StandaloneMapPresentation.markers(map.structures, blocksPerPixel);
        }
        return mapMarkers;
    }

    private boolean visibleWhilePanning(MapResult map, int x, int y, int radius) {
        int actualX = x + panOffsetX(map), actualY = y + panOffsetY(map);
        return actualX + radius >= 10 && actualX - radius < width - 10
            && actualY + radius >= mapTop() && actualY - radius < height - 10;
    }

    private void drawWaypoints(GuiGraphicsExtractor graphics, MapResult map, int mouseX, int mouseY) {
        int radius = Math.max(3, (int) Math.round(3.0
            * config.doubleValue("waypoint-marker-scale", 1.0, 0.5, 3.0)));
        StandaloneWaypoint hovered = null;
        for (StandaloneWaypoint waypoint : config.waypoints(map.seed, dimension)) {
            int x = screenX(map, waypoint.x());
            int y = screenY(map, waypoint.z());
            if (!visibleWhilePanning(map, x, y, radius)) continue;
            int border = waypoint.equals(selectedWaypoint) ? 0xFFFFFFFF : 0xFF101619;
            graphics.fill(x - 1, y - radius - 1, x + 2, y + radius + 2, border);
            graphics.fill(x - radius - 1, y - 1, x + radius + 2, y + 2, border);
            graphics.fill(x, y - radius, x + 1, y + radius + 1, 0xFF63F3CE);
            graphics.fill(x - radius, y, x + radius + 1, y + 1, 0xFF63F3CE);
            if (Math.max(Math.abs(mouseX - x), Math.abs(mouseY - y)) <= radius + 3) {
                hovered = waypoint;
            }
        }
        if (hovered != null && config.booleanValue("waypoint-tooltips", true)) {
            String label = ellipsize(hovered.name() + "  " + hovered.x() + ", " + hovered.z(),
                Math.max(40, width - 26));
            int labelWidth = font.width(label) + 10;
            int x = Math.min(width - labelWidth - 8, Math.max(8, mouseX + 10));
            int y = Math.max(mapTop() + 4, mouseY - 22);
            graphics.fill(x, y, x + labelWidth, y + 18, 0xE8101714);
            graphics.text(font, label, x + 5, y + 5, 0xFFFFFFFF);
        }
    }

    private int structureIconRadius() {
        return Math.max(4, (int) Math.round(7.0
            * config.doubleValue("structure-marker-scale", 1.0, 0.5, 3.0)));
    }

    private int visibleStructureCount(MapResult map) {
        if (!config.booleanValue("structure-layer", true)) return 0;
        int count = 0;
        for (StandaloneMapPresentation.Marker marker : markers(map)) {
            if (isStructureVisible(marker.structure())) count += marker.count();
        }
        return count;
    }

    private boolean isStructureVisible(GeneratedStructure structure) {
        return config.structureVisible(structure.type.name())
            && (selectedStructureFilters.isEmpty() || selectedStructureFilters.contains(structure.type));
    }

    private GeneratedStructure structureAt(MapResult map, double mouseX, double mouseY) {
        int left = 10;
        int top = mapTop();
        int mapWidth = width - 20;
        int mapHeight = height - top - 10;
        GeneratedStructure closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (StandaloneMapPresentation.Marker marker : markers(map)) {
            GeneratedStructure structure = marker.structure();
            if (!isStructureVisible(structure)) continue;
            int x = left + (int) Math.round(mapWidth / 2.0
                + (structure.x - map.centerX) / blocksPerPixel);
            int y = top + (int) Math.round(mapHeight / 2.0
                + (structure.z - map.centerZ) / blocksPerPixel);
            double distance = Math.max(Math.abs(mouseX - x), Math.abs(mouseY - y));
            if (distance <= structureIconRadius() + 3.0 && distance < closestDistance) {
                closest = structure;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private StandaloneWaypoint waypointAt(MapResult map, double mouseX, double mouseY) {
        int radius = Math.max(3, (int) Math.round(3.0
            * config.doubleValue("waypoint-marker-scale", 1.0, 0.5, 3.0)));
        StandaloneWaypoint closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (StandaloneWaypoint waypoint : config.waypoints(map.seed, dimension)) {
            int x = screenX(map, waypoint.x());
            int y = screenY(map, waypoint.z());
            double distance = Math.max(Math.abs(mouseX - x), Math.abs(mouseY - y));
            if (distance <= radius + 3 && distance < closestDistance) {
                closest = waypoint;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private void drawSelectedStructure(GuiGraphicsExtractor graphics) {
        GeneratedStructure selected = selectedStructure;
        if (selected == null) return;
        List<String> lines = new ArrayList<>();
        lines.add(selected.displayName());
        lines.add("Position: " + selected.getCoordsString());
        lines.add("Start chunk: " + selected.startChunkX + ", " + selected.startChunkZ);
        if (!selected.variant.isBlank()) lines.add("Variant: " + selected.variant);
        if (selected.type == StructureType.END_CITY) {
            lines.add(selected.hasShip ? "End ship: predicted" : "End ship: none");
        }
        if (lootLoading) {
            lines.add("Loot: predicting...");
        } else if (!lootError.isBlank()) {
            lines.add("Loot: " + lootError);
        } else if (!lootSupported) {
            lines.add("Loot: unavailable for this structure");
        } else if (selectedLoot.isEmpty()) {
            lines.add("Loot: no containers");
        } else {
            lines.add("Loot: " + selectedLoot.size() + " container"
                + (selectedLoot.size() == 1 ? "" : "s"));
            int shown = 0;
            for (ChestLootOutput chest : selectedLoot) {
                if (shown++ >= 4) {
                    lines.add("+ " + (selectedLoot.size() - 4) + " more containers");
                    break;
                }
                String precision = chest.exact() ? "exact" : "approx";
                lines.add(chest.blockX() + ", " + chest.blockY() + ", " + chest.blockZ()
                    + " (" + precision + ")");
                lines.add("  " + chest.summary());
            }
        }
        int maxPanelWidth = Math.max(160, Math.min(360, width - 32));
        for (int i = 0; i < lines.size(); i++) {
            lines.set(i, ellipsize(lines.get(i), maxPanelWidth - 14));
        }
        int panelWidth = 0;
        for (String line : lines) panelWidth = Math.max(panelWidth, font.width(line));
        panelWidth += 14;
        int panelHeight = lines.size() * 12 + 10;
        int x = Math.max(10, width - panelWidth - 12);
        int y = mapTop() + 10;
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xE8101714);
        graphics.fill(x, y, x + 3, y + panelHeight, markerColor(selected));
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(font, lines.get(i), x + 8, y + 6 + i * 12, 0xFFFFFFFF);
        }
    }

    private void drawSelectedWaypoint(GuiGraphicsExtractor graphics) {
        StandaloneWaypoint waypoint = selectedWaypoint;
        if (waypoint == null) return;
        List<String> lines = List.of(
            waypoint.name(),
            "Position: " + waypoint.x() + ", " + waypoint.z(),
            "Dimension: " + dimensionLabel(waypoint.dimension())
        );
        int panelWidth = 0;
        for (String line : lines) panelWidth = Math.max(panelWidth, font.width(line));
        panelWidth = Math.min(width - 32, panelWidth + 14);
        int panelHeight = lines.size() * 12 + 10;
        int x = Math.max(10, width - panelWidth - 12);
        int y = mapTop() + 10;
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xE8101714);
        graphics.fill(x, y, x + 3, y + panelHeight, 0xFF63F3CE);
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(font, ellipsize(lines.get(i), panelWidth - 14),
                x + 8, y + 6 + i * 12, 0xFFFFFFFF);
        }
    }

    private String ellipsize(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        String suffix = "...";
        int end = value.length();
        while (end > 0 && font.width(value.substring(0, end) + suffix) > maxWidth) end--;
        return value.substring(0, end) + suffix;
    }

    private void clearSelectedLoot() {
        LOOT_REQUESTS.incrementAndGet();
        selectedLoot = List.of();
        lootLoading = false;
        lootSupported = false;
        lootError = "";
    }

    private void requestSelectedLoot(GeneratedStructure structure, long seed) {
        clearSelectedLoot();
        PredictionContext context = new PredictionContext(seed, config.predictionVersion(), dimension);
        lootSupported = structure.type == StructureType.DUNGEON
            || StandaloneStructureLootPredictor.structureKey(context, structure) != null;
        if (!lootSupported) return;
        var saved = StandalonePersistentLootCache.get(context, structure);
        var observations = SeedExplorerFabricClient.lootObservations(context);
        if (saved.isPresent() && !StandaloneObservedLootRefinement.canRefine(context, structure, observations)) {
            selectedLoot = saved.get();
            StandaloneObservedLootRefinement.remember(context, structure, saved.get(), selectedLoot);
            return;
        }
        long request = LOOT_REQUESTS.incrementAndGet();
        StandaloneLootRequestGuard guard = StandaloneLootRequestGuard.capture(context);
        lootLoading = true;
        LOOT_GENERATOR.execute(() -> {
            if (LOOT_REQUESTS.get() != request || selectedStructure != structure) return;
            try {
                List<ChestLootOutput> canonical = saved.orElseGet(() ->
                    StandaloneStructureLootPredictor.predict(context, structure, 64));
                if (saved.isEmpty()) StandalonePersistentLootCache.put(context, structure, canonical);
                List<ChestLootOutput> predicted = StandaloneObservedLootRefinement.refine(
                    context, structure, canonical, observations);
                minecraft.execute(() -> {
                    if (LOOT_REQUESTS.get() != request || selectedStructure != structure) return;
                    if (!guard.isCurrent()) {
                        lootLoading = false;
                        lootError = "World or prediction settings changed; select again";
                        return;
                    }
                    selectedLoot = predicted;
                    lootLoading = false;
                    StandaloneObservedLootRefinement.remember(context, structure, canonical, predicted);
                });
            } catch (Throwable failure) {
                minecraft.execute(() -> {
                    if (LOOT_REQUESTS.get() != request || selectedStructure != structure) return;
                    if (!guard.isCurrent()) {
                        lootLoading = false;
                        lootError = "World or prediction settings changed; select again";
                        return;
                    }
                    lootError = failure.getClass().getSimpleName();
                    lootLoading = false;
                });
            }
        });
    }

    private static List<GeneratedStructure> mergeStructures(List<GeneratedStructure> known,
                                                            List<GeneratedStructure> incoming) {
        if (known.isEmpty()) return incoming;
        java.util.Map<StructureKey, GeneratedStructure> merged = new java.util.LinkedHashMap<>();
        for (var group : List.of(known, incoming)) for (var structure : group) {
            var key = new StructureKey(structure.type, structure.startChunkX, structure.startChunkZ,
                structure.x, structure.y, structure.z, structure.variant);
            merged.put(key, structure);
        }
        return List.copyOf(merged.values());
    }

    private record StructureKey(StructureType type, int startX, int startZ, int x, int y, int z, String variant) { }

    private static int markerColor(GeneratedStructure structure) {
        return switch (structure.type.dimension) {
            case -1 -> 0xFFFF8B57;
            case 1 -> 0xFFC9A5FF;
            default -> structure.type == me.seedexplorer.addon.structures.StructureType.ABANDONED_CAMP
                ? 0xFF46E0BA : 0xFFFFD25A;
        };
    }

    private static int dimColor(int color) {
        int red = ((color >>> 16) & 0xFF) * 2 / 5;
        int green = ((color >>> 8) & 0xFF) * 2 / 5;
        int blue = (color & 0xFF) * 2 / 5;
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static long packedPosition(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private void drawChunkBorders(GuiGraphicsExtractor graphics, MapResult map) {
        double blocksPerPixel = this.blocksPerPixel;
        if (!config.booleanValue("chunk-borders-layer", true)) return;
        double pixelsPerChunk = 16.0 / blocksPerPixel;
        if (pixelsPerChunk < 5.0) return;
        int left = 10;
        int top = mapTop();
        int mapWidth = width - 20;
        int mapHeight = height - top - 10;
        double minX = map.centerX - mapWidth * blocksPerPixel / 2.0;
        double maxX = map.centerX + mapWidth * blocksPerPixel / 2.0;
        double minZ = map.centerZ - mapHeight * blocksPerPixel / 2.0;
        double maxZ = map.centerZ + mapHeight * blocksPerPixel / 2.0;
        int firstX = Math.floorDiv((int) Math.floor(minX), 16) * 16;
        int firstZ = Math.floorDiv((int) Math.floor(minZ), 16) * 16;
        int drawn = 0;
        for (int blockX = firstX; blockX <= maxX && drawn < 512; blockX += 16, drawn++) {
            int x = left + (int) Math.round(mapWidth / 2.0 + (blockX - map.centerX) / blocksPerPixel);
            graphics.fill(x, top, x + 1, height - 10, 0x44343F39);
        }
        drawn = 0;
        for (int blockZ = firstZ; blockZ <= maxZ && drawn < 512; blockZ += 16, drawn++) {
            int y = top + (int) Math.round(mapHeight / 2.0 + (blockZ - map.centerZ) / blocksPerPixel);
            graphics.fill(left, y, width - 10, y + 1, 0x44343F39);
        }
    }

    private void drawPlayerMarker(GuiGraphicsExtractor graphics, MapResult map) {
        double blocksPerPixel = this.blocksPerPixel;
        if (!config.booleanValue("player-layer", true) || minecraft == null
            || minecraft.player == null || playerDimension() != dimension) return;
        int left = 10;
        int top = mapTop();
        int mapWidth = width - 20;
        int mapHeight = height - top - 10;
        int x = left + (int) Math.round(mapWidth / 2.0
            + (minecraft.player.getX() - map.centerX) / blocksPerPixel);
        int y = top + (int) Math.round(mapHeight / 2.0
            + (minecraft.player.getZ() - map.centerZ) / blocksPerPixel);
        if (x < left || x >= width - 10 || y < top || y >= height - 10) return;
        graphics.fill(x - 4, y - 4, x + 5, y + 5, 0xFF101619);
        graphics.fill(x - 2, y - 2, x + 3, y + 3, 0xFF60E7FF);
    }

    private void drawCoordinates(GuiGraphicsExtractor graphics, MapResult map, int mouseX, int mouseY) {
        double blocksPerPixel = this.blocksPerPixel;
        if (!config.booleanValue("coordinates-layer", true)) return;
        int left = 10;
        int top = mapTop();
        int mapWidth = width - 20;
        int mapHeight = height - top - 10;
        int centerScreenX = left + mapWidth / 2;
        int centerScreenY = top + mapHeight / 2;
        graphics.fill(centerScreenX - 5, centerScreenY, centerScreenX + 6, centerScreenY + 1, 0xCCFFFFFF);
        graphics.fill(centerScreenX, centerScreenY - 5, centerScreenX + 1, centerScreenY + 6, 0xCCFFFFFF);
        if (mouseX < left || mouseX >= width - 10 || mouseY < top || mouseY >= height - 10) return;
        int worldX = (int) Math.floor(centerX + (mouseX - centerScreenX) * blocksPerPixel);
        int worldZ = (int) Math.floor(centerZ + (mouseY - centerScreenY) * blocksPerPixel);
        String text = worldX + ", " + worldZ;
        int textWidth = font.width(text);
        graphics.fill(left + 4, top + 4, left + textWidth + 12, top + 20, 0xB0101714);
        graphics.text(font, text, left + 8, top + 8, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (!contextActions.isEmpty()) {
            int row = (int) ((click.y() - contextY) / 22);
            int visibleRows = visibleMenuRows();
            if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && click.x() >= contextX && click.x() < contextX + contextWidth
                && click.y() >= contextY && row >= 0 && row < visibleRows) {
                Runnable action = contextActions.get(contextScrollOffset + row).action();
                contextActions = List.of();
                action.run();
                return true;
            }
            contextActions = List.of();
            // Dismissing the menu consumes the gesture; it must not click the map below.
            return true;
        }
        if ((click.button() == InputConstants.MOUSE_BUTTON_LEFT || click.button() == InputConstants.MOUSE_BUTTON_RIGHT) && insideMap((int) click.x(), (int) click.y())) {
            setFocused(null);
            MapResult snapshot = result;
            if (click.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                dragging = false;
                setDragging(false);
                if (snapshot != null) openContextMenu(snapshot, click.x(), click.y());
                return true;
            }
            dragging = true;
            setDragging(true);
            dragMoved = false;
            pressMouseX = lastMouseX = click.x();
            pressMouseY = lastMouseY = click.y();
            double hitX = snapshot == null ? click.x() : click.x() - panOffsetX(snapshot);
            double hitY = snapshot == null ? click.y() : click.y() - panOffsetY(snapshot);
            StandaloneWaypoint waypoint = snapshot == null || !config.booleanValue("waypoint-layer", true)
                ? null : waypointAt(snapshot, hitX, hitY);
            GeneratedStructure hit = waypoint != null || snapshot == null || !config.booleanValue("structure-layer", true)
                ? null : structureAt(snapshot, hitX, hitY);
            selectedStructure = hit;
            selectWaypoint(waypoint);
            clearSelectedLoot();
            if (hit != null) requestSelectedLoot(hit, snapshot.seed);
            if (doubled && waypoint != null) editSelectedWaypoint();
            else if (doubled && hit != null) minecraft.gui.setScreen(new StandaloneStructureDetailScreen(
                this, config, new PredictionContext(snapshot.seed, config.predictionVersion(), dimension), hit));
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE && !contextActions.isEmpty()) {
            contextActions = List.of();
            return true;
        }
        return super.keyPressed(event);
    }

    private void addWaypointAtSelectionOrCenter() {
        MapResult snapshot = result;
        if (snapshot == null) return;
        GeneratedStructure structure = selectedStructure;
        if (structure != null) {
            addWaypoint(snapshot, structure.x, structure.z,
                structure.displayName() + " [" + structure.x + ", " + structure.z + "]",
                structure.type.name());
        } else {
            int x = (int) Math.floor(centerX);
            int z = (int) Math.floor(centerZ);
            addWaypoint(snapshot, x, z, null, null);
        }
    }

    private void addWaypoint(MapResult map, int x, int z, String name, String structureType) {
        selectedStructure = null;
        clearSelectedLoot();
        selectWaypoint(config.addWaypoint(map.seed, dimension, x, z, name, structureType));
        config.save();
    }

    private void deleteSelectedWaypoint() {
        StandaloneWaypoint waypoint = selectedWaypoint;
        if (waypoint == null) return;
        if (config.removeWaypoint(waypoint.id())) config.save();
        selectWaypoint(null);
    }

    private void selectWaypoint(StandaloneWaypoint waypoint) {
        selectedWaypoint = waypoint;
        updateWaypointButtons();
    }

    private void updateWaypointButtons() {
        if (deleteWaypointButton != null) deleteWaypointButton.active = selectedWaypoint != null;
    }

    private int screenX(MapResult map, int worldX) {
        return 10 + (int) Math.round((width - 20) / 2.0
            + (worldX - map.centerX) / blocksPerPixel);
    }

    private int screenY(MapResult map, int worldZ) {
        return mapTop() + (int) Math.round((height - mapTop() - 10) / 2.0
            + (worldZ - map.centerZ) / blocksPerPixel);
    }

    private int worldX(MapResult map, double screenX) {
        double blocksPerPixel = this.blocksPerPixel;
        return (int) Math.floor(centerX + (screenX - width / 2.0) * blocksPerPixel);
    }

    private int worldZ(MapResult map, double screenY) {
        double blocksPerPixel = this.blocksPerPixel;
        double centerScreenY = mapTop() + (height - mapTop() - 10) / 2.0;
        return (int) Math.floor(centerZ + (screenY - centerScreenY) * blocksPerPixel);
    }

    private boolean insideMap(int x, int y) {
        return x >= 10 && x < width - 10 && y >= mapTop() && y < height - 10;
    }

    private static String dimensionLabel(int dimension) {
        return switch (dimension) {
            case -1 -> "Nether";
            case 1 -> "End";
            default -> "Overworld";
        };
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && dragging) {
            dragging = false;
            setDragging(false);
            if (dragMoved) requestPanIfNeeded();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (!dragging || click.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseDragged(click, deltaX, deltaY);
        if (!dragMoved && Math.hypot(click.x() - pressMouseX, click.y() - pressMouseY) < 3.0) return true;
        double scale = blocksPerPixel;
        double moveX = dragMoved ? deltaX : click.x() - pressMouseX;
        double moveY = dragMoved ? deltaY : click.y() - pressMouseY;
        dragMoved = true;
        centerX -= moveX * scale;
        centerZ -= moveY * scale;
        lastMouseX = click.x();
        lastMouseY = click.y();
        requestPanIfNeeded();
        return true;
    }

    private void requestPanIfNeeded() {
        MapResult map = result;
        if (map == null || biomeRequestPending || System.nanoTime() - lastMapRequestNanos < 150_000_000L) return;
        double marginX = (map.columns * rasterCellSize(map) - (width - 20)) / 2.0;
        double marginY = (map.rows * rasterCellSize(map) - (height - mapTop() - 10)) / 2.0;
        if (Math.abs(panOffsetX(map)) > marginX / 2 || Math.abs(panOffsetY(map)) > marginY / 2)
            requestMap();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!contextActions.isEmpty()) {
            if (mouseX >= contextX && mouseX < contextX + contextWidth
                && mouseY >= contextY && mouseY < contextY + visibleMenuRows() * 22) {
                int maximum = Math.max(0, contextActions.size() - visibleMenuRows());
                contextScrollOffset = Math.max(0, Math.min(maximum,
                    contextScrollOffset + (verticalAmount < 0 ? 1 : -1)));
            }
            return true;
        }
        if (!insideMap((int) mouseX, (int) mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        if (!Double.isFinite(verticalAmount) || verticalAmount == 0.0) return true;
        contextActions = List.of();
        // Apply the full fractional event immediately. There is no animation queue to
        // continue moving the map after the user's wheel stops.
        double oldScale = blocksPerPixel;
        double zoomStep = config.doubleValue("zoom-step", 1.2, 1.01, 2.0);
        blocksPerPixel = clampScale(oldScale * Math.pow(zoomStep, -verticalAmount));
        if (blocksPerPixel == oldScale) return true;
        double top = mapTop();
        centerX += (mouseX - width / 2.0) * (oldScale - blocksPerPixel);
        centerZ += (mouseY - (top + (height - top - 10) / 2.0)) * (oldScale - blocksPerPixel);
        // Render the existing raster at the new scale now; coalesce regeneration to
        // one request per client tick when a touchpad emits many events per frame.
        zoomRequestPending = true;
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        applyMapPublication();
        if (zoomRequestPending) requestMap();
        requestPanIfNeeded();
        MapResult snapshot = result;
        if (++refreshTicks >= 20 && snapshot != null && snapshot.request == activeRequest && !dragging) {
            refreshTicks = 0;
            LoadedTerrainOverlay overlay = captureLoadedTerrain(snapshot.columns, snapshot.rows,
                snapshot.centerX, snapshot.centerZ, snapshot.step, dimension);
            result = new MapResult(snapshot.request, snapshot.columns, snapshot.rows, snapshot.colors,
                snapshot.structures, snapshot.markers, snapshot.centerX, snapshot.centerZ, snapshot.step, snapshot.seed,
                overlay, snapshot.structuresReady);
        }
        if (dragging || !contextActions.isEmpty() || !config.booleanValue("auto-center-player", false) || minecraft == null
            || minecraft.player == null || playerDimension() != dimension) return;
        double playerX = minecraft.player.getX();
        double playerZ = minecraft.player.getZ();
        if (Math.abs(playerX - centerX) < 16.0 && Math.abs(playerZ - centerZ) < 16.0) return;
        centerX = playerX;
        centerZ = playerZ;
        requestMap();
    }

    private int playerDimension() {
        if (minecraft == null || minecraft.level == null) return Integer.MIN_VALUE;
        if (minecraft.level.dimension() == Level.NETHER) return -1;
        if (minecraft.level.dimension() == Level.END) return 1;
        return 0;
    }

    private double clampScale(double scale) {
        double min = config.doubleValue("min-zoom", 0.01, 0.001, 10.0);
        double max = config.doubleValue("max-zoom", 100.0, 1.0, 200.0);
        if (min > max) {
            double swap = min;
            min = max;
            max = swap;
        }
        return Math.max(1.0 / max, Math.min(1.0 / min, scale));
    }

    private static String formatScale(double scale) {
        if (scale >= 10.0) return Integer.toString((int) Math.round(scale));
        if (scale >= 1.0) return String.format(java.util.Locale.ROOT, "%.1f", scale);
        return String.format(java.util.Locale.ROOT, "%.3f", scale);
    }

    @Override
    public void onClose() {
        REQUESTS.incrementAndGet();
        LOOT_REQUESTS.incrementAndGet();
        ORE_REQUESTS.incrementAndGet();
        config.save();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void removed() {
        saveMapOptions();
        pendingMap.set(null);
        REQUESTS.compareAndSet(activeRequest, activeRequest + 1);
        LOOT_REQUESTS.incrementAndGet();
        ORE_REQUESTS.incrementAndGet();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record MapResult(long request, int columns, int rows, int[] colors,
                             List<GeneratedStructure> structures, List<StandaloneMapPresentation.Marker> markers, double centerX,
                             double centerZ, double step, long seed,
                             LoadedTerrainOverlay loadedTerrain, boolean structuresReady) {
    }

    private record MapPublication(MapResult map, int completed, int total) { }

    private record LoadedTerrainOverlay(int stride, int[] colors) {
        private static final LoadedTerrainOverlay EMPTY = new LoadedTerrainOverlay(1, new int[0]);
    }

    private record OreResult(long request, OreType type, List<OrePatch> patches) {
    }
    private record BiomeKey(long seed, int dimension, int x, int z) { }
}
