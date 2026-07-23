package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.worldgen.GeneratedTerrainHeightmap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compares saved Paper block occupancy with the offline terrain pipeline. */
public final class TerrainBlockParityReport {
    private static final PrintStream OUT = System.out;

    private TerrainBlockParityReport() {
    }

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> error.printStackTrace(OUT));
        if (args.length != 10) {
            throw new IllegalArgumentException(
                "Usage: <region-dir> <chunk-x> <chunk-z> <seed> <min-x> <min-y> <min-z> <max-x> <max-y> <max-z>");
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path regionDir = Path.of(args[0]).toAbsolutePath();
        int chunkX = Integer.parseInt(args[1]);
        int chunkZ = Integer.parseInt(args[2]);
        long seed = Long.parseLong(args[3]);
        int minX = Integer.parseInt(args[4]);
        int minY = Integer.parseInt(args[5]);
        int minZ = Integer.parseInt(args[6]);
        int maxX = Integer.parseInt(args[7]);
        int maxY = Integer.parseInt(args[8]);
        int maxZ = Integer.parseInt(args[9]);

        CompoundTag root = readChunk(regionDir, new ChunkPos(chunkX, chunkZ));
        Map<Integer, SectionBlocks> sections = decodeSections(root.getListOrEmpty("sections"));
        GeneratedTerrainHeightmap terrain = new GeneratedTerrainHeightmap(seed, 0, List.of());
        int checked = 0;
        int serverAir = 0;
        int offlineAir = 0;
        int occupancyMismatches = 0;
        int idMismatches = 0;
        int printed = 0;

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    String server = block(sections, x, y, z);
                    String offline = terrain.blockStateId(x, y, z);
                    boolean serverIsAir = isAir(server);
                    boolean offlineIsAir = isAir(offline);
                    checked++;
                    if (serverIsAir) serverAir++;
                    if (offlineIsAir) offlineAir++;
                    if (serverIsAir != offlineIsAir) {
                        occupancyMismatches++;
                        if (printed++ < 50) OUT.println("occupancy_mismatch=" + x + "," + y + "," + z
                            + " paper=" + server + " offline=" + offline);
                    }
                    if (!server.equals(offline)) idMismatches++;
                }
            }
        }
        OUT.println("terrain_block_parity checked=" + checked + " paper_air=" + serverAir
            + " offline_air=" + offlineAir + " occupancy_mismatches=" + occupancyMismatches
            + " block_id_mismatches=" + idMismatches);
        if (occupancyMismatches != 0) System.exit(2);
    }

    private static CompoundTag readChunk(Path regionDir, ChunkPos chunk) throws Exception {
        Path file = regionDir.resolve("r." + Math.floorDiv(chunk.x(), 32)
            + "." + Math.floorDiv(chunk.z(), 32) + ".mca");
        RegionStorageInfo info = new RegionStorageInfo("terrain_world", Level.OVERWORLD, "chunk");
        try (RegionFile region = new RegionFile(info, file, regionDir, false);
             DataInputStream input = region.getChunkDataInputStream(chunk)) {
            if (input == null) throw new IllegalStateException("Missing Paper chunk " + chunk);
            return NbtIo.read(input, NbtAccounter.unlimitedHeap());
        }
    }

    private static Map<Integer, SectionBlocks> decodeSections(ListTag tags) {
        Map<Integer, SectionBlocks> result = new HashMap<>();
        for (CompoundTag section : tags.compoundStream().toList()) {
            int sectionY = section.getIntOr("Y", Integer.MIN_VALUE);
            CompoundTag states = section.getCompoundOrEmpty("block_states");
            List<String> palette = states.getListOrEmpty("palette").compoundStream()
                .map(entry -> entry.getStringOr("Name", "minecraft:air")).toList();
            if (palette.isEmpty()) continue;
            long[] data = states.getLongArray("data").orElseGet(() -> new long[0]);
            result.put(sectionY, new SectionBlocks(palette, data));
        }
        return result;
    }

    private static String block(Map<Integer, SectionBlocks> sections, int x, int y, int z) {
        SectionBlocks section = sections.get(Math.floorDiv(y, 16));
        if (section == null) return "minecraft:air";
        int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        return section.block(index);
    }

    private static boolean isAir(String id) {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air")
            || id.equals("minecraft:void_air");
    }

    private record SectionBlocks(List<String> palette, long[] data) {
        private String block(int index) {
            if (palette.size() == 1 || data.length == 0) return palette.getFirst();
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            int valuesPerLong = 64 / bits;
            int wordIndex = index / valuesPerLong;
            int bitIndex = (index % valuesPerLong) * bits;
            int paletteIndex = (int) ((data[wordIndex] >>> bitIndex) & ((1L << bits) - 1));
            return paletteIndex < palette.size() ? palette.get(paletteIndex) : "minecraft:air";
        }
    }
}
