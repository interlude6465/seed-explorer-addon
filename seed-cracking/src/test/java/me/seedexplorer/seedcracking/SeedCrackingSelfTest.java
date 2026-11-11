package me.seedexplorer.seedcracking;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free regression checks for the isolated seed-cracking module. */
public final class SeedCrackingSelfTest {
    private SeedCrackingSelfTest() {
    }

    public static void main(String[] args) {
        verifyLcgReversal();
        verifyOutputRecovery();
        verifyPlacementReplay();
        verifyRejectionPathBoundary();
        verifyInteractiveBudgets();
        verifyPillagerOutpostFrequency();
        verifyRuinedPortalSelector();
        verifyMineshaftFrequency();
        verifyMultiObservationLifting();
        verifyPillarTimeMachine();
        verifyPillarFingerprint();
        verifyPipelineWithoutFilters();
        verifyStructureVariantFilter();
        verifyShipwreckTemplateSelector();
        verifyStrongholdOracleBridge();
        verifyStructureOrientationFilter();
        verifyParallelPillarSearch();
        verifyOracleObservationFilters();
        verifyDimensionBiomeFilter();
        verifyFeatureAndBlockPatternFilters();
        System.out.println("Seed-cracking self-tests passed.");
    }

    private static void verifyLcgReversal() {
        for (long seed : new long[] {0L, 1L, -1L, 0x123456789ABCL}) {
            Lcg48 random = new Lcg48(seed);
            long before = random.rawState();
            random.nextInt();
            check(Lcg48.previousState(random.rawState()) == before, "LCG reverse step");
            long jumped = Lcg48.advanceState(before, 1_000_003L);
            check(Lcg48.retreatState(jumped, 1_000_003L) == before, "LCG jump reversal");
        }
    }

    /** Independent Java Random fixture documenting the sieve's non-exhaustive boundary. */
    private static void verifyRejectionPathBoundary() {
        long seed=186643064605892L; // First next(31) is MAX_VALUE and rejects for bound 24.
        var placement=new PlacementSpec("test:rejection",32,8,0,SpreadType.LINEAR);
        var actual=new java.util.Random(seed);
        int x=actual.nextInt(24),z=actual.nextInt(24);
        check(x==11 && z==5,"crafted rejection fixture changed");
        check(placement.matches(seed,0,0,x,z,new Lcg48()),"exact replay lost Java rejection candidate");
        // This research limitation must remain explicit until exceptional paths are searched.
        check(!placement.matchesLowBits(seed & ((1L<<placement.lowSeedBits())-1),0,0,x,z,new Lcg48()),
            "rejection boundary changed: reassess and update the sieve completeness contract");
    }

    private static void verifyInteractiveBudgets() {
        WorldSeedFilter expensiveReject=seed -> {
            try { Thread.sleep(4); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return false;
        };
        RecoveryResult recovered=UpperBitRecovery.recover(123L,List.of(expensiveReject),
            RecoveryOptions.builder().threads(1).timeBudgetMillis(25).build());
        check(recovered.status()==RecoveryResult.Status.BUDGET_EXHAUSTED
            && recovered.candidatesTested()<100 && recovered.elapsedMillis()<3000,
            "upper-bit deadline must be checked between expensive oracle calls");

        var placement=new PlacementSpec("test:deadline",32,8,0,SpreadType.LINEAR);
        var observations=new ArrayList<StructureObservation>();
        for(int i=0;i<6;i++) {
            var chunk=placement.potentialChunk(123L,i*11,i*17);
            observations.add(placement.observe(chunk.x(),chunk.z()));
        }
        LiftResult lifted=StructureSeedLifter.lift(observations,LiftOptions.builder()
            .threads(1).timeBudgetMillis(1).maxHighCandidates(Long.MAX_VALUE)
            .slimeObservations(List.of(new SlimeChunkObservation(0,0,true),new SlimeChunkObservation(0,0,false)))
            .build());
        check(lifted.status()==LiftResult.Status.BUDGET_EXHAUSTED && lifted.elapsedMillis()<3000,
            "lifting deadline must survive all-rejected slime early exits");
    }

    private static void verifyOutputRecovery() {
        long seed = 0x123456789ABCL;
        Lcg48 random = new Lcg48(seed);
        for (int i = 0; i < 3; i++) random.nextInt();
        List<Integer> ints = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ints.add(random.nextInt());
            if (i < 2) {
                random.nextInt();
                random.nextInt();
            }
        }
        check(Lcg48OutputRecovery.recoverNextIntOutputs(ints, 3, 2)
            .equals(List.of(seed)), "nextInt recovery with gaps");

        random = new Lcg48(seed);
        random.setRawState(Lcg48.advanceState(random.rawState(), 1_000_003L));
        List<Integer> distant = List.of(random.nextInt(), random.nextInt());
        check(Lcg48OutputRecovery.recoverNextIntOutputs(distant, 1_000_003, 0)
            .equals(List.of(seed)), "large fixed-gap recovery");

        random = new Lcg48(seed);
        random.nextLong();
        random.nextLong();
        List<Long> longs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            longs.add(random.nextLong());
            if (i == 0) random.nextLong();
        }
        check(Lcg48OutputRecovery.recoverNextLongOutputs(longs, 2, 1)
            .equals(List.of(seed)), "nextLong recovery with gaps");

        random = new Lcg48(seed);
        List<Float> floats = List.of(random.nextFloat(), random.nextFloat(), random.nextFloat());
        check(Lcg48OutputRecovery.recoverNextFloatOutputs(floats, 0, 0)
            .equals(List.of(seed)), "nextFloat recovery");

        random = new Lcg48(seed);
        List<Double> doubles = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            doubles.add(random.nextDouble());
            if (i < 2) random.nextDouble();
        }
        check(Lcg48OutputRecovery.recoverNextDoubleOutputs(doubles, 0, 1)
            .equals(List.of(seed)), "nextDouble recovery with gaps");

        random = new Lcg48(seed);
        int firstMixed = random.next(26);
        int secondMixed = random.next(27);
        random.nextInt();
        int thirdMixed = random.nextInt();
        List<RandomOutputObservation> mixed = List.of(
            new RandomOutputObservation(26, firstMixed, 0),
            new RandomOutputObservation(27, secondMixed, 0),
            new RandomOutputObservation(32, thirdMixed, 1));
        check(Lcg48OutputRecovery.recoverOutputSequence(mixed).equals(List.of(seed)),
            "mixed-width output recovery");
    }

    private static void verifyPlacementReplay() {
        for (MinecraftVersion version : MinecraftVersion.values()) {
            for (PlacementSpec placement : VersionProfile.of(version).placements().all()) {
                for (long seed : new long[] {0L, 0x123456789ABCL, -1L}) {
                    ChunkCoordinate chunk = placement.potentialChunk(seed, -17, 23);
                    check(placement.matches(seed, -17, 23, chunk.x(), chunk.z(), new Lcg48()),
                        "placement replay: " + version + " / " + placement.id());
                }
            }
        }
    }

    private static void verifyPillagerOutpostFrequency() {
        for (long seed : new long[] {0L, 1L, -1L, 0x123456789ABCDEFL}) {
            for (int chunkX : new int[] {-129, -17, -1, 0, 15, 16, 127}) {
                for (int chunkZ : new int[] {-257, -16, -1, 0, 15, 16, 255}) {
                    int regionX = chunkX >> 4;
                    int regionZ = chunkZ >> 4;
                    long mixedSeed = seed ^ (long) (regionX ^ (regionZ << 4));
                    java.util.Random reference = new java.util.Random(mixedSeed);
                    reference.nextInt();
                    boolean expected = reference.nextInt(5) == 0;
                    check(PillagerOutpostFrequency.acceptsVanilla(seed, chunkX, chunkZ)
                        == expected, "pillager outpost legacy_type_1 reducer");
                }
            }
        }
        boolean rejectedInvalidFrequency = false;
        try {
            PillagerOutpostFrequency.accepts(1L, 0, 0, 0.0F);
        } catch (IllegalArgumentException expected) {
            rejectedInvalidFrequency = true;
        }
        check(rejectedInvalidFrequency, "outpost reducer rejects invalid frequency");
    }

    private static void verifyRuinedPortalSelector() {
        check(RuinedPortalSelector.standardSetups(MinecraftVersion.V26_2).size() == 2,
            "ruined portal standard setup data");
        check(RuinedPortalSelector.desertSetups().get(0).airPocketProbability() == 0.0F,
            "ruined portal desert setup data");
        check(RuinedPortalSelector.normalTemplateIds().size() == 10
            && RuinedPortalSelector.giantTemplateIds().size() == 3,
            "ruined portal template counts");

        for (long seed = 0; seed < 10_000L; seed++) {
            List<RuinedPortalSelector.Setup> setups =
                RuinedPortalSelector.standardSetups(MinecraftVersion.V26_2);
            java.util.Random reference = new java.util.Random(seed);
            float setupRoll = reference.nextFloat();
            RuinedPortalSelector.Setup expectedSetup = setupRoll < 0.5F
                ? setups.get(0) : setups.get(1);
            float airProbability = expectedSetup.airPocketProbability();
            boolean expectedAir = airProbability == 1.0F
                || (airProbability != 0.0F && reference.nextFloat() < airProbability);
            boolean expectedGiant = reference.nextFloat() < 0.05F;
            List<String> expectedTemplates = expectedGiant
                ? RuinedPortalSelector.giantTemplateIds()
                : RuinedPortalSelector.normalTemplateIds();
            String expectedTemplate = expectedTemplates.get(reference.nextInt(expectedTemplates.size()));
            RuinedPortalSelector.Rotation expectedRotation =
                RuinedPortalSelector.Rotation.values()[reference.nextInt(4)];
            RuinedPortalSelector.Mirror expectedMirror = reference.nextFloat() < 0.5F
                ? RuinedPortalSelector.Mirror.NONE : RuinedPortalSelector.Mirror.FRONT_BACK;

            RuinedPortalSelector.Selection actual = RuinedPortalSelector.select(setups,
                new Lcg48(seed));
            check(actual.setupId().equals(expectedSetup.id())
                && actual.airPocket() == expectedAir
                && actual.giant() == expectedGiant
                && actual.templateId().equals(expectedTemplate)
                && actual.rotation() == expectedRotation
                && actual.mirror() == expectedMirror,
                "ruined portal selector sequence");
        }

        check(RuinedPortalSelector.standardSetups(MinecraftVersion.V26_1).get(1)
            .airPocketProbability() == 0.5F
            && RuinedPortalSelector.standardSetups(MinecraftVersion.V26_3).get(1)
            .airPocketProbability() == 0.0F, "versioned ruined portal standard setup");
        for (MinecraftVersion version : MinecraftVersion.values()) {
            for (List<RuinedPortalSelector.Setup> setups : List.of(
                RuinedPortalSelector.standardSetups(version),
                RuinedPortalSelector.desertSetups(), RuinedPortalSelector.jungleSetups(version),
                RuinedPortalSelector.mountainSetups(version),
                RuinedPortalSelector.netherSetups(version), RuinedPortalSelector.oceanSetups(),
                RuinedPortalSelector.swampSetups())) {
                RuinedPortalSelector.select(setups, new Lcg48(123456L));
            }
        }
    }

    private static void verifyMineshaftFrequency() {
        for (long seed : new long[] {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            for (int chunkX : new int[] {-129, -1, 0, 1, 127}) {
                for (int chunkZ : new int[] {-257, -1, 0, 1, 255}) {
                    long mixed = WorldgenSeeds263.largeFeatureSeed(seed, chunkX, chunkZ);
                    java.util.Random reference = new java.util.Random(mixed);
                    boolean expected = reference.nextDouble() < 0.004F;
                    check(MineshaftFrequency.acceptsVanilla(seed, chunkX, chunkZ) == expected,
                        "mineshaft legacy_type_3 reducer");
                    java.util.Random typeReference = new java.util.Random(mixed);
                    MineshaftFrequency.Type expectedType = typeReference.nextInt(2) == 0
                        ? MineshaftFrequency.Type.NORMAL : MineshaftFrequency.Type.MESA;
                    check(MineshaftFrequency.selectFromFreshChunkSeed(seed, chunkX, chunkZ)
                        == expectedType, "mineshaft weighted type selector");
                }
            }
        }
    }

    private static void verifyMultiObservationLifting() {
        long seed = 0x123456789ABCL;
        List<PlacementSpec> placements = List.of(
            VanillaPlacements.SHIPWRECKS,
            VanillaPlacements.OCEAN_RUINS,
            VanillaPlacements.DESERT_PYRAMIDS,
            VanillaPlacements.IGLOOS);
        List<StructureObservation> observations = new ArrayList<>();
        for (int index = 0; index < placements.size(); index++) {
            PlacementSpec placement = placements.get(index);
            ChunkCoordinate chunk = placement.potentialChunk(seed, index * 11 - 31,
                index * 7 - 15);
            observations.add(placement.observe(chunk.x(), chunk.z()));
        }
        List<Long> lowCandidates = StructureSeedLifter.findLowCandidates(observations);
        check(lowCandidates.contains(seed & ((1L << 20) - 1)),
            "multi-structure lifting retains the true low seed");
        check(lowCandidates.size() == 1,
            "independent structure observations triangulate a unique low seed");
    }

    private static void verifyPillarTimeMachine() {
        for (int pillarSeed : new int[] {0, 1, 0xABCD, 0xFFFF}) {
            long candidate = PillarTimeMachine.candidateStructureSeed(0xDEADBEEFL, pillarSeed);
            check(PillarTimeMachine.matchesPillarSeed(candidate, pillarSeed), "pillar time machine");
        }
    }

    private static void verifyPillarFingerprint() {
        int spikeSeed = 0xA53C;
        EndPillarFingerprint fingerprint = EndPillarFingerprint.fromHeights(
            EndPillarFingerprint.heightsForSpikeSeed(spikeSeed));
        check(fingerprint.spikeSeedCandidates().contains(spikeSeed), "pillar fingerprint");
    }

    private static void verifyPipelineWithoutFilters() {
        PlacementSpec placement = VanillaPlacements.SHIPWRECKS;
        long seed = 0x123456789ABCL;
        ChunkCoordinate first = placement.potentialChunk(seed, -3, 4);
        ChunkCoordinate second = placement.potentialChunk(seed, 7, -2);
        SeedCrackingPipeline.Result result = SeedCrackingPipeline.run(
            List.of(placement.observe(first.x(), first.z()), placement.observe(second.x(), second.z())),
            List.of(),
            LiftOptions.builder().highWindow(0, 1).maxHighCandidates(1L << 40).build(),
            RecoveryOptions.defaults());
        check(result.crackResult().status() == CrackResult.Status.STRUCTURE_SEEDS_ONLY
            || result.crackResult().status() == CrackResult.Status.NO_STRUCTURE_MATCH,
            "pipeline phase result");
        check(result.phases().contains(SeedCrackingPipeline.Phase.LIFTING), "pipeline phases");
    }

    private static void verifyStructureVariantFilter() {
        StructureVariantObservation observation = new StructureVariantObservation(
            "minecraft:village", -12, 8, "minecraft:village_plains");
        StructureVariantFilter filter = new StructureVariantFilter(
            (seed, structure, chunkX, chunkZ) -> seed == 42L
                ? "minecraft:village_plains" : "minecraft:village_desert",
            List.of(observation));
        check(filter.matches(42L), "variant filter match");
        check(!filter.matches(43L), "variant filter mismatch");
    }

    private static void verifyShipwreckTemplateSelector() {
        check(ShipwreckTemplateSelector.oceanTemplates().size() == 20,
            "shipwreck ocean template count");
        check(ShipwreckTemplateSelector.beachedTemplates().size() == 11,
            "shipwreck beached template count");
        for (long seed = 0; seed < 10_000L; seed++) {
            ShipwreckTemplateSelector.Selection ocean = ShipwreckTemplateSelector.select(
                false, new Lcg48(seed));
            check(ocean.kind() == ShipwreckVariant.Kind.SHIPWRECK
                && ShipwreckTemplateSelector.oceanTemplates().contains(ocean.templateId()),
                "shipwreck ocean selection range");
            ShipwreckTemplateSelector.Selection beached = ShipwreckTemplateSelector.select(
                true, new Lcg48(seed));
            check(beached.kind() == ShipwreckVariant.Kind.BEACHED
                && ShipwreckTemplateSelector.beachedTemplates().contains(beached.templateId()),
                "shipwreck beached selection range");
        }

        for (long seed : new long[] {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            for (int chunkX : new int[] {-33, -1, 0, 1, 47}) {
                for (int chunkZ : new int[] {-64, -1, 0, 1, 39}) {
                    Lcg48 seeder = new Lcg48(seed);
                    long xMul = seeder.nextLong();
                    long zMul = seeder.nextLong();
                    long startSeed = seed ^ ((long) chunkX * xMul) ^ ((long) chunkZ * zMul);
                    java.util.Random reference = new java.util.Random(startSeed);
                    int rotation = reference.nextInt(4);
                    boolean beached = ((chunkX + chunkZ) & 1) != 0;
                    List<String> templates = beached
                        ? ShipwreckTemplateSelector.beachedTemplates()
                        : ShipwreckTemplateSelector.oceanTemplates();
                    String template = templates.get(reference.nextInt(templates.size()));
                    ShipwreckTemplateSelector.Selection actual =
                        ShipwreckTemplateSelector.selectForStart(seed, chunkX, chunkZ, beached);
                    check(actual.facing().ordinal() == rotation
                        && actual.templateId().equals(template),
                        "shipwreck start-seed rotation/template replay");
                    check(ShipwreckTemplateSelector.selectForStart(seed, chunkX, chunkZ, beached)
                        .equals(ShipwreckTemplateSelector.selectForStart(
                            seed ^ (1L << 48), chunkX, chunkZ, beached)),
                        "shipwreck selector depends only on low-48 world seed");
                }
            }
        }
    }

    private static void verifyStrongholdOracleBridge() {
        long seed = 0x123456789ABCL;
        ConcentricRingsStrongholdOracle oracle = new ConcentricRingsStrongholdOracle(
            ConcentricRings263.identityGate());
        List<ChunkCoordinate> origins = ConcentricRings263.ringOrigins(seed);
        for (int index : new int[] {0, 1, 63, 127}) {
            check(oracle.strongholdChunk(seed, index).equals(origins.get(index)),
                "stronghold oracle ring bridge");
        }
        check(oracle.strongholdChunk(seed, 128) == null, "stronghold oracle index bound");
    }

    private static void verifyStructureOrientationFilter() {
        StructureGeometry.Orientation north = new StructureGeometry.Orientation(
            StructureGeometry.CompassFacing.NORTH, StructureGeometry.Mirror.NONE);
        StructureOrientationObservation observation = new StructureOrientationObservation(
            "minecraft:shipwreck", -4, 9, north);
        StructureOrientationFilter filter = new StructureOrientationFilter(
            (seed, structure, chunkX, chunkZ) -> seed == 77L ? north : null,
            List.of(observation));
        check(filter.matches(77L), "structure orientation filter match");
        check(!filter.matches(78L), "structure orientation filter rejects missing start");
    }

    private static void verifyParallelPillarSearch() {
        int pillarSeed = 0xA53C;
        long expected = PillarTimeMachine.candidateStructureSeed(17L, pillarSeed);
        PillarSearchResult result = PillarTimeMachine.searchRangeParallel(
            pillarSeed, 0L, 64L, candidate -> candidate == expected, 2, 3, 0L);
        check(result.structureSeeds().equals(List.of(expected)), "parallel pillar search result");
        check(result.rangeExhausted(), "parallel pillar search exhaustion");
        check(result.candidatesTested() == 64L, "parallel pillar search count");
        boolean propagated = false;
        try {
            PillarTimeMachine.searchRangeParallel(pillarSeed, 0L, 64L,
                candidate -> { throw new IllegalStateException("expected"); }, 2, 2, 0L);
        } catch (IllegalStateException expectedFailure) {
            propagated = expectedFailure.getCause() instanceof IllegalStateException;
        }
        check(propagated, "parallel pillar filter failure propagation");
    }

    private static void verifyOracleObservationFilters() {
        BiomeBoundaryFilter boundary = new BiomeBoundaryFilter(
            (seed, x, y, z) -> x < 0 ? "minecraft:taiga" : "minecraft:plains",
            List.of(new BiomeBoundaryObservation(-1, 64, 0, "minecraft:taiga",
                1, 64, 0, "minecraft:plains")));
        check(boundary.matches(0L), "biome boundary filter");

        ClimateFilter climate = new ClimateFilter(
            (seed, x, y, z) -> (x + z) / 100.0,
            List.of(new ClimateObservation(10, 64, 10, 0.2, 0.001)));
        check(climate.matches(0L), "climate filter");

        TerrainProfileFilter profile = new TerrainProfileFilter(
            (seed, x, z) -> x - z,
            List.of(new TerrainProfileObservation("ridge", List.of(
                new TerrainObservation(10, 8, 2, 0),
                new TerrainObservation(20, 12, 8, 0)))));
        check(profile.matches(0L), "terrain profile filter");
    }

    private static void verifyDimensionBiomeFilter() {
        List<DimensionBiomeObservation> observations = List.of(
            new DimensionBiomeObservation(Dimension.OVERWORLD, 100, 64, -200,
                "minecraft:plains"),
            new DimensionBiomeObservation(Dimension.NETHER, -300, 40, 500,
                "minecraft:basalt_deltas"));
        DimensionBiomeFilter filter = new DimensionBiomeFilter(
            (seed, dimension, x, y, z) -> dimension == Dimension.OVERWORLD
                ? "minecraft:plains" : "minecraft:basalt_deltas",
            observations);
        check(filter.matches(123L), "dimension-aware biome filter");
        check(filter.observations().get(1).dimension() == Dimension.NETHER,
            "dimension-aware biome observation preserved");
    }

    private static void verifyFeatureAndBlockPatternFilters() {
        FeaturePlacementObservation tree = new FeaturePlacementObservation(
            "minecraft:oak", 2, -1, 33, 70, -15, "minecraft:oak_tree");
        FeaturePlacementFilter featureFilter = new FeaturePlacementFilter(
            (seed, feature, chunkX, chunkZ) -> seed == 123L
                ? List.of(tree) : List.of(), List.of(tree), false);
        check(featureFilter.matches(123L), "feature placement filter match");
        check(!featureFilter.matches(124L), "feature placement filter mismatch");
        check(new FeaturePlacementFilter((seed, feature, chunkX, chunkZ) -> List.of(tree),
            List.of(tree), true).matches(123L), "complete feature placement match");
        check(!new FeaturePlacementFilter((seed, feature, chunkX, chunkZ) -> List.of(tree, tree),
            List.of(tree), true).matches(123L), "complete feature placement rejects extras");

        BlockPatternObservation blocks = BlockPatternObservation.partial("ore-vein",
            new BlockPatternObservation.BlockSample(10, 20, 30, "minecraft:diamond_ore"));
        BlockPatternFilter blockFilter = new BlockPatternFilter(
            (seed, x, y, z) -> seed == 321L ? "minecraft:diamond_ore" : "minecraft:stone",
            List.of(blocks));
        check(blockFilter.matches(321L), "block pattern filter match");
        check(!blockFilter.matches(322L), "block pattern filter mismatch");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
