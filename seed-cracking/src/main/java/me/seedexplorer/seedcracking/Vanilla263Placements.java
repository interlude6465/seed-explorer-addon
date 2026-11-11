package me.seedexplorer.seedcracking;

import java.util.List;

/**
 * 26.3 compatibility facade for {@link VanillaPlacements}.
 *
 * <p>New code should use {@link VanillaPlacements#forVersion(MinecraftVersion)}
 * or {@link VersionProfile#placements()} so 26.1/26.2 worlds do not receive the
 * 26.3-only abandoned-camp set. The constants here are the shared 26.1+ values;
 * this class fixes the catalog methods to the 26.3 catalog for source
 * compatibility with the original baseline.</p>
 */
public final class Vanilla263Placements {
    public static final PlacementSpec SHIPWRECKS = VanillaPlacements.SHIPWRECKS;
    public static final PlacementSpec OCEAN_RUINS = VanillaPlacements.OCEAN_RUINS;
    public static final PlacementSpec DESERT_PYRAMIDS = VanillaPlacements.DESERT_PYRAMIDS;
    public static final PlacementSpec IGLOOS = VanillaPlacements.IGLOOS;
    public static final PlacementSpec JUNGLE_TEMPLES = VanillaPlacements.JUNGLE_TEMPLES;
    public static final PlacementSpec SWAMP_HUTS = VanillaPlacements.SWAMP_HUTS;
    public static final PlacementSpec PILLAGER_OUTPOSTS = VanillaPlacements.PILLAGER_OUTPOSTS;
    public static final PlacementSpec ANCIENT_CITIES = VanillaPlacements.ANCIENT_CITIES;
    public static final PlacementSpec VILLAGES = VanillaPlacements.VILLAGES;
    public static final PlacementSpec TRAIL_RUINS = VanillaPlacements.TRAIL_RUINS;
    public static final PlacementSpec TRIAL_CHAMBERS = VanillaPlacements.TRIAL_CHAMBERS;
    public static final PlacementSpec RUINED_PORTALS = VanillaPlacements.RUINED_PORTALS;
    public static final PlacementSpec WOODLAND_MANSIONS = VanillaPlacements.WOODLAND_MANSIONS;
    public static final PlacementSpec OCEAN_MONUMENTS = VanillaPlacements.OCEAN_MONUMENTS;
    public static final PlacementSpec END_CITIES = VanillaPlacements.END_CITIES;
    public static final PlacementSpec NETHER_COMPLEXES = VanillaPlacements.NETHER_COMPLEXES;
    public static final PlacementSpec NETHER_FOSSILS = VanillaPlacements.NETHER_FOSSILS;
    public static final PlacementSpec ABANDONED_CAMPS = VanillaPlacements.ABANDONED_CAMPS;
    public static final PlacementSpec BURIED_TREASURES = VanillaPlacements.BURIED_TREASURES;
    public static final PlacementSpec MINESHAFTS = VanillaPlacements.MINESHAFTS;

    private Vanilla263Placements() {
    }

    public static PlacementCatalog catalog() {
        return VanillaPlacements.forVersion(MinecraftVersion.V26_3);
    }

    public static List<PlacementSpec> all() {
        return catalog().all();
    }

    public static PlacementSpec byId(String id) {
        return catalog().byId(id);
    }

    public static List<PlacementSpec> liftingCapable() {
        return catalog().liftingCapable();
    }
}
