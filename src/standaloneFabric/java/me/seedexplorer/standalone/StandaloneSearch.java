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
        public volatile String status = "Searching...";
        public volatile List<Result> results = List.of();
        public volatile boolean done;
        public void cancel() { cancelled = true; }
    }
    private static volatile Job active;
    private StandaloneSearch() { }

    private static synchronized Job submit(Consumer<Job> action) {
        if (active != null) active.cancel();
        Job job = new Job();
        active = job;
        WORKER.getQueue().clear();
        WORKER.execute(() -> {
            try {
                if (!job.cancelled) action.accept(job);
                if (!job.cancelled) job.status = job.results.isEmpty()
                    ? "No matches in this search radius." : job.results.size() + " results";
            } catch (RuntimeException failure) {
                job.status = "Search failed: " + (failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
                failure.printStackTrace(System.err);
            } finally { job.done = true; }
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
    private static String resolveBiome(PredictionContext context, String query) {
        return WorldgenEngine.publicRegistryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
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
            List<Result> found = new ArrayList<>();
            int[] dimensions = allDimensions ? new int[] {context.dimension(),
                context.dimension() == 0 ? -1 : 0, context.dimension() == 1 ? -1 : 1}
                : new int[] {context.dimension()};
            for (int dim : dimensions) {
                if (job.cancelled || found.size() >= 64) break;
                PredictionContext scoped = new PredictionContext(context.worldSeed(), context.minecraftVersion(), dim);
                int ox = convertCoordinate(x, context.dimension(), dim);
                int oz = convertCoordinate(z, context.dimension(), dim);
                scan(scoped, ox, oz, radius, null, job, structure -> {
                    matchLoot(scoped, structure, wanted, found, job, context.dimension(), x, z);
                });
            }
            // Dungeons are decorated features, not structure starts. Replay them only after
            // the faster multi-dimension structure scan so existing results remain usable.
            if (!job.cancelled && found.size() < 64 && (allDimensions || context.dimension() == 0)) {
                PredictionContext overworld = context.withDimension(0);
                scan(overworld, convertCoordinate(x, context.dimension(), 0),
                    convertCoordinate(z, context.dimension(), 0), radius, StructureType.DUNGEON, job,
                    structure -> matchLoot(overworld, structure, wanted, found, job, context.dimension(), x, z));
            }
        });
    }

    private static void matchLoot(PredictionContext scoped, GeneratedStructure structure,
                                  List<ItemLootRequest> wanted, List<Result> found, Job job,
                                  int originDimension, int x, int z) {
        if (job.cancelled || found.size() >= 64) return;
        List<ChestLootOutput> chests = StandaloneStructureLootPredictor.predict(scoped, structure, 128);
        if (!containsAll(chests, wanted)) return;
        List<ChestLootOutput> individual = chests.stream().filter(c -> containsAll(List.of(c), wanted)).toList();
        if (individual.isEmpty()) {
            found.add(new Result(scoped.dimension(), structure.x, structure.y, structure.z,
                structure.displayName() + " (combined across " + chests.size() + " containers; Inspect)", structure, null));
        }
        for (ChestLootOutput chest : individual) {
            if (found.size() >= 64) break;
            found.add(new Result(scoped.dimension(), chest.blockX(), chest.blockY(), chest.blockZ(),
                structure.displayName() + (chest.exact() ? " (exact)" : " (approximate)"), structure, chest));
        }
        publishAcrossDimensions(job, found, originDimension, x, z);
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

    private static void scan(PredictionContext context, int x, int z, int radius,
                             StructureType type, Job job, Consumer<GeneratedStructure> consumer) {
        int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
        int chunks = Math.max(1, Math.min(512, radius / 16));
        if (type == StructureType.DUNGEON) {
            if (context.dimension() != 0) return;
            for (int ring = 0; ring <= chunks && !job.cancelled && job.results.size() < 64; ring++) {
                job.status = "Replaying dungeon decoration: ring " + ring + " / " + chunks;
                for (int dz = -ring; dz <= ring && !job.cancelled; dz++) {
                    for (int dx = -ring; dx <= ring && !job.cancelled; dx++) {
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
        tiles.sort(Comparator.comparingLong(t -> (long)t[0] * t[0] + (long)t[1] * t[1]));
        int checked = 0;
        for (int[] tile : tiles) {
            if (job.cancelled || job.results.size() >= 64) return;
            job.status = "Searching " + context.dimension() + ": region " + (++checked) + " / " + tiles.size();
            List<GeneratedStructure> structures = StandalonePredictionCache.predictDimension(context,
                cx + tile[0], cz + tile[1], cx + Math.min(chunks, tile[0] + tileSize - 1),
                cz + Math.min(chunks, tile[1] + tileSize - 1), type == null || type == StructureType.TREASURE,
                type == null || type == StructureType.MINESHAFT, () -> job.cancelled);
            structures = new ArrayList<>(structures);
            structures.sort(Comparator.comparingDouble(s -> distance(s.x, s.z, x, z)));
            for (GeneratedStructure structure : structures) {
                if (job.cancelled || job.results.size() >= 64) return;
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
            convertCoordinate(z, originDimension, r.dimension)))).limit(64).toList();
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
