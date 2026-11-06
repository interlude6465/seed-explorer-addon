/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.modules;

import me.seedexplorer.addon.gui.SeedExplorerScreen;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;

/** Keeps the Seed Explorer GUI hotkey available even when its render module is disabled. */
public final class SeedExplorerKeybindHandler {
    private final SeedExplorerModule module;
    private boolean keyWasDown;

    public SeedExplorerKeybindHandler(SeedExplorerModule module) {
        this.module = module;
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) {
            keyWasDown = false;
            return;
        }

        boolean down = module.openMapKey.get().isPressed();
        if (down && !keyWasDown) {
            mc.setScreen(new SeedExplorerScreen(GuiThemes.get()));
        }
        keyWasDown = down;
    }
}
