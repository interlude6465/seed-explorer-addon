package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.worldgen.HorizonTerrainSampler;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

/** Measures Horizon-style surface sampling against the add-on's base-column path. */
public final class HorizonTerrainSamplerProbe {
    private HorizonTerrainSamplerProbe() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        long seed = args.length > 0 ? Long.parseLong(args[0]) : 42L;
        int startChunkX = args.length > 1 ? Integer.parseInt(args[1]) : -2;
        int startChunkZ = args.length > 2 ? Integer.parseInt(args[2]) : -2;
        int chunksPerSide = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        int step = args.length > 4 ? Integer.parseInt(args[4]) : 8;
        if (chunksPerSide < 1 || chunksPerSide > 64) {
            throw new IllegalArgumentException("chunksPerSide must be from 1 through 64");
        }
        if (step != 4 && step != 8 && step != 16) {
            throw new IllegalArgumentException("step must be 4, 8, or 16");
        }

        // Initialize the shared generator and density graph before timing either path.
        HorizonTerrainSampler.sampleChunk(seed, startChunkX - 64, startChunkZ - 64, step);
        WorldgenEngine.terrainAccessor(seed, 0).oceanFloorHeight(-1024, -1024);

        int samples = chunksPerSide * chunksPerSide * (16 / step) * (16 / step);
        int[] referenceHeights = new int[samples];
        int cursor = 0;
        var reference = WorldgenEngine.terrainAccessor(seed, 0);
        long referenceStart = System.nanoTime();
        for (int cz = 0; cz < chunksPerSide; cz++) {
            for (int cx = 0; cx < chunksPerSide; cx++) {
                int baseX = (startChunkX + cx) << 4;
                int baseZ = (startChunkZ + cz) << 4;
                for (int dx = 0; dx < 16; dx += step) {
                    for (int dz = 0; dz < 16; dz += step) {
                        int x = baseX + dx;
                        int z = baseZ + dz;
                        int top = reference.oceanFloorHeight(x, z);
                        reference.block(x, top - 1, z);
                        referenceHeights[cursor++] = top;
                    }
                }
            }
        }
        long referenceNanos = System.nanoTime() - referenceStart;

        int[] horizonHeights = new int[samples];
        cursor = 0;
        long horizonStart = System.nanoTime();
        for (int cz = 0; cz < chunksPerSide; cz++) {
            for (int cx = 0; cx < chunksPerSide; cx++) {
                HorizonTerrainSampler.SampledChunk chunk = HorizonTerrainSampler.sampleChunk(
                    seed, startChunkX + cx, startChunkZ + cz, step);
                for (int dx = 0; dx < 16; dx += step) {
                    for (int dz = 0; dz < 16; dz += step) {
                        int index = dz * 16 + dx;
                        horizonHeights[cursor++] = chunk.heights()[index];
                    }
                }
            }
        }
        long horizonNanos = System.nanoTime() - horizonStart;

        long totalError = 0L;
        int maximumError = 0;
        for (int i = 0; i < samples; i++) {
            int error = Math.abs(referenceHeights[i] - horizonHeights[i]);
            totalError += error;
            maximumError = Math.max(maximumError, error);
        }
        double referenceMs = referenceNanos / 1_000_000.0;
        double horizonMs = horizonNanos / 1_000_000.0;
        System.out.printf("seed=%d chunks=%d step=%d samples=%d%n", seed,
            chunksPerSide * chunksPerSide, step, samples);
        System.out.printf("baseColumnMs=%.2f horizonStyleMs=%.2f speedup=%.2fx%n",
            referenceMs, horizonMs, referenceMs / Math.max(0.001, horizonMs));
        System.out.printf("meanHeightError=%.2f maxHeightError=%d%n",
            totalError / (double) samples, maximumError);
    }
}
