package me.seedexplorer.standalone.mixin;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/** Opt-in live/offline 26.3 RNG trace for village fixtures across seeds. */
@Mixin(PoolElementStructurePiece.class)
public abstract class StandaloneVillageRngTraceMixin {
    @Shadow protected StructurePoolElement element;

    @Inject(method = "postProcess", at = @At("HEAD"))
    private void seedExplorer$before(WorldGenLevel level, StructureManager manager,
                                     ChunkGenerator generator, RandomSource random,
                                     BoundingBox box, ChunkPos chunk, BlockPos reference,
                                     CallbackInfo info) {
        if (Boolean.getBoolean("seedexplorer.villageRngTrace")
            && element instanceof net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement) {
            me.seedexplorer.standalone.VillageRngTrace.beginFeature("chunk=" + chunk.x() + ',' + chunk.z()
                + " origin=" + ((PoolElementStructurePiece) (Object) this).getPosition().toShortString());
        }
        trace("feature-before", level, random, chunk, element,
            ((PoolElementStructurePiece) (Object) this).getPosition());
    }

    @Inject(method = "postProcess", at = @At("RETURN"))
    private void seedExplorer$after(WorldGenLevel level, StructureManager manager,
                                    ChunkGenerator generator, RandomSource random,
                                    BoundingBox box, ChunkPos chunk, BlockPos reference,
                                    CallbackInfo info) {
        trace("feature-after", level, random, chunk, element,
            ((PoolElementStructurePiece) (Object) this).getPosition());
        me.seedexplorer.standalone.VillageRngTrace.endFeature();
    }

    private static void trace(String phase, WorldGenLevel level, RandomSource random, ChunkPos chunk,
                              StructurePoolElement element, BlockPos position) {
        if (!Boolean.getBoolean("seedexplorer.villageRngTrace")) return;
        String text = String.valueOf(element);
        String terrain = element instanceof net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement
            ? " origin=" + position.toShortString() + " at=" + level.getBlockState(position)
                + " below=" + level.getBlockState(position.below()) : "";
        int count = random instanceof WorldgenRandom worldgen ? worldgen.getCount() : -1;
        if (random instanceof WorldgenRandom worldgen) {
            me.seedexplorer.standalone.VillageRngTrace.append("phase=" + phase
                + " source=" + (java.lang.reflect.Proxy.isProxyClass(level.getClass()) ? "offline" : "live")
                + " seed=" + level.getSeed()
                + " chunk=" + chunk.x() + ',' + chunk.z()
                + " count=" + worldgen.getCount() + terrain + " element=" + text);
            return;
        }
        me.seedexplorer.standalone.VillageRngTrace.append("phase=" + phase + " chunk=" + chunk.x() + ',' + chunk.z()
            + " count=" + count + " element=" + text);
    }

}
