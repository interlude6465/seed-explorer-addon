package me.seedexplorer.standalone;

import me.seedexplorer.addon.worldgen.WorldgenEngine;
import me.seedexplorer.seedcracking.Dimension;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import java.util.ArrayList;
import java.util.List;
import static me.seedexplorer.standalone.StandaloneSeedCrackingWorkspace.Kind;

/** Client-thread collection and bounded automatic search. Never reads any seed source. */
final class StandaloneSeedCrackingRuntime {
    private static volatile StandaloneSeedCrackingRuntime instance;
    private final StandaloneSeedExplorerConfig config;
    private StandaloneSeedCrackingWorkspace workspace;
    private volatile Object level;
    private volatile String scope="";
    private volatile long ticks;
    private int scanCursor;
    private StandaloneSeedCrackingScheduler scheduler;
    private String state="Waiting for a world.";
    private final StandaloneSeedCrackingFeedback feedback=new StandaloneSeedCrackingFeedback();
    private static final List<int[]> SCAN_OFFSETS=scanOffsets();
    record Outline(String family,int dimension,int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {}
    private record SeenOutline(Outline outline,long tick) {}
    private final java.util.LinkedHashMap<String,SeenOutline> outlines=new java.util.LinkedHashMap<>();
    private volatile List<SeenOutline> outlineSnapshot=List.of();
    private final java.util.LinkedHashMap<BlockPos,Long> oceanAttempts=new java.util.LinkedHashMap<>();
    private String detectionStatus="Waiting for fully loaded structure geometry.";

    static List<Outline> visibleOutlines(Minecraft client) {
        var current=instance;
        if(current==null || !current.enabled() || client.level==null || current.level!=client.level
            || !current.scope.equals(current.config.crackingScope())) return List.of();
        int dimension=client.level.dimension()==Level.NETHER?-1:client.level.dimension()==Level.END?1:0;
        boolean searching=current.workspace!=null && current.workspace.running();
        return current.outlineSnapshot.stream().filter(seen->seen.outline.dimension()==dimension && (searching || current.ticks-seen.tick<=600))
            .map(SeenOutline::outline).toList();
    }
    private void observedOutline(String key,Outline outline) {
        outlines.remove(key);outlines.put(key,new SeenOutline(outline,ticks));
        while(outlines.size()>128) outlines.remove(outlines.keySet().iterator().next());
        outlineSnapshot=List.copyOf(outlines.values());
    }

    private StandaloneSeedCrackingRuntime(StandaloneSeedExplorerConfig config) { this.config=config; }
    static StandaloneSeedCrackingRuntime initialize(StandaloneSeedExplorerConfig config) {
        if(instance!=null) instance.closeSession();
        return instance=new StandaloneSeedCrackingRuntime(config);
    }
    static StandaloneSeedCrackingRuntime get(StandaloneSeedExplorerConfig config) {
        if(instance==null || instance.config!=config) return initialize(config);
        return instance;
    }
    static StandaloneSeedCrackingRuntime current() { return instance; }
    /** ClientLevel.hasChunk is always true; only the cache distinguishes absent packets. */
    static net.minecraft.world.level.chunk.LevelChunk loadedChunk(net.minecraft.client.multiplayer.ClientLevel world,int cx,int cz) {
        return world.getChunkSource().getChunk(cx,cz,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false);
    }
    StandaloneSeedCrackingWorkspace workspace(Minecraft client) { attach(client);return workspace; }
    String status() { return state; }
    String detectionStatus() { return detectionStatus; }
    long searches() { return scheduler==null?0:scheduler.searches(); }
    boolean enabled() { return config.booleanValue("seed-cracking-auto",true); }
    void enabled(boolean enabled) {
        config.setBoolean("seed-cracking-auto",enabled);config.save();
        if(!enabled && workspace!=null && workspace.running() && workspace.searchAutomatic()) workspace.cancel();
        if(!enabled && scheduler!=null) scheduler.paused();
        state=enabled?"Collecting loaded evidence as you explore.":"Paused. Saved evidence is retained.";
    }
    void reportManualCapture(Minecraft client,int added) {
        if(added<=0 || client==null || client.player==null || workspace==null)return;
        client.player.sendSystemMessage(Component.literal("[Seed Explorer] Added "+added+" observation(s). "
            +workspace.collectionProgress()).withColor(0x55DD88));
    }
    void retry() { if(scheduler!=null)scheduler.retry(ticks); }
    void clear() {
        if(workspace==null)return;
        workspace.cancel();workspace.clearEvidence();outlines.clear();outlineSnapshot=List.of();scheduler=new StandaloneSeedCrackingScheduler(workspace,this::persist,ticks);feedback.reset();persist();
    }
    void persist() { if(workspace!=null) { config.setCrackingData(scope,workspace.save());config.save(); } }
    private void closeSession() {
        if(workspace!=null) { persist();workspace.close();workspace=null; }
        outlines.clear();
        outlineSnapshot=List.of();
        oceanAttempts.clear();
        feedback.reset();
    }
    private void attach(Minecraft client) {
        String newScope=config.crackingScope();
        if(level==client.level && scope.equals(newScope) && workspace!=null) return;
        closeSession();level=client.level;scope=newScope;scanCursor=0;
        scheduler=null;
        if(client.level==null) { state="Waiting for a world.";return; }
        try {
            workspace=new StandaloneSeedCrackingWorkspace(config.predictionVersion(),
                (seed,dimension,x,y,z) -> {
                    var biome=WorldgenEngine.getBiome(seed,dimension==Dimension.NETHER?-1:dimension==Dimension.END?1:0,x,y,z);
                    if(biome==me.seedexplorer.addon.worldgen.PredictedBiome.UNKNOWN)
                        throw new IllegalStateException("Noise-biome oracle unavailable; no candidate was ruled out.");
                    return biome.id();
                });
            workspace.load(config.crackingData());
            if(workspace.removeLegacyClientCaptures()>0) persist();
            state="Collecting loaded evidence as you explore.";
            scheduler=new StandaloneSeedCrackingScheduler(workspace,this::persist,ticks);
            if(enabled() && config.predictionVersion().equals("26.3")) StandaloneSeedCrackingOceanDetector.prepare();
        } catch(IllegalArgumentException failure) { state="Automatic capture requires the 26.3 profile."; }
    }
    void tick(Minecraft client) {
        ticks++;attach(client);
        if(workspace==null || client.player==null) return;
        if(!enabled()) { if(scheduler!=null)scheduler.disabled();state="Automatic collection paused; advanced searches remain available.";reportFeedback(client);return; }
        if(!config.predictionVersion().equals("26.3")) { state="Automatic capture requires the running 26.3 profile.";reportFeedback(client);return; }
        if(client.level.dimension()!=Level.OVERWORLD && client.level.dimension()!=Level.NETHER && client.level.dimension()!=Level.END) {
            state="Automatic capture supports vanilla Overworld, Nether and End dimensions.";reportFeedback(client);return;
        }
        try {
            // Poll finished searches before collection can invalidate their result.
            scheduler.tick(ticks,config.intValue("seed-cracking-seconds",60,10,300),config.intValue("seed-cracking-threads",2,1,4));
            state=scheduler.status();
            if(!workspace.running() && ticks%5==0) collect(client);
            reportFeedback(client);
        } catch(RuntimeException failure) {
            enabled(false);state="Collection/search paused: "+failure.getMessage();
            client.player.sendSystemMessage(Component.literal("[Seed Explorer] Error: "+state+". "+workspace.collectionProgress())
                .withColor(0xFFFF7777));
        }
    }
    private void reportFeedback(Minecraft client) {
        String searchStatus="";
        if(workspace.outcome()!=null) {
            searchStatus=workspace.status();
        } else if(workspace.status().startsWith("Search failed:") || workspace.status().startsWith("Search cancelled")) searchStatus=workspace.status();
        String progress=workspace.collectionProgress();
        var snapshot=new StandaloneSeedCrackingFeedback.Snapshot(workspace.evidence().size(),
            workspace.distinctStructureCount(),workspace.overworldCount(Kind.BEDROCK),
            workspace.overworldCount(Kind.BIOME),enabled(),workspace.running(),workspace.searchAutomatic(),workspace.searchSequence(),
            workspace.running()?workspace.status():searchStatus,progress);
        for(String message:feedback.update(ticks,snapshot))
            client.player.sendSystemMessage(Component.literal("[Seed Explorer] "+message).withColor(0x55DD88));
    }
    private static List<int[]> scanOffsets() {
        List<int[]> offsets=new ArrayList<>();
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)offsets.add(new int[]{x,z});
        offsets.sort(java.util.Comparator.comparingInt(p->p[0]*p[0]+p[1]*p[1]));return List.copyOf(offsets);
    }
    private void collect(Minecraft client) {
        var world=client.level;var player=client.player;
        var before=workspace.evidence().size();
        int[] offset=SCAN_OFFSETS.get(scanCursor);scanCursor=(scanCursor+1)%SCAN_OFFSETS.size();
        int dx=offset[0],dz=offset[1];
        int cx=(player.blockPosition().getX()>>4)+dx,cz=(player.blockPosition().getZ()>>4)+dz;
        BlockPos at=new BlockPos(cx*16+8,64,cz*16+8);
        var chunk=loadedChunk(world,cx,cz);
        if(chunk==null) return;
        String dimension=world.dimension()==Level.NETHER?"the_nether":world.dimension()==Level.END?"the_end":"overworld";
        Dimension evidenceDimension=world.dimension()==Level.NETHER?Dimension.NETHER:world.dimension()==Level.END?Dimension.END:Dimension.OVERWORLD;
        if(workspace.dimensionCount(Kind.BIOME,evidenceDimension)<(evidenceDimension==Dimension.OVERWORLD?64:16)
            && !hasBiomeCell(dimension,at.getX(),at.getZ())) {
            int y=Math.floorDiv(player.blockPosition().getY(),4)*4;
            String biome=chunk.getNoiseBiome(at.getX()>>2,y>>2,at.getZ()>>2).unwrapKey().orElseThrow().identifier().toString();
            workspace.addObserved(Kind.BIOME,dimension+" "+at.getX()+" "+y+" "+at.getZ()+" "+biome,"loaded-noise-biome-v2");
        }
        long bedrockCount=workspace.dimensionCount(Kind.BEDROCK,world.dimension()==Level.NETHER?Dimension.NETHER:Dimension.OVERWORLD);
        int bedrockLimit=world.dimension()==Level.NETHER?64:192;
        if(world.dimension()!=Level.END && bedrockCount<bedrockLimit) {
            int floor=world.dimension()==Level.NETHER?1:-63;
            // Require the invariant base layer before interpreting gradient absence.
            // This excludes void/flat chunks and unfinished packet snapshots.
            for(int ox:new int[]{4,12}) for(int oz:new int[]{4,12}) {
                int x=cx*16+ox,z=cz*16+oz;
                if(!world.getBlockState(new BlockPos(x,floor-1,z)).is(Blocks.BEDROCK)) continue;
                for(int y=floor;y<floor+4 && bedrockCount<bedrockLimit;y++) if(workspace.addObserved(Kind.BEDROCK,
                    dimension+" "+x+" "+y+" "+z+" "+world.getBlockState(new BlockPos(x,y,z)).is(Blocks.BEDROCK),"loaded-bedrock")) bedrockCount++;
            }
        }
        if(world.dimension()==Level.OVERWORLD) {
            // The anchor lies in the same chunk as all four chamber chests. Scan
            // block entities only; no full-height block sweep on the render thread.
            var reader=(StandaloneSeedCrackingDetector.BlocksAt)(x,y,z)-> {
                var observed=loadedChunk(world,x>>4,z>>4);
                return observed==null?null:observed.getBlockState(new BlockPos(x,y,z));
            };
            int inspected=0,oceanChecks=0;
            for(BlockPos chest:chunk.getBlockEntities().keySet()) {
                if(++inspected>128) break;
                if(!world.getBlockState(chest).is(Blocks.CHEST)) continue;
                for(int[] direction:new int[][]{{2,0},{-2,0},{0,2},{0,-2}}) {
                    var start=StandaloneSeedCrackingDetector.pyramid(reader,chest.getX()+direction[0],chest.getY(),chest.getZ()+direction[1]);
                    if(start!=null) {
                        recordStructure(start.set(),start.chunkX(),start.chunkZ(),"recognized-intact-pyramid-26.3",new Outline("Desert pyramid",0,
                            start.chunkX()*16,chest.getY()-3,start.chunkZ()*16,
                            start.chunkX()*16+20,chest.getY()+25,start.chunkZ()*16+20));
                    }
                }
                if(oceanChecks==0 && ticks-oceanAttempts.getOrDefault(chest,-1000L)>=120) {
                    oceanChecks++;oceanAttempts.remove(chest);oceanAttempts.put(chest.immutable(),ticks);
                    while(oceanAttempts.size()>512) oceanAttempts.remove(oceanAttempts.keySet().iterator().next());
                    var recognition=StandaloneSeedCrackingOceanDetector.recognize(reader,chest);
                    if(recognition.status()==StandaloneSeedCrackingOceanDetector.Status.MATCH) {
                        var start=recognition.start();var box=start.bounds();
                        recordStructure(start.set(),start.chunkX(),start.chunkZ(),"recognized-shipwreck-26.3-v2",new Outline("Shipwreck",0,
                            box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ()));
                    } else if(recognition.status()!=StandaloneSeedCrackingOceanDetector.Status.NO_MATCH) detectionStatus=recognition.detail();
                }
            }
        }
        if(world.dimension()==Level.END && workspace.count(Kind.PILLARS)==0) capturePillars(world);
        if(workspace.evidence().size()!=before) persist();
    }
    private boolean hasBiomeCell(String dimension,int x,int z) {
        for(var evidence:workspace.evidence()) {
            if(evidence.kind()!=Kind.BIOME) continue;
            String[] fields=evidence.text().trim().split("[,\\s]+");
            if(fields.length==5 && fields[0].equals(dimension)
                && Math.floorDiv(Integer.parseInt(fields[1]),128)==Math.floorDiv(x,128)
                && Math.floorDiv(Integer.parseInt(fields[3]),128)==Math.floorDiv(z,128)) return true;
        }
        return false;
    }
    private void recordStructure(String set,int chunkX,int chunkZ,String source,Outline outline) {
        String text=set+" "+chunkX+" "+chunkZ;
        try {
            if(workspace.contains(Kind.STRUCTURE,text) || workspace.count(Kind.STRUCTURE)<64)
                workspace.addObserved(Kind.STRUCTURE,text,source);
            if(workspace.contains(Kind.STRUCTURE,text)) {
                observedOutline(text,outline);detectionStatus="Recorded observed "+outline.family()+" at start chunk "+chunkX+", "+chunkZ+".";
            }
        } catch(IllegalArgumentException unsupported) {
            detectionStatus="Recognized shape has no supported placement anchor; no seed constraint recorded.";
        }
    }
    private void capturePillars(net.minecraft.client.multiplayer.ClientLevel world) {
        List<String> tops=new ArrayList<>();
        for(int i=0;i<10;i++) {
            double angle=2*(-Math.PI+Math.PI/10*i);
            int x=(int)Math.floor(42*Math.cos(angle)),z=(int)Math.floor(42*Math.sin(angle));
            if(loadedChunk(world,x>>4,z>>4)==null) return;
            int top=-1;
            for(int y=103;y>=75;y--) if(world.getBlockState(new BlockPos(x,y,z)).is(Blocks.OBSIDIAN)) {top=y;break;}
            if(top<0 || !world.getBlockState(new BlockPos(x,top+1,z)).is(Blocks.BEDROCK)) return;
            tops.add(Integer.toString(top));
        }
        workspace.addObserved(Kind.PILLARS,String.join(" ",tops),"loaded-end-pillars");
    }
}
