package me.seedexplorer.addon.tools;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only scan of saved entity regions for abandoned-mineshaft chest minecarts. */
public final class MineshaftSavedOracleReport {
    private MineshaftSavedOracleReport() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Usage: <entity-region-dir> [limit]");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path entityDir = Path.of(args[0]).toAbsolutePath();
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 64;
        ScanResult scan = scan(entityDir);
        List<SavedMinecart> minecarts = scan.minecarts();
        List<Path> files = scan.files();
        int chunksRead = scan.chunksRead();
        int entitiesRead = scan.entitiesRead();

        List<SavedMinecart> selected = minecarts.stream()
            .sorted(Comparator.comparingDouble(SavedMinecart::x).thenComparingDouble(SavedMinecart::z))
            .limit(limit).toList();
        StringBuilder report = new StringBuilder();
        report.append("entity_region_files=").append(files.size()).append(" chunks_read=").append(chunksRead)
            .append(" entities_read=").append(entitiesRead).append('\n');
        report.append("saved_mineshaft_minecarts=").append(minecarts.size())
            .append(" unresolved=").append(minecarts.stream().filter(cart -> cart.lootSeed() != null).count())
            .append(" resolved=").append(minecarts.stream().filter(cart -> cart.lootSeed() == null).count())
            .append(" reported=").append(selected.size()).append('\n');
        for (SavedMinecart cart : selected) {
            report.append("minecart=").append(cart.x()).append(',').append(cart.y()).append(',').append(cart.z())
                .append(" block=").append(cart.blockX()).append(',').append(cart.blockY()).append(',').append(cart.blockZ())
                .append(" entity_chunk=").append(cart.entityChunkX()).append(',').append(cart.entityChunkZ())
                .append(" table=").append(cart.lootTable().isBlank() ? "<resolved>" : cart.lootTable())
                .append(" seed=").append(cart.lootSeed() == null ? "<resolved>" : cart.lootSeed())
                .append(" items=").append(cart.items())
                .append(" data_version=").append(cart.dataVersion()).append('\n');
        }
        Path reportPath = Path.of("build", "reports", "mineshaft-saved-oracle.txt").toAbsolutePath();
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString(), StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        System.out.print(report);
        System.out.println("report=" + reportPath);
    }

    static ScanResult scan(Path entityDir) throws Exception {
        RegionStorageInfo info = new RegionStorageInfo("mineshaft_oracle", Level.OVERWORLD, "entities");
        List<SavedMinecart> minecarts = new ArrayList<>();
        List<Path> files;
        try (var stream = Files.list(entityDir)) {
            files = stream.filter(path -> path.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                .filter(path -> {
                    try {
                        return Files.size(path) > 0;
                    } catch (Exception ignored) {
                        return false;
                    }
                })
                .sorted().toList();
        }

        int chunksRead = 0;
        int entitiesRead = 0;
        for (Path file : files) {
            String[] parts = file.getFileName().toString().split("\\.");
            int regionX = Integer.parseInt(parts[1]);
            int regionZ = Integer.parseInt(parts[2]);
            try (RegionFile region = new RegionFile(info, file, entityDir, false)) {
                for (int localZ = 0; localZ < 32; localZ++) {
                    for (int localX = 0; localX < 32; localX++) {
                        ChunkPos chunk = new ChunkPos(regionX * 32 + localX, regionZ * 32 + localZ);
                        if (!region.doesChunkExist(chunk)) continue;
                        try (DataInputStream input = region.getChunkDataInputStream(chunk)) {
                            if (input == null) continue;
                            CompoundTag root = NbtIo.read(input, NbtAccounter.unlimitedHeap());
                            chunksRead++;
                            for (var value : root.getListOrEmpty("Entities")) {
                                if (!(value instanceof CompoundTag entity)) continue;
                                entitiesRead++;
                                String id = entity.getString("id").orElse("");
                                if (!id.equals("minecraft:chest_minecart")) continue;
                                String table = entity.getString("LootTable").orElse("");
                                if (!table.isBlank() && !table.equals("minecraft:chests/abandoned_mineshaft")) continue;
                                var pos = entity.getListOrEmpty("Pos");
                                double x = pos.getDoubleOr(0, 0.0);
                                double y = pos.getDoubleOr(1, 0.0);
                                double z = pos.getDoubleOr(2, 0.0);
                                Map<String, Integer> items = readItems(entity);
                                minecarts.add(new SavedMinecart(x, y, z, table,
                                    entity.getLong("LootTableSeed").orElse(null), items,
                                    root.getIntOr("DataVersion", 0), chunk.x(), chunk.z()));
                            }
                        }
                    }
                }
            }
        }

        return new ScanResult(List.copyOf(files), chunksRead, entitiesRead, List.copyOf(minecarts));
    }

    private static Map<String, Integer> readItems(CompoundTag entity) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (var value : entity.getListOrEmpty("Items")) {
            if (!(value instanceof CompoundTag item)) continue;
            String id = item.getString("id").orElse("");
            int count = item.getInt("count").orElseGet(() -> (int) item.getByteOr("Count", (byte) 0));
            if (!id.isBlank() && count > 0) items.merge(id, count, Integer::sum);
        }
        return Map.copyOf(items);
    }

    record ScanResult(List<Path> files, int chunksRead, int entitiesRead, List<SavedMinecart> minecarts) {
    }

    record SavedMinecart(double x, double y, double z, String lootTable, Long lootSeed,
                         Map<String, Integer> items, int dataVersion,
                         int entityChunkX, int entityChunkZ) {
        int blockX() {
            return (int) Math.floor(x);
        }

        int blockY() {
            return (int) Math.floor(y);
        }

        int blockZ() {
            return (int) Math.floor(z);
        }
    }
}
