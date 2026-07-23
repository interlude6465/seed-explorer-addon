package me.seedexplorer.addon.loot;

import me.seedexplorer.addon.structures.StructureType;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StructureLootInfo {
    private static final Map<StructureType, List<ChestLocation>> CHEST_MAP = new HashMap<>();

    static {
        // Simple dungeon: varies in size, chests on walls
        // We approximate: up to 2 chests, offset from center
        // Dungeons are 5x5 to 7x7 rooms; chests at wall centers
        // The actual positions depend on random room size, so we estimate
        CHEST_MAP.put(StructureType.DUNGEON, List.of(
            new ChestLocation(3, 0, 0, "minecraft:chests/simple_dungeon"),
            new ChestLocation(-3, 0, 0, "minecraft:chests/simple_dungeon")
        ));

        // Desert pyramid: 4 chests in the hidden room
        // The room is centered below the pyramid at y=3 above bottom
        // Chests are in a 2x2 pattern
        CHEST_MAP.put(StructureType.DESERT_PYRAMID, List.of(
            new ChestLocation(3, -7, 2, "minecraft:chests/desert_pyramid"),
            new ChestLocation(-4, -7, 2, "minecraft:chests/desert_pyramid"),
            new ChestLocation(3, -7, -4, "minecraft:chests/desert_pyramid"),
            new ChestLocation(-4, -7, -4, "minecraft:chests/desert_pyramid")
        ));

        // Stronghold corridors: chests along corridor walls
        CHEST_MAP.put(StructureType.STRONGHOLD, List.of(
            new ChestLocation(4, 0, 0, "minecraft:chests/stronghold_corridor"),
            new ChestLocation(-4, 0, 0, "minecraft:chests/stronghold_corridor"),
            new ChestLocation(0, 0, 4, "minecraft:chests/stronghold_corridor"),
            new ChestLocation(0, 0, -4, "minecraft:chests/stronghold_corridor")
        ));
    }

    public static List<ChestLocation> chestLocationsFor(StructureType type) {
        return CHEST_MAP.getOrDefault(type, List.of());
    }

    public static boolean hasLoot(StructureType type) {
        return CHEST_MAP.containsKey(type);
    }
}
