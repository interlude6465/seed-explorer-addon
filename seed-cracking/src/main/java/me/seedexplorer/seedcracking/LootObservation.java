package me.seedexplorer.seedcracking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One observed container's loot.
 *
 * <p>Structure loot in 26.1+ is generated from a per-container seed derived from
 * the world seed, the structure start, and the container position. It is a
 * full-seed verifier, but the exact chain is complex and easy to get subtly
 * wrong. Mark an observation {@code complete} only when the container has not
 * been looted and every item stack is known. Partial observations can still be
 * used to reject seeds that do not contain the listed items.</p>
 *
 * @param structureId   structure identifier, for example {@code minecraft:desert_pyramid}
 * @param startChunkX   structure start chunk X
 * @param startChunkZ   structure start chunk Z
 * @param containerIndex zero-based container index within the structure
 * @param lootTableId   namespaced loot table id
 * @param observedItems observed item stacks
 * @param complete      true when the container is untouched and fully known
 */
public record LootObservation(
    String structureId,
    int startChunkX,
    int startChunkZ,
    int containerIndex,
    String lootTableId,
    List<LootItem> observedItems,
    boolean complete
) {
    public LootObservation {
        Objects.requireNonNull(structureId, "structureId");
        Objects.requireNonNull(lootTableId, "lootTableId");
        Objects.requireNonNull(observedItems, "observedItems");
        if (structureId.isBlank()) {
            throw new IllegalArgumentException("structureId must not be blank");
        }
        if (lootTableId.isBlank()) {
            throw new IllegalArgumentException("lootTableId must not be blank");
        }
        if (containerIndex < 0) {
            throw new IllegalArgumentException("containerIndex must be non-negative: " + containerIndex);
        }
        observedItems = List.copyOf(observedItems);
    }

    public static LootObservation exact(String structureId, int startChunkX, int startChunkZ,
                                        int containerIndex, String lootTableId,
                                        LootItem... items) {
        return new LootObservation(structureId, startChunkX, startChunkZ, containerIndex,
            lootTableId, List.of(items), true);
    }

    public static LootObservation partial(String structureId, int startChunkX, int startChunkZ,
                                          int containerIndex, String lootTableId,
                                          LootItem... items) {
        return new LootObservation(structureId, startChunkX, startChunkZ, containerIndex,
            lootTableId, List.of(items), false);
    }

    public Map<String, Integer> observedCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LootItem item : observedItems) {
            counts.merge(item.itemId(), item.count(), Integer::sum);
        }
        return counts;
    }

    /** A namespaced item id and a positive stack count. */
    public record LootItem(String itemId, int count) {
        public LootItem {
            Objects.requireNonNull(itemId, "itemId");
            if (itemId.isBlank()) {
                throw new IllegalArgumentException("itemId must not be blank");
            }
            if (count < 1) {
                throw new IllegalArgumentException("count must be positive: " + count);
            }
        }
    }
}
