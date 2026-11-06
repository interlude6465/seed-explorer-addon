package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

import java.io.DataInputStream;
import java.io.PrintStream;
import java.nio.file.Path;

/** Replays one chunk from a Paper-serialized structure start for parity research. */
public final class StrongholdSavedStartSimulation {
    private static final PrintStream OUT = System.out;

    private StrongholdSavedStartSimulation() {
    }

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> error.printStackTrace(OUT));
        if (args.length < 8) {
            throw new IllegalArgumentException("Usage: <region-dir> <start-x> <start-z>"
                + " <placement-x> <placement-z> <seed> <index|first..last> <expected-seed>"
                + " [structure-id] [dimension]");
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path regionDir = Path.of(args[0]).toAbsolutePath();
        int startX = Integer.parseInt(args[1]);
        int startZ = Integer.parseInt(args[2]);
        int placementX = Integer.parseInt(args[3]);
        int placementZ = Integer.parseInt(args[4]);
        long seed = Long.parseLong(args[5]);
        String indexArg = args[6];
        long expectedSeed = Long.parseLong(args[7]);

        String structureId = args.length > 8 ? args[8] : "minecraft:stronghold";
        String dimensionId = args.length > 9 ? args[9] : "minecraft:overworld";
        int dimension = switch (dimensionId) {
            case "minecraft:the_nether" -> -1;
            case "minecraft:the_end" -> 1;
            default -> 0;
        };
        CompoundTag stronghold = readStructureStart(
            regionDir, startX, startZ, structureId, dimensionId);
        var context = new StructurePieceSerializationContext(
            null, WorldgenEngine.offlineRegistryAccess(),
            WorldgenEngine.structureTemplateManager());
        StructureStart savedStart = StructureStart.loadStaticStart(context, stronghold, seed);
        if (savedStart == null || !savedStart.isValid()) {
            throw new IllegalStateException("Paper structure start could not be loaded");
        }

        BoundingBox writable = new BoundingBox(
            placementX * 16, -64, placementZ * 16,
            placementX * 16 + 15, 319, placementZ * 16 + 15);
        OUT.println("saved_start_loaded=true pieces=" + savedStart.getPieces().size());
        for (int i = 0; i < savedStart.getPieces().size(); i++) {
            var piece = savedStart.getPieces().get(i);
            if (!piece.getBoundingBox().intersects(writable)) continue;
            OUT.println("intersecting_piece index=" + i
                + " class=" + piece.getClass().getSimpleName()
                + " depth=" + piece.getGenDepth()
                + " orientation=" + piece.getOrientation()
                + " box=" + piece.getBoundingBox());
        }

        int firstIndex;
        int lastIndex;
        if (indexArg.contains("..")) {
            String[] range = indexArg.split("\\.\\.", 2);
            firstIndex = Integer.parseInt(range[0]);
            lastIndex = Integer.parseInt(range[1]);
        } else {
            firstIndex = lastIndex = Integer.parseInt(indexArg);
        }
        boolean matched = false;
        for (int index = firstIndex; index <= lastIndex; index++) {
            var containers = VanillaLootStructureSimulator.simulateStartChunk(
                seed, dimension, savedStart, placementX, placementZ, index);
            for (var container : containers) {
                OUT.println("saved_start_index=" + index + " container=" + container.x() + ","
                    + container.y() + "," + container.z() + " table=" + container.lootTableId()
                    + " seed=" + container.lootSeed());
                if (container.lootSeed() == expectedSeed) {
                    matched = true;
                    OUT.println("saved_start_expected_match_index=" + index);
                }
            }
        }
        OUT.println("saved_start_expected_match=" + matched);
        if (!matched) System.exit(2);
    }

    private static CompoundTag readStructureStart(Path regionDir, int chunkX,
                                                   int chunkZ, String structureId,
                                                   String dimensionId) throws Exception {
        ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
        Path file = regionDir.resolve("r." + Math.floorDiv(chunkX, 32)
            + "." + Math.floorDiv(chunkZ, 32) + ".mca");
        var dimension = switch (dimensionId) {
            case "minecraft:the_nether" -> Level.NETHER;
            case "minecraft:the_end" -> Level.END;
            default -> Level.OVERWORLD;
        };
        RegionStorageInfo info = new RegionStorageInfo("probe_world", dimension, "chunk");
        try (RegionFile region = new RegionFile(info, file, regionDir, false);
             DataInputStream input = region.getChunkDataInputStream(chunk)) {
            if (input == null) throw new IllegalStateException("Start chunk is absent: " + chunk);
            CompoundTag root = NbtIo.read(input, NbtAccounter.unlimitedHeap());
            CompoundTag start = root.getCompoundOrEmpty("structures")
                .getCompoundOrEmpty("starts")
                .getCompoundOrEmpty(structureId);
            if (start.isEmpty()) throw new IllegalStateException(
                structureId + " start NBT is absent");
            return start;
        }
    }
}
