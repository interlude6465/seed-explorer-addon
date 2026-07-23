package me.seedexplorer.addon.tools;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

import java.io.PrintStream;

/** Locates a Paper loot seed in candidate per-chunk structure RNG streams. */
public final class StrongholdRandomSequenceSearch {
    private static final PrintStream OUT = System.out;

    private StrongholdRandomSequenceSearch() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        long worldSeed = args.length > 0 ? Long.parseLong(args[0]) : 0L;
        int placementChunkX = args.length > 1 ? Integer.parseInt(args[1]) : 122;
        int placementChunkZ = args.length > 2 ? Integer.parseInt(args[2]) : 55;
        int minStep = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int maxStep = args.length > 4 ? Integer.parseInt(args[4]) : 10;
        long expected = args.length > 5
            ? Long.parseLong(args[5]) : -393332197303699418L;
        int maxIndex = args.length > 6 ? Integer.parseInt(args[6]) : 128;
        int maxBitCalls = args.length > 7 ? Integer.parseInt(args[7]) : 20_000;

        int matches = 0;
        for (int step = minStep; step <= maxStep; step++) {
            for (int index = 0; index <= maxIndex; index++) {
                WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
                long decorationSeed = random.setDecorationSeed(worldSeed,
                    placementChunkX * 16, placementChunkZ * 16);
                random.setFeatureSeed(decorationSeed, index, step);
                int previous = random.next(32);
                for (int bitCall = 0; bitCall < maxBitCalls; bitCall++) {
                    int current = random.next(32);
                    long candidate = ((long) previous << 32) + current;
                    if (candidate == expected) {
                        OUT.println("stronghold_rng_stream_match step=" + step
                            + " index=" + index + " first_bit_call=" + bitCall
                            + " second_bit_call=" + (bitCall + 1));
                        matches++;
                    }
                    previous = current;
                }
            }
        }
        OUT.println("stronghold_rng_stream_search_complete=true matches=" + matches
            + " steps=" + minStep + ".." + maxStep + " indexes=0.." + maxIndex
            + " bit_calls_per_index=" + maxBitCalls);
        if (matches == 0) System.exit(2);
    }
}
