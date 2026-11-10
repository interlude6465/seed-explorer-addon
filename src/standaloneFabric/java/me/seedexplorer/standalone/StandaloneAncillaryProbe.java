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
import java.util.List;

/** Focused regression checks for the standalone non-map service layer. */
public final class StandaloneAncillaryProbe {
    private StandaloneAncillaryProbe() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        rngProbe();
        lootProbe();
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
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (service.session().oreType() != null && service.session().targetCount() == 0
            && System.nanoTime() < deadline) Thread.sleep(5L);
        StandalonePredictedMineService.Session ready = service.session();
        require(ready.targetCount() > 0, "background ore prediction produced no probe targets");
        int index = ready.targetIndex();
        var target = ready.currentTarget();
        service.tick(new BlockPos(target.x, target.y, target.z));
        require(service.session().targetIndex() == index,
            "predicted mining advanced from proximity without verifying the loaded block");
        service.stop();
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
