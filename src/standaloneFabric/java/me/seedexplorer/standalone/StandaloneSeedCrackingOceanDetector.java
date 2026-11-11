package me.seedexplorer.standalone;

import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Observed shipwreck recognition. Uses shipped templates, never a world seed. */
final class StandaloneSeedCrackingOceanDetector {
    static final int MAX_BLOCK_READS=8192;
    static final BlockPos PIVOT=new BlockPos(4,0,15);
    static final List<String> TEMPLATES=List.of(
        "with_mast","upsidedown_full","upsidedown_fronthalf","upsidedown_backhalf",
        "sideways_full","sideways_fronthalf","sideways_backhalf",
        "rightsideup_full","rightsideup_fronthalf","rightsideup_backhalf",
        "with_mast_degraded","upsidedown_full_degraded","upsidedown_fronthalf_degraded","upsidedown_backhalf_degraded",
        "sideways_full_degraded","sideways_fronthalf_degraded","sideways_backhalf_degraded",
        "rightsideup_full_degraded","rightsideup_fronthalf_degraded","rightsideup_backhalf_degraded");
    enum Status { MATCH, NO_MATCH, AMBIGUOUS, INCOMPLETE, BUDGET_EXHAUSTED, LOADING, UNAVAILABLE }
    record Start(String set,int chunkX,int chunkZ,BoundingBox bounds,List<String> templates) {}
    record Recognition(Status status,Start start,int blockReads,String detail) {}
    private record Cell(BlockPos offset,Block block) {}
    private record Pattern(String template,List<Cell> cells,List<BlockPos> chests,BoundingBox bounds) {}
    private record Anchor(int chunkX,int chunkZ) {}
    private static volatile CompletableFuture<List<Pattern>> catalog;

    /** Starts immutable template loading off the client thread. Safe to call repeatedly. */
    static synchronized CompletableFuture<?> prepare() { return patterns(); }
    private static synchronized CompletableFuture<List<Pattern>> patterns() {
        if(catalog==null) catalog=CompletableFuture.supplyAsync(StandaloneSeedCrackingOceanDetector::loadPatterns,task->{
            Thread thread=new Thread(task,"Seed Explorer shipwreck recognition templates");
            thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);thread.start();
        });
        return catalog;
    }

    /**
     * Call on the client thread with actual loaded data; null means unknown.
     * Each chest proposes template origins. Vanilla's origin X/Z equals the
     * start chunk minimum; rotations use (4,0,15), not the template center.
     * Every placed non-air/non-marker block must be present. Comparing block
     * identities intentionally tolerates waterlogging and neighbor-derived
     * fence/stair states. Missing geometry never becomes negative evidence.
     */
    static Recognition recognize(StandaloneSeedCrackingDetector.BlocksAt blocks,BlockPos chest) {
        var future=patterns();
        if(!future.isDone()) return result(Status.LOADING,0,"Loading vanilla shipwreck templates.");
        List<Pattern> patterns;
        try { patterns=future.join(); }
        catch(RuntimeException failure) { return result(Status.UNAVAILABLE,0,"Vanilla shipwreck templates are unavailable: "+failure.getCause()); }
        Map<BlockPos,BlockState> cache=new HashMap<>();
        Set<BlockPos> read=new HashSet<>();
        Map<Anchor,Start> matches=new HashMap<>();
        Set<Anchor> incomplete=new HashSet<>();
        for(Pattern pattern:patterns) for(BlockPos relativeChest:pattern.chests) {
            BlockPos origin=chest.subtract(relativeChest);
            if(Math.floorMod(origin.getX(),16)!=0 || Math.floorMod(origin.getZ(),16)!=0) continue;
            Anchor anchor=new Anchor(origin.getX()>>4,origin.getZ()>>4);
            boolean match=true,unknown=false;
            for(Cell cell:pattern.cells) {
                BlockPos position=origin.offset(cell.offset);
                if(!read.contains(position)) {
                    if(read.size()>=MAX_BLOCK_READS) return result(Status.BUDGET_EXHAUSTED,read.size(),"Recognition read budget reached; no constraint recorded.");
                    read.add(position);cache.put(position,blocks.get(position.getX(),position.getY(),position.getZ()));
                }
                BlockState actual=cache.get(position);
                if(actual==null) { unknown=true;continue; }
                if(!actual.is(cell.block)) { match=false;break; }
            }
            if(!match) continue;
            if(unknown) { incomplete.add(anchor);continue; }
            BoundingBox box=pattern.bounds.moved(origin.getX(),origin.getY(),origin.getZ());
            Start previous=matches.get(anchor);
            if(previous==null) matches.put(anchor,new Start("shipwrecks",anchor.chunkX,anchor.chunkZ,box,List.of(pattern.template)));
            else {
                var names=new ArrayList<>(previous.templates);
                if(!names.contains(pattern.template))names.add(pattern.template);
                matches.put(anchor,new Start("shipwrecks",anchor.chunkX,anchor.chunkZ,union(previous.bounds,box),List.copyOf(names)));
            }
        }
        if(matches.size()>1) return result(Status.AMBIGUOUS,read.size(),"Loaded geometry supports conflicting start chunks.");
        if(matches.size()==1) {
            var entry=matches.entrySet().iterator().next();
            if(incomplete.stream().anyMatch(anchor->!anchor.equals(entry.getKey())))
                return result(Status.INCOMPLETE,read.size(),"Another possible anchor has unloaded geometry.");
            return new Recognition(Status.MATCH,entry.getValue(),read.size(),"Complete loaded shipwreck geometry; start anchor agrees.");
        }
        return result(incomplete.isEmpty()?Status.NO_MATCH:Status.INCOMPLETE,read.size(),
            incomplete.isEmpty()?"No complete vanilla shipwreck matched.":"Potential shipwreck geometry is not fully loaded.");
    }
    private static Recognition result(Status status,int reads,String detail) { return new Recognition(status,null,reads,detail); }
    private static BoundingBox union(BoundingBox a,BoundingBox b) {
        return new BoundingBox(Math.min(a.minX(),b.minX()),Math.min(a.minY(),b.minY()),Math.min(a.minZ(),b.minZ()),
            Math.max(a.maxX(),b.maxX()),Math.max(a.maxY(),b.maxY()),Math.max(a.maxZ(),b.maxZ()));
    }
    @SuppressWarnings("unchecked")
    private static List<Pattern> loadPatterns() {
        try {
            var palettesField=StructureTemplate.class.getDeclaredField("palettes");palettesField.setAccessible(true);
            var manager=WorldgenEngine.structureTemplateManager();
            var patterns=new ArrayList<Pattern>();
            for(String name:TEMPLATES) {
                StructureTemplate template=manager.get(Identifier.withDefaultNamespace("shipwreck/"+name))
                    .orElseThrow(()->new IllegalStateException("Missing shipwreck template: "+name));
                List<StructureTemplate.Palette> palettes=(List<StructureTemplate.Palette>)palettesField.get(template);
                if(palettes.isEmpty())throw new IllegalStateException("Empty shipwreck template: "+name);
                for(var palette:palettes) for(Rotation rotation:Rotation.values()) {
                    List<Cell> cells=new ArrayList<>();List<BlockPos> chests=new ArrayList<>();
                    Map<Block,Integer> frequency=new HashMap<>();
                    for(var info:palette.blocks()) {
                        BlockState state=info.state();
                        // Structure markers are data-marker control blocks and are
                        // removed by vanilla's template processor; air is likewise
                        // intentionally ignored by STRUCTURE_AND_AIR. Requiring either
                        // would create false negatives on intact client chunks.
                        if(state.isAir() || state.is(Blocks.STRUCTURE_BLOCK) || state.is(Blocks.STRUCTURE_VOID))continue;
                        BlockPos relative=StructureTemplate.transform(info.pos(),Mirror.NONE,rotation,PIVOT);
                        cells.add(new Cell(relative,state.getBlock()));frequency.merge(state.getBlock(),1,Integer::sum);
                        if(state.is(Blocks.CHEST))chests.add(relative);
                    }
                    if(cells.size()<40 || chests.isEmpty())throw new IllegalStateException("Incomplete shipwreck signature: "+name);
                    // Rare blocks reject wrong palettes/variants before a full hull read.
                    cells.sort(Comparator.comparingInt(cell->frequency.get(cell.block)));
                    var settings=new StructurePlaceSettings().setRotation(rotation).setRotationPivot(PIVOT);
                    patterns.add(new Pattern(name,List.copyOf(cells),List.copyOf(chests),template.getBoundingBox(settings,BlockPos.ZERO)));
                }
            }
            if(patterns.isEmpty())throw new IllegalStateException("No shipwreck recognition signatures loaded.");
            return List.copyOf(patterns);
        } catch(ReflectiveOperationException failure) { throw new IllegalStateException("Cannot read 26.3 shipwreck palettes",failure); }
    }
}
