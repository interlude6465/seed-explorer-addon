package me.seedexplorer.standalone;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import me.seedexplorer.addon.render.Terrain3DRenderer;

import java.util.Objects;

/** Standalone Fabric entrypoint; contains no Meteor runtime dependency. */
public final class SeedExplorerFabricClient implements ClientModInitializer {
    private static StandaloneMapScreenFactory screenFactory;
    private StandaloneSeedExplorerConfig config;
    private final StandaloneWorldContext worldContext = new StandaloneWorldContext();
    private final StandaloneLiveChunkCapture liveCapture = new StandaloneLiveChunkCapture();

    @Override
    public void onInitializeClient() {
        config = StandaloneSeedExplorerConfig.load();
        StandaloneAncillaryRuntime ancillary = StandaloneAncillaryRuntime.initialize(config);
        StandaloneOreEspRenderer.install(config);
        Terrain3DRenderer.shared().setWorldDataSource(liveCapture);
        KeyMapping.Category category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("seed-explorer", "main"));
        KeyMapping openMap = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.seed-explorer.open_map", InputConstants.Type.KEYBOARD,
            InputConstants.KEY_X, category));
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("seed-explorer", "minimap"),
            new StandaloneMinimapOverlay(config));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            worldContext.tick(client, config);
            ancillary.tick(client);
            liveCapture.tick(client);
            StandaloneOreEspRenderer.tick(client);
            while (openMap.consumeClick()) {
                if (client.gui.screen() != null) continue;
                StandaloneMapScreenFactory factory = screenFactory;
                client.gui.setScreen(factory == null
                    ? new StandaloneSeedExplorerScreen(null, config)
                    : factory.create(null));
            }
        });
    }

    public static void installScreenFactory(StandaloneMapScreenFactory factory) {
        screenFactory = Objects.requireNonNull(factory, "factory");
    }
}
