package me.seedexplorer.standalone;

import com.google.gson.Gson;
import me.seedexplorer.seedcracking.*;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/** Evidence and bounded searches. The configured map seed is never read by the cracker. */
final class StandaloneSeedCrackingWorkspace implements AutoCloseable {
    static final int MAX_EVIDENCE = 512;
    static final int LOW48_STRUCTURE_THRESHOLD = 6;
    static final int UPPER_BEDROCK_THRESHOLD = 48;
    static final int UPPER_BIOME_THRESHOLD = 8;
    private static final Gson JSON = new Gson();
    enum Kind {
        STRUCTURE("Structure", "shipwrecks -151 271", "Structure set, start chunk X Z (not /locate block coordinates)."),
        BEDROCK("Bedrock", "overworld 0 -62 0 true", "Dimension, block X Y Z, true/false. Use untouched gradient blocks."),
        BIOME("Noise biome", "overworld 0 64 0 plains", "Dimension, block X Y Z, biome ID. Use Capture for a noise-biome sample."),
        SLIME("Slime chunk", "12 -8 true", "Chunk X Z, true/false. A missing slime spawn is not proof of false."),
        PILLARS("End pillars", "75 78 81 84 87 90 93 96 99 102", "Ten top-obsidian Y values in vanilla pillar order; Capture reads loaded pillars.");
        final String label, example, help;
        Kind(String label, String example, String help) { this.label=label; this.example=example; this.help=help; }
    }
    record Evidence(Kind kind, String text, String source) {
        Evidence(Kind kind,String text) { this(kind,text,"manual"); }
    }
    record Saved(List<Evidence> evidence, String lowSeeds, boolean automaticLowSeeds) {}
    record Outcome(List<Long> lowSeeds, List<Long> worldSeeds, boolean complete, String message) {
        Outcome { lowSeeds=List.copyOf(lowSeeds); worldSeeds=List.copyOf(worldSeeds); }
    }
    record Parsed(List<StructureObservation> structures, List<WorldSeedFilter> filters,
                  List<SlimeChunkObservation> slimes, EndPillarFingerprint pillars) {}

    private final VersionProfile profile;
    private final DimensionBiomeOracle biomeOracle;
    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Integer> additions = new ArrayList<>();
    private final AtomicLong revision = new AtomicLong();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Seed Explorer seed cracking"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t;
    });
    private Future<?> task;
    private volatile boolean running;
    private volatile String status = "Add independent observations to begin.";
    private volatile CrackingProgress progress;
    private volatile Outcome outcome;
    private volatile boolean searchAutomatic;
    private volatile boolean lastSearchUpper;
    private volatile long searchSequence;
    private String lowSeeds = "";
    private boolean automaticLowSeeds;

    StandaloneSeedCrackingWorkspace(String version, DimensionBiomeOracle biomeOracle) {
        profile=VersionProfile.parse(version); this.biomeOracle=biomeOracle;
    }
    List<Evidence> evidence() { return List.copyOf(evidence); }
    String lowSeeds() { return lowSeeds; }
    void lowSeeds(String text) { requireIdle(); parseSeeds(text); lowSeeds=text.trim();automaticLowSeeds=false;outcome=null; }
    boolean automaticLowSeeds() { return automaticLowSeeds; }
    void automaticLowSeeds(List<Long> seeds) {
        Outcome completed=outcome;
        lowSeeds(seeds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        automaticLowSeeds=true;outcome=completed;
    }
    boolean running() { return running; }
    boolean searchAutomatic() { return searchAutomatic; }
    boolean lastSearchUpper() { return lastSearchUpper; }
    long searchSequence() { return searchSequence; }
    String status() { return status; }
    CrackingProgress progress() { return progress; }
    Outcome outcome() { return outcome; }
    String version() { return profile.version().id(); }
    long count(Kind kind) { return evidence.stream().filter(e -> e.kind==kind).count(); }
    long overworldCount(Kind kind) {
        return dimensionCount(kind,Dimension.OVERWORLD);
    }
    long dimensionCount(Kind kind,Dimension dimension) {
        return evidence.stream().filter(e -> e.kind==kind
            && Dimension.parse(e.text.trim().split("[,\\s]+",2)[0])==dimension).count();
    }
    long distinctStructureCount() {
        return parse(evidence.stream().filter(e -> e.kind==Kind.STRUCTURE).toList()).structures().stream().distinct().count();
    }
    String collectionProgress() {
        long structures=distinctStructureCount();
        long bedrock=overworldCount(Kind.BEDROCK),biomes=overworldCount(Kind.BIOME);
        String low=structures>=LOW48_STRUCTURE_THRESHOLD ? "low-48 lifting ready ("+structures+" structure starts)"
            : structures+"/"+LOW48_STRUCTURE_THRESHOLD+" distinct structure starts for low-48 lifting";
        String upper=bedrock>=UPPER_BEDROCK_THRESHOLD || biomes>=UPPER_BIOME_THRESHOLD ? "upper-bit data threshold reached"
            : Math.min(UPPER_BEDROCK_THRESHOLD,bedrock)+"/"+UPPER_BEDROCK_THRESHOLD+" Overworld bedrock or "+Math.min(UPPER_BIOME_THRESHOLD,biomes)+"/"+UPPER_BIOME_THRESHOLD+" Overworld noise-biome samples for upper bits";
        return low+"; "+upper+"; "+evidence.size()+"/"+MAX_EVIDENCE+" observations saved";
    }
    boolean hasUpperBitEvidence() { return overworldCount(Kind.BEDROCK)>0 || overworldCount(Kind.BIOME)>0; }
    boolean enoughUpperBitEvidence() { return overworldCount(Kind.BEDROCK)>=UPPER_BEDROCK_THRESHOLD || overworldCount(Kind.BIOME)>=UPPER_BIOME_THRESHOLD; }
    boolean contains(Kind kind,String text) { return evidence.stream().anyMatch(e -> e.kind==kind && e.text.equals(text)); }
    boolean addObserved(Kind kind,String text,String source) {
        if(contains(kind,text) || evidence.size()>=MAX_EVIDENCE) return false;
        addAll(List.of(new Evidence(kind,text,source)));return true;
    }
    void clearEvidence() { requireIdle();evidence.clear();additions.clear();outcome=null;lowSeeds="";automaticLowSeeds=false;status="Evidence cleared. Waiting for new loaded observations."; }
    /** Previous client-level checks admitted placeholder biomes and unloaded competing hulls. */
    int removeLegacyClientCaptures() {
        requireIdle();int before=evidence.size();
        evidence.removeIf(item -> "loaded-noise-biome".equals(item.source())
            || "recognized-shipwreck-26.3".equals(item.source()));
        int removed=before-evidence.size();
        if(removed>0) {
            additions.clear();outcome=null;
            if(automaticLowSeeds) {lowSeeds="";automaticLowSeeds=false;}
            status="Discarded "+removed+" observations from the old client loading check; exploring recollects them.";
        }
        return removed;
    }

    void add(Kind kind, String text) {
        addAll(List.of(new Evidence(kind, text.trim())));
    }
    void addAll(List<Evidence> entries) {
        requireIdle();
        if(entries.isEmpty()) throw new IllegalArgumentException("No new observations to record.");
        if(evidence.size()+entries.size() > MAX_EVIDENCE) throw new IllegalArgumentException("Maximum 512 observations reached; capture was not saved.");
        var combined=new ArrayList<>(evidence);
        for(Evidence entry:entries) {
            if(combined.contains(entry)) throw new IllegalArgumentException("That observation is already recorded.");
            combined.add(entry);
        }
        parse(combined);
        // Contradictory evidence remains visible and is never silently replaced.
        if(combined.stream().filter(e -> e.kind == Kind.PILLARS).count()>1)
            throw new IllegalArgumentException("Remove the previous pillar observation before replacing it.");
        evidence.addAll(entries); additions.add(entries.size());
        outcome=null; status="Recorded " + evidence.size() + " observation(s).";
    }
    void removeLast() {
        requireIdle();
        int count=additions.isEmpty() ? Math.min(1,evidence.size()) : additions.removeLast();
        for(int i=0;i<count;i++) evidence.removeLast();
        outcome=null; status="Removed " + count + " observation(s).";
    }
    String save() { return JSON.toJson(new Saved(List.copyOf(evidence), lowSeeds, automaticLowSeeds)); }
    void load(String json) {
        requireIdle();
        if(json == null || json.isBlank()) return;
        try {
            if(json.length() > 200_000) throw new IllegalArgumentException("Saved evidence exceeds the size limit.");
            Saved saved=JSON.fromJson(json, Saved.class);
            if(saved == null || saved.evidence == null || saved.evidence.size() > MAX_EVIDENCE)
                throw new IllegalArgumentException("Invalid saved evidence.");
            parse(saved.evidence); parseSeeds(saved.lowSeeds == null ? "" : saved.lowSeeds);
            evidence.clear(); evidence.addAll(saved.evidence); additions.clear(); outcome=null;
            lowSeeds=saved.lowSeeds == null ? "" : saved.lowSeeds;
            automaticLowSeeds=saved.automaticLowSeeds;
            status="Loaded " + evidence.size() + " observation(s) for this world/version.";
        } catch(RuntimeException failure) { status="Saved evidence could not be loaded: " + failure.getMessage(); }
    }
    private void requireIdle() { if(running) throw new IllegalStateException("Cancel the current search before editing evidence."); }

    static List<Long> parseSeeds(String text) {
        if(text == null || text.isBlank()) return List.of();
        var seeds=new LinkedHashSet<Long>();
        for(String part : text.trim().split("[,\\s]+")) {
            long value=Long.parseLong(part);
            if(value < 0 || value > Lcg48.MASK) throw new IllegalArgumentException("Low-48 seeds must be 0..281474976710655.");
            seeds.add(value);
        }
        if(seeds.size()>32) throw new IllegalArgumentException("At most 32 low-48 candidates per search.");
        return List.copyOf(seeds);
    }
    private static String id(String text) { return text.contains(":") ? text : "minecraft:"+text; }
    private static boolean truth(String value) {
        if(!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("Use true or false for the final value.");
        return Boolean.parseBoolean(value);
    }
    private static int coordinate(String value, int limit) {
        int number=Integer.parseInt(value);
        if(Math.abs((long)number)>limit) throw new IllegalArgumentException("Coordinate exceeds the vanilla world border.");
        return number;
    }
    Parsed parse(List<Evidence> entries) {
        var structures=new ArrayList<StructureObservation>();
        var filters=new ArrayList<WorldSeedFilter>();
        var slimes=new ArrayList<SlimeChunkObservation>();
        var biomes=new ArrayList<DimensionBiomeObservation>();
        var bedrock=new java.util.EnumMap<Dimension,List<BedrockObservation>>(Dimension.class);
        EndPillarFingerprint pillars=null;
        for(Evidence entry:entries) {
            if(entry == null || entry.kind == null || entry.text == null) throw new IllegalArgumentException("Invalid observation.");
            String[] parts=entry.text.trim().split("[,\\s]+");
            switch(entry.kind) {
                case STRUCTURE -> {
                    count(parts,3); PlacementSpec spec=profile.placements().byId(id(parts[0]));
                    if(spec==null) throw new IllegalArgumentException("Unknown structure set. Try shipwrecks, desert_pyramids, ocean_ruins, igloos, jungle_temples or pillager_outposts.");
                    if(spec.range()==1) throw new IllegalArgumentException("This structure needs a frequency adapter, which is not integrated. Use a random-spread structure such as shipwrecks.");
                    structures.add(spec.observe("",coordinate(parts[1],1_875_000),coordinate(parts[2],1_875_000)));
                }
                case BEDROCK -> {
                    count(parts,5); Dimension dimension=Dimension.parse(parts[0]);
                    int x=coordinate(parts[1],30_000_000),y=Integer.parseInt(parts[2]),z=coordinate(parts[3],30_000_000);
                    boolean gradient = dimension == Dimension.OVERWORLD ? y >= -63 && y <= -60
                        : dimension == Dimension.NETHER && (y >= 1 && y <= 4 || y >= 123 && y <= 126);
                    if(!gradient) throw new IllegalArgumentException("Use Overworld Y -63..-60 or Nether Y 1..4 / 123..126. End bedrock has no supported gradient.");
                    bedrock.computeIfAbsent(dimension, ignored -> new ArrayList<>())
                        .add(new BedrockObservation(x,y,z,truth(parts[4])));
                }
                case BIOME -> {
                    count(parts,5);
                    if(profile.version()!=MinecraftVersion.V26_3) throw new IllegalArgumentException("The integrated noise-biome adapter supports 26.3 only.");
                    if(biomeOracle==null) throw new IllegalArgumentException("Noise-biome adapter is unavailable.");
                    biomes.add(new DimensionBiomeObservation(Dimension.parse(parts[0]),coordinate(parts[1],30_000_000),
                        coordinate(parts[2],2048),coordinate(parts[3],30_000_000),id(parts[4])));
                }
                case SLIME -> { count(parts,3); slimes.add(new SlimeChunkObservation(coordinate(parts[0],1_875_000),coordinate(parts[1],1_875_000),truth(parts[2]))); }
                case PILLARS -> {
                    if(pillars!=null) throw new IllegalArgumentException("Only one pillar observation is supported.");
                    count(parts,10); int[] heights=new int[10];
                    for(int i=0;i<10;i++) heights[i]=Integer.parseInt(parts[i]);
                    pillars=EndPillarFingerprint.fromTopBlockHeights(heights);
                }
            }
        }
        // Cheap bedrock checks precede expensive biome worldgen; one oracle per dimension.
        bedrock.forEach((dimension, observations) -> filters.add(new BedrockFilter(profile.bedrockOracle(dimension), observations)));
        if(!biomes.isEmpty()) filters.add(new DimensionBiomeFilter(biomeOracle,biomes));
        return new Parsed(List.copyOf(structures),List.copyOf(filters),List.copyOf(slimes),pillars);
    }
    private static void count(String[] fields,int expected) {
        if(fields.length!=expected) throw new IllegalArgumentException("Expected " + expected + " space-separated values; see the example above.");
    }

    synchronized void start(boolean upperBits, int seconds, int threads) {
        start(upperBits, seconds, threads, false);
    }
    synchronized void start(boolean upperBits, int seconds, int threads, boolean automatic) {
        requireIdle();
        Parsed parsed=parse(evidence());
        List<Long> input=parseSeeds(lowSeeds);
        if(upperBits && input.isEmpty() && outcome!=null) input=outcome.lowSeeds;
        if(upperBits && input.isEmpty()) throw new IllegalArgumentException("Enter low-48 candidates or lift structure observations first.");
        if(upperBits && parsed.filters.isEmpty()) throw new IllegalArgumentException("Add bedrock or noise-biome observations to constrain the upper 16 bits.");
        if(upperBits && !hasUpperBitEvidence()) throw new IllegalArgumentException("Add Overworld bedrock or noise-biome observations for upper-bit recovery. Nether/End legacy randomness cannot distinguish all upper halves.");
        final List<Long> known=input;
        long request=revision.incrementAndGet(); running=true; outcome=null; progress=null;
        searchAutomatic=automatic; lastSearchUpper=upperBits;searchSequence++;
        status=upperBits ? "Searching upper 16 bits..." : "Lifting observed structure starts...";
        int budget=Math.max(10,Math.min(300,seconds)), workers=Math.max(1,Math.min(4,threads));
        task=executor.submit(() -> {
            try {
                ProgressListener listener=value -> {
                    synchronized (this) { if(revision.get()==request) progress=value; }
                };
                Outcome result;
                if(!upperBits) {
                    LiftResult lift=StructureSeedLifter.lift(parsed.structures,LiftOptions.builder()
                        .threads(workers).timeBudgetMillis(budget*1000L).maxResults(32)
                        .pillarFingerprint(parsed.pillars).slimeObservations(parsed.slimes).progressListener(listener).build());
                    // The research sieve omits rare nextInt rejection paths. Even a fully
                    // traversed sieve is not an exhaustive search of all 48-bit seeds.
                    result=new Outcome(lift.structureSeeds(),List.of(),false,
                        liftFeedback(lift));
                } else {
                    List<Long> accepted=known.stream().filter(seed -> SeedCracking.verifyStructures(seed,parsed.structures)
                        && parsed.slimes.stream().allMatch(s -> s.matches(seed))
                        && (parsed.pillars==null || parsed.pillars.matchesStructureSeed(seed))).toList();
                    RecoveryResult recovered=UpperBitRecovery.recover(accepted,parsed.filters,RecoveryOptions.builder()
                        .threads(workers).timeBudgetMillis(budget*1000L).maxResults(64)
                        .maxCandidates(32L*65536).progressListener(listener).build());
                    result=new Outcome(accepted,recovered.worldSeeds(),recovered.candidatesTested()==accepted.size()*65536L,
                        upperFeedback(accepted,recovered,automaticLowSeeds));
                }
                synchronized (this) {
                    if(revision.get()==request) { outcome=result; status=result.message; }
                }
            } catch(Throwable failure) {
                synchronized (this) {
                    if(revision.get()==request) status="Search failed: "+failure.getClass().getSimpleName()+": "+failure.getMessage();
                }
            } finally { synchronized (this) { if(revision.get()==request) running=false; } }
        });
    }
    synchronized void cancel() {
        revision.incrementAndGet(); if(task!=null) task.cancel(true);
        running=false; progress=null; status="Search cancelled. Evidence is saved; no seed was changed.";
    }
    static String liftFeedback(LiftResult lift) {
        if(lift.status()==LiftResult.Status.BUDGET_EXHAUSTED) return "Low-48 search stopped at its budget with "+lift.structureSeeds().size()
            +" candidate(s). This was partial; collect another independent structure start or retry with a larger budget.";
        if(lift.status()==LiftResult.Status.NEEDS_MORE_OBSERVATIONS) return "Low-48 search needs more independent structure information; no search space was completed. Add another valid structure start.";
        if(lift.structureSeeds().isEmpty()) return "No low-48 match in the non-exhaustive search. Collect another independent structure start and verify the recorded starts; this does not rule out every seed.";
        return "Found "+lift.structureSeeds().size()+" low-48 candidate(s). Lifting is non-exhaustive; upper-bit recovery needs Overworld bedrock or noise-biome samples.";
    }
    static String upperFeedback(List<Long> accepted,RecoveryResult recovery,boolean automaticInput) {
        if(accepted.isEmpty()) return "No supplied low-48 candidate matches the structure, slime and pillar evidence. Verify the evidence and relift; other low-48 seeds were not tested.";
        if(recovery.worldSeeds().isEmpty()) {
            if(recovery.candidatesTested()<accepted.size()*65536L) return "Upper-bit search stopped at its budget without a match. Some supplied upper halves remain untested; retry with a larger budget.";
            return "No upper-bit match for the supplied low-48 candidate(s). "+(automaticInput
                ? "The low-48 list came from a non-exhaustive lift; collect another structure start and relift."
                : "Verify the evidence or supply other low-48 candidates; other low-48 seeds were not tested.");
        }
        return "Found "+recovery.worldSeeds().size()+" full-seed candidate(s) for these inputs. Validate against independent observations before applying.";
    }
    String report() {
        StringBuilder text=new StringBuilder("Seed Explorer cracking candidates (experimental)\nVersion: "+version()+"\n");
        for(Evidence item:evidence) text.append(item.kind).append(' ').append(item.text).append(" [").append(item.source==null?"manual":item.source).append("]\n");
        text.append("\n").append(status).append('\n');
        if(outcome!=null) text.append("Search complete: ").append(outcome.complete)
            .append("\nLow-48 candidates: ").append(outcome.lowSeeds).append("\nWorld candidates: ").append(outcome.worldSeeds)
            .append("\nCandidates require independent hold-out validation.\n");
        return text.toString();
    }
    @Override public void close() { cancel(); executor.shutdownNow(); }
}
