package me.seedexplorer.standalone.test;

import java.util.*;
import java.nio.file.*;
import me.seedexplorer.addon.render.*;
import me.seedexplorer.addon.live.LiveChunkSnapshot;
import me.seedexplorer.standalone.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Fixed-camera geometry fixtures, plus the reported coast, using actual generated-world blocks. */
@SuppressWarnings({"UnstableApiUsage","unchecked"})
public final class StandaloneSpectatorVisualGameTest implements FabricClientGameTest {
    private final List<String> report=new ArrayList<>();
    public void runTest(ClientGameTestContext test) {
        try(TestSingleplayerContext world=test.worldBuilder().setUseConsistentSettings(true)
            .adjustSettings(settings->{
                var presets=settings.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET);
                settings.setWorldType(new net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.WorldTypeEntry(presets.getOrThrow(WorldPresets.NORMAL)));
                settings.setSeed("1");settings.setAllowCommands(true);
            }).create()) {
            world.getConnection().waitForChunksRender();
            var config=(StandaloneSeedExplorerConfig)get(StandaloneAncillaryRuntime.get(),"config");
            test.runOnClient(client->{config.setSeed(1);client.options.pauseOnLostFocus=false;client.options.framerateLimit().set(30);
                client.player.connection.sendCommand("gamemode spectator");});
            if(!Boolean.getBoolean("seedexplorer.spectatorCoastOnly")) {
            // Real server-built solid hill at high Y, including connected caves and a one-block shelf.
            world.getServer().runOnServer(server->{
                var level=server.overworld();var pos=new BlockPos.MutableBlockPos();
                for(int x=0;x<32;x++)for(int z=0;z<32;z++)for(int y=200;y<=240;y++) {
                    boolean room=x>=8&&x<=23&&z>=8&&z<=23&&y>=208&&y<=218;
                    boolean upper=x>=18&&x<=25&&z>=18&&z<=25&&y>=216&&y<=229;
                    boolean entrance=x<9&&z>=14&&z<=17&&y>=210&&y<=214;
                    boolean shelf=x>=12&&x<=16&&z==12&&y==212;
                    level.setBlock(pos.set(x,y,z),(!room&&!upper&&!entrance||shelf)?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
                }
            });
            test.runOnClient(client->client.player.connection.sendCommand("tp @s 14.5 210 14.5"));
            test.waitFor(client->client.player.getY()>200,1200);world.getConnection().waitForChunksRender();test.waitTicks(40);
            List<Case> cases=List.of(
                new Case("hill-up",4.5,230.5,4.5,170,0,true),
                new Case("solid-toward-cave",6.5,216.5,15.5,90,-90,true),
                new Case("below-ledge",14.5,211.5,12.5,160,0,false),
                new Case("floor-front",14.5,210.5,14.5,25,0,false),
                new Case("floor-opposite",14.5,207.5,14.5,155,0,true),
                new Case("transition-solid",7.95,215.5,20.5,90,-90,true),
                new Case("transition-air",8.05,215.5,20.5,90,-90,false),
                new Case("connected-caves",20.5,222.5,20.5,130,135,false),
                new Case("connected-caves-inside",17.5,222.5,20.5,65,-90,true));
            for(var c:cases) {
                open(test,config,0,c,false);ready(test);
                test.runOnClient(client->{var r=Terrain3DRenderer.shared();
                    if((boolean)get(r,"cameraInsideSolid")!=c.solid)throw new AssertionError(c.name+" occupancy wrong");
                    if(!(boolean)get(r,"gpuCaveView"))throw new AssertionError(c.name+" did not select voxel geometry");
                });
                test.takeScreenshot("spectator-"+c.name);stats(test,c.name);
                test.runOnClient(client->client.gui.setScreen(null));
            }
            // Above-ground air restores the user's selected Surface mode.
            var outside=new Case("outside-restored",14.5,248.5,14.5,25,0,false);
            open(test,config,0,outside,false);ready(test);
            test.runOnClient(client->{if((boolean)get(Terrain3DRenderer.shared(),"gpuCaveView"))throw new AssertionError("surface mode not restored");});
            test.takeScreenshot("spectator-outside-restored");stats(test,"outside-restored");
            test.runOnClient(client->client.gui.setScreen(null));
            // Reported real Nether camera; confirms backface culling preserves lava/floor/roof.
            test.runOnClient(client->client.player.connection.sendCommand("execute in minecraft:the_nether run tp @s -5883.5 32 -1429.5 -90 10"));
            test.waitFor(client->client.level.dimension()==Level.NETHER,1200);world.getConnection().waitForChunksRender();test.waitTicks(40);
            open(test,config,-1,new Case("nether",-5883.5,33.62,-1429.5,80,-90,false),true);ready(test);test.waitTicks(160);
            test.takeScreenshot("spectator-nether");stats(test,"nether");test.runOnClient(client->client.gui.setScreen(null));
            // The original Nether shot was in air with Underground enabled.
            // Also test automatic activation from an actual netherrack voxel,
            // facing an independently observed hollow space, with Surface set.
            Case netherSolid=test.computeOnClient(client->{
                int[][] directions={{1,0},{-1,0},{0,1},{0,-1}};
                for(int radius=0;radius<=32;radius++)for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++) {
                    if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
                    for(int y=32;y<=100;y++) {
                        BlockPos pos=new BlockPos(-5884+dx,y,-1430+dz);
                        if(!client.level.getBlockState(pos).is(Blocks.NETHERRACK))continue;
                        for(int[] direction:directions) {
                            boolean hollow=true;
                            for(int distance=1;distance<=3;distance++)
                                if(!client.level.getBlockState(pos.offset(direction[0]*distance,0,direction[1]*distance)).isAir())hollow=false;
                            if(hollow)return new Case("nether-inside",pos.getX()+0.5,y+0.5,pos.getZ()+0.5,90,
                                Math.toDegrees(Math.atan2(-direction[0],-direction[1])),true);
                        }
                    }
                }
                throw new AssertionError("No loaded netherrack wall beside air for the Nether inside-solid fixture");
            });
            open(test,config,-1,netherSolid,false);ready(test);
            test.waitFor(client->(boolean)get(Terrain3DRenderer.shared(),"cameraInsideSolid"),1200);
            test.runOnClient(client->{if(!(boolean)get(Terrain3DRenderer.shared(),"gpuCaveView"))
                throw new AssertionError("Nether inside-solid camera did not select cave geometry automatically");});
            test.takeScreenshot("spectator-nether-inside");stats(test,"nether-inside");
            test.runOnClient(client->client.gui.setScreen(null));
            }
            // User's coast: identical fixed camera, with streaming statistics at bounded checkpoints.
            test.runOnClient(client->client.player.connection.sendCommand("execute in minecraft:overworld run tp @s -9887 141 -967 0 65"));
            test.waitFor(client->client.level.dimension()==Level.OVERWORLD&&client.player.getX()<-9800,1200);
            world.getConnection().waitForChunksRender();test.waitTicks(40);test.takeScreenshot("coast-vanilla");
            open(test,config,0,new Case("coast",-9887,142.62,-967,25,180,false),false);ready(test);
            test.takeScreenshot("coast-map-initial");stats(test,"coast-initial");test.waitTicks(600);
            test.waitFor(client->nearbyDetailedBakes()==25,1200);
            test.waitTicks(60);
            test.takeScreenshot("coast-map-settled");stats(test,"coast-settled");
            test.runOnClient(client->client.gui.setScreen(null));
        } finally {try{Path out=Path.of("build/reports/spectator-visual/report.txt");Files.createDirectories(out.getParent());Files.write(out,report);
                // The next client GameTest cleans its run directory. Preserve evidence
                // before releasing the process, including failed or partial runs.
                Path archive=Path.of("../../../build/reports/oracle-archives/spectator-visual-"+
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
                Files.createDirectories(archive);Files.copy(out,archive.resolve("report.txt"));
                Path screenshots=Path.of("screenshots");
                if(Files.isDirectory(screenshots))try(var files=Files.list(screenshots)) {
                    for(Path screenshot:files.filter(Files::isRegularFile).toList())
                        Files.copy(screenshot,archive.resolve(screenshot.getFileName()));
                }
            }
            catch(Exception e){throw new RuntimeException(e);}report.forEach(System.out::println);}
    }
    private record Case(String name,double x,double y,double z,double pitch,double yaw,boolean solid) { }
    private void open(ClientGameTestContext test,StandaloneSeedExplorerConfig config,int dimension,Case c,boolean underground) {
        test.runOnClient(client->{var screen=new StandaloneTerrain3DScreen(null,config,1,dimension,List.of()).openAt(c.x,c.z,null,List.of(),0);
            client.gui.setScreen(screen);set(screen,"underground",underground);var cam=(FreeCamera3D)get(screen,"camera");
            cam.position(c.x,c.y,c.z);cam.pitch(Math.toRadians(c.pitch));cam.yaw(Math.toRadians(c.yaw));cam.fov(70);});
    }
    private void ready(ClientGameTestContext test) {
        test.waitFor(client->Terrain3DRenderer.shared().hasDrawableTerrain(),1200);test.waitTicks(160);
        test.runOnClient(client->{var err=(String)get(client.gui.screen(),"renderError");if(!err.isBlank())throw new AssertionError(err);});
    }
    private void stats(ClientGameTestContext test,String name) {
        report.add(test.computeOnClient(client->{var r=Terrain3DRenderer.shared();return name+" inside="+get(r,"cameraInsideSolid")+
            " belowSurface="+get(r,"cameraBelowSurface")+" caveView="+get(r,"gpuCaveView")+" batches="+r.lastGpuBatchesDrawn()+
            " cacheChunks="+((Map<?,?>)get(r,"chunkCache")).size()+" bakedBytes="+get(r,"bakedCacheBytes")+
            " renderNs="+get(r,"lastTerrainRenderNanos")+" uploadBytes="+get(r,"lastGpuUploadBytes")+
            " coastDetailedBakes="+nearbyDetailedBakes();}));
    }
    private static int nearbyDetailedBakes() {
        var baked=(Map<?,?>)get(Terrain3DRenderer.shared(),"bakedCache");int count=0;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {
            var chunk=baked.get(ChunkPos.pack(-618+dx,-61+dz));if(chunk==null)continue;
            if(((List<?>)get(chunk,"surfaceLods")).stream().anyMatch(lod->(int)get(lod,"step")==1))count++;
        }
        return count;
    }
    private static Object get(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
    private static void set(Object o,String n,Object v){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}catch(Exception e){throw new RuntimeException(e);}}
}
