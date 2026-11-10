package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.worldgen.GeneratedTerrainHeightmap;
import me.seedexplorer.addon.worldgen.HorizonTerrainSampler;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Measures sustained exact and interactive terrain generation throughput. */
public final class TerrainThroughputProbe {
    private TerrainThroughputProbe() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        long seed = args.length > 0 ? Long.parseLong(args[0]) : 42L;
        int chunks = args.length > 1 ? Integer.parseInt(args[1]) : 96;
        int maximumThreads = args.length > 2 ? Integer.parseInt(args[2]) : 16;
        if (chunks < 16 || chunks > 4096) {
            throw new IllegalArgumentException("chunks must be from 16 through 4096");
        }

        // Exclude registry/bootstrap and JIT startup from the sustained samples.
        new GeneratedTerrainHeightmap(seed, 0, List.of(), false)
            .getChunk(new ChunkPos(-64, -64));
        HorizonTerrainSampler.sampleChunk(seed, -65, -65, 8);

        for (int threads = 1; threads <= maximumThreads; threads *= 2) {
            measureExact(seed, chunks, threads);
        }
        measureHorizon(seed, chunks, Math.min(maximumThreads,
            Runtime.getRuntime().availableProcessors()));
    }

    private static void measureExact(long seed, int chunks, int threads) throws Exception {
        ThreadLocal<GeneratedTerrainHeightmap> generators = ThreadLocal.withInitial(() ->
            new GeneratedTerrainHeightmap(seed, 0, List.of(), false));
        long started = System.nanoTime();
        try (var executor = Executors.newFixedThreadPool(threads)) {
            List<Callable<Void>> jobs = new ArrayList<>(chunks);
            for (int index = 0; index < chunks; index++) {
                int chunkX = 256 + index % 32;
                int chunkZ = 256 + index / 32;
                jobs.add(() -> {
                    generators.get().getChunk(new ChunkPos(chunkX, chunkZ));
                    return null;
                });
            }
            executor.invokeAll(jobs).forEach(future -> {
                try {
                    future.get();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
        print("exact", chunks, threads, System.nanoTime() - started);
    }

    private static void measureHorizon(long seed, int chunks, int threads) throws Exception {
        long started = System.nanoTime();
        try (var executor = Executors.newFixedThreadPool(threads)) {
            List<Callable<Void>> jobs = new ArrayList<>(chunks);
            for (int index = 0; index < chunks; index++) {
                int chunkX = 512 + index % 32;
                int chunkZ = 512 + index / 32;
                jobs.add(() -> {
                    HorizonTerrainSampler.sampleChunk(seed, chunkX, chunkZ, 8);
                    return null;
                });
            }
            executor.invokeAll(jobs).forEach(future -> {
                try {
                    future.get();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
        print("horizon-8", chunks, threads, System.nanoTime() - started);
    }

    private static void print(String mode, int chunks, int threads, long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        System.out.printf("mode=%s chunks=%d threads=%d seconds=%.3f chunksPerSecond=%.1f%n",
            mode, chunks, threads, seconds, chunks / Math.max(0.001, seconds));
    }
}
