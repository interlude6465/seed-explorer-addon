package me.seedexplorer.standalone.test;

import com.mojang.blaze3d.platform.InputConstants;
import me.seedexplorer.standalone.StandaloneAncillaryRuntime;
import me.seedexplorer.standalone.StandaloneSeedExplorerConfig;
import me.seedexplorer.standalone.StandaloneSeedExplorerScreen;
import me.seedexplorer.standalone.StandaloneSeedExplorerSettingsScreen;
import me.seedexplorer.standalone.StandaloneSearchScreen;
import me.seedexplorer.standalone.StandaloneSearch;
import me.seedexplorer.standalone.StandaloneTerrain3DScreen;
import me.seedexplorer.standalone.StandaloneWaypoint;
import me.seedexplorer.addon.render.FreeCamera3D;
import me.seedexplorer.addon.render.Terrain3DRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@SuppressWarnings("UnstableApiUsage")
public final class StandaloneClientSmokeTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if(Boolean.getBoolean("seedexplorer.searchWorkflowAudit")) {
            me.seedexplorer.standalone.StandaloneSearchWorkflowGameTest.run(context);return;
        }
        if(Boolean.getBoolean("seedexplorer.runSeedCrackingAudit")) {
            me.seedexplorer.standalone.StandaloneSeedCrackingGameTest.run(context);return;
        }
        if (Boolean.getBoolean("seedexplorer.runPredictionOracle")) return;
        context.runOnClient(client -> client.getWindow().setTitle(
            "Seed Explorer - automated interface test (closes when finished)"));
        try (TestSingleplayerContext world = context.worldBuilder()
            .setUseConsistentSettings(true)
            .adjustSettings(settings -> {
                var presets = settings.getSettings().worldgenLoadContext().lookupOrThrow(
                    net.minecraft.core.registries.Registries.WORLD_PRESET);
                settings.setWorldType(new net.minecraft.client.gui.screens.worldselection
                    .WorldCreationUiState.WorldTypeEntry(
                        presets.getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL)));
                settings.setGenerateStructures(true);
                settings.setAllowCommands(true);
            }).create()) {
            world.getConnection().waitForChunksRender();
            context.waitTicks(5);
            context.runOnClient(client -> {
                client.getWindow().setTitle(
                    "Seed Explorer - automated interface test (closes when finished)");
                require(client.player != null && client.level != null, "temporary world did not load");
                require(me.seedexplorer.standalone.StandaloneClientChunks.loaded(
                    client.level, client.player.blockPosition()), "player chunk was not loaded");
                require(!me.seedexplorer.standalone.StandaloneClientChunks.loaded(client.level,
                    new net.minecraft.core.BlockPos(29_000_000, 64, 29_000_000)),
                    "unloaded client chunk was accepted as observed terrain");
                verifyMixins();
                require(StandaloneAncillaryRuntime.get() != null,
                    "standalone client entrypoint did not initialize its runtime");
            });

            var config = (StandaloneSeedExplorerConfig) field(StandaloneAncillaryRuntime.get(), "config");
            if (Boolean.getBoolean("seedexplorer.mapFollowupAudit")) {
                exerciseMapFollowup(context, config);
                return;
            }
            long seed = world.getServer().computeOnServer(server -> server.overworld().getSeed());
            config.setSeed(seed);
            config.setPredictionVersion("26.3");
            config.setMapDimension(0);
            config.setMapStructureFilters(0, java.util.Set.of());
            var mapScreen = new java.util.concurrent.atomic.AtomicReference<Screen>();
            context.runOnClient(client -> {
                Screen map = new StandaloneSeedExplorerScreen(null, config);
                mapScreen.set(map);
                client.gui.setScreen(map);
                require(client.gui.screen() == map, "2D map screen did not open");
            });
            context.waitFor(client -> mapFinished(
                (StandaloneSeedExplorerScreen) mapScreen.get()), 1200);
            context.runOnClient(client -> requireMapReady(
                (StandaloneSeedExplorerScreen) mapScreen.get()));
            context.waitFor(client -> mapStructuresReady(
                (StandaloneSeedExplorerScreen) mapScreen.get()), 2400);
            context.takeScreenshot("seed-explorer-standalone-map-2d");

            InteractionState interaction = exercise2DInteractions(
                context, config, seed, (StandaloneSeedExplorerScreen) mapScreen.get());
            context.waitFor(client -> Terrain3DRenderer.shared().hasDrawableTerrain()
                || !renderError((StandaloneTerrain3DScreen) client.gui.screen()).isBlank(), 2400);
            context.waitTicks(100);
            context.runOnClient(client -> {
                require(client.gui.screen() instanceof StandaloneTerrain3DScreen,
                    "3D screen was forced closed after opening");
                StandaloneTerrain3DScreen terrain3d = (StandaloneTerrain3DScreen) client.gui.screen();
                require(renderError(terrain3d).isBlank(),
                    "3D renderer reported: " + renderError(terrain3d));
                require(Terrain3DRenderer.shared().hasDrawableTerrain(),
                    "3D renderer did not draw a persistent terrain batch");
            });
            context.takeScreenshot("seed-explorer-standalone-map-3d");
            exercise3DInteractions(context);
            context.runOnClient(client -> {
                client.gui.screen().onClose();
                require(client.gui.screen() == mapScreen.get(),
                    "3D back navigation did not restore the same 2D map");

                Screen terrain3d = new StandaloneTerrain3DScreen(mapScreen.get(), config, seed, 0,
                    java.util.List.of()).openAt(client.player.getX(), client.player.getZ(),
                        null, java.util.List.of(), 0);
                client.gui.setScreen(terrain3d);
                require(client.gui.screen() == terrain3d,
                    "forest-spawn 3D verification screen did not open");
            });
            context.waitTicks(100);
            context.runOnClient(client -> {
                StandaloneTerrain3DScreen terrain3d = require3DScreen(client.gui.screen());
                require(renderError(terrain3d).isBlank(),
                    "forest-spawn 3D renderer reported: " + renderError(terrain3d));
                require(Terrain3DRenderer.shared().hasDrawableTerrain(),
                    "forest-spawn 3D renderer did not retain drawable terrain");
            });
            context.takeScreenshot("seed-explorer-standalone-map-3d-foliage");
            exerciseChestSidebar(context, world, config, seed, mapScreen.get());
            context.runOnClient(client -> {
                client.gui.screen().onClose();
                require(client.gui.screen() == mapScreen.get(),
                    "forest-spawn 3D back navigation did not restore the map");
            });
            context.waitTicks(2);
            deleteTestWaypoint(context, config, seed,
                (StandaloneSeedExplorerScreen) mapScreen.get(), interaction);
            exerciseMapMenus(context, config, (StandaloneSeedExplorerScreen) mapScreen.get());
            exerciseSearchScreen(context, config, seed, mapScreen.get());

            context.runOnClient(client -> {
                Screen map = mapScreen.get();
                Screen settings = new StandaloneSeedExplorerSettingsScreen(map, config);
                client.gui.setScreen(settings);
                require(client.gui.screen() == settings, "settings screen did not open");
            });
            context.waitTicks(3);
            context.takeScreenshot("seed-explorer-standalone-settings");
            context.runOnClient(client -> {
                client.gui.screen().onClose();
                require(client.gui.screen() == mapScreen.get(),
                    "settings back navigation did not restore the map");
            });
            exerciseSeedCracking(context, config, seed, mapScreen.get());
            exerciseChestVerification(context, world, seed);
            exerciseCrossDimensionLoot(context, config);
            exerciseHighlightWorldChanges(context, config, seed);
        } catch (Throwable failure) {
            throw new AssertionError("Standalone client smoke failed", failure);
        }
        context.runOnClient(client -> require(client.level == null,
            "temporary world did not close cleanly"));
        context.runOnClient(client -> require(
            me.seedexplorer.standalone.StandaloneChestHighlightRenderer.visibleChests().isEmpty(),
            "chest highlights survived leaving the world"));
    }

    private static void exerciseHighlightWorldChanges(ClientGameTestContext context,
                                                       StandaloneSeedExplorerConfig config, long seed) {
        var origin = context.computeOnClient(client -> client.player.blockPosition());
        var prediction = new me.seedexplorer.addon.worldgen.PredictionContext(seed, "26.3", 0);
        var chests = List.of(new me.seedexplorer.addon.loot.ChestLootOutput(origin.getX(), origin.getY(),
            origin.getZ(), "minecraft:chests/simple_dungeon", List.of(), 1L, false));
        context.runOnClient(client -> {
            client.gui.setScreen(null);
            require(me.seedexplorer.standalone.StandaloneChestHighlightRenderer.toggle(config, prediction, chests),
                "could not arm dimension-change highlight check");
            client.player.connection.sendCommand("execute in minecraft:the_nether run tp @s 0 130 0");
        });
        context.waitFor(client -> client.level != null && client.level.dimension()
            == net.minecraft.world.level.Level.NETHER, 600);
        context.waitTicks(3);
        context.runOnClient(client -> {
            require(me.seedexplorer.standalone.StandaloneChestHighlightRenderer.visibleChests().isEmpty()
                && !me.seedexplorer.standalone.StandaloneChestHighlightRenderer.selected(prediction, chests),
                "old world chest highlights survived a dimension switch");
            client.player.connection.sendCommand("execute in minecraft:overworld run tp @s "
                + origin.getX() + " " + origin.getY() + " " + origin.getZ());
        });
        context.waitFor(client -> client.level != null && client.level.dimension()
            == net.minecraft.world.level.Level.OVERWORLD, 600);
        context.waitTicks(3);
        context.runOnClient(client -> {
            require(!me.seedexplorer.standalone.StandaloneChestHighlightRenderer.selected(prediction, chests),
                "old chest selection reappeared on returning to the dimension");
            require(me.seedexplorer.standalone.StandaloneChestHighlightRenderer.toggle(config, prediction, chests),
                "could not arm world-disconnect highlight check");
        });
    }

    private static void exerciseMapFollowup(ClientGameTestContext context, StandaloneSeedExplorerConfig config) {
        var maps = new java.util.concurrent.atomic.AtomicReference<StandaloneSeedExplorerScreen>();
        context.runOnClient(client -> {
            client.options.framerateLimit().set(30);
            config.setSeed(1); config.setPredictionVersion("26.3"); config.setMapDimension(-1);
            config.setMapStructureFilters(-1, java.util.Set.of());
            config.setInt("search-mode", 0, 0, 3);
            config.setBoolean("auto-center-player", false);
            var map = new StandaloneSeedExplorerScreen(null, config);
            setField(map, "centerX", -7714.0); setField(map, "centerZ", -234.0);
            setField(map, "dimension", -1); setField(map, "initialPositionSet", true);
            maps.set(map); client.gui.setScreen(map);
        });
        context.waitFor(client -> mapStructuresReady(maps.get()), 3600);
        var map = maps.get();
        context.waitTicks(20);
        context.runOnClient(client -> {
            Object snapshot = field(map, "result");
            int predicted = ((List<?>) field(snapshot, "structures")).size();
            int icons = intField(map, "lastDrawnMarkers");
            require(predicted > 500 && icons < predicted / 3, "dense Nether map did not cluster fossils");
            System.out.println("MAP_FOLLOWUP predicted=" + predicted + " drawnIcons=" + icons
                + " biomeDraws=" + intField(map, "lastBiomeDrawCalls"));
        });
        var durations = new java.util.ArrayList<Long>();
        for (int i = 0; i < 30; i++) {
            context.waitTick();
            durations.add(context.computeOnClient(client -> (Long) field(map, "lastMapDrawNanos")));
        }
        durations.sort(Long::compare);
        System.out.println("MAP_FOLLOWUP map-extraction CPU p50_ms=" + durations.get(15) / 1_000_000.0
            + " p95_ms=" + durations.get(28) / 1_000_000.0);
        context.takeScreenshot("map-followup-nether-clusters");
        exerciseTouchpadZoom(context, config, map,
            new GuiPoint(map.width * .55, intField(map, "mapTop") + 40));
        exerciseSustainedMap(context, map);

        GuiPoint start = new GuiPoint(map.width * 0.65, Math.max(intField(map, "mapTop") + 25, map.height * 0.6));
        RawPoint raw = context.computeOnClient(client -> rawPoint(client, start));
        RawPoint delta = context.computeOnClient(client -> rawDelta(client, 140, 0));
        long previous = context.computeOnClient(client -> (Long) field(map, "activeRequest"));
        context.getInput().setCursorPos(raw.x, raw.y); context.waitTick();
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT); context.waitTick();
        context.getInput().moveCursor(delta.x, delta.y);
        context.waitFor(client -> (Long) field(map, "activeRequest") > previous, 200);
        context.waitFor(client -> !((Boolean) field(map, "biomeRequestPending")), 600);
        context.runOnClient(client -> {
            require((Boolean) field(map, "dragging"), "drag was released before prefetch verification");
            Object snapshot = field(map, "result");
            double scale = (Double) field(snapshot, "step") / 8;
            double dx = Math.abs((Double) field(snapshot, "centerX") - (Double) field(map, "centerX")) / scale;
            require(dx + (map.width - 20) / 2.0 <= intField(snapshot, "columns") * 4.0,
                "preloaded raster did not cover the held-drag viewport");
            double center = (Double) field(snapshot, "centerX");
            double halfWidth = intField(snapshot, "columns") * scale * 4;
            int margin = config.intValue("generation-margin", 10, 0, 48);
            int minChunk = Math.floorDiv((int) Math.floor(center - halfWidth), 16) - margin;
            int maxChunk = Math.floorDiv((int) Math.ceil(center + halfWidth), 16) + margin;
            var structures = (List<me.seedexplorer.addon.structures.GeneratedStructure>) field(snapshot, "structures");
            require(structures.stream().allMatch(structure -> structure.startChunkX >= minChunk
                && structure.startChunkX <= maxChunk), "held-drag retained predictions outside the buffered window");
        });
        context.takeScreenshot("map-followup-held-drag");
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT); context.waitTicks(2);
        clickFieldButton(context, "structureFilterButton");
        clickMenuAction(context, map, "[ ] Bastion Remnant");
        clickMenuAction(context, map, "[ ] Nether Fortress");
        context.runOnClient(client -> map.keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_ESCAPE, 0, 0)));
        clickNativeButton(context, "Open 3D");
        context.waitTicks(3);
        context.runOnClient(client -> client.gui.screen().onClose());
        context.runOnClient(client -> {
            require(((java.util.Set<?>) field(map, "selectedStructureFilters")).size() == 2,
                "2D/3D round trip reset filters");
            map.onClose();
            var reopened = new StandaloneSeedExplorerScreen(null, config);
            client.gui.setScreen(reopened);
            require(intField(reopened, "dimension") == -1
                && ((java.util.Set<?>) field(reopened, "selectedStructureFilters")).size() == 2,
                "reopening reset map menus");
            client.gui.setScreen(new StandaloneSearchScreen(reopened, config,
                new me.seedexplorer.addon.worldgen.PredictionContext(1, "26.3", -1), -7714, -234, result -> {}));
            ((net.minecraft.client.gui.components.EditBox) field(client.gui.screen(), "query")).setValue("golden apple");
        });
        context.waitTicks(3);
        context.runOnClient(client -> {
            var suggestions = (List<me.seedexplorer.addon.loot.LootSearchTerm>) field(client.gui.screen(), "suggestions");
            require(suggestions.stream().anyMatch(term -> term.itemId().equals("minecraft:golden_apple")),
                "Auto search has no golden-apple suggestion");
        });
        context.takeScreenshot("map-followup-item-suggestions");
        context.runOnClient(client -> invokeCracking(client.gui.screen(), "start", new Class<?>[0]));
        context.waitTicks(10);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            require(intField(screen, "mode") == 3, "plain item name entered biome search");
            StandaloneSearch.Job job = (StandaloneSearch.Job) field(screen, "job");
            require(job != null && !job.status.startsWith("Search failed"), "item search failed: " + (job == null ? "no job" : job.status));
            job.cancel(); screen.onClose();
        });
        System.out.println("MAP_FOLLOWUP PASS: dense clusters, held-drag preload, saved menus, Auto item suggestions and dispatch");
    }

    private static void exerciseSustainedMap(ClientGameTestContext context, StandaloneSeedExplorerScreen map) {
        context.runOnClient(client -> {
            setField(map, "blocksPerPixel", 32.0);
            invokeCracking(map, "requestMap", new Class<?>[0]);
        });
        var first = new java.util.ArrayList<Long>();
        var last = new java.util.ArrayList<Long>();
        long started = System.nanoTime();
        int maximumPredictions = 0;
        for (int second = 0; second < 120; second++) {
            context.waitTicks(20);
            long duration = context.computeOnClient(client -> (Long) field(map, "lastMapDrawNanos"));
            if (second < 30) first.add(duration);
            if (second >= 90) last.add(duration);
            int count = context.computeOnClient(client -> ((List<?>) field(field(map, "result"), "structures")).size());
            maximumPredictions = Math.max(maximumPredictions, count);
            if (second % 30 == 29) context.runOnClient(client -> {
                int[] stats = (int[]) invokeStatic("me.seedexplorer.standalone.StandalonePredictionCache", "memoryStats");
                require(stats[0] <= 256 && stats[1] <= 100_000, "prediction cache exceeded its memory bounds");
                long heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                System.out.println("MAP_SUSTAINED predicted=" + ((List<?>) field(field(map, "result"), "structures")).size()
                    + " regions=" + intField(map, "structureRegionsDone") + "/" + intField(map, "structureRegionsTotal")
                    + " cacheRegions=" + stats[0] + " cacheMarkers=" + stats[1] + " heapMiB=" + heap / 1048576
                    + " drawn=" + intField(map, "lastDrawnMarkers"));
            });
        }
        first.sort(Long::compare); last.sort(Long::compare);
        double firstP95 = first.get(28) / 1_000_000.0, lastP95 = last.get(28) / 1_000_000.0;
        require(lastP95 < 15 && lastP95 < Math.max(5, firstP95 * 3), "sustained 2D extraction regressed: " + lastP95);
        System.out.println("MAP_SUSTAINED seconds=" + (System.nanoTime() - started) / 1_000_000_000.0
            + " maxPredictions=" + maximumPredictions + " first_p95_ms=" + firstP95 + " last_p95_ms=" + lastP95);
        context.takeScreenshot("map-sustained-dense-nether");
        context.runOnClient(client -> {
            setField(map, "blocksPerPixel", 8.0);
            invokeCracking(map, "requestMap", new Class<?>[0]);
        });
        context.waitFor(client -> !((Boolean) field(map, "biomeRequestPending")), 600);
    }

    private static Object invokeStatic(String type, String method) {
        try {
            var target = Class.forName(type).getDeclaredMethod(method);
            target.setAccessible(true);
            return target.invoke(null);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void exerciseMapMenus(ClientGameTestContext context,
                                         StandaloneSeedExplorerConfig config,
                                         StandaloneSeedExplorerScreen map) {
        clickFieldButton(context, "structureFilterButton");
        clickMenuAction(context, map, "[ ] Mineshaft");
        clickMenuAction(context, map, "[ ] Buried Treasure");
        context.runOnClient(client -> {
            var filters = (java.util.Set<?>) field(map, "selectedStructureFilters");
            require(filters.contains(me.seedexplorer.addon.structures.StructureType.MINESHAFT)
                && filters.contains(me.seedexplorer.addon.structures.StructureType.TREASURE),
                "structure dropdown did not retain multiple selections");
            require(intField(map, "contextScrollOffset") > 0, "structure toggle lost menu scroll");
        });
        context.takeScreenshot("seed-explorer-structure-dropdown");
        context.runOnClient(client -> map.keyPressed(new net.minecraft.client.input.KeyEvent(
            InputConstants.KEY_ESCAPE, 0, 0)));
        click(context, new GuiPoint(map.width / 2.0, map.height / 2.0), InputConstants.MOUSE_BUTTON_RIGHT);
        context.runOnClient(client -> require(intField(map, "contextScrollOffset") == 0
            && !contextLabels(map).isEmpty(), "map context menu inherited dropdown scroll"));
        context.runOnClient(client -> map.keyPressed(new net.minecraft.client.input.KeyEvent(
            InputConstants.KEY_ESCAPE, 0, 0)));
        clickFieldButton(context, "structureFilterButton");
        clickMenuAction(context, map, "Reset structures");
        context.runOnClient(client -> require(((java.util.Set<?>) field(map, "selectedStructureFilters")).isEmpty(),
            "structure reset failed"));
        clickFieldButton(context, "versionButton");
        clickMenuAction(context, map, "[ ] 1.19.4");
        context.runOnClient(client -> require(config.predictionVersion().equals("1.19.4"), "version selection failed"));
        clickFieldButton(context, "versionButton");
        clickMenuAction(context, map, "Reset version");
        context.runOnClient(client -> require(config.predictionVersion().equals("26.3"), "version reset failed"));
        clickFieldButton(context, "dimensionButton");
        clickMenuAction(context, map, "[ ] Nether");
        context.runOnClient(client -> require(intField(map, "dimension") == -1, "dimension selection failed"));
        clickFieldButton(context, "dimensionButton");
        clickMenuAction(context, map, "Reset dimension");
        context.runOnClient(client -> require(intField(map, "dimension") == 0, "dimension reset failed"));
    }

    private static void clickFieldButton(ClientGameTestContext context, String fieldName) {
        GuiPoint point = context.computeOnClient(client -> {
            var button = (net.minecraft.client.gui.components.Button) field(client.gui.screen(), fieldName);
            require(button.visible && button.active, "button unavailable: " + fieldName);
            return new GuiPoint(button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2);
        });
        click(context, point, InputConstants.MOUSE_BUTTON_LEFT);
    }

    private static void exerciseSearchScreen(ClientGameTestContext context,
                                             StandaloneSeedExplorerConfig config,
                                             long seed, Screen parent) {
        long searchSeed = Long.MIN_VALUE;
        int originalWaypoints = config.waypoints(searchSeed, 0).size();
        context.runOnClient(client -> {
            config.setSeed(searchSeed);
            config.setInt("search-mode", 0, 0, 3);
            client.gui.setScreen(new StandaloneSearchScreen(parent, config,
                new me.seedexplorer.addon.worldgen.PredictionContext(searchSeed, "26.3", 0), -5356, 6389,
                result -> { }));
        });
        clickNativeButton(context, "Auto search");
        clickNativeButton(context, "Biome");
        clickNativeButton(context, "Coordinates");
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            require(screen instanceof StandaloneSearchScreen, "item search screen did not open");
            ((net.minecraft.client.gui.components.EditBox) field(screen, "query"))
                .setValue("Unbreaking III book");
            screen.tick();
            var suggestions = (List<?>) field(screen, "suggestions");
            require(suggestions.size() == 1
                && ((me.seedexplorer.addon.loot.LootSearchTerm) suggestions.getFirst()).level() == 3,
                "book suggestion lost the enchantment level");
            ((net.minecraft.client.gui.components.EditBox) field(screen, "quantity")).setValue("2");
            screen.resize(screen.width, screen.height);
            require(((net.minecraft.client.gui.components.EditBox) field(screen, "query"))
                .getValue().equals("Unbreaking III book")
                && ((net.minecraft.client.gui.components.EditBox) field(screen, "quantity"))
                    .getValue().equals("2"), "search resize discarded item or quantity");
        });
        clickNativeButton(context, "Add item");
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            require(((List<?>) field(screen, "requests")).size() == 1, "search item was not queued");
            ((net.minecraft.client.gui.components.EditBox) field(screen, "query"))
                .setValue("enchanted golden apple");
        });
        clickNativeButton(context, "Add item");
        clickNativeButton(context, "Remove item");
        context.runOnClient(client -> require(((List<?>) field(client.gui.screen(), "requests")).size() == 1,
            "search item removal failed"));
        clickNativeButton(context, "Edit item");
        context.runOnClient(client -> require(((List<?>) field(client.gui.screen(), "requests")).isEmpty(),
            "edited item was not removed from the queue"));
        clickNativeButton(context, "Add item");
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            require(((List<?>) field(screen, "requests")).size() == 1, "edited item was not restored");
        });
        clickNativeButton(context, "Remove item");
        context.runOnClient(client -> {
            ((net.minecraft.client.gui.components.EditBox) field(client.gui.screen(), "query"))
                .setValue("poisonous potato");
            ((net.minecraft.client.gui.components.EditBox) field(client.gui.screen(), "quantity")).setValue("9");
        });
        clickNativeButton(context, "Add item");
        clickNativeButton(context, "All dimensions");
        clickNativeButton(context, "Search");
        context.waitFor(client -> field(client.gui.screen(), "job") instanceof StandaloneSearch.Job job
            && job.done, 2400);
        context.waitTicks(2);
        context.runOnClient(client -> {
            var job = (StandaloneSearch.Job) field(client.gui.screen(), "job");
            require(job.status.startsWith("Predicted request") && job.results.size() == 2,
                "real multi-wreck UI search did not fulfill the request: " + job.status);
            require(job.results.get(0).x() == -5356 && job.results.get(0).z() == 6389
                && job.results.get(1).x() == -5285 && job.results.get(1).z() == 6543,
                "UI search differs from the two independently archived shipwreck inventories");
            require(((List<?>) field(client.gui.screen(), "shown")).size() == 2,
                "completed search did not publish both contributing chests to the screen");
        });
        context.takeScreenshot("seed-explorer-item-search");
        clickNativeButton(context, "Pin");
        context.runOnClient(client -> require(config.waypoints(searchSeed, 0).size() == originalWaypoints + 1,
            "search result waypoint was not saved"));
        clickNativeButton(context, "3D");
        context.runOnClient(client -> {
            require(client.gui.screen() instanceof StandaloneTerrain3DScreen,
                "search result did not open in 3D");
            client.gui.screen().onClose();
            require(client.gui.screen() instanceof StandaloneSearchScreen,
                "3D back did not restore search results");
        });
        clickNativeButton(context, "Done");
        context.runOnClient(client -> {
            require(client.gui.screen() == parent, "search Done did not restore the map");
            var added = config.waypoints(searchSeed, 0).stream()
                .filter(waypoint -> waypoint.x() == -5356 && waypoint.z() == 6389).findFirst().orElseThrow();
            config.removeWaypoint(added.id());
            config.setSeed(seed);
            config.save();
        });
    }

    private static void exerciseSeedCracking(ClientGameTestContext context,
                                             StandaloneSeedExplorerConfig config,
                                             long actualSeed, Screen parent) {
        context.runOnClient(client -> {
            config.setBoolean("seed-cracking-auto",true);
            client.gui.setScreen(new StandaloneSeedExplorerSettingsScreen(parent,config));
        });
        clickNativeButton(context, ">");
        context.takeScreenshot("seed-explorer-cracking-settings");
        clickNativeButton(context, "Open workspace");
        context.runOnClient(client -> require(client.gui.screen() instanceof me.seedexplorer.standalone.StandaloneSeedCrackingScreen,
            "Cracking settings did not open the workspace"));
        clickNativeButton(context,"Pause background cracking");
        clickNativeButton(context,"Observations");
        context.runOnClient(client -> {
            Screen screen=client.gui.screen();
            ((net.minecraft.client.gui.components.EditBox)field(screen,"entry")).setValue("shipwrecks 1 2");
            screen.resize(screen.width,screen.height);
            require(((net.minecraft.client.gui.components.EditBox)field(screen,"entry")).getValue().equals("shipwrecks 1 2"),
                "Cracking resize discarded unfinished evidence input");
        });
        clickNativeButton(context, "Structure >");
        clickNativeButton(context, "Capture loaded");
        clickNativeButton(context, "Bedrock >");
        clickNativeButton(context, "Capture loaded");
        context.takeScreenshot("seed-explorer-cracking-observations");
        context.runOnClient(client -> {
            Object workspace=field(client.gui.screen(),"workspace");
            List<?> evidence=(List<?>) field(workspace,"evidence");
            require(evidence.size()>=37,"Loaded bedrock and biome evidence was not captured");
            Object parsed=invokeCracking(workspace,"parse",new Class<?>[]{List.class},evidence);
            @SuppressWarnings("unchecked") var filters=(List<me.seedexplorer.seedcracking.WorldSeedFilter>)field(parsed,"filters");
            require(filters.size()>=2 && filters.stream().allMatch(filter -> filter.matches(actualSeed)),
                "Cracking adapters disagree with independently generated bedrock / noise biome");
        });
        clickNativeButton(context,"Search and candidates");
        context.runOnClient(client -> {
            Screen screen=client.gui.screen();
            String input=Long.toString(actualSeed & me.seedexplorer.seedcracking.Lcg48.MASK);
            ((net.minecraft.client.gui.components.EditBox)field(screen,"lowSeeds")).setValue(input);
            screen.resize(screen.width,screen.height);
            require(((net.minecraft.client.gui.components.EditBox)field(screen,"lowSeeds")).getValue().equals(input),
                "Cracking resize discarded low-48 candidate input");
        });
        clickNativeButton(context,"Recover upper bits");
        context.waitFor(client -> !(boolean)field(field(client.gui.screen(),"workspace"),"running"),1200);
        context.runOnClient(client -> {
            Object outcome=field(field(client.gui.screen(),"workspace"),"outcome");
            require(outcome!=null && (boolean)field(outcome,"complete"),"Cracking upper-bit search did not finish");
            require(((List<?>)field(outcome,"worldSeeds")).contains(actualSeed),"Cracking removed the independent true seed");
            require(config.seed()==actualSeed,"Search silently replaced the configured seed");
        });
        context.takeScreenshot("seed-explorer-cracking-candidates");
        // Start and cancel synchronously so completion cannot race the click.
        context.runOnClient(client -> {
            Object screen=client.gui.screen(),workspace=field(screen,"workspace");
            invokeCracking(workspace,"start",new Class<?>[]{boolean.class,int.class,int.class},true,10,1);
            invokeCracking(workspace,"cancel",new Class<?>[]{});
            require(!(boolean)field(workspace,"running") && field(workspace,"outcome")==null,
                "Cancelled cracking work remained visible");
            config.setPredictionVersion("26.2");
            client.gui.screen().tick();
            require(!((net.minecraft.client.gui.components.Button)field(screen,"useButton")).active,
                "Cracking candidates remained applicable after a version switch");
            config.setPredictionVersion("26.3");
            client.gui.screen().onClose();
            require(client.gui.screen() instanceof StandaloneSeedExplorerSettingsScreen,"Cracking Back lost its settings parent");
            client.gui.screen().onClose();
            require(client.gui.screen()==parent,"Cracking settings Back lost the map parent");
        });
    }

    private static Object invokeCracking(Object target,String name,Class<?>[] signature,Object... args) {
        try {
            var method=target.getClass().getDeclaredMethod(name,signature);method.setAccessible(true);return method.invoke(target,args);
        } catch(ReflectiveOperationException exception) { throw new AssertionError("Cracking test access failed: "+name,exception); }
    }

    private static void clickNativeButton(ClientGameTestContext context,String label) {
        GuiPoint point=context.computeOnClient(client -> {
            var matches=client.gui.screen().children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.Button)
                .map(child -> (net.minecraft.client.gui.components.Button)child)
                .filter(button -> button.getMessage().getString().equals(label)).toList();
            require(matches.size()==1 && matches.getFirst().active,"Missing or inactive native button: "+label);
            var button=matches.getFirst();return new GuiPoint(button.getX()+button.getWidth()/2,button.getY()+button.getHeight()/2);
        });
        click(context,point,InputConstants.MOUSE_BUTTON_LEFT);
    }

    private static InteractionState exercise2DInteractions(ClientGameTestContext context,
                                                            StandaloneSeedExplorerConfig config,
                                                            long seed,
                                                            StandaloneSeedExplorerScreen map) {
        boolean structureLayer = config.booleanValue("structure-layer", true);
        boolean waypointLayer = config.booleanValue("waypoint-layer", true);
        boolean autoCenter = config.booleanValue("auto-center-player", false);
        int baselineWaypoints = config.waypoints(seed, 0).size();
        config.setBoolean("structure-layer", false);
        config.setBoolean("waypoint-layer", false);
        config.setBoolean("auto-center-player", false);

        double beforeX = doubleField(map, "centerX");
        double beforeZ = doubleField(map, "centerZ");
        GuiPoint dragStart = context.computeOnClient(client -> new GuiPoint(
            map.width / 2, mapTop(map) + (map.height - mapTop(map) - 10) / 2));
        exerciseTouchpadZoom(context, config, map, dragStart);
        context.waitFor(client -> mapStructuresReady(map), 2400);
        require(!((List<?>) field(field(map, "result"), "structures")).isEmpty(),
            "marker retention test requires known structures");
        drag(context, dragStart, 54, 36, InputConstants.MOUSE_BUTTON_LEFT);
        for (int tick = 0; tick < 12; tick++) {
            context.runOnClient(client -> require(!((List<?>) field(field(map, "result"), "structures")).isEmpty(),
                "panning cleared previously known structure markers"));
            context.waitTick();
        }
        context.runOnClient(client -> {
            double afterX = doubleField(map, "centerX");
            double afterZ = doubleField(map, "centerZ");
            require(Math.abs(afterX - beforeX) > 0.01 || Math.abs(afterZ - beforeZ) > 0.01,
                "2D left drag did not change the map center");
            require(config.waypoints(seed, 0).size() == baselineWaypoints,
                "2D drag created a waypoint");
        });

        config.setBoolean("waypoint-layer", true);
        GuiPoint emptyPoint = context.computeOnClient(client -> emptyMapPoint(map, config, seed));
        click(context, emptyPoint, InputConstants.MOUSE_BUTTON_RIGHT);
        context.runOnClient(client -> {
            List<String> labels = contextLabels(map);
            require(labels.contains("Add waypoint"), "empty-map menu did not offer Add waypoint");
            require(labels.contains("View in 3D"), "empty-map menu did not offer View in 3D");
        });
        clickMenuAction(context, map, "Add waypoint");

        StandaloneWaypoint waypoint = context.computeOnClient(client -> {
            require(config.waypoints(seed, 0).size() == baselineWaypoints + 1,
                "Add waypoint did not persist exactly one waypoint");
            StandaloneWaypoint selected = (StandaloneWaypoint) field(map, "selectedWaypoint");
            require(selected != null, "Add waypoint did not select the new waypoint");
            return selected;
        });
        GuiPoint waypointPoint = context.computeOnClient(client -> waypointPoint(map, waypoint));
        click(context, waypointPoint, InputConstants.MOUSE_BUTTON_RIGHT);
        context.runOnClient(client -> require(contextLabels(map).contains("View in 3D"),
            "waypoint menu did not offer View in 3D"));
        clickMenuAction(context, map, "View in 3D");
        context.runOnClient(client -> require(client.gui.screen() instanceof StandaloneTerrain3DScreen,
            "waypoint View in 3D did not open the 3D screen"));
        return new InteractionState(baselineWaypoints, waypoint, structureLayer, waypointLayer, autoCenter);
    }

    private static void exerciseTouchpadZoom(ClientGameTestContext context,
                                             StandaloneSeedExplorerConfig config,
                                             StandaloneSeedExplorerScreen map,
                                             GuiPoint point) {
        RawPoint raw = context.computeOnClient(client -> rawPoint(client, point));
        context.getInput().setCursorPos(raw.x, raw.y);
        context.waitTick();
        double zoomStep = config.doubleValue("zoom-step", 1.2, 1.01, 2.0);
        double beforeFraction = doubleField(map, "blocksPerPixel");
        context.getInput().scroll(0.0, 0.05);
        context.waitTick();
        double afterFraction = doubleField(map, "blocksPerPixel");
        double fractionalNotches = -Math.log(afterFraction / beforeFraction) / Math.log(zoomStep);
        require(Math.abs(fractionalNotches - 0.05) < 0.002,
            "fractional touchpad scroll was treated as a full wheel notch");

        double beforeBurst = afterFraction;
        context.runOnClient(client -> {
            map.mouseScrolled(point.x, point.y, 0, 2.5);
            double expected = beforeBurst * Math.pow(zoomStep, -2.5);
            require(Math.abs(doubleField(map, "blocksPerPixel") - expected) < 1.0e-8,
                "wheel event was not applied immediately");
            // A second, opposite fractional input must also take effect in this call.
            map.mouseScrolled(point.x, point.y, 0, -0.5);
            require(Math.abs(doubleField(map, "blocksPerPixel") - beforeBurst * Math.pow(zoomStep, -2)) < 1.0e-8,
                "opposite fractional wheel movement was queued");
        });
        double afterBurst = doubleField(map, "blocksPerPixel");
        context.waitTicks(15);
        require(Math.abs(doubleField(map, "blocksPerPixel") - afterBurst) < 1.0e-9,
            "zoom continued after scrolling stopped");
    }

    private static void exerciseCrossDimensionLoot(ClientGameTestContext context,
                                                   StandaloneSeedExplorerConfig config) throws Exception {
        verifyLootQueueDelivery("Standalone3DSelectionController");
        verifyLootQueueDelivery("StandaloneStructureDetailScreen");
        long previousSeed = config.seed();
        String previousVersion = config.predictionVersion();
        var controllerClass = Class.forName("me.seedexplorer.standalone.Standalone3DSelectionController");
        var constructor = controllerClass.getDeclaredConstructor(long.class, int.class,
            StandaloneSeedExplorerConfig.class);
        constructor.setAccessible(true);
        var select = controllerClass.getDeclaredMethod("select",
            me.seedexplorer.addon.structures.GeneratedStructure.class);
        select.setAccessible(true);
        context.runOnClient(client -> {
            config.setSeed(1L);
            config.setPredictionVersion("26.3");
            require(client.level.dimension() == net.minecraft.world.level.Level.OVERWORLD,
                "cross-dimension test requires the player to stay in the Overworld");
        });
        // Reproduce the user's actual bastion and End City selections, without
        // teleporting the player or pre-populating the controller with results.
        var fixtures = List.of(
            new me.seedexplorer.addon.structures.GeneratedStructure(-2800, -3024, -175, -189,
                me.seedexplorer.addon.structures.StructureType.BASTION, "Bridge", false),
            new me.seedexplorer.addon.structures.GeneratedStructure(-2496, -1920, -156, -120,
                me.seedexplorer.addon.structures.StructureType.END_CITY, "Ship", true));
        try {
            for (var fixture : fixtures) {
                Object controller = constructor.newInstance(1L, fixture.type.dimension, config);
                context.runOnClient(client -> {
                    try { select.invoke(controller, fixture); }
                    catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                });
                context.waitFor(client -> !booleanField(controller, "loading"), 2400);
                context.runOnClient(client -> {
                    require(((String) field(controller, "error")).isBlank(),
                        "cross-dimension prediction failed: " + field(controller, "error"));
                    require(!((List<?>) field(controller, "chests")).isEmpty(),
                        "cross-dimension prediction did not publish containers for " + fixture.type);
                });
            }
            context.runOnClient(client -> {
                try {
                    var guardClass = Class.forName("me.seedexplorer.standalone.StandaloneLootRequestGuard");
                    var capture = guardClass.getDeclaredMethod("capture",
                        me.seedexplorer.addon.worldgen.PredictionContext.class);
                    var isCurrent = guardClass.getDeclaredMethod("isCurrent");
                    capture.setAccessible(true);
                    isCurrent.setAccessible(true);
                    Object guard = capture.invoke(null,
                        new me.seedexplorer.addon.worldgen.PredictionContext(1L, "26.3", 1));
                    require((boolean) isCurrent.invoke(guard), "other map dimension rejected a current session");
                    StandaloneAncillaryRuntime.get().resetSession();
                    require(!(boolean) isCurrent.invoke(guard), "session reset accepted stale prediction publication");
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
        } finally {
            context.runOnClient(client -> {
                config.setSeed(previousSeed);
                config.setPredictionVersion(previousVersion);
            });
        }
    }

    /** A busy worker must not silently lose another screen's pending result. */
    private static void verifyLootQueueDelivery(String className) throws Exception {
        var executorField = Class.forName("me.seedexplorer.standalone." + className)
            .getDeclaredField("LOOT_EXECUTOR");
        executorField.setAccessible(true);
        var executor = (java.util.concurrent.ThreadPoolExecutor) executorField.get(null);
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var delivered = new java.util.concurrent.CountDownLatch(4);
        executor.execute(() -> {
            started.countDown();
            try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try {
            require(started.await(10, java.util.concurrent.TimeUnit.SECONDS),
                className + " worker did not start");
            for (int i = 0; i < 4; i++) executor.execute(delivered::countDown);
        } finally {
            release.countDown();
        }
        require(delivered.await(10, java.util.concurrent.TimeUnit.SECONDS),
            className + " silently dropped queued work, leaving a loading UI without a result");
    }

    private static void exerciseChestVerification(ClientGameTestContext context,
                                                   TestSingleplayerContext world, long seed) {
        var position = context.computeOnClient(client -> client.player.blockPosition().offset(2, 0, 0));
        world.getServer().computeOnServer(server -> {
            var level = server.overworld();
            level.setBlock(position, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
            var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(position);
            chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 3));
            return true;
        });
        for (boolean matching : new boolean[] { true, false }) {
            context.runOnClient(client -> {
                client.gui.setScreen(null);
                var item = new me.seedexplorer.addon.loot.ItemLoot("minecraft:gold_ingot", matching ? 3 : 4,
                    matching ? 3 : 4, 1, "Gold Ingot", false, null, 0, null);
                var chest = new me.seedexplorer.addon.loot.ChestLootOutput(position.getX(), position.getY(), position.getZ(),
                    "minecraft:chests/village/village_weaponsmith", List.of(item), 1L, false);
                var overlay = me.seedexplorer.standalone.StandalonePredictedChestOverlay.get();
                overlay.remember(new me.seedexplorer.addon.worldgen.PredictionContext(seed, "26.3", 0), List.of(chest));
                overlay.bind(position);
            });
            world.getServer().computeOnServer(server -> {
                var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) server.overworld().getBlockEntity(position);
                server.getPlayerList().getPlayers().getFirst().openMenu(chest);
                return true;
            });
            context.waitFor(client -> field(me.seedexplorer.standalone.StandalonePredictedChestOverlay.get(),
                "comparison") != null, 200);
            context.runOnClient(client -> {
                var comparison = field(me.seedexplorer.standalone.StandalonePredictedChestOverlay.get(), "comparison");
                require(field(comparison, "status").toString().equals(matching ? "MATCH" : "MISMATCH"),
                    "server inventory packet produced incorrect loot verification status");
                if (!matching) require(java.nio.file.Files.isRegularFile(client.gameDirectory.toPath()
                    .resolve("seed-explorer-reports/loot-mismatches.log")), "loot mismatch diagnostic was not saved");
                client.player.closeContainer();
            });
            context.waitTicks(2);
        }
    }

    private static void exerciseChestSidebar(ClientGameTestContext context, TestSingleplayerContext world,
                                              StandaloneSeedExplorerConfig config, long seed, Screen parent) {
        var target = context.computeOnClient(client -> client.player.blockPosition().offset(2, 1, 2));
        context.runOnClient(client -> {
            var structure = new me.seedexplorer.addon.structures.GeneratedStructure(target.getX(), target.getZ(),
                me.seedexplorer.addon.structures.StructureType.VILLAGE);
            var items = List.of(new me.seedexplorer.addon.loot.ItemLoot("minecraft:gold_ingot", 3, 3, 1,
                "Gold Ingot", false, null, 0, null));
            var chest = new me.seedexplorer.addon.loot.ChestLootOutput(target.getX(), target.getY(), target.getZ(),
                "minecraft:chests/village/village_weaponsmith", items, 1L, false);
            client.gui.setScreen(new StandaloneTerrain3DScreen(parent, config, seed, 0, List.of(structure))
                .openAt(target.getX(), target.getZ(), structure, List.of(chest), 0));
        });
        context.waitTicks(3);
        GuiPoint teleport = context.computeOnClient(client -> {
            var screen = require3DScreen(client.gui.screen());
            var button = (net.minecraft.client.gui.components.Button) field(screen, "chestTeleportButton");
            require(button.visible && button.active, "selected chest TP button unavailable");
            var details = (net.minecraft.client.gui.components.Button) field(screen, "chestDetailsButton");
            require(details.visible && details.getY() + details.getHeight() < screen.height,
                "selected chest loot button is hidden or clipped at the test GUI scale");
            require(button.getWidth() < screen.width / 3, "selected chest sidebar covers too much of viewport");
            return new GuiPoint(button.getX() + button.getWidth() / 2, button.getY() + 9);
        });
        context.takeScreenshot("seed-explorer-standalone-chest-sidebar");
        world.getServer().computeOnServer(server -> {
            server.overworld().setBlock(target, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
            return true;
        });
        context.waitFor(client -> client.level.getBlockEntity(target)
            instanceof net.minecraft.world.level.block.entity.ChestBlockEntity, 200);
        context.takeScreenshot("seed-explorer-standalone-chest-location-confirmed");
        GuiPoint highlight = context.computeOnClient(client -> {
            var button = (net.minecraft.client.gui.components.Button) field(client.gui.screen(), "highlightChestsButton");
            require(button.visible && button.active, "chest highlight button unavailable");
            return new GuiPoint(button.getX() + button.getWidth() / 2, button.getY() + 9);
        });
        click(context, highlight, InputConstants.MOUSE_BUTTON_LEFT);
        Screen inspector = context.computeOnClient(client -> {
            Screen screen = client.gui.screen();
            require((boolean) field(screen, "highlightChests"), "nearby chest highlight did not activate");
            client.gui.setScreen(null);
            client.player.setYRot(-45);
            client.player.setXRot(0);
            return screen;
        });
        context.waitTicks(5);
        context.takeScreenshot("seed-explorer-world-chest-highlight");
        context.runOnClient(client -> require(
            me.seedexplorer.standalone.StandaloneChestHighlightRenderer.visibleChests().size() == 1,
            "nearby chest was absent from the world renderer"));
        world.getServer().computeOnServer(server -> {
            server.getPlayerList().getPlayers().getFirst().teleportTo(
                target.getX() + 301.5, target.getY() + 0.5, target.getZ() + 0.5);
            return true;
        });
        context.waitFor(client -> client.player.getX() > target.getX() + 300, 200);
        context.runOnClient(client -> require(
            me.seedexplorer.standalone.StandaloneChestHighlightRenderer.visibleChests().isEmpty(),
            "chest outline remained visible after moving beyond 299 blocks"));
        world.getServer().computeOnServer(server -> {
            server.getPlayerList().getPlayers().getFirst().teleportTo(
                target.getX() + 2.5, target.getY() + 0.5, target.getZ() + 2.5);
            return true;
        });
        context.waitFor(client -> Math.abs(client.player.getX() - target.getX()) < 4, 200);
        context.runOnClient(client -> require(
            me.seedexplorer.standalone.StandaloneChestHighlightRenderer.visibleChests().size() == 1,
            "chest outline did not return on moving back within range"));
        context.runOnClient(client -> client.gui.setScreen(inspector));
        click(context, highlight, InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> require(!(boolean) field(client.gui.screen(), "highlightChests"),
            "chest highlights did not toggle off"));
        click(context, teleport, InputConstants.MOUSE_BUTTON_LEFT);
        context.waitFor(client -> client.player != null
            && Math.abs(client.player.getX() - target.getX()) < 1.0
            && Math.abs(client.player.getZ() - target.getZ()) < 1.0, 200);
        context.runOnClient(client -> {
            var screen = require3DScreen(client.gui.screen());
            require(contextLabels(screen).isEmpty(), "chest teleport click opened a terrain context menu");
            require(field(field(screen, "selection"), "chest") != null,
                "chest teleport click cleared selection");
        });
    }

    private static void exercise3DInteractions(ClientGameTestContext context) {
        StandaloneWaypoint selectionBeforeMenu = context.computeOnClient(client ->
            (StandaloneWaypoint) field(require3DScreen(client.gui.screen()), "selectedWaypoint"));
        GuiPoint contextPoint = context.computeOnClient(client -> {
            Screen screen = client.gui.screen();
            return new GuiPoint(screen.width / 4.0, Math.max(110, screen.height * 0.75));
        });
        click(context, contextPoint, InputConstants.MOUSE_BUTTON_RIGHT);
        context.runOnClient(client -> {
            StandaloneTerrain3DScreen screen = require3DScreen(client.gui.screen());
            require(contextLabels(screen).contains("Add waypoint"),
                "3D empty-space right click did not offer Add waypoint");
            require(intField(screen, "dragButton") == -1,
                "3D right click left drag state armed");
            setField(screen, "selectedWaypoint", selectionBeforeMenu);
        });
        GuiPoint outsideMenu = context.computeOnClient(client -> {
            Screen screen = client.gui.screen();
            return new GuiPoint(screen.width - 24.0, screen.height - 24.0);
        });
        click(context, outsideMenu, InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> {
            StandaloneTerrain3DScreen screen = require3DScreen(client.gui.screen());
            require(contextLabels(screen).isEmpty(), "3D outside click did not close the context menu");
            require(field(screen, "selectedWaypoint") == selectionBeforeMenu,
                "3D context-menu outside click changed the terrain selection");
        });

        CameraState beforeMove = context.computeOnClient(client -> {
            StandaloneTerrain3DScreen screen = require3DScreen(client.gui.screen());
            setBooleanField(screen, "focusActive", true);
            FreeCamera3D camera = camera(screen);
            return CameraState.of(camera);
        });
        context.getInput().holdKey(InputConstants.KEY_W);
        context.waitTicks(6);
        context.runOnClient(client -> {
            StandaloneTerrain3DScreen screen = require3DScreen(client.gui.screen());
            require(!booleanField(screen, "focusActive"), "3D WASD did not clear focus");
            @SuppressWarnings("unchecked")
            java.util.Set<Integer> held = (java.util.Set<Integer>) field(screen, "heldMovementKeys");
            require(held.contains(InputConstants.KEYCODE_W),
                "physical W was not translated to its SDL logical keycode");
            require(beforeMove.moved(camera(screen)), "3D WASD did not move the camera");
        });
        context.getInput().releaseKey(InputConstants.KEY_W);
        context.waitTicks(2);

        CameraState beforeLook = context.computeOnClient(client -> {
            StandaloneTerrain3DScreen screen = require3DScreen(client.gui.screen());
            setBooleanField(screen, "focusActive", true);
            return CameraState.of(camera(screen));
        });
        GuiPoint lookStart = context.computeOnClient(client -> {
            Screen screen = client.gui.screen();
            return new GuiPoint(screen.width / 2, Math.max(110, screen.height / 2));
        });
        drag(context, lookStart, 72, 38, InputConstants.MOUSE_BUTTON_RIGHT);
        context.runOnClient(client -> {
            StandaloneTerrain3DScreen screen = require3DScreen(client.gui.screen());
            FreeCamera3D camera = camera(screen);
            require(!booleanField(screen, "focusActive"), "3D right drag did not clear focus");
            require(beforeLook.rotated(camera), "3D right drag did not rotate the camera");
            require(contextLabels(screen).isEmpty(),
                "3D right drag incorrectly opened the context menu");
            require(intField(screen, "dragButton") == -1,
                "3D right drag did not release the drag state");
        });
    }

    private static void deleteTestWaypoint(ClientGameTestContext context,
                                           StandaloneSeedExplorerConfig config,
                                           long seed,
                                           StandaloneSeedExplorerScreen map,
                                           InteractionState interaction) {
        GuiPoint point = context.computeOnClient(client -> waypointPoint(map, interaction.waypoint));
        click(context, point, InputConstants.MOUSE_BUTTON_RIGHT);
        context.runOnClient(client -> require(contextLabels(map).contains("Delete waypoint"),
            "waypoint menu did not offer Delete waypoint"));
        clickMenuAction(context, map, "Delete waypoint");
        context.runOnClient(client -> require(
            config.waypoints(seed, 0).size() == interaction.baselineWaypoints,
            "Delete waypoint did not restore the original waypoint count"));
        config.setBoolean("structure-layer", interaction.structureLayer);
        config.setBoolean("waypoint-layer", interaction.waypointLayer);
        config.setBoolean("auto-center-player", interaction.autoCenter);
    }

    private static void clickMenuAction(ClientGameTestContext context,
                                        StandaloneSeedExplorerScreen screen,
                                        String label) {
        GuiPoint point = context.computeOnClient(client -> {
            List<String> labels = contextLabels(screen);
            int row = labels.indexOf(label);
            require(row >= 0, "context menu action was missing: " + label);
            int visibleRows = Math.min(labels.size(), Math.max(1, Math.min(12, (screen.height - 4) / 22)));
            for (int attempt = 0; attempt < labels.size(); attempt++) {
                int offset = intField(screen, "contextScrollOffset");
                if (row >= offset && row < offset + visibleRows) break;
                screen.mouseScrolled(intField(screen, "contextX") + 6,
                    intField(screen, "contextY") + 6, 0, row < offset ? 1 : -1);
            }
            row -= intField(screen, "contextScrollOffset");
            return new GuiPoint(intField(screen, "contextX") + 6,
                intField(screen, "contextY") + row * 22 + 11);
        });
        click(context, point, InputConstants.MOUSE_BUTTON_LEFT);
    }

    private static void click(ClientGameTestContext context, GuiPoint point, int button) {
        RawPoint raw = context.computeOnClient(client -> rawPoint(client, point));
        context.getInput().setCursorPos(raw.x, raw.y);
        context.waitTick();
        context.getInput().pressMouse(button);
        context.waitTick();
    }

    private static void drag(ClientGameTestContext context, GuiPoint start,
                             int deltaX, int deltaY, int button) {
        RawPoint raw = context.computeOnClient(client -> rawPoint(client, start));
        RawPoint delta = context.computeOnClient(client -> rawDelta(client, deltaX, deltaY));
        context.getInput().setCursorPos(raw.x, raw.y);
        context.waitTick();
        context.getInput().holdMouse(button);
        context.waitTick();
        context.getInput().moveCursor(delta.x, delta.y);
        context.waitTicks(2);
        context.getInput().releaseMouse(button);
        context.waitTicks(2);
    }

    private static GuiPoint emptyMapPoint(StandaloneSeedExplorerScreen screen,
                                          StandaloneSeedExplorerConfig config,
                                          long seed) {
        int top = mapTop(screen);
        double centerX = doubleField(screen, "centerX");
        double centerZ = doubleField(screen, "centerZ");
        double scale = doubleField(screen, "blocksPerPixel");
        int centerScreenX = screen.width / 2;
        int centerScreenY = top + (screen.height - top - 10) / 2;
        for (int dy : new int[]{0, 44, -44, 88, -88}) {
            for (int dx : new int[]{96, -96, 144, -144, 0}) {
                int x = centerScreenX + dx;
                int y = centerScreenY + dy;
                if (x < 20 || x >= screen.width - 20 || y < top + 12 || y >= screen.height - 20) continue;
                boolean clear = true;
                for (StandaloneWaypoint waypoint : config.waypoints(seed, 0)) {
                    double waypointX = centerScreenX + (waypoint.x() - centerX) / scale;
                    double waypointY = centerScreenY + (waypoint.z() - centerZ) / scale;
                    if (Math.hypot(x - waypointX, y - waypointY) < 20.0) {
                        clear = false;
                        break;
                    }
                }
                if (clear) return new GuiPoint(x, y);
            }
        }
        throw new AssertionError("Could not find empty map space for waypoint interaction");
    }

    private static GuiPoint waypointPoint(StandaloneSeedExplorerScreen screen,
                                          StandaloneWaypoint waypoint) {
        int top = mapTop(screen);
        double centerX = doubleField(screen, "centerX");
        double centerZ = doubleField(screen, "centerZ");
        double scale = doubleField(screen, "blocksPerPixel");
        return new GuiPoint((int) Math.round(screen.width / 2.0 + (waypoint.x() - centerX) / scale),
            (int) Math.round(top + (screen.height - top - 10) / 2.0
                + (waypoint.z() - centerZ) / scale));
    }

    private static List<String> contextLabels(Object screen) {
        @SuppressWarnings("unchecked")
        List<Object> actions = (List<Object>) field(screen, "contextActions");
        return actions.stream().map(action -> {
            try {
                var label = action.getClass().getDeclaredMethod("label");
                label.setAccessible(true);
                return (String) label.invoke(action);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Could not inspect a context menu action", exception);
            }
        }).toList();
    }

    private static StandaloneTerrain3DScreen require3DScreen(Screen screen) {
        require(screen instanceof StandaloneTerrain3DScreen, "3D screen closed during input test");
        return (StandaloneTerrain3DScreen) screen;
    }

    private static FreeCamera3D camera(StandaloneTerrain3DScreen screen) {
        return (FreeCamera3D) field(screen, "camera");
    }

    private static int mapTop(StandaloneSeedExplorerScreen screen) {
        try {
            var method = StandaloneSeedExplorerScreen.class.getDeclaredMethod("mapTop");
            method.setAccessible(true);
            return (int) method.invoke(screen);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inspect the map viewport", exception);
        }
    }

    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inspect field " + name, exception);
        }
    }

    private static double doubleField(Object target, String name) {
        return ((Number) field(target, name)).doubleValue();
    }

    private static int intField(Object target, String name) {
        return ((Number) field(target, name)).intValue();
    }

    private static boolean booleanField(Object target, String name) {
        return (Boolean) field(target, name);
    }

    private static void setBooleanField(Object target, String name, boolean value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not set field " + name, exception);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not set field " + name, exception);
        }
    }

    private static RawPoint rawPoint(net.minecraft.client.Minecraft client, GuiPoint point) {
        return new RawPoint(point.x * client.getWindow().getWidth()
            / (double) client.getWindow().getGuiScaledWidth(),
            point.y * client.getWindow().getHeight()
                / (double) client.getWindow().getGuiScaledHeight());
    }

    private static RawPoint rawDelta(net.minecraft.client.Minecraft client, double x, double y) {
        return new RawPoint(x * client.getWindow().getWidth()
            / (double) client.getWindow().getGuiScaledWidth(),
            y * client.getWindow().getHeight()
                / (double) client.getWindow().getGuiScaledHeight());
    }

    private record GuiPoint(double x, double y) { }
    private record RawPoint(double x, double y) { }
    private record InteractionState(int baselineWaypoints, StandaloneWaypoint waypoint,
                                    boolean structureLayer, boolean waypointLayer,
                                    boolean autoCenter) { }
    private record CameraState(double x, double y, double z, double yaw, double pitch) {
        static CameraState of(FreeCamera3D camera) {
            return new CameraState(camera.targetX(), camera.targetY(), camera.targetZ(),
                camera.yaw(), camera.pitch());
        }

        boolean moved(FreeCamera3D camera) {
            return Math.abs(camera.targetX() - x) + Math.abs(camera.targetY() - y)
                + Math.abs(camera.targetZ() - z) > 0.001;
        }

        boolean rotated(FreeCamera3D camera) {
            return Math.abs(camera.yaw() - yaw) + Math.abs(camera.pitch() - pitch) > 0.0001;
        }
    }

    private static void verifyMixins() {
        Map<String, String[]> expectedMethods = Map.of(
            "net.minecraft.client.gui.screens.ChatScreen",
                new String[]{"seedExplorer$command"},
            "net.minecraft.client.multiplayer.ClientPacketListener",
                new String[]{"seedExplorer$command"},
            "net.minecraft.client.multiplayer.MultiPlayerGameMode",
                new String[]{"seedExplorer$blockContainer", "seedExplorer$entityContainer",
                    "seedExplorer$drop", "seedExplorer$creativeDrop"},
            "net.minecraft.world.inventory.AbstractContainerMenu",
                new String[]{"seedExplorer$seedUpdate"},
            "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
                new String[]{"seedExplorer$overlay", "seedExplorer$click", "seedExplorer$scroll"},
            "net.minecraft.client.gui.screens.inventory.EnchantmentScreen",
                new String[]{"seedExplorer$request", "seedExplorer$layout",
                    "seedExplorer$hitbox", "seedExplorer$background"},
            "net.minecraft.client.renderer.LevelRenderer",
                new String[]{"seedexplorer$submitOreEsp"}
        );
        expectedMethods.forEach((target, expected) -> {
            try {
                Class<?> targetClass = Class.forName(target, true,
                    StandaloneClientSmokeTest.class.getClassLoader());
                var transformedMethods = Arrays.stream(targetClass.getDeclaredMethods())
                    .map(method -> method.getName())
                    .collect(java.util.stream.Collectors.toSet());
                for (String method : expected) {
                    require(transformedMethods.stream().anyMatch(name -> name.equals(method)
                            || name.endsWith("$" + method) || name.contains(method)),
                        "expected injected method " + method + " was not transformed on " + target);
                }
            } catch (ClassNotFoundException exception) {
                throw new AssertionError("Mixin target class did not load: " + target, exception);
            }
        });
    }

    private static String renderError(StandaloneTerrain3DScreen screen) {
        try {
            var field = StandaloneTerrain3DScreen.class.getDeclaredField("renderError");
            field.setAccessible(true);
            return (String) field.get(screen);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inspect the 3D renderer state", exception);
        }
    }

    private static void requireMapReady(StandaloneSeedExplorerScreen screen) {
        try {
            var result = StandaloneSeedExplorerScreen.class.getDeclaredField("result");
            var error = StandaloneSeedExplorerScreen.class.getDeclaredField("error");
            result.setAccessible(true);
            error.setAccessible(true);
            String message = (String) error.get(screen);
            require(message.isBlank(), "2D map generation reported: " + message);
            require(result.get(screen) != null, "2D map generation did not finish");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inspect the 2D map state", exception);
        }
    }

    private static boolean mapFinished(StandaloneSeedExplorerScreen screen) {
        try {
            var result = StandaloneSeedExplorerScreen.class.getDeclaredField("result");
            var error = StandaloneSeedExplorerScreen.class.getDeclaredField("error");
            result.setAccessible(true);
            error.setAccessible(true);
            return result.get(screen) != null || !((String) error.get(screen)).isBlank();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inspect the 2D map state", exception);
        }
    }

    private static boolean mapStructuresReady(StandaloneSeedExplorerScreen screen) {
        try {
            var result = StandaloneSeedExplorerScreen.class.getDeclaredField("result");
            result.setAccessible(true);
            Object snapshot = result.get(screen);
            if (snapshot == null) return false;
            var structuresReady = snapshot.getClass().getDeclaredField("structuresReady");
            structuresReady.setAccessible(true);
            return structuresReady.getBoolean(snapshot);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inspect the 2D structure state", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
