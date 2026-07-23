/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.render;

import me.seedexplorer.addon.map.BiomeGenerator;
import me.seedexplorer.addon.modules.SeedExplorerModule;
import me.seedexplorer.addon.seed.SeedManager;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.Texture;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/** Draws the compact top-left live minimap. */
public class MinimapOverlay {
    private static final Color FRAME = new Color(8, 11, 17, 238);
    private static final Color INNER = new Color(15, 19, 25, 238);
    private static final Color BORDER = new Color(86, 176, 194, 235);

    public void render(Render2DEvent event, SeedExplorerModule module) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        int size = module.minimapSize.get();
        int x = 12;
        int y = 12;
        int mapX = x + 7;
        int mapY = y + 7;
        int mapSize = size - 14;

        drawFrame(x, y, size);
        drawTerrain(mc, mapX, mapY, mapSize);
        drawEntities(mc, mapX, mapY, mapSize);
        drawPlayer(mc, mapX + mapSize / 2.0, mapY + mapSize / 2.0);
        drawCompass(mapX, mapY, mapSize);
        drawCoords(mc, x, y + size + 4);
    }

    private void drawFrame(int x, int y, int size) {
        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(x + 3, y + 4, size, size, new Color(0, 0, 0, 110));
        r.quad(x, y, size, size, FRAME);
        r.quad(x + 5, y + 5, size - 10, size - 10, new Color(40, 53, 62, 210));
        r.quad(x + 7, y + 7, size - 14, size - 14, INNER);
        r.quad(x, y, size, 2, new Color(66, 122, 144, 220));
        r.boxLines(x, y, size, size, BORDER);
        r.boxLines(x + 5, y + 5, size - 10, size - 10, new Color(220, 242, 245, 135));
        drawCorner(r, x + 2, y + 2, 11, 1, 1);
        drawCorner(r, x + size - 13, y + 2, 11, -1, 1);
        drawCorner(r, x + 2, y + size - 13, 11, 1, -1);
        drawCorner(r, x + size - 13, y + size - 13, 11, -1, -1);
        r.render();
    }

    private void drawCorner(Renderer2D r, int x, int y, int length, int xDir, int yDir) {
        Color color = new Color(225, 248, 250, 205);
        int startX = xDir > 0 ? x : x + length;
        int startY = yDir > 0 ? y : y + length;
        r.quad(Math.min(startX, startX + xDir * length), startY, length, 1, color);
        r.quad(startX, Math.min(startY, startY + yDir * length), 1, length, color);
    }

    private void drawTerrain(Minecraft mc, int x, int y, int size) {
        int radius = 56;
        int step = terrainStep(size);
        double scale = size / (double) (radius * 2);
        int px = mc.player.blockPosition().getX();
        int pz = mc.player.blockPosition().getZ();
        Renderer2D r = Renderer2D.COLOR;
        r.begin();

        for (int dz = -radius; dz < radius; dz += step) {
            for (int dx = -radius; dx < radius; dx += step) {
                int wx = px + dx + step / 2;
                int wz = pz + dz + step / 2;
                Color color = terrainColor(mc, wx, wz);
                double sx = x + (dx + radius) * scale;
                double sy = y + (dz + radius) * scale;
                double tileSize = Math.ceil(scale * step) + 1;
                r.quad(sx, sy, tileSize, tileSize, color);
            }
        }

        r.render();
    }

    private int terrainStep(int mapSize) {
        if (mapSize >= 210) return 1;
        if (mapSize >= 135) return 2;
        return 3;
    }

    private Color terrainColor(Minecraft mc, int x, int z) {
        Level level = mc.level;
        LevelChunk chunk = level.getChunkSource().getChunkNow(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        if (chunk == null) return predictedBiomeColor(mc, x, z);

        int y = sampleY(mc, level, x, z);
        if (y < level.getMinY() || y >= level.getMaxY()) return predictedBiomeColor(mc, x, z);
        y = firstVisibleY(level, x, z, y);

        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = chunk.getBlockState(pos);
        return colorForState(mc, level, pos, state, y, x, z);
    }

    private Color colorForState(Minecraft mc, Level level, BlockPos pos, BlockState state, int y, int x, int z) {
        if (state.isAir()) return predictedBiomeColor(mc, x, z);

        int shade = Math.max(-28, Math.min(36, y - 64));
        if (state.is(Blocks.WATER)) return shade(new Color(32, 96, 178, 245), Math.max(-18, Math.min(18, shade / 2)));
        if (state.is(Blocks.LAVA)) return new Color(236, 94, 32, 255);
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MOSS_BLOCK)) return shade(blend(predictedBiomeColor(mc, x, z), new Color(70, 145, 72, 255), 0.45), shade / 2);
        if (state.is(BlockTags.LEAVES)) return shade(blend(predictedBiomeColor(mc, x, z), new Color(36, 112, 42, 255), 0.35), shade / 3);
        if (state.is(BlockTags.LOGS)) return shade(new Color(98, 76, 47, 255), shade / 2);
        if (state.is(BlockTags.SAND)) return shade(new Color(218, 202, 132, 255), shade);
        if (state.is(BlockTags.SNOW) || state.is(BlockTags.ICE) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)) return new Color(220, 232, 238, 255);
        if (state.is(BlockTags.DIRT) || state.is(Blocks.MUD)) return shade(new Color(110, 84, 54, 255), shade);
        if (state.is(BlockTags.TERRACOTTA)) return shade(new Color(154, 92, 58, 255), shade);
        if (state.is(BlockTags.BASE_STONE_OVERWORLD)) return shade(new Color(118, 122, 118, 255), shade);
        if (state.is(BlockTags.BASE_STONE_NETHER) || state.is(Blocks.NETHERRACK)) return shade(new Color(132, 48, 52, 255), shade);
        if (state.is(Blocks.END_STONE)) return shade(new Color(206, 202, 142, 255), shade);

        MapColor mapColor = state.getMapColor(level, pos);
        if (mapColor != MapColor.NONE) {
            int argb = mapColor.calculateARGBColor(MapColor.Brightness.NORMAL);
            return shade(saturate(new Color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, 255), 1.10), shade / 2);
        }

        return predictedBiomeColor(mc, x, z);
    }

    private Color predictedBiomeColor(Minecraft mc, int x, int z) {
        long seed = SeedManager.get().getWorldSeed();
        int dimension = dimensionId(mc);
        if (seed == 0) {
            return switch (dimension) {
                case -1 -> new Color(92, 36, 40, 230);
                case 1 -> new Color(188, 184, 126, 230);
                default -> new Color(54, 86, 62, 230);
            };
        }

        int abgr = BiomeGenerator.getBiomeColor(x, z, dimension);
        Color color = colorFromAbgr(abgr, 235);
        if (dimension == 1) return blend(color, new Color(205, 200, 142, 235), 0.50);
        if (dimension == -1) return saturate(color, 1.18);
        return saturate(color, 1.16);
    }

    private Color colorFromAbgr(int abgr, int alpha) {
        int r = abgr & 0xFF;
        int g = (abgr >> 8) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        return new Color(r, g, b, Math.max(0, Math.min(255, alpha)));
    }

    private Color shade(Color base, int shade) {
        int r = Math.max(0, Math.min(255, base.r + shade));
        int g = Math.max(0, Math.min(255, base.g + shade));
        int b = Math.max(0, Math.min(255, base.b + shade));
        return new Color(r, g, b, base.a);
    }

    private Color blend(Color a, Color b, double bWeight) {
        double weight = Math.max(0.0, Math.min(1.0, bWeight));
        double aWeight = 1.0 - weight;
        return new Color(
            (int) Math.max(0, Math.min(255, a.r * aWeight + b.r * weight)),
            (int) Math.max(0, Math.min(255, a.g * aWeight + b.g * weight)),
            (int) Math.max(0, Math.min(255, a.b * aWeight + b.b * weight)),
            Math.max(a.a, b.a)
        );
    }

    private Color saturate(Color color, double amount) {
        double gray = color.r * 0.299 + color.g * 0.587 + color.b * 0.114;
        return new Color(
            (int) Math.max(0, Math.min(255, gray + (color.r - gray) * amount)),
            (int) Math.max(0, Math.min(255, gray + (color.g - gray) * amount)),
            (int) Math.max(0, Math.min(255, gray + (color.b - gray) * amount)),
            color.a
        );
    }

    private int sampleY(Minecraft mc, Level level, int x, int z) {
        int playerY = mc.player.blockPosition().getY();
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (level.dimension() == Level.NETHER) return sampleNearPlayerY(level, x, z, playerY);

        boolean caveMode = playerY < 45 && surfaceY - playerY > 18;
        if (!caveMode) return surfaceY;

        return sampleNearPlayerY(level, x, z, playerY);
    }

    private int sampleNearPlayerY(Level level, int x, int z, int playerY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, playerY, z);
        for (int y = Math.min(level.getMaxY() - 1, playerY + 8); y >= Math.max(level.getMinY(), playerY - 8); y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) return y;
        }
        return playerY;
    }

    private int firstVisibleY(Level level, int x, int z, int startY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, startY, z);
        for (int y = Math.min(level.getMaxY() - 1, startY); y >= Math.max(level.getMinY(), startY - 10); y--) {
            pos.setY(y);
            if (!level.getBlockState(pos).isAir()) return y;
        }
        return startY;
    }

    private int dimensionId(Minecraft mc) {
        if (mc.level == null) return 0;
        if (mc.level.dimension() == Level.NETHER) return -1;
        if (mc.level.dimension() == Level.END) return 1;
        return 0;
    }

    private void drawEntities(Minecraft mc, int x, int y, int size) {
        int radius = 48;
        double scale = size / (double) (radius * 2);
        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;
            double dx = entity.getX() - mc.player.getX();
            double dz = entity.getZ() - mc.player.getZ();
            if (Math.abs(dx) > radius || Math.abs(dz) > radius) continue;
            Color color = entityColor(entity);
            double sx = x + (dx + radius) * scale;
            double sy = y + (dz + radius) * scale;
            r.quad(sx - 3, sy - 3, 6, 6, new Color(0, 0, 0, 180));
            r.quad(sx - 2, sy - 2, 4, 4, color);
        }
        r.render();
    }

    private Color entityColor(Entity entity) {
        if (entity instanceof Player) return new Color(245, 245, 245, 230);
        if (entity instanceof Mob) return new Color(190, 45, 55, 230);
        if (entity instanceof LivingEntity) return new Color(230, 205, 70, 230);
        if (entity instanceof ItemEntity) return new Color(120, 210, 255, 220);
        return new Color(170, 170, 180, 190);
    }

    private void drawPlayer(Minecraft mc, double x, double y) {
        Texture icon = UiIcons.get("player");
        double size = 14;
        if (icon != null) {
            drawPlayerBackplate(x, y, size + 5);
            Renderer2D r = Renderer2D.TEXTURE;
            r.begin();
            r.texQuad(x - size / 2.0, y - size / 2.0, size, size, -mc.player.getYRot(), 0, 0, 1, 1, Color.WHITE);
            r.render(icon.getTextureView(), icon.getSampler());
            return;
        }

        double yaw = Math.toRadians(mc.player.getYRot());
        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.triangle(
            x - Math.sin(yaw) * 8,
            y + Math.cos(yaw) * 8,
            x - Math.sin(yaw + 2.35) * 7,
            y + Math.cos(yaw + 2.35) * 7,
            x - Math.sin(yaw - 2.35) * 7,
            y + Math.cos(yaw - 2.35) * 7,
            new Color(8, 10, 14, 230)
        );
        r.triangle(
            x - Math.sin(yaw) * 7,
            y + Math.cos(yaw) * 7,
            x - Math.sin(yaw + 2.35) * 6,
            y + Math.cos(yaw + 2.35) * 6,
            x - Math.sin(yaw - 2.35) * 6,
            y + Math.cos(yaw - 2.35) * 6,
            new Color(240, 32, 36, 245)
        );
        r.render();
    }

    private void drawPlayerBackplate(double x, double y, double size) {
        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(x - size / 2.0 + 1.0, y - size / 2.0 + 2.0, size, size, new Color(0, 0, 0, 115));
        r.quad(x - size / 2.0, y - size / 2.0, size, size, new Color(10, 13, 18, 190));
        r.boxLines(x - size / 2.0, y - size / 2.0, size, size, new Color(250, 250, 250, 215));
        r.boxLines(x - size / 2.0 + 1.0, y - size / 2.0 + 1.0, size - 2.0, size - 2.0, new Color(235, 42, 45, 225));
        r.render();
    }

    private void drawCompass(int x, int y, int size) {
        TextRenderer text = TextRenderer.get();
        text.begin(0.85, false, true);
        text.render("N", x + size / 2.0 - 3, y + 4, Color.WHITE);
        text.render("S", x + size / 2.0 - 3, y + size - 13, new Color(210, 215, 220));
        text.render("W", x + 6, y + size / 2.0 - 5, new Color(210, 215, 220));
        text.render("E", x + size - 13, y + size / 2.0 - 5, new Color(210, 215, 220));
        text.end();
    }

    private void drawCoords(Minecraft mc, int x, int y) {
        String coords = String.format("X %.0f  Y %.0f  Z %.0f", mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(x, y - 1, 152, 13, new Color(7, 10, 14, 180));
        r.boxLines(x, y - 1, 152, 13, new Color(80, 155, 172, 135));
        r.render();

        TextRenderer text = TextRenderer.get();
        text.begin(0.85, false, true);
        text.render(coords, x + 4, y, new Color(230, 235, 238, 230));
        text.end();
    }
}
