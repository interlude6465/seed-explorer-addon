package me.seedexplorer.standalone;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Conservative recognition of intact vanilla geometry, not predicted positions. */
final class StandaloneSeedCrackingDetector {
    @FunctionalInterface interface BlocksAt { BlockState get(int x,int y,int z); }
    record Start(String set,int chunkX,int chunkZ) {}

    /**
     * 26.3 DesertPyramidPiece: the 21x21 box begins at the start chunk's minimum
     * block for every rotation. Local (10,-11,10) is the trap center; its four
     * chests, TNT, floor, walls and terracotta establish that anchor jointly.
     * Null/unloaded and modified samples fail closed. A player-built exact replica
     * remains indistinguishable from generated terrain on a vanilla client.
     */
    static Start pyramid(BlocksAt blocks,int x,int y,int z) {
        if(Math.floorMod(x-10,16)!=0 || Math.floorMod(z-10,16)!=0) return null;
        if(!is(blocks,x,y,z,Blocks.STONE_PRESSURE_PLATE)
            || !is(blocks,x,y+11,z,Blocks.DYED_TERRACOTTA.blue())) return null;
        for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            if(!is(blocks,x+direction[0]*2,y,z+direction[1]*2,Blocks.CHEST)) return null;
        }
        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
            if(!is(blocks,x+dx,y-2,z+dz,Blocks.TNT)) return null;
        }
        for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
            if(!is(blocks,x+dx,y-1,z+dz,Blocks.CUT_SANDSTONE)) return null;
        }
        for(int dx:new int[]{-2,2}) for(int dz:new int[]{-2,2}) {
            if(!is(blocks,x+dx,y+1,z+dz,Blocks.CHISELED_SANDSTONE)
                || !is(blocks,x+dx,y+2,z+dz,Blocks.CUT_SANDSTONE)
                || !is(blocks,x+dx,y+5,z+dz,Blocks.SANDSTONE)) return null;
        }
        return new Start("desert_pyramids",Math.floorDiv(x-10,16),Math.floorDiv(z-10,16));
    }
    private static boolean is(BlocksAt blocks,int x,int y,int z,Block expected) {
        BlockState state=blocks.get(x,y,z);return state!=null && state.is(expected);
    }
}
