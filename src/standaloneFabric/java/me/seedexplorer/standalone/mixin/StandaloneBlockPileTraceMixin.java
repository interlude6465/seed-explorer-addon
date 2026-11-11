package me.seedexplorer.standalone.mixin;

import me.seedexplorer.standalone.VillageRngTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.feature.BlockPileFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opt-in block-pile attempt inputs; never draws from the placement RNG. */
@Mixin(BlockPileFeature.class)
public abstract class StandaloneBlockPileTraceMixin {
    @Inject(method = "tryPlaceBlock", at = @At("HEAD"))
    private void seedExplorer$attempt(WorldGenLevel level, BlockPos pos, RandomSource random,
                                      CallbackInfo info) {
        String feature = VillageRngTrace.feature();
        if (feature == null) return;
        VillageRngTrace.append("phase=pile-attempt source="
            + (java.lang.reflect.Proxy.isProxyClass(level.getClass()) ? "offline" : "live")
            + " seed=" + level.getSeed() + ' ' + feature + " pos=" + pos.toShortString()
            + " count=" + (random instanceof WorldgenRandom worldgen ? worldgen.getCount() : -1)
            + " state=" + level.getBlockState(pos)
            + " below=" + level.getBlockState(pos.below()));
    }
}
