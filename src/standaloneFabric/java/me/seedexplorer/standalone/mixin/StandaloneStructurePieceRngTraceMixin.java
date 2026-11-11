package me.seedexplorer.standalone.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootTable;
import me.seedexplorer.standalone.VillageRngTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Opt-in chest RNG trace paired with StandaloneVillageRngTraceMixin. */
@Mixin(StructurePiece.class)
public abstract class StandaloneStructurePieceRngTraceMixin {
    private static final ThreadLocal<BlockPos> CHEST_POS = new ThreadLocal<>();
    private static final ThreadLocal<Long> WORLD_SEED = new ThreadLocal<>();

    @Inject(method = "createChest(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/block/state/BlockState;)Z", at = @At("HEAD"))
    private void seedExplorer$chestHead(ServerLevelAccessor level, BoundingBox box, RandomSource random,
                                        BlockPos pos, ResourceKey<LootTable> table, BlockState state,
                                        CallbackInfoReturnable<Boolean> callback) {
        if (!Boolean.getBoolean("seedexplorer.villageRngTrace")) return;
        CHEST_POS.set(pos.immutable());
        WORLD_SEED.set(level.getLevel().getSeed());
    }

    @Redirect(method = "createChest(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/block/state/BlockState;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextLong()J"))
    private long seedExplorer$lootSeed(RandomSource random) {
        int before = random instanceof net.minecraft.world.level.levelgen.WorldgenRandom worldgen
            ? worldgen.getCount() : -1;
        long seed = random.nextLong();
        int after = random instanceof net.minecraft.world.level.levelgen.WorldgenRandom worldgen
            ? worldgen.getCount() : -1;
        BlockPos pos = CHEST_POS.get();
        Long worldSeed = WORLD_SEED.get();
        if (pos != null && worldSeed != null)
            VillageRngTrace.chest(pos, seed, before, after, worldSeed);
        return seed;
    }

    @Inject(method = "createChest(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/block/state/BlockState;)Z", at = @At("RETURN"))
    private void seedExplorer$chestReturn(ServerLevelAccessor level, BoundingBox box, RandomSource random,
                                          BlockPos pos, ResourceKey<LootTable> table, BlockState state,
                                          CallbackInfoReturnable<Boolean> callback) {
        CHEST_POS.remove();
        WORLD_SEED.remove();
    }
}
