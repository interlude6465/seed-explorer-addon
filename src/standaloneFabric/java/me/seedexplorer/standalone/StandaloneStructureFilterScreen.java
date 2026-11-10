package me.seedexplorer.standalone;

import me.seedexplorer.addon.structures.StructureType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Persistent per-world marker visibility, independent of the current search filter. */
public final class StandaloneStructureFilterScreen extends Screen {
    private final Screen parent;
    private final StandaloneSeedExplorerConfig config;
    private int page;
    public StandaloneStructureFilterScreen(Screen parent, StandaloneSeedExplorerConfig config) {
        super(Component.literal("Structure visibility"));
        this.parent = parent; this.config = config;
    }
    @Override protected void init() {
        StructureType[] types = StructureType.values();
        int rows = Math.max(1, (height - 90) / 24);
        page = Math.min(page, (types.length - 1) / rows);
        for (int i = page * rows; i < Math.min(types.length, (page + 1) * rows); i++) {
            StructureType type = types[i];
            addRenderableWidget(Button.builder(label(type), b -> {
                config.setStructureVisible(type.name(), !config.structureVisible(type.name()));
                config.save(); b.setMessage(label(type));
            }).bounds(20, 36 + (i % rows) * 24, width - 40, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page = Math.max(0, page - 1); rebuildWidgets();
        }).bounds(20, height - 30, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page = Math.min((types.length - 1) / rows, page + 1); rebuildWidgets();
        }).bounds(56, height - 30, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(width - 100, height - 30, 80, 20).build());
    }
    private Component label(StructureType type) {
        return Component.literal(type.displayName + ": " + (config.structureVisible(type.name()) ? "Shown" : "Hidden"));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        graphics.fill(0, 0, width, height, 0xFF101619);
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        super.extractRenderState(graphics, x, y, delta);
    }
    @Override public void onClose() { config.save(); minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
