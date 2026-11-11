package me.seedexplorer.standalone;

import java.util.ArrayList;
import java.util.List;

/** Batches player-facing collection and search updates across screen lifetimes. */
final class StandaloneSeedCrackingFeedback {
    static final long EVIDENCE_BATCH_TICKS = 100;
    static final long HEARTBEAT_TICKS = 1200;

    record Snapshot(int evidence, long structures, long bedrock, long biomes,
                    boolean enabled, boolean running, boolean automaticSearch,
                    long searchSequence, String searchStatus, String collectionProgress) {}

    private boolean initialized;
    private boolean priorEnabled;
    private boolean priorRunning;
    private long priorSearchSequence;
    private long priorEvidence=-1, priorStructures=-1, lastMessageTick=Long.MIN_VALUE;
    private boolean priorLowReady, priorUpperReady;

    void reset() {
        initialized=false;priorEnabled=false;priorRunning=false;priorSearchSequence=0;
        priorEvidence=-1;priorStructures=-1;lastMessageTick=Long.MIN_VALUE;
        priorLowReady=false;priorUpperReady=false;lastFailureStatus="";
    }

    List<String> update(long tick, Snapshot snapshot) {
        var messages=new ArrayList<String>();
        boolean lowReady=snapshot.structures()>=StandaloneSeedCrackingWorkspace.LOW48_STRUCTURE_THRESHOLD;
        boolean upperReady=snapshot.bedrock()>=StandaloneSeedCrackingWorkspace.UPPER_BEDROCK_THRESHOLD
            || snapshot.biomes()>=StandaloneSeedCrackingWorkspace.UPPER_BIOME_THRESHOLD;
        if(!initialized) {
            initialized=true;priorEnabled=snapshot.enabled();
            priorSearchSequence=snapshot.searchSequence();
            messages.add(snapshot.enabled()?"Automatic evidence collection is active.":"Automatic evidence collection is paused.");
            emitProgress(tick,snapshot,messages);
        } else {
            boolean searchEvent=false;
            if(priorEnabled!=snapshot.enabled()) {
                messages.add(snapshot.enabled()?"Automatic evidence collection resumed.":"Automatic evidence collection paused. Saved evidence is retained.");
            }
            boolean newSearch=snapshot.searchSequence()!=priorSearchSequence;
            if(newSearch && snapshot.running() || !priorRunning && snapshot.running()) {
                messages.add((snapshot.automaticSearch()?"Automatic":"Manual")+" search started: "+shortText(snapshot.searchStatus())
                    +". "+snapshot.collectionProgress());searchEvent=true;
            } else if((newSearch || priorRunning) && !snapshot.running() && !snapshot.searchStatus().isBlank()) {
                String prefix=snapshot.searchStatus().startsWith("Search failed:")?"Search error: "
                    :snapshot.searchStatus().startsWith("Search cancelled")?"Search cancelled: ":"Search finished: ";
                messages.add(prefix
                    +shortText(snapshot.searchStatus())+". "+snapshot.collectionProgress());searchEvent=true;
            } else if(!snapshot.running() && !snapshot.searchStatus().isBlank()
                    && snapshot.searchStatus().startsWith("Search failed:")
                    && !snapshot.searchStatus().equals(lastFailureStatus)) {
                messages.add("Search error: "+shortText(snapshot.searchStatus())+". "+snapshot.collectionProgress());searchEvent=true;
            }
            boolean milestone=(!priorLowReady&&lowReady)||(!priorUpperReady&&upperReady);
            boolean changed=snapshot.evidence()!=priorEvidence||snapshot.structures()!=priorStructures;
            boolean elapsed=lastMessageTick==Long.MIN_VALUE||tick-lastMessageTick>=EVIDENCE_BATCH_TICKS;
            if(!searchEvent && (milestone || changed&&elapsed || tick-lastMessageTick>=HEARTBEAT_TICKS))
                emitProgress(tick,snapshot,messages);
        }
        priorEnabled=snapshot.enabled();priorRunning=snapshot.running();priorSearchSequence=snapshot.searchSequence();
        priorLowReady=lowReady;priorUpperReady=upperReady;
        if(!snapshot.running() && snapshot.searchStatus().startsWith("Search failed:")) lastFailureStatus=snapshot.searchStatus();
        else if(snapshot.running()) lastFailureStatus="";
        return List.copyOf(messages);
    }

    private String lastFailureStatus="";
    private void emitProgress(long tick,Snapshot snapshot,List<String> messages) {
        messages.add("Evidence update: "+snapshot.collectionProgress());
        lastMessageTick=tick;priorEvidence=snapshot.evidence();priorStructures=snapshot.structures();
    }
    private static String shortText(String value) {
        if(value==null||value.isBlank())return "searching";
        return value.length()<=150?value:value.substring(0,147)+"...";
    }
}
