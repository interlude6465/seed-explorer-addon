package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Native predicted-vs-actual container panel with no Meteor event or rendering dependency. */
public final class StandalonePredictedChestOverlay {
    private static final StandalonePredictedChestOverlay INSTANCE = new StandalonePredictedChestOverlay();
    private final CopyOnWriteArrayList<RememberedChest> remembered = new CopyOnWriteArrayList<>();
    private final StandaloneChestLootVerification.OpeningGate openingGate =
        new StandaloneChestLootVerification.OpeningGate();
    private volatile BlockPos interactedPosition;
    private volatile long interactionNanos;
    private long openingToken;
    private Screen boundScreen;
    private BoundChest active;
    private boolean synchronizedContents;
    private StandaloneChestLootVerification.Result comparison;

    private StandalonePredictedChestOverlay() {
    }

    public static StandalonePredictedChestOverlay get() {
        return INSTANCE;
    }

    public void remember(List<ChestLootOutput> chests) {
        remember(null, chests);
    }

    public void remember(PredictionContext context, List<ChestLootOutput> chests) {
        if (chests == null) return;
        for (ChestLootOutput chest : chests) {
            if (chest == null) continue;
            BlockPos position = new BlockPos(chest.blockX(), chest.blockY(), chest.blockZ());
            remembered.removeIf(previous -> java.util.Objects.equals(previous.context(), context)
                && previous.chest().blockX() == position.getX()
                && previous.chest().blockY() == position.getY()
                && previous.chest().blockZ() == position.getZ());
            remembered.add(new RememberedChest(context, chest));
        }
        while (remembered.size() > 512) remembered.removeFirst();
    }

    void forget(PredictionContext context, List<ChestLootOutput> chests) {
        var positions = new java.util.HashSet<BlockPos>();
        for (var chest : chests) positions.add(new BlockPos(chest.blockX(), chest.blockY(), chest.blockZ()));
        remembered.removeIf(previous -> java.util.Objects.equals(previous.context(), context)
            && positions.contains(new BlockPos(previous.chest().blockX(),
                previous.chest().blockY(), previous.chest().blockZ())));
    }

    public void clear() {
        StandaloneChestHighlightRenderer.clear();
        remembered.clear();
        interactedPosition = null;
        interactionNanos = 0L;
        openingToken = openingGate.begin();
        boundScreen = null;
        active = null;
        synchronizedContents = false;
        comparison = null;
    }

    public void bind(BlockPos position) {
        interactedPosition = position == null ? null : position.immutable();
        interactionNanos = System.nanoTime();
        openingToken = openingGate.begin();
        boundScreen = null;
        active = null;
        synchronizedContents = false;
        comparison = null;
    }

    /** Called after vanilla has applied the complete server container snapshot to the active menu. */
    public void onContainerContent(int containerId) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.containerMenu == null
            || client.player.containerMenu.containerId != containerId) return;
        Screen currentScreen = client.gui.screen();
        if (!isContainer(currentScreen)) return;
        bindScreen(currentScreen);
        BoundChest chest = active;
        if (chest == null || !openingGate.claim(openingToken, true)) return;

        Map<String, Integer> actual = actualCounts(client);
        StandaloneChestLootVerification.Result result = StandaloneChestLootVerification.compare(
            chest.context(), chest.predictions(), actual, actualStackContents(client));
        synchronizedContents = true;
        comparison = result;

        String message = switch (result.status()) {
            case MATCH -> "Loot match";
            case MISMATCH -> "Loot mismatch";
            case UNVERIFIED -> "Loot could not be fully compared";
        };
        int color = switch (result.status()) {
            case MATCH -> 0x55DD88;
            case MISMATCH -> 0xFF6666;
            case UNVERIFIED -> 0xD8B45A;
        };
        client.player.sendSystemMessage(Component.literal("[Seed Explorer] " + message).withColor(color));
        if (result.status() == StandaloneChestLootVerification.Status.MISMATCH) {
            Path log = client.gameDirectory.toPath().resolve("seed-explorer-reports")
                .resolve("loot-mismatches.log");
            StandaloneChestLootVerification.appendDiagnostic(log,
                StandaloneChestLootVerification.diagnostic(chest.context(), interactedPosition,
                    chest.partner(), containerId, chest.predictions(), result, actualStacks(client)));
        }
    }

    public void render(GuiGraphicsExtractor graphics, int guiLeft, int guiTop, int guiWidth, int guiHeight) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !isContainer(client.gui.screen())) return;
        Screen currentScreen = client.gui.screen();
        bindScreen(currentScreen);
        BoundChest bound = active;
        if (bound == null) return;
        ChestLootOutput chest = bound.predictions().getFirst();
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
        String status;
        int statusColor;
        if (!synchronizedContents || comparison == null) {
            status = "Waiting for server contents";
            statusColor = 0xFF9AA8B2;
        } else {
            status = switch (comparison.status()) {
                case MATCH -> "Loot match";
                case MISMATCH -> "Loot mismatch";
                case UNVERIFIED -> "Comparison incomplete";
            };
            statusColor = switch (comparison.status()) {
                case MATCH -> 0xFF7ED88A;
                case MISMATCH -> 0xFFFF7777;
                case UNVERIFIED -> 0xFFD8B45A;
            };
        }
        graphics.text(font, trim(font, status, textWidth), textX, guiTop + 31, statusColor);
        int y = guiTop + 47;
        for (ChestLootOutput predictedChest : bound.predictions()) {
            for (ItemLoot item : predictedChest.predictedItems()) {
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
    }

    private void bindScreen(Screen currentScreen) {
        if (currentScreen == boundScreen) return;
        boundScreen = currentScreen;
        active = boundChest();
        synchronizedContents = false;
        comparison = null;
    }

    private BoundChest boundChest() {
        BlockPos position = interactedPosition;
        if (position == null || System.nanoTime() - interactionNanos > 10_000_000_000L) return null;
        BlockPos partner = null;
        var level = Minecraft.getInstance().level;
        if (StandaloneClientChunks.loaded(level, position)) {
            var state = level.getBlockState(position);
            if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
                    != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                partner = position.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
            }
        }
        Map<BlockPos, RememberedChest> matches = new LinkedHashMap<>();
        for (RememberedChest candidate : remembered) {
            ChestLootOutput chest = candidate.chest();
            BlockPos chestPosition = new BlockPos(chest.blockX(), chest.blockY(), chest.blockZ());
            if (chestPosition.equals(position) || partner != null && chestPosition.equals(partner)) {
                matches.put(chestPosition, candidate);
            }
        }
        if (matches.isEmpty()) return null;
        List<RememberedChest> selected = List.copyOf(matches.values());
        PredictionContext context = selected.getFirst().context();
        List<ChestLootOutput> predictions = selected.stream().map(RememberedChest::chest).toList();
        return new BoundChest(context, partner, predictions);
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

    private static List<String> actualStacks(Minecraft client) {
        List<String> stacks = new ArrayList<>();
        if (client.player == null || client.player.containerMenu == null) return stacks;
        var inventory = client.player.getInventory();
        for (int index = 0; index < client.player.containerMenu.slots.size(); index++) {
            var slot = client.player.containerMenu.slots.get(index);
            if (slot.container == inventory || slot.getItem().isEmpty()) continue;
            ItemStack stack = slot.getItem();
            stacks.add(index + ":" + BuiltInRegistries.ITEM.getKey(stack.getItem())
                + "x" + stack.getCount() + " " + stack);
        }
        return List.copyOf(stacks);
    }

    private static List<StandaloneChestLootVerification.ActualStack> actualStackContents(
        Minecraft client) {
        List<StandaloneChestLootVerification.ActualStack> stacks = new ArrayList<>();
        if (client.player == null || client.player.containerMenu == null) return stacks;
        var inventory = client.player.getInventory();
        for (var slot : client.player.containerMenu.slots) {
            if (slot.container == inventory || slot.getItem().isEmpty()) continue;
            ItemStack stack = slot.getItem();
            Map<String, Integer> enchantments = new LinkedHashMap<>();
            addEnchantments(enchantments, stack.getEnchantments());
            addEnchantments(enchantments, stack.get(DataComponents.STORED_ENCHANTMENTS));
            stacks.add(new StandaloneChestLootVerification.ActualStack(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), enchantments,
                LootTableSimulator.damageComponent(stack),
                LootTableSimulator.potionContentsComponent(stack),
                LootTableSimulator.hasUnrepresentedLootComponents(stack),
                LootTableSimulator.mapDecorationsComponent(stack),
                LootTableSimulator.suspiciousStewEffectsComponent(stack)));
        }
        return List.copyOf(stacks);
    }

    private static void addEnchantments(Map<String, Integer> destination,
                                        net.minecraft.world.item.enchantment.ItemEnchantments source) {
        if (source == null || source.isEmpty()) return;
        source.entrySet().forEach(entry -> entry.getKey().unwrapKey().ifPresent(key ->
            destination.put(key.identifier().toString(), entry.getIntValue())));
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

    private record RememberedChest(PredictionContext context, ChestLootOutput chest) {
    }

    private record BoundChest(PredictionContext context, BlockPos partner,
                              List<ChestLootOutput> predictions) {
        BoundChest {
            predictions = List.copyOf(predictions);
        }
    }
}
