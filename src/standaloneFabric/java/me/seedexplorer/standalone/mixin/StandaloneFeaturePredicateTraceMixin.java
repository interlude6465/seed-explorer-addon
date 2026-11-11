package me.seedexplorer.standalone.mixin;

import me.seedexplorer.standalone.VillageRngTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Traces the environment-dependent acceptance of village feature attempts. */
@Mixin(BlockPredicateFilter.class)
public abstract class StandaloneFeaturePredicateTraceMixin {
    @Inject(method = "shouldPlace", at = @At("RETURN"))
    private void seedExplorer$predicate(PlacementContext context, RandomSource random, BlockPos pos,
                                        CallbackInfoReturnable<Boolean> result) {
        String feature = VillageRngTrace.feature();
        if (feature == null) return;
        var level = context.getLevel();
        VillageRngTrace.append("phase=feature-predicate source="
            + (java.lang.reflect.Proxy.isProxyClass(level.getClass()) ? "offline" : "live")
            + " seed=" + level.getSeed() + ' ' + feature + " pos=" + pos.toShortString()
            + " count=" + (random instanceof WorldgenRandom worldgen ? worldgen.getCount() : -1)
            + " accepted=" + result.getReturnValue() + " state=" + level.getBlockState(pos)
            + " below=" + level.getBlockState(pos.below()));
    }
}
