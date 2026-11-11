package me.seedexplorer.standalone;

import java.util.List;
import static me.seedexplorer.standalone.StandaloneSeedCrackingWorkspace.Kind;

/** Automatic search policy, independent of Minecraft/client seed state. */
final class StandaloneSeedCrackingScheduler {
    private final StandaloneSeedCrackingWorkspace workspace;
    private final Runnable persist;
    private List<?> lastLift;
    private List<?> lastUpper;
    private List<Long> lifted=List.of();
    private boolean wasRunning,lastSearchUpper;
    private long nextTick,searches;
    private String status="Collecting loaded evidence as you explore.";
    StandaloneSeedCrackingScheduler(StandaloneSeedCrackingWorkspace workspace,Runnable persist,long now) {
        this.workspace=workspace;this.persist=persist;nextTick=now+100;
    }
    String status() { return status; }
    long searches() { return searches; }
    void retry(long now) { lastLift=null;lastUpper=null;nextTick=now; }
    void paused() {
        if(wasRunning) { if(lastSearchUpper) lastUpper=null;else lastLift=null;nextTick=0; }
        wasRunning=false;
    }
    /** Settings and workspace controls share cancellation of only automatic work. */
    void disabled() {
        if(wasRunning && workspace.running() && workspace.searchAutomatic()) workspace.cancel();
        paused();
    }
    boolean candidateReady() {
        var result=workspace.outcome();return result!=null && result.complete() && result.worldSeeds().size()==1;
    }
    void tick(long now,int seconds,int threads) {
        if(workspace.running()) { status="Searching in background. "+workspace.status();return; }
        if(wasRunning) {
            wasRunning=false;
            var result=workspace.outcome();
            if(!lastSearchUpper) {
                lifted=result==null?List.of():result.lowSeeds();
                if(workspace.lowSeeds().isBlank() || workspace.automaticLowSeeds()) {
                    workspace.automaticLowSeeds(lifted);persist.run();
                }
            }
            nextTick=now+(!lastSearchUpper && !lifted.isEmpty()?20:1200);
        }
        if(candidateReady()) { status="Candidate ready. Review it in Search and candidates; application is explicit.";return; }
        if(now<nextTick) {
            status="Collecting; next automatic attempt in "+Math.max(1,(nextTick-now+19)/20)+"s. "+workspace.status();return;
        }
        var parsed=workspace.parse(workspace.evidence());
        var distinct=parsed.structures().stream().distinct().toList();
        List<?> signature=List.of(distinct,parsed.slimes(),workspace.evidence().stream().filter(e->e.kind()==Kind.PILLARS).toList());
        // Fresh low-48 constraints take priority over retrying a truncated older
        // candidate list with continuously arriving biome observations.
        if(distinct.size()>=StandaloneSeedCrackingWorkspace.LOW48_STRUCTURE_THRESHOLD && !signature.equals(lastLift)
            && (workspace.lowSeeds().isBlank() || workspace.automaticLowSeeds())) {
            lastLift=signature;start(false,now,seconds,threads);return;
        }
        List<Long> input=StandaloneSeedCrackingWorkspace.parseSeeds(workspace.lowSeeds());
        if(input.isEmpty()) input=lifted;
        boolean enoughFilters=workspace.enoughUpperBitEvidence();
        if(!input.isEmpty() && enoughFilters) {
            List<?> upperSignature=List.of(workspace.evidence(),input);
            if(!upperSignature.equals(lastUpper)) {
                lastUpper=upperSignature;
                start(true,now,seconds,threads);return;
            }
        }
        if(distinct.size()>=StandaloneSeedCrackingWorkspace.LOW48_STRUCTURE_THRESHOLD && !signature.equals(lastLift)) {
            lastLift=signature;start(false,now,seconds,threads);return;
        }
        status="Collecting: "+distinct.size()+" / 6 distinct structure starts; "
            +workspace.overworldCount(Kind.BEDROCK)+" Overworld bedrock and "+workspace.overworldCount(Kind.BIOME)+" Overworld biome samples.";
        if(workspace.outcome()!=null) status+=" Last attempt: "+workspace.status();
    }
    private void start(boolean upper,long now,int seconds,int threads) {
        workspace.start(upper,seconds,threads,true);searches++;wasRunning=true;lastSearchUpper=upper;nextTick=now+1200;
        status=upper?"Recovering upper bits automatically.":"Lifting observed structures automatically (non-exhaustive).";
    }
}
