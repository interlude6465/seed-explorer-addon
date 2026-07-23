package me.seedexplorer.addon.loot;

import java.util.List;

public record LootTableDef(String tableId, String displayName, List<LootPool> pools) {
}
