package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.*;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;

/** Native search editor, including quantity-aware multi-item and enchanted-book searches. */
public final class StandaloneSearchScreen extends Screen {
    private final Screen parent;
    private final StandaloneSeedExplorerConfig config;
    private final PredictionContext context;
    private final int originX, originZ;
    private final Consumer<StandaloneSearch.Result> select;
    private final List<ItemLootRequest> requests = new ArrayList<>();
    private EditBox query, quantity;
    private int mode, radius = 1024, page;
    private boolean allDimensions = true;
    private StandaloneSearch.Job job;
    private List<StandaloneSearch.Result> shown = List.of();
    private String message = "";
    private String queryText = "";
    private final List<Button> resultButtons = new ArrayList<>();
    private final List<Button> suggestionButtons = new ArrayList<>();
    private String suggestedQuery = "";
    private List<LootSearchTerm> suggestions = List.of();

    public StandaloneSearchScreen(Screen parent, StandaloneSeedExplorerConfig config,
                                  PredictionContext context, int x, int z,
                                  Consumer<StandaloneSearch.Result> select) {
        super(Component.literal("Search Seed Explorer"));
        this.parent = parent; this.config = config; this.context = context;
        originX = x; originZ = z; this.select = select;
    }

    @Override protected void init() {
        int left = Math.max(10, (width - 540) / 2);
        int available = Math.min(540, width - 20);
        int third = (available - 12) / 3;
        addRenderableWidget(Button.builder(Component.literal(modeName()), b -> {
            queryText = query.getValue(); mode = (mode + 1) % 4; rebuildWidgets();
        }).bounds(left, 30, third, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Radius: " + radius), b -> {
            radius = radius >= 8192 ? 512 : radius * 2;
            b.setMessage(Component.literal("Radius: " + radius));
        }).bounds(left + third + 6, 30, third, 20).build());
        addRenderableWidget(Button.builder(Component.literal(allDimensions ? "All dimensions" : "This dimension"), b -> {
            allDimensions = !allDimensions; b.setMessage(Component.literal(allDimensions ? "All dimensions" : "This dimension"));
        }).bounds(left + 2 * (third + 6), 30, third, 20).build());
        query = addRenderableWidget(new EditBox(font, left, 56, Math.max(80, available - 148), 20,
            Component.literal("Structure, biome, coordinates, or item")));
        query.setMaxLength(256); query.setValue(queryText);
        quantity = addRenderableWidget(new EditBox(font, left + available - 142, 56, 44, 20,
            Component.literal("Quantity")));
        quantity.setValue("1"); quantity.visible = mode == 3;
        addRenderableWidget(Button.builder(Component.literal(mode == 3 ? "Add item" : "Search"), b -> {
            if (mode == 3) addItem(); else start();
        }).bounds(left + available - 92, 56, 92, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Search"), b -> start())
            .bounds(left, 82, 76, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear items"), b -> { requests.clear(); message = ""; })
            .bounds(left + 82, 82, 88, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel search"), b -> {
            if (job != null) job.cancel(); message = "Search cancelled";
        }).bounds(left + 176, 82, 100, 20).build());
        resultButtons.clear();
        suggestionButtons.clear();
        suggestedQuery = "";
        for (int i = 0; i < 3; i++) {
            int index = i;
            Button suggestion = addRenderableWidget(Button.builder(Component.empty(), b -> {
                if (index < suggestions.size()) {
                    query.setValue(suggestions.get(index).itemId());
                    addItem();
                }
            }).bounds(left + i * (third + 6), 124, third, 20).build());
            suggestion.visible = false; suggestionButtons.add(suggestion);
        }
        int rows = Math.max(1, (height - 208) / 24);
        for (int i = 0; i < rows; i++) {
            final int row = i;
            Button button = addRenderableWidget(Button.builder(Component.empty(), b -> {
                int index = page * resultButtons.size() + row;
                if (index < shown.size()) { StandaloneSearch.Result result = shown.get(index);
                    onClose(); select.accept(result); }
            }).bounds(left, 150 + i * 24, available, 20).build());
            button.visible = false; resultButtons.add(button);
        }
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page = Math.max(0, page - 1); updateResults(); })
            .bounds(left, height - 30, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page = Math.min(Math.max(0, (shown.size() - 1) / resultButtons.size()), page + 1); updateResults();
        }).bounds(left + 36, height - 30, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(left + available - 80, height - 30, 80, 20).build());
        updateResults();
    }

    private String modeName() { return switch (mode) { case 1 -> "Biome"; case 2 -> "Coordinates";
        case 3 -> "Items / books"; default -> "Auto search"; }; }

    private void addItem() {
        try {
            LootSearchTerm term = LootSearchParser.parseTerm(query.getValue());
            if (term == null) { message = "Item not found"; return; }
            int amount = Math.max(1, Math.min(2304, Integer.parseInt(quantity.getValue().trim())));
            int existing = -1;
            for (int i = 0; i < requests.size(); i++) if (requests.get(i).term().signature().equals(term.signature())) existing = i;
            if (existing >= 0) requests.set(existing, new ItemLootRequest(term, requests.get(existing).quantity() + amount));
            else requests.add(new ItemLootRequest(term, amount));
            message = "Added " + term.display(); query.setValue("");
        } catch (NumberFormatException failure) { message = "Enter a whole-number quantity"; }
    }

    private void start() {
        queryText = query.getValue().trim(); page = 0; message = "";
        if (job != null) job.cancel();
        if (mode == 0 && (queryText.toLowerCase(Locale.ROOT).startsWith("loot:")
            || queryText.toLowerCase(Locale.ROOT).startsWith("item:"))) {
            queryText = queryText.substring(queryText.indexOf(':') + 1).trim();
            query.setValue(queryText); mode = 3;
        }
        if (mode == 2 || mode == 0 && queryText.matches("[-+]?\\d+[ ,]+[-+]?\\d+(?:[ ,]+[-+]?\\d+)?")) {
            try {
                String[] parts = queryText.replace(',', ' ').trim().split("\\s+");
                if (parts.length != 2 && parts.length != 3) throw new NumberFormatException();
                int x = Integer.parseInt(parts[0]), z = Integer.parseInt(parts[parts.length - 1]);
                int y = parts.length == 3 ? Integer.parseInt(parts[1]) : 80;
                onClose(); select.accept(new StandaloneSearch.Result(context.dimension(), x, y, z, "Coordinates", null, null));
            } catch (NumberFormatException failure) { message = "Enter X Z or X Y Z"; }
            return;
        }
        if (mode == 3) {
            if (!queryText.isBlank()) addItem();
            if (requests.isEmpty()) { message = "Add at least one item"; return; }
            job = StandaloneSearch.loot(context, originX, originZ, radius, requests, allDimensions);
        } else if (queryText.isBlank()) { message = "Enter a search"; return; }
        else if (mode == 1) job = StandaloneSearch.biomes(context, originX, originZ, radius, queryText);
        else job = StandaloneSearch.natural(context, originX, originZ, radius, queryText);
        shown = List.of(); updateResults();
    }

    @Override public void tick() {
        if (job != null && shown != job.results) { shown = job.results; updateResults(); }
        String text = mode == 3 ? query.getValue() : "";
        if (!text.equals(suggestedQuery)) {
            suggestedQuery = text;
            suggestions = LootSearchParser.suggest(text, 3);
            for (int i = 0; i < suggestionButtons.size(); i++) {
                Button button = suggestionButtons.get(i);
                button.visible = i < suggestions.size();
                if (button.visible) button.setMessage(Component.literal(shorten(suggestions.get(i).display(), button.getWidth() - 12)));
            }
        }
    }
    private void updateResults() {
        for (int i = 0; i < resultButtons.size(); i++) {
            int index = page * resultButtons.size() + i;
            Button b = resultButtons.get(i); b.visible = index < shown.size();
            if (b.visible) {
                StandaloneSearch.Result result = shown.get(index);
                b.setMessage(Component.literal(shorten(result.label() + "  " + result.x() + ", " + result.y()
                    + ", " + result.z() + "  [" + dimensionName(result.dimension()) + "]", b.getWidth() - 12)));
            }
        }
    }
    private String shorten(String text, int max) {
        if (font.width(text) <= max) return text;
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end) + "...") > max) end--;
        return text.substring(0, end) + "...";
    }
    private static String dimensionName(int dimension) { return dimension == -1 ? "Nether" : dimension == 1 ? "End" : "Overworld"; }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
        graphics.fill(0, 0, width, height, 0xFF101619);
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        String text = !message.isBlank() ? message : job == null ? "Choose a search and radius" : job.status;
        graphics.text(font, shorten(text, width - 24), 12, 110, 0xFFE3EAD8);
        if (mode == 3 && !requests.isEmpty()) graphics.text(font,
            shorten(requests.stream().map(r -> r.term().display() + " x" + r.quantity()).reduce((a,b) -> a + ", " + b).orElse(""), width - 180),
            82, height - 24, 0xFF8FD7A0);
        super.extractRenderState(graphics, mx, my, delta);
    }
    @Override public void onClose() { if (job != null) job.cancel(); minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
