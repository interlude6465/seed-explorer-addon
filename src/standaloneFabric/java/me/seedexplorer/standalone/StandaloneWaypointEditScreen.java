package me.seedexplorer.standalone;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

public final class StandaloneWaypointEditScreen extends Screen {
    private final Screen parent;
    private final StandaloneSeedExplorerConfig config;
    private final StandaloneWaypoint waypoint;
    private final Consumer<StandaloneWaypoint> changed;
    private EditBox name, x, z;
    private String error = "";
    public StandaloneWaypointEditScreen(Screen parent, StandaloneSeedExplorerConfig config,
                                        StandaloneWaypoint waypoint, Consumer<StandaloneWaypoint> changed) {
        super(Component.literal("Edit waypoint"));
        this.parent = parent; this.config = config; this.waypoint = waypoint; this.changed = changed;
    }
    @Override protected void init() {
        int left = Math.max(20, (width - 300) / 2), size = Math.min(300, width - 40);
        name = addRenderableWidget(new EditBox(font, left, 48, size, 20, Component.literal("Name")));
        name.setMaxLength(128); name.setValue(waypoint.name());
        x = addRenderableWidget(new EditBox(font, left, 88, (size - 8) / 2, 20, Component.literal("X")));
        z = addRenderableWidget(new EditBox(font, left + (size + 8) / 2, 88, (size - 8) / 2, 20, Component.literal("Z")));
        x.setValue(Integer.toString(waypoint.x())); z.setValue(Integer.toString(waypoint.z()));
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
            try {
                int bx = Integer.parseInt(x.getValue().trim()), bz = Integer.parseInt(z.getValue().trim());
                if (Math.abs((long)bx) > 30_000_000 || Math.abs((long)bz) > 30_000_000) throw new NumberFormatException();
                StandaloneWaypoint updated = config.updateWaypoint(waypoint.id(), name.getValue(), bx, bz);
                onClose(); changed.accept(updated);
            } catch (NumberFormatException failure) { error = "Enter coordinates within the world border."; }
              catch (IllegalArgumentException failure) { error = failure.getMessage(); }
        }).bounds(left, 142, (size - 8) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
            .bounds(left + (size + 8) / 2, 142, (size - 8) / 2, 20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xFF101619);
        g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        g.text(font, "Name", name.getX(), 36, 0xFFFFFFFF);
        g.text(font, "X", x.getX(), 76, 0xFFFFFFFF); g.text(font, "Z", z.getX(), 76, 0xFFFFFFFF);
        g.centeredText(font, error, width / 2, 119, 0xFFFF8888);
        super.extractRenderState(g, mx, my, delta);
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
