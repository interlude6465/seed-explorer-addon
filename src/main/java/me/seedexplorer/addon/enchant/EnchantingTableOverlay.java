package me.seedexplorer.addon.enchant;

import me.seedexplorer.addon.debug.PredictionDebugLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.EnchantmentMenu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Interactive prediction panel rendered beside the vanilla enchanting screen. */
public final class EnchantingTableOverlay {
    private static final EnchantingTableOverlay INSTANCE = new EnchantingTableOverlay();
    private static final int DEFAULT_IMAGE_WIDTH = 176;
    private static final int DEFAULT_IMAGE_HEIGHT = 166;
    private static final int PANEL_WIDTH = 340;
    private static final int MIN_PANEL_WIDTH = 170;
    private static final int PANEL_GAP = 8;
    private static final int PANEL_MAX_HEIGHT = 380;
    private static final int ROW_HEIGHT = 14;
    private static final int PICKER_HEIGHT = 16;
    private static final float DEFAULT_TEXT_SCALE = 0.72f;
    /* Keep long planner instructions visible on narrow GUI scales. */
    private static final float MIN_TEXT_SCALE = 0.34f;
    private static final ExecutorService PLANNER_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Seed Explorer enchantment planner");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicInteger PLAN_GENERATION = new AtomicInteger();

    private final Map<String, Integer> selected = new LinkedHashMap<>();
    private final Map<String, Integer> pendingTargets = new LinkedHashMap<>();
    private Set<String> conflicting = Set.of();
    private List<EnchantingTablePredictor.EnchantmentOption> options = List.of();
    private EnchantingTablePredictor.Report report;
    private volatile EnchantingTargetPlanner.Plan plan;
    private volatile EnchantingTargetPlanner.PartialPlan multiStepPlan;
    private volatile boolean planSearching;
    private String planKey = "";
    private long planDropRevision;
    private ItemStack trackedItem = ItemStack.EMPTY;
    private EnchantmentScreen trackedScreen;
    private int trackedSeed = Integer.MIN_VALUE;
    private int trackedResolvedSeed = Integer.MIN_VALUE;
    private int trackedCostsHash;
    private int scrollOffset;
    private boolean dropdownOpen;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int pickerTop;
    private int listTop;
    private int listBottom;

    private EnchantingTableOverlay() {
    }

    public static EnchantingTableOverlay get() {
        return INSTANCE;
    }

    /** Horizontal offset that centers the table plus prediction panel as one group. */
    public static int guiShift(int screenWidth, int guiWidth) {
        int availablePanelWidth = screenWidth - guiWidth - PANEL_GAP - 8;
        int panelWidth = Math.max(MIN_PANEL_WIDTH, Math.min(PANEL_WIDTH, availablePanelWidth));
        int groupWidth = guiWidth + PANEL_GAP + panelWidth;
        int groupLeft = Math.max(4, (screenWidth - groupWidth) / 2);
        int centeredGuiLeft = (screenWidth - guiWidth) / 2;
        return groupLeft - centeredGuiLeft;
    }

    public void renderOnScreen(GuiGraphicsExtractor graphics, int screenWidth, int screenHeight,
                               int guiLeft, int guiTop, int guiWidth, int guiHeight) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof EnchantmentScreen screen) || mc.player == null || mc.level == null) {
            clear();
            return;
        }

        layout(screenWidth, screenHeight, guiLeft, guiTop, guiWidth, guiHeight);
        refresh(screen);
        draw(graphics, screen, screenWidth, screenHeight);
    }

    /** Consumes a left-click when it lands inside the prediction controls. */
    public boolean mouseClicked(EnchantmentScreen screen, MouseButtonEvent click) {
        if (click.button() != 0) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        if (trackedScreen != screen || panelW <= 0) {
            layout(screen.width, screen.height,
                (screen.width - DEFAULT_IMAGE_WIDTH) / 2,
                (screen.height - DEFAULT_IMAGE_HEIGHT) / 2,
                DEFAULT_IMAGE_WIDTH, DEFAULT_IMAGE_HEIGHT);
        }
        refresh(screen);
        double mouseX = click.x();
        double mouseY = click.y();
        if (!inside(mouseX, mouseY, panelX, panelY, panelW, panelH)) {
            dropdownOpen = false;
            return false;
        }

        if (inside(mouseX, mouseY, panelX + 7, pickerTop, panelW - 14, PICKER_HEIGHT)) {
            dropdownOpen = !dropdownOpen;
            scrollOffset = clamp(scrollOffset, 0, Math.max(0, options.size() - visibleRows()));
            return true;
        }

        if (dropdownOpen && mouseY >= listTop && mouseY < listBottom && !options.isEmpty()) {
            int index = scrollOffset + (int) ((mouseY - listTop) / ROW_HEIGHT);
            if (index >= 0 && index < options.size()) {
                EnchantingTablePredictor.EnchantmentOption option = options.get(index);
                int rowRight = panelX + panelW - 12;
                if (mouseX >= rowRight - 62 && mouseX < rowRight - 47) {
                    adjust(option, -1);
                } else if (mouseX >= rowRight - 20 && mouseX < rowRight - 5) {
                    adjust(option, 1);
                } else if (mouseX < rowRight - 68) {
                    toggle(option);
                }
                logSelection();
                return true;
            }
        }
        return true;
    }

    /** Consumes scrolling over the compatible-enchantment list. */
    public boolean mouseScrolled(EnchantmentScreen screen, double mouseX, double mouseY, double scrollY) {
        if (trackedScreen != screen || panelW <= 0) {
            layout(screen.width, screen.height,
                (screen.width - DEFAULT_IMAGE_WIDTH) / 2,
                (screen.height - DEFAULT_IMAGE_HEIGHT) / 2,
                DEFAULT_IMAGE_WIDTH, DEFAULT_IMAGE_HEIGHT);
        }
        if (!dropdownOpen
            || !inside(mouseX, mouseY, panelX, listTop, panelW, Math.max(0, listBottom - listTop))) {
            return false;
        }
        int visible = visibleRows();
        int maxOffset = Math.max(0, options.size() - visible);
        if (maxOffset == 0) return true;
        scrollOffset = clamp(scrollOffset - (int) Math.signum(scrollY) * 3, 0, maxOffset);
        return true;
    }

    private void refresh(EnchantmentScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (screen == trackedScreen && mc.player != null && mc.level != null) {
            EnchantmentMenu menu = screen.getMenu();
            EnchantingSeedCracker.observe(mc.level, mc.player, menu);
            ItemStack current = menu.getSlot(0).getItem();
            Integer resolved = EnchantingSeedCracker.resolvedSeed(menu.getEnchantmentSeed());
            int resolvedKey = resolved == null ? Integer.MIN_VALUE : resolved;
            int costsHash = java.util.Arrays.hashCode(menu.costs)
                * 31 + java.util.Arrays.hashCode(menu.enchantClue)
                * 31 + java.util.Arrays.hashCode(menu.levelClue);
            if (ItemStack.matches(trackedItem, current)
                && trackedSeed == menu.getEnchantmentSeed()
                && trackedCostsHash == costsHash
                && trackedResolvedSeed == resolvedKey) {
                return;
            }
        }

        trackedScreen = screen;
        EnchantmentMenu menu = screen.getMenu();
        EnchantingSeedCracker.observe(mc.level, mc.player, menu);
        trackedSeed = menu.getEnchantmentSeed();
        Integer resolved = EnchantingSeedCracker.resolvedSeed(trackedSeed);
        trackedResolvedSeed = resolved == null ? Integer.MIN_VALUE : resolved;
        trackedCostsHash = java.util.Arrays.hashCode(menu.costs)
            * 31 + java.util.Arrays.hashCode(menu.enchantClue)
            * 31 + java.util.Arrays.hashCode(menu.levelClue);

        EnchantingTablePredictor.Report current;
        try {
            current = EnchantingTablePredictor.current(mc.level, mc.player,
                resolved == null ? trackedSeed : resolved, resolved != null);
        } catch (Throwable throwable) {
            report = null;
            options = List.of();
            PredictionDebugLogger.exception("event=enchant_gui_refresh_error", throwable);
            return;
        }
        report = current;
        ItemStack item = current == null ? ItemStack.EMPTY : current.item();

        boolean itemChanged = !ItemStack.matches(trackedItem, item);
        trackedItem = item.copy();
        if (itemChanged) {
            selected.clear();
            scrollOffset = 0;
            dropdownOpen = false;
        }
        try {
            options = EnchantingTablePredictor.compatibleEnchantments(item, mc.level.registryAccess());
        } catch (Throwable throwable) {
            options = List.of();
            PredictionDebugLogger.exception("event=enchant_gui_compatibility_error", throwable);
        }
        selected.keySet().removeIf(id -> options.stream().noneMatch(option -> option.id().equals(id)));
        for (EnchantingTablePredictor.EnchantmentOption option : options) {
            Integer targetLevel = pendingTargets.get(option.id());
            if (targetLevel != null) {
                selected.put(option.id(), Math.min(option.maxLevel(), Math.max(1, targetLevel)));
            }
        }
        updateConflicts();
        scrollOffset = clamp(scrollOffset, 0, Math.max(0, options.size() - visibleRows()));
    }

    private void draw(GuiGraphicsExtractor g, EnchantmentScreen screen, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF20B1117);
        g.fill(panelX, panelY, panelX + panelW, panelY + 1, 0xFF45B6A6);
        g.fill(panelX, panelY, panelX + 1, panelY + panelH, 0xFF45B6A6);

        text(g, font, "Seed Explorer Enchanting", panelX + 9, panelY + 8, 0xFFE7EDF3, panelW - 18);
        if (trackedItem.isEmpty()) {
            text(g, font, "Place an item in the table slot.", panelX + 9, panelY + 30, 0xFF8D9AA6, panelW - 18);
            text(g, font, "The compatible enchantments will appear here.", panelX + 9, panelY + 44, 0xFF8D9AA6, panelW - 18);
            return;
        }

        g.item(trackedItem, panelX + 9, panelY + 24);
        g.itemDecorations(font, trackedItem, panelX + 9, panelY + 24);
        text(g, font, trackedItem.getHoverName().getString(),
            panelX + 34, panelY + 27, 0xFFE7EDF3, panelW - 43);
        String state = report == null || report.bookshelfCount() < 0
            ? "Table/bookshelves unavailable"
            : !report.exact()
                ? crackStatus()
                : "Seed " + report.enchantmentSeed() + "  Bookshelves " + report.bookshelfCount();
        text(g, font, state, panelX + 9, panelY + 48, 0xFF8D9AA6, panelW - 18);
        text(g, font, playerRngStatus(), panelX + 9, panelY + 60, 0xFF8D9AA6, panelW - 18);
        text(g, font, report != null && !report.exact()
            ? "Swap item to add an observation"
            : "Select target enchantments", panelX + 9, panelY + 70, 0xFFD8B45A, panelW - 18);

        drawPickerButton(g, font);
        if (dropdownOpen) {
            drawDropdown(g, font);
            return;
        }

        List<EnchantingTablePredictor.RequestedEnchantment> requests = requests();
        ensurePlan(requests);
        String selectedText = selected.isEmpty() ? "Selected: none" : "Selected: " + selectedSummary();
        int selectedY = pickerTop + PICKER_HEIGHT + 5;
        text(g, font, selectedText, panelX + 9, selectedY,
            !conflicting.isEmpty() ? 0xFFFF7777 : selected.isEmpty() ? 0xFF8D9AA6 : 0xFFE7EDF3,
            panelW - 18);
        String planText = !conflicting.isEmpty()
            ? "Plan: incompatible enchantments selected"
            : selected.isEmpty()
            ? "Plan: select target enchantments"
            : planSearching
                ? "Plan: searching future player RNG states..."
                : plan != null
                    ? "Plan: " + planInstruction(plan)
                    : multiStepPlan != null
                        ? "Multi-step: " + multiStepInstruction(multiStepPlan)
                    : EnchantingPlayerRng.playerSeed() == null
                        ? "Plan: enchant twice to crack the player RNG"
                        : "Plan: no result within " + EnchantingTargetPlanner.DEFAULT_MAX_ITEM_THROWS
                            + " item drops";
        int planY = selectedY + 11;
        text(g, font, planText, panelX + 9, planY,
            !conflicting.isEmpty() ? 0xFFFF7777
                : plan != null || multiStepPlan != null ? 0xFF7ED88A : 0xFF8D9AA6,
            panelW - 18);
        int offersTitleY = planY + 13;
        text(g, font, "Predicted offers", panelX + 9, offersTitleY, 0xFFD8B45A, panelW - 18);
        int offersY = offersTitleY + 12;
        if (report == null || report.offers().isEmpty()) {
            text(g, font, "Waiting for the table state...", panelX + 9, offersY, 0xFF8D9AA6, panelW - 18);
        } else if (!report.exact()) {
            text(g, font, "Offers will appear after the seed is cracked.", panelX + 9, offersY,
                0xFF8D9AA6, panelW - 18);
        } else {
            for (int i = 0; i < report.offers().size(); i++) {
                EnchantingTablePredictor.Offer offer = report.offers().get(i);
                boolean matches = !requests.isEmpty() && offer.hasAll(requests);
                String line = (i + 1) + ". " + offer.summary();
                if (matches) line = "MATCH " + line;
                text(g, font, line, panelX + 9, offersY + i * 11,
                    matches ? 0xFF7ED88A : 0xFFE7EDF3, panelW - 18);
            }
        }
    }

    private void drawPickerButton(GuiGraphicsExtractor g, Font font) {
        int color = !conflicting.isEmpty() ? 0xFF52282C : dropdownOpen ? 0xFF273B42 : 0xFF202C33;
        g.fill(panelX + 7, pickerTop, panelX + panelW - 7, pickerTop + PICKER_HEIGHT, color);
        g.fill(panelX + 7, pickerTop, panelX + panelW - 7, pickerTop + 1, 0xFF40515A);
        String label = options.isEmpty()
            ? "No compatible enchantments"
            : "Enchantments (" + selected.size() + " selected)";
        text(g, font, label, panelX + 11, pickerTop + 3,
            conflicting.isEmpty() ? 0xFFE7EDF3 : 0xFFFF7777, panelW - 36);
        text(g, font, dropdownOpen ? "^" : "v", panelX + panelW - 20, pickerTop + 3,
            0xFFB5C0C8, 10);
    }

    private void drawDropdown(GuiGraphicsExtractor g, Font font) {
        g.fill(panelX + 7, pickerTop + PICKER_HEIGHT, panelX + panelW - 7,
            panelY + panelH - 5, 0xFA111A20);
        int visible = visibleRows();
        if (options.isEmpty()) {
            text(g, font, "No compatible table enchantments.", panelX + 11, listTop,
                0xFF8D9AA6, panelW - 22);
            return;
        }

        for (int row = 0; row < visible; row++) {
            int index = scrollOffset + row;
            if (index >= options.size()) break;
            drawOptionRow(g, font, options.get(index), listTop + row * ROW_HEIGHT);
        }
        drawScrollbar(g, visible);
    }

    private void drawScrollbar(GuiGraphicsExtractor g, int visible) {
        if (options.size() <= visible || visible <= 0) return;
        int trackTop = listTop;
        int trackBottom = listBottom;
        int trackHeight = Math.max(1, trackBottom - trackTop);
        int thumbHeight = Math.max(8, trackHeight * visible / options.size());
        int maxOffset = Math.max(1, options.size() - visible);
        int thumbTop = trackTop + (trackHeight - thumbHeight) * scrollOffset / maxOffset;
        int x = panelX + panelW - 10;
        g.fill(x, trackTop, x + 2, trackBottom, 0xFF263139);
        g.fill(x, thumbTop, x + 2, thumbTop + thumbHeight, 0xFF45B6A6);
    }

    private void drawOptionRow(GuiGraphicsExtractor g, Font font,
                               EnchantingTablePredictor.EnchantmentOption option, int y) {
        boolean active = selected.containsKey(option.id());
        boolean conflict = active && conflicting.contains(option.id());
        int rowRight = panelX + panelW - 12;
        if (active) g.fill(panelX + 8, y, panelX + panelW - 11, y + ROW_HEIGHT - 1,
            conflict ? 0x775E292E : 0x553B675F);
        g.fill(panelX + 11, y + 2, panelX + 20, y + 11,
            conflict ? 0xFFD85858 : active ? 0xFF45B6A6 : 0xFF33414A);
        if (active) g.fill(panelX + 13, y + 4, panelX + 18, y + 9, 0xFFE7EDF3);
        text(g, font, option.displayName(), panelX + 24, y + 2,
            conflict ? 0xFFFF7777 : active ? 0xFFE7EDF3 : 0xFFB5C0C8, panelW - 101);

        g.fill(rowRight - 62, y + 1, rowRight - 47, y + 12, 0xFF27333B);
        g.fill(rowRight - 20, y + 1, rowRight - 5, y + 12, 0xFF27333B);
        text(g, font, "-", rowRight - 57, y + 2, 0xFFE7EDF3, 10);
        text(g, font, "+", rowRight - 15, y + 2, 0xFFE7EDF3, 10);
        String level = active ? String.valueOf(selected.get(option.id())) : "-";
        text(g, font, level, rowRight - 39, y + 2,
            conflict ? 0xFFFF7777 : active ? 0xFFE7EDF3 : 0xFF8D9AA6, 14);
    }

    private void toggle(EnchantingTablePredictor.EnchantmentOption option) {
        if (selected.containsKey(option.id())) {
            selected.remove(option.id());
            pendingTargets.remove(option.id());
        } else {
            selected.put(option.id(), 1);
            pendingTargets.put(option.id(), 1);
        }
        updateConflicts();
        invalidatePlan();
    }

    private void adjust(EnchantingTablePredictor.EnchantmentOption option, int delta) {
        int level = selected.getOrDefault(option.id(), 1) + delta;
        if (level < 1) {
            selected.remove(option.id());
            pendingTargets.remove(option.id());
        } else {
            int adjusted = Math.min(option.maxLevel(), level);
            selected.put(option.id(), adjusted);
            pendingTargets.put(option.id(), adjusted);
        }
        updateConflicts();
        invalidatePlan();
    }

    private void updateConflicts() {
        Minecraft mc = Minecraft.getInstance();
        if (selected.size() < 2 || mc.level == null) {
            conflicting = Set.of();
            return;
        }

        List<String> ids = List.copyOf(selected.keySet());
        Set<String> result = new HashSet<>();
        for (int first = 0; first < ids.size(); first++) {
            for (int second = first + 1; second < ids.size(); second++) {
                if (!EnchantingTablePredictor.areCompatible(
                    ids.get(first), ids.get(second), mc.level.registryAccess())) {
                    result.add(ids.get(first));
                    result.add(ids.get(second));
                }
            }
        }
        conflicting = Set.copyOf(result);
    }

    private void ensurePlan(List<EnchantingTablePredictor.RequestedEnchantment> requests) {
        if (requests.isEmpty() || !conflicting.isEmpty()
            || report == null || !report.exact() || trackedItem.isEmpty()
            || report.bookshelfCount() < 0 || Minecraft.getInstance().level == null) {
            plan = null;
            multiStepPlan = null;
            planSearching = false;
            planKey = "";
            PLAN_GENERATION.incrementAndGet();
            return;
        }
        /* Keep the anchored plan while the client-side click is awaiting server confirmation. */
        if (EnchantingPlayerRng.enchantmentPending()) return;
        Long playerSeed = EnchantingPlayerRng.playerSeed();
        String key = trackedItem.getHoverName().getString() + "#" + trackedItem.getCount()
            + "#" + report.enchantmentSeed() + "#" + report.bookshelfCount() + "#" + requests
            + (playerSeed == null ? "#unknown-player-seed" : "");
        if (plan != null && !plan.currentSeed() && playerSeed == null) {
            invalidatePlan();
        }
        if (key.equals(planKey)) return;
        planKey = key;
        plan = null;
        multiStepPlan = null;
        planSearching = true;
        long planStartDropRevision = EnchantingPlayerRng.dropCount();
        int generation = PLAN_GENERATION.incrementAndGet();
        ItemStack item = trackedItem.copy();
        EnchantingTablePredictor.Report activeReport = report;
        var registryAccess = Minecraft.getInstance().level.registryAccess();
        PLANNER_WORKER.submit(() -> {
            EnchantingTargetPlanner.Plan result = null;
            EnchantingTargetPlanner.PartialPlan partial = null;
            try {
                result = EnchantingTargetPlanner.find(item, activeReport.enchantmentSeed(),
                    activeReport.bookshelfCount(), requests, activeReport, registryAccess).orElse(null);
                if (result == null) {
                    partial = EnchantingTargetPlanner.findBestPartial(item,
                        activeReport.enchantmentSeed(), activeReport.bookshelfCount(), requests,
                        activeReport, registryAccess).orElse(null);
                }
                if (result == null && playerSeed != null) {
                    EnchantingTargetPlanner.FutureSearch future = EnchantingTargetPlanner.searchFuture(item, playerSeed,
                        activeReport.bookshelfCount(), requests, registryAccess,
                        EnchantingTargetPlanner.DEFAULT_MAX_ITEM_THROWS);
                    result = future.exact();
                    if (betterPartial(future.bestPartial(), partial)) partial = future.bestPartial();
                }
            } catch (Throwable throwable) {
                PredictionDebugLogger.exception("event=enchant_gui_plan_error", throwable);
            }
            if (PLAN_GENERATION.get() != generation) return;
            if (EnchantingPlayerRng.dropCount() != planStartDropRevision) {
                plan = null;
                multiStepPlan = null;
                planSearching = false;
                planKey = "";
                PredictionDebugLogger.append("event=enchant_rng_plan_stale_drops\n");
                return;
            }
            plan = result;
            multiStepPlan = result == null ? partial : null;
            planSearching = false;
            planDropRevision = planStartDropRevision;
            PredictionDebugLogger.append("event=enchant_rng_plan"
                + " playerState=" + EnchantingPlayerRng.state().name().toLowerCase(java.util.Locale.ROOT)
                + " found=" + (result != null)
                + " partial=" + (partial == null ? 0 : partial.matched().size())
                + (result == null ? "" : " itemThrows=" + result.itemThrows()
                    + " xpSeed=" + result.enchantmentSeed()
                    + " bookshelves=" + result.bookshelfCount()
                    + " slot=" + (result.slot() + 1)) + "\n");
        });
    }

    private String crackStatus() {
        return switch (EnchantingSeedCracker.state()) {
            case INVALID -> "Seed crack needs another item observation";
            case CRACKING -> "XP seed" + (EnchantingSeedCracker.candidateCount() > 0
                ? ": " + String.format(java.util.Locale.ROOT, "%,d", EnchantingSeedCracker.candidateCount())
                    + " candidates" : ": scanning...");
            default -> "Waiting for hidden XP seed";
        };
    }

    private String playerRngStatus() {
        return switch (EnchantingPlayerRng.state()) {
            case UNKNOWN -> "Player RNG: enchant twice";
            case WAITING_FIRST -> "Player RNG: waiting for seed 1";
            case HALF_CRACKED -> "Player RNG: 1/2";
            case WAITING_SECOND -> "Player RNG: waiting for seed 2";
            case CRACKED -> "Player RNG cracked";
            case DESYNCED -> "Player RNG desynced; enchant twice";
        };
    }

    private void invalidatePlan() {
        plan = null;
        multiStepPlan = null;
        planSearching = false;
        planKey = "";
        planDropRevision = EnchantingPlayerRng.dropCount();
        PLAN_GENERATION.incrementAndGet();
    }

    private String planInstruction(EnchantingTargetPlanner.Plan current) {
        if (current.currentSeed()) return current.instruction();

        long dropped = Math.max(0L, EnchantingPlayerRng.dropCount() - planDropRevision);
        int remaining = (int) Math.max(0L,
            (long) current.itemThrows() - Math.min(Integer.MAX_VALUE, dropped));
        String bookshelf = current.currentBookshelves()
            ? ""
            : "set bookshelves to " + current.bookshelfCount() + "; ";
        String reroll = remaining == 0
            ? "Do one dummy enchant; "
            : "Drop " + remaining + " item" + (remaining == 1 ? "" : "s")
                + ", then do one dummy enchant; ";
        return reroll + bookshelf + "click offer " + (current.slot() + 1);
    }

    private String multiStepInstruction(EnchantingTargetPlanner.PartialPlan partial) {
        EnchantingTargetPlanner.Plan base = partial.base();
        String setup = base.currentSeed()
            ? (base.currentBookshelves() ? "click " + (base.slot() + 1)
                : "set " + base.bookshelfCount() + " shelves; click " + (base.slot() + 1))
            : planInstruction(base);
        String matched = partial.matched().stream()
            .map(EnchantingTablePredictor.RequestedEnchantment::display)
            .collect(java.util.stream.Collectors.joining(" + "));
        String missing = partial.missing().stream()
            .map(EnchantingTablePredictor.RequestedEnchantment::display)
            .collect(java.util.stream.Collectors.joining(" + "));
        return setup + " for " + matched + "; books: " + missing + "; combine books, then anvil once";
    }

    private boolean betterPartial(EnchantingTargetPlanner.PartialPlan candidate,
                                  EnchantingTargetPlanner.PartialPlan current) {
        if (candidate == null) return false;
        if (current == null) return true;
        if (candidate.matched().size() != current.matched().size()) {
            return candidate.matched().size() > current.matched().size();
        }
        return candidate.base().itemThrows() < current.base().itemThrows();
    }

    private List<EnchantingTablePredictor.RequestedEnchantment> requests() {
        List<EnchantingTablePredictor.RequestedEnchantment> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : selected.entrySet()) {
            result.add(new EnchantingTablePredictor.RequestedEnchantment(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }

    private String selectedSummary() {
        StringBuilder result = new StringBuilder();
        for (EnchantingTablePredictor.RequestedEnchantment request : requests()) {
            if (!result.isEmpty()) result.append(", ");
            result.append(request.display());
        }
        return result.toString();
    }

    private void logSelection() {
        if (trackedItem.isEmpty()) return;
        PredictionDebugLogger.append("event=enchant_gui_selection item="
            + trackedItem.getHoverName().getString().replace("\"", "\\\"")
            + " requested=\"" + selectedSummary().replace("\"", "\\\"") + "\"\n");
    }

    private void layout(int screenWidth, int screenHeight, int guiLeft, int guiTop,
                        int guiWidth, int guiHeight) {
        panelW = Math.min(PANEL_WIDTH, Math.max(MIN_PANEL_WIDTH, screenWidth - guiLeft - guiWidth - PANEL_GAP - 2));
        panelX = guiLeft + guiWidth + PANEL_GAP;
        if (panelW < 190 && guiLeft >= 190) {
            panelW = Math.min(PANEL_WIDTH, guiLeft - 6);
            panelX = guiLeft - panelW - 6;
        }
        panelW = Math.max(MIN_PANEL_WIDTH, Math.min(panelW, screenWidth - 8));
        panelX = clamp(panelX, 4, Math.max(4, screenWidth - panelW - 4));
        panelY = clamp(guiTop, 4, Math.max(4, screenHeight - 180));
        panelH = Math.min(PANEL_MAX_HEIGHT, Math.max(guiHeight, screenHeight - panelY - 4));
        panelH = Math.max(180, panelH);
        pickerTop = panelY + 78;
        listTop = pickerTop + PICKER_HEIGHT + 3;
        listBottom = Math.max(listTop + ROW_HEIGHT, panelY + panelH - 6);
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
    }

    private boolean inside(double x, double y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private void clear() {
        trackedScreen = null;
        trackedItem = ItemStack.EMPTY;
        trackedResolvedSeed = Integer.MIN_VALUE;
        options = List.of();
        report = null;
        selected.clear();
        pendingTargets.clear();
        conflicting = Set.of();
        invalidatePlan();
        scrollOffset = 0;
        dropdownOpen = false;
        panelW = 0;
    }

    private static void text(GuiGraphicsExtractor graphics, Font font, String value,
                             int x, int y, int color, int width) {
        if (value == null || value.isEmpty() || width <= 0) return;
        int textWidth = font.width(value);
        float scale = Math.min(DEFAULT_TEXT_SCALE, width / (float) textWidth);
        scale = Math.max(MIN_TEXT_SCALE, scale);
        String rendered = scale <= MIN_TEXT_SCALE
            ? trim(font, value, Math.max(4, (int) (width / scale)))
            : value;
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale);
        graphics.text(font, rendered, 0, 0, color);
        graphics.pose().popMatrix();
    }

    private static String trim(Font font, String value, int width) {
        if (value == null) return "";
        if (width <= 4 || font.width(value) <= width) return value;
        for (int i = value.length() - 1; i > 0; i--) {
            String candidate = value.substring(0, i) + "...";
            if (font.width(candidate) <= width) return candidate;
        }
        return "...";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
