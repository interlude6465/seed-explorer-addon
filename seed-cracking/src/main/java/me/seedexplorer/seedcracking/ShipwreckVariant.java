package me.seedexplorer.seedcracking;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Observable shipwreck variant metadata.
 *
 * <p>26.1+ chooses the shipwreck piece set, rotation, and chest placement during
 * structure generation, after the placement chunk is known. This record is the
 * dependency-free observation key for variant-aware filtering, loot hints, and
 * screenshot orientation data. It does not by itself prove a world seed; use it
 * with {@link StructureObservation} and a structure-start or loot oracle.</p>
 */
public record ShipwreckVariant(
    Kind kind,
    StructureGeometry.Orientation orientation,
    boolean treasureChest,
    boolean mapChest
) {
    /** Approximate upper bound: 1 type bit + log2(4*3) orientation + 2 chest bits. */
    public static final double APPROX_BITS = Math.log(2.0 * 4.0 * 3.0 * 2.0 * 2.0) / Math.log(2.0);

    public enum Kind {
        SHIPWRECK,
        BEACHED;

        public static Kind fromString(String value) {
            Objects.requireNonNull(value, "value");
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "shipwreck" -> SHIPWRECK;
                case "shipwreck_beached", "beached" -> BEACHED;
                default -> throw new IllegalArgumentException("Unknown shipwreck kind: " + value);
            };
        }
    }

    public ShipwreckVariant {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(orientation, "orientation");
    }

    public static ShipwreckVariant fromObservation(String kind,
                                                   String facing,
                                                   String mirror,
                                                   boolean treasureChest,
                                                   boolean mapChest) {
        return new ShipwreckVariant(
            Kind.fromString(kind),
            StructureGeometry.Orientation.from(facing, mirror),
            treasureChest,
            mapChest);
    }

    /** The loot table selected by the treasure/supply state. */
    public String lootTable() {
        return treasureChest
            ? "minecraft:chests/shipwreck_treasure"
            : "minecraft:chests/shipwreck_supply";
    }

    /** All loot tables a chest in this variant may use. */
    public List<String> lootTables() {
        List<String> tables = new ArrayList<>(2);
        tables.add(lootTable());
        if (mapChest) {
            tables.add("minecraft:chests/shipwreck_map");
        }
        return List.copyOf(tables);
    }

    public boolean matchesLootTable(String table) {
        return table != null && lootTables().contains(table);
    }

    public String describe() {
        return kind.name().toLowerCase(Locale.ROOT)
            + " facing=" + orientation.facing().name().toLowerCase(Locale.ROOT)
            + " mirror=" + orientation.mirror().name().toLowerCase(Locale.ROOT)
            + " treasure=" + treasureChest
            + " map=" + mapChest;
    }
}
