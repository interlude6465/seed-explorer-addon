package me.seedexplorer.standalone;

import me.seedexplorer.addon.live.LiveChunkSnapshot;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.loot.StandaloneStructureLootPredictor;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.PredictionContext;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Reads an independently generated, already-unpacked pyramid region without modifying it. */
final class StandaloneSavedPyramidProbe {
    private StandaloneSavedPyramidProbe() { }

    static void verify(PredictionContext context, int chunkX, int chunkZ, Path file, PrintStream out)
        throws Exception {
        var start = WorldgenEngine.generateSelectedStructureStart(context.worldSeed(), 0,
            BuiltinStructures.DESERT_PYRAMID, new ChunkPos(chunkX, chunkZ));
        if (!start.isValid()) throw new AssertionError("No pyramid at supplied start");
        var box = start.getBoundingBox();
        var chunks = box.intersectingChunks().toList();
        if (chunks.stream().anyMatch(chunk -> Math.floorDiv(chunk.x(), 32) != Math.floorDiv(chunkX, 32)
            || Math.floorDiv(chunk.z(), 32) != Math.floorDiv(chunkZ, 32))) {
            throw new IllegalArgumentException("This saved-region probe requires the footprint in one region");
        }
        var canonical = StandaloneStructureLootPredictor.predict(context,
            BuiltinStructures.DESERT_PYRAMID, chunkX, chunkZ, 64);
        Map<BlockPos, List<ItemStack>> actual = new LinkedHashMap<>();
        List<LiveChunkSnapshot> snapshots = new ArrayList<>();
        var ops = RegistryOps.create(NbtOps.INSTANCE, WorldgenEngine.offlineRegistryAccess());
        try (RandomAccessFile region = new RandomAccessFile(file.toFile(), "r")) {
            for (ChunkPos chunk : chunks) {
                CompoundTag root = readChunk(region, chunk);
                var builder = LiveChunkSnapshot.captureBuilder(0, chunk.x(), chunk.z(), -64, 320);
                for (var value : root.getListOrEmpty("sections")) {
                    if (!(value instanceof CompoundTag section)) throw new AssertionError("Invalid section NBT");
                    int baseY = section.getIntOr("Y", 0) * 16;
                    var states = section.getCompoundOrEmpty("block_states");
                    List<BlockState> palette = new ArrayList<>();
                    for (var entry : states.getListOrEmpty("palette")) {
                        palette.add(BlockState.CODEC.parse(NbtOps.INSTANCE, entry).getOrThrow());
                    }
                    if (palette.isEmpty()) continue;
                    long[] data = states.getLongArray("data").orElse(new long[0]);
                    int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                    int perLong = 64 / bits;
                    long mask = (1L << bits) - 1;
                    for (int index = 0; index < 4096; index++) {
                        int paletteIndex = palette.size() == 1 ? 0
                            : (int) (data[index / perLong] >>> (index % perLong * bits) & mask);
                        var state = palette.get(paletteIndex);
                        if (!state.isAir()) builder.addBlock(index & 15, baseY + (index >> 8),
                            index >> 4 & 15, state);
                    }
                }
                var snapshot = builder.build();
                if (!snapshot.usableForTerrainOverride()) throw new AssertionError("Incomplete saved chunk " + chunk);
                snapshots.add(snapshot);
                for (var value : root.getListOrEmpty("block_entities")) {
                    if (!(value instanceof CompoundTag entity)) throw new AssertionError("Invalid block entity NBT");
                    String id = entity.getStringOr("id", "");
                    if (!id.equals("minecraft:chest") && !id.equals("minecraft:brushable_block")) continue;
                    var pos = new BlockPos(entity.getIntOr("x", 0), entity.getIntOr("y", 0), entity.getIntOr("z", 0));
                    if (pos.getX() < box.minX() || pos.getX() > box.maxX()
                        || pos.getZ() < box.minZ() || pos.getZ() > box.maxZ()) continue;
                    if (entity.contains("LootTable")) {
                        throw new AssertionError("Saved inventory has not been independently unpacked at " + pos);
                    }
                    List<ItemStack> items = new ArrayList<>();
                    if (id.equals("minecraft:chest")) {
                        for (var item : entity.getListOrEmpty("Items")) {
                            items.add(ItemStack.CODEC.parse(ops, item).getOrThrow());
                        }
                    } else {
                        var item = entity.getCompoundOrEmpty("item");
                        if (!item.isEmpty()) items.add(ItemStack.CODEC.parse(ops, item).getOrThrow());
                    }
                    if (actual.put(pos, List.copyOf(items)) != null) throw new AssertionError("Duplicate saved container");
                }
            }
        }
        if (actual.isEmpty()) throw new AssertionError("No independently saved containers");
        boolean needsCorrection = canonical.stream().anyMatch(chest -> !actual.containsKey(
            new BlockPos(chest.blockX(), chest.blockY(), chest.blockZ())));
        if (!needsCorrection) throw new AssertionError("Fixture does not exercise a position correction");
        var marker = new GeneratedStructure(chunkX * 16, chunkZ * 16, chunkX, chunkZ,
            StructureType.DESERT_PYRAMID, "minecraft:desert_pyramid", false);
        var refined = StandaloneObservedLootRefinement.refine(context, marker, canonical, snapshots);
        if (refined.size() != actual.size()) throw new AssertionError("Container count differs after refinement");
        for (var chest : refined) {
            var pos = new BlockPos(chest.blockX(), chest.blockY(), chest.blockZ());
            var observed = actual.get(pos);
            if (observed == null || chest.lootTableId().isBlank() || chest.exact()) {
                throw new AssertionError("Expected uniquely modeled, still approximate container at " + pos);
            }
            var expected = LootTableSimulator.simulateContainerStacks(context, chest.lootTableId(), chest.seed(),
                chest.blockX(), chest.blockY(), chest.blockZ(),
                chest.lootTableId().startsWith("minecraft:archaeology/") ? 1 : 27);
            if (expected == null || !sameStacks(expected, observed)) {
                throw new AssertionError("Saved inventory mismatch at " + pos + " predicted=" + expected
                    + " actual=" + observed);
            }
            out.println("SAVED_INVENTORY_MATCH pos=" + pos + " table=" + chest.lootTableId()
                + " predictedLootSeed=" + chest.seed() + " stacks=" + observed.size());
        }
        out.println("SAVED_PYRAMID_PASS seed=" + context.worldSeed() + " start=" + chunkX + "," + chunkZ
            + " canonical=" + canonical.size() + " refined=" + refined.size()
            + " regionSha256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
    }

    private static boolean sameStacks(List<ItemStack> predicted, List<ItemStack> actual) {
        if (predicted.size() != actual.size()) return false;
        List<ItemStack> remaining = new ArrayList<>(actual);
        for (ItemStack expected : predicted) {
            int found = -1;
            for (int i = 0; i < remaining.size(); i++) {
                if (expected.getCount() == remaining.get(i).getCount()
                    && ItemStack.isSameItemSameComponents(expected, remaining.get(i))) { found = i; break; }
            }
            if (found < 0) return false;
            remaining.remove(found);
        }
        return remaining.isEmpty();
    }

    private static CompoundTag readChunk(RandomAccessFile region, ChunkPos chunk) throws Exception {
        region.seek((Math.floorMod(chunk.x(), 32) + Math.floorMod(chunk.z(), 32) * 32L) * 4L);
        int location = region.readInt();
        if (location == 0) throw new AssertionError("Saved chunk missing " + chunk);
        region.seek((location >>> 8) * 4096L);
        int length = region.readInt();
        int compression = region.readUnsignedByte();
        if (length < 1 || length > (location & 255) * 4096 - 4) throw new AssertionError("Invalid region length");
        byte[] bytes = new byte[length - 1];
        region.readFully(bytes);
        var raw = new ByteArrayInputStream(bytes);
        try (var input = new DataInputStream(switch (compression) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            default -> throw new IllegalArgumentException("Unsupported region compression " + compression);
        })) {
            var root = NbtIo.read(input, NbtAccounter.unlimitedHeap());
            if (root.getIntOr("xPos", Integer.MIN_VALUE) != chunk.x()
                || root.getIntOr("zPos", Integer.MIN_VALUE) != chunk.z()) {
                throw new AssertionError("Region does not contain requested chunk " + chunk);
            }
            return root;
        }
    }
}
