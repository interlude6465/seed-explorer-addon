/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

import me.seedexplorer.addon.seed.SeedManager;

public class SeedCommand extends Command {
    public SeedCommand() {
        super("seed", "Sets the world seed and version for seed exploration.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.then(literal("set")
            .then(argument("seed", StringArgumentType.word())
                .executes(context -> {
                    applySeed(context.getArgument("seed", String.class), "");
                    return SINGLE_SUCCESS;
                })
                .then(argument("version", StringArgumentType.greedyString())
                    .executes(context -> {
                        applySeed(context.getArgument("seed", String.class), context.getArgument("version", String.class));
                        return SINGLE_SUCCESS;
                    })
                )
            )
        );

        builder.then(literal("seed")
            .then(argument("seed", StringArgumentType.word())
                .executes(context -> {
                    applySeed(context.getArgument("seed", String.class), "");
                    return SINGLE_SUCCESS;
                })
                .then(argument("version", StringArgumentType.greedyString())
                    .executes(context -> {
                        applySeed(context.getArgument("seed", String.class), context.getArgument("version", String.class));
                        return SINGLE_SUCCESS;
                    })
                )
            )
        );

        builder.then(literal("show").executes(context -> {
            long seed = SeedManager.get().getWorldSeed();
            String version = SeedManager.get().getMcVersion();
            info("Current Seed Explorer seed: (highlight)%d(default)%s.",
                seed,
                version == null || version.isBlank() ? "" : " for version " + version);
            return SINGLE_SUCCESS;
        }));

        builder.then(argument("seed", StringArgumentType.word())
            .executes(context -> {
                applySeed(context.getArgument("seed", String.class), "");
                return SINGLE_SUCCESS;
            })
            .then(argument("version", StringArgumentType.greedyString())
                .executes(context -> {
                    applySeed(context.getArgument("seed", String.class), context.getArgument("version", String.class));
                    return SINGLE_SUCCESS;
                })
            )
        );
    }

    private void applySeed(String seedStr, String version) {
        long seed = parseSeed(seedStr);
        if (version == null || version.isBlank()) {
            SeedManager.get().setWorldSeed(seed);
            info("Set Seed Explorer seed to (highlight)%s(default) (numeric: (highlight)%d(default)).", seedStr, seed);
        } else {
            SeedManager.get().set(seed, version);
            info("Set Seed Explorer seed to (highlight)%s(default) (numeric: (highlight)%d(default)) for version (highlight)%s(default).", seedStr, seed, version);
        }
    }

    private long parseSeed(String seed) {
        try {
            return Long.parseLong(seed);
        } catch (NumberFormatException e) {
            return seed.hashCode();
        }
    }
}
