package me.seedexplorer.seedcracking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable set of structure-placement specs for one Minecraft version.
 *
 * <p>Use {@link #vanilla(MinecraftVersion)} for the audited vanilla catalogs,
 * or {@link #of(MinecraftVersion, Collection)} for a datapack. The catalog
 * contains random-spread structure sets only; strongholds use concentric rings
 * and live in {@link ConcentricRings263}.</p>
 */
public final class PlacementCatalog {
    private final MinecraftVersion version;
    private final List<PlacementSpec> all;
    private final Map<String, PlacementSpec> byId;
    private final List<PlacementSpec> liftingCapable;

    private PlacementCatalog(MinecraftVersion version, List<PlacementSpec> all,
                             Map<String, PlacementSpec> byId,
                             List<PlacementSpec> liftingCapable) {
        this.version = version;
        this.all = all;
        this.byId = byId;
        this.liftingCapable = liftingCapable;
    }

    /** The audited vanilla random-spread catalog for a version. */
    public static PlacementCatalog vanilla(MinecraftVersion version) {
        Objects.requireNonNull(version, "version");
        return of(version, VanillaPlacements.specsFor(version));
    }

    public static PlacementCatalog of(MinecraftVersion version,
                                      Collection<PlacementSpec> specs) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(specs, "specs");
        List<PlacementSpec> all = new ArrayList<>(specs.size());
        Map<String, PlacementSpec> byId = new LinkedHashMap<>();
        List<PlacementSpec> lifting = new ArrayList<>();
        for (PlacementSpec spec : specs) {
            Objects.requireNonNull(spec, "spec");
            if (byId.putIfAbsent(spec.id(), spec) != null) {
                throw new IllegalArgumentException("Duplicate structure-set id: " + spec.id());
            }
            all.add(spec);
            if (spec.supportsLowBitLifting()) {
                lifting.add(spec);
            }
        }
        return new PlacementCatalog(version,
            Collections.unmodifiableList(all),
            Collections.unmodifiableMap(byId),
            Collections.unmodifiableList(lifting));
    }

    public MinecraftVersion version() {
        return version;
    }

    public List<PlacementSpec> all() {
        return all;
    }

    /** Returns {@code null} when the id is not present. */
    public PlacementSpec byId(String id) {
        return byId.get(id);
    }

    public Optional<PlacementSpec> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<PlacementSpec> liftingCapable() {
        return liftingCapable;
    }

    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    public int size() {
        return all.size();
    }

    @Override
    public String toString() {
        return "PlacementCatalog[" + version.id() + ", " + all.size() + " set(s)]";
    }
}
