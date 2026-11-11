package me.seedexplorer.standalone.mixin;

import me.seedexplorer.standalone.VillageRngTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Templates assign loot seeds directly, without StructurePiece.createChest. */
@Mixin(StructureTemplate.class)
public abstract class StandaloneTemplateRngTraceMixin {
    @Redirect(method = "placeInWorld", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/entity/BlockEntity;loadWithComponents(Lnet/minecraft/world/level/storage/ValueInput;)V"))
    private void seedExplorer$containerLoaded(BlockEntity entity, ValueInput input,
                                              ServerLevelAccessor level, BlockPos origin, BlockPos pivot,
                                              StructurePlaceSettings settings, RandomSource random, int flags) {
        entity.loadWithComponents(input);
        if (!Boolean.getBoolean("seedexplorer.villageRngTrace")) return;
        if (entity instanceof RandomizableContainer container && container.getLootTable() != null) {
            int after = random instanceof WorldgenRandom worldgen ? worldgen.getCount() : -1;
            VillageRngTrace.append("phase=template-chest source="
                + (java.lang.reflect.Proxy.isProxyClass(level.getClass()) ? "offline" : "live")
                + " seed=" + level.getLevel().getSeed() + " pos=" + entity.getBlockPos().toShortString()
                + " count=" + (after < 0 ? -1 : after - 2) + "->" + after
                + " table=" + container.getLootTable().identifier()
                + " lootSeed=" + container.getLootTableSeed());
        }
    }
}
