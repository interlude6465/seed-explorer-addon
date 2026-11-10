/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.modules;

import me.seedexplorer.addon.gui.SeedExplorerScreen;
import me.seedexplorer.addon.seed.SeedManager;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/** Keeps the Seed Explorer GUI hotkey available even when its render module is disabled. */
public final class SeedExplorerKeybindHandler {
    private final SeedExplorerModule module;
    private boolean developmentAutoOpenPending =
        Boolean.getBoolean("seedexplorer.dev.auto-open-map");
    private boolean keyWasDown;

    public SeedExplorerKeybindHandler(SeedExplorerModule module) {
        this.module = module;
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.screen() != null) {
            keyWasDown = false;
            return;
        }

        if (developmentAutoOpenPending && SeedManager.get().getWorldSeed() == 0L) {
            var server = mc.hasSingleplayerServer() ? mc.getSingleplayerServer() : null;
            var overworld = server == null ? null : server.getLevel(Level.OVERWORLD);
            long runtimeSeed = overworld == null ? 0L : overworld.getSeed();
            if (runtimeSeed != 0L) SeedManager.get().set(runtimeSeed, "26.3");
        }
        if (developmentAutoOpenPending && SeedManager.get().getWorldSeed() != 0L) {
            developmentAutoOpenPending = false;
            mc.gui.setScreen(new SeedExplorerScreen(GuiThemes.get()));
            return;
        }

        boolean down = module.openMapKey.get().isPressed();
        if (down && !keyWasDown) {
            mc.gui.setScreen(new SeedExplorerScreen(GuiThemes.get()));
        }
        keyWasDown = down;
    }
}
