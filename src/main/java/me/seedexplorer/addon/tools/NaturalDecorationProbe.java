package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.worldgen.GeneratedTerrainHeightmap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Headless determinism and filtering check for 3D-map natural decoration. */
public final class NaturalDecorationProbe {
    private NaturalDecorationProbe() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable error) {
            StringBuilder report = new StringBuilder(error.getClass().getName())
                .append(": ").append(error.getMessage()).append('\n');
            for (StackTraceElement element : error.getStackTrace()) {
                report.append("  at ").append(element).append('\n');
            }
            try {
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of("build", "natural-probe-error.txt"), report);
            } catch (java.io.IOException ignored) {
                // Preserve the original failure below.
            }
            throw new AssertionError("natural-decoration-probe-error="
                + error.getClass().getName() + ": " + error.getMessage(), error);
        }
    }

    private static void run(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 123456789L;
        int dimension = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int chunkX = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int chunkZ = args.length > 3 ? Integer.parseInt(args[3]) : 0;

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        ChunkPos target = new ChunkPos(chunkX, chunkZ);
        GeneratedTerrainHeightmap firstTerrain = new GeneratedTerrainHeightmap(
            seed, dimension, List.of(), false);
        GeneratedTerrainHeightmap.NaturalDecorationSnapshot first =
            firstTerrain.naturalDecorationSnapshot(target);
        GeneratedTerrainHeightmap secondTerrain = new GeneratedTerrainHeightmap(
            seed, dimension, List.of(), false);
        GeneratedTerrainHeightmap.NaturalDecorationSnapshot second =
            secondTerrain.naturalDecorationSnapshot(target);

        List<String> firstKeys = keys(first);
        List<String> secondKeys = keys(second);
        if (!firstKeys.equals(secondKeys)) {
            int mismatch = 0;
            int common = Math.min(firstKeys.size(), secondKeys.size());
            while (mismatch < common && firstKeys.get(mismatch).equals(secondKeys.get(mismatch))) {
                mismatch++;
            }
            throw new AssertionError("Decoration block positions/states changed between identical runs"
                + " firstSize=" + firstKeys.size() + " secondSize=" + secondKeys.size()
                + " mismatch=" + mismatch
                + " first=" + (mismatch < firstKeys.size() ? firstKeys.get(mismatch) : "end")
                + " second=" + (mismatch < secondKeys.size() ? secondKeys.get(mismatch) : "end"));
        }
        if (first.deterministicHash() != second.deterministicHash()) {
            throw new AssertionError("Decoration hash changed despite identical ordered blocks: "
                + first.deterministicHash() + " != " + second.deterministicHash());
        }
        if (first.blocks().isEmpty()) {
            throw new AssertionError("Probe chunk produced no natural decoration blocks");
        }
        for (GeneratedTerrainHeightmap.NaturalDecorationBlock block : first.blocks()) {
            if (!GeneratedTerrainHeightmap.isNaturalDecorationBlock(block.state())) {
                throw new AssertionError("Non-natural block leaked into decoration snapshot: "
                    + block.state());
            }
            if (Math.floorDiv(block.x(), 16) != chunkX
                || Math.floorDiv(block.z(), 16) != chunkZ) {
                throw new AssertionError("Decoration escaped requested chunk: " + block);
            }
        }

        Map<String, Integer> counts = new TreeMap<>();
        for (GeneratedTerrainHeightmap.NaturalDecorationBlock block : first.blocks()) {
            String id = String.valueOf(BuiltInRegistries.BLOCK.getKey(block.state().getBlock()));
            counts.merge(id, 1, Integer::sum);
        }
        System.out.println("natural-decoration seed=" + seed + " dimension=" + dimension
            + " chunk=" + chunkX + "," + chunkZ + " blocks=" + first.blocks().size()
            + " hash=" + Long.toUnsignedString(first.deterministicHash()));
        System.out.println("states=" + counts);
    }

    private static List<String> keys(
        GeneratedTerrainHeightmap.NaturalDecorationSnapshot snapshot) {
        return snapshot.blocks().stream()
            .map(block -> block.x() + "," + block.y() + "," + block.z() + "=" + block.state())
            .toList();
    }
}
