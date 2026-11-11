package me.seedexplorer.standalone;

import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.structures.ShipwreckPieces;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detector oracle is vanilla ShipwreckPiece placement, not detector-generated blocks. */
public final class StandaloneSeedCrackingOceanProbe {
    public static void main(String[] args) throws Exception {
        if(args.length==0) { verify();return; }
        if(args.length<3 || args.length%2!=1)throw new IllegalArgumentException("Expected region-directory followed by start-chunk X Z pairs");
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        StandaloneSeedCrackingOceanDetector.prepare().join();
        for(int i=1;i<args.length;i+=2)verifySaved(java.nio.file.Path.of(args[0]),Integer.parseInt(args[i]),Integer.parseInt(args[i+1]));
    }
    @SuppressWarnings("unchecked")
    static void verify() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var namesField=ShipwreckPieces.class.getDeclaredField("STRUCTURE_LOCATION_OCEAN");namesField.setAccessible(true);
        var names=(Identifier[])namesField.get(null);
        Set<String> vanillaNames=new HashSet<>();
        for(var name:names) vanillaNames.add(name.getPath().substring("shipwreck/".length()));
        require(vanillaNames.equals(Set.copyOf(StandaloneSeedCrackingOceanDetector.TEMPLATES)),"Shipwreck catalogue differs from vanilla 26.3");
        StandaloneSeedCrackingOceanDetector.prepare().join();
        var palettesField=StructureTemplate.class.getDeclaredField("palettes");palettesField.setAccessible(true);
        int scenarios=0,matched=0,ambiguous=0,maxReads=0,negatives=0,chestChecks=0;
        var manager=WorldgenEngine.structureTemplateManager();
        for(Identifier name:names) {
            var template=manager.get(name).orElseThrow();
            int palettes=((List<?>)palettesField.get(template)).size();
            for(int palette=0;palette<palettes;palette++) for(Rotation rotation:Rotation.values()) for(int situation=0;situation<4;situation++) {
                int chunkX=situation<2?-13:17,chunkZ=situation<2?-29:31;
                int y=situation%2==0?29:68;
                var fixture=new Fixture(y<63);
                var piece=new ShipwreckPieces.ShipwreckPiece(manager,name,new BlockPos(chunkX*16,90,chunkZ*16),rotation,y>=63);
                final int selected=palette;
                piece.placeSettings().setRandom(new LegacyRandomSource(42) {
                    @Override public int nextInt(int bound) { return selected%bound; }
                });
                piece.adjustPositionHeight(y);
                piece.postProcess(fixture.world(),null,null,RandomSource.create(71),
                    new BoundingBox(chunkX*16-64,-64,chunkZ*16-64,chunkX*16+80,319,chunkZ*16+80),
                    new ChunkPos(chunkX,chunkZ),BlockPos.ZERO);
                var chests=fixture.blocks.entrySet().stream().filter(e->e.getValue().is(Blocks.CHEST)).map(Map.Entry::getKey).sorted(
                    java.util.Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).toList();
                require(!chests.isEmpty(),"Vanilla piece generated no chest: "+name);
                var result=StandaloneSeedCrackingOceanDetector.recognize(fixture::get,chests.getFirst());
                maxReads=Math.max(maxReads,result.blockReads());scenarios++;
                if(result.status()==StandaloneSeedCrackingOceanDetector.Status.MATCH) {
                    matched++;
                    require(result.start().chunkX()==chunkX && result.start().chunkZ()==chunkZ,
                        "Wrong shipwreck start for "+name+" / "+rotation+": "+result);
                    for(BlockPos chest:chests)require(result.start().bounds().isInside(chest),"Recognition box omitted a vanilla chest");
                } else if(result.status()==StandaloneSeedCrackingOceanDetector.Status.AMBIGUOUS) ambiguous++;
                else throw new AssertionError("Intact vanilla shipwreck was not recognized: "+name+" palette="+palette+" rotation="+rotation+" "+result);
                require(result.blockReads()<=StandaloneSeedCrackingOceanDetector.MAX_BLOCK_READS,"Read budget exceeded");
                for(BlockPos chest:chests) {
                    var each=StandaloneSeedCrackingOceanDetector.recognize(fixture::get,chest);chestChecks++;
                    require(each.status()==StandaloneSeedCrackingOceanDetector.Status.MATCH
                        && each.start().chunkX()==chunkX && each.start().chunkZ()==chunkZ,
                        "Another chest lost the unique anchor: "+name+" / "+rotation+" / "+chest+" "+each);
                    maxReads=Math.max(maxReads,each.blockReads());
                }
                BlockPos observed=chests.getFirst();
                // No loaded hull: a floating chest never becomes a confirmed structure.
                var chestOnly=StandaloneSeedCrackingOceanDetector.recognize((x,by,z)->
                    new BlockPos(x,by,z).equals(observed)?Blocks.CHEST.defaultBlockState():Blocks.WATER.defaultBlockState(),observed);
                require(chestOnly.status()!=StandaloneSeedCrackingOceanDetector.Status.MATCH,"Floating chest accepted");negatives++;
                // A translated exact replica loses the generation chunk anchor.
                var shifted=StandaloneSeedCrackingOceanDetector.recognize((x,by,z)->fixture.get(x-1,by,z),observed.offset(1,0,0));
                require(shifted.status()!=StandaloneSeedCrackingOceanDetector.Status.MATCH,"Misaligned replica accepted: "+name);negatives++;
                // An unloaded neighboring chunk must remain unknown, never a negative match.
                int unloadedX=piece.getBoundingBox().minX()>>4;
                var partial=StandaloneSeedCrackingOceanDetector.recognize((x,by,z)->(x>>4)==unloadedX?null:fixture.get(x,by,z),observed);
                require(partial.status()!=StandaloneSeedCrackingOceanDetector.Status.MATCH,"Partially unloaded hull accepted: "+name);negatives++;
                // Remove all other chests and the complete wooden hull, preserving only
                // a few planks around the observed chest as a realistic weak lookalike.
                var lookalike=StandaloneSeedCrackingOceanDetector.recognize((x,by,z)->
                    Math.abs(x-observed.getX())<=1 && Math.abs(by-observed.getY())<=1 && Math.abs(z-observed.getZ())<=1
                        ?fixture.get(x,by,z):Blocks.AIR.defaultBlockState(),observed);
                require(lookalike.status()!=StandaloneSeedCrackingOceanDetector.Status.MATCH,"Partial hull lookalike accepted");negatives++;
            }
        }
        require(matched>0,"No uniquely anchored shipwrecks were supported");
        System.out.println("Automatic shipwreck recognition: PASS ("+scenarios+" vanilla template/palette/rotation fixtures, "+matched+
            " unique anchors, "+ambiguous+" preserved ambiguities, "+chestChecks+" individual chests, "+negatives+
            " negative checks; all rotations at positive/negative and underwater/beached origins; max "+maxReads+" block reads)");
    }
    private static final class Fixture {
        final Map<BlockPos,BlockState> blocks=new HashMap<>();
        final boolean ocean;
        Fixture(boolean ocean) { this.ocean=ocean; }
        BlockState get(int x,int y,int z) { return blocks.getOrDefault(new BlockPos(x,y,z),
            ocean && y<63?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState()); }
        WorldGenLevel world() {
            return (WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},
                (proxy,method,args)->switch(method.getName()) {
                    case "getBlockState" -> {var p=(BlockPos)args[0];yield get(p.getX(),p.getY(),p.getZ());}
                    case "getFluidState" -> {var p=(BlockPos)args[0];yield get(p.getX(),p.getY(),p.getZ()).getFluidState();}
                    case "setBlock","setBlockAndUpdate" -> {blocks.put(((BlockPos)args[0]).immutable(),(BlockState)args[1]);yield true;}
                    case "getBlockEntity" -> null;
                    case "isClientSide" -> false;
                    case "scheduleTick","updateNeighborsAt","blockUpdated","markPosForPostprocessing" -> null;
                    case "getRandom" -> RandomSource.create(42);
                    case "getSeed" -> 42L;
                    case "getMinY","getMinBuildHeight" -> -64;
                    case "getMaxY" -> 319;
                    case "getMaxBuildHeight" -> 320;
                    case "enabledFeatures" -> net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;
                    case "toString" -> "Independent vanilla shipwreck placement fixture";
                    default -> throw new AssertionError("Unhandled vanilla shipwreck fixture call: "+method.getName());
                });
        }
    }
    /** Actual saved chunks are independent evidence; structure NBT is assertion-only. */
    private static void verifySaved(java.nio.file.Path directory,int chunkX,int chunkZ) throws Exception {
        var source=new SavedBlocks(directory);
        var root=source.chunk(chunkX,chunkZ);
        require(root!=null,"Missing independent saved start chunk "+chunkX+","+chunkZ);
        var starts=root.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts");
        var start=starts.getCompoundOrEmpty("minecraft:shipwreck");
        if(start.getListOrEmpty("Children").isEmpty())start=starts.getCompoundOrEmpty("minecraft:shipwreck_beached");
        var children=start.getListOrEmpty("Children");
        require(children.size()==1 && children.getFirst() instanceof net.minecraft.nbt.CompoundTag,"Expected one independently saved shipwreck piece");
        var piece=(net.minecraft.nbt.CompoundTag)children.getFirst();
        int[] bounds=piece.getIntArray("BB").orElseThrow();
        BoundingBox box=new BoundingBox(bounds[0],bounds[1],bounds[2],bounds[3],bounds[4],bounds[5]);
        var chests=new ArrayList<BlockPos>();
        for(int x=(box.minX()>>4);x<=(box.maxX()>>4);x++)for(int z=(box.minZ()>>4);z<=(box.maxZ()>>4);z++) {
            var data=source.chunk(x,z);if(data==null)continue;
            for(var entry:data.getListOrEmpty("block_entities")) {
                if(!(entry instanceof net.minecraft.nbt.CompoundTag entity) || !entity.getStringOr("id","").equals("minecraft:chest"))continue;
                var position=new BlockPos(entity.getIntOr("x",0),entity.getIntOr("y",0),entity.getIntOr("z",0));
                if(position.getX()>=box.minX() && position.getX()<=box.maxX() && position.getZ()>=box.minZ() && position.getZ()<=box.maxZ())chests.add(position);
            }
        }
        require(!chests.isEmpty(),"No saved shipwreck chests in footprint");
        int accepted=0;
        for(var chest:chests) {
            var result=StandaloneSeedCrackingOceanDetector.recognize(source::get,chest);
            if(result.status()==StandaloneSeedCrackingOceanDetector.Status.MATCH) {
                require(result.start().chunkX()==chunkX && result.start().chunkZ()==chunkZ,"Wrong start from independent saved world: "+result);
                accepted++;
            }
            System.out.println("SAVED_SHIPWRECK_CHEST start="+chunkX+","+chunkZ+" chest="+chest+" status="+result.status()+
                " reads="+result.blockReads()+" templates="+(result.start()==null?List.of():result.start().templates()));
        }
        source.verifyUnchanged();
        System.out.println("SAVED_SHIPWRECK_RESULT start="+chunkX+","+chunkZ+" template="+piece.getStringOr("Template","")+
            " accepted="+accepted+" total="+chests.size()+" incorrect=0");
    }
    private static final class SavedBlocks {
        private final java.nio.file.Path directory;
        private final Map<Long,net.minecraft.nbt.CompoundTag> chunks=new HashMap<>();
        private final Map<Long,Map<Integer,BlockState[]>> sections=new HashMap<>();
        private final Map<java.nio.file.Path,String> hashes=new HashMap<>();
        SavedBlocks(java.nio.file.Path directory) { this.directory=directory; }
        net.minecraft.nbt.CompoundTag chunk(int x,int z) throws Exception {
            long key=((long)x<<32) | (z&0xffffffffL);if(chunks.containsKey(key))return chunks.get(key);
            var path=directory.resolve("r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)+".mca");
            if(!java.nio.file.Files.isRegularFile(path)) {chunks.put(key,null);return null;}
            if(!hashes.containsKey(path))hashes.put(path,hash(path));
            try(var file=new java.io.RandomAccessFile(path.toFile(),"r")) {
                file.seek((Math.floorMod(x,32)+Math.floorMod(z,32)*32L)*4);int location=file.readInt();
                if(location==0) {chunks.put(key,null);return null;}
                file.seek((location>>>8)*4096L);int length=file.readInt(),compression=file.readUnsignedByte();
                require(length>=1 && length<=(location&255)*4096-4,"Invalid region length");
                byte[] bytes=new byte[length-1];file.readFully(bytes);
                var raw=new java.io.ByteArrayInputStream(bytes);
                try(var input=new java.io.DataInputStream(switch(compression) {
                    case 1 -> new java.util.zip.GZIPInputStream(raw);
                    case 2 -> new java.util.zip.InflaterInputStream(raw);
                    case 3 -> raw;
                    default -> throw new IllegalArgumentException("Unsupported compression "+compression);
                })) {
                    var root=net.minecraft.nbt.NbtIo.read(input,net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                    require(root.getIntOr("xPos",Integer.MIN_VALUE)==x && root.getIntOr("zPos",Integer.MIN_VALUE)==z,"Wrong saved chunk coordinates");
                    chunks.put(key,root);return root;
                }
            }
        }
        BlockState get(int x,int y,int z) {
            try {
                int cx=x>>4,cz=z>>4;long key=((long)cx<<32) | (cz&0xffffffffL);
                var root=chunk(cx,cz);if(root==null || !root.getStringOr("Status","").equals("minecraft:full"))return null;
                if(!sections.containsKey(key)) {
                    Map<Integer,BlockState[]> decoded=new HashMap<>();
                    for(var value:root.getListOrEmpty("sections")) {
                        var section=(net.minecraft.nbt.CompoundTag)value;var states=section.getCompoundOrEmpty("block_states");
                        List<BlockState> palette=new ArrayList<>();
                        for(var entry:states.getListOrEmpty("palette"))palette.add(BlockState.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE,entry).getOrThrow());
                        if(palette.isEmpty())continue;
                        long[] data=states.getLongArray("data").orElse(new long[0]);
                        int bits=Math.max(4,32-Integer.numberOfLeadingZeros(palette.size()-1)),perLong=64/bits;
                        BlockState[] blockStates=new BlockState[4096];long mask=(1L<<bits)-1;
                        for(int i=0;i<4096;i++)blockStates[i]=palette.get(palette.size()==1?0:(int)(data[i/perLong]>>>(i%perLong*bits)&mask));
                        decoded.put(section.getIntOr("Y",0),blockStates);
                    }
                    sections.put(key,decoded);
                }
                var states=sections.get(key).get(y>>4);return states==null?Blocks.AIR.defaultBlockState():states[(y&15)*256+(z&15)*16+(x&15)];
            } catch(Exception failure) {throw new IllegalStateException("Saved block read failed",failure);}
        }
        void verifyUnchanged() throws Exception {
            for(var entry:hashes.entrySet()) {
                require(entry.getValue().equals(hash(entry.getKey())),"Saved region changed while testing");
                System.out.println("SAVED_REGION_UNCHANGED file="+entry.getKey()+" sha256="+entry.getValue());
            }
        }
        private static String hash(java.nio.file.Path path) throws Exception {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(path)));
        }
    }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
