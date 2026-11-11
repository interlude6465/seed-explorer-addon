package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.*;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Cancellable, bounded searches with no access to live client state. */
public final class StandaloneSearch {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30,
        TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, "Seed Explorer search");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());

    public record Result(int dimension, int x, int y, int z, String label,
                         GeneratedStructure structure, ChestLootOutput chest) { }
    public static final class Job {
        private volatile boolean cancelled;
        private volatile boolean fulfilled;
        private volatile boolean lootSearch;
        private volatile String terminalStatus;
        public volatile String status = "Searching...";
        public volatile List<Result> results = List.of();
        public volatile List<Result> previews = List.of();
        public volatile Map<String,Long> remaining = Map.of();
        public volatile int regionsChecked,dungeonChunksChecked,structuresChecked,containersChecked;
        public volatile long totalContributing;
        public volatile boolean infinite;
        public volatile String coverage="";
        public volatile boolean dungeonLimited;
        public volatile boolean failed;
        int dungeonLimit=Integer.MAX_VALUE;
        long dungeonDeadline=Long.MAX_VALUE;
        public volatile boolean done;
        public void cancel() { cancelled = true;status="Cancelled";done=true; }
        public boolean cancelled() {return cancelled;}
        public String requestState(ItemLootRequest request) {
            if(cancelled)return "Cancelled";
            if(failed)return "Error";
            long left=remaining.getOrDefault(request.term().signature(),(long)request.quantity());
            if(left<=0)return dungeonLimited || infinite?"Found; nearest unverified":"Found";
            if(!done)return "Searching";
            return left<request.quantity() || dungeonLimited?"Partial":"No match in radius";
        }
    }
    private static volatile Job active;
    private StandaloneSearch() { }

    private static synchronized Job submit(Consumer<Job> action) {
        if (active != null && !active.done) active.cancel();
        Job job = new Job();
        active = job;
        WORKER.getQueue().clear();
        WORKER.execute(() -> {
            try {
                if (!job.cancelled) action.accept(job);
                if (!job.cancelled) job.status = job.terminalStatus != null ? job.terminalStatus : job.lootSearch
                    ? job.fulfilled ? "Predicted request in " + job.results.size() + " chests"
                        : "No complete match: " + job.containersChecked + " containers in "+job.structuresChecked+" structures; "+job.coverage
                    : job.results.isEmpty() ? "No matches in this search radius."
                        : job.results.size() + " results";
            } catch (RuntimeException failure) {
                if(!job.cancelled) {
                    job.failed=true;
                    job.status = "Search failed: " + (failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage());
                    failure.printStackTrace(System.err);
                }
            } finally {
                if(!job.cancelled && job.lootSearch)job.status+=" | "+job.coverage+"; "+job.structuresChecked+" structures, "+job.containersChecked+" containers checked";
                job.done = true;
            }
        });
        return job;
    }

    public static Job structures(PredictionContext context, int x, int z, int radius, String query) {
        StructureType type = resolveStructure(context, query);
        return submit(job -> {
            if (type == null) throw new IllegalArgumentException("Unknown structure: " + query);
            findStructures(context, x, z, radius, type, job);
        });
    }

    static StructureType resolveStructure(PredictionContext context, String query) {
        return Arrays.stream(StructureType.values())
            .filter(t -> t.dimension == context.dimension()
                && PredictionVersionPolicy.supportsStructure(context.minecraftVersion(), t))
            .filter(t -> structureScore(t, query) >= 60)
            .max(Comparator.comparingInt(t -> structureScore(t, query))).orElse(null);
    }
    private static int structureScore(StructureType type, String query) {
        int best = Math.max(matchScore(query, type.name()), matchScore(query, type.displayName));
        for (String alias : aliases(type)) best = Math.max(best, matchScore(query, alias));
        return best;
    }
    static int matchScore(String query, String target) {
        String a = normalize(query), b = normalize(target);
        if (a.isEmpty()) return 0;
        if (a.equals(b)) return 1000;
        if (b.startsWith(a)) return 260 - b.length();
        if (b.contains(a)) return 210 - b.length();
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1]; current[0] = i;
            for (int j = 1; j <= b.length(); j++) current[j] = Math.min(Math.min(current[j - 1] + 1,
                previous[j] + 1), previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            previous = current;
        }
        int score = 100 - previous[b.length()] * 12 - Math.max(0, b.length() - a.length()) * 2;
        int index = 0;
        for (int j = 0; j < b.length() && index < a.length(); j++) if (a.charAt(index) == b.charAt(j)) index++;
        return index == a.length() ? Math.max(score, 120 - b.length()) : score;
    }
    private static void findStructures(PredictionContext context, int x, int z, int radius, StructureType type, Job job) {
        List<Result> found = new ArrayList<>();
        scan(context, x, z, radius, type, job, structure -> {
            found.add(new Result(context.dimension(), structure.x, structure.y, structure.z,
                structure.displayName(), structure, null));
            publish(job, found, x, z);
        });
    }
    public static Job natural(PredictionContext context, int x, int z, int radius, String query) {
        return submit(job -> {
            StructureType type = resolveStructure(context, query);
            String biome = resolveBiome(context, query);
            int biomeScore = biome == null ? 0 : matchScore(query, biome);
            if (type != null && structureScore(type, query) >= biomeScore)
                findStructures(context, x, z, radius, type, job);
            else if (biome != null) findBiomes(context, x, z, radius, biome, job);
            else throw new IllegalArgumentException("No structure or biome matches '" + query + "'");
        });
    }
    static String resolveBiome(PredictionContext context, String query) {
        return WorldgenEngine.offlineRegistryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
            .keySet().stream().map(Object::toString)
            .filter(id -> PredictionVersionPolicy.supportsBiome(context.minecraftVersion(), id))
            .filter(id -> matchScore(query, id) >= 60)
            .max(Comparator.comparingInt(id -> matchScore(query, id))).orElse(null);
    }
    public static Job biomes(PredictionContext context, int x, int z, int radius, String query) {
        return submit(job -> {
            String target = resolveBiome(context, query);
            if (target == null) throw new IllegalArgumentException("Unknown biome: " + query);
            findBiomes(context, x, z, radius, target, job);
        });
    }
    private static void findBiomes(PredictionContext context, int x, int z, int radius, String query, Job job) {
            String target = normalize(query);
            List<Result> found = new ArrayList<>();
            int step = 32;
            int rings = Math.max(1, Math.min(8192, radius)) / step;
            boolean cave = target.contains("cave") || target.equals("deep dark");
            for (int ring = 0; ring <= rings && !job.cancelled && found.size() < 64; ring++) {
                job.status = "Sampling biomes: " + ring * step + " / " + radius + " blocks";
                for (int dz = -ring; dz <= ring && !job.cancelled; dz++) {
                    for (int dx = -ring; dx <= ring; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        int bx = x + dx * step, bz = z + dz * step;
                        for (int y = cave ? 48 : 64; y >= (cave ? -48 : 64); y -= 16) {
                            PredictedBiome biome = WorldgenEngine.getBiome(context.worldSeed(),
                                context.dimension(), bx, y, bz);
                            if (PredictionVersionPolicy.supportsBiome(context.minecraftVersion(), biome.id())
                                && (normalize(biome.id()).equals(target)
                                || normalize(biome.displayName()).equals(target))) {
                                found.add(new Result(context.dimension(), bx, y, bz,
                                    biome.displayName(), null, null));
                                break;
                            }
                        }
                        if (found.size() >= 64) break;
                    }
                }
                publish(job, found, x, z);
            }
    }

    public static Job loot(PredictionContext context, int x, int z, int radius,
                           List<ItemLootRequest> requests, boolean allDimensions) {
        List<ItemLootRequest> wanted = List.copyOf(requests);
        return submit(job -> {
            job.lootSearch = true;
            job.infinite=radius==0;
            // A finite search gets a bounded dungeon budget because dungeon
            // decoration is substantially more expensive than structure starts.
            // Infinite mode must keep progressing outward until it finds the
            // request or is cancelled; inheriting that finite cap made an
            // otherwise valid dungeon-only search stop after 32 chunks.
            job.dungeonLimit=job.infinite ? Integer.MAX_VALUE : 32;
            List<Result> found = new ArrayList<>();
            Map<String, Long> remaining = new LinkedHashMap<>();
            Map<String, LootSearchTerm> terms = new LinkedHashMap<>();
            for (ItemLootRequest request : wanted) {
                remaining.merge(request.term().signature(), (long) request.quantity(), Long::sum);
                terms.put(request.term().signature(), request.term());
            }
            if(wanted.size()>64 || remaining.values().stream().mapToLong(Long::longValue).sum()>147456)
                throw new IllegalArgumentException("At most64 item requests and147456 total items per search.");
            job.remaining=Map.copyOf(remaining);
            Set<String> seenChests = new HashSet<>();
            Set<String> supportedTerms = new HashSet<>();
            int[] dimensions = allDimensions ? new int[] {context.dimension(),
                context.dimension() == 0 ? -1 : 0, context.dimension() == 1 ? -1 : 1}
                : new int[] {context.dimension()};
            Map<Integer, Set<StructureType>> candidatesByDimension = new HashMap<>();
            for (int dim : dimensions) candidatesByDimension.put(dim, EnumSet.noneOf(StructureType.class));
            for (ItemLootRequest request : wanted) {
                for (StructureType type : candidateStructureTypes(context, request.term())) {
                    Set<StructureType> eligible = candidatesByDimension.get(type.dimension);
                    if (eligible == null) continue;
                    eligible.add(type);
                    supportedTerms.add(request.term().signature());
                }
            }
            job.coverage=context.minecraftVersion()+"; "+(job.infinite?"Infinite outward search":radius+"-block radius")+"; "+candidatesByDimension.entrySet().stream()
                .filter(e->!e.getValue().isEmpty()).map(e->(e.getKey()==1?"End":e.getKey()==-1?"Nether":"Overworld")+" "+e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
            if(job.infinite) {
                if(supportedTerms.size()!=terms.size())throw new IllegalArgumentException("A requested item/enchantment has no supported loot pool in selected dimensions.");
                searchInfinite(context,x,z,candidatesByDimension,terms,remaining,seenChests,found,job);return;
            }
            List<LootSearchTile> tiles = new ArrayList<>();
            int boundedRadius = Math.max(16, Math.min(8192, radius));
            for (int dim : dimensions) {
                Set<StructureType> types = candidatesByDimension.get(dim);
                if (types.isEmpty()) continue;
                int ox = convertCoordinate(x, context.dimension(), dim);
                int oz = convertCoordinate(z, context.dimension(), dim);
                int minX = Math.floorDiv(ox - boundedRadius, 16), maxX = Math.floorDiv(ox + boundedRadius, 16);
                int minZ = Math.floorDiv(oz - boundedRadius, 16), maxZ = Math.floorDiv(oz + boundedRadius, 16);
                Set<StructureType> starts = EnumSet.copyOf(types);
                starts.remove(StructureType.DUNGEON);
                for (int cz = minZ; cz <= maxZ; cz += 32) for (int cx = minX; cx <= maxX; cx += 32) {
                    if (!starts.isEmpty()) tiles.add(new LootSearchTile(context.withDimension(dim), ox, oz,
                        boundedRadius, cx, cz, Math.min(maxX, cx + 31), Math.min(maxZ, cz + 31), starts));
                    if (types.contains(StructureType.DUNGEON)) tiles.add(new LootSearchTile(
                        context.withDimension(dim), ox, oz, boundedRadius, cx, cz,
                        Math.min(maxX, cx + 31), Math.min(maxZ, cz + 31), Set.of(StructureType.DUNGEON)));
                }
            }
            // Publish useful structure inventories before expensive dungeon decoration.
            // These are explicitly provisional; the ordered pass owns final allocation.
            Map<String,Long> previewRemaining=new LinkedHashMap<>(remaining);
            var previewJob=new Job();previewJob.lootSearch=true;
            var previewFound=new ArrayList<Result>();var previewSeen=new HashSet<String>();
            boolean includesDungeons=tiles.stream().anyMatch(t->t.types().contains(StructureType.DUNGEON));
            long previewDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);int previewCandidates=0;
            for(var tile:tiles.stream().filter(t->includesDungeons && !t.types().contains(StructureType.DUNGEON))
                    .sorted(Comparator.comparingDouble(LootSearchTile::minimumDistanceSquared)).limit(8).toList()) {
                if(job.cancelled || previewJob.fulfilled || previewCandidates>=16 || System.nanoTime()>=previewDeadline)break;
                job.status="Checking structure loot; nearby dungeons follow";
                for(var structure:loadLootTile(tile,job).stream().filter(s->tile.types().contains(s.type))
                    .filter(s->distance(s.x,s.z,tile.originX(),tile.originZ())<=(double)tile.radius()*tile.radius())
                    .sorted(Comparator.comparingDouble(s->distance(s.x,s.z,tile.originX(),tile.originZ()))).toList()) {
                    if(job.cancelled || previewJob.fulfilled || previewCandidates++>=16 || System.nanoTime()>=previewDeadline)break;
                    matchLoot(tile.context(),structure,terms,previewRemaining,previewSeen,previewFound,previewJob,context.dimension(),x,z);
                    if(!job.cancelled)job.previews=previewJob.results;
                }
            }
            job.dungeonDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            visitNearestCandidates(tiles, job, tile -> loadLootTile(tile, job),
                candidate -> matchLoot(candidate.context(), candidate.structure(), terms,
                    remaining, seenChests, found, job, context.dimension(), x, z));
            if(job.dungeonLimited && !job.cancelled)job.terminalStatus=(job.fulfilled?"Found requested items":"Partial search")
                +"; nearby dungeon coverage limited ("+job.dungeonChunksChecked+" unique chunks); nearest not guaranteed";
            if (!job.cancelled && !job.fulfilled && found.size() >= 64)
                job.terminalStatus = "Stopped at 64 contributing chests; request incomplete";
            if (!job.cancelled && !job.fulfilled && supportedTerms.size() < terms.size()) {
                String unsupported = terms.entrySet().stream()
                    .filter(entry -> !supportedTerms.contains(entry.getKey()))
                    .map(entry -> entry.getValue().display()).reduce((a, b) -> a + ", " + b).orElse("");
                job.terminalStatus = "Unsupported loot pool: " + unsupported;
            }
        });
    }

    record LootSearchTile(PredictionContext context, int originX, int originZ, int radius,
                          int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                          Set<StructureType> types) {
        double minimumDistanceSquared() {
            double nearX = Math.max(minChunkX * 16.0, Math.min(originX, maxChunkX * 16.0 + 15));
            double nearZ = Math.max(minChunkZ * 16.0, Math.min(originZ, maxChunkZ * 16.0 + 15));
            return (nearX - originX) * (nearX - originX) + (nearZ - originZ) * (nearZ - originZ);
        }
    }
    record LootCandidate(PredictionContext context, GeneratedStructure structure, double distanceSquared) { }

    private static List<GeneratedStructure> loadLootTile(LootSearchTile tile, Job job) {
        if (tile.types().equals(Set.of(StructureType.DUNGEON))) {
            job.status = "Dungeon " + job.dungeonChunksChecked + "/"+job.dungeonLimit+" unique chunks; now "
                + tile.minChunkX() + ", " + tile.minChunkZ()+"; "+job.previews.size()+" provisional structure results";
            var rooms=VanillaLootStructureSimulator.simulateDungeons(tile.context().worldSeed(),tile.minChunkX(),tile.minChunkZ());
            var starts=new ArrayList<GeneratedStructure>();
            for(var room:rooms) {
                if(job.cancelled)break;
                var structure=new GeneratedStructure(room.x(),room.y(),room.z(),tile.minChunkX(),tile.minChunkZ(),
                    StructureType.DUNGEON,room.spawnerEntityId(),false);
                var outputs=new ArrayList<ChestLootOutput>();
                for(var container:room.containers()) {
                    var items=LootTableSimulator.simulateVanilla(tile.context(),container.lootTableId(),container.lootSeed(),container.x(),container.y(),container.z());
                    if(items==null) {
                        var profile=BundledLootTableLoader.VersionProfile.forVersion(tile.context().minecraftVersion());
                        var fallback=VanillaLootTables.get(profile,container.lootTableId());
                        items=fallback==null?List.of():LootTableSimulator.simulate(profile,fallback,container.lootSeed(),64);
                    }
                    outputs.add(new ChestLootOutput(container.x(),container.y(),container.z(),container.lootTableId(),items,container.lootSeed(),false));
                }
                if(job.cancelled)break;
                // Reuse containers already captured during discovery; prediction used
                // to replay the same expensive decoration once more per found room.
                StandalonePersistentLootCache.put(tile.context(),structure,outputs);starts.add(structure);
            }
            return List.copyOf(starts);
        }
        return StandalonePredictionCache.predictDimension(tile.context(), tile.minChunkX(), tile.minChunkZ(),
            tile.maxChunkX(), tile.maxChunkZ(), tile.types().contains(StructureType.TREASURE),
            tile.types().contains(StructureType.MINESHAFT), () -> job.cancelled);
    }

    /** Drain candidates only after every nearer tile has been examined, across all selected dimensions. */
    static void visitNearestCandidates(List<LootSearchTile> tiles, Job job,
            java.util.function.Function<LootSearchTile, List<GeneratedStructure>> load,
            Consumer<LootCandidate> visit) {
        var ordered = new PriorityQueue<LootSearchTile>(
            Comparator.comparingDouble(LootSearchTile::minimumDistanceSquared)
                .thenComparingInt(tile -> tile.context().dimension())
                .thenComparingInt(LootSearchTile::minChunkX).thenComparingInt(LootSearchTile::minChunkZ));
        ordered.addAll(tiles);
        var pending = new PriorityQueue<LootCandidate>(Comparator.comparingDouble(LootCandidate::distanceSquared)
            .thenComparingInt(candidate -> candidate.context().dimension())
            .thenComparingInt(candidate -> candidate.structure().x)
            .thenComparingInt(candidate -> candidate.structure().z)
            .thenComparing(candidate -> candidate.structure().type.name()));
        int checked = 0;
        Set<String> seenTiles=new HashSet<>(),seenCandidates=new HashSet<>();
        while (!ordered.isEmpty() || !pending.isEmpty()) {
            if (job.cancelled || job.fulfilled || !job.infinite && job.results.size() >= 64) return;
            if (!pending.isEmpty() && (ordered.isEmpty()
                || pending.peek().distanceSquared() < ordered.peek().minimumDistanceSquared())) {
                visit.accept(pending.remove());
                continue;
            }
            LootSearchTile tile = ordered.remove();
            String tileKey=tile.context().dimension()+":"+tile.minChunkX()+":"+tile.minChunkZ()+":"+tile.maxChunkX()+":"+tile.maxChunkZ()+":"+tile.types();
            if(!seenTiles.add(tileKey))continue;
            if (tile.minimumDistanceSquared() > (double) tile.radius() * tile.radius()) continue;
            if(tile.types().equals(Set.of(StructureType.DUNGEON))
                && (job.dungeonChunksChecked>=job.dungeonLimit || System.nanoTime()>=job.dungeonDeadline)) {
                job.dungeonLimited=true;continue;
            }
            // Split expensive dungeon decoration lazily. This keeps nearby rooms in the
            // same distance ordering without replaying whole 32-chunk regions up front.
            if (tile.types().equals(Set.of(StructureType.DUNGEON))
                && (tile.minChunkX() != tile.maxChunkX() || tile.minChunkZ() != tile.maxChunkZ())) {
                int midX = tile.minChunkX() + (tile.maxChunkX() - tile.minChunkX()) / 2;
                int midZ = tile.minChunkZ() + (tile.maxChunkZ() - tile.minChunkZ()) / 2;
                for (int cz : new int[] {tile.minChunkZ(), midZ + 1})
                    for (int cx : new int[] {tile.minChunkX(), midX + 1}) {
                        if (cx > tile.maxChunkX() || cz > tile.maxChunkZ()) continue;
                        ordered.add(new LootSearchTile(tile.context(), tile.originX(), tile.originZ(),
                            tile.radius(), cx, cz, cx == tile.minChunkX() ? midX : tile.maxChunkX(),
                            cz == tile.minChunkZ() ? midZ : tile.maxChunkZ(), tile.types()));
                    }
                continue;
            }
            if(tile.types().equals(Set.of(StructureType.DUNGEON))) {
                if(job.dungeonChunksChecked>=job.dungeonLimit || System.nanoTime()>=job.dungeonDeadline) {
                    job.dungeonLimited=true;continue;
                }
                job.dungeonChunksChecked++;
            } else job.regionsChecked++;
            job.status = "Finding loot: " + (++checked) + " unique regions/chunks; "+job.structuresChecked+" structures, "+job.containersChecked+" containers; "+job.coverage;
            for (GeneratedStructure structure : load.apply(tile)) {
                if (!tile.types().contains(structure.type)) continue;
                if(!seenCandidates.add(tile.context().dimension()+":"+structure.type+":"+structure.x+":"+structure.y+":"+structure.z))continue;
                double distance = distance(structure.x, structure.z, tile.originX(), tile.originZ());
                if (distance <= (double) tile.radius() * tile.radius())
                    pending.add(new LootCandidate(tile.context(), structure, distance));
            }
        }
    }

    static void matchLoot(PredictionContext scoped, GeneratedStructure structure,
                                  Map<String, LootSearchTerm> terms, Map<String, Long> remaining,
                                  Set<String> seenChests, List<Result> found, Job job,
                                  int originDimension, int x, int z) {
        if (job.cancelled || job.fulfilled || !job.infinite && found.size() >= 64) return;
        job.structuresChecked++;
        List<ChestLootOutput> chests = StandalonePersistentLootCache.get(scoped, structure)
            .orElseGet(() -> {
                List<ChestLootOutput> predicted = StandaloneStructureLootPredictor.predict(scoped, structure, 64);
                StandalonePersistentLootCache.put(scoped, structure, predicted);
                return predicted;
            });
        for (ChestLootOutput chest : chests) {
            if (job.cancelled || job.fulfilled || !job.infinite && found.size() >= 64) break;
            String key = scoped.dimension() + ":" + chest.blockX() + ":" + chest.blockY()
                + ":" + chest.blockZ();
            if (seenChests.contains(key)) continue;
            job.containersChecked++;
            List<String> contributions = takeMatchingLoot(chest, terms, remaining);
            if (contributions.isEmpty()) continue;
            seenChests.add(key);job.totalContributing++;
            found.add(new Result(scoped.dimension(), chest.blockX(), chest.blockY(), chest.blockZ(),
                structure.displayName() + ": " + String.join(", ", contributions)
                    + (chest.exact() ? " (exact)" : " (approximate)"), structure, chest));
            job.fulfilled = remaining.values().stream().allMatch(value -> value <= 0);
        }
        publishAcrossDimensions(job, found, originDimension, x, z);
        if(!job.cancelled)job.remaining=Map.copyOf(remaining);
    }

    /** One region is produced at a time; memory does not grow with search radius. */
    static final class OutwardTiles implements java.util.Iterator<int[]> {
        private final int originX,originZ,maxRing;private int ring,index;
        OutwardTiles(int blockX,int blockZ){this(blockX,blockZ,512);}
        OutwardTiles(int blockX,int blockZ,int tileBlocks){
            originX=Math.floorDiv(blockX,tileBlocks);originZ=Math.floorDiv(blockZ,tileBlocks);
            maxRing=(60_000_000+tileBlocks-1)/tileBlocks;
        }
        public boolean hasNext(){return ring<=maxRing;}
        public int[] next(){
            if(!hasNext())throw new NoSuchElementException();
            int dx=0,dz=0,r=ring;
            if(r>0) {
                int side=index/(2*r),offset=index%(2*r);
                switch(side){case 0->{dx=-r+offset;dz=-r;}case 1->{dx=r;dz=-r+offset;}
                    case 2->{dx=r-offset;dz=r;}default->{dx=-r;dz=r-offset;}}
            }
            if(++index>=(r==0?1:8*r)){ring++;index=0;}
            return new int[]{originX+dx,originZ+dz,r};
        }
    }
    private static void searchInfinite(PredictionContext context,int x,int z,Map<Integer,Set<StructureType>> pools,
            Map<String,LootSearchTerm> terms,Map<String,Long> remaining,Set<String> seenChests,List<Result> found,Job job) {
        searchInfinite(context,x,z,pools,terms,remaining,seenChests,found,job,tile->loadLootTile(tile,job));
    }
    static void searchInfinite(PredictionContext context,int x,int z,Map<Integer,Set<StructureType>> pools,
            Map<String,LootSearchTerm> terms,Map<String,Long> remaining,Set<String> seenChests,List<Result> found,Job job,
            java.util.function.Function<LootSearchTile,List<GeneratedStructure>> load) {
        var iterators=new LinkedHashMap<Integer,OutwardTiles>();
        pools.forEach((dimension,types)->{if(types.stream().anyMatch(t->t!=StructureType.DUNGEON))iterators.put(dimension,new OutwardTiles(
            convertCoordinate(x,context.dimension(),dimension),convertCoordinate(z,context.dimension(),dimension)));});
        int dungeonX=convertCoordinate(x,context.dimension(),0),dungeonZ=convertCoordinate(z,context.dimension(),0);
        OutwardTiles dungeons=pools.getOrDefault(0,Set.of()).contains(StructureType.DUNGEON)
            ?new OutwardTiles(dungeonX,dungeonZ,16):null;
        job.dungeonLimit=Integer.MAX_VALUE;job.dungeonDeadline=Long.MAX_VALUE;
        // Separate outward cursors interleave one dungeon chunk with a region
        // per eligible dimension. Replaying all 1,024 chunks in a region first
        // would starve the much faster structure search for minutes or longer.
        while(!job.cancelled && !job.fulfilled && (iterators.values().stream().anyMatch(OutwardTiles::hasNext)
                || dungeons!=null && dungeons.hasNext())) {
            for(var entry:iterators.entrySet()) {
                if(job.cancelled || job.fulfilled)break;
                if(!entry.getValue().hasNext())continue;
                int[] cell=entry.getValue().next();
                int minX=cell[0]*32,minZ=cell[1]*32;
                if(minX>1874999 || minZ>1874999 || minX+31< -1875000 || minZ+31< -1875000)continue;
                int dim=entry.getKey(),ox=convertCoordinate(x,context.dimension(),dim),oz=convertCoordinate(z,context.dimension(),dim);
                var types=EnumSet.copyOf(pools.get(dim));types.remove(StructureType.DUNGEON);
                String progress="Infinite ring "+cell[2]+" (~"+((long)(cell[2]+1)*512)+" blocks); "+job.regionsChecked
                    +" regions, "+job.structuresChecked+" structures, "+job.containersChecked+" containers; nearest unverified";
                job.status=progress;
                if(!types.isEmpty()) {
                    var tile=new LootSearchTile(context.withDimension(dim),ox,oz,Integer.MAX_VALUE,minX,minZ,minX+31,minZ+31,types);
                    job.regionsChecked++;
                    for(var structure:load.apply(tile)) {
                        if(job.cancelled || job.fulfilled)break;
                        if(types.contains(structure.type))matchLoot(tile.context(),structure,terms,remaining,seenChests,found,job,context.dimension(),x,z);
                    }
                }
            }
            if(dungeons!=null && dungeons.hasNext() && !job.cancelled && !job.fulfilled) {
                int[] cell=dungeons.next();
                if(cell[0]<-1875000 || cell[0]>1874999 || cell[1]<-1875000 || cell[1]>1874999)continue;
                var tile=new LootSearchTile(context.withDimension(0),dungeonX,dungeonZ,Integer.MAX_VALUE,
                    cell[0],cell[1],cell[0],cell[1],Set.of(StructureType.DUNGEON));
                job.dungeonChunksChecked++;
                for(var structure:load.apply(tile)) {
                    if(job.cancelled || job.fulfilled)break;
                    if(structure.type==StructureType.DUNGEON)
                        matchLoot(tile.context(),structure,terms,remaining,seenChests,found,job,context.dimension(),x,z);
                }
            }
        }
        if(!job.cancelled)job.terminalStatus=(job.fulfilled?"Found requested items":job.dungeonLimited?"Partial search; dungeon budget reached":"Reached vanilla world border")
            +"; outward region order, nearest unverified; "+job.totalContributing+" contributing chests"
            +(job.dungeonLimited?"; dungeon coverage limited":"");
    }

    static List<String> takeMatchingLoot(ChestLootOutput chest, Map<String, LootSearchTerm> terms,
                                         Map<String, Long> remaining) {
        List<String> contributions = new ArrayList<>();
        long[] available = chest.predictedItems().stream().mapToLong(item -> Math.max(0, item.minCount())).toArray();
        // Allocate specific requests first; one book must not also satisfy a second request.
        var ordered = terms.entrySet().stream().sorted(Comparator.comparingInt(entry ->
            -entry.getValue().properties().size()*100-(int)entry.getValue().properties().stream().filter(p->p.level()>0).count())).toList();
        for (var entry : ordered) {
            String signature = entry.getKey();
            LootSearchTerm term = entry.getValue();
            long needed = remaining.getOrDefault(signature, 0L);
            if (needed <= 0) continue;
            long taken = 0;
            for (int i = 0; i < available.length && taken < needed; i++) {
                if (!term.matches(chest.predictedItems().get(i))) continue;
                long amount = Math.min(needed - taken, available[i]);
                available[i] -= amount;
                taken += amount;
            }
            if (taken > 0) {
                remaining.put(signature, needed - taken);
                contributions.add(term.display() + " x" + taken);
            }
        }
        return contributions;
    }

    static boolean containsAll(List<ChestLootOutput> chests, List<ItemLootRequest> requests) {
        if (requests.isEmpty()) return false;
        // The predictor emits each single or merged double inventory once.
        for (ItemLootRequest request : requests) {
            long count = chests.stream().flatMap(chest -> chest.predictedItems().stream())
                .filter(request.term()::matches).mapToLong(ItemLoot::minCount).sum();
            if (count < request.quantity()) return false;
        }
        return true;
    }

    static Set<StructureType> candidateStructureTypes(PredictionContext context, LootSearchTerm term) {
        var profile = BundledLootTableLoader.VersionProfile.forVersion(context.minecraftVersion());
        Set<StructureType> candidates = EnumSet.noneOf(StructureType.class);
        for (StructureType type : StructureType.values()) {
            if (!PredictionVersionPolicy.supportsStructure(context.minecraftVersion(), type)) continue;
            for (String table : lootTablesFor(type)) {
                if (tableCanProduce(profile, table, term, new HashSet<>())) {
                    candidates.add(type);
                    break;
                }
            }
        }
        return candidates;
    }

    private static final Map<String, Optional<LootTableDef>> SEARCH_TABLES = new ConcurrentHashMap<>();

    private static LootTableDef searchTable(BundledLootTableLoader.VersionProfile profile, String tableId) {
        return SEARCH_TABLES.computeIfAbsent(profile.minecraftVersion() + ":" + tableId,
            ignored -> Optional.ofNullable(BundledLootTableLoader.parseFromBundled(profile, tableId, tableId)))
            .orElse(null);
    }

    private static boolean tableCanProduce(BundledLootTableLoader.VersionProfile profile,
                                           String tableId, LootSearchTerm term, Set<String> visited) {
        if (!visited.add(tableId)) return false;
        LootTableDef table = searchTable(profile, tableId);
        if (table == null) return false;
        for (LootPool pool : table.pools()) for (ItemLoot item : pool.entries()) {
            if (item.referenceTableId() != null
                && tableCanProduce(profile, item.referenceTableId(), term, visited)) return true;
            boolean enchantedBookFromBook = term.itemId().equals("minecraft:enchanted_book")
                && item.itemId().equals("minecraft:book")
                && (item.randomEnchantment() || item.levelsEnchantment());
            if (!term.itemId().equals(item.itemId()) && !enchantedBookFromBook) continue;
            if(term.properties().isEmpty())return true;
            if(term.properties().size()>1 && !item.levelsEnchantment())continue;
            if(term.properties().stream().allMatch(property->itemCanEnchant(profile,item,term.itemId(),property)))return true;
        }
        return false;
    }
    private static boolean itemCanEnchant(BundledLootTableLoader.VersionProfile profile, ItemLoot item,
                                          String itemId, LootSearchTerm.Property property) {
        String normalizedId=property.enchantment().replace("minecraft:","");
        if(!availableInProfile(profile,normalizedId))return false;
        var registry=WorldgenEngine.offlineRegistryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        var selected=registry.get(net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.ENCHANTMENT,
            net.minecraft.resources.Identifier.parse(property.enchantment()))).orElse(null);
        if(selected==null || property.level()>selected.value().getMaxLevel())return false;
        if(item.enchantmentId()!=null) return Arrays.stream(item.enchantmentId().split(","))
            .anyMatch(id->id.trim().replace("minecraft:","").equals(property.enchantment().replace("minecraft:","")))
            && (property.level()==0 || item.enchantmentLevel()==0 || item.enchantmentLevel()==property.level());
        if(!item.randomEnchantment() && !item.levelsEnchantment())return false;
        boolean book=itemId.equals("minecraft:enchanted_book");
        if(book || item.levelsEnchantment()) {
            if(!LootTableSimulator.randomLootEnchantments(profile).contains(normalizedId))return false;
        }
        var stack=net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.parse(itemId))
            .orElseThrow().getDefaultInstance();
        return book || selected.value().isSupportedItem(stack);
    }

    private static boolean availableInProfile(BundledLootTableLoader.VersionProfile profile,String id) {
        return LootTableSimulator.enchantmentAvailableInProfile(profile, id);
    }

    private static final Map<String, Set<String>> ITEM_CATALOGS = new ConcurrentHashMap<>();

    static Set<String> chestItemIds(PredictionContext context, boolean allDimensions) {
        String key = context.minecraftVersion() + ":" + (allDimensions ? "all" : context.dimension());
        return ITEM_CATALOGS.computeIfAbsent(key, ignored -> {
            var profile = BundledLootTableLoader.VersionProfile.forVersion(context.minecraftVersion());
            Set<String> items = new HashSet<>(), visited = new HashSet<>();
            for (StructureType type : StructureType.values()) {
                if ((!allDimensions && type.dimension != context.dimension())
                    || !PredictionVersionPolicy.supportsStructure(context.minecraftVersion(), type)) continue;
                for (String table : lootTablesFor(type)) {
                    if ((table.contains(":chests/") && !table.endsWith("_dispenser"))
                        || table.contains(":barrels/")) collectLootItems(profile, table, visited, items);
                }
            }
            items.remove("minecraft:air");
            return Set.copyOf(items);
        });
    }

    private static void collectLootItems(BundledLootTableLoader.VersionProfile profile,
                                         String id, Set<String> visited, Set<String> items) {
        if (!visited.add(id)) return;
        LootTableDef table = searchTable(profile, id);
        if (table == null) return;
        for (LootPool pool : table.pools()) for (ItemLoot item : pool.entries()) {
            if (item.referenceTableId() != null) collectLootItems(profile, item.referenceTableId(), visited, items);
            if (item.itemId() == null) continue;
            items.add(item.itemId());
            if (item.itemId().equals("minecraft:book") && (item.randomEnchantment() || item.levelsEnchantment()))
                items.add("minecraft:enchanted_book");
        }
    }

    static boolean validLootTerm(PredictionContext context, LootSearchTerm term, boolean allDimensions) {
        if (term == null || !chestItemIds(context, allDimensions).contains(term.itemId())) return false;
        if (term.properties().isEmpty()) return true;
        var enchantments = WorldgenEngine.offlineRegistryAccess()
            .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        var chosen=new ArrayList<net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>>();
        for(var property:term.properties()) {
            var id=net.minecraft.resources.Identifier.tryParse(property.enchantment());
            var holder=id==null?null:enchantments.get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,id)).orElse(null);
            if(holder==null || property.level()<0 || property.level()>holder.value().getMaxLevel())return false;
            if(chosen.stream().anyMatch(other->!net.minecraft.world.item.enchantment.Enchantment.areCompatible(other,holder)))return false;
            chosen.add(holder);
        }
        return candidateStructureTypes(context, term).stream().anyMatch(type -> allDimensions || type.dimension == context.dimension());
    }
    static String enchantmentConflict(Collection<LootSearchTerm.Property> selected,String candidate) {
        var registry=WorldgenEngine.offlineRegistryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        var holder=registry.get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
            net.minecraft.resources.Identifier.parse(candidate))).orElse(null);
        if(holder==null)return "Unknown enchantment";
        for(var other:selected) {
            if(other.enchantment().equals(candidate))continue;
            var existing=registry.get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                net.minecraft.resources.Identifier.parse(other.enchantment()))).orElse(null);
            if(existing!=null && !net.minecraft.world.item.enchantment.Enchantment.areCompatible(existing,holder))
                return "Cannot coexist with "+other.enchantment().replace("minecraft:","").replace('_',' ');
        }
        return null;
    }
    static List<LootSearchTerm.Property> enchantmentOptions(PredictionContext context,String itemId,boolean allDimensions) {
        var options=new ArrayList<LootSearchTerm.Property>();
        for(var entry:WorldgenEngine.offlineRegistryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).entrySet()) {
            var property=new LootSearchTerm.Property(entry.getKey().identifier().toString(),0);
            if(validLootTerm(context,LootSearchTerm.withProperties(itemId,itemId,List.of(property)),allDimensions))options.add(property);
        }
        options.sort(Comparator.comparing(LootSearchTerm.Property::enchantment));return List.copyOf(options);
    }

    static LootSearchTerm automaticItem(PredictionContext context, String query, boolean allDimensions) {
        LootSearchTerm term;
        try { term = LootSearchParser.parseTerm(query); }
        catch (IllegalArgumentException invalid) { return null; }
        if (!validLootTerm(context, term, allDimensions)) return null;
        String normalized = normalize(query.replace("minecraft:", ""));
        return term.enchantment() != null || normalized.equals(normalize(term.display()))
            || normalized.equals(normalize(term.itemId().replace("minecraft:", ""))) ? term : null;
    }

    private static List<String> lootTablesFor(StructureType type) {
        return switch (type) {
            case VILLAGE -> List.of("minecraft:chests/village/village_armorer",
                "minecraft:chests/village/village_butcher", "minecraft:chests/village/village_cartographer",
                "minecraft:chests/village/village_desert_house", "minecraft:chests/village/village_fisher",
                "minecraft:chests/village/village_fletcher", "minecraft:chests/village/village_mason",
                "minecraft:chests/village/village_plains_house", "minecraft:chests/village/village_savanna_house",
                "minecraft:chests/village/village_shepherd", "minecraft:chests/village/village_snowy_house",
                "minecraft:chests/village/village_taiga_house", "minecraft:chests/village/village_tannery",
                "minecraft:chests/village/village_temple", "minecraft:chests/village/village_toolsmith",
                "minecraft:chests/village/village_weaponsmith");
            case DUNGEON -> List.of("minecraft:chests/simple_dungeon");
            case MINESHAFT -> List.of("minecraft:chests/abandoned_mineshaft");
            case DESERT_PYRAMID -> List.of("minecraft:chests/desert_pyramid");
            case STRONGHOLD -> List.of("minecraft:chests/stronghold_corridor", "minecraft:chests/stronghold_crossing",
                "minecraft:chests/stronghold_library", "minecraft:chests/stronghold_storeroom");
            case SHIPWRECK -> List.of("minecraft:chests/shipwreck_treasure", "minecraft:chests/shipwreck_supply",
                "minecraft:chests/shipwreck_map");
            case OUTPOST -> List.of("minecraft:chests/pillager_outpost");
            case JUNGLE_TEMPLE -> List.of("minecraft:chests/jungle_temple", "minecraft:chests/jungle_temple_dispenser");
            case TREASURE -> List.of("minecraft:chests/buried_treasure");
            case RUINED_PORTAL, NETHER_RUINED_PORTAL -> List.of("minecraft:chests/ruined_portal");
            case OCEAN_RUIN -> List.of("minecraft:chests/underwater_ruin_small", "minecraft:chests/underwater_ruin_big");
            case IGLOO -> List.of("minecraft:chests/igloo_chest");
            case ANCIENT_CITY -> List.of("minecraft:chests/ancient_city", "minecraft:chests/ancient_city_ice_box");
            case TRAIL_RUINS -> List.of("minecraft:archaeology/trail_ruins_common", "minecraft:archaeology/trail_ruins_rare");
            case ABANDONED_CAMP -> List.of("minecraft:barrels/abandoned_camp_barrel",
                "minecraft:chests/abandoned_camp_common_chest", "minecraft:chests/abandoned_camp_secret_chest");
            case MANSION -> List.of("minecraft:chests/woodland_mansion");
            case END_CITY -> List.of("minecraft:chests/end_city_treasure");
            case FORTRESS -> List.of("minecraft:chests/nether_bridge");
            case BASTION -> List.of("minecraft:chests/bastion_treasure", "minecraft:chests/bastion_other",
                "minecraft:chests/bastion_hoglin_stable", "minecraft:chests/bastion_bridge");
            default -> List.of();
        };
    }

    private static void scan(PredictionContext context, int x, int z, int radius,
                             StructureType type, Job job, Consumer<GeneratedStructure> consumer) {
        int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
        int chunks = Math.max(1, Math.min(512, radius / 16));
        if (type == StructureType.DUNGEON) {
            if (context.dimension() != 0) return;
            for (int ring = 0; ring <= chunks && !job.cancelled && !job.fulfilled && job.results.size() < 64; ring++) {
                job.status = "Replaying dungeon decoration: ring " + ring + " / " + chunks;
                for (int dz = -ring; dz <= ring && !job.cancelled && !job.fulfilled; dz++) {
                    for (int dx = -ring; dx <= ring && !job.cancelled && !job.fulfilled; dx++) {
                        if (job.results.size() >= 64) return;
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        for (var dungeon : VanillaLootStructureSimulator.simulateDungeons(context.worldSeed(), cx + dx, cz + dz)) {
                            consumer.accept(new GeneratedStructure(dungeon.x(), dungeon.y(), dungeon.z(), cx + dx, cz + dz,
                                StructureType.DUNGEON, dungeon.spawnerEntityId(), false));
                        }
                    }
                }
            }
            return;
        }
        // Small disjoint tiles let cancellation take effect between predictor calls.
        int tileSize = 32;
        List<int[]> tiles = new ArrayList<>();
        for (int dz = -chunks; dz <= chunks; dz += tileSize)
            for (int dx = -chunks; dx <= chunks; dx += tileSize) tiles.add(new int[] {dx, dz});
        tiles.sort(Comparator.comparingLong(t -> {
            long nearX = t[0] > 0 ? t[0] : Math.min(0, t[0] + tileSize - 1);
            long nearZ = t[1] > 0 ? t[1] : Math.min(0, t[1] + tileSize - 1);
            return nearX * nearX + nearZ * nearZ;
        }));
        int checked = 0;
        for (int[] tile : tiles) {
            if (job.cancelled || job.fulfilled || job.results.size() >= 64) return;
            job.status = "Searching " + context.dimension() + ": region " + (++checked) + " / " + tiles.size();
            List<GeneratedStructure> structures = StandalonePredictionCache.predictDimension(context,
                cx + tile[0], cz + tile[1], cx + Math.min(chunks, tile[0] + tileSize - 1),
                cz + Math.min(chunks, tile[1] + tileSize - 1), type == null || type == StructureType.TREASURE,
                type == null || type == StructureType.MINESHAFT, () -> job.cancelled);
            structures = new ArrayList<>(structures);
            structures.sort(Comparator.comparingDouble(s -> distance(s.x, s.z, x, z)));
            for (GeneratedStructure structure : structures) {
                if (job.cancelled || job.fulfilled || job.results.size() >= 64) return;
                if (type == null || structure.type == type) consumer.accept(structure);
            }
        }
    }

    private static void publish(Job job, List<Result> results, int x, int z) {
        if (job.cancelled) return;
        job.results = results.stream().sorted(Comparator.comparingDouble(r -> distance(r.x, r.z, x, z)))
            .limit(64).toList();
    }
    private static void publishAcrossDimensions(Job job, List<Result> results, int originDimension, int x, int z) {
        if (job.cancelled) return;
        job.results = results.stream().sorted(Comparator.comparingDouble(r -> distance(r.x, r.z,
            convertCoordinate(x, originDimension, r.dimension),
            convertCoordinate(z, originDimension, r.dimension)))).limit(job.infinite?147456:64).toList();
    }
    private static double distance(int x, int z, int ox, int oz) {
        return Math.pow((double)x - ox, 2) + Math.pow((double)z - oz, 2);
    }
    static int convertCoordinate(int coordinate, int from, int to) {
        if (from == -1 && to == 0) return (int)Math.max(-30_000_000, Math.min(30_000_000, (long)coordinate * 8));
        if (from == 0 && to == -1) return Math.floorDiv(coordinate, 8);
        return coordinate;
    }
    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace('_', ' ')
            .replace("sulphur", "sulfur").trim();
    }
    private static List<String> aliases(StructureType type) {
        return switch (type) {
            case DESERT_PYRAMID -> List.of("desert temple", "temple", "pyramid");
            case JUNGLE_TEMPLE -> List.of("jungle pyramid");
            case TREASURE -> List.of("buried treasure");
            case ABANDONED_CAMP -> List.of("camp", "campsite", "abandoned campsite");
            case OUTPOST -> List.of("pillager outpost", "pillager tower");
            case MONUMENT -> List.of("ocean monument");
            case MANSION -> List.of("woodland mansion");
            case WITCH_HUT -> List.of("swamp hut");
            case FORTRESS -> List.of("nether fortress");
            case NETHER_RUINED_PORTAL -> List.of("nether portal");
            default -> List.of();
        };
    }
}
