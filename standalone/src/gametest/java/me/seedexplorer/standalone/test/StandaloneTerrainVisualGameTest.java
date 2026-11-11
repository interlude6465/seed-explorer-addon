package me.seedexplorer.standalone.test;

import me.seedexplorer.addon.render.FreeCamera3D;
import me.seedexplorer.addon.preview.StructurePreviewModel;
import me.seedexplorer.addon.preview.StructurePreviewSimulator;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.render.Terrain3DRenderer;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.worldgen.GeneratedTerrainHeightmap;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import me.seedexplorer.standalone.StandaloneAncillaryRuntime;
import me.seedexplorer.standalone.StandaloneSeedExplorerConfig;
import me.seedexplorer.standalone.StandaloneTerrain3DScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import java.nio.file.*;
import java.util.*;

/** Opt-in fixed-camera screenshots plus an independent generated-world occupancy comparison. */
@SuppressWarnings("UnstableApiUsage")
public final class StandaloneTerrainVisualGameTest implements FabricClientGameTest {
    private final List<String> report = new ArrayList<>();
    public void runTest(ClientGameTestContext test) {
        try (TestSingleplayerContext world = test.worldBuilder().setUseConsistentSettings(true)
            .adjustSettings(settings -> {
                var presets=settings.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET);
                settings.setWorldType(new net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.WorldTypeEntry(
                    presets.getOrThrow(WorldPresets.NORMAL)));
                settings.setSeed("1"); settings.setAllowCommands(true); settings.setGenerateStructures(true);
            }).create()) {
            world.getConnection().waitForChunksRender();
            var config=(StandaloneSeedExplorerConfig)get(StandaloneAncillaryRuntime.get(),"config");
            test.runOnClient(client -> {config.setSeed(1); client.options.pauseOnLostFocus=false;client.options.framerateLimit().set(30);});
            if (Boolean.getBoolean("seedexplorer.terrainMovingAudit")) {
                test.runOnClient(client->client.player.connection.sendCommand("gamemode spectator"));
                test.runOnClient(client->client.player.connection.sendCommand("execute in minecraft:overworld run tp @s -9887 141 -967 0 65"));
                test.waitFor(client->client.level.dimension()==Level.OVERWORLD&&client.player.getX()<-9800,1200);
                world.getConnection().waitForChunksRender();
                open(test,config,0,null,-9887,142.62,-967,25,180,false);ready(test);
                runMovingCameraAudit(test, config);
                return;
            }
            report.add("Seed 1. Nether camera -5883.5,33.62,-1429.5, facing east, pitch 10. Map FOV=70.");
            report.add(world.getServer().computeOnServer(server -> {
                var level=server.getLevel(Level.NETHER);
                var predicted=new GeneratedTerrainHeightmap(1,-1,List.of(),false);
                int total=0, occupancy=0, materials=0, actualLava=0, predictedLava=0;
                Map<String,Integer> differences=new TreeMap<>();
                for(int[] offset:new int[][]{{0,0},{-1,0},{1,0},{0,-1},{0,1}}) {
                    int cx=-368+offset[0],cz=-90+offset[1];
                    var actual=level.getChunk(cx,cz);
                    var expected=predicted.getChunk(new ChunkPos(cx,cz));
                    for(int y=0;y<128;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                        var pos=new BlockPos(cx*16+x,y,cz*16+z);
                        var a=actual.getBlockState(pos);var b=expected.getBlockState(pos);total++;
                        if(a.isAir()!=b.isAir()) occupancy++;
                        if(a.is(Blocks.LAVA))actualLava++;if(b.is(Blocks.LAVA))predictedLava++;
                        if(a.getBlock()!=b.getBlock()) {materials++;differences.merge(a.getBlock()+" <- "+b.getBlock(),1,Integer::sum);}
                    }
                }
                return "NETHER_BLOCKS checked="+total+" occupancyMismatch="+occupancy+" materialMismatch="+materials+
                    " liveLava="+actualLava+" predictedLava="+predictedLava+" differences="+differences;
            }));
            test.runOnClient(client -> client.player.connection.sendCommand("gamemode spectator"));
            test.runOnClient(client -> client.player.connection.sendCommand("execute in minecraft:the_nether run tp @s -5883.5 32 -1429.5 -90 10"));
            test.waitFor(client -> client.level.dimension()==Level.NETHER && client.player.getX() < -5800, 1200);
            world.getConnection().waitForChunksRender();test.waitTicks(30);
            test.takeScreenshot("terrain-seed1-nether-vanilla");
            open(test,config,-1,null,-5883.5,33.62,-1429.5,80,-90,true);
            ready(test);
            settledCaves(test);
            test.waitTicks(100);test.takeScreenshot("terrain-seed1-nether-underground");
            stats(test,"Nether underground");
            test.runOnClient(client -> set(client.gui.screen(),"underground",false));
            ready(test);test.takeScreenshot("terrain-seed1-nether-surface");stats(test,"Nether surface");
            test.runOnClient(client -> client.gui.setScreen(null));
            test.runOnClient(client -> client.player.connection.sendCommand("execute in minecraft:overworld run tp @s -5866 140 -1507 144 28"));
            test.waitFor(client -> client.level.dimension()==Level.OVERWORLD && client.player.getY()>130,1200);
            world.getConnection().waitForChunksRender();test.waitTicks(30);
            var temple=VanillaStructurePredictor.predictStructure(1,0,"minecraft:jungle_pyramid",-368,-96,-368,-96).stream()
                .findFirst().orElseThrow(()->new AssertionError("Reported temple missing"));
            open(test,config,0,null,-5866,142,-1507,62,36,false);
            ready(test);test.takeScreenshot("terrain-seed1-temple-unselected");stats(test,"Temple unselected");
            open(test,config,0,temple,-5866,142,-1507,62,36,false);
            ready(test);test.waitFor(client -> ((Map<?,?>)get(Terrain3DRenderer.shared(),"previewCache")).values().stream()
                .anyMatch(value -> value instanceof StructurePreviewModel model && model.blocks().size() > 100),1200);
            report.add(test.computeOnClient(client -> "Temple preview " +
                ((Map<?,?>)get(Terrain3DRenderer.shared(),"previewCache")).values().stream()
                .map(value -> { var model=(StructurePreviewModel)value; return "blocks="+model.blocks().size()+
                    " bounds="+model.minY()+".."+model.maxY(); }).toList()));
            test.waitTicks(20);test.takeScreenshot("terrain-seed1-temple-selected");stats(test,"Temple selected");
            open(test,config,0,null,-5866,142,-1507,62,36,false);
            ready(test);test.takeScreenshot("terrain-seed1-temple-unselected-ready");stats(test,"Temple unselected ready");
            test.runOnClient(client -> client.gui.setScreen(null));
            for (var fixture : List.of(
                new GeneratedStructure(-2800,-3024,-175,-189,StructureType.BASTION,"Bridge",false),
                new GeneratedStructure(-2496,-1920,-156,-120,StructureType.END_CITY,"Ship",true))) {
                var model=StructurePreviewSimulator.preview(fixture,1);
                if(model.blocks().size()<100) throw new AssertionError("Missing preview for "+fixture.type);
                int dimension=fixture.type.dimension;
                double distance=Math.min(180,Math.max(model.maxX()-model.minX(),model.maxZ()-model.minZ())+45);
                double x=model.centerX()+distance*Math.sin(Math.toRadians(36))*Math.sin(Math.toRadians(70));
                double y=model.centerY()+distance*Math.cos(Math.toRadians(70));
                double z=model.centerZ()+distance*Math.cos(Math.toRadians(36))*Math.sin(Math.toRadians(70));
                String label=dimension==-1?"bastion":"endcity";
                open(test,config,dimension,null,x,y,z,70,36,dimension==-1);
                ready(test);if(dimension==-1)settledCaves(test);
                test.takeScreenshot("terrain-seed1-"+label+"-unselected");stats(test,label+" unselected");
                open(test,config,dimension,fixture,x,y,z,70,36,dimension==-1);
                ready(test);if(dimension==-1)settledCaves(test);
                test.waitFor(client -> ((Map<?,?>)get(Terrain3DRenderer.shared(),"previewCache")).values().stream()
                    .anyMatch(value -> value instanceof StructurePreviewModel m && m.blocks().size()>100),1200);
                test.takeScreenshot("terrain-seed1-"+label+"-selected");stats(test,label+" selected");
                report.add(label+" preview blocks="+model.blocks().size()+" boundsY="+model.minY()+".."+model.maxY());
                open(test,config,dimension,null,x,y,z,70,36,dimension==-1);
                ready(test);test.takeScreenshot("terrain-seed1-"+label+"-unselected-ready");stats(test,label+" unselected ready");
                test.runOnClient(client -> client.gui.setScreen(null));
            }
            // A single open renderer survives repeated camera motion for two
            // minutes; retain resource counters and the regular frame log.
            test.runOnClient(client->client.player.connection.sendCommand("execute in minecraft:overworld run tp @s -9887 141 -967 0 65"));
            test.waitFor(client->client.level.dimension()==Level.OVERWORLD&&client.player.getX()<-9800,1200);
            world.getConnection().waitForChunksRender();
            open(test,config,0,null,-9887,142.62,-967,25,180,false);ready(test);
            if (Boolean.getBoolean("seedexplorer.terrainMovingAudit")) {
                runMovingCameraAudit(test, config);
                return;
            }
            for(int sample=0;sample<12;sample++) {
                int phase=sample%4;
                test.runOnClient(client->{var camera=(FreeCamera3D)get(client.gui.screen(),"camera");
                    camera.position(-9887+(phase==1?32:phase==3?-32:0),142.62,-967+(phase==2?32:0));});
                test.waitTicks(200);stats(test,"sustained-coast-"+sample);
                if(sample==0||sample==11)test.takeScreenshot("terrain-coast-sustained-"+sample);
            }
            test.runOnClient(client->client.gui.setScreen(null));
        } catch(Throwable failure) {report.add("FAILURE "+failure);throw new AssertionError(failure);}
        finally {
            try {var file=Path.of("build/reports/nether-3d-visual/report.txt");Files.createDirectories(file.getParent());Files.write(file,report);
                Path archive=Path.of("../../../build/reports/oracle-archives/terrain-visual-"+
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
                Files.createDirectories(archive);Files.copy(file,archive.resolve("report.txt"));
                if(Files.isDirectory(Path.of("screenshots")))try(var files=Files.list(Path.of("screenshots"))) {
                    for(Path screenshot:files.filter(Files::isRegularFile).toList())Files.copy(screenshot,archive.resolve(screenshot.getFileName()));
                }
            }
            catch(Exception e){throw new RuntimeException(e);}
            report.forEach(System.out::println);
        }
    }
    private static void open(ClientGameTestContext test,StandaloneSeedExplorerConfig config,int dimension,
                             GeneratedStructure selected,double x,double y,double z,double pitch,double yaw,boolean cave) {
        test.runOnClient(client -> {
            var screen=new StandaloneTerrain3DScreen(null,config,1,dimension,selected==null?List.of():List.of(selected))
                .openAt(x,z,selected,List.of(),0);
            client.gui.setScreen(screen);set(screen,"underground",cave);
            var camera=(FreeCamera3D)get(screen,"camera");camera.position(x,y,z);camera.pitch(Math.toRadians(pitch));camera.yaw(Math.toRadians(yaw));camera.fov(70);
            // Freeze automatic focus orbit while retaining the production focus rendering branch.
            camera.animateTo(x,y,z,camera.distance(),pitch,yaw,1000000);
        });
    }
    private static void settledCaves(ClientGameTestContext test) {
        test.waitFor(client -> {
                var renderer=Terrain3DRenderer.shared();
                var camera=(FreeCamera3D)get(client.gui.screen(),"camera");
                int cx=(int)Math.floor(camera.eyeX()/16),cz=(int)Math.floor(camera.eyeZ()/16);
                var baked=(Map<?,?>)get(renderer,"bakedCache");
                for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                    var chunk=baked.get(ChunkPos.pack(cx+dx,cz+dz));
                    if(chunk==null || !(boolean)get(chunk,"cavesIncluded"))return false;
                }
                return renderer.lastGpuBatchesDrawn()>0;
            },2400);
        test.waitTicks(60);
    }
    private static void ready(ClientGameTestContext test) {
        test.waitFor(client -> Terrain3DRenderer.shared().hasDrawableTerrain(),2400);
        test.waitTicks(200);
        test.waitFor(client -> Terrain3DRenderer.shared().lastGpuBatchesDrawn()>0,2400);
        test.waitTicks(60);
        test.runOnClient(client -> {String error=(String)get(client.gui.screen(),"renderError");if(!error.isBlank())throw new AssertionError(error);});
    }
    private void stats(ClientGameTestContext test,String label) {
        report.add(test.computeOnClient(client -> {
            var renderer=Terrain3DRenderer.shared();
            String selection=selectedState(client.gui.screen());
            return label+" dimension="+get(client.gui.screen(),"dimension")+
                " selected="+selection+" gpuBatches="+renderer.lastGpuBatchesDrawn()+
                " cached="+((Map<?,?>)get(renderer,"chunkCache")).size()+" insideSolid="+get(renderer,"cameraInsideSolid")+
                " camera="+cameraState(client.gui.screen())+
                " geometry="+dimensionGeometry(renderer,(int)get(client.gui.screen(),"dimension"))+
                " caveBakes="+get(renderer,"caveBakesWithGeometry")+
                " caveVertices="+get(renderer,"caveVerticesBaked")+
                " terrainRenderNs="+get(renderer,"lastTerrainRenderNanos")+
                " gpuUploadNs="+get(renderer,"lastGpuUploadNanos")+
                " gpuUploadBytes="+get(renderer,"lastGpuUploadBytes")+
                " bakedBytes="+get(renderer,"bakedCacheBytes")+
                " bakesInFlight="+((Set<?>)get(renderer,"inFlightBakes")).size()+
                " generationInFlight="+((Set<?>)get(renderer,"inFlightChunks")).size()+
                " heapBytes="+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        }));
    }

    private static String selectedState(Object screen) {
        Object selection=get(screen,"selection");
        Object structure=get(selection,"structure");
        if(structure==null)return "none";
        Object renderer=Terrain3DRenderer.shared();
        long seed=((Number)get(screen,"seed")).longValue();
        int dimension=(int)get(screen,"dimension");
        Object cache=get(renderer,"previewCache");
        int blocks=((Map<?,?>)cache).values().stream()
            .filter(value->value instanceof StructurePreviewModel)
            .map(value->(StructurePreviewModel)value)
            .mapToInt(model->model.blocks().size()).max().orElse(0);
        return "active:previewBlocks="+blocks+":seed="+seed+":dimension="+dimension;
    }

    private static String dimensionGeometry(Terrain3DRenderer renderer,int dimension) {
        long chunks=0,surfaceVertices=0,caveVertices=0;
        for(Object baked:((Map<?,?>)get(renderer,"bakedCache")).values()) {
            if((int)get(baked,"dimension")!=dimension)continue;
            chunks++;
            for(Object lod:(List<?>)get(baked,"surfaceLods")) {
                surfaceVertices+=geometryVertices(get(lod,"solid"))+geometryVertices(get(lod,"fluid"));
            }
            caveVertices+=geometryVertices(get(baked,"caveSolid"));
        }
        return "bakedChunks="+chunks+",surfaceVertices="+surfaceVertices+",caveVertices="+caveVertices;
    }

    private static int geometryVertices(Object geometry) {
        return ((float[])get(geometry,"px")).length;
    }

    /**
     * Opt-in cold/warm stream audit. The game-test tick clock is only used to
     * yield to the client; the wall clock controls completion so a slow client
     * cannot accidentally turn this into a short nominal run. Renderer
     * terrainRenderNs is the map extraction path, while frameIntervalNs is the
     * renderer's observed invocation interval and therefore remains labelled as
     * end-to-end frame timing rather than being conflated with extraction.
     */
    private void runMovingCameraAudit(ClientGameTestContext test,
                                      StandaloneSeedExplorerConfig config) {
        long requestedSeconds = Long.getLong("seedexplorer.terrainMovingAuditSeconds", 120L);
        long durationSeconds = Math.max(120L, requestedSeconds);
        long started = System.nanoTime();
        long deadline = started + durationSeconds * 1_000_000_000L;
        long nextReport = started;
        List<MovingSample> samples = new ArrayList<>();
        int step = 0;
        while (System.nanoTime() < deadline) {
            long now = System.nanoTime();
            double elapsed = (now - started) / 1_000_000_000.0;
            double half = durationSeconds / 2.0;
            boolean warm = elapsed >= half;
            double phase = elapsed * (warm ? 0.17 : 0.11);
            double radius = warm ? 192.0 : 24.0 + 360.0 * Math.min(1.0, elapsed / half);
            double drift = warm ? 0.0 : elapsed * 1.75;
            double x = -9887.0 + drift + radius * Math.cos(phase);
            double z = -967.0 + radius * Math.sin(phase);
            double y = 142.62 + 8.0 * Math.sin(phase * 0.5);
            final double cameraX = x, cameraY = y, cameraZ = z;
            test.runOnClient(client -> {
                var camera = (FreeCamera3D) get(client.gui.screen(), "camera");
                camera.cancelAnimation();
                camera.position(cameraX, cameraY, cameraZ);
                camera.yaw((float) Math.atan2(cameraX + 9887.0, cameraZ + 967.0));
            });
            test.waitTicks(10);
            MovingSample sample = test.computeOnClient(client -> movingSample(
                (Terrain3DRenderer) Terrain3DRenderer.shared(), client.gui.screen(),
                (long) ((System.nanoTime() - started) / 1_000_000L), warm));
            samples.add(sample);
            if (System.nanoTime() >= nextReport) {
                report.add("moving-" + (warm ? "warm" : "cold") + " " + sample);
                nextReport = System.nanoTime() + 5_000_000_000L;
            }
            step++;
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        if (elapsedMillis < durationSeconds * 1000L)
            throw new AssertionError("moving audit ended before wall-clock duration: " + elapsedMillis + "ms");
        if (samples.size() < 20)
            throw new AssertionError("moving audit collected too few client samples: " + samples.size());
        MovingSample last = samples.get(samples.size() - 1);
        report.add("moving-summary durationMs=" + elapsedMillis + " samples=" + samples.size()
            + " steps=" + step + " last=" + last);
        report.add("moving-timing-definition frameIntervalNs=renderer invocation interval;"
            + " terrainRenderNs=terrain extraction; gpuUploadNs=GPU upload processing;"
            + " wallClockMs=test-thread elapsed time");
        test.takeScreenshot("terrain-coast-moving-final");
    }

    private static MovingSample movingSample(Terrain3DRenderer renderer, Object screen,
                                             long elapsedMillis, boolean warm) {
        return new MovingSample(elapsedMillis, warm,
            ((Number) get(renderer, "averageFrameIntervalNanos")).longValue(),
            ((Number) get(renderer, "lastTerrainRenderNanos")).longValue(),
            ((Number) get(renderer, "lastGpuUploadNanos")).longValue(),
            ((Number) get(renderer, "lastGpuUploadBytes")).longValue(),
            ((Number) get(renderer, "lastGpuBatchesQueued")).intValue(),
            ((Set<?>) get(renderer, "inFlightChunks")).size(),
            ((Set<?>) get(renderer, "inFlightBakes")).size(),
            ((java.util.concurrent.ConcurrentLinkedQueue<?>) get(renderer, "pendingGpuUploads")).size(),
            ((Map<?, ?>) get(renderer, "chunkCache")).size(),
            ((Map<?, ?>) get(renderer, "bakedCache")).size(),
            ((Number) get(renderer, "bakedCacheBytes")).longValue(),
            Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            renderer.lastGpuBatchesDrawn(),
            cameraState(screen));
    }

    private record MovingSample(long elapsedMillis, boolean warm, long frameIntervalNs,
                                long terrainRenderNs, long gpuUploadNs, long gpuUploadBytes,
                                int gpuQueue, int generationInFlight, int bakesInFlight,
                                int gpuUploadsWaiting, int cachedChunks, int bakedChunks,
                                long bakedBytes, long heapBytes, int gpuBatches, String camera) { }
    private static String cameraState(Object screen) {
        var c=(FreeCamera3D)get(screen,"camera");
        return c.eyeX()+","+c.eyeY()+","+c.eyeZ()+" pitch="+Math.toDegrees(c.pitch())+" yaw="+Math.toDegrees(c.yaw());
    }
    private static Object get(Object target,String name) {
        try {var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new RuntimeException(e);}
    }
    private static void set(Object target,String name,Object value) {
        try {var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(Exception e){throw new RuntimeException(e);}
    }
}
