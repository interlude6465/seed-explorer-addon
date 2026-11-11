package me.seedexplorer.standalone;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import java.util.ArrayList;
import java.util.List;

/** Native evidence collection and candidate review, separate from map prediction settings. */
public final class StandaloneSeedCrackingScreen extends Screen {
    private final Screen parent;
    private final StandaloneSeedExplorerConfig config;
    private final String scope;
    private final Object level;
    private final StandaloneSeedCrackingRuntime runtime;
    private StandaloneSeedCrackingWorkspace workspace;
    private StandaloneSeedCrackingWorkspace.Kind kind=StandaloneSeedCrackingWorkspace.Kind.STRUCTURE;
    private EditBox entry, lowSeeds;
    private int page, scroll;
    private String entryText="", lowText="", notice="";
    private final List<Button> editButtons=new ArrayList<>();
    private Button cancelButton, useButton;

    public StandaloneSeedCrackingScreen(Screen parent, StandaloneSeedExplorerConfig config) {
        super(Component.literal("Seed cracking")); this.parent=parent; this.config=config;
        scope=config.crackingScope(); level=net.minecraft.client.Minecraft.getInstance().level;
        runtime=StandaloneSeedCrackingRuntime.get(config);
        try {
            workspace=runtime.workspace(net.minecraft.client.Minecraft.getInstance());
            if(workspace!=null) lowText=workspace.lowSeeds();
            else notice="Join a world with a supported prediction profile to collect evidence.";
        } catch(IllegalArgumentException failure) { notice="Cracking supports 26.1, 26.2 and 26.3. Select a supported prediction version."; }
    }
    private boolean current() { return scope.equals(config.crackingScope()) && net.minecraft.client.Minecraft.getInstance().level==level; }
    private int left() { return Math.max(12,(width-600)/2); }
    private int span() { return Math.min(600,width-24); }
    private Button button(String text,int x,int y,int w,Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text),b -> attempt(action)).bounds(x,y,w,20).build());
    }
    private Button edit(String text,int x,int y,int w,Runnable action) {
        Button button=button(text,x,y,w,action); editButtons.add(button); return button;
    }
    private void attempt(Runnable action) {
        try { if(!current()) throw new IllegalStateException("World or version changed. Reopen this workspace."); action.run(); }
        catch(RuntimeException failure) { notice=failure.getMessage()==null ? failure.getClass().getSimpleName() : failure.getMessage(); }
    }
    @Override protected void init() {
        editButtons.clear(); cancelButton=null;useButton=null;
        int x=left(),w=span(),half=(w-6)/2;
        int tab=(w-12)/3;
        button("Background",x,34,tab,()->{saveInputs();page=0;scroll=0;rebuildWidgets();});
        button("Observations",x+tab+6,34,tab,()->{saveInputs();page=1;scroll=0;rebuildWidgets();});
        button("Search and candidates",x+2*(tab+6),34,w-2*(tab+6),()->{saveInputs();page=2;scroll=0;rebuildWidgets();});
        button("Back",x+w-80,height-28,80,this::onClose);
        if(workspace==null) return;
        if(page==0) {
            button(runtime.enabled()?"Pause background cracking":"Resume background cracking",x,64,w,()->{
                runtime.enabled(!runtime.enabled());rebuildWidgets();
            });
            edit("Retry with current evidence",x,92,half,()->{runtime.retry();notice="Automatic search will retry when sufficiently constrained.";});
            edit("Clear saved evidence",x+half+6,92,half,()->{runtime.clear();lowText="";notice="Evidence cleared; new observations will be collected.";});
            button("Review candidates",x,120,w,()->{page=2;scroll=0;lowText=workspace.lowSeeds();rebuildWidgets();});
        } else if(page==1) {
            edit(kind.label+" >",x,64,w,()->{saveInputs();kind=StandaloneSeedCrackingWorkspace.Kind.values()[(kind.ordinal()+1)%StandaloneSeedCrackingWorkspace.Kind.values().length];entryText="";rebuildWidgets();});
            entry=addRenderableWidget(new EditBox(font,x,118,w,20,Component.literal("Observation values")));
            entry.setMaxLength(600);entry.setValue(entryText);
            int third=(w-12)/3;
            edit("Add",x,144,third,()->{workspace.add(kind,entry.getValue());entry.setValue("");entryText="";persist();runtime.reportManualCapture(minecraft,1);notice="Observation saved. "+workspace.collectionProgress();});
            Button capture=edit("Capture loaded",x+third+6,144,third,this::capture);
            capture.setTooltip(Tooltip.create(Component.literal("Reads loaded blocks/noise biomes only. Pyramids and shipwrecks are recorded automatically; other structure starts and slime status use advanced evidence.")));
            edit("Undo last add",x+2*(third+6),144,w-2*(third+6),()->{workspace.removeLast();persist();notice=workspace.status();});
        } else {
            lowSeeds=addRenderableWidget(new EditBox(font,x,80,w,20,Component.literal("Known low-48 seeds")));
            lowSeeds.setMaxLength(700);lowSeeds.setValue(lowText);
            int third=(w-12)/3;
            edit("Lift structures",x,108,third,()->start(false));
            edit("Recover upper bits",x+third+6,108,third,()->start(true));
            cancelButton=button("Cancel search",x+2*(third+6),108,w-2*(third+6),()->{runtime.enabled(false);workspace.cancel();});
            button("Copy report",x,136,half,()->minecraft.keyboardHandler.setClipboard(workspace.report()));
            edit("Use lifted candidates",x+half+6,136,half,()->{
                var result=workspace.outcome();
                if(result==null || result.lowSeeds().isEmpty()) throw new IllegalStateException("Run structure lifting first.");
                lowText=result.lowSeeds().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
                lowSeeds.setValue(lowText); workspace.lowSeeds(lowText);persist();
            });
            useButton=button("Use unique candidate",x,height-28,Math.min(190,w-90),()->{
                var result=workspace.outcome();
                if(result==null || !result.complete() || result.worldSeeds().size()!=1) return;
                config.setSeed(result.worldSeeds().getFirst());config.save();
                notice="Candidate applied to this world's map. Check independent observations.";
            });
            useButton.setTooltip(Tooltip.create(Component.literal("Uses the only full seed for the supplied low-48 inputs. Other low-48 seeds may exist, especially after partial lifting. Validate independently.")));
        }
        tick();
    }
    private void saveInputs() {
        if(page==1 && entry!=null) entryText=entry.getValue();
        if(page==2 && lowSeeds!=null) lowText=lowSeeds.getValue();
    }
    @Override public void resize(int width,int height) {
        saveInputs();
        super.resize(width,height);
    }
    private void persist() { if(current() && workspace!=null) { config.setCrackingData(workspace.save());config.save(); } }
    private void start(boolean upper) {
        saveInputs();
        if(!lowText.equals(workspace.lowSeeds())) workspace.lowSeeds(lowText);
        runtime.enabled(false);persist();workspace.start(upper,config.intValue("seed-cracking-seconds",60,10,300),config.intValue("seed-cracking-threads",2,1,4),false);
        notice="";scroll=0;
    }
    @Override public void tick() {
        if(workspace==null) return;
        if(!current() && workspace.running()) workspace.cancel();
        boolean canEdit=current() && !workspace.running();
        for(Button button:editButtons) button.active=canEdit;
        if(entry!=null) entry.setEditable(canEdit);
        if(lowSeeds!=null) lowSeeds.setEditable(canEdit);
        if(cancelButton!=null) cancelButton.active=workspace.running();
        var result=workspace.outcome();
        if(useButton!=null) useButton.active=canEdit && result!=null && result.complete() && result.worldSeeds().size()==1;
    }
    private void capture() {
        var client=minecraft; var world=client.level; var player=client.player;
        if(world==null || player==null) throw new IllegalStateException("Join a world to capture loaded evidence.");
        if(!config.predictionVersion().equals("26.3")) throw new IllegalStateException("Live capture requires the running 26.3 prediction profile.");
        int before=workspace.evidence().size();
        String dimension=world.dimension()==Level.NETHER ? "the_nether" : world.dimension()==Level.END ? "the_end" : "overworld";
        BlockPos pos=player.blockPosition();
        if(kind==StandaloneSeedCrackingWorkspace.Kind.BIOME) {
            int x=Math.floorDiv(pos.getX(),4)*4,y=Math.floorDiv(pos.getY(),4)*4,z=Math.floorDiv(pos.getZ(),4)*4;
            var observed=StandaloneSeedCrackingRuntime.loadedChunk(world,pos.getX()>>4,pos.getZ()>>4);
            if(observed==null) throw new IllegalStateException("Player chunk is not loaded.");
            var biome=observed.getNoiseBiome(x>>2,y>>2,z>>2);
            String id=biome.unwrapKey().orElseThrow().identifier().toString();
            workspace.add(kind,dimension+" "+x+" "+y+" "+z+" "+id);
        } else if(kind==StandaloneSeedCrackingWorkspace.Kind.BEDROCK) {
            if(world.dimension()==Level.END) throw new IllegalStateException("The End has no bedrock gradient for this method.");
            int floor=world.dimension()==Level.NETHER ? (pos.getY()>64 ? 123 : 1) : -63;
            var samples=new ArrayList<StandaloneSeedCrackingWorkspace.Evidence>();
            for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) for(int y=floor;y<floor+4;y++) {
                BlockPos sample=new BlockPos(pos.getX()+dx,y,pos.getZ()+dz);
                var observed=StandaloneSeedCrackingRuntime.loadedChunk(world,sample.getX()>>4,sample.getZ()>>4);
                if(observed==null || !observed.getBlockState(new BlockPos(sample.getX(),floor==123?127:floor-1,sample.getZ())).is(Blocks.BEDROCK)) continue;
                String text=dimension+" "+sample.getX()+" "+y+" "+sample.getZ()+" "+world.getBlockState(sample).is(Blocks.BEDROCK);
                if(workspace.contains(kind,text)) continue;
                samples.add(new StandaloneSeedCrackingWorkspace.Evidence(kind,text));
            }
            if(samples.isEmpty()) throw new IllegalStateException("No new loaded bedrock samples; move to another column.");
            workspace.addAll(samples);
        } else if(kind==StandaloneSeedCrackingWorkspace.Kind.PILLARS) {
            if(world.dimension()!=Level.END) throw new IllegalStateException("Visit the central End island to capture pillars.");
            List<String> tops=new ArrayList<>();
            for(int i=0;i<10;i++) {
                double angle=2.0*(-Math.PI+(Math.PI/10.0)*i);
                int x=(int)Math.floor(42.0*Math.cos(angle)),z=(int)Math.floor(42.0*Math.sin(angle));
                BlockPos center=new BlockPos(x,80,z);
                if(StandaloneSeedCrackingRuntime.loadedChunk(world,x>>4,z>>4)==null) throw new IllegalStateException("All ten pillar chunks must be loaded.");
                int top=-1;
                for(int y=103;y>=75;y--) if(world.getBlockState(new BlockPos(x,y,z)).is(Blocks.OBSIDIAN)) { top=y;break; }
                if(top<0 || !world.getBlockState(new BlockPos(x,top+1,z)).is(Blocks.BEDROCK))
                    throw new IllegalStateException("A pillar is missing or modified; capture an untouched central island.");
                tops.add(Integer.toString(top));
            }
            workspace.add(kind,String.join(" ",tops));
        } else throw new IllegalStateException("Enter confirmed structure start chunks or slime observations manually.");
        persist();
        int added=workspace.evidence().size()-before;
        runtime.reportManualCapture(client,added);
        notice=added+" observation(s) saved. "+workspace.collectionProgress();
    }
    private String fit(String text,int pixels) { return font.plainSubstrByWidth(text,Math.max(20,pixels)); }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
        int x=left(),w=span();g.fill(0,0,width,height,0xFF101619);
        g.centeredText(font,title,width/2,12,0xFFFFFFFF);
        if(workspace==null) { g.text(font,fit(notice,w),x,72,0xFFFF9999);super.extractRenderState(g,mx,my,delta);return; }
        List<String> lines=new ArrayList<>();int listY;
        if(page==0) {
            lines.addAll(wrapped(runtime.status(),w));
            lines.add("Automatic searches: "+runtime.searches()+" | Saved evidence: "+workspace.evidence().size());
            lines.add("Explore untouched vanilla terrain; no coordinates are required.");
            lines.add("Recognizes loaded shipwrecks and intact desert-pyramid chambers.");
            lines.add(StandaloneSeedCrackingWorkspace.LOW48_STRUCTURE_THRESHOLD
                +" distinct starts trigger bounded low-48 search automatically.");
            lines.add("Loaded bedrock, noise biomes and End pillars are also recorded.");
            lines.add("Other structure families and slime status need advanced evidence.");
            lines.add("Lifting is non-exhaustive. Applying a candidate is always explicit.");
            lines.addAll(wrapped(runtime.detectionStatus(),w));
            var progress=workspace.progress();
            if(progress!=null) lines.add(progress.stage()+": "+progress.tested()+" / "+progress.total()+" tested");
            listY=153;
        } else if(page==1) {
            g.text(font,fit(kind.help,w),x,91,0xFFB1C9C5);
            g.text(font,fit("Example: "+kind.example,w),x,104,0xFF8FABA6);
            lines.add("Saved observations: "+workspace.evidence().size()+" | Version "+workspace.version());
            for(var item:workspace.evidence()) lines.add(item.kind().label+": "+item.text()+" ["+(item.source()==null?"manual":item.source())+"]");
            listY=177;
        } else {
            g.text(font,"Known low-48 candidates (optional, comma-separated)",x,66,0xFFB1C9C5);
            lines.add(workspace.running() ? "Searching... continues when you leave this screen." : "Experimental candidates; validate against independent observations.");
            var progress=workspace.progress();
            if(progress!=null) lines.add(progress.stage()+": "+progress.tested()+" / "+progress.total()+" tested, "+progress.matches()+" matches");
            lines.addAll(font.split(Component.literal(workspace.status()),w).stream().map(seq -> {
                StringBuilder out=new StringBuilder();seq.accept((index,style,codepoint)->{out.appendCodePoint(codepoint);return true;});return out.toString();
            }).toList());
            var result=workspace.outcome();
            if(result!=null) {
                lines.add(result.complete() ? "Search finished for these inputs." : "Partial / incomplete search; candidates are not exhaustive.");
                lines.add("Low-48: "+result.lowSeeds().size()+" | Full seeds: "+result.worldSeeds().size());
                for(long seed:result.worldSeeds()) lines.add("World candidate: "+seed);
                if(result.worldSeeds().isEmpty()) for(long seed:result.lowSeeds()) lines.add("Low-48 candidate: "+seed);
            }
            listY=169;
        }
        int rows=Math.max(1,(height-60-listY)/13);scroll=Math.min(scroll,Math.max(0,lines.size()-rows));
        for(int i=scroll;i<Math.min(lines.size(),scroll+rows);i++) g.text(font,fit(lines.get(i),w),x,listY+(i-scroll)*13,0xFFE4EAE5);
        if(lines.size()>rows) g.text(font,"Scroll for more ("+(scroll+1)+"/"+lines.size()+")",x,height-55,0xFF8FABA6);
        g.text(font,fit(current()?notice:"World or prediction version changed; reopen this workspace.",w),x,height-42,0xFFFFC17D);
        super.extractRenderState(g,mx,my,delta);
    }
    private List<String> wrapped(String text,int width) {
        return font.split(Component.literal(text),width).stream().map(seq -> {
            StringBuilder out=new StringBuilder();seq.accept((index,style,codepoint)->{out.appendCodePoint(codepoint);return true;});return out.toString();
        }).toList();
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(vertical==0) return false;scroll=Math.max(0,scroll+(vertical<0?1:-1));return true;
    }
    @Override public void onClose() {
        saveInputs();
        if(workspace!=null) {
            if(page==2 && !workspace.running() && !lowText.equals(workspace.lowSeeds())) {
                try { workspace.lowSeeds(lowText); } catch(IllegalArgumentException ignored) {}
            }
            persist();
        }
        minecraft.gui.setScreen(parent);
    }
    @Override public void removed() { if(current()) persist(); }
    @Override public boolean isPauseScreen() { return false; }
}
