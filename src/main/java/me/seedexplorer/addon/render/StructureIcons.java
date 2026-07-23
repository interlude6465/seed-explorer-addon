/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.render;

import com.mojang.blaze3d.textures.FilterMode;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import meteordevelopment.meteorclient.renderer.Texture;

import java.util.EnumMap;
import java.util.Map;

/** Loads optional map marker icons packaged with the addon. */
public final class StructureIcons {
    private static final String ROOT = "/assets/meteor-seed-explorer/textures/structures/";
    private static final Map<StructureType, Texture> ICONS = new EnumMap<>(StructureType.class);
    private static boolean loaded;

    private StructureIcons() {
    }

    public static Texture get(StructureType type) {
        if (!loaded) load();
        return ICONS.get(type);
    }

    public static Texture get(GeneratedStructure structure) {
        if (!loaded) load();
        if (structure.type == StructureType.BASTION) {
            Texture variant = bastionVariantIcon(structure.variant);
            if (variant != null) return variant;
        }
        if (structure.type == StructureType.END_CITY) {
            Texture ship = structure.hasShip ? EXTRA.get("end_city_ship") : EXTRA.get("end_city_no_ship");
            if (ship != null) return ship;
        }
        return ICONS.get(structure.type);
    }

    private static void load() {
        loaded = true;
        add(StructureType.ANCIENT_CITY, "ancient_city.png");
        add(StructureType.DESERT_PYRAMID, "desert_pyramid.png");
        add(StructureType.IGLOO, "igloo.png");
        add(StructureType.JUNGLE_TEMPLE, "jungle_temple.png");
        add(StructureType.MINESHAFT, "mineshaft.png");
        add(StructureType.OCEAN_RUIN, "ocean_ruin.png");
        add(StructureType.MONUMENT, "ocean_monument.png");
        add(StructureType.OUTPOST, "pillager_outpost.png");
        add(StructureType.RUINED_PORTAL, "ruined_portal.png");
        add(StructureType.SHIPWRECK, "shipwreck.png");
        add(StructureType.STRONGHOLD, "stronghold.png");
        add(StructureType.TRAIL_RUINS, "trail_ruins.png");
        add(StructureType.TREASURE, "treasure_chest.png");
        add(StructureType.TRIAL_CHAMBER, "trial_chamber.png");
        add(StructureType.VILLAGE, "village.png");
        add(StructureType.WITCH_HUT, "witch_hut.png");
        add(StructureType.MANSION, "woodland_mansion.png");
        add(StructureType.DUNGEON, "dungeon.png");
        add(StructureType.AMETHYST_GEODE, "amethyst_geode.png");
        add(StructureType.FORTRESS, "nether_fortress.png");
        add(StructureType.BASTION, "bastion.png");
        add(StructureType.NETHER_RUINED_PORTAL, "nether_ruined_portal.png");
        add(StructureType.NETHER_FOSSIL, "nether_fossil.png");
        add(StructureType.END_CITY, "end_city.png");
        add(StructureType.END_GATEWAY, "end_gateway.png");
        addExtra("end_city_ship", "end_city_ship.png");
        addExtra("end_city_no_ship", "end_city_no_ship.png");
        addExtra("bastion_bridge", "bastion_bridge.png");
        addExtra("bastion_hoglin_stables", "bastion_hoglin_stables.png");
        addExtra("bastion_housing_units", "bastion_housing_units.png");
        addExtra("bastion_treasure_room", "bastion_treasure_room.png");
    }

    private static final Map<String, Texture> EXTRA = new java.util.HashMap<>();

    private static Texture bastionVariantIcon(String variant) {
        if (variant == null || variant.isBlank()) return null;
        return switch (variant) {
            case "Bridge" -> EXTRA.get("bastion_bridge");
            case "Hoglin Stables" -> EXTRA.get("bastion_hoglin_stables");
            case "Housing Units" -> EXTRA.get("bastion_housing_units");
            case "Treasure Room" -> EXTRA.get("bastion_treasure_room");
            default -> null;
        };
    }

    private static void add(StructureType type, String fileName) {
        Texture texture = Texture.readResource(ROOT + fileName, false, FilterMode.NEAREST);
        if (texture != null) ICONS.put(type, texture);
    }

    private static void addExtra(String key, String fileName) {
        Texture texture = Texture.readResource(ROOT + fileName, false, FilterMode.NEAREST);
        if (texture != null) EXTRA.put(key, texture);
    }
}
