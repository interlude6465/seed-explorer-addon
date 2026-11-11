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
    private static SeedExplorerFabricClient instance;
    private static StandaloneMapScreenFactory screenFactory;
    private StandaloneSeedExplorerConfig config;
    private final StandaloneWorldContext worldContext = new StandaloneWorldContext();
    private final StandaloneLiveChunkCapture liveCapture = new StandaloneLiveChunkCapture();

    @Override
    public void onInitializeClient() {
        config = StandaloneSeedExplorerConfig.load();
        instance = this;
        StandaloneAncillaryRuntime ancillary = StandaloneAncillaryRuntime.initialize(config);
        StandaloneSeedCrackingRuntime cracking = StandaloneSeedCrackingRuntime.initialize(config);
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
            StandaloneSearchRuntime.tick(client, config);
            cracking.tick(client);
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

    /** Client-thread snapshot handoff, scoped to the currently configured world. */
    static java.util.List<me.seedexplorer.addon.live.LiveChunkSnapshot> lootObservations(
            me.seedexplorer.addon.worldgen.PredictionContext context) {
        var client = net.minecraft.client.Minecraft.getInstance();
        SeedExplorerFabricClient current = instance;
        if (current == null || client == null || !client.isSameThread()
            || client.level == null || !current.config.hasSeed()
            || current.config.seed() != context.worldSeed()
            || !current.config.predictionVersion().equals(context.minecraftVersion())) return java.util.List.of();
        return current.liveCapture.snapshots(client.level, context.dimension());
    }
}
