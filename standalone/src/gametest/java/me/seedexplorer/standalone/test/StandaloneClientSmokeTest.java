package me.seedexplorer.standalone.test;

import com.mojang.blaze3d.platform.InputConstants;
import me.seedexplorer.standalone.StandaloneAncillaryRuntime;
import me.seedexplorer.standalone.StandaloneSeedExplorerConfig;
import me.seedexplorer.standalone.StandaloneSeedExplorerScreen;
import me.seedexplorer.standalone.StandaloneSeedExplorerSettingsScreen;
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
        try (TestSingleplayerContext world = context.worldBuilder()
            .setUseConsistentSettings(true)
            .adjustSettings(settings -> {
                var presets = settings.getSettings().worldgenLoadContext().lookupOrThrow(
                    net.minecraft.core.registries.Registries.WORLD_PRESET);
                settings.setWorldType(new net.minecraft.client.gui.screens.worldselection
                    .WorldCreationUiState.WorldTypeEntry(
                        presets.getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL)));
                settings.setGenerateStructures(true);
            }).create()) {
            world.getConnection().waitForChunksRender();
            context.waitTicks(5);
            context.runOnClient(client -> {
                require(client.player != null && client.level != null, "temporary world did not load");
                verifyMixins();
                require(StandaloneAncillaryRuntime.get() != null,
                    "standalone client entrypoint did not initialize its runtime");
            });

            var config = StandaloneSeedExplorerConfig.load();
            long seed = world.getServer().computeOnServer(server -> server.overworld().getSeed());
            config.setSeed(seed);
            var mapScreen = new java.util.concurrent.atomic.AtomicReference<Screen>();
            context.runOnClient(client -> {
                Screen map = new StandaloneSeedExplorerScreen(null, config);
                mapScreen.set(map);
                client.gui.setScreen(map);
                require(client.gui.screen() == map, "2D map screen did not open");
            });
            context.waitFor(client -> mapFinished(
                (StandaloneSeedExplorerScreen) mapScreen.get()), 60_000);
            context.runOnClient(client -> requireMapReady(
                (StandaloneSeedExplorerScreen) mapScreen.get()));
            context.waitFor(client -> mapStructuresReady(
                (StandaloneSeedExplorerScreen) mapScreen.get()), 120_000);
            context.takeScreenshot("seed-explorer-standalone-map-2d");

            InteractionState interaction = exercise2DInteractions(
                context, config, seed, (StandaloneSeedExplorerScreen) mapScreen.get());
            context.waitFor(client -> Terrain3DRenderer.shared().hasDrawableTerrain()
                || !renderError((StandaloneTerrain3DScreen) client.gui.screen()).isBlank(), 120_000);
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
            context.runOnClient(client -> {
                client.gui.screen().onClose();
                require(client.gui.screen() == mapScreen.get(),
                    "forest-spawn 3D back navigation did not restore the map");
            });
            context.waitTicks(2);
            deleteTestWaypoint(context, config, seed,
                (StandaloneSeedExplorerScreen) mapScreen.get(), interaction);

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
        } catch (Throwable failure) {
            throw new AssertionError("Standalone client smoke failed", failure);
        }
        context.runOnClient(client -> require(client.level == null,
            "temporary world did not close cleanly"));
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
        drag(context, dragStart, 54, 36, InputConstants.MOUSE_BUTTON_LEFT);
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
        context.getInput().scroll(0.0, 100.0);
        context.waitTick();
        double afterBurst = doubleField(map, "blocksPerPixel");
        double appliedNotches = -Math.log(afterBurst / beforeBurst) / Math.log(zoomStep);
        require(appliedNotches > 0.24 && appliedNotches < 0.26,
            "touchpad zoom burst was not bounded to a quarter notch per tick");
        require(doubleField(map, "queuedZoom") <= 2.76,
            "touchpad zoom queue exceeded its bounded backlog");

        click(context, point, InputConstants.MOUSE_BUTTON_LEFT);
        context.runOnClient(client -> require(Math.abs(doubleField(map, "queuedZoom")) < 1.0e-9,
            "physical map click did not cancel queued touchpad zoom"));
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
