package me.seedexplorer.addon.loot;

/** One item and the minimum stack quantity requested by the item-search screen. */
public record ItemLootRequest(LootSearchTerm term, int quantity) {
    public ItemLootRequest {
        if (term == null) throw new IllegalArgumentException("term");
        quantity = Math.max(1, quantity);
    }
}
