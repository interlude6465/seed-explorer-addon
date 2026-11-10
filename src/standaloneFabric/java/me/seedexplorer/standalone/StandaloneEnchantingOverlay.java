package me.seedexplorer.standalone;

import com.mojang.blaze3d.platform.InputConstants;

import me.seedexplorer.addon.enchant.EnchantingTablePredictor;
import me.seedexplorer.addon.enchant.EnchantingTargetPlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Native exact-offer and target-planning panel for the vanilla enchanting screen. */
public final class StandaloneEnchantingOverlay {
    private static final StandaloneEnchantingOverlay INSTANCE = new StandaloneEnchantingOverlay();
    private static final int PANEL_WIDTH = 310;
    private static final int MIN_PANEL_WIDTH = 175;
    private static final int GAP = 8;
    private static final int ROW = 14;
    private static final ExecutorService PLANNER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Seed Explorer enchantment planner");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final AtomicInteger PLAN_GENERATION = new AtomicInteger();
    private final Map<String, Integer> selected = new LinkedHashMap<>();
    private List<EnchantingTablePredictor.EnchantmentOption> options = List.of();
    private Set<String> conflicts = Set.of();
    private EnchantingTablePredictor.Report report;
    private volatile EnchantingTargetPlanner.Plan plan;
    private volatile EnchantingTargetPlanner.PartialPlan partial;
    private volatile boolean searching;
    private String planKey = "";
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int optionTop;
    private int scroll;
    private ItemStack item = ItemStack.EMPTY;

    private StandaloneEnchantingOverlay() {
    }

    public static StandaloneEnchantingOverlay get() {
        return INSTANCE;
    }

    public static int guiShift(int screenWidth, int guiWidth) {
        int panel = Math.max(MIN_PANEL_WIDTH, Math.min(PANEL_WIDTH, screenWidth - guiWidth - GAP - 8));
        int left = Math.max(4, (screenWidth - guiWidth - GAP - panel) / 2);
        return left - (screenWidth - guiWidth) / 2;
    }

    public void render(GuiGraphicsExtractor graphics, EnchantmentScreen screen,
                       int guiLeft, int guiTop, int guiWidth, int guiHeight) {
        Minecraft client = Minecraft.getInstance();
        StandaloneAncillaryRuntime runtime = StandaloneAncillaryRuntime.get();
        if (runtime == null || client.player == null || client.level == null) return;
        layout(graphics.guiWidth(), graphics.guiHeight(), guiLeft, guiTop, guiWidth, guiHeight);
        refresh(screen, runtime, client);
        draw(graphics, client.font, runtime);
    }

    public boolean click(EnchantmentScreen screen, MouseButtonEvent click) {
        if (click.button() != InputConstants.MOUSE_BUTTON_LEFT || !inside(click.x(), click.y(), panelX, panelY, panelW, panelH)) return false;
        int index = scroll + (int) ((click.y() - optionTop) / ROW);
        if (click.y() >= optionTop && index >= 0 && index < options.size()) {
            EnchantingTablePredictor.EnchantmentOption option = options.get(index);
            int right = panelX + panelW - 10;
            if (click.x() >= right - 18) adjust(option, 1);
            else if (click.x() >= right - 40) adjust(option, -1);
            else toggle(option);
        }
        return true;
    }

    public boolean scroll(double mouseX, double mouseY, double delta) {
        if (!inside(mouseX, mouseY, panelX, optionTop, panelW, panelH - (optionTop - panelY))) return false;
        int visible = visibleRows();
        scroll = clamp(scroll - (int) Math.signum(delta) * 3, 0, Math.max(0, options.size() - visible));
        return true;
    }

    private void refresh(EnchantmentScreen screen, StandaloneAncillaryRuntime runtime, Minecraft client) {
        EnchantmentMenu menu = screen.getMenu();
        runtime.seedCracker().observe(client.level, client.player, menu);
        Integer resolved = runtime.seedCracker().resolvedSeed(menu.getEnchantmentSeed());
        EnchantingTablePredictor.Report next = EnchantingTablePredictor.current(client.level, client.player,
            resolved == null ? menu.getEnchantmentSeed() : resolved, resolved != null);
        ItemStack nextItem = next == null ? ItemStack.EMPTY : next.item();
        if (!ItemStack.matches(item, nextItem)) {
            item = nextItem.copy();
            selected.clear();
            scroll = 0;
            invalidatePlan();
        }
        report = next;
        try {
            options = EnchantingTablePredictor.compatibleEnchantments(item, client.level.registryAccess());
        } catch (Throwable ignored) {
            options = List.of();
        }
        selected.keySet().removeIf(id -> options.stream().noneMatch(option -> option.id().equals(id)));
        updateConflicts(client);
        ensurePlan(runtime, client);
    }

    private void ensurePlan(StandaloneAncillaryRuntime runtime, Minecraft client) {
        List<EnchantingTablePredictor.RequestedEnchantment> requests = requests();
        if (report == null || !report.exact() || item.isEmpty() || requests.isEmpty() || !conflicts.isEmpty()) {
            invalidatePlan();
            return;
        }
        Long playerSeed = runtime.enchantingRng().snapshot().playerSeed();
        String key = report.enchantmentSeed() + ":" + report.bookshelfCount() + ":" + requests + ":" + playerSeed;
        if (key.equals(planKey)) return;
        planKey = key;
        plan = null;
        partial = null;
        searching = true;
        int generation = PLAN_GENERATION.incrementAndGet();
        ItemStack target = item.copy();
        EnchantingTablePredictor.Report active = report;
        var registries = client.level.registryAccess();
        PLANNER.submit(() -> {
            EnchantingTargetPlanner.Plan exact = EnchantingTargetPlanner.find(target,
                active.enchantmentSeed(), active.bookshelfCount(), requests, active, registries).orElse(null);
            EnchantingTargetPlanner.PartialPlan best = exact == null
                ? EnchantingTargetPlanner.findBestPartial(target, active.enchantmentSeed(),
                    active.bookshelfCount(), requests, active, registries).orElse(null) : null;
            if (exact == null && playerSeed != null) {
                EnchantingTargetPlanner.FutureSearch future = EnchantingTargetPlanner.searchFuture(target,
                    playerSeed, active.bookshelfCount(), requests, registries,
                    EnchantingTargetPlanner.DEFAULT_MAX_ITEM_THROWS);
                exact = future.exact();
                if (best == null) best = future.bestPartial();
            }
            if (PLAN_GENERATION.get() != generation) return;
            plan = exact;
            partial = exact == null ? best : null;
            searching = false;
        });
    }

    private void draw(GuiGraphicsExtractor graphics, Font font, StandaloneAncillaryRuntime runtime) {
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xEE0B1117);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, 0xFF45B6A6);
        int x = panelX + 8;
        int width = panelW - 16;
        graphics.text(font, "Enchanting predictor", x, panelY + 7, 0xFFE7EDF3);
        String seed = switch (runtime.seedCracker().state()) {
            case UNKNOWN -> "Waiting for enchantable item";
            case CRACKING -> "Cracking seed: " + runtime.seedCracker().candidateCount() + " candidates";
            case INVALID -> "Seed clues inconsistent; change item";
            case CRACKED -> "Exact seed " + report.enchantmentSeed();
        };
        graphics.text(font, trim(font, seed, width), x, panelY + 20, 0xFF9AA8B2);
        int y = panelY + 34;
        if (report != null) {
            for (EnchantingTablePredictor.Offer offer : report.offers()) {
                boolean match = !selected.isEmpty() && offer.hasAll(requests());
                String line = (offer.slot() + 1) + ". " + offer.summary() + " (" + offer.cost() + ")";
                graphics.text(font, trim(font, line, width), x, y, match ? 0xFF7ED88A : 0xFFE7EDF3);
                y += 12;
            }
        }
        String instruction = !conflicts.isEmpty() ? "Selected enchantments conflict"
            : searching ? "Searching current and future rolls..."
            : plan != null ? plan.instruction()
            : partial != null ? partial.instruction()
            : selected.isEmpty() ? "Select target enchantments below"
            : runtime.enchantingRng().snapshot().playerSeed() == null
                ? "Enchant twice to crack player RNG" : "No roll found in search window";
        graphics.text(font, trim(font, instruction, width), x, panelY + 73,
            conflicts.isEmpty() ? 0xFFD8B45A : 0xFFFF7777);
        optionTop = panelY + 91;
        int visible = visibleRows();
        for (int row = 0; row < visible; row++) {
            int index = scroll + row;
            if (index >= options.size()) break;
            EnchantingTablePredictor.EnchantmentOption option = options.get(index);
            int rowY = optionTop + row * ROW;
            boolean active = selected.containsKey(option.id());
            boolean conflict = conflicts.contains(option.id());
            if (active) graphics.fill(panelX + 6, rowY, panelX + panelW - 6, rowY + ROW - 1,
                conflict ? 0x775E292E : 0x553B675F);
            graphics.text(font, trim(font, (active ? "[x] " : "[ ] ") + option.displayName(), panelW - 82),
                x, rowY + 2, conflict ? 0xFFFF7777 : 0xFFE7EDF3);
            graphics.text(font, "-", panelX + panelW - 48, rowY + 2, 0xFFE7EDF3);
            graphics.text(font, active ? Integer.toString(selected.get(option.id())) : "-",
                panelX + panelW - 34, rowY + 2, 0xFFD8B45A);
            graphics.text(font, "+", panelX + panelW - 18, rowY + 2, 0xFFE7EDF3);
        }
    }

    private void toggle(EnchantingTablePredictor.EnchantmentOption option) {
        if (selected.containsKey(option.id())) selected.remove(option.id());
        else selected.put(option.id(), 1);
        invalidatePlan();
    }

    private void adjust(EnchantingTablePredictor.EnchantmentOption option, int delta) {
        int level = selected.getOrDefault(option.id(), 1) + delta;
        if (level < 1) selected.remove(option.id());
        else selected.put(option.id(), Math.min(option.maxLevel(), level));
        invalidatePlan();
    }

    private void updateConflicts(Minecraft client) {
        List<String> ids = List.copyOf(selected.keySet());
        Set<String> found = new HashSet<>();
        for (int first = 0; first < ids.size(); first++) {
            for (int second = first + 1; second < ids.size(); second++) {
                if (!EnchantingTablePredictor.areCompatible(ids.get(first), ids.get(second),
                    client.level.registryAccess())) {
                    found.add(ids.get(first));
                    found.add(ids.get(second));
                }
            }
        }
        conflicts = Set.copyOf(found);
    }

    private List<EnchantingTablePredictor.RequestedEnchantment> requests() {
        List<EnchantingTablePredictor.RequestedEnchantment> result = new ArrayList<>();
        selected.forEach((id, level) -> result.add(
            new EnchantingTablePredictor.RequestedEnchantment(id, level)));
        return List.copyOf(result);
    }

    private void invalidatePlan() {
        plan = null;
        partial = null;
        searching = false;
        planKey = "";
        PLAN_GENERATION.incrementAndGet();
    }

    private void layout(int screenWidth, int screenHeight, int guiLeft, int guiTop,
                        int guiWidth, int guiHeight) {
        panelW = Math.max(MIN_PANEL_WIDTH, Math.min(PANEL_WIDTH, screenWidth - guiLeft - guiWidth - GAP - 4));
        panelX = guiLeft + guiWidth + GAP;
        if (panelW < 190 && guiLeft > 190) {
            panelW = Math.min(PANEL_WIDTH, guiLeft - GAP - 4);
            panelX = guiLeft - GAP - panelW;
        }
        panelY = Math.max(4, guiTop);
        panelH = Math.max(guiHeight, Math.min(360, screenHeight - panelY - 4));
    }

    private int visibleRows() {
        return Math.max(1, (panelY + panelH - optionTop - 5) / ROW);
    }

    private static boolean inside(double x, double y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trim(Font font, String value, int width) {
        if (value == null || font.width(value) <= width) return value == null ? "" : value;
        for (int length = value.length() - 1; length > 0; length--) {
            String candidate = value.substring(0, length) + "...";
            if (font.width(candidate) <= width) return candidate;
        }
        return "...";
    }
}
