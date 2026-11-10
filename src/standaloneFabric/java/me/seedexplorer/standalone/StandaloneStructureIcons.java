package me.seedexplorer.standalone;

import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import net.minecraft.resources.Identifier;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Exhaustive vanilla-GUI texture catalog for standalone structure markers. */
public final class StandaloneStructureIcons {
    private static final String NAMESPACE = "meteor-seed-explorer";
    private static final String DIRECTORY = "textures/structures/";
    private static final Map<StructureType, String> FILES = new EnumMap<>(StructureType.class);
    private static final Map<String, String> BASTION_VARIANTS = Map.of(
        "Bridge", "bastion_bridge.png",
        "Hoglin Stables", "bastion_hoglin_stables.png",
        "Housing Units", "bastion_housing_units.png",
        "Treasure Room", "bastion_treasure_room.png"
    );

    static {
        register(StructureType.VILLAGE, "village.png");
        register(StructureType.DESERT_PYRAMID, "desert_pyramid.png");
        register(StructureType.JUNGLE_TEMPLE, "jungle_temple.png");
        register(StructureType.WITCH_HUT, "witch_hut.png");
        register(StructureType.IGLOO, "igloo.png");
        register(StructureType.OUTPOST, "pillager_outpost.png");
        register(StructureType.MONUMENT, "ocean_monument.png");
        register(StructureType.MANSION, "woodland_mansion.png");
        register(StructureType.ANCIENT_CITY, "ancient_city.png");
        register(StructureType.TRIAL_CHAMBER, "trial_chamber.png");
        register(StructureType.TRAIL_RUINS, "trail_ruins.png");
        register(StructureType.ABANDONED_CAMP, "abandoned_camp.png");
        register(StructureType.STRONGHOLD, "stronghold.png");
        register(StructureType.RUINED_PORTAL, "ruined_portal.png");
        register(StructureType.SHIPWRECK, "shipwreck.png");
        register(StructureType.OCEAN_RUIN, "ocean_ruin.png");
        register(StructureType.MINESHAFT, "mineshaft.png");
        register(StructureType.DUNGEON, "dungeon.png");
        register(StructureType.TREASURE, "treasure_chest.png");
        register(StructureType.AMETHYST_GEODE, "amethyst_geode.png");
        register(StructureType.FORTRESS, "nether_fortress.png");
        register(StructureType.BASTION, "bastion.png");
        register(StructureType.NETHER_RUINED_PORTAL, "nether_ruined_portal.png");
        register(StructureType.NETHER_FOSSIL, "nether_fossil.png");
        register(StructureType.END_CITY, "end_city.png");
        register(StructureType.END_GATEWAY, "end_gateway.png");
        if (!FILES.keySet().equals(EnumSet.allOf(StructureType.class))) {
            throw new IllegalStateException("Standalone structure icon catalog is incomplete");
        }
    }

    private StandaloneStructureIcons() {
    }

    public static Identifier texture(StructureType type) {
        return identifier(fileName(type));
    }

    public static Identifier texture(GeneratedStructure structure) {
        return identifier(fileName(structure));
    }

    public static String fileName(StructureType type) {
        String file = FILES.get(type);
        if (file == null) throw new IllegalArgumentException("No icon for " + type);
        return file;
    }

    public static String fileName(GeneratedStructure structure) {
        if (structure.type == StructureType.BASTION) {
            String variant = BASTION_VARIANTS.get(structure.variant);
            if (variant != null) return variant;
        }
        if (structure.type == StructureType.END_CITY) {
            return structure.hasShip ? "end_city_ship.png" : "end_city_no_ship.png";
        }
        return fileName(structure.type);
    }

    public static String resourcePath(String fileName) {
        return "/assets/" + NAMESPACE + "/" + DIRECTORY + fileName;
    }

    public static Set<StructureType> supportedTypes() {
        return Set.copyOf(FILES.keySet());
    }

    public static Set<String> requiredFileNames() {
        Set<String> files = new LinkedHashSet<>(FILES.values());
        files.addAll(BASTION_VARIANTS.values());
        files.add("end_city_ship.png");
        files.add("end_city_no_ship.png");
        return Set.copyOf(files);
    }

    private static Identifier identifier(String fileName) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, DIRECTORY + fileName);
    }

    private static void register(StructureType type, String fileName) {
        if (FILES.put(type, fileName) != null) {
            throw new IllegalStateException("Duplicate structure icon: " + type);
        }
    }
}
