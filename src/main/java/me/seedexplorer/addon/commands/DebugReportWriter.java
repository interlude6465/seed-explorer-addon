/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.commands;

import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Copies debug reports to the clipboard and saves them as text files. */
final class DebugReportWriter {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private DebugReportWriter() {
    }

    static void copyAndSave(String name, String report) {
        Minecraft mc = Minecraft.getInstance();
        String text = report.endsWith(System.lineSeparator()) ? report : report + System.lineSeparator();

        if (mc.keyboardHandler != null) {
            mc.keyboardHandler.setClipboard(text);
        }

        try {
            Path path = save(name, text);
            ChatUtils.info("Seed Explorer: copied report to clipboard and saved to (highlight)" + path + "(default).");
        } catch (IOException exception) {
            ChatUtils.warning("Seed Explorer: copied report to clipboard, but could not save file: " + exception.getMessage());
        }
    }

    private static Path save(String name, String report) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        Path directory = mc.gameDirectory.toPath().resolve("seed-explorer-reports");
        Files.createDirectories(directory);

        String fileName = sanitize(name) + "-" + LocalDateTime.now().format(FILE_TIME) + ".txt";
        Path path = directory.resolve(fileName);
        Files.writeString(path, report, StandardCharsets.UTF_8);
        return path;
    }

    private static String sanitize(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9._-]+", "-");
        if (clean.isBlank()) return "seed-explorer-report";
        return clean;
    }
}
