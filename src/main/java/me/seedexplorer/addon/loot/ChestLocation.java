package me.seedexplorer.addon.loot;

import net.minecraft.world.level.block.entity.BlockEntityType;

public record ChestLocation(int offsetX, int offsetY, int offsetZ, String lootTableId) {
    public int absoluteX(int structureX) { return structureX + offsetX; }
    public int absoluteZ(int structureZ) { return structureZ + offsetZ; }
}
