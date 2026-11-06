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
import java.util.List;

/** Read-only scan of saved chunks for monster spawners and nearby dungeon loot chests. */
public final class DungeonSavedOracleReport {
    private DungeonSavedOracleReport() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Usage: <region-dir> [limit]");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path regionDir = Path.of(args[0]).toAbsolutePath();
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 64;
        RegionStorageInfo info = new RegionStorageInfo("dungeon_oracle", Level.OVERWORLD, "chunk");
        List<SavedBlockEntity> entities = new ArrayList<>();

        List<Path> files;
        try (var stream = Files.list(regionDir)) {
            files = stream.filter(path -> path.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                .sorted().toList();
        }
        int chunksRead = 0;
        int blockEntitiesRead = 0;
        for (Path file : files) {
            String[] parts = file.getFileName().toString().split("\\.");
            int regionX = Integer.parseInt(parts[1]);
            int regionZ = Integer.parseInt(parts[2]);
            try (RegionFile region = new RegionFile(info, file, regionDir, false)) {
                for (int localZ = 0; localZ < 32; localZ++) {
                    for (int localX = 0; localX < 32; localX++) {
                        ChunkPos chunk = new ChunkPos(regionX * 32 + localX, regionZ * 32 + localZ);
                        if (!region.doesChunkExist(chunk)) continue;
                        try (DataInputStream input = region.getChunkDataInputStream(chunk)) {
                            if (input == null) continue;
                            CompoundTag root = NbtIo.read(input, NbtAccounter.unlimitedHeap());
                            chunksRead++;
                            int dataVersion = root.getIntOr("DataVersion", 0);
                            var blockEntities = root.contains("block_entities")
                                ? root.getListOrEmpty("block_entities")
                                : root.getListOrEmpty("TileEntities");
                            for (var value : blockEntities) {
                                if (!(value instanceof CompoundTag tag)) continue;
                                blockEntitiesRead++;
                                String id = tag.getString("id").orElse("");
                                String lootTable = tag.getString("LootTable").orElse("");
                                if (!id.contains("spawner") && !lootTable.equals("minecraft:chests/simple_dungeon")) continue;
                                String mob = "";
                                if (id.contains("spawner")) {
                                    mob = tag.getCompoundOrEmpty("SpawnData")
                                        .getCompoundOrEmpty("entity").getString("id").orElse("");
                                }
                                entities.add(new SavedBlockEntity(tag.getIntOr("x", 0), tag.getIntOr("y", 0),
                                    tag.getIntOr("z", 0), id, mob, lootTable, tag.getLongOr("LootTableSeed", 0L),
                                    dataVersion));
                            }
                        }
                    }
                }
            }
        }

        List<SavedBlockEntity> spawners = entities.stream().filter(entity -> entity.id().contains("spawner"))
            .sorted(Comparator.comparingInt(SavedBlockEntity::x).thenComparingInt(SavedBlockEntity::z))
            .limit(limit).toList();
        StringBuilder report = new StringBuilder();
        report.append("region_files=").append(files.size()).append(" chunks_read=").append(chunksRead)
            .append(" block_entities_read=").append(blockEntitiesRead).append('\n');
        report.append("saved_dungeon_spawners=").append(spawners.size()).append('\n');
        for (SavedBlockEntity spawner : spawners) {
            List<SavedBlockEntity> chests = entities.stream().filter(entity ->
                    entity.lootTable().equals("minecraft:chests/simple_dungeon")
                        && Math.abs(entity.x() - spawner.x()) <= 8
                        && Math.abs(entity.y() - spawner.y()) <= 1
                        && Math.abs(entity.z() - spawner.z()) <= 8)
                .toList();
            report.append("spawner=").append(spawner.x()).append(',').append(spawner.y()).append(',')
                .append(spawner.z()).append(" chunk=").append(Math.floorDiv(spawner.x(), 16)).append(',')
                .append(Math.floorDiv(spawner.z(), 16)).append(" mob=").append(spawner.mob())
                .append(" chests=").append(chests.size()).append(" data_version=")
                .append(spawner.dataVersion()).append('\n');
            for (SavedBlockEntity chest : chests) {
                report.append("  chest=").append(chest.x()).append(',').append(chest.y()).append(',')
                    .append(chest.z()).append(" seed=").append(chest.lootSeed()).append('\n');
            }
        }
        Path reportPath = Path.of("build", "reports", "dungeon-saved-oracle.txt").toAbsolutePath();
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString(), StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private record SavedBlockEntity(int x, int y, int z, String id, String mob,
                                    String lootTable, long lootSeed, int dataVersion) {
    }
}
