package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import java.util.List;

/** Quantity and enchantment checks use distinct identical adjacent inventories deliberately. */
public final class StandaloneSearchProbe {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        ItemLoot diamonds = new ItemLoot("minecraft:diamond", 3, 3, 1, "Diamond", false, null, 0, null);
        List<ChestLootOutput> chests = List.of(
            new ChestLootOutput(0, 60, 0, "test", List.of(diamonds), 1),
            new ChestLootOutput(1, 60, 0, "test", List.of(diamonds), 2));
        LootSearchTerm diamond = new LootSearchTerm("minecraft:diamond", null, 0, "Diamond");
        require(StandaloneSearch.containsAll(chests, List.of(new ItemLootRequest(diamond, 6))), "distinct inventories undercounted");
        require(!StandaloneSearch.containsAll(chests, List.of(new ItemLootRequest(diamond, 7))), "quantity overcounted");
        ItemLoot book = new ItemLoot("minecraft:enchanted_book", 1, 1, 1, "Book", false, "minecraft:sharpness", 5, null);
        require(new LootSearchTerm("minecraft:enchanted_book", "sharpness", 5, "Sharpness V").matches(book), "enchantment not found");
        require(!new LootSearchTerm("minecraft:enchanted_book", "sharpness", 4, "Sharpness IV").matches(book), "wrong enchantment level accepted");
        require(StandaloneSearch.convertCoordinate(-9, 0, -1) == -2, "Nether coordinate rounding");
        require(StandaloneSearch.convertCoordinate(4_000_000, -1, 0) == 30_000_000, "world border clamp");
        var context = new me.seedexplorer.addon.worldgen.PredictionContext(42L, "26.3", 0);
        require(StandaloneSearch.resolveStructure(context, "woodlnd mansion")
            == me.seedexplorer.addon.structures.StructureType.MANSION, "fuzzy structure matching");
        require(StandaloneSearch.resolveStructure(context, "pillager tower")
            == me.seedexplorer.addon.structures.StructureType.OUTPOST, "structure alias matching");
        System.out.println("Standalone search probe: PASS");
    }
    private static void require(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
}
