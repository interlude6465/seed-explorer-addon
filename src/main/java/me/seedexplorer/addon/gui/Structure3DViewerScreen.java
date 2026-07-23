package me.seedexplorer.addon.gui;

import me.seedexplorer.addon.debug.PredictionDebugLogger;
import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.preview.StructurePreviewModel;
import me.seedexplorer.addon.preview.StructurePreviewSimulator;
import me.seedexplorer.addon.render.StructureColors;
import me.seedexplorer.addon.render.StructureIcons;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.utils.render.color.Color;
import me.seedexplorer.addon.workers.WorkerManager;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Structure3DViewerScreen extends Screen {
    private static final int SBW = 240;
    private static final int CHH = 28;
    private static final int PNW = 280;
    private static final int ITH = 20;
    private static final int TOP_BAR = 72;
    private static final int BG = 0xFF0B1117;
    private static final int SURFACE = 0xFF111A22;
    private static final int SURFACE_2 = 0xFF16212B;
    private static final int LINE = 0xFF2C3B48;
    private static final int TEXT = 0xFFE7EDF3;
    private static final int TEXT_MUTED = 0xFF8D9AA6;
    private static final int ACCENT = 0xFF45B6A6;
    private static final int GOLD = 0xFFE2B64C;
    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final long seed;
    private final GeneratedStructure structure;
    private final String structureName;
    private final StructureType structureType;
    private final String structureVariant;
    private final boolean hasShip;
    private final int blockX, blockZ;
    private final List<ChestLootOutput> chests;
    private volatile StructurePreviewModel previewModel;
    private volatile boolean previewLoading;
    private int selectedChest = -1;
    private int scrollOffset;
    private double panX, panY;
    // TP button rectangle, updated each render so the click handler matches the drawn position.
    private int tpButtonX, tpButtonY, tpButtonW, tpButtonH;
    private int prevChestX, prevChestY, prevChestW, prevChestH;
    private int nextChestX, nextChestY, nextChestW, nextChestH;
    private int exportChestX, exportChestY, exportChestW, exportChestH;
    private int copyChestX, copyChestY, copyChestW, copyChestH;
    private boolean dragging;
    private int dragStartX, dragStartY;
    private float zoom = 1.0f;

    public Structure3DViewerScreen(long seed, GeneratedStructure structure, List<ChestLootOutput> chests) {
        super(Component.literal("Structure: " + structure.displayName()));
        this.seed = seed;
        this.structure = structure;
        this.structureName = structure.displayName();
        this.structureType = structure.type;
        this.structureVariant = structure.variant;
        this.hasShip = structure.hasShip;
        this.blockX = structure.x;
        this.blockZ = structure.z;
        this.chests = chests == null ? List.of() : List.copyOf(chests);
        if (!this.chests.isEmpty()) selectedChest = 0;
        this.previewLoading = true;
        WorkerManager.get().submit(() -> {
            StructurePreviewModel model = StructurePreviewSimulator.preview(structure, seed);
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().screen == this) {
                    previewModel = model;
                    previewLoading = false;
                }
            });
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        super.extractRenderState(g, mx, my, dt);
        int sidebarW = sidebarWidth();
        int vw = width - sidebarW;
        Font f = Minecraft.getInstance().font;

        g.fill(0, 0, width, height, 0xFF000000);
        g.fill(0, 0, vw, height, BG);
        g.fill(0, 0, vw, TOP_BAR, 0xF2101820);
        g.fill(0, TOP_BAR - 1, vw, TOP_BAR, LINE);

        g.text(f, structureName, 14, 12, TEXT);
        g.text(f, "Seed " + seed, 14, 29, TEXT_MUTED);
        String meta = blockX + ", " + blockZ + "   " + chests.size() + " containers";
        g.text(f, meta, Math.max(14, vw - f.width(meta) - 16), 29, TEXT_MUTED);
        drawHeaderBadges(g, vw);

        drawStructurePreview(g, vw, mx, my);

        // Empty state: no loot to show for this structure.
        if (chests.isEmpty()) {
            Font f2 = Minecraft.getInstance().font;
            String line1 = "No loot to predict for this structure";
            String line2 = structureName + " is not in the validated set, or produced no containers.";
            g.text(f2, line1, (vw - f2.width(line1)) / 2, height / 2 - 8, 0xFFAAAAAA);
            g.text(f2, line2, (vw - f2.width(line2)) / 2, height / 2 + 4, 0xFF666666);
        }

        // Sidebar
        g.fill(vw, 0, sidebarW, height, 0xFF0D141B);
        g.fill(vw, 0, 1, height, LINE);
        drawSidebar(g, vw, mx, my);

        // Loot panel
        if (selectedChest >= 0 && selectedChest < chests.size()) {
            drawLootPanel(g, vw, mx, my);
        }
    }

    private void drawStructurePreview(GuiGraphicsExtractor g, int vw, int mx, int my) {
        StructurePreviewModel model = previewModel;
        if (model == null || model.isEmpty()) {
            drawChestLayout(g, vw, mx, my);
            if (previewLoading) {
                Font f = Minecraft.getInstance().font;
                g.text(f, "Building structure preview...", 14, TOP_BAR + 16, TEXT_MUTED);
            }
            return;
        }

        Renderer2D r = Renderer2D.COLOR;
        int areaX = 0;
        int areaY = TOP_BAR;
        int areaW = vw;
        int areaH = height - TOP_BAR;
        int centerX = areaX + areaW / 2 + (int) panX;
        int centerY = areaY + areaH / 2 + (int) panY;
        double scale = Math.max(1.0, Math.min(1.8, zoom));
        int spanX = Math.max(1, model.maxX() - model.minX() + 1);
        int spanZ = Math.max(1, model.maxZ() - model.minZ() + 1);
        int spanY = Math.max(1, model.maxY() - model.minY() + 1);
        double tile = Math.min(areaW / (double) (spanX + spanZ), areaH / (double) (spanY + spanX / 2.0)) * 0.55 * scale;
        tile = Math.max(3.0, Math.min(18.0, tile));

        g.fill(areaX, areaY, areaW, areaH, 0xFF0C1218);
        g.fill(areaX + 8, areaY + 8, areaW - 16, areaH - 16, 0xFF111A22);

        for (StructurePreviewModel.PreviewBlock block : model.blocks()) {
            int relX = block.x() - model.centerX();
            int relY = block.y() - model.centerY();
            int relZ = block.z() - model.centerZ();
            int sx = centerX + (int) Math.round((relX - relZ) * tile);
            int sy = centerY + (int) Math.round((relX + relZ) * tile * 0.5 - relY * tile * 0.8);
            if (sx < -64 || sx > vw + 64 || sy < TOP_BAR - 64 || sy > height + 64) continue;

            Color tint = structureTint(block.state());
            int w = Math.max(2, (int) Math.round(tile));
            int h = Math.max(2, (int) Math.round(tile * 0.6));
            r.begin();
            r.quad(sx - w / 2.0, sy - h / 2.0, w, h, new Color(tint.r, tint.g, tint.b, 220));
            r.boxLines(sx - w / 2.0, sy - h / 2.0, w, h, new Color(0, 0, 0, 70));
            r.render();
        }

        Renderer2D overlay = Renderer2D.COLOR;
        overlay.begin();
        overlay.quad(14, TOP_BAR + 10, 138, 20, new Color(11, 17, 24, 200));
        overlay.boxLines(14, TOP_BAR + 10, 138, 20, new Color(44, 59, 72, 200));
        overlay.render();
        Font f = Minecraft.getInstance().font;
        g.text(f, model.truncated() ? "Structure preview (clipped)" : "Structure preview", 22, TOP_BAR + 15, TEXT);
    }

    private Color structureTint(net.minecraft.world.level.block.state.BlockState state) {
        if (state == null || state.isAir()) return new Color(90, 98, 106, 220);
        String id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        if (id.contains("end_stone")) return new Color(222, 208, 132, 220);
        if (id.contains("purpur")) return new Color(184, 132, 220, 220);
        if (id.contains("stone") || id.contains("cobblestone") || id.contains("deepslate")) return new Color(130, 136, 146, 220);
        if (id.contains("plank") || id.contains("log") || id.contains("wood")) return new Color(152, 118, 76, 220);
        if (id.contains("brick") || id.contains("terracotta")) return new Color(186, 108, 84, 220);
        if (id.contains("sea_lantern") || id.contains("prismarine") || id.contains("conduit")) return new Color(98, 184, 196, 220);
        if (id.contains("blackstone") || id.contains("nether") || id.contains("basalt")) return new Color(108, 77, 128, 220);
        return new Color(160, 170, 180, 220);
    }

    private void drawHeaderBadges(GuiGraphicsExtractor g, int vw) {
        Renderer2D r = Renderer2D.COLOR;
        Font f = Minecraft.getInstance().font;
        int chipX = 14;
        int chipY = 49;
        int rightLimit = vw - 46;

        chipX = drawChip(g, r, structureType.displayName, chipX, chipY, rightLimit,
            new Color(19, 32, 42, 255), StructureColors.get(structureType), 0xFFE7EDF3);
        if (!structureVariant.isBlank()) {
            chipX = drawChip(g, r, structureVariant, chipX, chipY, rightLimit,
                new Color(23, 27, 36, 255), new Color(51, 65, 85, 255), 0xFFB7C3CF);
        }
        if (hasShip) {
            drawChip(g, r, "Ship", chipX, chipY, rightLimit,
                new Color(38, 31, 18, 255), new Color(197, 140, 49, 255), 0xFFF2D08B);
        }

        var icon = StructureIcons.get(structure);
        if (icon != null) {
            Renderer2D tex = Renderer2D.TEXTURE;
            tex.begin();
            tex.texQuad(vw - 32, 9, 18, 18, Color.WHITE);
            tex.render(icon.getTextureView(), icon.getSampler());
        }
    }

    private int drawChip(GuiGraphicsExtractor g, Renderer2D r, String text, int x, int y, int rightLimit,
                         Color bg, Color border, int textColor) {
        Font f = Minecraft.getInstance().font;
        int width = Math.min(Math.max(44, f.width(text) + 16), 128);
        if (x + width > rightLimit) return x;

        r.begin();
        r.quad(x, y, width, 16, bg);
        r.boxLines(x, y, width, 16, border);
        r.render();
        g.text(f, trimText(f, text, width - 14), x + 8, y + 4, textColor);
        return x + width + 6;
    }

    private String trimText(Font f, String text, int maxWidth) {
        if (f.width(text) <= maxWidth) return text;
        String suffix = "...";
        for (int i = text.length() - 1; i > 0; i--) {
            String candidate = text.substring(0, i) + suffix;
            if (f.width(candidate) <= maxWidth) return candidate;
        }
        return suffix;
    }

    private void drawChestLayout(GuiGraphicsExtractor g, int vw, int mx, int my) {
        if (chests.isEmpty()) return;
        Font f = Minecraft.getInstance().font;

        int cx = vw / 2 + (int) panX;
        int cy = TOP_BAR + (height - TOP_BAR) / 2 + (int) panY;

        // Find bounds
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ChestLootOutput c : chests) {
            minX = Math.min(minX, c.blockX());
            maxX = Math.max(maxX, c.blockX());
            minZ = Math.min(minZ, c.blockZ());
            maxZ = Math.max(maxZ, c.blockZ());
        }
        int rangeX = Math.max(maxX - minX + 1, 4);
        int rangeZ = Math.max(maxZ - minZ + 1, 4);
        int cellSize = (int) (Math.min(vw, height) * 0.6f / Math.max(rangeX, rangeZ) * zoom);
        cellSize = Math.max(8, Math.min(60, cellSize));

        // Draw floor grid
        int originX = cx - rangeX * cellSize / 2;
        int originZ = cy - rangeZ * cellSize / 2;

        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                int sx = originX + (x - minX) * cellSize + cellSize / 2;
                int sy = originZ + (z - minZ) * cellSize + cellSize / 2;
                boolean isEdge = x == minX - 1 || x == maxX + 1 || z == minZ - 1 || z == maxZ + 1;
                int half = isEdge ? cellSize / 2 : cellSize / 2 - 1;
                g.fill(sx - half, sy - half, sx + half, sy + half,
                    ((x + z) & 1) == 0 ? 0xFF17212A : 0xFF141C24);
            }
        }

        // Draw walls on edge
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                if (x != minX - 1 && x != maxX + 1 && z != minZ - 1 && z != maxZ + 1) continue;
                int sx = originX + (x - minX) * cellSize + cellSize / 2;
                int sy = originZ + (z - minZ) * cellSize + cellSize / 2;
                g.fill(sx - cellSize / 2, sy - cellSize / 2, sx + cellSize / 2, sy + cellSize / 2, 0xFF22303A);
                g.fill(sx - cellSize / 2, sy - cellSize / 2, sx + cellSize / 2, sy - cellSize / 2 + 1, LINE);
            }
        }

        // Draw chest markers
        boolean hoveredAny = false;
        for (int i = 0; i < chests.size(); i++) {
            ChestLootOutput c = chests.get(i);
            int sx = originX + (c.blockX() - minX) * cellSize + cellSize / 2;
            int sy = originZ + (c.blockZ() - minZ) * cellSize + cellSize / 2;
            int half = cellSize / 2 - 2;
            int dx = mx - sx, dy = my - sy;
            boolean hover = Math.abs(dx) < half && Math.abs(dy) < half && mx < width - SBW;

            int color;
            if (i == selectedChest) color = 0xFFFFD978;
            else if (hover) color = 0xFFFFE38A;
            else color = GOLD;

            // Chest body
            g.fill(sx - half, sy - half, sx + half, sy + half, color);
            // Outline
            g.fill(sx - half - 1, sy - half - 1, sx + half + 1, sy - half, 0xFF6E5422);
            g.fill(sx - half - 1, sy + half, sx + half + 1, sy + half + 1, 0xFF6E5422);
            g.fill(sx - half - 1, sy - half, sx - half, sy + half, 0xFF6E5422);
            g.fill(sx + half, sy - half, sx + half + 1, sy + half, 0xFF6E5422);
            // Label
            String num = String.valueOf(i + 1);
            g.text(f, num, sx - f.width(num) / 2, sy - 4, 0xFF000000);

            if (hover && !hoveredAny) {
                hoveredAny = true;
                g.fill(sx - 34, sy - half - 26, 72, 22, 0xEE0D141B);
                g.text(f, "Chest " + (i + 1), sx - 28, sy - half - 23, GOLD);
                g.text(f, c.blockX() + "," + c.blockY() + "," + c.blockZ(), sx - 30, sy - half - 11, TEXT_MUTED);
            }
        }
    }

    private void drawSidebar(GuiGraphicsExtractor g, int vw, int mx, int my) {
        Font f = Minecraft.getInstance().font;
        int ssx = vw;
        int sidebarW = width - vw;

        g.fill(ssx, 0, sidebarW, TOP_BAR, SURFACE);
        g.fill(ssx, TOP_BAR - 1, sidebarW, TOP_BAR, LINE);
        g.text(f, "Containers", ssx + 12, 12, TEXT);
        g.text(f, String.valueOf(chests.size()), ssx + sidebarW - 22 - f.width(String.valueOf(chests.size())), 12, ACCENT);

        int y = TOP_BAR + 10;
        for (int i = scrollOffset; i < chests.size(); i++) {
            if (y + CHH > height - 10) break;
            ChestLootOutput c = chests.get(i);
            boolean hover = mx >= ssx + 4 && mx <= ssx + sidebarW - 4 && my >= y && my <= y + CHH - 2;
            boolean selected = i == selectedChest;

            int bg = selected ? 0xFF1D5E67 : (hover ? SURFACE_2 : 0xFF101820);
            g.fill(ssx + 4, y, ssx + sidebarW - 4, y + CHH - 2, bg);
            if (selected) g.fill(ssx + 4, y, ssx + 7, y + CHH - 2, ACCENT);

            String label = "Chest " + (i + 1);
            int tc = selected ? 0xFFFFFFFF : (hover ? TEXT : TEXT_MUTED);
            g.text(f, label, ssx + 12, y + 6, tc);
            String yz = c.blockX() + ", " + c.blockY() + ", " + c.blockZ();
            g.text(f, yz, ssx + sidebarW - f.width(yz) - 12, y + 6, TEXT_MUTED);
            y += CHH;
        }
    }

    private void drawLootPanel(GuiGraphicsExtractor g, int vw, int mx, int my) {
        ChestLootOutput chest = chests.get(selectedChest);
        Font f = Minecraft.getInstance().font;
        int panelW = lootPanelWidth(vw);
        int px = Math.max(8, vw - panelW);

        g.fill(px, 0, panelW, height, 0xFF0E151D);
        g.fill(px, 0, 1, height, ACCENT);

        // Close button
        g.fill(px + panelW - 22, 8, px + panelW - 8, 22, 0xFF2A1619);
        g.text(f, "X", px + panelW - 18, 10, 0xFFFF7A7A);

        int y = 12;
        g.text(f, "Chest " + (selectedChest + 1), px + 12, y, TEXT);
        y += 15;
        g.text(f, chest.blockX() + ", " + chest.blockY() + ", " + chest.blockZ(), px + 12, y, TEXT_MUTED);
        y += 14;
        if (chest.lootTableId() != null) {
            g.text(f, chest.lootTableId(), px + 12, y, 0xFF6FAEC0);
            y += 14;
        }
        g.text(f, "Loot seed " + chest.seed(), px + 12, y, TEXT_MUTED);
        y += 14;
        g.text(f, chest.exact() ? "Accuracy: exact" : "Accuracy: approx (position only)",
            px + 12, y, chest.exact() ? 0xFF7ED88A : 0xFFD8B45A);
        y += 18;

        int navY = y;
        prevChestX = px + 12;
        prevChestY = navY;
        prevChestW = 22;
        prevChestH = 18;
        nextChestX = prevChestX + prevChestW + 6;
        nextChestY = navY;
        nextChestW = 22;
        nextChestH = 18;
        copyChestX = nextChestX + nextChestW + 6;
        copyChestY = navY;
        copyChestW = 42;
        copyChestH = 18;
        exportChestX = copyChestX + copyChestW + 6;
        exportChestY = navY;
        exportChestW = Math.max(48, panelW - 24 - (exportChestX - (px + 12)));
        exportChestH = 18;

        drawButton(g, prevChestX, prevChestY, prevChestW, prevChestH, selectedChest > 0, "<");
        drawButton(g, nextChestX, nextChestY, nextChestW, nextChestH, selectedChest + 1 < chests.size(), ">");
        drawButton(g, copyChestX, copyChestY, copyChestW, copyChestH, true, "Copy");
        drawButton(g, exportChestX, exportChestY, exportChestW, exportChestH, true, "Export");
        y += 26;

        int tpX = px + 12;
        int tpY = y;
        int tpW = Math.min(110, panelW - 24);
        tpButtonX = tpX;
        tpButtonY = tpY;
        tpButtonW = tpW;
        tpButtonH = 18;
        boolean tpHover = mx >= tpX && mx <= tpX + tpW && my >= tpY && my <= tpY + 18;
        g.fill(tpX, tpY, tpX + tpW, tpY + 18, tpHover ? 0xFF1B7B72 : 0xFF155B55);
        g.fill(tpX, tpY, tpX + tpW, tpY + 1, 0xFF45B6A6);
        g.text(f, "TP to chest", tpX + 10, tpY + 5, 0xFFFFFFFF);
        y += 26;

        // Separator
        g.fill(px + 12, y, px + PNW - 12, y + 1, LINE);
        y += 10;

        g.text(f, "Items (" + chest.predictedItems().size() + ")", px + 12, y, TEXT);
        y += 14;

        if (chest.predictedItems().isEmpty()) {
            g.text(f, "No items", px + 14, y, TEXT_MUTED);
        } else {
            for (ItemLoot item : chest.predictedItems()) {
                if (y > height - 20) break;
                String name = item.displayName();
                if (name == null || name.isEmpty()) continue;
                int cmin = item.minCount(), cmax = item.maxCount();
                String countStr = cmin == cmax ? "x" + cmin : "x" + cmin + "-" + cmax;
                boolean ench = item.randomEnchantment() || item.enchantmentId() != null
                    || (item.enchantmentSummary() != null && !item.enchantmentSummary().isBlank());
                int color = ench ? 0xFFD6A8FF : TEXT;

                // Item background
            g.fill(px + 12, y + 1, px + panelW - 12, y + ITH - 1, ench ? 0xFF21182C : 0xFF111A22);
                g.fill(px + 16, y + 6, px + 23, y + 13, ench ? 0xFF7A46C6 : ACCENT);
                if (ench) {
                    g.fill(px + 12, y + 1, px + 15, y + ITH - 1, 0xFF9E6DDB);
                }
                boolean hasSummary = item.enchantmentSummary() != null && !item.enchantmentSummary().isBlank();
                int rightReserve = ench && !hasSummary ? 86 : 10;
                g.text(f, trimText(f, name + " " + countStr, panelW - 40 - rightReserve), px + 28, y + 4, color);

                if (ench) {
                    String enchStr;
                    if (hasSummary) {
                        enchStr = "[" + item.enchantmentSummary() + "]";
                    } else if (item.enchantmentId() != null) {
                        String id = item.enchantmentId().contains(":")
                            ? item.enchantmentId().substring(item.enchantmentId().indexOf(':') + 1)
                            : item.enchantmentId();
                        id = id.replace('_', ' ');
                        String lvl = item.enchantmentLevel() > 0 && item.enchantmentLevel() <= ROMAN.length
                            ? ROMAN[item.enchantmentLevel() - 1] : String.valueOf(item.enchantmentLevel());
                        enchStr = "[" + id + " " + lvl + "]";
                    } else {
                        enchStr = "[Enchanted]";
                    }
                    if (hasSummary) {
                        y += 12;
                        g.text(f, trimText(f, enchStr, panelW - 28), px + 18, y + 4, 0xFFD6A8FF);
                    } else {
                        g.text(f, trimText(f, enchStr, 82), px + panelW - Math.min(f.width(enchStr), 82) - 14, y + 4, 0xFFD6A8FF);
                    }
                }
                y += ITH;
            }
        }
    }

    private void drawButton(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean enabled, String text) {
        Renderer2D r = Renderer2D.COLOR;
        Color bg = enabled ? new Color(26, 40, 47, 245) : new Color(22, 26, 30, 210);
        Color border = enabled ? new Color(69, 182, 166, 220) : new Color(64, 74, 84, 180);
        Font f = Minecraft.getInstance().font;
        r.begin();
        r.quad(x, y, w, h, bg);
        r.boxLines(x, y, w, h, border);
        r.render();
        g.text(f, text, x + (w - f.width(text)) / 2, y + 5, enabled ? 0xFFE7EDF3 : 0xFF8D9AA6);
    }

    private int sidebarWidth() {
        return Math.max(180, Math.min(SBW, width / 3));
    }

    private int lootPanelWidth(int previewWidth) {
        return Math.max(180, Math.min(PNW, previewWidth - 24));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ev, boolean doubled) {
        if (ev.button() == 0) {
            double mx = ev.x(), my = ev.y();

            // Close button on loot panel
            if (selectedChest >= 0) {
                int vw = width - sidebarWidth();
                int panelW = lootPanelWidth(vw);
                int px = Math.max(8, vw - panelW);
                if (mx >= px + panelW - 20 && mx <= px + panelW && my >= 4 && my <= 18) {
                    selectedChest = -1;
                    return true;
                }
                if (inButton(mx, my, prevChestX, prevChestY, prevChestW, prevChestH) && selectedChest > 0) {
                    selectedChest--;
                    return true;
                }
                if (inButton(mx, my, nextChestX, nextChestY, nextChestW, nextChestH) && selectedChest + 1 < chests.size()) {
                    selectedChest++;
                    return true;
                }
                if (inButton(mx, my, copyChestX, copyChestY, copyChestW, copyChestH)) {
                    copyLootReport();
                    return true;
                }
                if (inButton(mx, my, exportChestX, exportChestY, exportChestW, exportChestH)) {
                    exportLootReport();
                    return true;
                }
                if (tpButtonW > 0 && mx >= tpButtonX && mx <= tpButtonX + tpButtonW
                    && my >= tpButtonY && my <= tpButtonY + tpButtonH) {
                    teleportToSelectedChest();
                    return true;
                }
            }

            // Sidebar click
            if (mx >= width - sidebarWidth()) {
                int listY = TOP_BAR + 10;
                int idx = ((int) my - listY) / CHH + scrollOffset;
                if (idx >= 0 && idx < chests.size() && my >= listY) {
                    selectedChest = (selectedChest == idx) ? -1 : idx;
                    return true;
                }
            }

            // Start drag
            if (mx < width - sidebarWidth()) {
                if (selectedChest >= 0) selectedChest = -1;
                dragging = true;
                dragStartX = (int) mx;
                dragStartY = (int) my;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent ev) {
        if (ev.button() == 0) dragging = false;
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent ev, double dx, double dy) {
        if (ev.button() == 0 && dragging) {
            panX += dx;
            panY += dy;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double ha, double va) {
        if (mouseX >= width - sidebarWidth()) {
            scrollOffset = Math.max(0, Math.min(chests.size() - 1, scrollOffset + (va > 0 ? -1 : 1)));
            return true;
        }
        zoom *= (va > 0) ? 1.15f : 0.87f;
        zoom = Math.max(0.3f, Math.min(8.0f, zoom));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent ev) {
        if (ev.key() == 256 || ev.key() == 81) {
            onClose();
            return true;
        }
        return false;
    }

    private void teleportToSelectedChest() {
        if (selectedChest < 0 || selectedChest >= chests.size()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) return;
        ChestLootOutput chest = chests.get(selectedChest);
        mc.player.connection.sendCommand("tp " + chest.blockX() + " " + chest.blockY() + " " + chest.blockZ());
        PredictionDebugLogger.chestTeleport(structure, chest);
        ChatUtils.info("Seed Explorer: Sent teleport to chest at (highlight)"
            + chest.blockX() + ", " + chest.blockY() + ", " + chest.blockZ() + "(default).");
        onClose();
    }

    private void copyLootReport() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.keyboardHandler != null) mc.keyboardHandler.setClipboard(buildLootReport());
        ChatUtils.info("Seed Explorer: copied chest report to clipboard.");
    }

    private void exportLootReport() {
        String report = buildLootReport();
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("seed-explorer-reports");
            Files.createDirectories(dir);
            Path path = dir.resolve("structure-loot-" + sanitize(structureName) + "-" + LocalDateTime.now().format(FILE_TIME) + ".txt");
            Files.writeString(path, report, StandardCharsets.UTF_8);
            copyLootReport();
            ChatUtils.info("Seed Explorer: saved chest report to (highlight)" + path + "(default).");
        } catch (Exception e) {
            ChatUtils.warning("Seed Explorer: could not export chest report: " + e.getMessage());
        }
    }

    private String buildLootReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("Seed Explorer structure loot report\n");
        sb.append("seed=").append(seed).append('\n');
        sb.append("structure=").append(structureName).append('\n');
        sb.append("type=").append(structureType).append('\n');
        sb.append("variant=").append(structureVariant).append('\n');
        sb.append("hasShip=").append(hasShip).append('\n');
        sb.append("block=").append(blockX).append(',').append(blockZ).append('\n');
        sb.append("chests=").append(chests.size()).append('\n');
        for (int i = 0; i < chests.size(); i++) {
            ChestLootOutput chest = chests.get(i);
            sb.append('\n');
            sb.append("chest=").append(i + 1).append('\n');
            sb.append("pos=").append(chest.blockX()).append(',').append(chest.blockY()).append(',').append(chest.blockZ()).append('\n');
            sb.append("table=").append(chest.lootTableId()).append('\n');
            sb.append("seed=").append(chest.seed()).append('\n');
            sb.append("exact=").append(chest.exact()).append('\n');
            sb.append("summary=").append(chest.summary()).append('\n');
        }
        return sb.toString();
    }

    private static boolean inButton(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static String sanitize(String text) {
        String clean = text == null ? "report" : text.replaceAll("[^A-Za-z0-9._-]+", "-");
        return clean.isBlank() ? "report" : clean;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
