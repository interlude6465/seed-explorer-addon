package me.seedexplorer.standalone.test;

import me.seedexplorer.standalone.StandaloneAncillaryRuntime;
import me.seedexplorer.standalone.StandaloneSeedExplorerConfig;
import me.seedexplorer.standalone.StandaloneSeedExplorerScreen;
import me.seedexplorer.standalone.StandaloneSeedExplorerSettingsScreen;
import me.seedexplorer.standalone.StandaloneTerrain3DScreen;
import me.seedexplorer.addon.render.Terrain3DRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;

import java.util.Arrays;
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

            context.runOnClient(client -> {
                Screen map = mapScreen.get();
                Screen settings = new StandaloneSeedExplorerSettingsScreen(map, config);
                client.gui.setScreen(settings);
                require(client.gui.screen() == settings, "settings screen did not open");
            });
            context.waitTicks(3);
            context.takeScreenshot("seed-explorer-standalone-settings");

            context.runOnClient(client -> {
                Screen settings = client.gui.screen();
                settings.onClose();
                Screen map = mapScreen.get();
                require(client.gui.screen() == map, "settings back navigation did not restore the map");

                Screen terrain3d = new StandaloneTerrain3DScreen(map, config, seed, 0,
                    java.util.List.of()).openAt(client.player.getX(), client.player.getZ(),
                        null, java.util.List.of(), 0);
                client.gui.setScreen(terrain3d);
                require(client.gui.screen() == terrain3d, "3D screen did not open");
            });
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
            context.runOnClient(client -> {
                client.gui.screen().onClose();
                require(client.gui.screen() == mapScreen.get(),
                    "3D back navigation did not restore the same 2D map");
            });
        } catch (Throwable failure) {
            throw new AssertionError("Standalone client smoke failed", failure);
        }
        context.runOnClient(client -> require(client.level == null,
            "temporary world did not close cleanly"));
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
