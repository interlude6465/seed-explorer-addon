package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.StandaloneStructureLootPredictor;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Collectors;

/** Artifact-level loot replay for a pinned 26.3 abandoned camp. */
public final class StandaloneLootProbe {
    private static final PrintStream OUT = System.out;
    private static final long WORLD_SEED = 42L;
    private static final int START_CHUNK_X = 40;
    private static final int START_CHUNK_Z = -120;
    private static final ResourceKey<Structure> CAMP = ResourceKey.create(Registries.STRUCTURE,
        Identifier.parse("minecraft:abandoned_camp_windswept_forest"));

    private StandaloneLootProbe() {
    }

    public static void main(String[] args) {
        try {
            if (args.length > 0 && args[0].equals("reported-bastion")) {
                SharedConstants.tryDetectVersion();
                Bootstrap.bootStrap();
                verifyConcurrentBastion();
                return;
            }
            runProbe();
        } catch (Throwable failure) {
            OUT.println("standalone loot probe failed: " + failure);
            failure.printStackTrace(OUT);
            OUT.flush();
            System.exit(1);
        }
    }

    private static void verifyConcurrentBastion() throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(6);
        var start = new java.util.concurrent.CountDownLatch(1);
        var inventories = new java.util.concurrent.ConcurrentLinkedQueue<List<ChestLootOutput>>();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(6)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 6; worker++) {
                final boolean preview = (worker & 1) == 0;
                tasks.add(workers.submit(() -> {
                    ready.countDown();
                    try { start.await(); }
                    catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); }
                    if (preview) {
                        var structure = new GeneratedStructure(-9888, -1648, -618, -103,
                            StructureType.BASTION, "Hoglin Stable", false);
                        require(!me.seedexplorer.addon.preview.StructurePreviewSimulator.preview(structure, 1L).isEmpty(),
                            "reported Bastion preview is empty");
                    } else {
                        var result = StandaloneStructureLootPredictor.predict(
                            new PredictionContext(1L, "26.3", -1), BuiltinStructures.BASTION_REMNANT,
                            -618, -103, 64);
                        require(!result.isEmpty(), "reported Hoglin Stable returned no inventories");
                        inventories.add(result);
                        OUT.println("reported bastion probe: " + result.size() + " inventories");
                    }
                }));
            }
            ready.await();
            start.countDown();
            Throwable failure = null;
            for (var task : tasks) {
                try { task.get(); }
                catch (java.util.concurrent.ExecutionException problem) {
                    problem.getCause().printStackTrace(OUT);
                    failure = problem.getCause();
                }
            }
            if (failure != null) throw new AssertionError("Concurrent Bastion requests failed", failure);
            var expected = inventories.element();
            for (var actual : inventories) {
                if (!expected.equals(actual)) {
                    for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
                        if (!expected.get(i).equals(actual.get(i))) {
                            OUT.println("concurrent expected: " + expected.get(i));
                            OUT.println("concurrent actual: " + actual.get(i));
                            break;
                        }
                    }
                }
            }
            require(inventories.size() == 3 && inventories.stream().allMatch(expected::equals),
                "concurrent Bastion requests returned different positions, seeds or loot");
        }
    }

    private static void verifyVillageTreeRng() {
        // Independent Fabric 26.3 capture: two oak features precede this house.
        // Missing LevelSimulatedReader predicates used to abort both trees and
        // change the loot seed even though the chest position was correct.
        var containers = me.seedexplorer.addon.loot.VanillaLootStructureSimulator.simulate(
            new PredictionContext(1L, "26.3", 0), BuiltinStructures.VILLAGE_PLAINS, 785, 995);
        var chest = containers.stream()
            .filter(value -> value.x() == 12571 && value.y() == 64 && value.z() == 15975)
            .findFirst().orElseThrow(() -> new AssertionError("village tree RNG fixture chest missing"));
        require(chest.lootSeed() == -2081197769866577064L,
            "village tree placement RNG differs from the independent live capture");
    }

    private static void runProbe() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        System.setErr(OUT);
        verifyConcurrentBastion();

        PredictionContext context = new PredictionContext(WORLD_SEED, "26.3", 0);
        GeneratedStructure marker = new GeneratedStructure(
            START_CHUNK_X * 16, START_CHUNK_Z * 16, START_CHUNK_X, START_CHUNK_Z,
            StructureType.ABANDONED_CAMP,
            "minecraft:abandoned_camp_windswept_forest", false);
        require(CAMP.equals(StandaloneStructureLootPredictor.structureKey(context, marker)),
            "standalone map marker resolved to the wrong camp structure key");
        List<ChestLootOutput> outputs = StandaloneStructureLootPredictor.predict(
            context, marker, 64);
        require(outputs.size() == 3, "expected three camp containers, got " + outputs.size());
        require(outputs.stream().noneMatch(ChestLootOutput::exact),
            "unverified 26.3 camp loot was promoted to exact");
        for (ChestLootOutput output : outputs) {
            OUT.println(output.lootTableId() + " " + output.predictedCounts());
        }

        Set<String> tables = outputs.stream().map(ChestLootOutput::lootTableId)
            .collect(Collectors.toUnmodifiableSet());
        require(tables.equals(Set.of(
            "minecraft:barrels/abandoned_camp_barrel",
            "minecraft:chests/abandoned_camp_common_chest",
            "minecraft:chests/abandoned_camp_secret_chest")),
            "unexpected camp tables " + tables);

        Map<String, Long> seeds = outputs.stream().collect(Collectors.toUnmodifiableMap(
            ChestLootOutput::lootTableId, ChestLootOutput::seed));
        require(seeds.get("minecraft:chests/abandoned_camp_common_chest") == 3565625889219861159L,
            "common chest seed drifted");
        require(seeds.get("minecraft:chests/abandoned_camp_secret_chest") == -7067353347797955036L,
            "secret chest seed drifted");
        require(seeds.get("minecraft:barrels/abandoned_camp_barrel") == 8971376422279707887L,
            "barrel seed drifted");

        ChestLootOutput common = outputs.stream()
            .filter(output -> output.lootTableId().endsWith("common_chest"))
            .findFirst().orElseThrow();
        require(common.predictedCounts().getOrDefault("minecraft:copper_spear", 0) == 1,
            "26.3 copper spear loot is missing");
        require(outputs.stream()
                .flatMap(output -> output.predictedItems().stream())
                .filter(item -> item.itemId().equals("minecraft:potion"))
                .anyMatch(item -> item.potionContents() != null
                    && !item.potionContents().isBlank()),
            "potion contents were discarded from exact vanilla loot output");

        verifyVillageKeys(context);
        verifyVillageTreeRng();
        verifyOverlappingMineshafts();
        verifyDungeonReplay();
        verifyDynamicCampMaps();
        verifyTreasureMapTargets();
        verifySuspiciousStewProperties();
        verifyPersistentCache(context, marker, outputs);
        StandaloneDoubleChestProbe.verify();

        OUT.println("standalone loot probe: unverified containers=" + outputs.size()
            + " items=" + outputs.stream().mapToInt(output -> output.predictedItems().size()).sum());
    }

    private static void verifyOverlappingMineshafts() {
        // Independent 26.3 fixture: two mineshaft starts share decoration RNG.
        // Replaying only (-58,-339) incorrectly adds carts at (-928,30,-5448)
        // and (-926,25,-5455). The live world has exactly these eight inventories.
        var outputs = StandaloneStructureLootPredictor.predict(
            new PredictionContext(1L, "26.3", 0), BuiltinStructures.MINESHAFT, -58, -339, 64);
        var positions = outputs.stream().map(chest ->
            chest.blockX() + "," + chest.blockY() + "," + chest.blockZ()).collect(Collectors.toSet());
        require(outputs.size() == 8 && positions.equals(Set.of(
            "-979,36,-5450", "-963,30,-5423", "-962,37,-5487", "-944,34,-5379",
            "-941,35,-5394", "-938,25,-5467", "-932,27,-5377", "-926,39,-5378")),
            "overlapping mineshafts drifted from independent container fixture: " + positions);
        OUT.println("standalone mineshaft probe: shared RNG preserves eight live containers");
    }

    private static void verifyVillageKeys(PredictionContext context) {
        Map<String, ResourceKey<Structure>> expected = Map.of(
            "minecraft:village_plains", BuiltinStructures.VILLAGE_PLAINS,
            "minecraft:village_desert", BuiltinStructures.VILLAGE_DESERT,
            "minecraft:village_savanna", BuiltinStructures.VILLAGE_SAVANNA,
            "minecraft:village_snowy", BuiltinStructures.VILLAGE_SNOWY,
            "minecraft:village_taiga", BuiltinStructures.VILLAGE_TAIGA);
        for (Map.Entry<String, ResourceKey<Structure>> entry : expected.entrySet()) {
            GeneratedStructure village = new GeneratedStructure(0, 0, 0, 0,
                StructureType.VILLAGE, entry.getKey(), false);
            require(entry.getValue().equals(
                StandaloneStructureLootPredictor.structureKey(context, village)),
                "wrong standalone village key for " + entry.getKey());
        }
    }

    private static void verifyDungeonReplay() {
        long seed = -5330673357612662400L;
        PredictionContext context = new PredictionContext(seed, "26.3", 0);
        GeneratedStructure dungeon = new GeneratedStructure(-606, -20, -275, -38, -18,
            StructureType.DUNGEON, "minecraft:skeleton", false);
        List<ChestLootOutput> outputs = StandaloneStructureLootPredictor.predict(context, dungeon, 64);
        require(!outputs.isEmpty(), "standalone dungeon replay returned no containers");
        require(outputs.stream().noneMatch(ChestLootOutput::exact),
            "unverified 26.3 dungeon loot was promoted to exact");
        require(outputs.stream().allMatch(output ->
            output.lootTableId().equals("minecraft:chests/simple_dungeon")),
            "standalone dungeon replay returned another loot table");
        Set<Long> expectedSeeds = Set.of(-8144953013160125626L, 3527612760189953148L,
            8433468082816297385L);
        require(outputs.stream().allMatch(output -> expectedSeeds.contains(output.seed())),
            "standalone dungeon loot seed drifted");
        OUT.println("standalone dungeon probe: unverified containers=" + outputs.size());
    }

    private static void verifyDynamicCampMaps() {
        PredictionContext context = new PredictionContext(1L, "26.3", 0);
        GeneratedStructure bambooCamp = new GeneratedStructure(
            897 * 16, -1089 * 16, 897, -1089,
            StructureType.ABANDONED_CAMP,
            "minecraft:abandoned_camp_bamboo_jungle", false);
        List<ChestLootOutput> outputs = StandaloneStructureLootPredictor.predict(
            context, bambooCamp, 64);

        ChestLootOutput common = outputs.stream()
            .filter(output -> output.blockX() == 14354 && output.blockY() == 74
                && output.blockZ() == -17427)
            .findFirst().orElseThrow(() -> new AssertionError(
                "known bamboo camp common chest is missing"));
        require(common.predictedCounts().getOrDefault(
                "minecraft:abandoned_camp_map", 0) == 1,
            "available abandoned-camp destination map was discarded");

        ChestLootOutput secret = outputs.stream()
            .filter(output -> output.blockX() == 14355 && output.blockY() == 72
                && output.blockZ() == -17420)
            .findFirst().orElseThrow(() -> new AssertionError(
                "known bamboo camp secret chest is missing"));
        require(secret.predictedCounts().getOrDefault(
                "minecraft:buried_mineshaft_map", 0) == 1,
            "available mineshaft destination map was discarded");
        OUT.println("standalone camp map probe: dynamic destinations preserved");
    }

    private static void verifyTreasureMapTargets() {
        // Independently generated live inventories, recorded in
        // docs/prediction-evidence/26.3-map-targets-two-seeds.txt. These replay
        // loot at the observed origins; they do not certify structure positions.
        long[][] fixtures = {
            {1L, 7105594996654701655L, -8027, 51, 429, -8103, 105},
            {1L, -7147718265403446992L, 6225, 61, 10528, 6377, 10265},
            {1L, 7055324468062037489L, 7601, 55, 9920, 7593, 9929},
            {-7046029254386353131L, 5854607105635396541L, 11026, 55, 14870, 10921, 15033},
            {-7046029254386353131L, 8777687674909048284L, 11635, 55, -12116, 11705, -12087}
        };
        for (long[] fixture : fixtures) {
            var stacks = me.seedexplorer.addon.loot.LootTableSimulator.simulateContainerStacks(
                new PredictionContext(fixture[0], "26.3", 0), "minecraft:chests/shipwreck_map",
                fixture[1], (int) fixture[2], (int) fixture[3], (int) fixture[4], 27);
            require(stacks != null, "map fixture inventory unavailable");
            var maps = stacks.stream().filter(stack -> stack.get(
                net.minecraft.core.component.DataComponents.MAP_DECORATIONS) != null).toList();
            require(maps.size() == 1, "expected one decorated treasure map: " + stacks);
            String expected = "{+=minecraft:red_x@" + fixture[5] + ".0,"
                + fixture[6] + ".0,180.0}";
            require(expected.equals(me.seedexplorer.addon.loot.LootTableSimulator
                .mapDecorationsComponent(maps.getFirst())), "treasure-map target differs from vanilla: " + fixture[0]);
            require(!maps.getFirst().has(net.minecraft.core.component.DataComponents.MAP_ID),
                "offline map fabricated a saved-world map ID");
            require(me.seedexplorer.addon.loot.LootTableSimulator.hasUnrepresentedLootComponents(maps.getFirst()),
                "partial map properties were incorrectly certified as complete compact loot metadata");
        }
        OUT.println("standalone map probe: five independent target/type/rotation fixtures matched across two seeds");
    }

    private static void verifySuspiciousStewProperties() {
        // Independent vanilla item components captured in
        // docs/prediction-evidence/26.3-map-targets-two-seeds.txt, ACTUAL rows.
        // Observed loot seeds isolate item-property fidelity from the separately
        // recorded beached-wreck position and generation-history discrepancies.
        long[][] fixtures = {
            {1L, -6230066546454467639L, 6235, 61, 10527},
            {1L, 6980092457068274153L, 7610, 55, 9919},
            {-7046029254386353131L, 8220817990207311449L, 12037, 42, 15480},
            {-7046029254386353131L, 5023899182600129428L, 11636, 55, -12107}
        };
        List<List<String>> expected = List.of(
            List.of("[minecraft:blindness@120]", "[minecraft:blindness@140]"),
            List.of("[minecraft:jump_boost@140]"),
            List.of("[minecraft:jump_boost@180]", "[minecraft:jump_boost@200]"),
            List.of("[minecraft:blindness@100]", "[minecraft:saturation@8]"));
        String table = "minecraft:chests/shipwreck_supply";
        int comparedStews = 0;
        for (int index = 0; index < fixtures.length; index++) {
            long[] fixture = fixtures[index];
            var context = new PredictionContext(fixture[0], "26.3", 0);
            var items = me.seedexplorer.addon.loot.LootTableSimulator.simulateVanilla(
                context, table, fixture[1], (int) fixture[2], (int) fixture[3], (int) fixture[4]);
            require(items != null, "stew fixture unavailable: " + index);
            var stews = items.stream().filter(item -> item.itemId().equals("minecraft:suspicious_stew")).toList();
            require(stews.stream().allMatch(item -> item.minCount() == 1 && item.maxCount() == 1
                    && !item.hasUnrepresentedComponents()),
                "stew properties merged or left unrepresented: " + index);
            require(stews.stream().map(me.seedexplorer.addon.loot.ItemLoot::suspiciousStewEffects)
                    .sorted().toList().equals(expected.get(index)),
                "stew effect/duration differs from archived vanilla inventory: " + index + " " + stews);

            var filled = me.seedexplorer.addon.loot.LootTableSimulator.simulateContainerStacks(
                context, table, fixture[1], (int) fixture[2], (int) fixture[3], (int) fixture[4], 27);
            require(filled != null, "stew inventory fill unavailable: " + index);
            var filledStews = filled.stream().filter(stack -> stack.is(net.minecraft.world.item.Items.SUSPICIOUS_STEW))
                .toList();
            require(filledStews.stream().allMatch(stack -> stack.getCount() == 1), "stew stack size changed");
            // Read the typed vanilla components directly, without using the
            // production serializer to derive both sides of this assertion.
            var typedEffects = filledStews.stream().map(stack -> {
                var effects = stack.get(net.minecraft.core.component.DataComponents.SUSPICIOUS_STEW_EFFECTS);
                require(effects != null, "filled stew lost effects");
                return effects.effects().stream().map(entry ->
                    entry.effect().unwrapKey().orElseThrow().identifier() + "@" + entry.duration())
                    .toList().toString();
            }).sorted().toList();
            require(typedEffects.equals(expected.get(index)), "filled stew effects differ: " + index);

            var predicted = List.of(new ChestLootOutput((int) fixture[2], (int) fixture[3], (int) fixture[4],
                table, stews, fixture[1], false));
            var actual = expected.get(index).stream().map(effects ->
                new StandaloneChestLootVerification.ActualStack("minecraft:suspicious_stew", 1,
                    Map.of(), null, null, false, null, effects)).toList();
            var counts = Map.of("minecraft:suspicious_stew", actual.size());
            require(StandaloneChestLootVerification.compare(context, predicted, counts, actual).status()
                    == StandaloneChestLootVerification.Status.MATCH,
                "observed stew properties not matched for approximate prediction");
            for (String changed : List.of("[minecraft:blindness@121]", "[minecraft:poison@120]")) {
                var mutation = new java.util.ArrayList<>(actual);
                mutation.set(0, new StandaloneChestLootVerification.ActualStack("minecraft:suspicious_stew", 1,
                    Map.of(), null, null, false, null, changed));
                require(StandaloneChestLootVerification.compare(context, predicted, counts, mutation).status()
                        == StandaloneChestLootVerification.Status.MISMATCH,
                    "same-count stew effect/duration mismatch escaped verification");
            }
            require(StandaloneChestLootVerification.compare(context, predicted, counts).status()
                    == StandaloneChestLootVerification.Status.UNVERIFIED,
                "counts alone incorrectly certified stew effects");
            comparedStews += stews.size();
        }
        OUT.println("standalone stew probe: four independent inventories / " + comparedStews
            + " effect-duration stacks matched across two seeds; same-count metadata mutations rejected");
    }

    private static void verifyPersistentCache(PredictionContext context,
                                              GeneratedStructure marker,
                                              List<ChestLootOutput> outputs) throws Exception {
        Path cacheRoot = Files.createTempDirectory("seed-explorer-loot-cache-probe-")
            .toAbsolutePath().normalize();
        String previous = System.getProperty("seedexplorer.lootCacheDir");
        try {
            System.setProperty("seedexplorer.lootCacheDir", cacheRoot.toString());
            StandalonePersistentLootCache.clearMemory();
            require(StandalonePersistentLootCache.get(context, marker).isEmpty(),
                "empty persistent cache unexpectedly returned loot");
            StandalonePersistentLootCache.put(context, marker, outputs);
            StandalonePersistentLootCache.clearMemory();
            require(StandalonePersistentLootCache.get(context, marker)
                    .filter(outputs::equals).isPresent(),
                "persistent completed loot did not survive a disk round trip");

            PredictionContext anotherVersion = new PredictionContext(
                context.worldSeed(), "26.2", context.dimension());
            require(StandalonePersistentLootCache.get(anotherVersion, marker).isEmpty(),
                "persistent loot crossed prediction-version contexts");
            GeneratedStructure anotherVariant = new GeneratedStructure(marker.x, marker.z,
                marker.startChunkX, marker.startChunkZ, marker.type,
                "minecraft:abandoned_camp_swamp", false);
            require(StandalonePersistentLootCache.get(context, anotherVariant).isEmpty(),
                "persistent loot crossed structure-variant identities");

            GeneratedStructure anotherY = new GeneratedStructure(marker.x, 72, marker.z,
                marker.startChunkX, marker.startChunkZ, marker.type, marker.variant, marker.hasShip);
            require(StandalonePersistentLootCache.get(context, anotherY).isEmpty(),
                "persistent loot crossed vertical structure identities");

            ChestLootOutput first = outputs.getFirst();
            List<ChestLootOutput> approximate = List.of(new ChestLootOutput(first.blockX(),
                first.blockY(), first.blockZ(), first.lootTableId(), first.predictedItems(),
                first.seed(), false));
            StandalonePersistentLootCache.put(context, anotherVariant, approximate);
            StandalonePersistentLootCache.clearMemory();
            require(StandalonePersistentLootCache.get(context, anotherVariant)
                    .filter(approximate::equals)
                    .filter(cached -> !cached.getFirst().exact())
                    .isPresent(),
                "approximate completed loot did not retain its inexact status");

            GeneratedStructure emptyVariant = new GeneratedStructure(marker.x + 1, marker.z,
                marker.startChunkX, marker.startChunkZ, marker.type,
                "minecraft:abandoned_camp_swamp", false);
            StandalonePersistentLootCache.put(context, emptyVariant, List.of());
            StandalonePersistentLootCache.clearMemory();
            require(StandalonePersistentLootCache.get(context, emptyVariant)
                    .filter(List::isEmpty).isPresent(),
                "completed empty loot did not survive a disk round trip");
            OUT.println("standalone loot cache probe: exact, approximate, and empty round trips passed");
        } finally {
            if (previous == null) System.clearProperty("seedexplorer.lootCacheDir");
            else System.setProperty("seedexplorer.lootCacheDir", previous);
            StandalonePersistentLootCache.clearMemory();
            if (cacheRoot.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())) {
                try (var paths = Files.walk(cacheRoot)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
