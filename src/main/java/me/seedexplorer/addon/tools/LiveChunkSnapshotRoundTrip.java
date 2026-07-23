package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.live.LiveChunkSnapshot;
import me.seedexplorer.addon.live.LiveChunkStore;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Verifies observed chunk snapshot compression and restore semantics. */
public final class LiveChunkSnapshotRoundTrip {
    private LiveChunkSnapshotRoundTrip() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();
        LiveChunkSnapshot snapshot = new LiveChunkSnapshot(0, -12, 34, -64, 320);
        snapshot.setBlock(0, -64, 0, Blocks.BEDROCK.defaultBlockState());
        snapshot.setBlock(0, 63, 0, Blocks.STONE.defaultBlockState());
        snapshot.setBlock(0, 64, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        snapshot.setBlock(5, 70, 6, Blocks.OAK_PLANKS.defaultBlockState());
        snapshot.setBlock(5, 71, 6, Blocks.CHEST.defaultBlockState());
        snapshot.setBlock(15, 319, 15, Blocks.DIAMOND_BLOCK.defaultBlockState());

        Path dir = Files.createTempDirectory("seed-explorer-live-chunk-test");
        Path file = dir.resolve("chunk.nbt.gz");
        LiveChunkStore.get().saveTo(file, "roundtrip:test", snapshot);
        LiveChunkSnapshot loaded = LiveChunkStore.get().loadFrom(file, 0, -12, 34);
        if (loaded == null) {
            failures.add("loaded snapshot was null");
        } else {
            check(failures, "dimension", loaded.dimension() == 0);
            check(failures, "chunk", loaded.chunkX() == -12 && loaded.chunkZ() == 34);
            check(failures, "surface 0,0", loaded.surfaceY(0, 0) == 65);
            check(failures, "surface 5,6", loaded.surfaceY(5, 6) == 72);
            check(failures, "surface 15,15", loaded.surfaceY(15, 15) == 320);
            check(failures, "grass", loaded.block(0, 64, 0).is(Blocks.GRASS_BLOCK));
            check(failures, "chest", loaded.block(5, 71, 6).is(Blocks.CHEST));
            check(failures, "diamond", loaded.block(15, 319, 15).is(Blocks.DIAMOND_BLOCK));
            check(failures, "air omitted", loaded.block(1, 64, 1) == null);
        }

        Files.deleteIfExists(file);
        Files.deleteIfExists(dir);

        System.out.println("live_chunk_snapshot_roundtrip_pass=" + failures.isEmpty()
            + " failures=" + failures.size());
        failures.forEach(failure -> System.out.println("  " + failure));
        if (!failures.isEmpty()) System.exit(2);
    }

    private static void check(List<String> failures, String label, boolean condition) {
        if (!condition) failures.add(label);
    }
}
