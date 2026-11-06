package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.worldgen.NaturalFeatureFinder;

/** Command-line probe for tuning natural terrain search before UI integration. */
public final class NaturalFeatureSearchProbe {
    private NaturalFeatureSearchProbe() {
    }

    public static void main(String[] args) {
        NaturalFeatureFinder.bootstrapMinecraft();
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 0L;
        int originX = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int originZ = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 512;
        String lastSeedArgument = args.length > 4 ? args[4].trim() : "";
        long lastSeed = lastSeedArgument.isEmpty() ? seed : Long.parseLong(lastSeedArgument);
        boolean requireDeepCave = args.length > 5 && Boolean.parseBoolean(args[5]);
        int minimumCaveDepth = args.length > 6 ? Integer.parseInt(args[6]) : 80;
        var request = new NaturalFeatureFinder.Request(seed, 0, originX, originZ,
            radius, 32, 96, 192, 24, 12.0, 18.0, true,
            requireDeepCave, minimumCaveDepth, 2, 20);
        var results = lastSeed == seed
            ? NaturalFeatureFinder.search(request)
            : NaturalFeatureFinder.searchSeeds(request, seed, lastSeed);
        System.out.printf("natural-feature candidates=%d seeds=%d..%d radius=%d%n",
            results.size(), seed, lastSeed, radius);
        for (NaturalFeatureFinder.Result result : results) {
            System.out.printf("candidate seed=%d x=%d y=%d z=%d score=%.2f biome=%s "
                    + "ringMean=%.1f centerDrop=%.1f spread=%.1f caveDepth=%d%n",
                result.seed(), result.x(), result.centerY(), result.z(), result.score(), result.centerBiome(),
                result.ringMean(), result.centerDrop(), result.ringSpread(), result.caveDepth());
        }
    }
}
