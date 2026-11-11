package me.seedexplorer.seedcracking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Vanilla random-spread structure-set placement constants for 26.1+.
 *
 * <p>Values were extracted from the vanilla structure-set JSON files. The 20
 * structure-set files shared by 26.1.2, 26.2, and 26.3 are byte-for-byte
 * identical. Strongholds use concentric rings and are not part of this
 * random-spread catalog. 26.3 adds one random-spread set,
 * {@code minecraft:abandoned_camp}; it is present in the 26.3 catalog and
 * absent from the 26.1/26.2 catalogs.</p>
 *
 * <p>Use {@link #forVersion(MinecraftVersion)} or
 * {@link VersionProfile#placements()} to obtain an immutable catalog for the
 * world you are cracking. The static constants are shared because their
 * placement values are identical across the audited range.</p>
 *
 * <p>Only shipwrecks, ocean ruins, desert pyramids, igloos, jungle temples,
 * swamp huts, and pillager outposts currently support the low-bit sieve. The
 * rest are still useful as exact candidate filters, especially when combined
 * with a known low partial seed. Frequency reduction and exclusion zones are
 * not part of this placement model; see the individual notes.</p>
 */
public final class VanillaPlacements {
    /** spacing 24, separation 4, range 20: low 19 seed bits. */
    public static final PlacementSpec SHIPWRECKS =
        new PlacementSpec("minecraft:shipwrecks", 24, 4, 165745295, SpreadType.LINEAR);

    /** spacing 20, separation 8, range 12: low 19 seed bits. */
    public static final PlacementSpec OCEAN_RUINS =
        new PlacementSpec("minecraft:ocean_ruins", 20, 8, 14357621, SpreadType.LINEAR);

    /** spacing 32, separation 8, range 24: low 20 seed bits. */
    public static final PlacementSpec DESERT_PYRAMIDS =
        new PlacementSpec("minecraft:desert_pyramids", 32, 8, 14357617, SpreadType.LINEAR);

    /** spacing 32, separation 8, range 24: low 20 seed bits. */
    public static final PlacementSpec IGLOOS =
        new PlacementSpec("minecraft:igloos", 32, 8, 14357618, SpreadType.LINEAR);

    /** spacing 32, separation 8, range 24: low 20 seed bits. */
    public static final PlacementSpec JUNGLE_TEMPLES =
        new PlacementSpec("minecraft:jungle_temples", 32, 8, 14357619, SpreadType.LINEAR);

    /** spacing 32, separation 8, range 24: low 20 seed bits. */
    public static final PlacementSpec SWAMP_HUTS =
        new PlacementSpec("minecraft:swamp_huts", 32, 8, 14357620, SpreadType.LINEAR);

    /**
     * spacing 32, separation 8, range 24: low 20 seed bits.
     *
     * <p>26.1-26.3 also apply a 0.2 frequency reducer and a 10-chunk village
     * exclusion zone. An observed outpost proves those gates passed, but a
     * candidate placement chunk does not prove an outpost exists.</p>
     */
    public static final PlacementSpec PILLAGER_OUTPOSTS =
        new PlacementSpec("minecraft:pillager_outposts", 32, 8, 165745296, SpreadType.LINEAR);

    /**
     * spacing 24, separation 8, range 16. Range is a power of two, so the
     * non-power-of-two low-bit sieve does not apply; use exact checks only.
     */
    public static final PlacementSpec ANCIENT_CITIES =
        new PlacementSpec("minecraft:ancient_cities", 24, 8, 20083232, SpreadType.LINEAR);

    /** spacing 34, separation 8, range 26: exact checks only. */
    public static final PlacementSpec VILLAGES =
        new PlacementSpec("minecraft:villages", 34, 8, 10387312, SpreadType.LINEAR);

    /** spacing 34, separation 8, range 26: exact checks only. */
    public static final PlacementSpec TRAIL_RUINS =
        new PlacementSpec("minecraft:trail_ruins", 34, 8, 83469867, SpreadType.LINEAR);

    /** spacing 34, separation 12, range 22: exact checks only. */
    public static final PlacementSpec TRIAL_CHAMBERS =
        new PlacementSpec("minecraft:trial_chambers", 34, 12, 94251327, SpreadType.LINEAR);

    /**
     * spacing 40, separation 15, range 25: exact checks only. The same
     * structure set is used in the Overworld and Nether; variant selection
     * decides which portal type appears.
     */
    public static final PlacementSpec RUINED_PORTALS =
        new PlacementSpec("minecraft:ruined_portals", 40, 15, 34222645, SpreadType.LINEAR);

    /** spacing 80, separation 20, triangular: exact checks only. */
    public static final PlacementSpec WOODLAND_MANSIONS =
        new PlacementSpec("minecraft:woodland_mansions", 80, 20, 10387319, SpreadType.TRIANGULAR);

    /** spacing 32, separation 5, triangular: exact checks only. */
    public static final PlacementSpec OCEAN_MONUMENTS =
        new PlacementSpec("minecraft:ocean_monuments", 32, 5, 10387313, SpreadType.TRIANGULAR);

    /** spacing 20, separation 11, triangular: exact checks only. */
    public static final PlacementSpec END_CITIES =
        new PlacementSpec("minecraft:end_cities", 20, 11, 10387313, SpreadType.TRIANGULAR);

    /** spacing 27, separation 4, range 23: exact checks only. Fortress or bastion. */
    public static final PlacementSpec NETHER_COMPLEXES =
        new PlacementSpec("minecraft:nether_complexes", 27, 4, 30084232, SpreadType.LINEAR);

    /** spacing 2, separation 1, range 1: no placement information. */
    public static final PlacementSpec NETHER_FOSSILS =
        new PlacementSpec("minecraft:nether_fossils", 2, 1, 14357921, SpreadType.LINEAR);

    /**
     * spacing 37, separation 8, range 29: exact checks only.
     *
     * <p>Present in 26.3 and later; absent from 26.1/26.2 vanilla data.</p>
     */
    public static final PlacementSpec ABANDONED_CAMPS =
        new PlacementSpec("minecraft:abandoned_camp", 37, 8, 91231127, SpreadType.LINEAR);

    /**
     * spacing 1, separation 0. Every chunk is a potential chunk; only the
     * 1% frequency reducer and biome/height rules matter.
     */
    public static final PlacementSpec BURIED_TREASURES =
        new PlacementSpec("minecraft:buried_treasures", 1, 0, 0, SpreadType.LINEAR);

    /**
     * spacing 1, separation 0. Every chunk is a potential chunk; only the
     * 0.4% frequency reducer and biome/height rules matter.
     */
    public static final PlacementSpec MINESHAFTS =
        new PlacementSpec("minecraft:mineshafts", 1, 0, 0, SpreadType.LINEAR);

    private static final List<PlacementSpec> SHARED;

    static {
        List<PlacementSpec> shared = new ArrayList<>();
        shared.add(SHIPWRECKS);
        shared.add(OCEAN_RUINS);
        shared.add(DESERT_PYRAMIDS);
        shared.add(IGLOOS);
        shared.add(JUNGLE_TEMPLES);
        shared.add(SWAMP_HUTS);
        shared.add(PILLAGER_OUTPOSTS);
        shared.add(ANCIENT_CITIES);
        shared.add(VILLAGES);
        shared.add(TRAIL_RUINS);
        shared.add(TRIAL_CHAMBERS);
        shared.add(RUINED_PORTALS);
        shared.add(WOODLAND_MANSIONS);
        shared.add(OCEAN_MONUMENTS);
        shared.add(END_CITIES);
        shared.add(NETHER_COMPLEXES);
        shared.add(NETHER_FOSSILS);
        shared.add(BURIED_TREASURES);
        shared.add(MINESHAFTS);
        SHARED = Collections.unmodifiableList(shared);
    }

    private VanillaPlacements() {
    }

    /** Immutable, cached 26.1/26.2/26.3 catalog for the requested version. */
    public static PlacementCatalog forVersion(MinecraftVersion version) {
        Objects.requireNonNull(version, "version");
        return VersionProfile.of(version).placements();
    }

    /** Latest audited catalog (currently 26.3). */
    public static PlacementCatalog catalog() {
        return forVersion(MinecraftVersion.latest());
    }

    /** Latest audited catalog entries. */
    public static List<PlacementSpec> all() {
        return catalog().all();
    }

    public static List<PlacementSpec> all(MinecraftVersion version) {
        return forVersion(version).all();
    }

    /** Returns {@code null} when the id is not in the latest catalog. */
    public static PlacementSpec byId(String id) {
        return catalog().byId(id);
    }

    public static PlacementSpec byId(MinecraftVersion version, String id) {
        return forVersion(version).byId(id);
    }

    /**
     * The overworld sets that can feed the low-bit sieve for the latest
     * version. Add your own specs for custom datapacks; any linear
     * non-power-of-two range divisible by four works.
     */
    public static List<PlacementSpec> liftingCapable() {
        return catalog().liftingCapable();
    }

    public static List<PlacementSpec> liftingCapable(MinecraftVersion version) {
        return forVersion(version).liftingCapable();
    }

    /** True when the version's vanilla data contains abandoned camps. */
    public static boolean hasAbandonedCamps(MinecraftVersion version) {
        return Objects.requireNonNull(version, "version").supportsAbandonedCamps();
    }

    /** Shared 26.1+ entries before version-specific additions. */
    static List<PlacementSpec> specsFor(MinecraftVersion version) {
        Objects.requireNonNull(version, "version");
        List<PlacementSpec> specs = new ArrayList<>(SHARED);
        if (version.supportsAbandonedCamps()) {
            specs.add(ABANDONED_CAMPS);
        }
        return specs;
    }
}
