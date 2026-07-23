/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.commands;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.orbit.EventHandler;

import java.util.Locale;

/** Routes "#mine predicted <ore>" chat input into Seed Explorer's predicted ore miner. */
public final class PredictedMineChatBridge {
    private static SeedExplorerCommand activeCommand;

    public PredictedMineChatBridge(SeedExplorerCommand command) {
        activeCommand = command;
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        if (handleBaritoneStyleMine(event.message)) event.setCancelled(true);
    }

    public static boolean isSeedExplorerMineCommand(String rawMessage) {
        String message = rawMessage == null ? "" : rawMessage.trim();
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.startsWith("#mine predicted ")
            || lower.startsWith("#mine seedexplorer ")
            || lower.equals("#mine predicted")
            || lower.equals("#mine seedexplorer")
            || lower.equals("#stop");
    }

    public static boolean handleBaritoneStyleMine(String rawMessage) {
        if (activeCommand == null) return false;

        String message = rawMessage == null ? "" : rawMessage.trim();
        String lower = message.toLowerCase(Locale.ROOT);
        if ("#stop".equals(lower) && activeCommand.hasActivePredictedMineSession()) {
            activeCommand.stopMinePredictedFromChat();
            return false;
        }

        if (!lower.startsWith("#mine predicted ") && !lower.startsWith("#mine seedexplorer ")) return false;

        String[] parts = message.split("\\s+");
        if (parts.length < 3) {
            activeCommand.runMinePredictedFromChat("", 8, 24);
            return true;
        }

        if ("stop".equalsIgnoreCase(parts[2])) {
            activeCommand.stopMinePredictedFromChat();
            return true;
        }

        int radius = parseInt(parts, 3, 8);
        int targets = parseInt(parts, 4, 24);
        activeCommand.runMinePredictedFromChat(parts[2], radius, targets);
        return true;
    }

    private static int parseInt(String[] parts, int index, int fallback) {
        if (index >= parts.length) return fallback;
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
