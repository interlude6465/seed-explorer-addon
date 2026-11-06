package me.seedexplorer.addon.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Standalone natural-terrain search prototype. It deliberately has no screen,
 * module, or command dependency so the scoring model can be tuned independently.
 */
public final class NaturalFeatureFinder {
    private static final double TWO_PI = Math.PI * 2.0;

    private NaturalFeatureFinder() {
    }

    public static List<Result> search(Request request) {
        request.validate();
        List<Result> results = new ArrayList<>();
        WorldgenEngine.TerrainAccessor terrain = WorldgenEngine.terrainAccessor(
            request.seed(), request.dimension());
        GeneratedTerrainHeightmap carved = request.requireDeepCave()
            ? new GeneratedTerrainHeightmap(request.seed(), request.dimension(), List.of(), false)
            : null;

        int endX = request.originX() + request.radiusBlocks();
        int endZ = request.originZ() + request.radiusBlocks();
        for (int z = request.originZ() - request.radiusBlocks(); z <= endZ;
             z += request.gridStep()) {
            for (int x = request.originX() - request.radiusBlocks(); x <= endX;
                 x += request.gridStep()) {
                Result result = evaluate(request, terrain, carved, x, z);
                if (result != null) results.add(result);
            }
        }
        results.sort(Comparator.comparingDouble(Result::score).reversed());
        return topResults(results, request.maxResults());
    }

    /** Searches a seed interval using the same spatial query and keeps its best matches. */
    public static List<Result> searchSeeds(Request template, long firstSeed, long lastSeed) {
        template.validate();
        if (firstSeed > lastSeed) throw new IllegalArgumentException("firstSeed must not exceed lastSeed");
        List<Result> results = new ArrayList<>();
        for (long seed = firstSeed; ; seed++) {
            results.addAll(search(template.withSeed(seed)));
            if (seed == lastSeed) break;
        }
        return topResults(results, template.maxResults());
    }

    private static List<Result> topResults(List<Result> results, int maximum) {
        results.sort(Comparator.comparingDouble(Result::score).reversed()
            .thenComparingLong(Result::seed).thenComparingInt(Result::x).thenComparingInt(Result::z));
        return results.size() <= maximum
            ? List.copyOf(results) : List.copyOf(results.subList(0, maximum));
    }

    private static Result evaluate(Request request, WorldgenEngine.TerrainAccessor terrain,
                                   GeneratedTerrainHeightmap carved, int centerX, int centerZ) {
        int centerY = terrain.oceanFloorHeight(centerX, centerZ);
        if (centerY <= 0) return null;

        int[] ringHeights = new int[request.ringSamples()];
        double ringSum = 0.0;
        int ringMin = Integer.MAX_VALUE;
        int ringMax = Integer.MIN_VALUE;
        for (int i = 0; i < ringHeights.length; i++) {
            double angle = TWO_PI * i / ringHeights.length;
            int x = centerX + (int) Math.round(Math.cos(angle) * request.ringRadiusBlocks());
            int z = centerZ + (int) Math.round(Math.sin(angle) * request.ringRadiusBlocks());
            int y = terrain.oceanFloorHeight(x, z);
            ringHeights[i] = y;
            ringSum += y;
            ringMin = Math.min(ringMin, y);
            ringMax = Math.max(ringMax, y);
        }

        double ringMean = ringSum / ringHeights.length;
        double ringSpread = ringMax - ringMin;
        double outerSum = 0.0;
        for (int i = 0; i < ringHeights.length; i++) {
            double angle = TWO_PI * i / ringHeights.length;
            int x = centerX + (int) Math.round(Math.cos(angle) * request.outerRadiusBlocks());
            int z = centerZ + (int) Math.round(Math.sin(angle) * request.outerRadiusBlocks());
            outerSum += terrain.oceanFloorHeight(x, z);
        }
        double outerMean = outerSum / ringHeights.length;
        double centerDrop = ringMean - Math.max(centerY, outerMean);
        double ringError = 0.0;
        for (int height : ringHeights) {
            ringError += Math.abs(height - ringMean);
        }
        ringError /= ringHeights.length;
        if (centerDrop < request.minimumRingRelief()
            || ringError > request.maximumRingError()) return null;

        String centerBiome = WorldgenEngine.getBiome(request.seed(), request.dimension(),
            centerX, centerY, centerZ).id();
        boolean river = centerBiome != null && (centerBiome.endsWith(":river")
            || centerBiome.contains("river"));
        if (request.requireRiver() && !river) return null;

        int caveDepth = 0;
        if (request.requireDeepCave()) {
            caveDepth = deepCaveDepth(request, carved, centerX, centerY, centerZ);
            if (caveDepth < request.minimumCaveDepth()) return null;
        }

        double score = centerDrop * 4.0 - ringError * 2.0 + ringSpread * 0.15;
        if (river) score += 10.0;
        if (caveDepth > 0) score += Math.min(50.0, caveDepth * 0.25);
        return new Result(request.seed(), centerX, centerY, centerZ, score, ringMean,
            ringSpread, centerDrop, centerBiome, caveDepth, ringHeights);
    }

    private static int deepCaveDepth(Request request, GeneratedTerrainHeightmap carved,
                                     int centerX, int surfaceY, int centerZ) {
        if (carved == null) return 0;
        int deepest = surfaceY;
        int minY = carved.minBuildHeight();
        int radius = Math.max(1, request.caveProbeRadiusBlocks());
        for (int z = centerZ - radius; z <= centerZ + radius; z++) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                for (int y = surfaceY - 4; y >= minY; y--) {
                    BlockState state = carved.blockState(request.seed(), x, y, z);
                    if (state == null || !state.isAir()) continue;
                    if (!hasSolidNeighbour(carved, request.seed(), x, y, z)) continue;
                    deepest = Math.min(deepest, y);
                }
            }
        }
        return deepest < surfaceY ? surfaceY - deepest : 0;
    }

    private static boolean hasSolidNeighbour(GeneratedTerrainHeightmap carved, long seed,
                                              int x, int y, int z) {
        return solid(carved.blockState(seed, x + 1, y, z))
            || solid(carved.blockState(seed, x - 1, y, z))
            || solid(carved.blockState(seed, x, y, z + 1))
            || solid(carved.blockState(seed, x, y, z - 1))
            || solid(carved.blockState(seed, x, y + 1, z))
            || solid(carved.blockState(seed, x, y - 1, z));
    }

    private static boolean solid(BlockState state) {
        return state != null && !state.isAir() && state.isSolidRender();
    }

    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    public record Request(long seed, int dimension, int originX, int originZ,
                          int radiusBlocks, int gridStep, int ringRadiusBlocks,
                          int outerRadiusBlocks, int ringSamples, double minimumRingRelief,
                          double maximumRingError, boolean requireRiver, boolean requireDeepCave,
                          int minimumCaveDepth, int caveProbeRadiusBlocks, int maxResults) {
        public static Request ringRiver(long seed, int originX, int originZ, int radiusBlocks) {
            return new Request(seed, 0, originX, originZ, radiusBlocks, 32,
                96, 192, 24, 12.0, 18.0, true, false, 0, 2, 20);
        }

        public Request withSeed(long value) {
            return new Request(value, dimension, originX, originZ, radiusBlocks, gridStep,
                ringRadiusBlocks, outerRadiusBlocks, ringSamples, minimumRingRelief,
                maximumRingError, requireRiver, requireDeepCave, minimumCaveDepth,
                caveProbeRadiusBlocks, maxResults);
        }

        private void validate() {
            if (ringSamples < 8 || ringSamples > 128) throw new IllegalArgumentException("ringSamples");
            if (gridStep < 1 || radiusBlocks < 0 || ringRadiusBlocks < 1
                || outerRadiusBlocks <= ringRadiusBlocks) {
                throw new IllegalArgumentException("search dimensions");
            }
            if (minimumRingRelief < 0.0 || maximumRingError < 0.0) {
                throw new IllegalArgumentException("ring thresholds");
            }
            if (maxResults < 1 || maxResults > 10_000) throw new IllegalArgumentException("maxResults");
            if (minimumCaveDepth < 0 || caveProbeRadiusBlocks < 0) {
                throw new IllegalArgumentException("cave options");
            }
        }
    }

    public record Result(long seed, int x, int centerY, int z, double score, double ringMean,
                         double ringSpread, double centerDrop, String centerBiome,
                         int caveDepth, int[] ringHeights) {
    }
}
