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
import java.io.PrintStream;
import java.nio.file.Path;

/** Read-only inspection of a generated Anvil chunk's structure-start NBT. */
public final class SavedChunkNbtReport {
    private static final PrintStream OUT = System.out;

    private SavedChunkNbtReport() {
    }

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> error.printStackTrace(OUT));
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: <region-dir> <chunk-x> <chunk-z>");
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path regionDir = Path.of(args[0]).toAbsolutePath();
        int chunkX = Integer.parseInt(args[1]);
        int chunkZ = Integer.parseInt(args[2]);
        ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
        Path file = regionDir.resolve("r." + Math.floorDiv(chunkX, 32)
            + "." + Math.floorDiv(chunkZ, 32) + ".mca");
        RegionStorageInfo info = new RegionStorageInfo(
            "probe_world", Level.OVERWORLD, "chunk");

        try (RegionFile region = new RegionFile(info, file, regionDir, false);
             DataInputStream input = region.getChunkDataInputStream(chunk)) {
            if (input == null) {
                OUT.println("saved_chunk_present=false chunk=" + chunkX + "," + chunkZ);
                System.exit(2);
                return;
            }
            CompoundTag root = NbtIo.read(input, NbtAccounter.unlimitedHeap());
            CompoundTag structures = root.getCompoundOrEmpty("structures");
            CompoundTag starts = structures.getCompoundOrEmpty("starts");
            CompoundTag references = structures.getCompoundOrEmpty("References");
            CompoundTag stronghold = starts.getCompoundOrEmpty("minecraft:stronghold");
            var children = stronghold.getListOrEmpty("Children");
            OUT.println("saved_chunk_present=true chunk=" + chunkX + "," + chunkZ);
            OUT.println("root_keys=" + root.keySet());
            OUT.println("structure_keys=" + structures.keySet());
            OUT.println("start_keys=" + starts.keySet());
            OUT.println("reference_keys=" + references.keySet());
            OUT.println("references=" + references);
            OUT.println("stronghold_keys=" + stronghold.keySet());
            OUT.println("stronghold_children=" + children.size());
            OUT.println("stronghold_start=" + stronghold);
        }
    }
}
