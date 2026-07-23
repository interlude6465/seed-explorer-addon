package me.seedexplorer.addon.loot;

import me.seedexplorer.addon.debug.PredictionDebugLogger;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shows predicted contents beside real opened containers when a predicted chest is nearby. */
public final class PredictedChestOverlay {
    private static final PredictedChestOverlay INSTANCE = new PredictedChestOverlay();
    private static final int MAX_REMEMBERED = 256;

    private final List<ChestLootOutput> remembered = new CopyOnWriteArrayList<>();
    private ChestLootOutput active;
    private Screen lastScreen;

    private PredictedChestOverlay() {
    }

    public static PredictedChestOverlay get() {
        return INSTANCE;
    }

    public void init() {
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public void remember(List<ChestLootOutput> chests) {
        if (chests == null || chests.isEmpty()) return;
        remembered.addAll(chests);
        while (remembered.size() > MAX_REMEMBERED) remembered.remove(0);
    }

    public void remember(ChestLootOutput chest) {
        if (chest == null) return;
        remembered.add(chest);
        while (remembered.size() > MAX_REMEMBERED) remembered.remove(0);
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!isContainerScreen(event.screen)) {
            active = null;
            lastScreen = event.screen;
            return;
        }

        active = nearestRememberedChest();
        lastScreen = event.screen;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            int x = (int) Math.floor(mc.player.getX());
            int y = (int) Math.floor(mc.player.getY());
            int z = (int) Math.floor(mc.player.getZ());
            PredictionDebugLogger.chestOpened(x, y, z, event.screen.getTitle().getString());
        }
    }

    /**
     * Draws the predicted-contents panel on top of an open container screen. Invoked from
     * {@code ContainerScreenOverlayMixin} at the TAIL of the screen's render so the panel sits
     * above the chest GUI. Rendering through {@link Render2DEvent} would draw behind the screen
     * (HUD layer renders before screens), which cut off the left half of the panel.
     */
    public void renderOnScreen(GuiGraphicsExtractor graphics, int screenWidth, int screenHeight,
                               int guiLeft, int guiTop, int guiWidth, int guiHeight) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null || !isContainerScreen(mc.screen)) return;
        if (active == null) active = nearestRememberedChest();
        if (active == null) return;

        draw(graphics, screenWidth, screenHeight, guiLeft, guiTop, guiWidth, guiHeight, active);
    }

    private ChestLootOutput nearestRememberedChest() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || remembered.isEmpty()) return null;
        ChestLootOutput best = null;
        double bestDist = Double.MAX_VALUE;
        for (ChestLootOutput chest : remembered) {
            double dx = chest.blockX() + 0.5 - mc.player.getX();
            double dy = chest.blockY() + 0.5 - mc.player.getY();
            double dz = chest.blockZ() + 0.5 - mc.player.getZ();
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDist) {
                best = chest;
                bestDist = dist;
            }
        }
        return bestDist <= 144.0 ? best : null;
    }

    private void draw(GuiGraphicsExtractor g, int width, int height,
                      int guiLeft, int guiTop, int guiWidth, int guiHeight, ChestLootOutput chest) {
        Minecraft mc = Minecraft.getInstance();
        Font f = mc.font;

        int gap = 6;
        int margin = 4;
        // Sit flush against the chest GUI's right edge and take whatever horizontal room is left up to
        // the screen edge, so the panel never overlaps the chest and never runs off-screen.
        int x = guiLeft + guiWidth + gap;
        int available = width - x - margin;
        int panelW = Math.min(210, available);

        // If there isn't enough room on the right (small window / large GUI scale), place it on the left
        // of the chest instead.
        if (panelW < 90) {
            int leftRoom = guiLeft - gap - margin;
            if (leftRoom >= 90) {
                panelW = Math.min(210, leftRoom);
                x = guiLeft - gap - panelW;
            } else {
                // Nowhere good to fit beside the chest: clamp to the widest of the two sides.
                if (leftRoom > available) {
                    panelW = Math.max(80, leftRoom);
                    x = guiLeft - gap - panelW;
                } else {
                    panelW = Math.max(80, available);
                    x = guiLeft + guiWidth + gap;
                }
            }
        }

        // Align the panel with the chest GUI vertically and match its height.
        int y = guiTop;
        int panelH = Math.min(guiHeight, height - y - margin);

        g.fill(x, y, x + panelW, y + panelH, 0xEE0B1117);
        g.fill(x, y, x + panelW, y + 1, 0xFF45B6A6);
        g.fill(x, y, x + 1, y + panelH, 0xFF45B6A6);

        int tx = x + 8;
        int textW = panelW - 16;
        g.text(f, trim(f, "Predicted contents", textW), tx, y + 8, 0xFFE7EDF3);
        g.text(f, trim(f, chest.blockX() + ", " + chest.blockY() + ", " + chest.blockZ(), textW), tx, y + 21, 0xFF8D9AA6);
        g.text(f, trim(f, chest.exact() ? "exact prediction" : "approx (position only)", textW), tx, y + 32,
            chest.exact() ? 0xFF7ED88A : 0xFFD8B45A);

        Map<String, Integer> actual = actualCounts();
        String diff = chest.diffSummary(actual);
        g.text(f, trim(f, actual.isEmpty() ? "Actual: unavailable" : "Diff: " + diff, textW), tx, y + 44,
            actual.isEmpty() ? 0xFF8D9AA6 : ("match".equals(diff) ? 0xFF7ED88A : 0xFFFFB86C));

        int iy = y + 60;
        if (chest.predictedItems() == null || chest.predictedItems().isEmpty()) {
            g.text(f, trim(f, "No item prediction", textW), tx, iy, 0xFF8D9AA6);
            return;
        }
        if (!actual.isEmpty()) {
            g.text(f, trim(f, "Actual:", textW), tx, iy, 0xFFD8B45A);
            iy += 12;
            for (var entry : actual.entrySet()) {
                if (iy > y + panelH - 14) break;
                g.text(f, trim(f, entry.getValue() + "x " + entry.getKey(), textW), tx, iy, 0xFFE7EDF3);
                iy += 12;
            }
            iy += 4;
        }
        g.text(f, trim(f, "Predicted:", textW), tx, iy, 0xFF7ED88A);
        iy += 12;
        for (ItemLoot item : chest.predictedItems()) {
            if (iy > y + panelH - 14) break;
            String count = item.minCount() == item.maxCount()
                ? " x" + item.minCount()
                : " x" + item.minCount() + "-" + item.maxCount();
            String text = trim(f, item.displayName() + count, textW);
            boolean enchanted = item.randomEnchantment() || item.enchantmentId() != null
                || (item.enchantmentSummary() != null && !item.enchantmentSummary().isBlank());
            g.text(f, text, tx, iy, enchanted ? 0xFFD6A8FF : 0xFFE7EDF3);
            if (item.enchantmentSummary() != null && !item.enchantmentSummary().isBlank() && iy + 12 <= y + panelH - 14) {
                iy += 12;
                g.text(f, trim(f, "[" + item.enchantmentSummary() + "]", panelW - 16),
                    tx, iy, 0xFFD6A8FF);
            }
            iy += 12;
        }
    }

    private Map<String, Integer> actualCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.containerMenu == null) return counts;
        var inventory = mc.player.getInventory();
        for (var slot : mc.player.containerMenu.slots) {
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(itemId, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static boolean isContainerScreen(Screen screen) {
        return screen instanceof ContainerScreen || screen instanceof ShulkerBoxScreen;
    }

    private static String trim(Font f, String text, int maxWidth) {
        if (text == null) return "";
        if (f.width(text) <= maxWidth) return text;
        for (int i = text.length() - 1; i > 0; i--) {
            String candidate = text.substring(0, i) + "...";
            if (f.width(candidate) <= maxWidth) return candidate;
        }
        return "...";
    }
}
