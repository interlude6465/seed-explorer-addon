package me.seedexplorer.standalone;

import com.google.gson.Gson;
import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.PredictionContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Versioned disk cache for completed standalone loot replays. */
public final class StandalonePersistentLootCache {
    private static final int FORMAT = 3;
    private static final String PREDICTOR_REVISION = "loot-v12-selected-version";
    private static final int MAX_CONTAINERS = 4_096;
    private static final int MAX_ITEMS_PER_CONTAINER = 4_096;
    private static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;
    private static final Gson JSON = new Gson();
    private static final Map<Key, List<ChestLootOutput>> MEMORY =
        new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, List<ChestLootOutput>> entry) {
                return size() > 256;
            }
        };

    private StandalonePersistentLootCache() {
    }

    public static Optional<List<ChestLootOutput>> get(PredictionContext context,
                                                       GeneratedStructure structure) {
        Key key = key(context, structure);
        synchronized (MEMORY) {
            List<ChestLootOutput> hit = MEMORY.get(key);
            if (hit != null) return Optional.of(hit);
        }

        Path file = file(key);
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) {
                return Optional.empty();
            }
            Data data = JSON.fromJson(Files.readString(file), Data.class);
            if (data == null || data.format != FORMAT || !key.equals(data.key)
                || !validCompletedLoot(data.chests)) {
                return Optional.empty();
            }
            List<ChestLootOutput> hit = immutableLoot(data.chests);
            synchronized (MEMORY) {
                MEMORY.put(key, hit);
            }
            return Optional.of(hit);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public static void put(PredictionContext context, GeneratedStructure structure,
                           List<ChestLootOutput> chests) {
        Key key = key(context, structure);
        if (!validCompletedLoot(chests)) return;
        List<ChestLootOutput> stored = immutableLoot(chests);
        synchronized (MEMORY) {
            MEMORY.put(key, stored);
        }

        Path file = file(key);
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "loot-", ".tmp");
            Files.writeString(temporary, JSON.toJson(new Data(FORMAT, key, stored)));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // Persistence is best effort; prediction results remain available in memory.
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                }
            }
        }
    }

    static void clearMemory() {
        synchronized (MEMORY) {
            MEMORY.clear();
        }
    }

    private static Key key(PredictionContext context, GeneratedStructure structure) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(structure, "structure");
        return new Key(PREDICTOR_REVISION, context.worldSeed(), context.minecraftVersion(), context.dimension(),
            structure.type, structure.startChunkX, structure.startChunkZ,
            structure.x, structure.y, structure.z, structure.variant == null ? "" : structure.variant,
            structure.hasShip);
    }

    private static boolean validCompletedLoot(List<ChestLootOutput> chests) {
        if (chests == null || chests.size() > MAX_CONTAINERS) return false;
        for (ChestLootOutput chest : chests) {
            if (chest == null || chest.lootTableId() == null || chest.predictedItems() == null
                || chest.predictedItems().size() > MAX_ITEMS_PER_CONTAINER) {
                return false;
            }
            for (ItemLoot item : chest.predictedItems()) {
                if (item == null || item.itemId() == null || item.displayName() == null) return false;
            }
        }
        return true;
    }

    private static List<ChestLootOutput> immutableLoot(List<ChestLootOutput> chests) {
        return chests.stream()
            .map(chest -> new ChestLootOutput(chest.blockX(), chest.blockY(), chest.blockZ(),
                chest.lootTableId(), List.copyOf(chest.predictedItems()), chest.seed(), chest.exact()))
            .toList();
    }

    private static Path file(Key key) {
        String scope = Long.toHexString(key.seed) + "-" + key.dimension + "-"
            + key.version.replaceAll("[^A-Za-z0-9._-]", "_");
        return Path.of(System.getProperty("seedexplorer.lootCacheDir",
                ".cache/seed-explorer-standalone-loot"),
            "v" + FORMAT, scope, digest(key) + ".json");
    }

    private static String digest(Key key) {
        String identity = key.type.name() + "\n" + key.startChunkX + "\n" + key.startChunkZ
            + "\n" + key.x + "\n" + key.y + "\n" + key.z + "\n" + key.variant
            + "\n" + key.hasShip;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte value : digest) text.append(String.format("%02x", value & 0xff));
            return text.toString();
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private record Key(String predictorRevision, long seed, String version, int dimension, StructureType type,
                       int startChunkX, int startChunkZ, int x, int y, int z, String variant,
                       boolean hasShip) {
    }

    private record Data(int format, Key key, List<ChestLootOutput> chests) {
    }
}
