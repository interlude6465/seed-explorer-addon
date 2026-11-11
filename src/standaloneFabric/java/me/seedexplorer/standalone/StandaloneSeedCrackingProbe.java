package me.seedexplorer.standalone;

import me.seedexplorer.seedcracking.*;
import java.nio.file.Files;
import java.util.List;

/** Integration checks for persistence, actual engine invocation and stale-result cancellation. */
public final class StandaloneSeedCrackingProbe {
    public static void main(String[] args) throws Exception {
        if(args.length==3 && args[0].equals("--saved-evidence")) {
            net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
            verifySavedEvidence(java.nio.file.Path.of(args[1]),Long.parseLong(args[2]));return;
        }
        if(java.util.Arrays.asList(args).contains("--workflow-only")) {
            net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
            verifyAutomaticSearch();verifyAutomaticControl();verifyFeedback();
            System.out.println("Standalone seed cracking workflow probe: PASS");return;
        }
        StandaloneSeedCrackingDetectionProbe.verify();
        StandaloneSeedCrackingOutlineProbe.verify();
        StandaloneSeedCrackingVanillaProbe.verify();
        verifyAutomaticSearch();
        verifyAutomaticControl();
        try(var workspace=new StandaloneSeedCrackingWorkspace("26.3",null)) {
            rejects(() -> workspace.add(StandaloneSeedCrackingWorkspace.Kind.BEDROCK,"overworld 0 -64 0 true"));
            rejects(() -> workspace.add(StandaloneSeedCrackingWorkspace.Kind.SLIME,"0 0 maybe"));
            rejects(() -> StandaloneSeedCrackingWorkspace.parseSeeds("-1"));
            rejects(() -> workspace.add(StandaloneSeedCrackingWorkspace.Kind.STRUCTURE,"mineshafts 0 0"));
            verifyAtomicCapture();
            verifyFeedback();
            long expected=0x1234000000075BCDL;
            var oracle=VersionProfile.parse("26.3").bedrockOracle(Dimension.OVERWORLD);
            for(int i=0;i<64;i++) {
                int x=i*37-900,z=i*19-500,y=-63+i%4;
                workspace.add(StandaloneSeedCrackingWorkspace.Kind.BEDROCK,"overworld "+x+" "+y+" "+z+" "+oracle.isBedrock(expected,x,y,z));
            }
            workspace.lowSeeds(Long.toString(expected & Lcg48.MASK));
            String saved=workspace.save();
            try(var restored=new StandaloneSeedCrackingWorkspace("26.3",null)) {
                restored.load(saved);
                restored.load(saved);
                require(restored.evidence().size()==64,"Evidence did not round-trip");
                restored.start(true,10,2);
                long deadline=System.nanoTime()+20_000_000_000L;
                while(restored.running() && System.nanoTime()<deadline) Thread.sleep(10);
                require(!restored.running(),"Upper-bit worker exceeded integration budget");
                var result=restored.outcome();
                require(result!=null && result.complete() && result.worldSeeds().equals(List.of(expected)),
                    "Upper-bit search did not preserve the known candidate: "+restored.status());
                restored.add(StandaloneSeedCrackingWorkspace.Kind.SLIME,"0 0 "+!new SlimeChunkObservation(0,0,true).matches(expected));
                restored.start(true,10,1);
                while(restored.running() && System.nanoTime()<deadline) Thread.sleep(10);
                require(restored.outcome()!=null && restored.outcome().complete() && restored.outcome().worldSeeds().isEmpty(),
                    "Completely rejected low-48 inputs were mislabeled as a partial search");
                restored.removeLast();
                restored.start(true,10,2);restored.cancel();Thread.sleep(100);
                require(restored.outcome()==null && !restored.running(),"Cancelled search published a stale result");
            }
            var dir=Files.createTempDirectory("seed-explorer-cracking-probe-");
            var file=dir.resolve("config.properties");
            try {
                var config=StandaloneSeedExplorerConfig.load(file);config.selectProfile("server:a");
                config.setCrackingData(saved);config.save();config.selectProfile("server:b");
                require(config.crackingData().isBlank(),"Cracking evidence leaked between worlds");
                config.selectProfile("server:a");require(config.crackingData().equals(saved),"World evidence disappeared");
                config.setPredictionVersion("26.2");require(config.crackingData().isBlank(),"Cracking evidence leaked between versions");
                config.setPredictionVersion("26.3");config.resetSettings();config.save();
                require(config.crackingData().equals(saved),"Settings reset erased cracking evidence");
            } finally { Files.deleteIfExists(file);Files.deleteIfExists(dir); }
            System.out.println("Standalone seed cracking probe: PASS (evidence, upper bits, cancellation, world/version isolation)");
        }
    }
    private static void verifySavedEvidence(java.nio.file.Path file,long expected) throws Exception {
        var properties=new java.util.Properties();
        try(var reader=Files.newBufferedReader(file)) {properties.load(reader);}
        String key=properties.stringPropertyNames().stream().filter(name->name.startsWith("cracking.")).findFirst().orElseThrow();
        try(var workspace=new StandaloneSeedCrackingWorkspace("26.3",(seed,dimension,x,y,z)->
            me.seedexplorer.addon.worldgen.WorldgenEngine.getBiome(seed,dimension==Dimension.NETHER?-1:dimension==Dimension.END?1:0,x,y,z).id())) {
            workspace.load(properties.getProperty(key));int mismatches=0;
            for(var item:workspace.evidence()) {
                for(var filter:workspace.parse(List.of(item)).filters()) if(!filter.matches(expected)) {
                    System.out.println("SAVED LIVE FILTER MISMATCH: "+item);mismatches++;
                }
            }
            require(mismatches==0,"Saved client observations rejected known vanilla ground truth: "+mismatches);
            System.out.println("Saved live evidence independently accepts vanilla ground truth; observations="+workspace.evidence().size());
            workspace.start(true,60,2);
            long deadline=System.nanoTime()+65_000_000_000L;
            while(workspace.running() && System.nanoTime()<deadline) Thread.sleep(20);
            System.out.println("Saved live recovery: "+workspace.outcome()+"; "+workspace.status());
            require(!workspace.running(),"Saved live recovery exceeded budget");
            require(workspace.outcome()!=null && workspace.outcome().complete() && workspace.outcome().worldSeeds().equals(List.of(expected)),
                "Saved live evidence did not uniquely recover vanilla seed");
        }
    }
    private static void verifyAtomicCapture() {
        try(var workspace=new StandaloneSeedCrackingWorkspace("26.3",null)) {
            var batch=new java.util.ArrayList<StandaloneSeedCrackingWorkspace.Evidence>();
            for(int i=0;i<511;i++) batch.add(new StandaloneSeedCrackingWorkspace.Evidence(
                StandaloneSeedCrackingWorkspace.Kind.SLIME,i+" 0 true"));
            workspace.addAll(batch);
            rejects(() -> workspace.addAll(List.of(
                new StandaloneSeedCrackingWorkspace.Evidence(StandaloneSeedCrackingWorkspace.Kind.SLIME,"511 0 true"),
                new StandaloneSeedCrackingWorkspace.Evidence(StandaloneSeedCrackingWorkspace.Kind.SLIME,"512 0 true"))));
            require(workspace.evidence().size()==511,"Oversized capture partially changed evidence");
            workspace.removeLast();
            require(workspace.evidence().isEmpty(),"Undo did not remove the whole capture batch");
        }
    }
    private static void verifyAutomaticSearch() throws Exception {
        try(var workspace=new StandaloneSeedCrackingWorkspace("26.3",(seed,dimension,x,y,z)->
            me.seedexplorer.addon.worldgen.WorldgenEngine.getBiome(seed,0,x,y,z).id())) {
            long expected=0x123400000000007bL;
            var bedrock=VersionProfile.parse("26.3").bedrockOracle(Dimension.OVERWORLD);
            for(int i=0;i<192;i++) {
                int x=i*7919-900_000,z=i*i*31+(i%7)*997-500_000,y=-63+i%4;
                workspace.addObserved(StandaloneSeedCrackingWorkspace.Kind.BEDROCK,
                    "overworld "+x+" "+y+" "+z+" "+bedrock.isBedrock(expected,x,y,z),"probe-observed-bedrock");
            }
            for(int i=0;i<8;i++) {
                int x=i*2048-8192,z=i*i*1024-16384;
                String biome=me.seedexplorer.addon.worldgen.WorldgenEngine.getBiome(expected,0,x,64,z).id();
                workspace.addObserved(StandaloneSeedCrackingWorkspace.Kind.BIOME,
                    "overworld "+x+" 64 "+z+" "+biome,"probe-noise-biome");
            }
            // Independent vanilla placement generates the known starts. The solver
            // receives only observations, never this seed or a configured map seed.
            var vanilla=new net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement(
                32,8,net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType.LINEAR,14357617);
            var starts=new java.util.ArrayList<String>();
            for(int i=0;i<6;i++) {
                var chunk=vanilla.getPotentialStructureChunk(expected,(-31+i*11)*32,(17-i*13)*32);
                starts.add("desert_pyramids "+chunk.x()+" "+chunk.z());
            }
            for(int i=0;i<5;i++) workspace.addObserved(StandaloneSeedCrackingWorkspace.Kind.STRUCTURE,starts.get(i),"probe-observed-pyramid");
            require(!workspace.addObserved(StandaloneSeedCrackingWorkspace.Kind.STRUCTURE,starts.getFirst(),"other-source"),
                "Automatic deduplication depends on source label");
            workspace.add(StandaloneSeedCrackingWorkspace.Kind.STRUCTURE,starts.getFirst().replace(" ","  "));
            var saves=new java.util.concurrent.atomic.AtomicInteger();
            var scheduler=new StandaloneSeedCrackingScheduler(workspace,saves::incrementAndGet,0);
            scheduler.tick(100,10,2);
            require(scheduler.searches()==0 && !workspace.running(),"Five distinct starts launched a search or duplicates inflated constraints");
            String[] firstStart = starts.getFirst().split(" ");
            workspace.add(StandaloneSeedCrackingWorkspace.Kind.STRUCTURE,"minecraft:desert_pyramids "+
                Integer.parseInt(firstStart[1])+"  "+Integer.parseInt(firstStart[2]));
            require(workspace.distinctStructureCount()==5,"structure id aliases, whitespace or numeric formatting inflated distinct starts");
            workspace.addObserved(StandaloneSeedCrackingWorkspace.Kind.STRUCTURE,starts.get(5),"probe-observed-pyramid");
            scheduler.tick(101,10,2);
            require(scheduler.searches()==1,"Six observed starts did not trigger automatic low-48 search");
            await(workspace);
            require(workspace.outcome()!=null && workspace.outcome().lowSeeds().contains(expected & Lcg48.MASK),
                "Automatic low-48 search lost independent vanilla seed: "+workspace.status());
            require(!workspace.outcome().complete(),"Research lifting falsely advertised exhaustive coverage");
            scheduler.tick(102,10,2);scheduler.tick(121,10,2);
            require(scheduler.searches()==1,"Automatic stage handoff ignored cooldown");
            scheduler.tick(122,10,2);await(workspace);scheduler.tick(123,10,2);
            require(scheduler.searches()==2 && scheduler.candidateReady() && workspace.outcome().worldSeeds().equals(List.of(expected)),
                "Background pipeline did not recover the independent full seed: "+workspace.status());
            require(saves.get()==1,"Automatic low-48 handoff was not persisted");
            scheduler.tick(1000,10,2);require(scheduler.searches()==2,"Completed candidate repeatedly triggered automatic searches");
            int x=771,y=-62,z=-411;
            workspace.addObserved(StandaloneSeedCrackingWorkspace.Kind.BEDROCK,
                "overworld "+x+" "+y+" "+z+" "+bedrock.isBedrock(expected,x,y,z),"probe-new-observation");
            scheduler.tick(1001,10,2);require(scheduler.searches()==2,"New evidence bypassed retry cooldown");
            scheduler.tick(1323,10,2);await(workspace);scheduler.tick(1324,10,2);
            require(scheduler.searches()==3 && scheduler.candidateReady(),"New evidence did not trigger automatic revalidation");
            var seventh=vanilla.getPotentialStructureChunk(expected,73*32,-89*32);
            workspace.addObserved(StandaloneSeedCrackingWorkspace.Kind.STRUCTURE,
                "desert_pyramids "+seventh.x()+" "+seventh.z(),"probe-new-start");
            scheduler.tick(2524,10,2);
            require(scheduler.searches()==4 && scheduler.status().startsWith("Lifting"),
                "Fresh structure evidence retried stale upper-bit inputs instead of relifting");
            scheduler.disabled();Thread.sleep(100);
            require(!workspace.running() && workspace.outcome()==null,"Disabling automatic search published a stale result");
            scheduler.tick(2525,10,2);
            require(scheduler.searches()==5 && scheduler.status().startsWith("Lifting"),
                "Resuming automatic mode did not retry cancelled evidence");
            scheduler.disabled();
            System.out.println("Automatic cracking scheduler: PASS (observed starts -> low48 -> full seed, deduplication, cooldown, persistence, revalidation, fresh-start priority, disable/resume)");
        }
    }
    private static void verifyAutomaticControl() throws Exception {
        try(var workspace=new StandaloneSeedCrackingWorkspace("26.3",(s,d,x,y,z)->"minecraft:plains")) {
            workspace.addObserved(StandaloneSeedCrackingWorkspace.Kind.BIOME,"overworld 0 64 0 plains","loaded-noise-biome");
            workspace.addObserved(StandaloneSeedCrackingWorkspace.Kind.STRUCTURE,"shipwrecks 0 0","recognized-shipwreck-26.3");
            workspace.addObserved(StandaloneSeedCrackingWorkspace.Kind.BIOME,"overworld 32 64 0 plains","loaded-noise-biome-v2");
            workspace.add(StandaloneSeedCrackingWorkspace.Kind.SLIME,"0 0 true");
            workspace.automaticLowSeeds(List.of(1L));
            workspace.load(workspace.save());
            require(workspace.removeLegacyClientCaptures()==2 && workspace.evidence().size()==2
                && workspace.lowSeeds().isBlank(),"Unsafe old client observations or derived candidates survived migration");
            require(workspace.removeLegacyClientCaptures()==0,"Client evidence migration was not idempotent");
        }
        try(var workspace=new StandaloneSeedCrackingWorkspace("26.3",null)) {
            workspace.lowSeeds("1");
            var nether=VersionProfile.parse("26.3").bedrockOracle(Dimension.NETHER);
            for(int i=0;i<64;i++) workspace.addObserved(StandaloneSeedCrackingWorkspace.Kind.BEDROCK,
                "the_nether "+i+" 2 0 "+nether.isBedrock(1,i,2,0),"probe-nether");
            require(workspace.dimensionCount(StandaloneSeedCrackingWorkspace.Kind.BEDROCK,Dimension.NETHER)==64
                && workspace.overworldCount(StandaloneSeedCrackingWorkspace.Kind.BEDROCK)==0,
                "Nether samples consumed the Overworld evidence count");
            require(!workspace.hasUpperBitEvidence() && !workspace.enoughUpperBitEvidence(),
                "Nether legacy samples incorrectly advertised upper-bit information");
            rejects(()->workspace.start(true,10,2));
            var scheduler=new StandaloneSeedCrackingScheduler(workspace,()->{},0);
            scheduler.tick(100,10,2);
            require(scheduler.searches()==0,"Nether-only samples launched upper-bit recovery");
            var overworld=VersionProfile.parse("26.3").bedrockOracle(Dimension.OVERWORLD);
            for(int i=0;i<48;i++) workspace.addObserved(StandaloneSeedCrackingWorkspace.Kind.BEDROCK,
                "overworld "+i+" -62 0 "+overworld.isBedrock(1,i,-62,0),"probe-overworld");
            require(workspace.enoughUpperBitEvidence(),"Overworld filters did not enable upper-bit recovery");
            String progress=workspace.collectionProgress();
            require(progress.contains("0/"+StandaloneSeedCrackingWorkspace.LOW48_STRUCTURE_THRESHOLD+" distinct structure starts")
                && progress.contains("upper-bit data threshold reached")
                && progress.contains("112/512 observations saved"),
                "collection progress did not expose the upper-bit threshold: "+progress);
            scheduler.tick(101,10,2);
            require(scheduler.searches()==1,"Mixed-dimension evidence failed to launch recovery");
            scheduler.disabled();Thread.sleep(100);
            require(!workspace.running() && workspace.outcome()==null,"Disabled upper-bit search published a stale result");
            scheduler.tick(102,10,2);require(scheduler.searches()==2,"Cancelled upper search did not resume");
            scheduler.disabled();
        }
    }
    private static void verifyFeedback() {
        var feedback=new StandaloneSeedCrackingFeedback();
        java.util.function.BiFunction<Long,StandaloneSeedCrackingFeedback.Snapshot,java.util.List<String>> update=feedback::update;
        var start=new StandaloneSeedCrackingFeedback.Snapshot(0,0,0,0,true,false,false,0,"","0/6 structures");
        var initial=update.apply(1L,start);
        require(initial.size()==2 && initial.getFirst().contains("active") && initial.get(1).contains("Evidence update"),
            "new-session feedback did not announce collection and current counts");
        var one=new StandaloneSeedCrackingFeedback.Snapshot(1,0,0,0,true,false,false,0,"","0/6 structures");
        require(update.apply(2L,one).isEmpty(),"individual observation generated chat spam");
        var batch=new StandaloneSeedCrackingFeedback.Snapshot(4,1,0,0,true,false,false,0,"","1/6 structures");
        require(update.apply(101L,batch).stream().anyMatch(m->m.contains("Evidence update")),
            "batched evidence update did not appear after the throttle interval");
        var threshold=new StandaloneSeedCrackingFeedback.Snapshot(9,
            StandaloneSeedCrackingWorkspace.LOW48_STRUCTURE_THRESHOLD,0,0,true,false,false,0,"","6/6 structures");
        require(update.apply(102L,threshold).stream().anyMatch(m->m.contains("Evidence update")),
            "low-48 threshold crossing was delayed by batching");
        var paused=new StandaloneSeedCrackingFeedback.Snapshot(9,6,0,0,false,false,false,0,"","6/6 structures");
        require(update.apply(103L,paused).stream().anyMatch(m->m.contains("paused")),"pause was not announced");
        var manualStart=new StandaloneSeedCrackingFeedback.Snapshot(9,6,0,0,false,true,false,1,"Searching upper bits", "6/6 structures");
        require(update.apply(104L,manualStart).stream().anyMatch(m->m.contains("Manual search started")),
            "manual search start was not announced while automatic collection is paused");
        var manualError=new StandaloneSeedCrackingFeedback.Snapshot(9,6,0,0,false,false,false,1,
            "Search failed: oracle unavailable","6/6 structures");
        require(update.apply(105L,manualError).stream().anyMatch(m->m.contains("Search error")),
            "manual search error was not announced after completion");
        var resumed=new StandaloneSeedCrackingFeedback.Snapshot(9,6,0,0,true,false,false,1,"","6/6 structures");
        require(update.apply(106L,resumed).stream().anyMatch(m->m.contains("resumed")),"resume was not announced");
        feedback.reset();
        var reset=update.apply(1L,start);
        require(reset.size()==2 && reset.getFirst().contains("active"),"session reset retained old throttle state");
        feedback.reset();update.apply(1L,start);
        var missedTicks=new StandaloneSeedCrackingFeedback.Snapshot(0,0,0,0,true,false,true,1,
            "Found 1 low-48 candidate(s)","0/6 structures");
        require(update.apply(2L,missedTicks).stream().anyMatch(m->m.contains("Search finished")),
            "search completion was missed when start and finish fell between runtime ticks");
        require(StandaloneSeedCrackingWorkspace.liftFeedback(new LiftResult(List.of(),LiftResult.Status.BUDGET_EXHAUSTED,
            0,0,0,0,0,0,0,"budget")).contains("partial"),"truncated low-48 result was reported as exhaustive no-match");
        require(StandaloneSeedCrackingWorkspace.liftFeedback(new LiftResult(List.of(),LiftResult.Status.NO_MATCH,
            0,0,0,0,0,0,0,"none")).contains("non-exhaustive"),"low-48 no-match overstated completeness");
        require(StandaloneSeedCrackingWorkspace.upperFeedback(List.of(12L),new RecoveryResult(List.of(),
            RecoveryResult.Status.BUDGET_EXHAUSTED,1,10,0,0,"budget"),false).contains("Some supplied upper halves remain untested"),
            "truncated upper search was reported as a complete no-match");
        require(StandaloneSeedCrackingWorkspace.upperFeedback(List.of(12L),new RecoveryResult(List.of(),
            RecoveryResult.Status.NO_MATCH,1,65536,0,0,"no match"),false).contains("No upper-bit match"),
            "complete upper-bit no-match was not distinguished");
    }
    private static void await(StandaloneSeedCrackingWorkspace workspace) throws InterruptedException {
        long deadline=System.nanoTime()+25_000_000_000L;
        while(workspace.running() && System.nanoTime()<deadline) Thread.sleep(10);
        require(!workspace.running(),"Automatic worker exceeded probe deadline");
    }
    private static void rejects(Runnable action) {
        try { action.run();throw new AssertionError("Invalid evidence accepted"); }
        catch(IllegalArgumentException expected) {}
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
