package me.seedexplorer.standalone.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static me.seedexplorer.standalone.StandaloneMineshaftTrace.*;

/** Opt-in comparison of mineshaft placement decisions in independent worlds. */
public final class StandaloneMineshaftTraceMixin {
    private StandaloneMineshaftTraceMixin() {}

    @Mixin(net.minecraft.world.level.levelgen.structure.StructureStart.class)
    public abstract static class Start {
        @Inject(method = "placeInChunk", at = @At("HEAD"))
        private void seedExplorer$start(WorldGenLevel level, StructureManager manager, ChunkGenerator generator,
                RandomSource random, BoundingBox area, ChunkPos chunk, CallbackInfo info) {
            if (!enabled()) return;
            var start = (net.minecraft.world.level.levelgen.structure.StructureStart) (Object) this;
            log("phase=start source=" + source(level) + " seed=" + level.getSeed() + " chunk=" + chunk
                + " start=" + start.getChunkPos() + " structure=" + start.getStructure().type()
                + " count=" + count(random));
        }
    }

    @Mixin(targets = "net.minecraft.world.level.levelgen.structure.structures.MineshaftPieces$MineShaftPiece")
    public abstract static class Piece {
        @Inject(method = "isInInvalidLocation", at = @At("RETURN"))
        private void seedExplorer$invalid(LevelAccessor level, BoundingBox area,
                                          CallbackInfoReturnable<Boolean> result) {
            if (!enabled()) return;
            var piece = (StructurePiece) (Object) this;
            var box = piece.getBoundingBox();
            int minX = Math.max(box.minX() - 1, area.minX()), maxX = Math.min(box.maxX() + 1, area.maxX());
            int minY = Math.max(box.minY() - 1, area.minY()), maxY = Math.min(box.maxY() + 1, area.maxY());
            int minZ = Math.max(box.minZ() - 1, area.minZ()), maxZ = Math.min(box.maxZ() + 1, area.maxZ());
            var liquids = new java.util.ArrayList<String>();
            for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
                if (x != minX && x != maxX && y != minY && y != maxY && z != minZ && z != maxZ) continue;
                BlockPos pos = new BlockPos(x, y, z);
                var state = level.getBlockState(pos);
                if (state.liquid() && liquids.size() < 6) liquids.add(pos.toShortString() + ":" + state);
            }
            log("phase=invalid source=" + source(level) + " area=" + area + " box=" + box
                + " rejected=" + result.getReturnValue() + " liquids=" + liquids);
        }
    }

    @Mixin(targets = "net.minecraft.world.level.levelgen.structure.structures.MineshaftPieces$MineShaftCorridor")
    public abstract static class Corridor extends StructurePiece {
        @Shadow @Final private boolean spiderCorridor;
        @Shadow private boolean hasPlacedSpider;

        protected Corridor(StructurePieceType type, int depth, BoundingBox box) { super(type, depth, box); }

        @Inject(method = "postProcess", at = @At("HEAD"))
        private void seedExplorer$before(WorldGenLevel level, StructureManager manager, ChunkGenerator generator,
                RandomSource random, BoundingBox area, ChunkPos chunk, BlockPos reference, CallbackInfo info) {
            if (enabled()) log("phase=before source=" + source(level) + " seed=" + level.getSeed()
                + " chunk=" + chunk + " box=" + getBoundingBox() + " count=" + count(random)
                + " spiderCorridor=" + spiderCorridor + " hasPlacedSpider=" + hasPlacedSpider);
        }

        @Inject(method = "postProcess", at = @At("RETURN"))
        private void seedExplorer$after(WorldGenLevel level, StructureManager manager, ChunkGenerator generator,
                RandomSource random, BoundingBox area, ChunkPos chunk, BlockPos reference, CallbackInfo info) {
            if (enabled()) log("phase=after source=" + source(level) + " seed=" + level.getSeed()
                + " chunk=" + chunk + " box=" + getBoundingBox() + " count=" + count(random)
                + " spiderCorridor=" + spiderCorridor + " hasPlacedSpider=" + hasPlacedSpider);
        }

        @Inject(method = "createChest", at = @At("HEAD"))
        private void seedExplorer$chestBefore(WorldGenLevel level, BoundingBox area, RandomSource random,
                int x, int y, int z, ResourceKey<LootTable> table, CallbackInfoReturnable<Boolean> result) {
            if (!enabled()) return;
            BlockPos pos = getWorldPos(x, y, z);
            log("phase=chest-before source=" + source(level) + " seed=" + level.getSeed()
                + " pos=" + pos.toShortString() + " count=" + count(random) + " inside=" + area.isInside(pos)
                + " state=" + level.getBlockState(pos) + " below=" + level.getBlockState(pos.below()));
        }

        @Inject(method = "createChest", at = @At("RETURN"))
        private void seedExplorer$chestAfter(WorldGenLevel level, BoundingBox area, RandomSource random,
                int x, int y, int z, ResourceKey<LootTable> table, CallbackInfoReturnable<Boolean> result) {
            if (enabled()) log("phase=chest-after source=" + source(level) + " seed=" + level.getSeed()
                + " pos=" + getWorldPos(x, y, z).toShortString() + " count=" + count(random)
                + " placed=" + result.getReturnValue());
        }
    }
}
