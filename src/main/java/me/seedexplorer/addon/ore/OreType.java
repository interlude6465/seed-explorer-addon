/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.ore;

import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Minecraft ore types supported by the ore predictor.
 */
public enum OreType {
    DIAMOND("Diamond", 0, -64, 16, new Color(0, 200, 255, 200), Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
    EMERALD("Emerald", 0, -16, 320, new Color(0, 220, 0, 200), Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE),
    COAL("Coal", 0, 0, 320, new Color(35, 35, 35, 220), Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
    IRON("Iron", 0, -64, 320, new Color(220, 165, 115, 220), Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
    COPPER("Copper", 0, -16, 112, new Color(230, 120, 70, 220), Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
    GOLD("Gold", 0, -64, 256, new Color(255, 200, 0, 220), Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE),
    LAPIS("Lapis", 0, -64, 64, new Color(40, 80, 230, 220), Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
    REDSTONE("Redstone", 0, -64, 16, new Color(230, 35, 35, 220), Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
    QUARTZ("Quartz", -1, 10, 117, new Color(235, 225, 200, 220), Blocks.NETHER_QUARTZ_ORE),
    NETHER_GOLD("Nether Gold", -1, 10, 117, new Color(255, 185, 35, 220), Blocks.NETHER_GOLD_ORE),
    ANCIENT_DEBRIS("Ancient Debris", -1, 8, 119, new Color(120, 60, 40, 220), Blocks.ANCIENT_DEBRIS);

    public final String displayName;
    public final int dimension;  // 0=Overworld, -1=Nether, 1=End
    public final int minY;
    public final int maxY;
    public final Color color;
    private final Set<Block> blocks;

    OreType(String displayName, int dimension, int minY, int maxY, Color color, Block... blocks) {
        this.displayName = displayName;
        this.dimension = dimension;
        this.minY = minY;
        this.maxY = maxY;
        this.color = color;
        this.blocks = Set.of(blocks);
    }

    public boolean matches(BlockState state) {
        return blocks.contains(state.getBlock());
    }

    public static OreType fromBlockState(BlockState state, int dimension) {
        for (OreType type : values()) {
            if (type.dimension == dimension && type.matches(state)) return type;
        }

        return null;
    }

    public int veinSize() {
        return switch (this) {
            case DIAMOND -> 4;
            case EMERALD -> 3;
            case COAL -> 12;
            case IRON -> 9;
            case COPPER -> 10;
            case GOLD -> 9;
            case LAPIS -> 7;
            case REDSTONE -> 8;
            case QUARTZ -> 14;
            case NETHER_GOLD -> 10;
            case ANCIENT_DEBRIS -> 3;
        };
    }

    public int attemptsPerChunk() {
        return switch (this) {
            case DIAMOND -> 10;
            case EMERALD -> 100;
            case COAL -> 50;
            case IRON -> 28;
            case COPPER -> 22;
            case GOLD -> 18;
            case LAPIS -> 6;
            case REDSTONE -> 12;
            case QUARTZ -> 16;
            case NETHER_GOLD -> 10;
            case ANCIENT_DEBRIS -> 2;
        };
    }
}
