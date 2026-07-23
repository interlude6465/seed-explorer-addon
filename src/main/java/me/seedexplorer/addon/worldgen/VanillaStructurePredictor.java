/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.worldgen;

import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.EndCityPieces;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Predicts supported structures using vanilla structure-set placement data. */
public final class VanillaStructurePredictor {
    private static final Object STRONGHOLD_FALLBACK_LOCK = new Object();
    private static volatile long fallbackStrongholdSeed = Long.MIN_VALUE;
    private static volatile List<ChunkPos> fallbackStrongholdPositions = List.of();
    private static volatile Field templateNameField;

    private VanillaStructurePredictor() {
    }

    public static List<GeneratedStructure> predictOverworld(long seed, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        return predictOverworld(seed, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, true);
    }

    public static List<GeneratedStructure> predictOverworld(long seed, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ, boolean includeBuriedTreasure) {
        return predictOverworld(seed, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, includeBuriedTreasure, false);
    }

    public static List<GeneratedStructure> predictOverworld(long seed, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ, boolean includeBuriedTreasure, boolean includeMineshafts) {
        List<GeneratedStructure> results = new ArrayList<>();
        addForSet(results, seed, BuiltinStructureSets.VILLAGES, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addForSet(results, seed, BuiltinStructureSets.DESERT_PYRAMIDS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addForSet(results, seed, BuiltinStructureSets.ANCIENT_CITIES, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addForSet(results, seed, BuiltinStructureSets.SHIPWRECKS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addForSet(results, seed, BuiltinStructureSets.IGLOOS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addForSet(results, seed, BuiltinStructureSets.JUNGLE_TEMPLES, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addForSet(results, seed, BuiltinStructureSets.SWAMP_HUTS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addForSet(results, seed, BuiltinStructureSets.OCEAN_MONUMENTS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addForSet(results, seed, BuiltinStructureSets.WOODLAND_MANSIONS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addForSet(results, seed, BuiltinStructureSets.OCEAN_RUINS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        if (includeBuriedTreasure) addForSet(results, seed, BuiltinStructureSets.BURIED_TREASURES, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addForSet(results, seed, BuiltinStructureSets.RUINED_PORTALS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addForSet(results, seed, BuiltinStructureSets.PILLAGER_OUTPOSTS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addForSet(results, seed, BuiltinStructureSets.TRAIL_RUINS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addForSet(results, seed, BuiltinStructureSets.TRIAL_CHAMBERS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        if (includeMineshafts) addForSet(results, seed, BuiltinStructureSets.MINESHAFTS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addStrongholds(results, seed, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        return results;
    }

    public static List<GeneratedStructure> predictDimension(long seed, int dimension, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ, boolean includeBuriedTreasure) {
        return predictDimension(seed, dimension, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, includeBuriedTreasure, false);
    }

    public static List<GeneratedStructure> predictDimension(long seed, int dimension, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ, boolean includeBuriedTreasure, boolean includeMineshafts) {
        return switch (dimension) {
            case -1 -> predictNether(seed, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
            case 1 -> predictEnd(seed, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
            default -> predictOverworld(seed, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, includeBuriedTreasure, includeMineshafts);
        };
    }

    public static List<GeneratedStructure> predictNether(long seed, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        List<GeneratedStructure> results = new ArrayList<>();
        addForSet(results, seed, -1, BuiltinStructureSets.NETHER_COMPLEXES, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addForSet(results, seed, -1, BuiltinStructureSets.RUINED_PORTALS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, "minecraft:ruined_portal_nether");
        addForSet(results, seed, -1, BuiltinStructureSets.NETHER_FOSSILS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        return results;
    }

    public static List<GeneratedStructure> predictEnd(long seed, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        List<GeneratedStructure> results = new ArrayList<>();
        addForSet(results, seed, 1, BuiltinStructureSets.END_CITIES, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addEndGateways(results, seed, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        return results;
    }

    public static List<DebugCandidate> debugOverworld(long seed, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        List<DebugCandidate> results = new ArrayList<>();
        addDebugForSet(results, seed, BuiltinStructureSets.VILLAGES, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugForSet(results, seed, BuiltinStructureSets.DESERT_PYRAMIDS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugForSet(results, seed, BuiltinStructureSets.ANCIENT_CITIES, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugForSet(results, seed, BuiltinStructureSets.SHIPWRECKS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugForSet(results, seed, BuiltinStructureSets.IGLOOS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugForSet(results, seed, BuiltinStructureSets.JUNGLE_TEMPLES, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugForSet(results, seed, BuiltinStructureSets.SWAMP_HUTS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugForSet(results, seed, BuiltinStructureSets.OCEAN_MONUMENTS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugForSet(results, seed, BuiltinStructureSets.WOODLAND_MANSIONS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugForSet(results, seed, BuiltinStructureSets.OCEAN_RUINS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugForSet(results, seed, BuiltinStructureSets.RUINED_PORTALS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugForSet(results, seed, BuiltinStructureSets.PILLAGER_OUTPOSTS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugForSet(results, seed, BuiltinStructureSets.TRAIL_RUINS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugForSet(results, seed, BuiltinStructureSets.TRIAL_CHAMBERS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugForSet(results, seed, BuiltinStructureSets.MINESHAFTS, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugForSet(results, seed, BuiltinStructureSets.BURIED_TREASURES, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        addDebugStrongholds(results, seed, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        return results;
    }

    public static List<GeneratedStructure> predictOverworldStructure(long seed, String structureId, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        List<GeneratedStructure> results = new ArrayList<>();
        ResourceKey<StructureSet> key = setForStructureId(structureId);
        if (key == BuiltinStructureSets.STRONGHOLDS) {
            addStrongholds(results, seed, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ);
        } else if (key != null) {
            addForSet(results, seed, 0, key, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, structureId);
        }
        return results;
    }

    public static GeneratedStructure predictInRegion(long seed, StructureType type, int regionX, int regionZ) {
        ResourceKey<StructureSet> setKey = setFor(type);
        if (setKey == null) return null;

        try {
            Holder<StructureSet> holder = WorldgenEngine.vanillaLookup()
                .lookupOrThrow(Registries.STRUCTURE_SET)
                .getOrThrow(setKey);
            StructureSet set = holder.value();
            if (set.placement() instanceof ConcentricRingsStructurePlacement) {
                List<GeneratedStructure> structures = predictOverworldStructure(
                    seed,
                    "minecraft:stronghold",
                    regionX * 64,
                    regionZ * 64,
                    regionX * 64 + 63,
                    regionZ * 64 + 63
                );
                return structures.isEmpty() ? null : structures.get(0);
            }

            if (!(set.placement() instanceof RandomSpreadStructurePlacement placement)) return null;

            ChunkPos chunk = placement.getPotentialStructureChunk(seed, regionX * placement.spacing(), regionZ * placement.spacing());
            if (!isAllowedByPlacement(seed, setKey, placement, chunk)) return null;
            return createIfViable(seed, type.dimension, set, placement, chunk, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addForSet(List<GeneratedStructure> results, long seed, ResourceKey<StructureSet> key, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        addForSet(results, seed, 0, key, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, null);
    }

    private static void addForSet(List<GeneratedStructure> results, long seed, ResourceKey<StructureSet> key, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ, String exactStructureId) {
        addForSet(results, seed, 0, key, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, exactStructureId);
    }

    private static void addForSet(List<GeneratedStructure> results, long seed, int dimension, ResourceKey<StructureSet> key, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        addForSet(results, seed, dimension, key, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, null);
    }

    private static void addForSet(List<GeneratedStructure> results, long seed, int dimension, ResourceKey<StructureSet> key, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ, String exactStructureId) {
        try {
            Holder<StructureSet> holder = WorldgenEngine.vanillaLookup()
                .lookupOrThrow(Registries.STRUCTURE_SET)
                .getOrThrow(key);
            StructureSet set = holder.value();
            if (!(set.placement() instanceof RandomSpreadStructurePlacement placement)) return;

            int spacing = placement.spacing();
            int regionMinX = Math.floorDiv(chunkMinX, spacing);
            int regionMinZ = Math.floorDiv(chunkMinZ, spacing);
            int regionMaxX = Math.floorDiv(chunkMaxX, spacing);
            int regionMaxZ = Math.floorDiv(chunkMaxZ, spacing);

            for (int rz = regionMinZ; rz <= regionMaxZ; rz++) {
                for (int rx = regionMinX; rx <= regionMaxX; rx++) {
                    ChunkPos chunk = placement.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    if (chunk.x() < chunkMinX || chunk.x() > chunkMaxX || chunk.z() < chunkMinZ || chunk.z() > chunkMaxZ) continue;
                    if (!isAllowedByPlacement(seed, key, placement, chunk)) continue;

                    GeneratedStructure structure = createIfViable(seed, dimension, set, placement, chunk, exactStructureId);
                    if (structure != null) results.add(structure);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static GeneratedStructure createIfViable(long seed, int dimension, StructureSet set, StructurePlacement placement, ChunkPos chunk, String exactStructureId) {
        StructureSet.StructureSelectionEntry selected = selectViableEntry(seed, dimension, chunk, set, exactStructureId);
        if (selected == null) return null;

        for (StructureSet.StructureSelectionEntry entry : List.of(selected)) {
            String structureId = structureId(entry.structure());
            if (exactStructureId != null && !structureId.equals(exactStructureId)) continue;
            StructureType type = typeFor(structureId);
            if (type != null) {
                BlockPos locatePos = placement.getLocatePos(chunk);
                return withDetails(seed, chunk, locatePos, type, structureId);
            }
        }

        return null;
    }

    private static StructureSet.StructureSelectionEntry selectViableEntry(long seed, int dimension, ChunkPos chunk,
                                                                           StructureSet set, String exactStructureId) {
        if (exactStructureId != null) {
            StructureSet.StructureSelectionEntry entry = chooseEntry(seed, chunk, set, exactStructureId);
            return entry != null && isViableEntry(seed, dimension, chunk, entry) ? entry : null;
        }

        StructureSet.StructureSelectionEntry selected = chooseEntry(seed, chunk, set, null);
        if (selected != null && isViableEntry(seed, dimension, chunk, selected)) return selected;

        for (StructureSet.StructureSelectionEntry entry : set.structures()) {
            if (entry == selected) continue;
            if (isViableEntry(seed, dimension, chunk, entry)) return entry;
        }
        return null;
    }

    private static boolean isViableEntry(long seed, int dimension, ChunkPos chunk, StructureSet.StructureSelectionEntry entry) {
        String structureId = structureId(entry.structure());
        StructureType type = typeFor(structureId);
        if (type == null) return false;
        if (!structureId.equals("minecraft:stronghold")
            && !isValidStructureCandidate(seed, dimension, chunk, entry.structure(), structureId)) {
            return false;
        }
        return !requiresStrictStartValidation(type) || hasValidSelectedStart(seed, dimension, structureId, chunk);
    }

    private static boolean requiresStrictStartValidation(StructureType type) {
        return switch (type) {
            case DESERT_PYRAMID, JUNGLE_TEMPLE, IGLOO, WITCH_HUT, RUINED_PORTAL,
                 SHIPWRECK, OCEAN_RUIN, TREASURE -> true;
            default -> false;
        };
    }

    private static boolean hasValidSelectedStart(long seed, int dimension, String structureId, ChunkPos chunk) {
        ResourceKey<Structure> key = exactStructureKey(structureId);
        if (key == null) return true;
        try {
            StructureStart start = WorldgenEngine.generateSelectedStructureStart(seed, dimension, key, chunk);
            return start != null && start.isValid();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ResourceKey<Structure> exactStructureKey(String structureId) {
        return switch (structureId) {
            case "minecraft:desert_pyramid" -> BuiltinStructures.DESERT_PYRAMID;
            case "minecraft:jungle_pyramid" -> BuiltinStructures.JUNGLE_TEMPLE;
            case "minecraft:swamp_hut" -> BuiltinStructures.SWAMP_HUT;
            case "minecraft:igloo" -> BuiltinStructures.IGLOO;
            case "minecraft:ruined_portal" -> BuiltinStructures.RUINED_PORTAL_STANDARD;
            case "minecraft:ruined_portal_desert" -> BuiltinStructures.RUINED_PORTAL_DESERT;
            case "minecraft:ruined_portal_jungle" -> BuiltinStructures.RUINED_PORTAL_JUNGLE;
            case "minecraft:ruined_portal_swamp" -> BuiltinStructures.RUINED_PORTAL_SWAMP;
            case "minecraft:ruined_portal_mountain" -> BuiltinStructures.RUINED_PORTAL_MOUNTAIN;
            case "minecraft:ruined_portal_ocean" -> BuiltinStructures.RUINED_PORTAL_OCEAN;
            case "minecraft:shipwreck" -> BuiltinStructures.SHIPWRECK;
            case "minecraft:shipwreck_beached" -> BuiltinStructures.SHIPWRECK_BEACHED;
            case "minecraft:ocean_ruin_cold" -> BuiltinStructures.OCEAN_RUIN_COLD;
            case "minecraft:ocean_ruin_warm" -> BuiltinStructures.OCEAN_RUIN_WARM;
            case "minecraft:buried_treasure" -> BuiltinStructures.BURIED_TREASURE;
            default -> null;
        };
    }

    public static EndCityCandidate checkEndCityCandidate(long seed, int chunkX, int chunkZ) {
        try {
            Holder<StructureSet> holder = WorldgenEngine.vanillaLookup()
                .lookupOrThrow(Registries.STRUCTURE_SET)
                .getOrThrow(BuiltinStructureSets.END_CITIES);
            StructureSet set = holder.value();
            if (!(set.placement() instanceof RandomSpreadStructurePlacement placement)) {
                return new EndCityCandidate(chunkX, chunkZ, chunkX * 16, chunkZ * 16, false, false, false, false, "placement_not_random_spread");
            }

            ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
            boolean placementChunk = placement.getPotentialStructureChunk(seed, chunkX, chunkZ).equals(chunk);
            boolean allowed = placementChunk && isAllowedByPlacement(seed, BuiltinStructureSets.END_CITIES, placement, chunk);
            StructureSet.StructureSelectionEntry entry = chooseEntry(seed, chunk, set, "minecraft:end_city");
            BlockPos generationPos = allowed && entry != null ? endCityGenerationPos(seed, chunk, entry.structure()) : null;
            boolean biome = generationPos != null;
            boolean generation = generationPos != null;
            BlockPos locate = generationPos == null ? placement.getLocatePos(chunk) : generationPos;
            int terrainY = generationPos == null ? endCityTerrainY(seed, chunk) : generationPos.getY();
            String startBiome = terrainY == Integer.MIN_VALUE
                ? "n/a"
                : WorldgenEngine.getBiome(seed, 1, chunk.getBlockX(7), terrainY, chunk.getBlockZ(7)).id();
            String sampleBiome = WorldgenEngine.getBiome(seed, 1, chunk.getBlockX(7), 64, chunk.getBlockZ(7)).id();
            String note = (generation ? "valid" : "rejected")
                + " y=" + (terrainY == Integer.MIN_VALUE ? "n/a" : terrainY)
                + " start_biome=" + startBiome
                + " sample_biome=" + sampleBiome;
            return new EndCityCandidate(chunkX, chunkZ, locate.getX(), locate.getZ(), placementChunk, allowed, biome, generation, note);
        } catch (Throwable throwable) {
            return new EndCityCandidate(chunkX, chunkZ, chunkX * 16, chunkZ * 16, false, false, false, false, throwableSummary(throwable));
        }
    }

    public static List<EndCityCandidate> debugEndCities(long seed, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        List<EndCityCandidate> results = new ArrayList<>();
        try {
            Holder<StructureSet> holder = WorldgenEngine.vanillaLookup()
                .lookupOrThrow(Registries.STRUCTURE_SET)
                .getOrThrow(BuiltinStructureSets.END_CITIES);
            StructureSet set = holder.value();
            if (!(set.placement() instanceof RandomSpreadStructurePlacement placement)) return results;

            int spacing = placement.spacing();
            int regionMinX = Math.floorDiv(chunkMinX, spacing);
            int regionMinZ = Math.floorDiv(chunkMinZ, spacing);
            int regionMaxX = Math.floorDiv(chunkMaxX, spacing);
            int regionMaxZ = Math.floorDiv(chunkMaxZ, spacing);

            for (int rz = regionMinZ; rz <= regionMaxZ; rz++) {
                for (int rx = regionMinX; rx <= regionMaxX; rx++) {
                    ChunkPos chunk = placement.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    if (chunk.x() < chunkMinX || chunk.x() > chunkMaxX || chunk.z() < chunkMinZ || chunk.z() > chunkMaxZ) continue;
                    results.add(checkEndCityCandidate(seed, chunk.x(), chunk.z()));
                }
            }
        } catch (Throwable throwable) {
            results.add(new EndCityCandidate(0, 0, 0, 0, false, false, false, false, throwableSummary(throwable)));
        }
        return results;
    }

    private static String throwableSummary(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 5) {
            if (!builder.isEmpty()) builder.append(" -> ");
            builder.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                builder.append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }
        return builder.toString();
    }

    private static StructureSet.StructureSelectionEntry chooseEntry(long seed, ChunkPos chunk, StructureSet set, String exactStructureId) {
        if (exactStructureId != null) {
            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                if (structureId(entry.structure()).equals(exactStructureId)) return entry;
            }
            return null;
        }

        int totalWeight = 0;
        for (StructureSet.StructureSelectionEntry entry : set.structures()) {
            totalWeight += entry.weight();
        }
        if (totalWeight <= 0) return null;

        WorldgenRandom random = chunkGenerateRandom(seed, chunk);
        int roll = random.nextInt(totalWeight);
        for (StructureSet.StructureSelectionEntry entry : set.structures()) {
            roll -= entry.weight();
            if (roll < 0) return entry;
        }
        return set.structures().isEmpty() ? null : set.structures().get(0);
    }

    private static GeneratedStructure withDetails(long seed, ChunkPos chunk, BlockPos locatePos, StructureType type, String structureId) {
        if (type == StructureType.BASTION) {
            return new GeneratedStructure(locatePos.getX(), locatePos.getZ(), chunk.x(), chunk.z(),
                type, bastionVariant(seed, chunk), false);
        }
        if (type == StructureType.END_CITY) {
            try {
                Holder<StructureSet> holder = WorldgenEngine.vanillaLookup()
                    .lookupOrThrow(Registries.STRUCTURE_SET)
                    .getOrThrow(BuiltinStructureSets.END_CITIES);
                StructureSet.StructureSelectionEntry entry = chooseEntry(seed, chunk, holder.value(), "minecraft:end_city");
                if (entry != null) {
                    BlockPos generated = endCityGenerationPos(seed, chunk, entry.structure());
                    if (generated != null) locatePos = generated;
                }
            } catch (Throwable ignored) {
            }
            boolean hasShip = endCityLikelyHasShip(seed, chunk);
            return new GeneratedStructure(locatePos.getX(), locatePos.getZ(), chunk.x(), chunk.z(),
                type, hasShip ? "Ship" : "", hasShip);
        }
        String variant = selectedStructureVariant(type, structureId);
        return new GeneratedStructure(locatePos.getX(), locatePos.getZ(), chunk.x(), chunk.z(),
            type, variant, false);
    }

    private static String selectedStructureVariant(StructureType type, String structureId) {
        if (type == StructureType.RUINED_PORTAL
            || type == StructureType.SHIPWRECK
            || type == StructureType.OCEAN_RUIN
            || type == StructureType.VILLAGE) {
            return structureId;
        }
        return "";
    }

    private static void addStrongholds(List<GeneratedStructure> results, long seed, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        try {
            Holder<StructureSet> holder = WorldgenEngine.vanillaLookup()
                .lookupOrThrow(Registries.STRUCTURE_SET)
                .getOrThrow(BuiltinStructureSets.STRONGHOLDS);
            StructureSet set = holder.value();
            if (!(set.placement() instanceof ConcentricRingsStructurePlacement placement)) return;

            for (ChunkPos chunk : strongholdPositions(seed, placement)) {
                if (chunk.x() < chunkMinX || chunk.x() > chunkMaxX || chunk.z() < chunkMinZ || chunk.z() > chunkMaxZ) continue;
                GeneratedStructure structure = createIfViable(seed, 0, set, placement, chunk, "minecraft:stronghold");
                if (structure != null) results.add(structure);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isAllowedByPlacement(long seed, ResourceKey<StructureSet> key, RandomSpreadStructurePlacement placement, ChunkPos chunk) {
        if (!placement.applyAdditionalChunkRestrictions(chunk.x(), chunk.z(), seed)) return false;
        if (key == BuiltinStructureSets.PILLAGER_OUTPOSTS && hasVillageNear(seed, chunk, 10)) return false;
        return true;
    }

    private static boolean hasVillageNear(long seed, ChunkPos center, int chunkRadius) {
        try {
            Holder<StructureSet> holder = WorldgenEngine.vanillaLookup()
                .lookupOrThrow(Registries.STRUCTURE_SET)
                .getOrThrow(BuiltinStructureSets.VILLAGES);
            StructureSet set = holder.value();
            if (!(set.placement() instanceof RandomSpreadStructurePlacement placement)) return false;

            int spacing = placement.spacing();
            int regionMinX = Math.floorDiv(center.x() - chunkRadius, spacing);
            int regionMinZ = Math.floorDiv(center.z() - chunkRadius, spacing);
            int regionMaxX = Math.floorDiv(center.x() + chunkRadius, spacing);
            int regionMaxZ = Math.floorDiv(center.z() + chunkRadius, spacing);

            for (int rz = regionMinZ; rz <= regionMaxZ; rz++) {
                for (int rx = regionMinX; rx <= regionMaxX; rx++) {
                    ChunkPos villageChunk = placement.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    if (Math.abs(villageChunk.x() - center.x()) > chunkRadius || Math.abs(villageChunk.z() - center.z()) > chunkRadius) continue;
                    if (createIfViable(seed, 0, set, placement, villageChunk, null) != null) return true;
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static void addEndGateways(List<GeneratedStructure> results, long seed, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
            for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
                if ((long) cx * cx + (long) cz * cz < 64L * 64L) continue;
                WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
                random.setLargeFeatureWithSalt(seed, cx, cz, StructureType.END_GATEWAY.salt);
                if (random.nextFloat() >= StructureType.END_GATEWAY.rarity) continue;
                results.add(new GeneratedStructure(cx * 16 + random.nextInt(16), cz * 16 + random.nextInt(16), StructureType.END_GATEWAY));
            }
        }
    }

    private static void addDebugForSet(List<DebugCandidate> results, long seed, ResourceKey<StructureSet> key, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        try {
            Holder<StructureSet> holder = WorldgenEngine.vanillaLookup()
                .lookupOrThrow(Registries.STRUCTURE_SET)
                .getOrThrow(key);
            StructureSet set = holder.value();
            if (!(set.placement() instanceof RandomSpreadStructurePlacement placement)) return;

            int spacing = placement.spacing();
            int regionMinX = Math.floorDiv(chunkMinX, spacing);
            int regionMinZ = Math.floorDiv(chunkMinZ, spacing);
            int regionMaxX = Math.floorDiv(chunkMaxX, spacing);
            int regionMaxZ = Math.floorDiv(chunkMaxZ, spacing);

            for (int rz = regionMinZ; rz <= regionMaxZ; rz++) {
                for (int rx = regionMinX; rx <= regionMaxX; rx++) {
                    ChunkPos chunk = placement.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    if (chunk.x() < chunkMinX || chunk.x() > chunkMaxX || chunk.z() < chunkMinZ || chunk.z() > chunkMaxZ) continue;

                    for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                        String structureId = structureId(entry.structure());
                        StructureType type = typeFor(structureId);
                        if (type == null) continue;
                        Holder<Biome> biomeHolder = biomeHolderAtGenerationPoint(seed, chunk, structureId);
                        PredictedBiome biome = WorldgenEngine.fromRuntimeBiome(biomeHolder);
                        BlockPos locatePos = placement.getLocatePos(chunk);
                        results.add(new DebugCandidate(
                            structureId,
                            locatePos.getX(),
                            locatePos.getZ(),
                            biome.id(),
                            isValidBiome(entry.structure(), biomeHolder, structureId, biome.id())
                        ));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void addDebugStrongholds(List<DebugCandidate> results, long seed, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        try {
            Holder<StructureSet> holder = WorldgenEngine.vanillaLookup()
                .lookupOrThrow(Registries.STRUCTURE_SET)
                .getOrThrow(BuiltinStructureSets.STRONGHOLDS);
            StructureSet set = holder.value();
            if (!(set.placement() instanceof ConcentricRingsStructurePlacement placement)) return;

            for (ChunkPos chunk : strongholdPositions(seed, placement)) {
                if (chunk.x() < chunkMinX || chunk.x() > chunkMaxX || chunk.z() < chunkMinZ || chunk.z() > chunkMaxZ) continue;

                for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                    String structureId = structureId(entry.structure());
                    Holder<Biome> biomeHolder = biomeHolderAtGenerationPoint(seed, chunk, structureId);
                    PredictedBiome biome = WorldgenEngine.fromRuntimeBiome(biomeHolder);
                    BlockPos locatePos = placement.getLocatePos(chunk);
                    results.add(new DebugCandidate(
                        structureId,
                        locatePos.getX(),
                        locatePos.getZ(),
                        biome.id(),
                        isValidBiome(entry.structure(), biomeHolder, structureId, biome.id())
                    ));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static List<ChunkPos> strongholdPositions(long seed, ConcentricRingsStructurePlacement placement) {
        try {
            List<ChunkPos> vanilla = WorldgenEngine.structureState(seed).getRingPositionsFor(placement);
            if (vanilla != null && !vanilla.isEmpty()) return vanilla;
        } catch (Throwable ignored) {
        }

        return fallbackStrongholdPositions(seed, placement);
    }

    private static List<ChunkPos> fallbackStrongholdPositions(long seed, ConcentricRingsStructurePlacement placement) {
        List<ChunkPos> current = fallbackStrongholdPositions;
        if (fallbackStrongholdSeed == seed && !current.isEmpty()) return current;

        synchronized (STRONGHOLD_FALLBACK_LOCK) {
            current = fallbackStrongholdPositions;
            if (fallbackStrongholdSeed == seed && !current.isEmpty()) return current;

            List<ChunkPos> created = createFallbackStrongholdPositions(seed, placement);
            fallbackStrongholdSeed = seed;
            fallbackStrongholdPositions = created;
            return created;
        }
    }

    private static List<ChunkPos> createFallbackStrongholdPositions(long seed, ConcentricRingsStructurePlacement placement) {
        int distance = placement.distance();
        int count = placement.count();
        List<ChunkPos> results = new ArrayList<>(count);
        int spread = placement.spread();
        RandomSource random = RandomSource.create();
        random.setSeed(seed);
        double angle = random.nextDouble() * Math.PI * 2.0;
        int placedInRing = 0;
        int ring = 0;

        for (int i = 0; i < count; i++) {
            double radius = 4 * distance + distance * ring * 6 + (random.nextDouble() - 0.5) * distance * 2.5;
            int ringX = (int) Math.round(Math.cos(angle) * radius);
            int ringZ = (int) Math.round(Math.sin(angle) * radius);
            results.add(findNearestStrongholdBiome(seed, ringX, ringZ, placement, random.fork()));

            angle += Math.PI * 2.0 / spread;
            placedInRing++;
            if (placedInRing == spread) {
                ring++;
                placedInRing = 0;
                spread += 2 * spread / (ring + 1);
                spread = Math.min(spread, count - i);
                angle += random.nextDouble() * Math.PI * 2.0;
            }
        }

        return List.copyOf(results);
    }

    private static ChunkPos findNearestStrongholdBiome(long seed, int chunkX, int chunkZ, ConcentricRingsStructurePlacement placement, RandomSource random) {
        try {
            WorldgenEngine.EngineView view = WorldgenEngine.view(seed, 0);
            Pair<BlockPos, Holder<Biome>> pair = view.biomeSource().findBiomeHorizontal(
                SectionPos.sectionToBlockCoord(chunkX, 8),
                0,
                SectionPos.sectionToBlockCoord(chunkZ, 8),
                112,
                placement.preferredBiomes()::contains,
                random,
                view.sampler()
            );

            if (pair == null) return new ChunkPos(chunkX, chunkZ);

            BlockPos pos = pair.getFirst();
            return new ChunkPos(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        } catch (Throwable ignored) {
            return new ChunkPos(chunkX, chunkZ);
        }
    }

    private static boolean isValidBiome(long seed, ChunkPos chunk, Holder<Structure> structure, String structureId) {
        return isValidBiome(seed, 0, chunk, structure, structureId);
    }

    private static boolean isValidBiome(long seed, int dimension, ChunkPos chunk, Holder<Structure> structure, String structureId) {
        if (structureId.startsWith("minecraft:village_")) {
            return isValidVillageBiome(seed, chunk, structure, structureId);
        }

        if (structureId.equals("minecraft:trial_chambers")) {
            return isValidTrialChambersBiome(seed, chunk, structure, structureId);
        }

        if (structureId.equals("minecraft:bastion_remnant")) {
            return isValidBastionBiome(seed, chunk, structure, structureId);
        }

        Holder<Biome> biome = biomeHolderAtGenerationPoint(seed, dimension, chunk, structureId);
        return isValidBiome(structure, biome, structureId, WorldgenEngine.fromRuntimeBiome(biome).id());
    }

    private static boolean isValidVillageBiome(long seed, ChunkPos chunk, Holder<Structure> structure, String structureId) {
        VillageVariant variant = villageVariant(seed, chunk, structureId);
        if (variant == null) return false;

        Holder<Biome> biome = WorldgenEngine.getBiomeHolder(
            seed,
            0,
            blockFromQuart(jigsawSampleQuart(chunk.x(), variant.x(), variant.sx())),
            blockFromQuart(319 >> 2),
            blockFromQuart(jigsawSampleQuart(chunk.z(), variant.z(), variant.sz()))
        );
        return isValidBiome(structure, biome, structureId, WorldgenEngine.fromRuntimeBiome(biome).id());
    }

    private static boolean isValidTrialChambersBiome(long seed, ChunkPos chunk, Holder<Structure> structure, String structureId) {
        TrialVariant variant = trialVariant(seed, chunk);
        Holder<Biome> biome = WorldgenEngine.getBiomeHolder(
            seed,
            0,
            blockFromQuart(jigsawSampleQuart(chunk.x(), variant.x(), variant.sx())),
            blockFromQuart(variant.y() >> 2),
            blockFromQuart(jigsawSampleQuart(chunk.z(), variant.z(), variant.sz()))
        );
        return isValidBiome(structure, biome, structureId, WorldgenEngine.fromRuntimeBiome(biome).id());
    }

    private static boolean isValidBastionBiome(long seed, ChunkPos chunk, Holder<Structure> structure, String structureId) {
        BastionVariant variant = bastionVariantData(seed, chunk);
        Holder<Biome> biome = WorldgenEngine.getBiomeHolder(
            seed,
            -1,
            blockFromQuart(jigsawSampleQuart(chunk.x(), variant.x(), variant.sx())),
            blockFromQuart(33 >> 2),
            blockFromQuart(jigsawSampleQuart(chunk.z(), variant.z(), variant.sz()))
        );
        return isValidBiome(structure, biome, structureId, WorldgenEngine.fromRuntimeBiome(biome).id());
    }

    private static boolean isValidStructureCandidate(long seed, int dimension, ChunkPos chunk, Holder<Structure> structure, String structureId) {
        if (structureId.equals("minecraft:end_city")) return endCityGenerationPos(seed, chunk, structure) != null;
        if (!isValidBiome(seed, dimension, chunk, structure, structureId)) return false;
        return true;
    }

    private static BlockPos endCityGenerationPos(long seed, ChunkPos chunk, Holder<Structure> structure) {
        WorldgenRandom random = chunkGenerateRandom(seed, chunk);
        return endCityGenerationPos(seed, chunk, Rotation.getRandom(random));
    }

    private static BlockPos endCityGenerationPos(long seed, ChunkPos chunk, Rotation rotation) {
        try {
            WorldgenEngine.GeneratorView view = WorldgenEngine.generatorView(seed, 1);
            LevelHeightAccessor height = LevelHeightAccessor.create(view.noiseSettings().minY(), view.noiseSettings().height());
            int blockX = chunk.getBlockX(7);
            int blockZ = chunk.getBlockZ(7);
            int[] offset = endCityRotationOffset(rotation);
            int y = endCityLowestY(view, height, blockX, blockZ, offset[0], offset[1]);
            if (y < 60) return null;
            String biomeId = WorldgenEngine.getBiome(seed, 1, blockX, y, blockZ).id();
            return isValidBiome("minecraft:end_city", biomeId) ? new BlockPos(blockX, y, blockZ) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int endCityTerrainY(long seed, ChunkPos chunk) {
        try {
            WorldgenEngine.GeneratorView view = WorldgenEngine.generatorView(seed, 1);
            LevelHeightAccessor height = LevelHeightAccessor.create(view.noiseSettings().minY(), view.noiseSettings().height());
            int blockX = chunk.getBlockX(7);
            int blockZ = chunk.getBlockZ(7);
            WorldgenRandom random = chunkGenerateRandom(seed, chunk);
            int[] offset = endCityRotationOffset(Rotation.getRandom(random));
            return endCityLowestY(view, height, blockX, blockZ, offset[0], offset[1]);
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static int[] endCityRotationOffset(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[] {-5, 5};
            case CLOCKWISE_180 -> new int[] {-5, -5};
            case COUNTERCLOCKWISE_90 -> new int[] {5, -5};
            default -> new int[] {5, 5};
        };
    }

    private static int endCityLowestY(WorldgenEngine.GeneratorView view, LevelHeightAccessor height, int blockX, int blockZ, int offsetX, int offsetZ) {
        return Math.min(
            Math.min(
                view.chunkGenerator().getFirstOccupiedHeight(blockX, blockZ, Heightmap.Types.WORLD_SURFACE_WG, height, view.randomState()),
                view.chunkGenerator().getFirstOccupiedHeight(blockX, blockZ + offsetZ, Heightmap.Types.WORLD_SURFACE_WG, height, view.randomState())
            ),
            Math.min(
                view.chunkGenerator().getFirstOccupiedHeight(blockX + offsetX, blockZ, Heightmap.Types.WORLD_SURFACE_WG, height, view.randomState()),
                view.chunkGenerator().getFirstOccupiedHeight(blockX + offsetX, blockZ + offsetZ, Heightmap.Types.WORLD_SURFACE_WG, height, view.randomState())
            )
        );
    }

    private static PredictedBiome biomeAtGenerationPoint(long seed, ChunkPos chunk, String structureId) {
        return WorldgenEngine.getBiome(
            seed,
            0,
            sampleBlockX(chunk, structureId),
            sampleBlockY(structureId),
            sampleBlockZ(chunk, structureId)
        );
    }

    private static Holder<Biome> biomeHolderAtGenerationPoint(long seed, ChunkPos chunk, String structureId) {
        return biomeHolderAtGenerationPoint(seed, 0, chunk, structureId);
    }

    private static Holder<Biome> biomeHolderAtGenerationPoint(long seed, int dimension, ChunkPos chunk, String structureId) {
        return WorldgenEngine.getBiomeHolder(
            seed,
            dimension,
            sampleBlockX(chunk, structureId),
            sampleBlockY(structureId),
            sampleBlockZ(chunk, structureId)
        );
    }

    private static boolean isValidBiome(Holder<Structure> structure, Holder<Biome> biome, String structureId, String biomeId) {
        if (structure != null && biome != null) {
            try {
                return structure.value().biomes().contains(biome);
            } catch (Throwable ignored) {
            }
        }

        return isValidBiome(structureId, biomeId);
    }

    private static boolean isValidBiome(String structureId, String biomeId) {
        return switch (structureId) {
            case "minecraft:village_plains" -> biomeId.equals("minecraft:plains") || biomeId.equals("minecraft:meadow");
            case "minecraft:village_desert", "minecraft:desert_pyramid" -> biomeId.equals("minecraft:desert");
            case "minecraft:village_savanna" -> biomeId.equals("minecraft:savanna");
            case "minecraft:village_snowy" -> biomeId.equals("minecraft:snowy_plains");
            case "minecraft:village_taiga" -> biomeId.equals("minecraft:taiga");
            case "minecraft:ancient_city" -> biomeId.equals("minecraft:deep_dark");
            case "minecraft:shipwreck" -> isOcean(biomeId);
            case "minecraft:shipwreck_beached" -> biomeId.equals("minecraft:beach") || biomeId.equals("minecraft:snowy_beach");
            case "minecraft:igloo" -> biomeId.equals("minecraft:snowy_taiga")
                || biomeId.equals("minecraft:snowy_plains")
                || biomeId.equals("minecraft:snowy_slopes");
            case "minecraft:jungle_pyramid" -> biomeId.equals("minecraft:jungle")
                || biomeId.equals("minecraft:bamboo_jungle");
            case "minecraft:swamp_hut" -> biomeId.equals("minecraft:swamp");
            case "minecraft:monument" -> isDeepOcean(biomeId);
            case "minecraft:mansion" -> biomeId.equals("minecraft:dark_forest")
                || biomeId.equals("minecraft:pale_garden");
            case "minecraft:ocean_ruin_cold" -> isColdOceanRuinBiome(biomeId);
            case "minecraft:ocean_ruin_warm" -> biomeId.equals("minecraft:lukewarm_ocean")
                || biomeId.equals("minecraft:warm_ocean")
                || biomeId.equals("minecraft:deep_lukewarm_ocean");
            case "minecraft:pillager_outpost" -> isOutpostBiome(biomeId);
            case "minecraft:trail_ruins" -> isTrailRuinsBiome(biomeId);
            case "minecraft:trial_chambers" -> isTrialChambersBiome(biomeId);
            case "minecraft:ruined_portal" -> isStandardPortalBiome(biomeId);
            case "minecraft:ruined_portal_desert" -> biomeId.equals("minecraft:desert");
            case "minecraft:ruined_portal_jungle" -> isJungle(biomeId);
            case "minecraft:ruined_portal_swamp" -> biomeId.equals("minecraft:swamp")
                || biomeId.equals("minecraft:mangrove_swamp");
            case "minecraft:ruined_portal_mountain" -> isBadlands(biomeId)
                || isHill(biomeId)
                || isMountain(biomeId)
                || biomeId.equals("minecraft:savanna_plateau")
                || biomeId.equals("minecraft:windswept_savanna")
                || biomeId.equals("minecraft:stony_shore");
            case "minecraft:ruined_portal_ocean" -> isOcean(biomeId);
            case "minecraft:stronghold" -> isStrongholdBiome(biomeId);
            case "minecraft:mineshaft" -> !isBadlands(biomeId);
            case "minecraft:mineshaft_mesa" -> isBadlands(biomeId);
            case "minecraft:buried_treasure" -> isBeach(biomeId);
            case "minecraft:fortress" -> isNetherBiome(biomeId);
            case "minecraft:bastion_remnant" -> isBastionBiome(biomeId);
            case "minecraft:ruined_portal_nether" -> biomeId.equals("minecraft:nether_wastes");
            case "minecraft:nether_fossil" -> biomeId.equals("minecraft:soul_sand_valley");
            case "minecraft:end_city" -> biomeId.equals("minecraft:end_midlands")
                || biomeId.equals("minecraft:end_highlands");
            default -> false;
        };
    }

    private static int jigsawSampleQuart(int chunkCoordinate, int variantOffset, int variantSize) {
        return ((chunkCoordinate * 32 + 2 * variantOffset + variantSize - 1) / 2) >> 2;
    }

    private static int blockFromQuart(int quart) {
        return quart << 2;
    }

    private static WorldgenRandom chunkGenerateRandom(long seed, ChunkPos chunk) {
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, chunk.x(), chunk.z());
        return random;
    }

    private static VillageVariant villageVariant(long seed, ChunkPos chunk, String structureId) {
        WorldgenRandom random = chunkGenerateRandom(seed, chunk);
        int rotation = random.nextInt(4);
        VillageVariant variant;

        switch (structureId) {
            case "minecraft:village_plains" -> {
                int t = random.nextInt(204);
                if (t < 50) variant = new VillageVariant(0, 0, 9, 9);
                else if (t < 100) variant = new VillageVariant(0, 0, 10, 10);
                else if (t < 150) variant = new VillageVariant(0, 0, 8, 15);
                else if (t < 200) variant = new VillageVariant(0, 0, 11, 11);
                else if (t < 201) variant = new VillageVariant(0, 0, 9, 9);
                else if (t < 202) variant = new VillageVariant(0, 0, 10, 10);
                else if (t < 203) variant = new VillageVariant(0, 0, 8, 15);
                else variant = new VillageVariant(0, 0, 11, 11);
            }
            case "minecraft:village_desert" -> {
                int t = random.nextInt(250);
                if (t < 98) variant = new VillageVariant(0, 0, 17, 9);
                else if (t < 196) variant = new VillageVariant(0, 0, 12, 12);
                else if (t < 245) variant = new VillageVariant(0, 0, 15, 15);
                else if (t < 247) variant = new VillageVariant(0, 0, 17, 9);
                else if (t < 249) variant = new VillageVariant(0, 0, 12, 12);
                else variant = new VillageVariant(0, 0, 15, 15);
            }
            case "minecraft:village_savanna" -> {
                int t = random.nextInt(459);
                if (t < 100) variant = new VillageVariant(0, 0, 14, 12);
                else if (t < 150) variant = new VillageVariant(0, 0, 11, 11);
                else if (t < 300) variant = new VillageVariant(0, 0, 9, 11);
                else if (t < 450) variant = new VillageVariant(0, 0, 9, 9);
                else if (t < 452) variant = new VillageVariant(0, 0, 14, 12);
                else if (t < 453) variant = new VillageVariant(0, 0, 11, 11);
                else if (t < 456) variant = new VillageVariant(0, 0, 9, 11);
                else variant = new VillageVariant(0, 0, 9, 9);
            }
            case "minecraft:village_taiga" -> {
                int t = random.nextInt(100);
                if (t < 49) variant = new VillageVariant(0, 0, 22, 18);
                else if (t < 98) variant = new VillageVariant(0, 0, 9, 9);
                else if (t < 99) variant = new VillageVariant(0, 0, 22, 18);
                else variant = new VillageVariant(0, 0, 9, 9);
            }
            case "minecraft:village_snowy" -> {
                int t = random.nextInt(306);
                if (t < 100) variant = new VillageVariant(0, 0, 12, 8);
                else if (t < 150) variant = new VillageVariant(0, 0, 11, 9);
                else if (t < 300) variant = new VillageVariant(0, 0, 7, 7);
                else if (t < 302) variant = new VillageVariant(0, 0, 12, 8);
                else if (t < 303) variant = new VillageVariant(0, 0, 11, 9);
                else variant = new VillageVariant(0, 0, 7, 7);
            }
            default -> {
                return null;
            }
        }

        return rotateVillageVariant(rotation, variant);
    }

    private static VillageVariant rotateVillageVariant(int rotation, VillageVariant variant) {
        return switch (rotation) {
            case 1 -> new VillageVariant(1 - variant.sz(), 0, variant.sz(), variant.sx());
            case 2 -> new VillageVariant(1 - variant.sx(), 1 - variant.sz(), variant.sx(), variant.sz());
            case 3 -> new VillageVariant(0, 1 - variant.sx(), variant.sz(), variant.sx());
            default -> variant;
        };
    }

    private static TrialVariant trialVariant(long seed, ChunkPos chunk) {
        WorldgenRandom random = chunkGenerateRandom(seed, chunk);
        int y = random.nextInt(21) - 40;
        int rotation = random.nextInt(4);
        random.nextInt(2);

        return switch (rotation) {
            case 1 -> new TrialVariant(1 - 19, 0, 19, 19, y);
            case 2 -> new TrialVariant(1 - 19, 1 - 19, 19, 19, y);
            case 3 -> new TrialVariant(0, 1 - 19, 19, 19, y);
            default -> new TrialVariant(0, 0, 19, 19, y);
        };
    }

    private static String bastionVariant(long seed, ChunkPos chunk) {
        return switch (bastionVariantData(seed, chunk).start()) {
            case 0 -> "Housing Units";
            case 1 -> "Hoglin Stables";
            case 2 -> "Treasure Room";
            case 3 -> "Bridge";
            default -> "";
        };
    }

    private static BastionVariant bastionVariantData(long seed, ChunkPos chunk) {
        WorldgenRandom random = chunkGenerateRandom(seed, chunk);
        int rotation = random.nextInt(4);
        int start = random.nextInt(4);
        int sx;
        int sz;
        switch (start) {
            case 0 -> {
                sx = 46;
                sz = 46;
            }
            case 1 -> {
                sx = 30;
                sz = 48;
            }
            case 2 -> {
                sx = 38;
                sz = 38;
            }
            default -> {
                sx = 16;
                sz = 32;
            }
        }

        return switch (rotation) {
            case 1 -> new BastionVariant(start, 1 - sz, 0, sz, sx);
            case 2 -> new BastionVariant(start, 1 - sx, 1 - sz, sx, sz);
            case 3 -> new BastionVariant(start, 0, 1 - sx, sz, sx);
            default -> new BastionVariant(start, 0, 0, sx, sz);
        };
    }

    private static boolean endCityLikelyHasShip(long seed, ChunkPos chunk) {
        try {
            WorldgenRandom random = chunkGenerateRandom(seed, chunk);
            Rotation rotation = Rotation.getRandom(random);
            BlockPos generationPos = endCityGenerationPos(seed, chunk, rotation);
            if (generationPos == null) return false;

            List<StructurePiece> pieces = new ArrayList<>();
            EndCityPieces.startHouseTower(
                OfflineWorldgenResources.structureTemplateManager(),
                generationPos,
                rotation,
                pieces,
                random
            );

            for (StructurePiece piece : pieces) {
                if (piece instanceof TemplateStructurePiece templatePiece) {
                    String templateName = templateName(templatePiece);
                    if (templateName != null && templateName.contains("ship")) return true;
                }
            }
        } catch (Throwable ignored) {
            EndCityShipProbe probe = new EndCityShipProbe(chunkGenerateRandom(seed, chunk));
            return probe.hasShip();
        }
        return false;
    }

    private static String templateName(TemplateStructurePiece piece) {
        try {
            Field field = templateNameField;
            if (field == null) {
                field = TemplateStructurePiece.class.getDeclaredField("templateName");
                field.setAccessible(true);
                templateNameField = field;
            }
            Object value = field.get(piece);
            return value instanceof String name ? name : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isOcean(String biomeId) {
        return switch (biomeId) {
            case "minecraft:ocean",
                 "minecraft:deep_ocean",
                 "minecraft:cold_ocean",
                 "minecraft:deep_cold_ocean",
                 "minecraft:frozen_ocean",
                 "minecraft:deep_frozen_ocean",
                 "minecraft:lukewarm_ocean",
                 "minecraft:deep_lukewarm_ocean",
                 "minecraft:warm_ocean" -> true;
            default -> false;
        };
    }

    private static boolean isDeepOcean(String biomeId) {
        return switch (biomeId) {
            case "minecraft:deep_ocean",
                 "minecraft:deep_cold_ocean",
                 "minecraft:deep_frozen_ocean",
                 "minecraft:deep_lukewarm_ocean" -> true;
            default -> false;
        };
    }

    private static boolean isColdOceanRuinBiome(String biomeId) {
        return switch (biomeId) {
            case "minecraft:frozen_ocean",
                 "minecraft:cold_ocean",
                 "minecraft:ocean",
                 "minecraft:deep_frozen_ocean",
                 "minecraft:deep_cold_ocean",
                 "minecraft:deep_ocean" -> true;
            default -> false;
        };
    }

    private static boolean isOutpostBiome(String biomeId) {
        return biomeId.equals("minecraft:desert")
            || biomeId.equals("minecraft:plains")
            || biomeId.equals("minecraft:savanna")
            || biomeId.equals("minecraft:snowy_plains")
            || biomeId.equals("minecraft:taiga")
            || biomeId.equals("minecraft:grove")
            || isMountain(biomeId);
    }

    private static boolean isTrailRuinsBiome(String biomeId) {
        return biomeId.equals("minecraft:taiga")
            || biomeId.equals("minecraft:snowy_taiga")
            || biomeId.equals("minecraft:old_growth_pine_taiga")
            || biomeId.equals("minecraft:old_growth_spruce_taiga")
            || biomeId.equals("minecraft:old_growth_birch_forest")
            || biomeId.equals("minecraft:jungle");
    }

    private static boolean isTrialChambersBiome(String biomeId) {
        return biomeId.startsWith("minecraft:")
            && !biomeId.equals("minecraft:deep_dark")
            && !biomeId.equals("minecraft:the_void")
            && !biomeId.equals("minecraft:nether_wastes")
            && !biomeId.equals("minecraft:crimson_forest")
            && !biomeId.equals("minecraft:warped_forest")
            && !biomeId.equals("minecraft:soul_sand_valley")
            && !biomeId.equals("minecraft:basalt_deltas")
            && !biomeId.equals("minecraft:the_end")
            && !biomeId.equals("minecraft:end_highlands")
            && !biomeId.equals("minecraft:end_midlands")
            && !biomeId.equals("minecraft:end_barrens")
            && !biomeId.equals("minecraft:small_end_islands");
    }

    private static boolean isStandardPortalBiome(String biomeId) {
        return isBeach(biomeId)
            || isRiver(biomeId)
            || isTaiga(biomeId)
            || isForest(biomeId)
            || biomeId.equals("minecraft:mushroom_fields")
            || biomeId.equals("minecraft:ice_spikes")
            || biomeId.equals("minecraft:dripstone_caves")
            || biomeId.equals("minecraft:lush_caves")
            || biomeId.equals("minecraft:savanna")
            || biomeId.equals("minecraft:snowy_plains")
            || biomeId.equals("minecraft:plains")
            || biomeId.equals("minecraft:sunflower_plains");
    }

    private static boolean isBeach(String biomeId) {
        return biomeId.equals("minecraft:beach") || biomeId.equals("minecraft:snowy_beach");
    }

    private static boolean isRiver(String biomeId) {
        return biomeId.equals("minecraft:river") || biomeId.equals("minecraft:frozen_river");
    }

    private static boolean isTaiga(String biomeId) {
        return biomeId.equals("minecraft:taiga")
            || biomeId.equals("minecraft:snowy_taiga")
            || biomeId.equals("minecraft:old_growth_pine_taiga")
            || biomeId.equals("minecraft:old_growth_spruce_taiga");
    }

    private static boolean isForest(String biomeId) {
        return biomeId.equals("minecraft:forest")
            || biomeId.equals("minecraft:flower_forest")
            || biomeId.equals("minecraft:birch_forest")
            || biomeId.equals("minecraft:dark_forest")
            || biomeId.equals("minecraft:pale_garden")
            || biomeId.equals("minecraft:old_growth_birch_forest");
    }

    private static boolean isJungle(String biomeId) {
        return biomeId.equals("minecraft:jungle")
            || biomeId.equals("minecraft:sparse_jungle")
            || biomeId.equals("minecraft:bamboo_jungle");
    }

    private static boolean isBadlands(String biomeId) {
        return biomeId.equals("minecraft:badlands")
            || biomeId.equals("minecraft:eroded_badlands")
            || biomeId.equals("minecraft:wooded_badlands");
    }

    private static boolean isHill(String biomeId) {
        return biomeId.equals("minecraft:windswept_hills")
            || biomeId.equals("minecraft:windswept_gravelly_hills")
            || biomeId.equals("minecraft:windswept_forest");
    }

    private static boolean isMountain(String biomeId) {
        return biomeId.equals("minecraft:meadow")
            || biomeId.equals("minecraft:cherry_grove")
            || biomeId.equals("minecraft:grove")
            || biomeId.equals("minecraft:snowy_slopes")
            || biomeId.equals("minecraft:frozen_peaks")
            || biomeId.equals("minecraft:jagged_peaks")
            || biomeId.equals("minecraft:stony_peaks");
    }

    private static boolean isStrongholdBiome(String biomeId) {
        return !isOcean(biomeId)
            && !biomeId.equals("minecraft:deep_dark")
            && !biomeId.equals("minecraft:the_void");
    }

    private static boolean isNetherBiome(String biomeId) {
        return biomeId.equals("minecraft:nether_wastes")
            || biomeId.equals("minecraft:soul_sand_valley")
            || biomeId.equals("minecraft:crimson_forest")
            || biomeId.equals("minecraft:warped_forest")
            || biomeId.equals("minecraft:basalt_deltas");
    }

    private static boolean isBastionBiome(String biomeId) {
        return biomeId.equals("minecraft:nether_wastes")
            || biomeId.equals("minecraft:soul_sand_valley")
            || biomeId.equals("minecraft:crimson_forest")
            || biomeId.equals("minecraft:warped_forest");
    }

    private static String structureId(Holder<Structure> structure) {
        Optional<ResourceKey<Structure>> key = structure.unwrapKey();
        return key.map(ResourceKey::identifier).map(Identifier::toString).orElse("");
    }

    private static StructureType typeFor(String id) {
        if (id.startsWith("minecraft:village_")) return StructureType.VILLAGE;
        if (id.equals("minecraft:ruined_portal_nether")) return StructureType.NETHER_RUINED_PORTAL;
        if (id.startsWith("minecraft:ruined_portal")) return StructureType.RUINED_PORTAL;
        return switch (id) {
            case "minecraft:desert_pyramid" -> StructureType.DESERT_PYRAMID;
            case "minecraft:jungle_pyramid" -> StructureType.JUNGLE_TEMPLE;
            case "minecraft:swamp_hut" -> StructureType.WITCH_HUT;
            case "minecraft:igloo" -> StructureType.IGLOO;
            case "minecraft:pillager_outpost" -> StructureType.OUTPOST;
            case "minecraft:monument" -> StructureType.MONUMENT;
            case "minecraft:mansion" -> StructureType.MANSION;
            case "minecraft:ancient_city" -> StructureType.ANCIENT_CITY;
            case "minecraft:trial_chambers" -> StructureType.TRIAL_CHAMBER;
            case "minecraft:trail_ruins" -> StructureType.TRAIL_RUINS;
            case "minecraft:shipwreck", "minecraft:shipwreck_beached" -> StructureType.SHIPWRECK;
            case "minecraft:ocean_ruin_cold", "minecraft:ocean_ruin_warm" -> StructureType.OCEAN_RUIN;
            case "minecraft:stronghold" -> StructureType.STRONGHOLD;
            case "minecraft:mineshaft", "minecraft:mineshaft_mesa" -> StructureType.MINESHAFT;
            case "minecraft:buried_treasure" -> StructureType.TREASURE;
            case "minecraft:fortress" -> StructureType.FORTRESS;
            case "minecraft:bastion_remnant" -> StructureType.BASTION;
            case "minecraft:nether_fossil" -> StructureType.NETHER_FOSSIL;
            case "minecraft:end_city" -> StructureType.END_CITY;
            default -> null;
        };
    }

    public static StructureType typeForStructureId(String id) {
        return typeFor(id);
    }

    private static int sampleBlockX(ChunkPos chunk, String structureId) {
        return usesChunkCenterBiomeSample(structureId) ? chunk.getMiddleBlockX() : chunk.getMinBlockX();
    }

    private static int sampleBlockY(String structureId) {
        return switch (structureId) {
            case "minecraft:ancient_city" -> -27;
            case "minecraft:trial_chambers" -> -30;
            case "minecraft:mineshaft", "minecraft:mineshaft_mesa" -> 32;
            case "minecraft:fortress", "minecraft:bastion_remnant", "minecraft:ruined_portal_nether", "minecraft:nether_fossil" -> 33;
            default -> 64;
        };
    }

    private static int sampleBlockZ(ChunkPos chunk, String structureId) {
        return usesChunkCenterBiomeSample(structureId) ? chunk.getMiddleBlockZ() : chunk.getMinBlockZ();
    }

    private static boolean usesChunkCenterBiomeSample(String structureId) {
        return structureId.startsWith("minecraft:village_")
            || structureId.equals("minecraft:trial_chambers")
            || structureId.equals("minecraft:desert_pyramid")
            || structureId.equals("minecraft:jungle_pyramid")
            || structureId.equals("minecraft:swamp_hut")
            || structureId.equals("minecraft:igloo")
            || structureId.equals("minecraft:monument")
            || structureId.equals("minecraft:mansion")
            || structureId.equals("minecraft:trail_ruins")
            || structureId.equals("minecraft:shipwreck")
            || structureId.equals("minecraft:shipwreck_beached")
            || structureId.equals("minecraft:ocean_ruin_cold")
            || structureId.equals("minecraft:ocean_ruin_warm")
            || structureId.equals("minecraft:buried_treasure")
            || structureId.equals("minecraft:end_city");
    }

    private static ResourceKey<StructureSet> setFor(StructureType type) {
        return switch (type) {
            case VILLAGE -> BuiltinStructureSets.VILLAGES;
            case DESERT_PYRAMID -> BuiltinStructureSets.DESERT_PYRAMIDS;
            case JUNGLE_TEMPLE -> BuiltinStructureSets.JUNGLE_TEMPLES;
            case WITCH_HUT -> BuiltinStructureSets.SWAMP_HUTS;
            case IGLOO -> BuiltinStructureSets.IGLOOS;
            case OUTPOST -> BuiltinStructureSets.PILLAGER_OUTPOSTS;
            case MONUMENT -> BuiltinStructureSets.OCEAN_MONUMENTS;
            case MANSION -> BuiltinStructureSets.WOODLAND_MANSIONS;
            case ANCIENT_CITY -> BuiltinStructureSets.ANCIENT_CITIES;
            case TRIAL_CHAMBER -> BuiltinStructureSets.TRIAL_CHAMBERS;
            case TRAIL_RUINS -> BuiltinStructureSets.TRAIL_RUINS;
            case RUINED_PORTAL -> BuiltinStructureSets.RUINED_PORTALS;
            case SHIPWRECK -> BuiltinStructureSets.SHIPWRECKS;
            case OCEAN_RUIN -> BuiltinStructureSets.OCEAN_RUINS;
            case STRONGHOLD -> BuiltinStructureSets.STRONGHOLDS;
            case MINESHAFT -> BuiltinStructureSets.MINESHAFTS;
            case TREASURE -> BuiltinStructureSets.BURIED_TREASURES;
            case FORTRESS, BASTION -> BuiltinStructureSets.NETHER_COMPLEXES;
            case NETHER_RUINED_PORTAL -> BuiltinStructureSets.RUINED_PORTALS;
            case NETHER_FOSSIL -> BuiltinStructureSets.NETHER_FOSSILS;
            case END_CITY -> BuiltinStructureSets.END_CITIES;
            default -> null;
        };
    }

    private static ResourceKey<StructureSet> setForStructureId(String structureId) {
        StructureType type = typeFor(structureId);
        return type == null ? null : setFor(type);
    }

    public static String biomeAt(long seed, int blockX, int blockZ) {
        PredictedBiome biome = WorldgenEngine.getBiome(seed, 0, blockX, 64, blockZ);
        return biome.id();
    }

    public record DebugCandidate(String structureId, int x, int z, String biomeId, boolean validBiome) {
    }

    public record EndCityCandidate(
        int chunkX,
        int chunkZ,
        int x,
        int z,
        boolean placement,
        boolean allowed,
        boolean biome,
        boolean generation,
        String note
    ) {
    }

    private record VillageVariant(int x, int z, int sx, int sz) {
    }

    private record TrialVariant(int x, int z, int sx, int sz, int y) {
    }

    private record BastionVariant(int start, int x, int z, int sx, int sz) {
    }

    private static final class EndCityShipProbe {
        private final RandomSource random;
        private boolean ship;

        private EndCityShipProbe(RandomSource random) {
            this.random = random;
            this.random.nextInt(4);
        }

        private boolean hasShip() {
            genTower(1);
            return ship;
        }

        private boolean genRec(Generator generator, int depth) {
            if (depth > 8) return false;
            EndCityShipProbe branch = new EndCityShipProbe(random.fork());
            branch.ship = ship;
            boolean ok = generator.run(branch, depth);
            if (!ok) return false;
            ship = branch.ship;
            return true;
        }

        private boolean genTower(int depth) {
            random.nextInt(2);
            random.nextInt(2);
            random.nextInt(3);
            int floorCount = 1 + random.nextInt(3);
            boolean floor = false;
            for (int i = 0; i < floorCount; i++) {
                if (i < floorCount - 1 && random.nextBoolean()) floor = true;
            }
            if (floor) {
                for (int i = 0; i < 4; i++) {
                    if (random.nextBoolean()) genRec(EndCityShipProbe::genBridge, depth + 1);
                }
            } else if (depth != 7) {
                return genRec(EndCityShipProbe::genFatTower, depth + 1);
            }
            return true;
        }

        private boolean genBridge(int depth) {
            int floorCount = 1 + random.nextInt(4);
            int y = 0;
            for (int i = 0; i < floorCount; i++) {
                if (random.nextBoolean()) {
                    y = 0;
                    continue;
                }
                random.nextBoolean();
                y = 4;
            }

            if (!ship && random.nextInt(10 - depth) == 0) {
                random.nextInt(8);
                random.nextInt(10);
                ship = true;
            } else {
                if (!genRec(EndCityShipProbe::genHouseTower, depth + 1)) return false;
            }
            return true;
        }

        private boolean genHouseTower(int depth) {
            if (depth > 8) return false;
            int size = random.nextInt(3);
            if (size != 0) genRec(EndCityShipProbe::genTower, depth + 1);
            return true;
        }

        private boolean genFatTower(int depth) {
            for (int j = 0; j < 2 && random.nextInt(3) != 0; j++) {
                for (int i = 0; i < 4; i++) {
                    if (random.nextBoolean()) genRec(EndCityShipProbe::genBridge, depth + 1);
                }
            }
            return true;
        }

        @FunctionalInterface
        private interface Generator {
            boolean run(EndCityShipProbe probe, int depth);
        }
    }
}
