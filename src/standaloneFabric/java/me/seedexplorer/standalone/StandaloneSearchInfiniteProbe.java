package me.seedexplorer.standalone;
import java.util.*;
import me.seedexplorer.addon.loot.*;
import me.seedexplorer.addon.worldgen.*;
import me.seedexplorer.addon.structures.StructureType;
/** Focused regressions for selected pool routing and bounded infinite traversal. */
public final class StandaloneSearchInfiniteProbe {
 public static void main(String[]args)throws Exception {
  net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();WorldgenEngine.offlineRegistryAccess();
  var c=new PredictionContext(1,"26.3",0);
  var helmet=LootSearchParser.parseTerm("diamond helmet [aqua_affinity=1, unbreaking=3, respiration=3, protection=4]");
  require(StandaloneSearch.validLootTerm(c,helmet,true),"valid four-enchantment helmet rejected");
  require(StandaloneSearch.candidateStructureTypes(c,helmet).equals(Set.of(StructureType.END_CITY)),"four-enchantment helmet searched irrelevant pools");
  require(!StandaloneSearch.validLootTerm(c,helmet,false),"End-only request allowed Overworld-only scope");
  var protectedItem=List.of(new LootSearchTerm.Property("minecraft:protection",4));
  require(StandaloneSearch.enchantmentConflict(protectedItem,"minecraft:fire_protection").contains("Cannot coexist"),"protection conflict not explained");
  require(StandaloneSearch.enchantmentConflict(protectedItem,"minecraft:respiration")==null,"compatible helmet enchantment blocked");
  var tiles=new StandaloneSearch.OutwardTiles(-1,-513);Set<String> seen=new HashSet<>();int ring=-1;
  for(int i=0;i<10000;i++) {int[] t=tiles.next();require(t[2]>=ring,"outward ring decreased");ring=t[2];
   require(seen.add(t[0]+":"+t[1]),"infinite traversal repeated a tile");
   if(i==0)require(t[0]==-1&&t[1]==-2,"negative coordinate floor division failed");
  }
  require(ring>16,"infinite traversal stopped at8192");
  require(Arrays.stream(StandaloneSearch.OutwardTiles.class.getDeclaredFields()).allMatch(f->f.getType()==int.class),"infinite iterator retains growing queued work");
  verifyInfiniteDungeonProgress(c);
  var registry=WorldgenEngine.offlineRegistryAccess();
  var independentlyRecorded=LootTableSimulator.simulateVanilla(c.withDimension(1),"minecraft:chests/end_city_treasure",5875608748547006662L,-9247,117,14150);
  var boots=LootSearchParser.parseTerm("iron boots [mending=1,unbreaking=3]");
  require(independentlyRecorded.stream().anyMatch(boots::matches),"multi-enchantment match lost independent nonship End-city treasure inventory");
  var storage=java.nio.file.Files.createTempDirectory("seed-search-retention-");System.setProperty("seedexplorer.lootCacheDir",storage.toString());
  var diamond=LootSearchParser.parseTerm("diamond");var remaining=new LinkedHashMap<String,Long>();remaining.put(diamond.signature(),65L);
  var retention=new StandaloneSearch.Job();retention.infinite=true;var retained=new ArrayList<StandaloneSearch.Result>();var chestKeys=new HashSet<String>();
  for(int i=0;i<65;i++) {
   var structure=new me.seedexplorer.addon.structures.GeneratedStructure(i*32,0,StructureType.END_CITY);
   var chest=new ChestLootOutput(i*32,64,0,"test",List.of(new ItemLoot("minecraft:diamond",1,1,1,"Diamond",false,null,0,null)),i);
   StandalonePersistentLootCache.put(c.withDimension(1),structure,List.of(chest));
   StandaloneSearch.matchLoot(c.withDimension(1),structure,Map.of(diamond.signature(),diamond),remaining,chestKeys,retained,retention,0,0,0);
  }
  require(retention.results.size()==65 && retention.results.getFirst().x()==0 && remaining.get(diamond.signature())==0,"infinite search lost an earlier contributing chest beyond64");
  System.out.println("Infinite search focused: PASS (End-only4-enchant helmet, compatibility,10000unique negative-coordinate tiles, >8192 expansion, constant iterator memory, independent End-city gear)");
  if(args.length>0 && args[0].equals("oracle")) {
   var dir=java.nio.file.Files.createTempDirectory("seed-search-infinite-probe-");
   System.setProperty("seedexplorer.structureCacheDir",dir.resolve("structures").toString());System.setProperty("seedexplorer.lootCacheDir",dir.resolve("loot").toString());
   var finite=StandaloneSearch.loot(c,-9247,14150,128,List.of(new ItemLootRequest(boots,1)),true);await(finite,60);
   require(finite.results.stream().anyMatch(r->r.dimension()==1&&r.x()==-9247&&r.y()==117&&r.z()==14150&&r.chest().seed()==5875608748547006662L),"finite End-city inventory regression: "+finite.status);
   require(finite.coverage.contains("End [END_CITY]")&&!finite.coverage.contains("Overworld"),"finite search visited irrelevant dimensions: "+finite.coverage);
   var infinite=StandaloneSearch.loot(c,-9247,14150,0,List.of(new ItemLootRequest(boots,1)),true);await(infinite,60);
   require(infinite.results.stream().anyMatch(r->r.chest().predictedItems().stream().anyMatch(boots::matches)),"infinite did not find matching actual gear");
   require(infinite.status.contains("nearest unverified")&&infinite.regionsChecked>0,"infinite completion overstated nearest ordering");
   var cancel=StandaloneSearch.loot(c,0,0,0,List.of(new ItemLootRequest(helmet,147456)),true);Thread.sleep(100);cancel.cancel();
   var before=cancel.results;Thread.sleep(200);require(cancel.cancelled()&&cancel.done&&cancel.results==before,"cancel published stale result");
   System.out.println("Infinite search oracle: PASS finite="+finite.status+"; infinite="+infinite.status+"; cancellation PASS");
  }
 }
 static void verifyInfiniteDungeonProgress(PredictionContext context) {
  for(boolean mixed:List.of(false,true)) {
   var job=new StandaloneSearch.Job();job.infinite=true;job.dungeonLimit=32;job.dungeonDeadline=0;
   var pools=new HashMap<Integer,Set<StructureType>>();pools.put(0,Set.of(StructureType.DUNGEON));
   if(mixed)pools.put(1,Set.of(StructureType.END_CITY));
   Set<String> visited=new HashSet<>();int[] structureTiles={0};
   StandaloneSearch.searchInfinite(context,-1,-513,pools,Map.of(),new LinkedHashMap<>(),new HashSet<>(),
    new ArrayList<>(),job,tile->{
     if(tile.types().contains(StructureType.DUNGEON)) {
      require(tile.minChunkX()==tile.maxChunkX()&&tile.minChunkZ()==tile.maxChunkZ(),"dungeons batched ahead of structures");
      require(visited.add(tile.minChunkX()+":"+tile.minChunkZ()),"infinite replayed a dungeon chunk");
      if(visited.size()==1)require(tile.minChunkX()==-1&&tile.minChunkZ()==-33,"dungeons did not start at the origin chunk");
      if(mixed)require(structureTiles[0]==visited.size(),"dungeon replay starved structure regions");
      if(visited.size()==65)job.cancel();
     }else structureTiles[0]++;
     return List.of();
    });
   require(visited.size()==65&&job.dungeonChunksChecked==65&&!job.dungeonLimited,
    "infinite dungeon search inherited finite cap/deadline");
   require(job.cancelled()&&job.done,"infinite dungeon traversal ignored cancellation");
  }
  System.out.println("Infinite dungeon scheduling: PASS (65 unique chunks, expired finite budget ignored, mixed-dimension fairness, cancellation)");
 }
 static void await(StandaloneSearch.Job job,int seconds)throws Exception {long deadline=System.nanoTime()+seconds*1000000000L;while(!job.done&&System.nanoTime()<deadline)Thread.sleep(25);if(!job.done){job.cancel();throw new AssertionError("Budget exceeded: "+job.status);}require(!job.failed,job.status);}
 static void require(boolean pass,String message){if(!pass)throw new AssertionError(message);}
}
