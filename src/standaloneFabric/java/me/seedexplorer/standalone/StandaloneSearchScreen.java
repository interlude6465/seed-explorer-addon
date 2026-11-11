package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.*;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
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
    private final List<ItemLootRequest> requests;
    private final StandaloneSearchRuntime.Session session;
    private final List<Button> requestButtons=new ArrayList<>();
    private final List<Button> requestPropertyButtons=new ArrayList<>();
    private int requestPage;
    private int requestPageSize=4;
    private Button enchantButton;
    private EditBox query, quantity;
    private int mode, radius = 1024, page;
    private boolean allDimensions = true;
    private int selectedRequest;
    private StandaloneSearch.Job job;
    private List<StandaloneSearch.Result> shown = List.of();
    private String message = "";
    private String queryText = "";
    private String quantityText = "1";
    private final List<Button> resultButtons = new ArrayList<>();
    private final List<Button> resultActionButtons = new ArrayList<>();
    private final List<Button> suggestionButtons = new ArrayList<>();
    private String suggestedQuery = "";
    private List<LootSearchTerm> suggestions = List.of();

    public StandaloneSearchScreen(Screen parent, StandaloneSeedExplorerConfig config,
                                  PredictionContext context, int x, int z,
                                  Consumer<StandaloneSearch.Result> select) {
        super(Component.literal("Search Seed Explorer - " + context.minecraftVersion()));
        this.parent = parent; this.config = config;
        session=StandaloneSearchRuntime.attach(net.minecraft.client.Minecraft.getInstance(),config,context,x,z);
        this.context=session.context;requests=session.requests;job=session.job;
        queryText=session.query;quantityText=session.quantity;
        originX = session.x; originZ = session.z; this.select = select;
        mode = config.intValue("search-mode", 0, 0, 3);
        radius = config.intValue("search-radius", 1024, 0, 8192);
        allDimensions = config.booleanValue("search-all-dimensions", true);
    }

    @Override protected void init() {
        int left = Math.max(10, (width - 540) / 2);
        int available = Math.min(540, width - 20);
        int third = (available - 12) / 3;
        addRenderableWidget(Button.builder(Component.literal(modeName()), b -> {
            queryText = query.getValue(); mode = (mode + 1) % 4; saveOptions(); rebuildWidgets();
        }).bounds(left, 30, third, 20).build());
        addRenderableWidget(Button.builder(Component.literal(radiusLabel()), b -> {
            radius = radius==0?512:radius >= 8192 ? 0 : radius * 2;
            saveOptions();
            b.setMessage(Component.literal(radiusLabel()));
        }).bounds(left + third + 6, 30, third, 20).build());
        addRenderableWidget(Button.builder(Component.literal(allDimensions ? "All dimensions" : "This dimension"), b -> {
            allDimensions = !allDimensions; b.setMessage(Component.literal(allDimensions ? "All dimensions" : "This dimension"));
            suggestedQuery = null; saveOptions();
        }).bounds(left + 2 * (third + 6), 30, third, 20).build());
        query = addRenderableWidget(new EditBox(font, left, 56, Math.max(80, available - 184), 20,
            Component.literal("Structure, biome, coordinates, or item")));
        query.setMaxLength(256); query.setValue(queryText);
        query.setResponder(value -> {queryText = value;session.query=value;});
        enchantButton=addRenderableWidget(Button.builder(Component.literal("..."),b->editEnchantments())
            .bounds(left+available-178,56,30,20).build());
        quantity = addRenderableWidget(new EditBox(font, left + available - 142, 56, 44, 20,
            Component.literal("Quantity")));
        quantity.setValue(quantityText); quantity.visible = mode == 3;
        quantity.setResponder(value -> {quantityText = value;session.quantity=value;});
        addRenderableWidget(Button.builder(Component.literal(mode == 3 ? "Add item" : "Search"), b -> {
            if (mode == 3) addItem(); else start();
        }).bounds(left + available - 92, 56, 92, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Search"), b -> start())
            .bounds(left, 82, 76, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear items"), b -> {
            cancelForEdit();requests.clear(); selectedRequest = 0; message = "";rebuildWidgets();
        })
            .bounds(left + 82, 82, 88, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel search"), b -> {
            if (job != null) job.cancel(); message = "Search cancelled";
        }).bounds(left + 176, 82, 100, 20).build());
        Button editItem = addRenderableWidget(Button.builder(Component.literal("Edit item"), b -> {
            if (requests.isEmpty()) return;
            cancelForEdit();
            ItemLootRequest request = requests.remove(Math.min(selectedRequest, requests.size() - 1));
            selectedRequest = Math.max(0, Math.min(selectedRequest, requests.size() - 1));
            query.setValue(request.term().display());
            quantity.setValue(Integer.toString(request.quantity()));
            message = "Edit item and add it again";
        }).bounds(left, height - 54, 82, 20).build());
        Button removeItem = addRenderableWidget(Button.builder(Component.literal("Remove item"), b -> {
            if (requests.isEmpty()) return;
            cancelForEdit();
            requests.remove(Math.min(selectedRequest, requests.size() - 1));
            selectedRequest = Math.max(0, Math.min(selectedRequest, requests.size() - 1));
            rebuildWidgets();
        }).bounds(left + 88, height - 54, 94, 20).build());
        Button nextItem = addRenderableWidget(Button.builder(Component.literal("Next item"), b -> {
            if (!requests.isEmpty()) selectedRequest = (selectedRequest + 1) % requests.size();
        }).bounds(left + 188, height - 54, 82, 20).build());
        editItem.visible = removeItem.visible = nextItem.visible = mode == 3;
        resultButtons.clear();
        resultActionButtons.clear();
        suggestionButtons.clear();
        suggestedQuery = "";
        for (int i = 0; i < 3; i++) {
            int index = i;
            Button suggestion = addRenderableWidget(Button.builder(Component.empty(), b -> {
                if (index < suggestions.size()) {
                    LootSearchTerm term = suggestions.get(index);
                    mode = 3;
                    saveOptions();
                    rebuildWidgets();
                    query.setValue(term.enchantment() == null ? term.itemId() : term.display());
                    addItem();
                }
            }).bounds(left + i * (third + 6), 124, third, 20).build());
            suggestion.visible = false; suggestionButtons.add(suggestion);
        }
        requestButtons.clear();requestPropertyButtons.clear();
        requestPageSize=Math.max(1,Math.min(4,(height-260)/22));
        requestPage=Math.min(requestPage,Math.max(0,(requests.size()-1)/requestPageSize));
        int requestRows=mode==3?Math.min(requestPageSize,Math.max(0,requests.size())):0;
        for(int i=0;i<requestRows;i++) {
            int index=requestPage*requestPageSize+i;
            if(index>=requests.size())break;
            var request=requests.get(index);
            Button row=addRenderableWidget(Button.builder(Component.empty(),b->{selectedRequest=index;})
                .bounds(left,124+i*22,available-36,20).build());requestButtons.add(row);
            Button properties=addRenderableWidget(Button.builder(Component.literal("..."),b->{selectedRequest=index;editRequestEnchantments(index);})
                .bounds(left+available-30,124+i*22,30,20).build());requestPropertyButtons.add(properties);
            properties.active=!StandaloneSearch.enchantmentOptions(context,request.term().itemId(),allDimensions).isEmpty();
        }
        if(requests.size()>requestPageSize && mode==3)addRenderableWidget(Button.builder(Component.literal("Items >"),b->{requestPage=(requestPage+1)%((requests.size()+requestPageSize-1)/requestPageSize);rebuildWidgets();})
            .bounds(left+282,82,70,20).build());
        int resultTop=150+Math.max(0,requestRows-1)*22;
        int rows = Math.max(1, (height - resultTop - 92) / 24);
        for (int i = 0; i < rows; i++) {
            final int row = i;
            Button button = addRenderableWidget(Button.builder(Component.empty(), b -> {
                int index = page * resultButtons.size() + row;
                if (index < shown.size()) { onClose(); select.accept(shown.get(index)); }
            }).bounds(left, resultTop + i * 24, available - 96, 20).build());
            button.visible = false; resultButtons.add(button);
            Button view3d = addRenderableWidget(Button.builder(Component.literal("3D"), b -> {
                int index = page * resultButtons.size() + row;
                if (index >= shown.size()) return;
                StandaloneSearch.Result result = shown.get(index);
                List<me.seedexplorer.addon.structures.GeneratedStructure> structures = result.structure() == null
                    ? List.of() : List.of(result.structure());
                List<ChestLootOutput> chests = result.chest() == null ? null : List.of(result.chest());
                minecraft.gui.setScreen(new StandaloneTerrain3DScreen(this, config,
                    context.worldSeed(), result.dimension(), structures)
                    .openAt(result.x(), result.z(), result.structure(), chests, 0));
            }).bounds(left + available - 92, resultTop + i * 24, 42, 20).build());
            Button pin = addRenderableWidget(Button.builder(Component.literal("Pin"), b -> {
                int index = page * resultButtons.size() + row;
                if (index >= shown.size()) return;
                StandaloneSearch.Result result = shown.get(index);
                config.addWaypoint(context.worldSeed(), result.dimension(), result.x(), result.z(),
                    result.label(), result.structure() == null ? null : result.structure().type.name());
                config.save();
                message = "Waypoint added at " + result.x() + ", " + result.z();
            }).bounds(left + available - 46, resultTop + i * 24, 46, 20).build());
            view3d.visible = false;
            pin.visible = false;
            resultActionButtons.add(view3d);
            resultActionButtons.add(pin);
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
    private void cancelForEdit(){if(job!=null&&!job.done)job.cancel();session.job=null;job=null;shown=List.of();}
    private void editEnchantments() {
        LootSearchTerm term=LootSearchParser.parseTerm(query.getValue());
        if(term==null)return;
        minecraft.gui.setScreen(new StandaloneSearchEnchantmentsScreen(this,context,term,allDimensions,
            updated->{queryText=updated.display();session.query=queryText;}));
    }
    private void editRequestEnchantments(int index) {
        ItemLootRequest request=requests.get(index);
        minecraft.gui.setScreen(new StandaloneSearchEnchantmentsScreen(this,context,request.term(),allDimensions,
            updated->{cancelForEdit();requests.set(index,new ItemLootRequest(updated,request.quantity()));}));
    }

    private String modeName() { return switch (mode) { case 1 -> "Biome"; case 2 -> "Coordinates";
        case 3 -> "Items / books"; default -> "Auto search"; }; }
    private String radiusLabel(){return radius==0?"Radius: Infinite":"Radius: "+radius;}

    private void addItem() {
        try {
            LootSearchTerm term = LootSearchParser.parseTerm(query.getValue());
            if (term == null) { message = "Item not found"; return; }
            if (!StandaloneSearch.validLootTerm(context, term, allDimensions)) {
                message = "No supported chest loot for this item or enchantment";
                return;
            }
            int amount = Math.max(1, Math.min(2304, Integer.parseInt(quantity.getValue().trim())));
            cancelForEdit();
            int existing = -1;
            for (int i = 0; i < requests.size(); i++) if (requests.get(i).term().signature().equals(term.signature())) existing = i;
            if (existing >= 0) requests.set(existing, new ItemLootRequest(term, requests.get(existing).quantity() + amount));
            else requests.add(new ItemLootRequest(term, amount));
            selectedRequest = existing >= 0 ? existing : requests.size() - 1;
            message = "Added " + term.display(); query.setValue("");
            rebuildWidgets();
        } catch (IllegalArgumentException failure) { message = "Enter a valid item, enchantment and whole-number quantity"; }
    }

    private void start() {
        if(session!=StandaloneSearchRuntime.current()) {message="World, seed or version changed. Reopen search.";return;}
        queryText = query.getValue().trim(); page = 0; message = "";
        if (job != null) job.cancel();
        if (mode == 0 && (queryText.toLowerCase(Locale.ROOT).startsWith("loot:")
            || queryText.toLowerCase(Locale.ROOT).startsWith("item:"))) {
            queryText = queryText.substring(queryText.indexOf(':') + 1).trim();
            query.setValue(queryText); mode = 3;
            rebuildWidgets();
        }
        if (mode == 0 && StandaloneSearch.automaticItem(context, queryText, allDimensions) != null) {
            mode = 3;
            saveOptions();
            rebuildWidgets();
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
            if (!query.getValue().isBlank()) return;
            if(requests.stream().anyMatch(request->!StandaloneSearch.validLootTerm(context,request.term(),allDimensions))) {
                message="A request is incompatible or unavailable in the selected dimensions.";return;
            }
            message = "";
            job = StandaloneSearch.loot(context, originX, originZ, radius, requests, allDimensions);
        } else if(radius==0) {message="Infinite radius is available for item searches.";return;}
        else if (queryText.isBlank()) { message = "Enter a search"; return; }
        else if (mode == 1) job = StandaloneSearch.biomes(context, originX, originZ, radius, queryText);
        else job = StandaloneSearch.natural(context, originX, originZ, radius, queryText);
        session.job=job;
        shown = List.of(); updateResults();
    }

    @Override public void tick() {
        if(session!=StandaloneSearchRuntime.current()) {if(job!=null)job.cancel();message="World, seed or version changed. Reopen search.";return;}
        if (job != null) {
            List<StandaloneSearch.Result> results=job.results.isEmpty()&&!job.done?job.previews:job.results;
            if(shown!=results) {shown=results;updateResults();}
        }
        for(int i=0;i<requestButtons.size();i++) {
            int index=requestPage*requestPageSize+i;if(index>=requests.size())break;
            var request=requests.get(index);String state=job==null?"Ready":job.requestState(request);
            requestButtons.get(i).setMessage(Component.literal(shorten(state+" | "+request.term().display()+" x"+request.quantity(),requestButtons.get(i).getWidth()-12))
                .withStyle(state.equals("Searching")?net.minecraft.ChatFormatting.RED:net.minecraft.ChatFormatting.WHITE));
            requestButtons.get(i).setTooltip(Tooltip.create(Component.literal(request.term().display()+" x"+request.quantity()+" | "+state
                +(job==null?"":"\n"+job.status))));
        }
        String text = mode == 3 || mode == 0 ? query.getValue() : "";
        if (!text.equals(suggestedQuery)) {
            suggestedQuery = text;
            suggestions = LootSearchParser.suggest(text, 3, StandaloneSearch.chestItemIds(context, allDimensions))
                .stream().filter(term -> StandaloneSearch.validLootTerm(context, term, allDimensions)).toList();
            for (int i = 0; i < suggestionButtons.size(); i++) {
                Button button = suggestionButtons.get(i);
                button.visible = i < suggestions.size();
                if (button.visible) button.setMessage(Component.literal(shorten(suggestions.get(i).display(), button.getWidth() - 12)));
            }
            LootSearchTerm editTerm=null;
            try {editTerm=LootSearchParser.parseTerm(text);}catch(IllegalArgumentException ignored){}
            enchantButton.active=editTerm!=null&&!StandaloneSearch.enchantmentOptions(context,editTerm.itemId(),allDimensions).isEmpty();
        }
        // Suggestions temporarily occupy the first request row while typing;
        // the full request list returns immediately when the item is added.
        boolean suggesting=suggestionButtons.stream().anyMatch(b->b.visible);
        for(int i=0;i<requestButtons.size();i++) {
            requestButtons.get(i).visible=i>0 || !suggesting;
            requestPropertyButtons.get(i).visible=i>0 || !suggesting;
        }
    }
    private void saveOptions() {
        config.setInt("search-mode", mode, 0, 3);
        config.setInt("search-radius", radius, 0, 8192);
        config.setBoolean("search-all-dimensions", allDimensions);
        config.save();
    }
    private void updateResults() {
        for (int i = 0; i < resultButtons.size(); i++) {
            int index = page * resultButtons.size() + i;
            Button b = resultButtons.get(i); b.visible = index < shown.size();
            resultActionButtons.get(i * 2).visible = b.visible;
            resultActionButtons.get(i * 2 + 1).visible = b.visible;
            if (b.visible) {
                StandaloneSearch.Result result = shown.get(index);
                String description = result.label() + "  " + result.x() + ", " + result.y()
                    + ", " + result.z() + "  [" + dimensionName(result.dimension()) + "]";
                b.setMessage(Component.literal(shorten(description, b.getWidth() - 12)));
                b.setTooltip(Tooltip.create(Component.literal(description)));
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
        if(job!=null && shown==job.previews && !shown.isEmpty())text="Provisional results; nearest dungeon checks running. "+job.status;
        graphics.text(font, shorten(text, width - 24), 12, 110, 0xFFE3EAD8);
        if (mode == 3 && !requests.isEmpty()) graphics.text(font,
            "Requests "+(requestPage+1)+"/"+((requests.size()+requestPageSize-1)/requestPageSize)
                +" | selected "+(Math.min(selectedRequest,requests.size()-1)+1)+" | results "+shown.size(),
                12, height - 65, 0xFF8FD7A0);
        super.extractRenderState(graphics, mx, my, delta);
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
