package me.seedexplorer.standalone;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import me.seedexplorer.seedcracking.Lcg48;

/** Client packets alone feed the collector; server fixtures are navigation and ground truth only. */
public final class StandaloneSeedCrackingGameTest {
    private StandaloneSeedCrackingGameTest() {}
    private record Fixture(String structure,String set,int x,int z,String family) {}
    private static final Fixture[] FIXTURES={
        new Fixture("desert_pyramid","desert_pyramids",-700,-126,"Desert pyramid"),
        new Fixture("shipwreck_beached","shipwrecks",389,657,"Shipwreck"),
        new Fixture("shipwreck_beached","shipwrecks",475,619,"Shipwreck"),
        new Fixture("shipwreck_beached","shipwrecks",199,-933,"Shipwreck"),
        new Fixture("shipwreck_beached","shipwrecks",303,-924,"Shipwreck"),
        new Fixture("shipwreck","shipwrecks",-502,26,"Shipwreck")
    };
    public static void run(ClientGameTestContext context) {
        try(TestSingleplayerContext world=context.worldBuilder().setUseConsistentSettings(true)
            .adjustSettings(settings -> {
                var presets=settings.getSettings().worldgenLoadContext()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET);
                settings.setWorldType(new net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.WorldTypeEntry(
                    presets.getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL)));
                settings.setGenerateStructures(true);settings.setSeed("1");
            }).create()) {
            world.getConnection().waitForChunksRender();context.waitTicks(8);
            long expectedSeed=world.getServer().computeOnServer(server->server.overworld().getSeed());
            require(expectedSeed==1L,"live fixture did not retain requested seed: "+expectedSeed);
            context.runOnClient(client -> {
                client.getWindow().setTitle("Seed Explorer - automatic cracking workflow test");
                var runtime=StandaloneSeedCrackingRuntime.current();
                require(runtime!=null,"automatic runtime was not initialized");
                require(StandaloneSeedCrackingRuntime.loadedChunk(client.level,123456,123456)==null,
                    "Unloaded client chunk was accepted as observed evidence");
                runtime.enabled(true);runtime.workspace(client);runtime.clear();
                config(runtime).setSeed(-987654321L); // Deliberately wrong map seed: not solver input.
            });
            for(Fixture fixture:FIXTURES) {
                // Independent archived fixtures; no predicted structures or low seeds enter the workspace.
                world.getServer().computeOnServer(server -> {
                    var registry=server.overworld().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
                    var chunk=server.overworld().getChunk(fixture.x,fixture.z,
                        net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS);
                    var id=net.minecraft.resources.Identifier.withDefaultNamespace(fixture.structure);
                    require(chunk.getAllStarts().entrySet().stream().anyMatch(entry -> id.equals(registry.getKey(entry.getKey()))
                        && entry.getValue()!=null && entry.getValue().isValid()),"Invalid independent fixture: "+fixture);
                    var player=server.getPlayerList().getPlayers().getFirst();
                    player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                    player.teleportTo(fixture.x*16+8,100,fixture.z*16+8);return null;
                });
                context.waitFor(client -> {
                    var runtime=StandaloneSeedCrackingRuntime.current();
                    return runtime.workspace(client).contains(StandaloneSeedCrackingWorkspace.Kind.STRUCTURE,
                        fixture.set+" "+fixture.x+" "+fixture.z);
                },1_200);
                context.runOnClient(client -> {
                    var runtime=StandaloneSeedCrackingRuntime.current();
                    var workspace=runtime.workspace(client);
                    require(StandaloneSeedCrackingRuntime.visibleOutlines(client).stream()
                        .anyMatch(outline -> outline.family().equals(fixture.family)
                            && Math.abs(outline.minX()-fixture.x*16)<32 && Math.abs(outline.minZ()-fixture.z*16)<32),
                        "Recognized fixture did not create a blue outline: "+fixture);
                    require(me.seedexplorer.seedcracking.SeedCracking.verifyStructures(expectedSeed&Lcg48.MASK,
                        workspace.parse(workspace.evidence()).structures()),"Observed start disagrees with vanilla seed: "+fixture);
                    System.out.println("Live cracking observed fixture: "+fixture+"; "+runtime.status());
                });
            }
            context.takeScreenshot("seed-explorer-live-observed-shipwreck-blue-outline");
            context.runOnClient(client -> {
                var workspace=StandaloneSeedCrackingRuntime.current().workspace(client);
                for(var observation:workspace.evidence()) {
                    for(var filter:workspace.parse(java.util.List.of(observation)).filters())
                        require(filter.matches(expectedSeed),"Client-collected filter rejects vanilla ground truth: "+observation);
                }
            });
            long[] nextLog={0};long searchDeadline=System.nanoTime()+180_000_000_000L;
            try {
                context.waitFor(client -> {
                    var runtime=StandaloneSeedCrackingRuntime.current();var workspace=runtime.workspace(client);
                    var result=workspace.outcome();
                    require(System.nanoTime()<searchDeadline,"Live automatic search exceeded 180 second wall-clock budget");
                    if(System.nanoTime()>=nextLog[0]) {
                        nextLog[0]=System.nanoTime()+10_000_000_000L;
                        System.out.println("Live cracking search: "+runtime.status()+"; "+workspace.status()
                            +"; progress="+workspace.progress()+"; outcome="+result);
                    }
                    return result!=null && result.complete() && result.worldSeeds().contains(expectedSeed);
                },3_600);
            } catch(Throwable failure) {
                context.runOnClient(client -> System.out.println(StandaloneSeedCrackingRuntime.current().workspace(client).report()));
                throw failure;
            }
            context.runOnClient(client -> {
                var runtime=StandaloneSeedCrackingRuntime.current();var workspace=runtime.workspace(client);
                require(workspace.count(StandaloneSeedCrackingWorkspace.Kind.STRUCTURE)>=6,"Live starts were lost");
                require(workspace.automaticLowSeeds(),"Live pipeline did not automatically hand off low seeds");
                require(workspace.outcome().worldSeeds().equals(java.util.List.of(expectedSeed)),
                    "Live pipeline did not uniquely recover fixture seed within searched candidates: "+workspace.report());
                require(config(runtime).seed()==-987654321L,"Automatic pipeline changed configured map seed");
                require(runtime.searches()>=2,"Live pipeline skipped a search stage");
                System.out.println(workspace.report());
                runtime.enabled(false);
                require(StandaloneSeedCrackingRuntime.visibleOutlines(client).isEmpty(),"Disabling kept observed outlines visible");
                runtime.enabled(true);
            });
            System.out.println("Automatic cracking live flow: PASS (six client-recognized starts -> low48 -> unique full candidate, independent map seed, explicit application, observed outlines)");
        }
    }
    private static StandaloneSeedExplorerConfig config(StandaloneSeedCrackingRuntime runtime) {
        try {
            var field=StandaloneSeedCrackingRuntime.class.getDeclaredField("config");field.setAccessible(true);
            return (StandaloneSeedExplorerConfig)field.get(runtime);
        } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
