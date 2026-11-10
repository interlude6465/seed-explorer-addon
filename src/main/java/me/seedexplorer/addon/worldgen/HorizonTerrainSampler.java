package me.seedexplorer.addon.worldgen;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Experimental Horizon-inspired far-terrain sampler.
 *
 * It locates the top density crossing using a coarse vertical scan and a short
 * refinement pass, then assigns a biome-aware surface material. It is intended
 * for distant interactive terrain; the normal
 * Minecraft chunk path remains authoritative for exact terrain and caves.
 */
public final class HorizonTerrainSampler {
    // A 64-block stride can jump completely over the overworld's positive-density
    // band and create sea-level cliffs or empty chunks. Sixteen is still much
    // cheaper than a full column while reliably bracketing normal terrain.
    private static final int COARSE_VERTICAL_STEP = 16;
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
                // Store the representative sample at the cell origin, but sample
                // its center. A corner-only 16x16 sample turned shorelines into a
                // conspicuous checkerboard of full water and full grass chunks.
                int sampleOffset = Math.max(0, step / 2);
                int worldX = baseX + Math.min(15, dx + sampleOffset);
                int worldZ = baseZ + Math.min(15, dz + sampleOffset);
                int groundTop = surfaceHeight(state.density(), state.context(), worldX, worldZ,
                    state.minY(), state.maxY());
                if (groundTop <= state.minY()) continue;

                // Surface material and ocean coverage follow the surface climate.
                // Sampling at the density crossing can return a cave biome and
                // incorrectly turn whole ocean columns into dry grass terrain.
                String biome = WorldgenEngine.getBiome(seed, 0, worldX, 64, worldZ).id();
                int index = dz * 16 + dx;
                BlockState ground = surfaceMaterial(biome, groundTop, state.seaLevel());
                if (groundTop <= state.seaLevel()) {
                    heights[index] = groundTop;
                    states[index] = ground;
                    fluidHeights[index] = state.seaLevel();
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
        DensitySampler density = randomState.getSampler(settings.noiseRouter().finalDensity());
        SamplerContext context = SamplerContext.builder().enableCaches().build();
        SamplerState created = new SamplerState(seed, density, context,
            view.noiseSettings().minY(),
            view.noiseSettings().minY() + view.noiseSettings().height() - 1,
            settings.seaLevel());
        WORKER_STATE.set(created);
        return created;
    }

    private static int surfaceHeight(DensitySampler density, SamplerContext context, int x, int z,
                                     int minY, int maxY) {
        int upper = maxY;
        if (densityAt(density, context, x, upper, z) > 0.0) return upper + 1;

        for (int lower = maxY - COARSE_VERTICAL_STEP; lower >= minY;
             lower -= COARSE_VERTICAL_STEP) {
            int sampleY = Math.max(minY, lower);
            if (densityAt(density, context, x, sampleY, z) > 0.0) {
                // Search the coarse bracket top-down. The first positive
                // interval contains the highest density crossing in the column.
                for (int high = upper; high > sampleY;) {
                    int low = Math.max(sampleY, high - REFINE_VERTICAL_STEP);
                    if (densityAt(density, context, x, low, z) > 0.0) {
                        for (int y = high - 1; y >= low; y--) {
                            if (densityAt(density, context, x, y, z) > 0.0) return y + 1;
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

    private static double densityAt(DensitySampler density, SamplerContext context,
                                    int x, int y, int z) {
        return density.sampleValue(context, x, y, z);
    }

    private static BlockState surfaceMaterial(String biome, int height, int seaLevel) {
        String id = biome == null ? "" : biome;
        if (id.contains("badlands")) {
            return height <= seaLevel + 3 ? Blocks.RED_SAND.defaultBlockState()
                : Blocks.TERRACOTTA.defaultBlockState();
        }
        if (id.contains("desert") || id.contains("beach")) return Blocks.SAND.defaultBlockState();
        if (id.contains("mushroom_fields")) return Blocks.MYCELIUM.defaultBlockState();
        if (id.contains("stony_shore") || id.contains("gravelly")) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        if (id.contains("snow") || id.contains("frozen") || id.contains("ice_spikes")
            || id.contains("grove")) return Blocks.SNOW_BLOCK.defaultBlockState();
        if (id.contains("old_growth_pine") || id.contains("old_growth_spruce")) {
            return Blocks.PODZOL.defaultBlockState();
        }
        if (id.contains("mangrove_swamp")) return Blocks.MUD.defaultBlockState();
        if (height > seaLevel + 40 && (id.contains("mountain") || id.contains("peak")
            || id.contains("windswept"))) return Blocks.STONE.defaultBlockState();
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    public record SampledChunk(int chunkX, int chunkZ, int step, int[] heights,
                               BlockState[] states, int[] fluidHeights,
                               BlockState[] fluidStates) {
    }

    private record SamplerState(long seed, DensitySampler density, SamplerContext context, int minY,
                                int maxY, int seaLevel) {
    }
}
