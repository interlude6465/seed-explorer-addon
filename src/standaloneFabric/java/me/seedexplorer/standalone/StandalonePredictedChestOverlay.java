package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ItemLoot;
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

/** Native predicted-vs-actual container panel with no Meteor event or rendering dependency. */
public final class StandalonePredictedChestOverlay {
    private static final StandalonePredictedChestOverlay INSTANCE = new StandalonePredictedChestOverlay();
    private final CopyOnWriteArrayList<ChestLootOutput> remembered = new CopyOnWriteArrayList<>();
    private volatile net.minecraft.core.BlockPos interactedPosition;
    private volatile long interactionNanos;
    private Screen boundScreen;
    private ChestLootOutput active;

    private StandalonePredictedChestOverlay() {
    }

    public static StandalonePredictedChestOverlay get() {
        return INSTANCE;
    }

    public void remember(List<ChestLootOutput> chests) {
        if (chests == null) return;
        remembered.addAll(chests);
        while (remembered.size() > 512) remembered.removeFirst();
    }

    public void clear() {
        remembered.clear();
        interactedPosition = null;
        interactionNanos = 0L;
        boundScreen = null;
        active = null;
    }

    public void bind(net.minecraft.core.BlockPos position) {
        interactedPosition = position == null ? null : position.immutable();
        interactionNanos = System.nanoTime();
    }

    public void render(GuiGraphicsExtractor graphics, int guiLeft, int guiTop, int guiWidth, int guiHeight) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !isContainer(client.gui.screen())) return;
        Screen currentScreen = client.gui.screen();
        if (currentScreen != boundScreen) {
            boundScreen = currentScreen;
            active = boundChest();
        }
        ChestLootOutput chest = active;
        if (chest == null) return;
        Font font = client.font;
        int screenWidth = graphics.guiWidth();
        int gap = 6;
        int panelWidth = Math.min(210, screenWidth - guiLeft - guiWidth - gap - 4);
        int x = guiLeft + guiWidth + gap;
        if (panelWidth < 100 && guiLeft > 110) {
            panelWidth = Math.min(210, guiLeft - gap - 4);
            x = guiLeft - gap - panelWidth;
        }
        if (panelWidth < 80) return;
        int panelHeight = Math.min(guiHeight, graphics.guiHeight() - guiTop - 4);
        graphics.fill(x, guiTop, x + panelWidth, guiTop + panelHeight, 0xEE0B1117);
        graphics.fill(x, guiTop, x + panelWidth, guiTop + 1, 0xFF45B6A6);
        int textX = x + 7;
        int textWidth = panelWidth - 14;
        graphics.text(font, trim(font, "Predicted contents", textWidth), textX, guiTop + 7, 0xFFE7EDF3);
        graphics.text(font, chest.blockX() + ", " + chest.blockY() + ", " + chest.blockZ(),
            textX, guiTop + 19, 0xFF9AA8B2);
        Map<String, Integer> actual = actualCounts(client);
        String diff = chest.diffSummary(actual);
        graphics.text(font, trim(font, actual.isEmpty() ? "Actual unavailable" : "Diff: " + diff, textWidth),
            textX, guiTop + 31, "match".equals(diff) ? 0xFF7ED88A : 0xFFFFB86C);
        int y = guiTop + 47;
        for (ItemLoot item : chest.predictedItems()) {
            if (y > guiTop + panelHeight - 12) break;
            String count = item.minCount() == item.maxCount() ? " x" + item.minCount()
                : " x" + item.minCount() + "-" + item.maxCount();
            graphics.text(font, trim(font, item.displayName() + count, textWidth), textX, y,
                item.randomEnchantment() ? 0xFFD6A8FF : 0xFFE7EDF3);
            y += 12;
            if (item.enchantmentSummary() != null && !item.enchantmentSummary().isBlank()
                && y <= guiTop + panelHeight - 12) {
                graphics.text(font, trim(font, item.enchantmentSummary(), textWidth), textX, y, 0xFFD6A8FF);
                y += 12;
            }
        }
    }

    private ChestLootOutput boundChest() {
        net.minecraft.core.BlockPos position = interactedPosition;
        if (position == null || System.nanoTime() - interactionNanos > 3_000_000_000L) return null;
        net.minecraft.core.BlockPos partner = null;
        var level = Minecraft.getInstance().level;
        if (level != null && level.hasChunkAt(position)) {
            var state = level.getBlockState(position);
            if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
                    != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                partner = position.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
            }
        }
        for (ChestLootOutput chest : remembered) {
            if (chest.blockX() == position.getX() && chest.blockY() == position.getY()
                && chest.blockZ() == position.getZ()) return chest;
            if (partner != null && chest.blockX() == partner.getX() && chest.blockY() == partner.getY()
                && chest.blockZ() == partner.getZ()) return chest;
        }
        return null;
    }

    private static Map<String, Integer> actualCounts(Minecraft client) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (client.player == null || client.player.containerMenu == null) return counts;
        var inventory = client.player.getInventory();
        for (var slot : client.player.containerMenu.slots) {
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static boolean isContainer(Screen screen) {
        return screen instanceof ContainerScreen || screen instanceof ShulkerBoxScreen;
    }

    private static String trim(Font font, String value, int width) {
        if (font.width(value) <= width) return value;
        for (int length = value.length() - 1; length > 0; length--) {
            String candidate = value.substring(0, length) + "...";
            if (font.width(candidate) <= width) return candidate;
        }
        return "...";
    }
}
