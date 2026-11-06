package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.ItemLootRequest;
import me.seedexplorer.addon.loot.ChestLootPredictor;
import me.seedexplorer.addon.loot.LootSearchParser;
import me.seedexplorer.addon.loot.LootSearchTerm;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;

/** Offline regression checks for natural item and enchanted-book search terms. */
public final class LootSearchQueryProbe {
    private LootSearchQueryProbe() {
    }

    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            WorldgenEngine.offlineRegistryAccess();

            LootSearchTerm apple = require("golden enchanted apple");
            check("minecraft:enchanted_golden_apple".equals(apple.itemId()),
                "reordered enchanted golden apple query resolved to " + apple.itemId());

            LootSearchTerm pluralApple = require("golden enchanted apples");
            check("minecraft:enchanted_golden_apple".equals(pluralApple.itemId()),
                "plural reordered apple query resolved to " + pluralApple.itemId());
            check(LootSearchParser.suggest("golden enchanted app", 5).stream()
                    .anyMatch(term -> "minecraft:enchanted_golden_apple".equals(term.itemId())),
                "enchanted golden apple was missing from suggestions");

            LootSearchTerm protection = require("protection 3 book");
            check("minecraft:enchanted_book".equals(protection.itemId()), "book item id");
            check("protection".equals(protection.enchantment()) && protection.level() == 3,
                "Protection III parsing: " + protection);

            ItemLoot matching = ItemLoot.randomlyEnchantedBook(1)
                .withPredictedEnchantments("Protection III, Unbreaking III");
            ItemLoot wrongType = ItemLoot.randomlyEnchantedBook(1)
                .withPredictedEnchantments("Fire Protection III");
            ItemLoot wrongLevel = ItemLoot.randomlyEnchantedBook(1)
                .withPredictedEnchantments("Protection IV");
            check(protection.matches(matching), "Protection III summary did not match");
            check(!protection.matches(wrongType), "Fire Protection incorrectly matched Protection");
            check(!protection.matches(wrongLevel), "Protection IV incorrectly matched Protection III");

            ItemLootRequest quantity = new ItemLootRequest(protection, 12);
            check(quantity.quantity() == 12, "quantity was not retained");

            LootSearchTerm netherWart = require("nether wart");
            check(ChestLootPredictor.candidateDimensions(java.util.Set.of(netherWart))
                    .equals(java.util.Set.of(-1)),
                "nether wart search was not restricted to the Nether");
            check(ChestLootPredictor.candidateStructureTypes(java.util.Set.of(apple))
                    .contains(StructureType.DUNGEON),
                "enchanted golden apple search omitted simple dungeons");

            LootSearchTerm detectorRail = require("detector rail");
            check(ChestLootPredictor.candidateStructureTypes(java.util.Set.of(detectorRail))
                    .contains(StructureType.MINESHAFT),
                "detector rail search omitted abandoned mineshafts");

            // This 26.1.2 ocean ruined portal previously fell through to a deterministic,
            // fabricated loot seed and produced the only mismatch in the 2026-08-09 TP audit.
            GeneratedStructure portal = new GeneratedStructure(208, 848, 13, 53,
                StructureType.RUINED_PORTAL, "minecraft:ruined_portal_ocean", false);
            var portalContainers = ChestLootPredictor.predictForStructure(
                3996587595190838260L, portal, 64);
            check(!portalContainers.isEmpty(), "ruined portal preview lost its container position");
            check(portalContainers.stream().allMatch(chest -> !chest.exact()
                    && chest.seed() == 0L
                    && chest.lootTableId().isBlank()
                    && chest.predictedItems().isEmpty()),
                "ruined portal preview exposed fabricated loot as a prediction: " + portalContainers);

            report("loot_search_query_probe=PASS");
        } catch (Throwable throwable) {
            report("loot_search_query_probe=FAIL " + throwable.getClass().getName() + ": " + throwable.getMessage());
            throwable.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static LootSearchTerm require(String text) {
        LootSearchTerm term = LootSearchParser.parseTerm(text);
        if (term == null) throw new IllegalStateException("Could not parse: " + text);
        return term;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void report(String message) {
        System.err.println(message);
        try {
            Files.createDirectories(Path.of("build"));
            Files.writeString(Path.of("build", "loot-search-query-probe.txt"), message + System.lineSeparator());
        } catch (Exception ignored) {
        }
    }
}
