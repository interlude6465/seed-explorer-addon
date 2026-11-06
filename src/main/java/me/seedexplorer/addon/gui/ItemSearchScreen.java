package me.seedexplorer.addon.gui;

import me.seedexplorer.addon.loot.ItemLootRequest;
import me.seedexplorer.addon.loot.LootSearchParser;
import me.seedexplorer.addon.loot.LootSearchTerm;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Dedicated multi-item, quantity-aware search editor. */
public final class ItemSearchScreen extends WindowScreen {
    private final List<ItemLootRequest> requests = new ArrayList<>();
    private final Consumer<List<ItemLootRequest>> searchAction;
    private WTextBox itemBox;
    private WIntEdit quantityEdit;
    private WTable suggestionsTable;

    public ItemSearchScreen(GuiTheme theme, List<ItemLootRequest> current,
                            Consumer<List<ItemLootRequest>> searchAction) {
        super(theme, "Item Search");
        window.minWidth = 480;
        if (current != null) requests.addAll(current);
        this.searchAction = searchAction;
    }

    @Override
    public void initWidgets() {
        add(theme.label("Add one or more items and the amount needed."));

        WHorizontalList addRow = add(theme.horizontalList()).expandX().widget();
        itemBox = addRow.add(theme.textBox("", "Item or enchanted book..."))
            .minWidth(300).expandX().widget();
        itemBox.action = this::updateSuggestions;
        quantityEdit = addRow.add(theme.intEdit(1, 1, 2304, true)).widget();
        quantityEdit.noSlider = true;
        WButton addButton = addRow.add(theme.button("Add")).widget();
        addButton.action = this::addRequest;

        suggestionsTable = add(theme.table()).expandX().widget();
        rebuildRows();
        enterAction = this::addRequest;
    }

    private void updateSuggestions() {
        if (suggestionsTable == null || itemBox == null) return;
        suggestionsTable.clear();
        for (LootSearchTerm suggestion : LootSearchParser.suggest(itemBox.get(), 5)) {
            WButton option = suggestionsTable.add(theme.button(suggestion.display())).expandX().widget();
            option.action = () -> selectSuggestion(suggestion);
            suggestionsTable.row();
        }
    }

    private void selectSuggestion(LootSearchTerm suggestion) {
        itemBox.set(suggestion.display());
        itemBox.setCursorMax();
        itemBox.setFocused(true);
        suggestionsTable.clear();
    }

    private void rebuildRows() {
        WTable table = add(theme.table()).expandX().widget();
        if (requests.isEmpty()) {
            table.add(theme.label("No items added yet.")).expandX();
        } else {
            table.add(theme.label("Item"));
            table.add(theme.label("Quantity"));
            table.add(theme.label(""));
            table.row();
            for (int index = 0; index < requests.size(); index++) {
                ItemLootRequest request = requests.get(index);
                table.add(theme.label(request.term().display())).expandX();
                WIntEdit edit = table.add(theme.intEdit(request.quantity(), 1, 2304, true)).widget();
                edit.noSlider = true;
                int requestIndex = index;
                edit.action = () -> requests.set(requestIndex,
                    new ItemLootRequest(request.term(), edit.get()));
                WButton remove = table.add(theme.button("Remove")).widget();
                remove.action = () -> {
                    requests.remove(requestIndex);
                    reload();
                };
                table.row();
            }
        }

        WHorizontalList actions = add(theme.horizontalList()).expandX().widget();
        WButton search = actions.add(theme.button("Search chests")).expandX().widget();
        search.action = () -> {
            if (requests.isEmpty()) return;
            Minecraft.getInstance().setScreen(parent);
            searchAction.accept(List.copyOf(requests));
        };
        WButton clear = actions.add(theme.button("Clear")).widget();
        clear.action = () -> {
            requests.clear();
            reload();
        };
    }

    private void addRequest() {
        if (itemBox == null) return;
        LootSearchTerm term = LootSearchParser.parseTerm(itemBox.get());
        if (term == null) {
            itemBox.set("Item not found");
            itemBox.setFocused(true);
            return;
        }
        int quantity = quantityEdit == null ? 1 : quantityEdit.get();
        for (int i = 0; i < requests.size(); i++) {
            ItemLootRequest current = requests.get(i);
            if (current.term().signature().equals(term.signature())) {
                requests.set(i, new ItemLootRequest(term, current.quantity() + quantity));
                reload();
                return;
            }
        }
        requests.add(new ItemLootRequest(term, quantity));
        reload();
    }
}
