package me.seedexplorer.standalone;

import me.seedexplorer.addon.worldgen.GeneratedTerrainHeightmap;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import me.seedexplorer.seedcracking.Dimension;
import me.seedexplorer.seedcracking.EndPillarFingerprint;
import me.seedexplorer.seedcracking.Lcg48;
import me.seedexplorer.seedcracking.SlimeChunkFilter;
import me.seedexplorer.seedcracking.SlimeChunkObservation;
import me.seedexplorer.seedcracking.VersionProfile;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Independent 26.3 adapter checks against vanilla registry placements, vanilla
 * RNG/feature code and actual vanilla-generated ProtoChunks. These fixtures do
 * not certify old versions, custom presets/datapacks, or exhaustive seed lifting.
 */
final class StandaloneSeedCrackingVanillaProbe {
    private static final long[] SEEDS = {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x123456789ABCDEFL};
    private static final VersionProfile PROFILE = VersionProfile.parse("26.3");

    public static void main(String[] args) { verify(); }

    static void verify() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        verifyPlacements();
        verifySlime();
        verifyPillars();
        verifyGeneratedTerrainAndBiomes();
    }

    private static void verifyPlacements() {
        var registry = WorldgenEngine.vanillaLookup().lookupOrThrow(Registries.STRUCTURE_SET);
        int checks = 0, sets = 0;
        int[][] regions = {{0, 0}, {-1, -1}, {1, -1}, {-17, 23}, {2345, -1987}, {-12345, 9876}};
        for (var spec : PROFILE.placements().all()) {
            var set = registry.getOrThrow(ResourceKey.create(Registries.STRUCTURE_SET, Identifier.parse(spec.id()))).value();
            require(set.placement() instanceof RandomSpreadStructurePlacement,
                "Catalog incorrectly calls a vanilla structure set random-spread: " + spec.id());
            var vanilla = (RandomSpreadStructurePlacement) set.placement();
            require(vanilla.spacing() == spec.spacing() && vanilla.separation() == spec.separation()
                    && vanilla.spreadType().name().equals(spec.spreadType().name()),
                "26.3 registry constants differ for " + spec.id());
            sets++;
            for (long seed : SEEDS) for (int[] region : regions) {
                var expected = vanilla.getPotentialStructureChunk(seed,
                    region[0] * vanilla.spacing(), region[1] * vanilla.spacing());
                var actual = spec.potentialChunk(seed, region[0], region[1]);
                require(actual.x() == expected.x() && actual.z() == expected.z(),
                    "26.3 placement mismatch: " + spec.id() + " seed=" + seed
                        + " region=" + Arrays.toString(region) + " expected=" + expected + " actual=" + actual);
                require(spec.matches(seed, region[0], region[1], expected.x(), expected.z(), new Lcg48()),
                    "Exact candidate filter rejected vanilla potential start: " + spec.id());
                require(!spec.matches(seed, region[0], region[1], expected.x() + 1, expected.z(), new Lcg48()),
                    "Exact candidate filter accepted a displaced start: " + spec.id());
                checks++;
            }
        }
        System.out.println("Cracking vanilla placement: PASS (" + sets + " registry sets, " + checks
            + " potential-start checks, six seeds including signed extremes; frequency/existence gates excluded)");
    }

    private static void verifySlime() {
        int checks = 0, positive = 0;
        int[][] positions = {{0, 0}, {-1, -1}, {1, -1}, {-17, 29}, {23456, -19876},
            {1_875_000, -1_875_000}, {-1_875_000, 1_875_000}};
        for (long seed : SEEDS) for (int[] position : positions) {
            boolean expected = WorldgenRandom.seedSlimeChunk(position[0], position[1], seed, 987234911L).nextInt(10) == 0;
            require(SlimeChunkFilter.isSlimeChunk(seed, position[0], position[1]) == expected,
                "Vanilla slime overflow/sign mismatch at " + Arrays.toString(position) + " seed=" + seed);
            require(new SlimeChunkObservation(position[0], position[1], expected).matchesWorldSeed(seed),
                "Slime evidence rejected its independently computed status");
            require(!new SlimeChunkObservation(position[0], position[1], !expected).matchesWorldSeed(seed),
                "Contradictory slime evidence accepted");
            if (expected) positive++;
            checks++;
        }
        require(positive > 0 && positive < checks, "Slime fixtures did not include both statuses");
        System.out.println("Cracking vanilla slime: PASS (" + checks + " checks, " + positive
            + " positive, negative/world-border coordinates and integer-overflow cases)");
    }

    private static void verifyPillars() {
        int heights = 0, blockColumns = 0;
        for (int seedIndex = 0; seedIndex < SEEDS.length; seedIndex++) {
            long seed = SEEDS[seedIndex];
            List<EndSpikeFeature.EndSpike> spikes = vanillaSpikes(seed);
            require(spikes.size() == 10, "Vanilla pillar count changed");
            int[] tops = new int[10];
            int vanillaKey = (int) (RandomSource.createThreadLocalInstance(seed).nextLong() & 0xFFFFL);
            int[] modeled = EndPillarFingerprint.heightsForSpikeSeed(vanillaKey);
            require(EndPillarFingerprint.spikeSeedForWorldSeed(seed) == vanillaKey,
                "Pillar key differs from vanilla RNG for seed " + seed);
            for (int i = 0; i < 10; i++) {
                var spike = spikes.get(i);
                double angle = 2.0 * (-Math.PI + (Math.PI / 10.0) * i);
                int captureX = (int) Math.floor(42.0 * Math.cos(angle));
                int captureZ = (int) Math.floor(42.0 * Math.sin(angle));
                require(spike.getCenterX() == captureX && spike.getCenterZ() == captureZ,
                    "Integrated capture's pillar ordering/center differs from vanilla at index " + i);
                require(spike.getHeight() == modeled[i], "Pillar shuffle height differs from vanilla for seed " + seed);
                tops[i] = spike.getHeight() - 1;
                if (seedIndex < 2) {
                    require(vanillaObsidianTop(spike) == tops[i],
                        "Highest obsidian conversion differs from actual vanilla block placement");
                    blockColumns++;
                }
                heights++;
            }
            var fingerprint = EndPillarFingerprint.fromTopBlockHeights(tops);
            require(fingerprint.matchesStructureSeed(seed & Lcg48.MASK)
                    && fingerprint.spikeSeedCandidates().contains(vanillaKey),
                "Pillar fingerprint excluded independent true seed/key");
            boolean checkedNegative = false;
            for (long other = seed + 1; other != seed + 100 && !checkedNegative; other++) {
                int[] otherHeights = vanillaSpikes(other).stream().mapToInt(EndSpikeFeature.EndSpike::getHeight).toArray();
                if (!Arrays.equals(modeled, otherHeights)) {
                    require(!fingerprint.matchesStructureSeed(other), "Pillar filter accepted a different vanilla arrangement");
                    checkedNegative = true;
                }
            }
            require(checkedNegative, "Pillar fixtures did not find a distinct arrangement");
        }
        System.out.println("Cracking vanilla pillars: PASS (" + heights + " ordered heights/centers, " + blockColumns
            + " actual obsidian columns, key recovery and negative arrangements; crystal/cap spawning is not simulated)");
    }

    private static List<EndSpikeFeature.EndSpike> vanillaSpikes(long seed) {
        var world = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getSeed")) return seed;
                throw new AssertionError("Unexpected vanilla pillar-key call: " + method.getName());
            });
        return EndSpikeFeature.getSpikesForLevel(world);
    }

    /** Runs the real block-placement loop, stopping explicitly before server-only crystal creation. */
    private static int vanillaObsidianTop(EndSpikeFeature.EndSpike spike) {
        BlockState[] center = new BlockState[128];
        var world = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getMinY" -> 0;
                case "setBlock", "setBlockAndUpdate" -> {
                    BlockPos pos = (BlockPos) args[0];
                    if (pos.getX() == spike.getCenterX() && pos.getZ() == spike.getCenterZ()
                        && pos.getY() >= 0 && pos.getY() < center.length) center[pos.getY()] = (BlockState) args[1];
                    yield true;
                }
                case "getLevel" -> throw CrystalBoundary.INSTANCE;
                default -> throw new AssertionError("Unexpected vanilla pillar block call: " + method.getName());
            });
        try {
            new EndSpikeFeature(List.of(spike), false, Optional.empty()).place(world, null,
                RandomSource.create(42L), new BlockPos(spike.getCenterX(), 0, spike.getCenterZ()));
            throw new AssertionError("Vanilla pillar fixture did not reach the explicit crystal boundary");
        } catch (CrystalBoundary expected) {
            for (int y = center.length - 1; y >= 0; y--) {
                if (center[y] != null && center[y].is(Blocks.OBSIDIAN)) return y;
            }
            throw new AssertionError("Vanilla pillar placed no center obsidian");
        }
    }

    private static void verifyGeneratedTerrainAndBiomes() {
        int chunks = 0, bedrockChecks = 0, biomeChecks = 0;
        var mismatches = new java.util.LinkedHashMap<String,Integer>();
        var counts = new java.util.LinkedHashMap<String,Integer>();
        var examples = new java.util.ArrayList<String>();
        long[] terrainSeeds = {1L, Long.MIN_VALUE};
        ChunkPos[] locations = {new ChunkPos(-2, -1), new ChunkPos(17, -19)};
        for (long seed : terrainSeeds) for (int dimension : new int[]{0, -1, 1}) {
            var generated = new GeneratedTerrainHeightmap(seed, dimension, List.of(), false, 2);
            var oracle = PROFILE.bedrockOracle(dimension == -1 ? Dimension.NETHER : dimension == 1 ? Dimension.END : Dimension.OVERWORLD);
            for (ChunkPos location : locations) {
                // This invokes vanilla createBiomes/buildTerrain and its real material rules,
                // independently of the standalone bedrock arithmetic under test.
                var chunk = generated.getChunk(location);
                // The helper calls vanilla createBiomes/buildTerrain directly;
                // the live ChunkStatus pipeline normally advances this metadata.
                // Its palette is populated, but ProtoChunk guards access by status.
                chunk.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
                chunks++;
                int[] bedrockY = dimension == 0 ? new int[]{-64, -63, -62, -61, -60, -59}
                    : dimension == -1 ? new int[]{0, 1, 2, 3, 4, 5, 122, 123, 124, 125, 126, 127} : new int[0];
                for (int localX = 0; localX < 16; localX++) for (int localZ = 0; localZ < 16; localZ++) {
                    int x = location.getMinBlockX() + localX, z = location.getMinBlockZ() + localZ;
                    for (int y : bedrockY) {
                        boolean expected = chunk.getBlockState(new BlockPos(x, y, z)).is(Blocks.BEDROCK);
                        String group=dimension==0?"overworld-floor":y<=5?"nether-floor":"nether-roof";
                        counts.merge(group,1,Integer::sum);
                        boolean actual=oracle.isBedrock(seed,x,y,z);
                        if(actual!=expected) {
                            mismatches.merge(group,1,Integer::sum);
                            if(examples.size()<12)examples.add("seed="+seed+" "+group+" at "+x+","+y+","+z+" vanilla="+expected+" modeled="+actual);
                        }
                        bedrockChecks++;
                    }
                }
                int[] ys = dimension == 0 ? new int[]{-63, -17, 3, 63, 319}
                    : dimension == -1 ? new int[]{3, 31, 63, 95, 127} : new int[]{3, 31, 63, 127, 255};
                for (int localX : new int[]{0, 3, 4, 15}) for (int localZ : new int[]{0, 3, 4, 15}) for (int y : ys) {
                    int x = location.getMinBlockX() + localX, z = location.getMinBlockZ() + localZ;
                    String expected = chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2).unwrapKey().orElseThrow().identifier().toString();
                    int qx = Math.floorDiv(x, 4) * 4, qy = Math.floorDiv(y, 4) * 4, qz = Math.floorDiv(z, 4) * 4;
                    String actual = WorldgenEngine.getBiome(seed, dimension, qx, qy, qz).id();
                    String group="noise-biome-dim-"+dimension;
                    counts.merge(group,1,Integer::sum);
                    if(!expected.equals(actual)) {
                        mismatches.merge(group,1,Integer::sum);
                        if(examples.size()<12)examples.add("seed="+seed+" "+group+" at "+x+","+y+","+z+" vanilla="+expected+" modeled="+actual);
                    }
                    biomeChecks++;
                }
            }
        }
        System.out.println("Cracking vanilla terrain adapter counts: "+counts+"; mismatches="+mismatches);
        for(String example:examples)System.out.println("Cracking vanilla adapter mismatch: "+example);
        require(mismatches.isEmpty(),"Generated vanilla adapter mismatches: "+mismatches);
        System.out.println("Cracking vanilla terrain adapters: PASS (" + chunks + " generated ProtoChunks, " + bedrockChecks
            + " floor/Nether-roof blocks, " + biomeChecks + " quart-biome checks across Overworld/Nether/End; two seeds, negative/chunk boundaries)");
    }

    private static final class CrystalBoundary extends RuntimeException {
        private static final CrystalBoundary INSTANCE = new CrystalBoundary();
        private CrystalBoundary() { super("Expected fixture boundary before server crystal spawning", null, false, false); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
