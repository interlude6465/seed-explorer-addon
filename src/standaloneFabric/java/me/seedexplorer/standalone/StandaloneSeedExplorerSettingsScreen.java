package me.seedexplorer.standalone;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Native replacement for the old Meteor module settings panel. */
public final class StandaloneSeedExplorerSettingsScreen extends Screen {
    private static final int ROW_HEIGHT = 28;
    private static final List<Category> CATEGORIES = List.of(
        new Category("General", List.of(
            bool("map-ore-overlay", "Map ore overlay", false,
                "Shows predicted ore locations on the map. Heavy and off by default.", true))),
        new Category("Map", List.of(
            decimal("zoom-step", "Zoom step", 1.2, 1.01, 2.0, 2,
                "How quickly the map zooms with the mouse wheel.", true),
            decimal("min-zoom", "Minimum zoom", 0.01, 0.001, 10.0, 3,
                "Minimum map magnification (pixels per block).", true),
            decimal("max-zoom", "Maximum zoom", 100.0, 1.0, 200.0, 1,
                "Maximum map magnification (pixels per block).", true),
            bool("loaded-terrain-on-map", "Loaded terrain overlay", true,
                "Overlays loaded client terrain when chunks are available.", true),
            bool("minimap", "Minimap", true,
                "Shows a compact Seed Explorer minimap.", true),
            keybind("open-map-key", "Open map key", "Configure the key in Minecraft Controls."),
            integer("minimap-size", "Minimap size", 150, 90, 260,
                "Size of the compact minimap.", true))),
        new Category("Structures", List.of(
            bool("structure-tooltips", "Structure tooltips", true,
                "Shows details while hovering structure markers.", true),
            bool("dim-waypoint-structures", "Dim waypoint structures", true,
                "Dims structures that already have a waypoint.", true),
            decimal("structure-marker-scale", "Marker scale", 1.0, 0.5, 3.0, 2,
                "Scales predicted structure markers.", true))),
        new Category("Biomes", List.of(
            integer("biome-opacity", "Biome opacity", 255, 40, 255,
                "Opacity of predicted biome map tiles.", true))),
        new Category("Ores", List.of(
            bool("ore-esp", "Ore ESP", false,
                "Shows nearby predicted ores as in-world boxes.", true),
            choice("ore-esp-type", "Ore type", "Diamond", List.of(
                "Diamond", "Emerald", "Coal", "Iron", "Copper", "Gold", "Lapis",
                "Redstone", "Quartz", "Nether Gold", "Ancient Debris"),
                "Ore type highlighted by in-world ESP. The map shows all ores.", true),
            integer("ore-esp-radius", "ESP radius", 2, 1, 6,
                "Chunk radius used for ore prediction.", true),
            integer("ore-esp-max-boxes", "Maximum boxes", 80, 1, 500,
                "Maximum ore boxes rendered at once.", true),
            choice("ore-esp-shape-mode", "Box shape", "Both",
                List.of("Both", "Lines", "Sides"), "Ore box rendering style.", true),
            integer("ore-opacity", "Ore opacity", 55, 20, 255,
                "Opacity of ore markers and box fills.", true),
            decimal("ore-marker-scale", "Ore marker scale", 1.0, 0.5, 3.0, 2,
                "Scales predicted ore markers.", true))),
        new Category("Waypoints", List.of(
            bool("waypoint-tooltips", "Waypoint tooltips", true,
                "Shows details while hovering waypoints.", true),
            decimal("waypoint-marker-scale", "Waypoint scale", 1.0, 0.5, 3.0, 2,
                "Scales waypoint markers.", true))),
        new Category("Rendering", List.of(
            bool("terrain-layer", "Terrain background", true,
                "Shows the base map background.", true),
            bool("biome-layer", "Biome layer", true,
                "Shows predicted biome tiles.", true),
            bool("structure-layer", "Structure layer", true,
                "Shows predicted structure markers.", true),
            bool("waypoint-layer", "Waypoint layer", true,
                "Shows Seed Explorer waypoints.", true),
            bool("player-layer", "Player marker", true,
                "Shows the current player position.", true),
            bool("chunk-borders-layer", "Chunk borders", true,
                "Shows chunk border grid lines.", true),
            bool("coordinates-layer", "Coordinates", true,
                "Shows cursor coordinates and the center crosshair.", true))),
        new Category("Player", List.of(
            bool("show-player-info", "Player information", true,
                "Shows player coordinates and dimension.", true),
            bool("auto-center-player", "Follow player", false,
                "Keeps the map centered on the player dimension.", true))),
        new Category("Performance", List.of(
            integer("generation-margin", "Generation margin", 10, 0, 48,
                "Extra chunk margin used for structure generation.", true),
            integer("biome-tile-margin", "Biome tile margin", 2, 0, 16,
                "Extra margin used around visible biome tiles.", true)))
    );

    private final Screen parent;
    private final StandaloneSeedExplorerConfig config;
    private int categoryIndex;
    private int scrollOffset;
    private int maxRows;

    public StandaloneSeedExplorerSettingsScreen(Screen parent,
                                                StandaloneSeedExplorerConfig config) {
        super(Component.literal("Seed Explorer Settings"));
        this.parent = parent;
        this.config = config;
    }

    static int settingCount() {
        return CATEGORIES.stream().mapToInt(category -> category.settings.size()).sum();
    }

    static void validateDefinitions() {
        Set<String> keys = new HashSet<>();
        for (Category category : CATEGORIES) {
            if (category.name == null || category.name.isBlank()) {
                throw new IllegalStateException("Settings category has no name");
            }
            for (SettingDef setting : category.settings) {
                if (setting.key == null || setting.key.isBlank() || !keys.add(setting.key)) {
                    throw new IllegalStateException("Duplicate or empty setting key: " + setting.key);
                }
                if (setting.kind == ValueKind.INTEGER || setting.kind == ValueKind.DECIMAL) {
                    if (!Double.isFinite(setting.defaultNumber) || setting.min > setting.max
                        || setting.defaultNumber < setting.min || setting.defaultNumber > setting.max) {
                        throw new IllegalStateException("Invalid numeric setting: " + setting.key);
                    }
                }
                if (setting.kind == ValueKind.CHOICE
                    && (setting.choices == null || setting.choices.isEmpty()
                    || !setting.choices.contains(setting.defaultText))) {
                    throw new IllegalStateException("Invalid choice setting: " + setting.key);
                }
            }
        }
    }

    @Override
    protected void init() {
        maxRows = Math.max(1, (height - 110) / ROW_HEIGHT);
        Category category = CATEGORIES.get(categoryIndex);
        scrollOffset = Math.min(scrollOffset, Math.max(0, category.settings.size() - maxRows));

        int categoryWidth = Math.min(240, Math.max(120, width - 100));
        int categoryX = (width - categoryWidth) / 2;
        addRenderableWidget(Button.builder(Component.literal("<"), button -> changeCategory(-1))
            .bounds(categoryX - 30, 34, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal(category.name), button -> changeCategory(1))
            .bounds(categoryX, 34, categoryWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> changeCategory(1))
            .bounds(categoryX + categoryWidth + 6, 34, 24, 20).build());

        int labelWidth = Math.min(210, Math.max(105, width / 2 - 24));
        int controlWidth = Math.min(190, Math.max(105, width / 2 - 24));
        int totalWidth = labelWidth + 12 + controlWidth;
        int left = Math.max(12, (width - totalWidth) / 2);
        int end = Math.min(category.settings.size(), scrollOffset + maxRows);
        for (int i = scrollOffset; i < end; i++) {
            SettingDef setting = category.settings.get(i);
            int y = 68 + (i - scrollOffset) * ROW_HEIGHT;
            AbstractWidget widget = createWidget(setting, left + labelWidth + 12, y, controlWidth);
            Component tooltip = Component.literal(setting.description);
            widget.setTooltip(Tooltip.create(tooltip));
            addRenderableWidget(widget);
        }

        addRenderableWidget(Button.builder(Component.literal("Reset"), button -> {
            config.resetSettings();
            config.save();
            rebuildWidgets();
        }).bounds(width / 2 - 104, height - 30, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(width / 2 + 6, height - 30, 98, 20).build());
    }

    private AbstractWidget createWidget(SettingDef setting, int x, int y, int width) {
        return switch (setting.kind) {
            case BOOLEAN -> {
                boolean value = config.booleanValue(setting.key, setting.defaultBoolean);
                yield Button.builder(onOff(value), button -> {
                    boolean next = !config.booleanValue(setting.key, setting.defaultBoolean);
                    config.setBoolean(setting.key, next);
                    button.setMessage(onOff(next));
                    config.save();
                }).bounds(x, y, width, 20).build();
            }
            case INTEGER, DECIMAL -> new SettingSlider(x, y, width, setting);
            case CHOICE -> {
                String value = config.stringValue(setting.key, setting.defaultText);
                yield Button.builder(Component.literal(value), button -> {
                    String current = config.stringValue(setting.key, setting.defaultText);
                    int index = setting.choices.indexOf(current);
                    String next = setting.choices.get((index + 1 + setting.choices.size())
                        % setting.choices.size());
                    config.setString(setting.key, next);
                    button.setMessage(Component.literal(next));
                    config.save();
                }).bounds(x, y, width, 20).build();
            }
            case KEYBIND -> Button.builder(Component.literal("Minecraft Controls"), button ->
                minecraft.gui.setScreen(new KeyBindsScreen(this, minecraft.options)))
                .bounds(x, y, width, 20).build();
        };
    }

    private void changeCategory(int direction) {
        categoryIndex = Math.floorMod(categoryIndex + direction, CATEGORIES.size());
        scrollOffset = 0;
        rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        Category category = CATEGORIES.get(categoryIndex);
        int maxOffset = Math.max(0, category.settings.size() - maxRows);
        int next = Math.max(0, Math.min(maxOffset,
            scrollOffset + (verticalAmount < 0 ? 1 : -1)));
        if (next != scrollOffset) {
            scrollOffset = next;
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xFF101619);
        graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
        Category category = CATEGORIES.get(categoryIndex);
        int labelWidth = Math.min(210, Math.max(105, width / 2 - 24));
        int controlWidth = Math.min(190, Math.max(105, width / 2 - 24));
        int left = Math.max(12, (width - labelWidth - 12 - controlWidth) / 2);
        int end = Math.min(category.settings.size(), scrollOffset + maxRows);
        for (int i = scrollOffset; i < end; i++) {
            int y = 74 + (i - scrollOffset) * ROW_HEIGHT;
            graphics.text(font, category.settings.get(i).label, left, y, 0xFFE4EAE5);
        }
        if (category.settings.size() > maxRows) {
            graphics.centeredText(font, Component.literal((scrollOffset + 1) + "-" + end
                + " / " + category.settings.size()), width / 2, height - 44, 0xFF9AA8A0);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        config.save();
        minecraft.gui.setScreen(parent);
    }

    private static Component onOff(boolean value) {
        return Component.literal(value ? "On" : "Off");
    }

    private final class SettingSlider extends AbstractSliderButton {
        private final SettingDef setting;

        private SettingSlider(int x, int y, int width, SettingDef setting) {
            super(x, y, width, 20, Component.empty(),
                normalizedValue(config, setting));
            this.setting = setting;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double raw = setting.min + value * (setting.max - setting.min);
            String text = setting.kind == ValueKind.INTEGER
                ? Integer.toString((int) Math.round(raw))
                : String.format(Locale.ROOT, "%." + setting.decimals + "f", raw);
            setMessage(Component.literal(text));
        }

        @Override
        protected void applyValue() {
            double raw = setting.min + value * (setting.max - setting.min);
            if (setting.kind == ValueKind.INTEGER) {
                config.setInt(setting.key, (int) Math.round(raw), (int) setting.min, (int) setting.max);
            } else {
                double scale = Math.pow(10.0, setting.decimals);
                config.setDouble(setting.key, Math.round(raw * scale) / scale, setting.min, setting.max);
            }
            updateMessage();
            config.save();
        }
    }

    private static double normalizedValue(StandaloneSeedExplorerConfig config,
                                          SettingDef setting) {
        double current = setting.kind == ValueKind.INTEGER
            ? config.intValue(setting.key, (int) setting.defaultNumber,
                (int) setting.min, (int) setting.max)
            : config.doubleValue(setting.key, setting.defaultNumber, setting.min, setting.max);
        return (current - setting.min) / (setting.max - setting.min);
    }

    private static SettingDef bool(String key, String label, boolean fallback,
                                   String description, boolean connected) {
        return new SettingDef(key, label, description, ValueKind.BOOLEAN, fallback,
            0, 0, 0, "", List.of(), connected);
    }

    private static SettingDef integer(String key, String label, int fallback, int min, int max,
                                      String description, boolean connected) {
        return new SettingDef(key, label, description, ValueKind.INTEGER, false,
            fallback, min, max, "", List.of(), connected);
    }

    private static SettingDef decimal(String key, String label, double fallback,
                                      double min, double max, int decimals,
                                      String description, boolean connected) {
        return new SettingDef(key, label, description, ValueKind.DECIMAL, false,
            fallback, min, max, "", List.of(), connected, decimals);
    }

    private static SettingDef choice(String key, String label, String fallback,
                                     List<String> choices, String description, boolean connected) {
        return new SettingDef(key, label, description, ValueKind.CHOICE, false,
            0, 0, 0, fallback, List.copyOf(choices), connected);
    }

    private static SettingDef keybind(String key, String label, String description) {
        return new SettingDef(key, label, description, ValueKind.KEYBIND, false,
            0, 0, 0, "", List.of(), true);
    }

    private enum ValueKind {
        BOOLEAN, INTEGER, DECIMAL, CHOICE, KEYBIND
    }

    private record Category(String name, List<SettingDef> settings) {
    }

    private record SettingDef(String key, String label, String description, ValueKind kind,
                              boolean defaultBoolean, double defaultNumber, double min, double max,
                              String defaultText, List<String> choices, boolean connected,
                              int decimals) {
        private SettingDef(String key, String label, String description, ValueKind kind,
                           boolean defaultBoolean, double defaultNumber, double min, double max,
                           String defaultText, List<String> choices, boolean connected) {
            this(key, label, description, kind, defaultBoolean, defaultNumber, min, max,
                defaultText, choices, connected, 0);
        }
    }
}
