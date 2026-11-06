package me.seedexplorer.addon.worldgen;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Experimental Horizon-inspired far-terrain sampler.
 *
 * It locates the top density crossing using a coarse vertical scan and a short
 * refinement pass, then assigns a deliberately approximate biome surface
 * material. It is intended for visual/performance testing only; the normal
 * Minecraft chunk path remains authoritative for exact terrain and caves.
 */
public final class HorizonTerrainSampler {
    private static final int COARSE_VERTICAL_STEP = 64;
    private static final int REFINE_VERTICAL_STEP = 8;
    private static final ThreadLocal<SamplerState> WORKER_STATE = new ThreadLocal<>();

    private HorizonTerrainSampler() {
    }

    public static SampledChunk sampleChunk(long seed, int chunkX, int chunkZ, int step) {
        if (step < 4 || step > 16 || (step & (step - 1)) != 0) {
            throw new IllegalArgumentException("Horizon sample step must be 4, 8, or 16");
        }

        SamplerState state = sampler(seed);
        int[] heights = new int[16 * 16];
        BlockState[] states = new BlockState[16 * 16];
        int[] fluidHeights = new int[16 * 16];
        BlockState[] fluidStates = new BlockState[16 * 16];
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        for (int dx = 0; dx < 16; dx += step) {
            for (int dz = 0; dz < 16; dz += step) {
                int worldX = baseX + dx;
                int worldZ = baseZ + dz;
                int groundTop = surfaceHeight(state.density(), worldX, worldZ,
                    state.minY(), state.maxY());
                if (groundTop <= state.minY()) continue;

                String biome = WorldgenEngine.getBiome(seed, 0, worldX,
                    groundTop - 1, worldZ).id();
                int index = dz * 16 + dx;
                BlockState ground = surfaceMaterial(biome, groundTop, state.seaLevel());
                if (isWaterBiome(biome) && groundTop <= state.seaLevel()) {
                    heights[index] = groundTop;
                    states[index] = ground;
                    fluidHeights[index] = state.seaLevel() + 1;
                    fluidStates[index] = Blocks.WATER.defaultBlockState();
                } else {
                    heights[index] = groundTop;
                    states[index] = ground;
                }
            }
        }

        return new SampledChunk(chunkX, chunkZ, step, heights, states,
            fluidHeights, fluidStates);
    }

    private static SamplerState sampler(long seed) {
        SamplerState cached = WORKER_STATE.get();
        if (cached != null && cached.seed() == seed) return cached;

        WorldgenEngine.GeneratorView view = WorldgenEngine.generatorView(seed, 0);
        NoiseGeneratorSettings settings = view.lookup().lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        RandomState randomState = view.randomState();
        SamplerState created = new SamplerState(seed,
            randomState.router().finalDensity(), view.noiseSettings().minY(),
            view.noiseSettings().minY() + view.noiseSettings().height() - 1,
            settings.seaLevel());
        WORKER_STATE.set(created);
        return created;
    }

    private static int surfaceHeight(DensityFunction density, int x, int z,
                                     int minY, int maxY) {
        int upper = maxY;
        if (densityAt(density, x, upper, z) > 0.0) return upper + 1;

        for (int lower = maxY - COARSE_VERTICAL_STEP; lower >= minY;
             lower -= COARSE_VERTICAL_STEP) {
            int sampleY = Math.max(minY, lower);
            if (densityAt(density, x, sampleY, z) > 0.0) {
                // Search the coarse bracket top-down. The first positive
                // interval contains the highest density crossing in the column.
                for (int high = upper; high > sampleY;) {
                    int low = Math.max(sampleY, high - REFINE_VERTICAL_STEP);
                    if (densityAt(density, x, low, z) > 0.0) {
                        for (int y = high - 1; y >= low; y--) {
                            if (densityAt(density, x, y, z) > 0.0) return y + 1;
                        }
                        return low + 1;
                    }
                    high = low;
                }
                return sampleY + 1;
            }
            upper = sampleY;
        }
        return minY;
    }

    private static double densityAt(DensityFunction density, int x, int y, int z) {
        return density.compute(new DensityFunction.SinglePointContext(x, y, z));
    }

    private static boolean isWaterBiome(String biome) {
        return biome != null && (biome.contains("ocean") || biome.endsWith(":river"));
    }

    private static BlockState surfaceMaterial(String biome, int height, int seaLevel) {
        String id = biome == null ? "" : biome;
        if (id.contains("badlands")) return Blocks.TERRACOTTA.defaultBlockState();
        if (id.contains("desert") || id.contains("beach")) return Blocks.SAND.defaultBlockState();
        if (id.contains("mushroom_fields")) return Blocks.MYCELIUM.defaultBlockState();
        if (height > seaLevel + 56 && (id.contains("mountain") || id.contains("peaks")
            || id.contains("windswept"))) return Blocks.STONE.defaultBlockState();
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    public record SampledChunk(int chunkX, int chunkZ, int step, int[] heights,
                               BlockState[] states, int[] fluidHeights,
                               BlockState[] fluidStates) {
    }

    private record SamplerState(long seed, DensityFunction density, int minY,
                                int maxY, int seaLevel) {
    }
}
