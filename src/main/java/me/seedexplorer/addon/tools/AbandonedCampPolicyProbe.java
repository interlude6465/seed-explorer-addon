package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.BundledLootTableLoader;
import me.seedexplorer.addon.loot.LootTableDef;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import me.seedexplorer.addon.worldgen.PredictionVersionPolicy;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Lightweight regression checks for the 26.3 abandoned-camp contract. */
public final class AbandonedCampPolicyProbe {
    private static final List<String> BIOMES = List.of(
        "bamboo_jungle",
        "birch_forest",
        "cherry_grove",
        "dappled_forest",
        "flower_forest",
        "forest",
        "meadow",
        "old_growth_birch_forest",
        "old_growth_pine_taiga",
        "old_growth_spruce_taiga",
        "pale_garden",
        "savanna",
        "snowy_taiga",
        "sparse_jungle",
        "swamp",
        "taiga",
        "windswept_forest",
        "wooded_badlands"
    );
    private static final List<LootResource> LOOT_RESOURCES = List.of(
        new LootResource("minecraft:barrels/abandoned_camp_barrel",
            "98F7C131DE780A06A2EE9BCEC32A71B82072A79B8279717E330A536E9BC46F1F"),
        new LootResource("minecraft:chests/abandoned_camp_common_chest",
            "5085AAFC1AA077E5F3A4A96F654DAEDD76C40A98F5E89DAFA7F2B778ACACB497"),
        new LootResource("minecraft:chests/abandoned_camp_secret_chest",
            "EC2369138A4586302D632D29F0F588FD39E12EE26A48AB3B9C1EFEB627EE7197")
    );

    private AbandonedCampPolicyProbe() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        require(StructureType.ABANDONED_CAMP.salt == 91231127,
            "abandoned camp placement salt drifted");
        require(StructureType.ABANDONED_CAMP.regionSize == 37,
            "abandoned camp spacing drifted");
        require(StructureType.ABANDONED_CAMP.chunkRange == 29,
            "abandoned camp separation drifted");
        for (String biome : BIOMES) {
            String id = "minecraft:abandoned_camp_" + biome;
            require(VanillaStructurePredictor.typeForStructureId(id) == StructureType.ABANDONED_CAMP,
                id + " is not mapped");
        }
        require(BundledLootTableLoader.VersionProfile.forVersion("26.3")
                .minecraftVersion().equals("26.3"),
            "26.3 version profile is missing");
        require(BundledLootTableLoader.compareVersions("26.2", "26.3") < 0,
            "26.3 feature gate comparison is broken");
        require(!PredictionVersionPolicy.supportsStructure("26.2", StructureType.ABANDONED_CAMP)
                && PredictionVersionPolicy.supportsStructure("26.3", StructureType.ABANDONED_CAMP),
            "abandoned-camp selected-profile gate is broken");
        verify263WorldgenContent();
        verifyRuntimeStructureSet();
        for (LootResource resource : LOOT_RESOURCES) verifyLootResource(resource);
        System.out.println("Abandoned camp policy probe: PASS");
    }

    private static void verify263WorldgenContent() {
        var lookup = WorldgenEngine.vanillaLookup();
        var biomes = lookup.lookupOrThrow(Registries.BIOME);
        for (String id : List.of("minecraft:dappled_forest", "minecraft:sulfur_caves")) {
            require(biomes.get(ResourceKey.create(Registries.BIOME, Identifier.parse(id))).isPresent(),
                "missing 26.3 biome " + id);
        }

        var features = lookup.lookupOrThrow(Registries.PLACED_FEATURE);
        for (String id : List.of(
            "minecraft:orange_poplar",
            "minecraft:red_poplar",
            "minecraft:yellow_poplar",
            "minecraft:orange_poplar_leaf_litter",
            "minecraft:red_poplar_leaf_litter",
            "minecraft:yellow_poplar_leaf_litter",
            "minecraft:rooted_sulfur_spring",
            "minecraft:sulfur_pool",
            "minecraft:sulfur_spike",
            "minecraft:sulfur_spike_cluster"
        )) {
            require(features.get(ResourceKey.create(Registries.PLACED_FEATURE,
                    Identifier.parse(id))).isPresent(),
                "missing 26.3 placed feature " + id);
        }

        var dappled = biomes.get(ResourceKey.create(Registries.BIOME,
            Identifier.parse("minecraft:dappled_forest"))).orElseThrow();
        require(dappled.value().getGenerationSettings().hasFeature(
                features.get(ResourceKey.create(Registries.PLACED_FEATURE,
                    Identifier.parse("minecraft:trees_dappled_forest"))).orElseThrow().value()),
            "dappled forest is not linked to its poplar tree selector");

        var sulfur = biomes.get(ResourceKey.create(Registries.BIOME,
            Identifier.parse("minecraft:sulfur_caves"))).orElseThrow();
        for (String id : List.of(
            "minecraft:rooted_sulfur_spring",
            "minecraft:sulfur_pool",
            "minecraft:sulfur_spike",
            "minecraft:sulfur_spike_cluster"
        )) {
            var feature = features.get(ResourceKey.create(Registries.PLACED_FEATURE,
                Identifier.parse(id))).orElseThrow();
            require(sulfur.value().getGenerationSettings().hasFeature(feature.value()),
                "sulfur caves is not linked to " + id);
        }
    }

    private static void verifyRuntimeStructureSet() {
        StructureSet set = WorldgenEngine.vanillaLookup()
            .lookupOrThrow(Registries.STRUCTURE_SET)
            .getOrThrow(VanillaStructurePredictor.ABANDONED_CAMP_SET)
            .value();
        require(set.placement() instanceof RandomSpreadStructurePlacement,
            "abandoned camp does not use random-spread placement");
        RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) set.placement();
        require(placement.spacing() == 37, "runtime abandoned-camp spacing drifted");
        require(placement.separation() == 8, "runtime abandoned-camp separation drifted");
        require(placementSalt(placement) == 91231127, "runtime abandoned-camp salt drifted");

        Set<String> actual = set.structures().stream()
            .map(entry -> entry.structure().unwrapKey().orElseThrow().identifier().toString())
            .collect(Collectors.toUnmodifiableSet());
        Set<String> expected = BIOMES.stream()
            .map(biome -> "minecraft:abandoned_camp_" + biome)
            .collect(Collectors.toUnmodifiableSet());
        require(actual.equals(expected), "runtime abandoned-camp variants differ: " + actual);
        require(set.structures().stream().allMatch(entry -> entry.weight() == 1),
            "abandoned-camp variants must all have weight 1");
    }

    private static int placementSalt(RandomSpreadStructurePlacement placement) {
        Class<?> type = placement.getClass();
        while (type != null) {
            try {
                var method = type.getDeclaredMethod("salt");
                method.setAccessible(true);
                return ((Number) method.invoke(placement)).intValue();
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("could not read abandoned-camp placement salt", failure);
            }
        }
        throw new AssertionError("abandoned-camp placement exposes no salt");
    }

    private static void verifyLootResource(LootResource resource) {
        String path = "/data/minecraft/loot_table/"
            + resource.id().substring("minecraft:".length()) + ".json";
        try (InputStream in = AbandonedCampPolicyProbe.class.getResourceAsStream(path)) {
            require(in != null, "missing bundled loot table " + resource.id());
            String digest = java.util.HexFormat.of().withUpperCase()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
            require(digest.equals(resource.sha256()), "bundled loot table drifted: " + resource.id());
        } catch (Exception failure) {
            throw new AssertionError("could not verify " + resource.id(), failure);
        }

        LootTableDef parsed = BundledLootTableLoader.parseFromBundled(
            BundledLootTableLoader.VersionProfile.forVersion("26.3"), resource.id(), resource.id());
        require(parsed != null && !parsed.pools().isEmpty(),
            "bundled loot table did not parse: " + resource.id());
        require(parsed.pools().stream().allMatch(pool -> !pool.entries().isEmpty()),
            "bundled loot table contains an empty parsed pool: " + resource.id());
    }

    private record LootResource(String id, String sha256) {
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
