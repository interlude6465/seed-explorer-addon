package me.seedexplorer.standalone.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static me.seedexplorer.standalone.StandaloneCampPlacementTrace.*;

/** Temporary, opt-in mixin hooks for the seed-1 camp phantom barrel. */
public final class StandaloneCampPlacementTraceMixin {

    @Mixin(StructureStart.class)
    public static abstract class Start {
        @Shadow public abstract java.util.List<StructurePiece> getPieces();

        @Inject(method = "placeInChunk", at = @At("HEAD"))
        private void seedExplorer$start(net.minecraft.world.level.WorldGenLevel level,
                                        net.minecraft.world.level.StructureManager manager,
                                        net.minecraft.world.level.chunk.ChunkGenerator generator,
                                        net.minecraft.util.RandomSource random,
                                        BoundingBox box, ChunkPos chunk, CallbackInfo callback) {
            StructureStart self = (StructureStart) (Object) this;
            if (ownsTarget(self)) {
                var idValue = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                    .getKey(self.getStructure());
                String id = idValue == null ? "<unregistered>" : idValue.toString();
                log("START head id=" + id + " start=" + self.getChunkPos() + " chunk=" + chunk
                    + " box=" + box + " pieces=" + getPieces().size()
                    + " targetState=" + level.getBlockState(new BlockPos(TARGET_X, TARGET_Y, TARGET_Z)));
            }
        }

        @Inject(method = "placeInChunk", at = @At("TAIL"))
        private void seedExplorer$finish(net.minecraft.world.level.WorldGenLevel level,
                                         net.minecraft.world.level.StructureManager manager,
                                         net.minecraft.world.level.chunk.ChunkGenerator generator,
                                         net.minecraft.util.RandomSource random,
                                         BoundingBox box, ChunkPos chunk, CallbackInfo callback) {
            StructureStart self = (StructureStart) (Object) this;
            if (ownsTarget(self)) {
                var idValue = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                    .getKey(self.getStructure());
                String id = idValue == null ? "<unregistered>" : idValue.toString();
                log("START tail id=" + id + " start=" + self.getChunkPos() + " chunk=" + chunk
                    + " targetState="
                    + level.getBlockState(new BlockPos(TARGET_X, TARGET_Y, TARGET_Z)));
            }
        }
    }

    @Mixin(WorldGenRegion.class)
    public static abstract class Region {
        @Shadow public abstract BlockState getBlockState(BlockPos pos);

        @Inject(method = "setBlock", at = @At("HEAD"))
        private void seedExplorer$write(BlockPos pos, BlockState state, int flags, int recursion,
                                        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> callback) {
            if (pos.getX() != TARGET_X || pos.getY() != TARGET_Y || pos.getZ() != TARGET_Z) return;
            StringBuilder stack = new StringBuilder();
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                String owner = frame.getClassName();
                if (owner.contains("Structure") || owner.contains("Template")
                    || owner.contains("PoolElement") || owner.contains("Feature")) {
                    if (!stack.isEmpty()) stack.append(" <- ");
                    stack.append(owner).append('#').append(frame.getMethodName());
                }
            }
            log("WRITE pos=" + pos + " old=" + getBlockState(pos) + " new=" + state
                + " flags=" + flags + " recursion=" + recursion + " stack=" + stack);
        }
    }

    @Mixin(net.minecraft.world.level.StructureManager.class)
    public static abstract class Manager {
        @Shadow public abstract net.minecraft.core.RegistryAccess registryAccess();

        @Inject(method = "startsForStructure(IILnet/minecraft/world/level/levelgen/structure/Structure;)Ljava/util/List;",
                at = @At("RETURN"))
        private void seedExplorer$starts(int chunkX, int chunkZ, Structure structure,
                org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<java.util.List<StructureStart>> callback) {
            if (chunkX != -701 || chunkZ != 651) return;
            java.util.List<StructureStart> starts = callback.getReturnValue();
            StructureStart target = starts.stream()
                .filter(me.seedexplorer.standalone.StandaloneCampPlacementTrace::ownsTarget)
                .findFirst().orElse(null);
            var idValue = registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(structure);
            String id = idValue == null ? "<unregistered>" : idValue.toString();
            if (target != null || id.contains("abandoned_camp")) {
                log("LOOKUP id=" + id + " chunk=" + chunkX + ',' + chunkZ
                    + " count=" + starts.size()
                    + " starts=" + starts.stream().map(start -> start.getChunkPos().toString()).toList()
                + " targetStart=" + (target == null ? "none" : target.getChunkPos().toString()));
            }
        }
    }
}
