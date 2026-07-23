package me.seedexplorer.addon.worldgen;

import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;

import java.util.ArrayList;
import java.util.List;

public final class TerrainPredictor {
    private TerrainPredictor() {
    }

    public static HeightmapData generateHeightmap(long seed, int regionBlockX, int regionBlockZ, int sizeBlocks) {
        int[] heights = new int[sizeBlocks * sizeBlocks];
        int[] surfaceBlocks = new int[sizeBlocks * sizeBlocks];

        for (int dz = 0; dz < sizeBlocks; dz++) {
            for (int dx = 0; dx < sizeBlocks; dx++) {
                int bx = regionBlockX + dx;
                int bz = regionBlockZ + dz;
                int idx = dz * sizeBlocks + dx;
                heights[idx] = WorldgenEngine.generationHeight(seed, 0,
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
                try {
                    var state = WorldgenEngine.baseBlock(seed, 0, bx, heights[idx] - 1, bz);
                    surfaceBlocks[idx] = state.getBlock().defaultBlockState().getBlock().hashCode();
                } catch (Exception e) {
                    surfaceBlocks[idx] = 0;
                }
            }
        }

        return new HeightmapData(regionBlockX, regionBlockZ, sizeBlocks, heights, surfaceBlocks);
    }

    public static record HeightmapData(int originX, int originZ, int size, int[] heights, int[] surfaceBlocks) {
        public int heightAt(int bx, int bz) {
            int dx = bx - originX;
            int dz = bz - originZ;
            if (dx < 0 || dx >= size || dz < 0 || dz >= size) return -1;
            return heights[dz * size + dx];
        }
    }
}
