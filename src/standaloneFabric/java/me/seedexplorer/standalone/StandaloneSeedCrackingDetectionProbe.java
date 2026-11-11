package me.seedexplorer.standalone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Recognition oracle is vanilla piece placement, independently of the detector. */
final class StandaloneSeedCrackingDetectionProbe {
    static void verify() {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var orientations=new HashSet<Direction>();
        int checked=0;
        for(int seed=0;seed<24;seed++) {
            int chunkX=seed%2==0 ? -41 : 29,chunkZ=seed%3==0 ? -77 : 13;
            Map<BlockPos,BlockState> blocks=new HashMap<>();
            var world=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},
                (proxy,method,args)-> {
                    return switch(method.getName()) {
                        case "getHeight" -> args!=null && args.length==3?75:384;
                        case "getMinY","getMinBuildHeight" -> -64;
                        case "getMaxY" -> 319;
                        case "getMaxBuildHeight" -> 320;
                        case "getHeightmapPos" -> new BlockPos(((BlockPos)args[1]).getX(),75,((BlockPos)args[1]).getZ());
                        case "getBlockState" -> state(blocks,(BlockPos)args[0]);
                        case "getFluidState" -> state(blocks,(BlockPos)args[0]).getFluidState();
                        case "setBlock" -> { blocks.put(((BlockPos)args[0]).immutable(),(BlockState)args[1]);yield true; }
                        case "getBlockEntity" -> null;
                        case "getRandom" -> RandomSource.create(42);
                        case "getSeed" -> 42L; // Independent fixture terrain, used only by vanilla cellar decoration.
                        case "ensureCanWrite","hasChunk","hasChunkAt" -> true;
                        case "isClientSide" -> false;
                        case "scheduleTick","blockUpdated","markPosForPostprocessing" -> null;
                        case "toString" -> "Independent pyramid recognition fixture";
                        default -> throw new AssertionError("Unhandled vanilla fixture call: "+method.getName());
                    };
                });
            var piece=new DesertPyramidPiece(RandomSource.create(seed*0x9E3779B97F4A7C15L),chunkX*16,chunkZ*16);
            orientations.add(piece.getOrientation());
            piece.postProcess(world,null,null,RandomSource.create(seed+91),
                new BoundingBox(chunkX*16-32,-64,chunkZ*16-32,chunkX*16+64,319,chunkZ*16+64),
                new ChunkPos(chunkX,chunkZ),BlockPos.ZERO);
            BlockPos plate=blocks.entrySet().stream().filter(e->e.getValue().is(Blocks.STONE_PRESSURE_PLATE))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
            StandaloneSeedCrackingDetector.BlocksAt reader=(x,y,z)->state(blocks,new BlockPos(x,y,z));
            var detected=StandaloneSeedCrackingDetector.pyramid(reader,plate.getX(),plate.getY(),plate.getZ());
            require(detected!=null && detected.chunkX()==chunkX && detected.chunkZ()==chunkZ,
                "Vanilla pyramid anchor mismatch for "+piece.getOrientation()+", seed "+seed);
            // A missing chest, unloaded TNT, incomplete decoration, or offset replica
            // cannot become a confirmed start merely because a few blocks resemble it.
            BlockPos chest=plate.offset(2,0,0);
            BlockState original=blocks.put(chest,Blocks.AIR.defaultBlockState());
            require(StandaloneSeedCrackingDetector.pyramid(reader,plate.getX(),plate.getY(),plate.getZ())==null,"Missing chest accepted");
            blocks.put(chest,original);
            require(StandaloneSeedCrackingDetector.pyramid((x,y,z)->y==plate.getY()-2?null:reader.get(x,y,z),
                plate.getX(),plate.getY(),plate.getZ())==null,"Unloaded TNT inferred from absence");
            require(StandaloneSeedCrackingDetector.pyramid((x,y,z)->reader.get(x-1,y,z),
                plate.getX()+1,plate.getY(),plate.getZ())==null,"Misaligned replica accepted as start chunk");
            blocks.put(plate.above(11),Blocks.SANDSTONE.defaultBlockState());
            require(StandaloneSeedCrackingDetector.pyramid(reader,plate.getX(),plate.getY(),plate.getZ())==null,"Partial chamber lookalike accepted");
            checked++;
        }
        require(orientations.size()==4,"Fixture did not cover every vanilla rotation");
        System.out.println("Automatic cracking recognition: PASS ("+checked+" vanilla pyramids, four rotations, negative coordinates, missing/unloaded/misaligned/lookalike negatives)");
    }
    private static BlockState state(Map<BlockPos,BlockState> blocks,BlockPos pos) {
        return blocks.getOrDefault(pos,pos.getY()<75?Blocks.SANDSTONE.defaultBlockState():Blocks.AIR.defaultBlockState());
    }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
