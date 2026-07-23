package me.seedexplorer.addon.preview;

import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Lightweight block model captured from vanilla structure placement. */
public record StructurePreviewModel(List<PreviewBlock> blocks,
                                    int minX, int minY, int minZ,
                                    int maxX, int maxY, int maxZ,
                                    boolean truncated) {
    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public int centerX() {
        return (minX + maxX) / 2;
    }

    public int centerY() {
        return (minY + maxY) / 2;
    }

    public int centerZ() {
        return (minZ + maxZ) / 2;
    }

    public record PreviewBlock(int x, int y, int z, BlockState state) {
    }
}
