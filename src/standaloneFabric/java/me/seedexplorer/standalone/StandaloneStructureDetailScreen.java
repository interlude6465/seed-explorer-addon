package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.StandaloneStructureLootPredictor;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Native all-container inspector shared by the standalone 2D and 3D maps. */
public final class StandaloneStructureDetailScreen extends Screen {
    private static final ThreadPoolExecutor LOOT_EXECUTOR = new ThreadPoolExecutor(1, 1,
        30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), runnable -> {
            Thread thread = new Thread(runnable, "Seed Explorer structure details");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int SIDEBAR_WIDTH = 220;
    private static final int ROW_HEIGHT = 38;

    private final Screen parent;
    private final StandaloneSeedExplorerConfig config;
    private final PredictionContext context;
    private final GeneratedStructure structure;
    private final AtomicLong requests = new AtomicLong();
    private final StandaloneNavigationCapability navigation = new ReflectiveBaritoneNavigation();
    private final boolean navigationAvailable;
    private volatile List<ChestLootOutput> chests;
    private volatile boolean loading;
    private volatile String error = "";
    private volatile int selectedChest;
    private int containerScroll;
    private int itemScroll;
    private Button previousButton;
    private Button nextButton;
    private Button teleportButton;
    private Button completedButton;
    private Button navigateButton;
    private String notice = "";
    private long noticeUntil;

    public StandaloneStructureDetailScreen(Screen parent, StandaloneSeedExplorerConfig config,
                                           PredictionContext context, GeneratedStructure structure) {
        this(parent, config, context, structure, null, 0);
    }

    public StandaloneStructureDetailScreen(Screen parent, StandaloneSeedExplorerConfig config,
                                           PredictionContext context, GeneratedStructure structure,
                                           List<ChestLootOutput> knownChests, int selectedChest) {
        super(Component.literal("Structure details"));
        this.parent = parent;
        this.config = config;
        this.context = context.withDimension(structure.type.dimension);
        this.structure = structure;
        this.navigationAvailable = navigation.available();
        this.chests = knownChests == null ? List.of() : List.copyOf(knownChests);
        this.selectedChest = Math.max(0, Math.min(this.chests.size() - 1, selectedChest));
        if (knownChests == null) loadLoot();
    }

    private void loadLoot() {
        long expected = requests.incrementAndGet();
        loading = true;
        error = "";
        LOOT_EXECUTOR.execute(() -> {
            try {
                List<ChestLootOutput> result = StandaloneStructureLootPredictor.predict(
                    context, structure, 64);
                if (requests.get() == expected) {
                    chests = result;
                    selectedChest = 0;
                    StandaloneAncillaryRuntime runtime = StandaloneAncillaryRuntime.get();
                    if (runtime != null) runtime.rememberLoot(context, result);
                }
            } catch (Throwable failure) {
                if (requests.get() == expected) {
                    error = failure.getClass().getSimpleName()
                        + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
                }
            } finally {
                if (requests.get() == expected) loading = false;
            }
        });
    }

    @Override
    protected void init() {
        boolean compact = width < 700;
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
            .bounds(10, 10, 62, 20).build());
        previousButton = addRenderableWidget(Button.builder(Component.literal("<"), button ->
            selectChest(selectedChest - 1)).bounds(80, 10, 24, 20).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal(">"), button ->
            selectChest(selectedChest + 1)).bounds(110, 10, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Copy all"), button -> copyReport())
            .bounds(compact ? 10 : 142, compact ? 38 : 10, 74, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Export"), button -> exportReport())
            .bounds(compact ? 90 : 222, compact ? 38 : 10, 66, 20).build());
        teleportButton = addRenderableWidget(Button.builder(Component.literal("TP chest"), button ->
            teleportToSelectedChest()).bounds(compact ? 162 : 294, compact ? 38 : 10, 72, 20).build());
        addRenderableWidget(Button.builder(Component.literal(compact ? "TP struct" : "TP structure"), button ->
            teleportToStructure()).bounds(compact ? 240 : 372, compact ? 38 : 10,
                compact ? 76 : 92, 20).build());
        completedButton = addRenderableWidget(Button.builder(completedLabel(), button -> {
            config.toggleCompleted(context.dimension(), structure.type.name(), structure.x, structure.z);
            completedButton.setMessage(completedLabel());
        }).bounds(compact ? 140 : 470, 10, 78, 20).build());
        navigateButton = addRenderableWidget(Button.builder(Component.literal("Navigate"), button ->
            navigateToSelection()).bounds(compact ? 10 : 554, compact ? 66 : 10, 74, 20).build());
        updateButtons();
    }

    private Component completedLabel() {
        return Component.literal(config.completed(context.dimension(), structure.type.name(),
            structure.x, structure.z) ? "Undo" : "Complete");
    }

    private int headerHeight() {
        return width < 700 ? 122 : 66;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xFF0B1117);
        int headerHeight = headerHeight();
        graphics.fill(0, 0, width, headerHeight, 0xF2101820);
        graphics.fill(0, headerHeight - 1, width, headerHeight, 0xFF2C3B48);
        graphics.text(font, ellipsize(structure.displayName(), Math.max(40, width - 490)),
            10, width < 700 ? 94 : 39, 0xFFE7EDF3);
        String meta = structure.getCoordsString() + "  |  Seed " + context.worldSeed()
            + "  |  " + context.minecraftVersion();
        graphics.text(font, ellipsize(meta, Math.max(40, width - 20)), 10,
            width < 700 ? 108 : 52, 0xFF8D9AA6);

        int sidebar = Math.min(SIDEBAR_WIDTH, Math.max(120, width / 3));
        graphics.fill(0, headerHeight, sidebar, height, 0xFF0D141B);
        graphics.fill(sidebar, headerHeight, sidebar + 1, height, 0xFF2C3B48);
        drawContainers(graphics, sidebar, mouseX, mouseY);
        drawSelectedLoot(graphics, sidebar);

        if (!notice.isBlank() && System.currentTimeMillis() < noticeUntil) {
            int noticeWidth = Math.min(width - 24, font.width(notice) + 16);
            int x = (width - noticeWidth) / 2;
            graphics.fill(x, height - 28, x + noticeWidth, height - 8, 0xE8152328);
            graphics.centeredText(font, Component.literal(ellipsize(notice, noticeWidth - 12)),
                width / 2, height - 22, 0xFFE7EDF3);
        }
        updateButtons();
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void drawContainers(GuiGraphicsExtractor graphics, int sidebar, int mouseX, int mouseY) {
        int headerHeight = headerHeight();
        graphics.text(font, "Containers (" + chests.size() + ")", 10, headerHeight + 9, 0xFFE7EDF3);
        if (loading) {
            graphics.text(font, "Predicting exact loot...", 10, headerHeight + 28, 0xFFE2B64C);
            return;
        }
        if (!error.isBlank()) {
            graphics.text(font, ellipsize(error, sidebar - 20), 10, headerHeight + 28, 0xFFFF9388);
            return;
        }
        if (chests.isEmpty()) {
            String status = StandaloneStructureLootPredictor.structureKey(context, structure) == null
                && structure.type != me.seedexplorer.addon.structures.StructureType.DUNGEON
                ? "Prediction unavailable" : "No containers predicted";
            graphics.text(font, status, 10, headerHeight + 28, 0xFF8D9AA6);
            return;
        }
        int y = headerHeight + 28;
        for (int index = containerScroll; index < chests.size(); index++) {
            if (y + ROW_HEIGHT > height - 8) break;
            ChestLootOutput chest = chests.get(index);
            boolean selected = index == selectedChest;
            boolean hovered = mouseX < sidebar && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
            graphics.fill(4, y, sidebar - 4, y + ROW_HEIGHT - 2,
                selected ? 0xFF1D5E67 : hovered ? 0xFF16212B : 0xFF101820);
            if (selected) graphics.fill(4, y, 7, y + ROW_HEIGHT - 2, 0xFF45B6A6);
            graphics.text(font, "Container " + (index + 1), 12, y + 5,
                selected ? 0xFFFFFFFF : 0xFFE7EDF3);
            graphics.text(font, chest.blockX() + ", " + chest.blockY() + ", " + chest.blockZ(),
                12, y + 20, 0xFF8D9AA6);
            y += ROW_HEIGHT;
        }
    }

    private void drawSelectedLoot(GuiGraphicsExtractor graphics, int sidebar) {
        int x = sidebar + 16;
        int contentWidth = Math.max(40, width - x - 12);
        int y = headerHeight() + 12;
        if (loading) return;
        if (selectedChest < 0 || selectedChest >= chests.size()) {
            graphics.text(font, "Select a container to inspect its complete prediction.",
                x, y, 0xFF8D9AA6);
            return;
        }
        ChestLootOutput chest = chests.get(selectedChest);
        graphics.text(font, "Container " + (selectedChest + 1) + " / " + chests.size(),
            x, y, 0xFFE7EDF3);
        y += 16;
        graphics.text(font, ellipsize(chest.blockX() + ", " + chest.blockY() + ", " + chest.blockZ()
            + "  |  " + (chest.exact() ? "Exact" : "Approximate"), contentWidth), x, y,
            chest.exact() ? 0xFF7ED88A : 0xFFD8B45A);
        y += 14;
        graphics.text(font, ellipsize(chest.lootTableId(), contentWidth), x, y, 0xFF6FAEC0);
        y += 14;
        graphics.text(font, ellipsize("Loot seed " + chest.seed(), contentWidth), x, y, 0xFF8D9AA6);
        y += 20;

        List<ItemLoot> items = chest.predictedItems() == null ? List.of() : chest.predictedItems();
        graphics.text(font, "Items (" + items.size() + ")", x, y, 0xFFE7EDF3);
        y += 16;
        if (items.isEmpty()) {
            graphics.text(font, "No predicted items", x, y, 0xFF8D9AA6);
            return;
        }
        for (int index = itemScroll; index < items.size(); index++) {
            if (y + 28 > height - 8) break;
            ItemLoot item = items.get(index);
            boolean enchanted = item.randomEnchantment() || item.enchantmentId() != null
                || item.enchantmentSummary() != null && !item.enchantmentSummary().isBlank();
            graphics.fill(x, y, width - 12, y + 25, enchanted ? 0xFF21182C : 0xFF111A22);
            graphics.fill(x, y, x + 3, y + 25, enchanted ? 0xFF9E6DDB : 0xFF45B6A6);
            String count = item.minCount() == item.maxCount() ? "x" + item.minCount()
                : "x" + item.minCount() + "-" + item.maxCount();
            graphics.text(font, ellipsize(item.displayName() + " " + count, contentWidth - 12),
                x + 8, y + 5, enchanted ? 0xFFD6A8FF : 0xFFE7EDF3);
            String enchantment = item.enchantmentSummary();
            if (enchantment == null || enchantment.isBlank()) enchantment = item.enchantmentId();
            if (enchantment != null && !enchantment.isBlank()) {
                graphics.text(font, ellipsize(enchantment, contentWidth - 12),
                    x + 8, y + 15, 0xFFB98BEA);
            }
            y += 28;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        if (click.button() != 0) return false;
        int sidebar = Math.min(SIDEBAR_WIDTH, Math.max(120, width / 3));
        int headerHeight = headerHeight();
        if (click.x() >= sidebar || click.y() < headerHeight + 28) return false;
        int index = containerScroll + (int) ((click.y() - headerHeight - 28) / ROW_HEIGHT);
        if (index >= 0 && index < chests.size()) {
            selectChest(index);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        int sidebar = Math.min(SIDEBAR_WIDTH, Math.max(120, width / 3));
        int direction = verticalAmount > 0 ? -1 : 1;
        if (mouseX < sidebar) {
            int visible = Math.max(1, (height - headerHeight() - 36) / ROW_HEIGHT);
            containerScroll = clamp(containerScroll + direction, 0,
                Math.max(0, chests.size() - visible));
        } else if (selectedChest >= 0 && selectedChest < chests.size()) {
            List<ItemLoot> items = chests.get(selectedChest).predictedItems();
            int size = items == null ? 0 : items.size();
            int visible = Math.max(1, (height - headerHeight() - 90) / 28);
            itemScroll = clamp(itemScroll + direction, 0, Math.max(0, size - visible));
        }
        return true;
    }

    private void selectChest(int index) {
        if (index < 0 || index >= chests.size()) return;
        selectedChest = index;
        itemScroll = 0;
        int visible = Math.max(1, (height - headerHeight() - 36) / ROW_HEIGHT);
        if (selectedChest < containerScroll) containerScroll = selectedChest;
        if (selectedChest >= containerScroll + visible) containerScroll = selectedChest - visible + 1;
        updateButtons();
    }

    private void updateButtons() {
        if (previousButton != null) previousButton.active = selectedChest > 0;
        if (nextButton != null) nextButton.active = selectedChest >= 0 && selectedChest + 1 < chests.size();
        if (teleportButton != null) teleportButton.active = selectedChest >= 0 && selectedChest < chests.size();
        if (navigateButton != null) navigateButton.active = navigationAvailable;
    }

    private void teleportToSelectedChest() {
        if (selectedChest < 0 || selectedChest >= chests.size()) return;
        ChestLootOutput chest = chests.get(selectedChest);
        sendTeleport(chest.blockX(), chest.blockY(), chest.blockZ());
    }

    private void teleportToStructure() {
        int y = structure.y == GeneratedStructure.UNKNOWN_Y ? 100 : structure.y + 1;
        sendTeleport(structure.x, y, structure.z);
    }

    private void sendTeleport(int x, int y, int z) {
        if (minecraft == null || minecraft.player == null || minecraft.player.connection == null) {
            showNotice("Teleport unavailable outside a world");
            return;
        }
        String dimensionId = switch (context.dimension()) {
            case -1 -> "minecraft:the_nether";
            case 1 -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
        minecraft.player.connection.sendCommand("execute in " + dimensionId
            + " run tp @s " + x + " " + y + " " + z);
        showNotice("Sent teleport to " + x + ", " + y + ", " + z);
    }

    private void navigateToSelection() {
        if (minecraft == null || minecraft.player == null || playerDimension() != context.dimension()) {
            showNotice("Enter the structure dimension before navigating");
            return;
        }
        ChestLootOutput chest = selectedChest >= 0 && selectedChest < chests.size()
            ? chests.get(selectedChest) : null;
        int x = chest == null ? structure.x : chest.blockX();
        int y = chest == null
            ? structure.y == GeneratedStructure.UNKNOWN_Y ? minecraft.player.getBlockY() : structure.y
            : chest.blockY();
        int z = chest == null ? structure.z : chest.blockZ();
        showNotice(navigation.navigate(new BlockPos(x, y, z), 2).message());
    }

    private int playerDimension() {
        if (minecraft == null || minecraft.level == null) return Integer.MIN_VALUE;
        if (minecraft.level.dimension() == Level.NETHER) return -1;
        if (minecraft.level.dimension() == Level.END) return 1;
        return 0;
    }

    private void copyReport() {
        if (minecraft != null && minecraft.keyboardHandler != null) {
            minecraft.keyboardHandler.setClipboard(buildReport());
            showNotice("Copied complete structure loot report");
        }
    }

    private void exportReport() {
        try {
            Path directory = minecraft.gameDirectory.toPath().resolve("seed-explorer-reports");
            Files.createDirectories(directory);
            Path path = directory.resolve("structure-loot-" + sanitize(structure.displayName()) + "-"
                + LocalDateTime.now().format(FILE_TIME) + ".txt");
            Files.writeString(path, buildReport(), StandardCharsets.UTF_8);
            showNotice("Saved " + path.getFileName());
        } catch (Exception failure) {
            showNotice("Export failed: " + failure.getClass().getSimpleName());
        }
    }

    private String buildReport() {
        StringBuilder report = new StringBuilder();
        report.append("Seed Explorer structure loot report\n")
            .append("seed=").append(context.worldSeed()).append('\n')
            .append("version=").append(context.minecraftVersion()).append('\n')
            .append("dimension=").append(context.dimension()).append('\n')
            .append("structure=").append(structure.displayName()).append('\n')
            .append("type=").append(structure.type).append('\n')
            .append("variant=").append(structure.variant).append('\n')
            .append("pos=").append(structure.getCoordsString()).append('\n')
            .append("containers=").append(chests.size()).append('\n');
        for (int index = 0; index < chests.size(); index++) {
            ChestLootOutput chest = chests.get(index);
            report.append("\ncontainer=").append(index + 1).append('\n')
                .append("pos=").append(chest.blockX()).append(',').append(chest.blockY())
                .append(',').append(chest.blockZ()).append('\n')
                .append("table=").append(chest.lootTableId()).append('\n')
                .append("lootSeed=").append(chest.seed()).append('\n')
                .append("exact=").append(chest.exact()).append('\n')
                .append("summary=").append(chest.summary()).append('\n');
        }
        return report.toString();
    }

    private void showNotice(String message) {
        notice = message;
        noticeUntil = System.currentTimeMillis() + 4_000L;
    }

    private String ellipsize(String text, int maximumWidth) {
        if (text == null) return "";
        if (font.width(text) <= maximumWidth) return text;
        String suffix = "...";
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end) + suffix) > maximumWidth) end--;
        return text.substring(0, end) + suffix;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String sanitize(String text) {
        String clean = text == null ? "report" : text.replaceAll("[^A-Za-z0-9._-]+", "-");
        return clean.isBlank() ? "report" : clean;
    }

    @Override
    public void onClose() {
        requests.incrementAndGet();
        config.save();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
