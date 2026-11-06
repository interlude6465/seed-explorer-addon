package me.seedexplorer.addon.structures;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.LootSearchTerm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Persistent 64x64 structure regions, predicted containers, and item index. */
public final class StructurePredictionStore {
    private static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final StructurePredictionStore INSTANCE = new StructurePredictionStore(defaultRoot());

    private final Path root;
    private final ThreadPoolExecutor writer;
    private final Map<ContextKey, Map<Long, RegionData>> loaded = new ConcurrentHashMap<>();
    private final Set<ContextKey> fullyLoaded = ConcurrentHashMap.newKeySet();

    public static StructurePredictionStore get() {
        return INSTANCE;
    }

    public StructurePredictionStore(Path root) {
        this.root = root;
        writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), runnable -> {
                Thread thread = new Thread(runnable, "Seed Explorer Structure Persistence");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    public Optional<RegionPrediction> load(String worldIdentity, long seed, int dimension,
                                           long regionKey, boolean treasure, boolean mineshafts) {
        ContextKey context = new ContextKey(worldIdentity, seed, dimension, treasure, mineshafts);
        RegionData cached = loaded.computeIfAbsent(context, ignored -> new ConcurrentHashMap<>())
            .get(regionKey);
        if (cached != null) return Optional.of(fromData(cached));
        Path file = regionFile(context, regionKey);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            RegionData data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), RegionData.class);
            if (!valid(data, context, regionKey)) return Optional.empty();
            loaded.get(context).put(regionKey, data);
            return Optional.of(fromData(data));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public void storeAsync(String worldIdentity, long seed, int dimension, long regionKey,
                           boolean treasure, boolean mineshafts,
                           List<StructurePrediction> structures) {
        RegionPrediction prediction = new RegionPrediction(regionKey, List.copyOf(structures));
        writer.execute(() -> storeNow(worldIdentity, seed, dimension, treasure, mineshafts, prediction));
    }

    public void storeNow(String worldIdentity, long seed, int dimension, boolean treasure,
                         boolean mineshafts, RegionPrediction prediction) {
        ContextKey context = new ContextKey(worldIdentity, seed, dimension, treasure, mineshafts);
        RegionData data = toData(context, prediction);
        Path file = regionFile(context, prediction.regionKey());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temporary, GSON.toJson(data), StandardCharsets.UTF_8);
            moveAtomically(temporary, file);
            loaded.computeIfAbsent(context, ignored -> new ConcurrentHashMap<>())
                .put(prediction.regionKey(), data);
        } catch (IOException | RuntimeException ignored) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignoredDelete) {
                // Best-effort prediction cache.
            }
        }
    }

    public List<StructurePrediction> findMatches(String worldIdentity, long seed,
                                                  Set<Integer> dimensions,
                                                  Set<LootSearchTerm> terms) {
        if (terms == null || terms.isEmpty()) return List.of();
        List<StructurePrediction> matches = new ArrayList<>();
        for (int dimension : dimensions) {
            ContextKey context = new ContextKey(worldIdentity, seed, dimension, true, true);
            loadAll(context);
            Map<Long, RegionData> regions = loaded.getOrDefault(context, Map.of());
            for (RegionData region : regions.values()) {
                for (StructureData structure : region.structures) {
                    StructurePrediction prediction = fromData(structure);
                    if (matchesAll(prediction.chests(), terms)) matches.add(prediction);
                }
            }
        }
        matches.sort(Comparator.comparingInt(value -> value.structure().type.dimension));
        return List.copyOf(matches);
    }

    public Optional<List<ChestLootOutput>> lootFor(String worldIdentity, long seed, int dimension,
                                                   GeneratedStructure structure) {
        for (boolean treasure : new boolean[]{true, false}) {
            for (boolean mineshafts : new boolean[]{true, false}) {
                ContextKey context = new ContextKey(worldIdentity, seed, dimension, treasure, mineshafts);
                loadAll(context);
                for (RegionData region : loaded.getOrDefault(context, Map.of()).values()) {
                    for (StructureData candidate : region.structures) {
                        if (candidate.startChunkX == structure.startChunkX
                            && candidate.startChunkZ == structure.startChunkZ
                            && candidate.type.equals(structure.type.name())) {
                            return Optional.of(fromData(candidate).chests());
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private void loadAll(ContextKey context) {
        if (!fullyLoaded.add(context)) return;
        Map<Long, RegionData> target = loaded.computeIfAbsent(context, ignored -> new ConcurrentHashMap<>());
        Path directory = contextDirectory(context);
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    RegionData data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), RegionData.class);
                    if (data != null && data.version == VERSION
                        && data.seed == context.seed && data.dimension == context.dimension
                        && data.treasure == context.treasure && data.mineshafts == context.mineshafts
                        && context.worldIdentity.equals(data.worldIdentity)) {
                        target.putIfAbsent(data.regionKey, data);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // One damaged region must not hide the rest of the index.
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static boolean matchesAll(List<ChestLootOutput> chests, Set<LootSearchTerm> terms) {
        for (LootSearchTerm term : terms) {
            boolean found = false;
            for (ChestLootOutput chest : chests) {
                if (chest.predictedItems() == null) continue;
                for (ItemLoot item : chest.predictedItems()) {
                    if (term.matches(item)) {
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }
            if (!found) return false;
        }
        return true;
    }

    private static RegionData toData(ContextKey context, RegionPrediction prediction) {
        return new RegionData(VERSION, context.worldIdentity, context.seed, context.dimension,
            context.treasure, context.mineshafts, prediction.regionKey,
            prediction.structures.stream().map(StructurePredictionStore::toData).toList());
    }

    private static StructureData toData(StructurePrediction prediction) {
        GeneratedStructure structure = prediction.structure;
        return new StructureData(structure.x, structure.y, structure.z, structure.startChunkX,
            structure.startChunkZ, structure.type.name(), structure.variant, structure.hasShip,
            structure.verified, prediction.minX, prediction.minY, prediction.minZ,
            prediction.maxX, prediction.maxY, prediction.maxZ,
            prediction.chests.stream().map(StructurePredictionStore::toData).toList());
    }

    private static ChestData toData(ChestLootOutput chest) {
        return new ChestData(chest.blockX(), chest.blockY(), chest.blockZ(), chest.lootTableId(),
            chest.seed(), chest.exact(), chest.predictedItems() == null ? List.of() : chest.predictedItems());
    }

    private static RegionPrediction fromData(RegionData data) {
        return new RegionPrediction(data.regionKey,
            data.structures.stream().map(StructurePredictionStore::fromData).toList());
    }

    private static StructurePrediction fromData(StructureData data) {
        GeneratedStructure structure = new GeneratedStructure(data.x, data.y, data.z,
            data.startChunkX, data.startChunkZ, StructureType.valueOf(data.type),
            data.variant, data.hasShip);
        structure.verified = data.verified;
        return new StructurePrediction(structure, data.minX, data.minY, data.minZ,
            data.maxX, data.maxY, data.maxZ,
            data.chests.stream().map(StructurePredictionStore::fromData).toList());
    }

    private static ChestLootOutput fromData(ChestData data) {
        return new ChestLootOutput(data.x, data.y, data.z, data.lootTableId,
            data.items == null ? List.of() : List.copyOf(data.items), data.lootSeed, data.exact);
    }

    private boolean valid(RegionData data, ContextKey context, long regionKey) {
        return data != null && data.version == VERSION && data.regionKey == regionKey
            && data.seed == context.seed && data.dimension == context.dimension
            && data.treasure == context.treasure && data.mineshafts == context.mineshafts
            && context.worldIdentity.equals(data.worldIdentity);
    }

    private Path regionFile(ContextKey context, long regionKey) {
        return contextDirectory(context).resolve("r." + (int) (regionKey >> 32) + "."
            + (int) regionKey + ".json");
    }

    private Path contextDirectory(ContextKey context) {
        return root.resolve(identityHash(context.worldIdentity))
            .resolve(Long.toUnsignedString(context.seed, 16)).resolve("d" + context.dimension)
            .resolve("t" + (context.treasure ? 1 : 0) + "m" + (context.mineshafts ? 1 : 0));
    }

    private static Path defaultRoot() {
        return Path.of(System.getProperty("user.home", "."), ".seed-explorer", "structure-cache");
    }

    private static String identityHash(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record RegionPrediction(long regionKey, List<StructurePrediction> structures) {
    }

    public record StructurePrediction(GeneratedStructure structure,
                                      int minX, int minY, int minZ,
                                      int maxX, int maxY, int maxZ,
                                      List<ChestLootOutput> chests) {
        public StructurePrediction(GeneratedStructure structure, List<ChestLootOutput> chests) {
            this(structure, structure.x, structure.y, structure.z,
                structure.x, structure.y, structure.z, List.copyOf(chests));
        }
    }

    private record ContextKey(String worldIdentity, long seed, int dimension,
                              boolean treasure, boolean mineshafts) {
    }

    private record RegionData(int version, String worldIdentity, long seed, int dimension,
                              boolean treasure, boolean mineshafts, long regionKey,
                              List<StructureData> structures) {
    }

    private record StructureData(int x, int y, int z, int startChunkX, int startChunkZ,
                                 String type, String variant, boolean hasShip, boolean verified,
                                 int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                 List<ChestData> chests) {
    }

    private record ChestData(int x, int y, int z, String lootTableId, long lootSeed,
                             boolean exact, List<ItemLoot> items) {
    }
}
