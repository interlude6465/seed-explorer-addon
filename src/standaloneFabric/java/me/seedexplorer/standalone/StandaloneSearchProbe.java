package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Quantity and enchantment checks use distinct identical adjacent inventories deliberately. */
public final class StandaloneSearchProbe {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        verifyMapClustering();
        if (args.length > 0 && args[0].equals("oracle")) {
            runArchivedShipwreckSearch();
            return;
        }
        ItemLoot diamonds = new ItemLoot("minecraft:diamond", 3, 3, 1, "Diamond", false, null, 0, null);
        List<ChestLootOutput> chests = List.of(
            new ChestLootOutput(0, 60, 0, "test", List.of(diamonds), 1),
            new ChestLootOutput(1, 60, 0, "test", List.of(diamonds), 2));
        LootSearchTerm diamond = new LootSearchTerm("minecraft:diamond", null, 0, "Diamond");
        require(StandaloneSearch.containsAll(chests, List.of(new ItemLootRequest(diamond, 6))), "distinct inventories undercounted");
        require(!StandaloneSearch.containsAll(chests, List.of(new ItemLootRequest(diamond, 7))), "quantity overcounted");
        Map<String, LootSearchTerm> terms = Map.of(diamond.signature(), diamond);
        Map<String, Long> remaining = new LinkedHashMap<>(Map.of(diamond.signature(), 6L));
        require(!StandaloneSearch.takeMatchingLoot(chests.get(0), terms, remaining).isEmpty(), "first chest skipped");
        require(remaining.get(diamond.signature()) == 3L, "first chest quantity");
        require(!StandaloneSearch.takeMatchingLoot(chests.get(1), terms, remaining).isEmpty(), "second chest skipped");
        require(remaining.get(diamond.signature()) == 0L, "cross-structure quantity");
        ItemLoot enchantedBook = new ItemLoot("minecraft:enchanted_book", 1, 1, 1, "Book", false,
            "minecraft:unbreaking", 3, null);
        ChestLootOutput oneBook = new ChestLootOutput(2, 60, 0, "test", List.of(enchantedBook), 3);
        LootSearchTerm anyBook = new LootSearchTerm("minecraft:enchanted_book", null, 0, "Any Book");
        LootSearchTerm specificBook = new LootSearchTerm("minecraft:enchanted_book", "unbreaking", 3,
            "Unbreaking III Book");
        Map<String, LootSearchTerm> bookTerms = new LinkedHashMap<>();
        bookTerms.put(anyBook.signature(), anyBook);
        bookTerms.put(specificBook.signature(), specificBook);
        Map<String, Long> bookRemaining = new LinkedHashMap<>();
        bookRemaining.put(anyBook.signature(), 1L);
        bookRemaining.put(specificBook.signature(), 1L);
        StandaloneSearch.takeMatchingLoot(oneBook, bookTerms, bookRemaining);
        require(bookRemaining.get(specificBook.signature()) == 0L
            && bookRemaining.get(anyBook.signature()) == 1L, "one book satisfied two requirements");
        ItemLoot book = new ItemLoot("minecraft:enchanted_book", 1, 1, 1, "Book", false, "minecraft:sharpness", 5, null);
        require(new LootSearchTerm("minecraft:enchanted_book", "sharpness", 5, "Sharpness V").matches(book), "enchantment not found");
        require(!new LootSearchTerm("minecraft:enchanted_book", "sharpness", 4, "Sharpness IV").matches(book), "wrong enchantment level accepted");
        require(StandaloneSearch.convertCoordinate(-9, 0, -1) == -2, "Nether coordinate rounding");
        var chestCenter = new net.minecraft.world.phys.Vec3(0.5, 60.5, 0.5);
        require(StandaloneChestHighlightRenderer.eligible(List.of(chests.getFirst()), 0, 0,
            chestCenter.add(299, 0, 0)).size() == 1, "299-block highlight boundary");
        require(StandaloneChestHighlightRenderer.eligible(List.of(chests.getFirst()), 0, 0,
            chestCenter.add(299.01, 0, 0)).isEmpty(), "out-of-range highlight");
        require(StandaloneChestHighlightRenderer.eligible(chests, 0, -1, chestCenter).isEmpty(),
            "cross-dimension highlight");
        require(StandaloneSearch.convertCoordinate(4_000_000, -1, 0) == 30_000_000, "world border clamp");
        var context = new me.seedexplorer.addon.worldgen.PredictionContext(42L, "26.3", 0);
        require("minecraft:plains".equals(StandaloneSearch.resolveBiome(context, "plains")),
            "biome search used builtin registries without dynamic biomes");
        require(StandaloneSearch.automaticItem(context.withDimension(-1), "golden apple", true)
            .itemId().equals("minecraft:golden_apple"), "auto search missed an ordinary item name");
        var chestItems = StandaloneSearch.chestItemIds(context, true);
        require(chestItems.contains("minecraft:enchanted_golden_apple")
            && chestItems.contains("minecraft:enchanted_book")
            && !chestItems.contains("minecraft:command_block"), "chest suggestion catalog");
        require(LootSearchParser.suggest("golden apple", 3, chestItems).stream()
            .anyMatch(term -> term.itemId().equals("minecraft:golden_apple")), "golden apple suggestions");
        require(StandaloneSearch.validLootTerm(context, specificBook, true), "Unbreaking III book unavailable");
        LootSearchTerm multi=LootSearchParser.parseTerm("enchanted book [minecraft:unbreaking=3, minecraft:mending=1]");
        require(multi!=null && multi.properties().size()==2 && StandaloneSearch.validLootTerm(context,multi,true),
            "multiple enchantment editor term was not validated");
        var combinedBook=enchantedBook.withPredictedEnchantments("Unbreaking III, Mending I");
        require(multi.matches(combinedBook) && !multi.matches(enchantedBook),"multiple properties did not require one matching item");
        require(!StandaloneSearch.validLootTerm(context,LootSearchTerm.withProperties("minecraft:golden_apple","bad",
            List.of(new LootSearchTerm.Property("minecraft:unbreaking",3))),true),"non-enchantable chest item accepted enchantments");
        require(StandaloneSearch.enchantmentOptions(context,"minecraft:diamond_sword",true).stream()
            .anyMatch(p->p.enchantment().equals("minecraft:unbreaking")),"natural enchanted equipment unavailable");
        require(!StandaloneSearch.validLootTerm(context,LootSearchTerm.withProperties("minecraft:enchanted_book","bad",
            List.of(new LootSearchTerm.Property("minecraft:sharpness",5),new LootSearchTerm.Property("minecraft:sharpness",4))),true),
            "duplicate incompatible enchantments were accepted");
        require(!StandaloneSearch.validLootTerm(context,
            new LootSearchTerm("minecraft:enchanted_book", "imaginary", 3, "Imaginary III Book"), true),
            "invented enchantment suggestion accepted");
        require(!StandaloneSearch.validLootTerm(context,
            new LootSearchTerm("minecraft:enchanted_book", "wind_burst", 1, "Wind Burst I Book"), true),
            "trial-only enchantment advertised as supported chest loot");
        var historicalContext = new me.seedexplorer.addon.worldgen.PredictionContext(42L, "1.19.4", 0);
        var soulSpeedBoots = LootSearchTerm.withProperties("minecraft:diamond_boots", "Soul Speed III boots",
            List.of(new LootSearchTerm.Property("minecraft:soul_speed", 3)));
        require(StandaloneSearch.validLootTerm(historicalContext, soulSpeedBoots, true)
            && StandaloneSearch.candidateStructureTypes(historicalContext, soulSpeedBoots)
                .contains(me.seedexplorer.addon.structures.StructureType.BASTION),
            "explicit historical Soul Speed loot option was omitted from Bastion routing");
        require(StandaloneSearch.enchantmentOptions(historicalContext, "minecraft:diamond_boots", true)
            .stream().anyMatch(option -> option.enchantment().equals("minecraft:soul_speed")),
            "historical explicit Soul Speed option was not offered by the enchantment editor");
        for(String unavailable:List.of("minecraft:density","minecraft:breach","minecraft:lunge")) {
            var oldBook=LootSearchTerm.withProperties("minecraft:enchanted_book", unavailable,
                List.of(new LootSearchTerm.Property(unavailable,1)));
            require(!StandaloneSearch.validLootTerm(historicalContext,oldBook,true)
                && !StandaloneSearch.candidateStructureTypes(historicalContext,oldBook)
                    .contains(me.seedexplorer.addon.structures.StructureType.DUNGEON),
                "post-1.19 enchantment leaked into historical dungeon routing: "+unavailable);
        }
        require(!StandaloneSearch.validLootTerm(historicalContext,
            LootSearchTerm.withProperties("minecraft:enchanted_book","Sharpness VI",
                List.of(new LootSearchTerm.Property("minecraft:sharpness",6))),true),
            "historical enchantment level exceeded its supported maximum");
        require(StandaloneSearch.automaticItem(context, "unbreaking 999999999999999 book", true) == null,
            "invalid enchantment level was accepted");
        var wreckType = me.seedexplorer.addon.structures.StructureType.SHIPWRECK;
        var bastionType = me.seedexplorer.addon.structures.StructureType.BASTION;
        var tiles = List.of(
            new StandaloneSearch.LootSearchTile(context, 0, 0, 32, 0, 0, 0, 0, java.util.Set.of(wreckType)),
            new StandaloneSearch.LootSearchTile(context, 0, 0, 32, -1, 0, -1, 0, java.util.Set.of(wreckType)),
            new StandaloneSearch.LootSearchTile(context.withDimension(-1), 0, 0, 32, 0, 0, 0, 0,
                java.util.Set.of(bastionType)));
        var visited = new java.util.ArrayList<StandaloneSearch.LootCandidate>();
        StandaloneSearch.visitNearestCandidates(tiles, new StandaloneSearch.Job(), tile ->
            tile.context().dimension() == -1
                ? List.of(new me.seedexplorer.addon.structures.GeneratedStructure(2, 0, bastionType))
                : tile.minChunkX() < 0
                    ? List.of(new me.seedexplorer.addon.structures.GeneratedStructure(-1, 0, wreckType))
                    : List.of(new me.seedexplorer.addon.structures.GeneratedStructure(15, 0, wreckType)),
            visited::add);
        require(visited.size() == 3 && visited.get(0).structure().x == -1
            && visited.get(1).context().dimension() == -1 && visited.get(2).structure().x == 15,
            "loot traversal consumed a farther tile/dimension before a nearer structure");
        var dungeonType = me.seedexplorer.addon.structures.StructureType.DUNGEON;
        var mixedTiles = List.of(
            new StandaloneSearch.LootSearchTile(context, 0, 0, 64, -4, -4, 3, 3,
                java.util.Set.of(dungeonType)),
            new StandaloneSearch.LootSearchTile(context, 0, 0, 64, 0, 0, 3, 3,
                java.util.Set.of(wreckType)));
        visited.clear();
        int[] dungeonChunks = {0};
        var nearestJob = new StandaloneSearch.Job();
        StandaloneSearch.visitNearestCandidates(mixedTiles, nearestJob, tile -> {
            if (!tile.types().contains(dungeonType))
                return List.of(new me.seedexplorer.addon.structures.GeneratedStructure(48, 0, wreckType));
            require(tile.minChunkX() == tile.maxChunkX() && tile.minChunkZ() == tile.maxChunkZ(),
                "dungeon tile was not split before expensive replay");
            dungeonChunks[0]++;
            return tile.minChunkX() == 0 && tile.minChunkZ() == 0
                ? List.of(new me.seedexplorer.addon.structures.GeneratedStructure(1, 1, dungeonType))
                : List.of();
        }, candidate -> { visited.add(candidate); nearestJob.cancel(); });
        require(visited.size() == 1 && visited.getFirst().structure().type == dungeonType,
            "farther structure satisfied request before nearby dungeon");
        require(dungeonChunks[0] < 64, "dungeon traversal eagerly replayed entire region");
        var boundedJob=new StandaloneSearch.Job();boundedJob.dungeonLimit=2;
        var loadedChunks=new java.util.HashSet<String>();visited.clear();
        var duplicateTiles=new java.util.ArrayList<>(mixedTiles);duplicateTiles.addAll(mixedTiles);
        StandaloneSearch.visitNearestCandidates(duplicateTiles,boundedJob,tile->{
            if(!tile.types().contains(dungeonType))return List.of(new me.seedexplorer.addon.structures.GeneratedStructure(48,0,wreckType));
            require(loadedChunks.add(tile.minChunkX()+":"+tile.minChunkZ()),"dungeon chunk replayed twice");return List.of();
        },visited::add);
        require(loadedChunks.size()==2 && boundedJob.dungeonLimited && visited.size()==1,
            "dungeon bound blocked useful structure results or failed to report incomplete nearest coverage");
        LootSearchTerm unbreaking = LootSearchParser.parseTerm("Unbreaking III book");
        require(unbreaking != null && StandaloneSearch.candidateStructureTypes(context, unbreaking)
            .contains(me.seedexplorer.addon.structures.StructureType.DUNGEON), "enchanted-book pool skipped");
        LootSearchTerm apple = new LootSearchTerm("minecraft:enchanted_golden_apple", null, 0,
            "Enchanted Golden Apple");
        require(StandaloneSearch.candidateStructureTypes(context, apple)
            .contains(me.seedexplorer.addon.structures.StructureType.DUNGEON), "enchanted-apple pool skipped");
        require(StandaloneSearch.candidateStructureTypes(context,
            new LootSearchTerm("minecraft:water_bucket", null, 0, "Water Bucket"))
            .contains(me.seedexplorer.addon.structures.StructureType.VILLAGE), "village loot pool skipped");
        require(StandaloneSearch.resolveStructure(context, "woodlnd mansion")
            == me.seedexplorer.addon.structures.StructureType.MANSION, "fuzzy structure matching");
        require(StandaloneSearch.resolveStructure(context, "pillager tower")
            == me.seedexplorer.addon.structures.StructureType.OUTPOST, "structure alias matching");
        var sessionJob=new StandaloneSearch.Job();
        require(sessionJob.requestState(new ItemLootRequest(diamond,1)).equals("Searching"),"new request row was not red-searching state");
        sessionJob.remaining=Map.of(diamond.signature(),0L);sessionJob.done=true;
        require(sessionJob.requestState(new ItemLootRequest(diamond,1)).equals("Found"),"fulfilled row state missing");
        sessionJob.cancel();require(sessionJob.requestState(new ItemLootRequest(diamond,1)).equals("Cancelled"),"cancelled row state missing");
        Object connection=new Object();StandaloneSearchRuntime.identity(connection,"world:26.3",42);
        var session=StandaloneSearchRuntime.attach(context,12,34);session.job=new StandaloneSearch.Job();
        session.requests.add(new ItemLootRequest(diamond,2));
        require(StandaloneSearchRuntime.attach(context,90,91)==session && session.x==12 && session.requests.size()==1,
            "reopening lost background requests/origin");
        StandaloneSearchRuntime.identity(connection,"world:26.3",42);
        require(!session.job.cancelled(),"same-world tick cancelled background search");
        StandaloneSearchRuntime.identity(connection,"world:26.3",43);
        require(session.job.cancelled() && StandaloneSearchRuntime.current()==null,"seed change retained background search");
        session=StandaloneSearchRuntime.attach(context,12,34);session.job=new StandaloneSearch.Job();
        StandaloneSearchRuntime.identity(null,"world:26.3",43);
        require(session.job.cancelled(),"disconnect did not cancel search");
        StandaloneSearchInfiniteProbe.main(new String[0]);
        System.out.println("Standalone search probe: PASS");
    }

    private static void verifyMapClustering() {
        var structures = new java.util.ArrayList<me.seedexplorer.addon.structures.GeneratedStructure>();
        var type = me.seedexplorer.addon.structures.StructureType.NETHER_FOSSIL;
        for (int x = -1024; x < 1024; x += 32) for (int z = -1024; z < 1024; z += 32)
            structures.add(new me.seedexplorer.addon.structures.GeneratedStructure(x, 40, z,
                Math.floorDiv(x, 16), Math.floorDiv(z, 16), type, "", false));
        int previous = 0;
        for (double scale : new double[] {16, 8, 4, 2, 1}) {
            var markers = StandaloneMapPresentation.markers(structures, scale);
            require(markers.size() >= previous, "zooming in hid fossil groups");
            require(markers.stream().mapToInt(StandaloneMapPresentation.Marker::count).sum() == structures.size(),
                "clustering discarded fossil predictions");
            previous = markers.size();
        }
        require(previous == structures.size(), "close zoom did not reveal every fossil");
        require(StandaloneMapPresentation.markers(structures, 8).size() <= structures.size() / 16,
            "dense map did not reduce icon draws");
        var reversed = new java.util.ArrayList<>(structures);
        java.util.Collections.reverse(reversed);
        var a = StandaloneMapPresentation.markers(structures, 8).stream().map(m -> m.structure().getCoordsString()).collect(java.util.stream.Collectors.toSet());
        var b = StandaloneMapPresentation.markers(reversed, 8).stream().map(m -> m.structure().getCoordsString()).collect(java.util.stream.Collectors.toSet());
        require(a.equals(b), "pan/order changed fossil cluster representatives");
        System.out.println("2D map density: 4096 fossils -> " + a.size() + " markers at 8 blocks/pixel");
    }

    private static void runArchivedShipwreckSearch() throws Exception {
        var root = java.nio.file.Files.createTempDirectory("seed-explorer-search-oracle-");
        System.setProperty("seedexplorer.structureCacheDir", root.resolve("structures").toString());
        System.setProperty("seedexplorer.lootCacheDir", root.resolve("loot").toString());
        var context = new me.seedexplorer.addon.worldgen.PredictionContext(-7046029254386353131L, "26.3", 0);
        me.seedexplorer.addon.worldgen.WorldgenEngine.publicRegistryAccess();
        var request = new ItemLootRequest(new LootSearchTerm("minecraft:suspicious_stew", null, 0,
            "Suspicious Stew"), 2);
        StandaloneSearch.Job job = StandaloneSearch.loot(context, 12037, 15480, 32, List.of(request), false);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        while (!job.done && System.nanoTime() < deadline) Thread.sleep(100);
        if (!job.done) { job.cancel(); throw new AssertionError("Archived shipwreck search timed out: " + job.status); }
        require(job.results.size() == 1, "archived shipwreck search: " + job.status + " " + job.results);
        var result = job.results.getFirst();
        require(result.x() == 12037 && result.y() == 42 && result.z() == 15480 && result.dimension() == 0,
            "search returned a different chest than the independent live fixture");
        require(result.chest() != null && result.chest().seed() == 8220817990207311449L,
            "search lost independently recorded chest loot seed");
        require(result.chest().predictedItems().stream().filter(request.term()::matches)
            .mapToLong(ItemLoot::minCount).sum() == 2, "search did not return both observed stews");
        require(job.status.startsWith("Predicted request"), "fulfilled search reported incomplete: " + job.status);
        System.out.println("Standalone search oracle: PASS; independent fixture 26.3-stew-properties-live-shipwreck.txt"
            + "; chest=12037,42,15480; stew=2; cache=" + root);
        runArchivedMultiWreckSearch();
        runArchivedDungeonSearch();
    }

    private static void runArchivedMultiWreckSearch() throws Exception {
        var context = new me.seedexplorer.addon.worldgen.PredictionContext(Long.MIN_VALUE, "26.3", 0);
        var request = new ItemLootRequest(new LootSearchTerm("minecraft:poisonous_potato", null, 0,
            "Poisonous Potato"), 9);
        var job = StandaloneSearch.loot(context, -5356, 6389, 192, List.of(request), false);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        while (!job.done && System.nanoTime() < deadline) Thread.sleep(100);
        if (!job.done) { job.cancel(); throw new AssertionError("Multi-wreck search timed out: " + job.status); }
        require(job.results.size() == 2, "multi-wreck results: " + job.status + " " + job.results);
        var first = job.results.get(0);
        var second = job.results.get(1);
        require(first.x() == -5356 && first.y() == 41 && first.z() == 6389
            && first.chest().seed() == -213769163421774178L,
            "nearest multi-wreck inventory differs from independent fixture");
        require(second.x() == -5285 && second.y() == 53 && second.z() == 6543
            && second.chest().seed() == -6657626484374031792L,
            "second multi-wreck inventory differs from independent fixture");
        require(job.results.stream().flatMap(result -> result.chest().predictedItems().stream())
            .filter(request.term()::matches).mapToLong(ItemLoot::minCount).sum() == 9,
            "multi-wreck search did not accumulate independently recorded quantities 3+6");
        require(job.status.startsWith("Predicted request"), "multi-wreck request not fulfilled: " + job.status);
        System.out.println("Standalone multi-wreck search oracle: PASS; independent fixture "
            + "history-retests/26.3-overworld-broad-mineshaft-retrace.txt; poisonous_potato=3+6; distinct wrecks=2");
    }

    private static void runArchivedDungeonSearch() throws Exception {
        var context = new me.seedexplorer.addon.worldgen.PredictionContext(1L, "26.3", 0);
        var request = new ItemLootRequest(new LootSearchTerm("minecraft:name_tag", null, 0, "Name Tag"), 1);
        var job = StandaloneSearch.loot(context, 6787, -8417, 16, List.of(request), false);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        while (!job.done && System.nanoTime() < deadline) Thread.sleep(100);
        if (!job.done) { job.cancel(); throw new AssertionError("Dungeon search timed out: " + job.status); }
        require(job.results.size() == 1, "dungeon search results: " + job.status);
        var result = job.results.getFirst();
        require(result.x() == 6787 && result.y() == -16 && result.z() == -8420
            && result.structure().type == me.seedexplorer.addon.structures.StructureType.DUNGEON
            && result.chest().seed() == 3660284170677919530L,
            "dungeon result differs from the independently generated inventory");
        require(job.status.startsWith("Predicted request"), "dungeon request incomplete: " + job.status);
        System.out.println("Standalone dungeon search oracle: PASS; independent fixture "
            + "26.3-remaining-families-two-seeds.txt; chest=6787,-16,-8420; name_tag=1");
    }
    private static void require(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
}
