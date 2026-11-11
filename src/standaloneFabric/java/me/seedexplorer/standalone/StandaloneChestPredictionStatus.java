package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.ChestLootOutput;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Presentation of location observations separately from predicted contents. */
final class StandaloneChestPredictionStatus {
    private StandaloneChestPredictionStatus() {}

    static boolean locationObserved(ChestLootOutput chest, int dimension) {
        var level = Minecraft.getInstance().level;
        if (level == null) return false;
        int currentDimension = level.dimension() == Level.NETHER ? -1
            : level.dimension() == Level.END ? 1 : 0;
        if (dimension != currentDimension) return false;
        var position = new BlockPos(chest.blockX(), chest.blockY(), chest.blockZ());
        if (!StandaloneClientChunks.loaded(level, position)) return false;
        var entity = level.getBlockEntity(position);
        if (entity instanceof net.minecraft.world.Container
            || entity instanceof net.minecraft.world.level.block.entity.BrushableBlockEntity) return true;
        for (var candidate : level.entitiesForRendering()) {
            if (candidate instanceof net.minecraft.world.entity.vehicle.ContainerEntity
                && candidate.blockPosition().equals(position)) return true;
        }
        return false;
    }

    static String lootLabel(ChestLootOutput chest) {
        if (chest.lootTableId().isBlank()) return "Loot unavailable";
        return chest.exact() ? "Validated loot" : "Predicted loot";
    }
}
