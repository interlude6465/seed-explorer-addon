package me.seedexplorer.standalone;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Compact command palette for secondary 2D map actions. */
public final class StandaloneMapActionsScreen extends Screen {
    private final Screen parent;
    private final List<Action> actions;

    public StandaloneMapActionsScreen(Screen parent, List<Action> actions) {
        super(Component.literal("Map actions"));
        this.parent = parent;
        this.actions = List.copyOf(actions);
    }

    @Override
    protected void init() {
        int columns = width < 240 ? 1 : 2;
        int gap = 6;
        int available = Math.min(420, width - 24);
        int buttonWidth = (available - gap * (columns - 1)) / columns;
        int left = (width - available) / 2;
        int top = 34;
        int rows = (actions.size() + columns - 1) / columns;
        int doneY = height - 30;
        int rowStep = rows <= 1 ? 26 : Math.max(21,
            Math.min(26, (doneY - top - 24) / (rows - 1)));
        for (int index = 0; index < actions.size(); index++) {
            Action action = actions.get(index);
            int column = index % columns;
            int row = index / columns;
            Button button = Button.builder(Component.literal(action.label()), ignored -> {
                minecraft.gui.setScreen(parent);
                action.command().run();
            }).bounds(left + column * (buttonWidth + gap), top + row * rowStep,
                buttonWidth, 20).build();
            button.active = action.enabled();
            addRenderableWidget(button);
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), ignored -> onClose())
            .bounds(Math.max(12, width - 92), doneY, 80, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float delta) {
        graphics.fill(0, 0, width, height, 0xFF101619);
        graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public record Action(String label, boolean enabled, Runnable command) {
    }
}
