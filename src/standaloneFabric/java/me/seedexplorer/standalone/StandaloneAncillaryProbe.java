package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.live.LiveChunkSnapshot;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Focused regression checks for the standalone non-map service layer. */
public final class StandaloneAncillaryProbe {
    private StandaloneAncillaryProbe() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        rngProbe();
        lootProbe();
        rememberedLootProbe();
        lootVerificationProbe();
        commandProbe();
        oreNameProbe();
        predictedMineProbe();
        liveStoreProbe();
        System.out.println("standalone ancillary probe: passed");
    }

    private static void rngProbe() {
        long firstState = StandaloneEnchantingRng.advance(0x1234ABCD9876L);
        int first = (int) (firstState >>> 16);
        long secondState = StandaloneEnchantingRng.advance(firstState);
        int second = (int) (secondState >>> 16);
        StandaloneEnchantingRng rng = new StandaloneEnchantingRng();
        rng.enchantmentRequested();
        rng.observeXpSeed(first);
        rng.enchantmentRequested();
        rng.observeXpSeed(second);
        require(rng.snapshot().state() == StandaloneEnchantingRng.State.CRACKED,
            "two XP seeds did not crack the RNG");
        require(rng.snapshot().playerSeed() == secondState, "recovered Java LCG state is wrong");

        rng.enchantmentRequested();
        int expected = StandaloneEnchantingRng.nextInt(secondState);
        require(rng.snapshot().expectedXpSeed() == expected, "pending enchant seed is wrong");
        rng.serverXpSeedUpdate(expected);
        long beforeDrop = rng.snapshot().playerSeed();
        rng.itemDropped();
        long afterFourCalls = beforeDrop;
        for (int i = 0; i < 4; i++) afterFourCalls = StandaloneEnchantingRng.advance(afterFourCalls);
        require(rng.snapshot().playerSeed() == afterFourCalls, "drop did not consume four RNG calls");
    }

    private static void lootProbe() {
        ItemLoot diamonds = new ItemLoot("minecraft:diamond", 2, 4, 1, "Diamond",
            false, null, 0, null);
        ItemLoot book = ItemLoot.randomlyEnchantedBook(1, "minecraft:mending").withFixedEnchantment("minecraft:mending", 1);
        ChestLootOutput near = new ChestLootOutput(3, 64, 4, "near", List.of(diamonds), 1L);
        ChestLootOutput far = new ChestLootOutput(30, 64, 40, "far", List.of(book), 2L);
        StandaloneLootSearchService service = new StandaloneLootSearchService();
        List<StandaloneLootSearchService.Match> diamondsFound = service.search(
            List.of(far, near), "diamond", 2, new BlockPos(0, 64, 0), 8);
        require(diamondsFound.size() == 1 && diamondsFound.getFirst().chest() == near,
            "item ID/display search failed");
        require(service.search(List.of(far, near), "mending", 1, null, 8).getFirst().chest() == far,
            "enchantment search failed");
        require(service.search(List.of(near), "diamond", 3, null, 8).isEmpty(),
            "possible maximum quantity was reported as a minimum match");
    }

    private static void rememberedLootProbe() {
        ChestLootOutput canonical = ChestLootOutput.positionOnly(100, 62, 200);
        ChestLootOutput firstRefinement = ChestLootOutput.positionOnly(100, 63, 200);
        ChestLootOutput secondRefinement = ChestLootOutput.positionOnly(100, 64, 200);
        ChestLootOutput unrelated = ChestLootOutput.positionOnly(400, 70, 500);
        List<ChestLootOutput> remembered = StandaloneRememberedLoot.remember(
            List.of(firstRefinement, unrelated), List.of(firstRefinement, firstRefinement), 512);
        require(remembered.size() == 2 && remembered.contains(firstRefinement)
                && !remembered.contains(canonical),
            "remembered loot was not deduplicated by coordinate");
        List<ChestLootOutput> replaced = StandaloneRememberedLoot.replace(remembered,
            List.of(canonical), List.of(firstRefinement), List.of(secondRefinement), 512);
        require(replaced.size() == 2 && replaced.contains(secondRefinement)
                && replaced.contains(unrelated) && !replaced.contains(canonical)
                && !replaced.contains(firstRefinement),
            "repeated refinement left stale remembered chest coordinates");
    }

    private static void lootVerificationProbe() throws Exception {
        ChestLootOutput left = new ChestLootOutput(10, 64, 20, "minecraft:chests/village_house",
            List.of(new ItemLoot("minecraft:diamond", 2, 2, 1, "Diamond",
                false, null, 0, null)), 101L, true);
        ChestLootOutput right = new ChestLootOutput(11, 64, 20, "minecraft:chests/village_house",
            List.of(new ItemLoot("minecraft:bread", 3, 3, 1, "Bread",
                false, null, 0, null)), 102L, true);
        Map<String, Integer> actual = new LinkedHashMap<>();
        actual.put("minecraft:diamond", 2);
        actual.put("minecraft:bread", 3);
        PredictionContext context = new PredictionContext(42L, "26.3", 0);
        var match = StandaloneChestLootVerification.compare(context, List.of(left, right), actual);
        require(match.status() == StandaloneChestLootVerification.Status.MATCH,
            "synchronized double-chest contents did not match combined predictions");

        actual.put("minecraft:bread", 2);
        var mismatch = StandaloneChestLootVerification.compare(context, List.of(left, right), actual);
        require(mismatch.status() == StandaloneChestLootVerification.Status.MISMATCH,
            "loot count drift did not report a mismatch");

        ItemLoot fortuneBook = new ItemLoot("minecraft:enchanted_book", 1, 1, 1,
            "Enchanted Book", true, null, 0, null)
            .withFixedEnchantment("minecraft:fortune", 3);
        ChestLootOutput enchanted = new ChestLootOutput(12, 64, 20,
            "minecraft:chests/simple_dungeon", List.of(fortuneBook), 104L, true);
        Map<String, Integer> enchantedCounts = Map.of("minecraft:enchanted_book", 1);
        var correctEnchantment = List.of(new StandaloneChestLootVerification.ActualStack(
            "minecraft:enchanted_book", 1, Map.of("minecraft:fortune", 3)));
        require(StandaloneChestLootVerification.compare(context, List.of(enchanted),
                enchantedCounts, correctEnchantment).status()
                == StandaloneChestLootVerification.Status.MATCH,
            "matching enchantment metadata did not verify");
        var wrongEnchantment = List.of(new StandaloneChestLootVerification.ActualStack(
            "minecraft:enchanted_book", 1, Map.of("minecraft:fortune", 2)));
        require(StandaloneChestLootVerification.compare(context, List.of(enchanted),
                enchantedCounts, wrongEnchantment).status()
                == StandaloneChestLootVerification.Status.MISMATCH,
            "wrong enchantment level falsely claimed a loot match");
        ItemLoot summaryBook = ItemLoot.of("minecraft:enchanted_book", 1, 1, 1)
            .withPredictedEnchantments("Fortune III");
        ChestLootOutput uncertified = new ChestLootOutput(12, 64, 20,
            "minecraft:chests/simple_dungeon", List.of(summaryBook), 104L, false);
        require(StandaloneChestLootVerification.compare(context, List.of(uncertified),
                enchantedCounts, correctEnchantment).status()
                == StandaloneChestLootVerification.Status.MATCH,
            "matching observed contents were hidden by family confidence");
        require(!uncertified.exact(), "an observed match changed seed-only prediction confidence");
        require(StandaloneChestLootVerification.compare(context, List.of(uncertified),
                Map.of("minecraft:enchanted_book", 2), correctEnchantment).status()
                == StandaloneChestLootVerification.Status.MISMATCH,
            "uncertified loot count drift was hidden instead of reported");
        require(StandaloneChestLootVerification.compare(context, List.of(uncertified),
                enchantedCounts, wrongEnchantment).status()
                == StandaloneChestLootVerification.Status.MISMATCH,
            "uncertified modeled metadata drift was hidden instead of reported");
        require(StandaloneChestLootVerification.compareContents(List.of(uncertified),
                enchantedCounts, correctEnchantment).status()
                == StandaloneChestLootVerification.Status.MATCH,
            "oracle content comparison did not parse matching enchantment metadata");
        require(StandaloneChestLootVerification.compareContents(List.of(uncertified),
                enchantedCounts, wrongEnchantment).status()
                == StandaloneChestLootVerification.Status.MISMATCH,
            "oracle content comparison accepted the wrong enchantment level");
        ItemLoot damagedSword = ItemLoot.of("minecraft:golden_sword", 1, 1, 1)
            .withDamage(17);
        String healingPotion = "minecraft:healing|color=|name=|effects=[]";
        ItemLoot potion = ItemLoot.of("minecraft:potion", 1, 1, 1)
            .withPotionContents(healingPotion);
        ChestLootOutput componentLoot = new ChestLootOutput(13, 64, 20,
            "minecraft:chests/ruined_portal", List.of(damagedSword, potion), 106L, true);
        Map<String, Integer> componentCounts = Map.of(
            "minecraft:golden_sword", 1, "minecraft:potion", 1);
        var matchingComponents = List.of(
            new StandaloneChestLootVerification.ActualStack("minecraft:golden_sword", 1,
                Map.of(), 17, null, false),
            new StandaloneChestLootVerification.ActualStack("minecraft:potion", 1,
                Map.of(), null, healingPotion, false));
        require(StandaloneChestLootVerification.compare(context, List.of(componentLoot),
                componentCounts, matchingComponents).status()
                == StandaloneChestLootVerification.Status.MATCH,
            "matching damage and potion metadata did not verify");
        var wrongDamage = List.of(
            new StandaloneChestLootVerification.ActualStack("minecraft:golden_sword", 1,
                Map.of(), 18, null, false),
            matchingComponents.get(1));
        require(StandaloneChestLootVerification.compare(context, List.of(componentLoot),
                componentCounts, wrongDamage).status()
                == StandaloneChestLootVerification.Status.MISMATCH,
            "wrong durability falsely claimed a loot match");
        ItemLoot unknownComponent = ItemLoot.of("minecraft:filled_map", 1, 1, 1)
            .withUnrepresentedComponents();
        ChestLootOutput unknownMetadata = new ChestLootOutput(14, 64, 20,
            "minecraft:chests/shipwreck_map", List.of(unknownComponent), 107L, true);
        require(StandaloneChestLootVerification.compare(context, List.of(unknownMetadata),
                Map.of("minecraft:filled_map", 1), List.of(
                    new StandaloneChestLootVerification.ActualStack("minecraft:filled_map", 1,
                        Map.of(), null, null, true))).status()
                == StandaloneChestLootVerification.Status.UNVERIFIED,
            "unrepresented stack components falsely claimed an exact result");
        ItemLoot unresolvedBook = ItemLoot.randomlyEnchantedBook(1);
        ChestLootOutput unresolved = new ChestLootOutput(12, 64, 20,
            "minecraft:chests/simple_dungeon", List.of(unresolvedBook), 105L, true);
        require(StandaloneChestLootVerification.compare(context, List.of(unresolved),
                enchantedCounts, correctEnchantment).status()
                == StandaloneChestLootVerification.Status.UNVERIFIED,
            "unresolved random enchantment falsely claimed an exact result");

        ChestLootOutput empty = new ChestLootOutput(12, 64, 20, "minecraft:chests/simple_dungeon",
            List.of(), 103L, true);
        require(StandaloneChestLootVerification.compare(context, List.of(empty), Map.of()).status()
                == StandaloneChestLootVerification.Status.MATCH,
            "a synchronized empty chest was treated as unavailable");
        require(StandaloneChestLootVerification.compare(context,
                List.of(ChestLootOutput.positionOnly(12, 64, 20)), Map.of()).status()
                == StandaloneChestLootVerification.Status.UNVERIFIED,
            "position-only loot incorrectly claimed an exact match");
        require(StandaloneChestLootVerification.compare(context,
                List.of(ChestLootOutput.positionOnly(12, 64, 20)),
                Map.of("minecraft:diamond", 3)).status()
                == StandaloneChestLootVerification.Status.UNVERIFIED,
            "position-only loot was treated as an empty-loot mismatch");
        require(StandaloneChestLootVerification.compare(null, List.of(left), Map.of()).status()
                == StandaloneChestLootVerification.Status.UNVERIFIED,
            "context-free loot incorrectly claimed an exact result");

        var gate = new StandaloneChestLootVerification.OpeningGate();
        long opening = gate.begin();
        require(!gate.claim(opening, false), "loot comparison ran before container synchronization");
        require(gate.claim(opening, true), "synchronized opening did not produce a result");
        require(!gate.claim(opening, true), "one opening produced duplicate results");
        long nextOpening = gate.begin();
        require(gate.claim(nextOpening, true), "new opening did not produce its own result");

        String diagnostic = StandaloneChestLootVerification.diagnostic(context,
            new BlockPos(10, 64, 20), new BlockPos(11, 64, 20), 7,
            List.of(left, right), mismatch, List.of("0:minecraft:diamondx2", "1:minecraft:breadx2"));
        require(diagnostic.contains("seed=42") && diagnostic.contains("version=\"26.3\"")
                && diagnostic.contains("dimension=0") && diagnostic.contains("doubleChest=true")
                && diagnostic.contains("table=\"minecraft:chests/village_house\"")
                && diagnostic.contains("lootSeed=101") && diagnostic.contains("exact=true")
                && diagnostic.contains("actualCounts="),
            "loot mismatch diagnostic omitted prediction context");
        Path log = Files.createTempDirectory("seed-explorer-loot-verification")
            .resolve("loot-mismatches.log");
        StandaloneChestLootVerification.appendDiagnostic(log, diagnostic);
        require(Files.readString(log).equals(diagnostic), "loot mismatch diagnostic was not persisted");
    }

    private static void commandProbe() throws Exception {
        var path = Files.createTempDirectory("seed-explorer-command-probe").resolve("config.properties");
        StandaloneSeedExplorerConfig config = StandaloneSeedExplorerConfig.load(path);
        StandaloneCommandRouter router = new StandaloneCommandRouter(config,
            StandaloneNavigationCapability.unavailable(), null, null, null);
        StandaloneCommandRouter.CommandContext context = new StandaloneCommandRouter.CommandContext() {
            @Override public BlockPos playerPosition() { return new BlockPos(8, 64, -9); }
            @Override public int dimension() { return 0; }
            @Override public PredictionContext predictionContext() { return new PredictionContext(42L, "26.3", 0); }
            @Override public List<ChestLootOutput> chestLoot() { return List.of(); }
        };
        require(router.execute("/seedexplorer seed -77", context).success(), "seed command failed");
        require(config.seed() == -77L, "seed command did not persist state");
        require(router.execute("se waypoint add \"Home base\"", context).success(), "waypoint add failed");
        require(config.waypoints(42L, 0).size() == 1, "waypoint used the wrong prediction context");
        require(router.execute("waypoint remove 1", context).success(), "waypoint indexed removal failed");
        require(router.execute("goto 1 70 2", context).handled(), "navigation command was not handled");
        require(!router.execute("goto 1 70 2", context).success(), "unavailable navigation reported success");
        require(StandaloneCommandRouter.tokenize("loot search \"enchanted book\"").size() == 3,
            "quoted command tokenization failed");
    }

    private static void oreNameProbe() {
        require(StandalonePredictedMineService.parseOre("minecraft:diamond_ore").name().equals("DIAMOND"),
            "diamond ore normalization failed");
        require(StandalonePredictedMineService.parseOre("nether_gold_ore").name().equals("NETHER_GOLD"),
            "nether gold ore normalization failed");
        require(StandalonePredictedMineService.parseOre("ancient debris").name().equals("ANCIENT_DEBRIS"),
            "ancient debris normalization failed");
    }

    private static void predictedMineProbe() throws Exception {
        StandalonePredictedMineService service = new StandalonePredictedMineService(
            StandaloneNavigationCapability.unavailable());
        StandalonePredictedMineService.StartResult started = service.start(
            new PredictionContext(42L, "26.3", 0), new BlockPos(0, -40, 0),
            "diamond", 2, 8);
        require(started.started(), "background ore prediction did not start");
        try {
            // This is a completion/correctness check, not a five-second startup benchmark.
            // Cold registry loading can exceed that budget while other worldgen probes run.
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (service.session().oreType() != null && service.session().targetCount() == 0
                && System.nanoTime() < deadline) Thread.sleep(5L);
            StandalonePredictedMineService.Session ready = service.session();
            require(ready.targetCount() > 0, "background ore prediction produced no probe targets: "
                + ready.navigationStatus());
            int index = ready.targetIndex();
            var target = ready.currentTarget();
            service.tick(new BlockPos(target.x, target.y, target.z));
            require(service.session().targetIndex() == index,
                "predicted mining advanced from proximity without verifying the loaded block");
        } finally {
            service.stop();
        }
    }

    private static void liveStoreProbe() throws Exception {
        var root = Files.createTempDirectory("seed-explorer-live-store-probe");
        LiveChunkSnapshot snapshot = new LiveChunkSnapshot(0, 3, -4, -64, 320);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) snapshot.setBlock(x, 63, z, Blocks.STONE.defaultBlockState());
        }
        try (StandaloneLiveChunkStore store = new StandaloneLiveChunkStore(root)) {
            store.saveNow("world:42", snapshot);
            LiveChunkSnapshot loaded = store.load("world:42", 0, 3, -4);
            require(loaded != null && loaded.nonAirBlockCount() == 256,
                "live chunk palette round trip failed");
            require(loaded.block(4, 63, 7).is(Blocks.STONE), "live chunk block state drifted");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
