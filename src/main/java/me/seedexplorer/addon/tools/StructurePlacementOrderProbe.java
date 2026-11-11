package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.worldgen.PredictionContext;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Regression for mutable structure placement state across decoration chunks. */
public final class StructurePlacementOrderProbe {
    private static final PrintStream OUT = System.out;
    private static final long SEED = 1L;
    private static final ChunkPos START_CHUNK = new ChunkPos(-700, -126);

    private StructurePlacementOrderProbe() {
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable failure) {
            failure.printStackTrace(OUT);
            System.exit(1);
        }
    }

    private static void run() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        int index = VanillaLootStructureSimulator.decorationIndex(BuiltinStructures.DESERT_PYRAMID);
        StructureStart canonicalFirst = freshStart();
        int initialMinY = firstPieceMinY(canonicalFirst);
        List<ChunkPos> placementChunks = canonicalFirst.getPieces().stream()
            .flatMap(piece -> piece.getBoundingBox().intersectingChunks())
            .distinct()
            .sorted(Comparator.comparingInt(ChunkPos::z).thenComparingInt(ChunkPos::x))
            .toList();
        if (!placementChunks.contains(START_CHUNK) || placementChunks.size() < 2) {
            throw new IllegalStateException("Expected a multi-chunk pyramid containing its start chunk");
        }

        var startChunkContainers = VanillaLootStructureSimulator.simulateStartChunk(
            SEED, canonicalFirst, START_CHUNK.x(), START_CHUNK.z(), index);
        int canonicalMinY = firstPieceMinY(canonicalFirst);
        if (canonicalMinY == initialMinY || startChunkContainers.isEmpty()) {
            throw new IllegalStateException("Start-chunk placement did not lock the pyramid height");
        }

        ChunkPos orderSensitiveChunk = null;
        int alternateMinY = canonicalMinY;
        for (ChunkPos chunk : placementChunks) {
            if (chunk.equals(START_CHUNK)) continue;
            StructureStart alternateFirst = freshStart();
            VanillaLootStructureSimulator.simulateStartChunk(
                SEED, alternateFirst, chunk.x(), chunk.z(), index);
            int candidateMinY = firstPieceMinY(alternateFirst);
            if (candidateMinY != canonicalMinY) {
                orderSensitiveChunk = chunk;
                alternateMinY = candidateMinY;
                break;
            }
        }
        if (orderSensitiveChunk == null) {
            throw new IllegalStateException("Fixture no longer demonstrates first-chunk height sensitivity");
        }

        var canonicalContainers = VanillaLootStructureSimulator.simulate(
            SEED, BuiltinStructures.DESERT_PYRAMID, START_CHUNK.x(), START_CHUNK.z(), index);
        if (!canonicalContainers.equals(startChunkContainers)) {
            throw new IllegalStateException(
                "Full replay diverged from its canonical start-chunk-first placement");
        }

        verifyTerrainFootprintReplay();
        verifyEntitySpawningReplay();
        int iglooChunks = verifyOrderInvariant(
            BuiltinStructures.IGLOO, new ChunkPos(-63, -119));
        int portalChunks = verifyOrderInvariant(
            BuiltinStructures.RUINED_PORTAL_STANDARD, new ChunkPos(-116, -107));
        String campOrder = inspectCampOrderSensitivity();
        String beachedShipOrder = verifyBeachedCandidateSelection();

        OUT.println("structurePlacementOrderProbe passed"
            + " start=" + START_CHUNK.x() + "," + START_CHUNK.z()
            + " canonicalMinY=" + canonicalMinY
            + " alternateFirst=" + orderSensitiveChunk.x() + "," + orderSensitiveChunk.z()
            + " alternateMinY=" + alternateMinY
            + " containers=" + canonicalContainers.size()
            + " iglooChunks=" + iglooChunks
            + " portalChunks=" + portalChunks
            + " campOrder=" + campOrder
            + " beachedShipOrder=" + beachedShipOrder);
    }

    private static void verifyTerrainFootprintReplay() {
        var containers = VanillaLootStructureSimulator.simulate(
            SEED, BuiltinStructures.OCEAN_RUIN_COLD, 10, 184);
        boolean oracleChest = containers.stream().anyMatch(container ->
            container.x() == 158 && container.y() == 48 && container.z() == 2943);
        if (!oracleChest) {
            throw new IllegalStateException(
                "Ocean-ruin footprint replay did not preserve its oracle sea-floor height: "
                    + containers);
        }
    }

    private static void verifyEntitySpawningReplay() {
        // These pieces spawn entities during postProcess. Their entity creation
        // path dereferences ServerLevel.structureManager() even though this probe
        // only captures structure containers.
        VanillaLootStructureSimulator.simulate(
            SEED, BuiltinStructures.SWAMP_HUT, 279, -117);
        VanillaLootStructureSimulator.simulate(
            SEED, BuiltinStructures.VILLAGE_PLAINS, 282, -271);
    }

    private static int verifyOrderInvariant(ResourceKey<Structure> key, ChunkPos startChunk) {
        StructureStart initial = freshStart(key, startChunk);
        List<ChunkPos> chunks = initial.getPieces().stream()
            .flatMap(piece -> piece.getBoundingBox().intersectingChunks())
            .distinct()
            .sorted(Comparator.comparingInt(ChunkPos::z).thenComparingInt(ChunkPos::x))
            .toList();
        if (chunks.isEmpty()) {
            throw new IllegalStateException("No placement chunks for " + key.identifier());
        }

        List<VanillaLootStructureSimulator.SimulatedContainer> canonical =
            VanillaLootStructureSimulator.simulate(
                SEED, key, startChunk.x(), startChunk.z());
        List<VanillaLootStructureSimulator.SimulatedContainer> forward =
            replayChunks(key, startChunk, chunks);
        List<ChunkPos> reverseChunks = new ArrayList<>(chunks);
        Collections.reverse(reverseChunks);
        List<VanillaLootStructureSimulator.SimulatedContainer> reverse =
            replayChunks(key, startChunk, reverseChunks);
        if (!canonical.equals(forward) || !forward.equals(reverse)) {
            throw new IllegalStateException("Placement order changed " + key.identifier()
                + " containers: canonical=" + canonical + " forward=" + forward
                + " reverse=" + reverse);
        }
        return chunks.size();
    }

    private static String inspectCampOrderSensitivity() {
        ResourceKey<Structure> key = ResourceKey.create(Registries.STRUCTURE,
            Identifier.parse("minecraft:abandoned_camp_old_growth_spruce_taiga"));
        return inspectPlacementOrders(key, new ChunkPos(-701, 652));
    }

    private static String verifyBeachedCandidateSelection() {
        PredictionContext context = new PredictionContext(SEED, "26.3", 0);
        List<VanillaLootStructureSimulator.PlacementHistoryCandidate> candidates =
            VanillaLootStructureSimulator.simulateBeachedShipwreckCandidates(
                context, 389, 657);
        if (candidates.size() < 2) {
            throw new IllegalStateException(
                "Beached shipwreck did not expose multiple first-chunk histories");
        }

        Set<String> invariantIdentity = containerIdentity(candidates.getFirst().containers());
        Set<String> coordinateLayouts = new HashSet<>();
        Set<String> seedLayouts = new HashSet<>();
        for (VanillaLootStructureSimulator.PlacementHistoryCandidate candidate : candidates) {
            if (!containerIdentity(candidate.containers()).equals(invariantIdentity)) {
                throw new IllegalStateException(
                    "Beached shipwreck first-chunk history changed x/z/table identity: "
                        + candidates);
            }
            coordinateLayouts.add(candidate.containers().stream()
                .map(container -> container.x() + ":" + container.y() + ":" + container.z())
                .sorted().reduce("", (left, right) -> left + ";" + right));
            seedLayouts.add(candidate.containers().stream()
                .map(container -> container.x() + ":" + container.z() + ":" + container.lootSeed())
                .sorted().reduce("", (left, right) -> left + ";" + right));
        }
        if (coordinateLayouts.size() < 2 || seedLayouts.size() < 2) {
            throw new IllegalStateException(
                "Beached shipwreck history no longer changes both height and placement RNG");
        }

        List<VanillaLootStructureSimulator.ObservedContainerPosition> observed = List.of(
            new VanillaLootStructureSimulator.ObservedContainerPosition(6219, 63, 10529),
            new VanillaLootStructureSimulator.ObservedContainerPosition(6225, 61, 10528),
            new VanillaLootStructureSimulator.ObservedContainerPosition(6235, 61, 10527));
        List<VanillaLootStructureSimulator.PlacementHistoryCandidate> actualGeometry =
            candidates.stream()
                .filter(candidate -> observedPositions(candidate.containers())
                    .equals(Set.copyOf(observed)))
                .toList();
        if (actualGeometry.size() != 2) {
            throw new IllegalStateException(
                "Expected two live-geometry histories, got " + actualGeometry);
        }
        Set<String> actualSeedLayouts = new HashSet<>();
        for (VanillaLootStructureSimulator.PlacementHistoryCandidate candidate : actualGeometry) {
            actualSeedLayouts.add(candidate.containers().stream()
                .map(container -> container.x() + ":" + container.z() + ":" + container.lootSeed())
                .sorted().reduce("", (left, right) -> left + ";" + right));
        }
        if (actualSeedLayouts.size() != 2
            || VanillaLootStructureSimulator.selectUniqueContainerCandidate(
                candidates, observed).isPresent()) {
            throw new IllegalStateException(
                "Same live geometry with different loot seeds was guessed as exact history");
        }

        List<VanillaLootStructureSimulator.ObservedContainerPosition> uniqueObserved = List.of(
            new VanillaLootStructureSimulator.ObservedContainerPosition(6219, 62, 10529),
            new VanillaLootStructureSimulator.ObservedContainerPosition(6225, 60, 10528),
            new VanillaLootStructureSimulator.ObservedContainerPosition(6235, 60, 10527));
        List<VanillaLootStructureSimulator.SimulatedContainer> selected =
            VanillaLootStructureSimulator.selectUniqueContainerCandidate(
                candidates, uniqueObserved)
                .orElseThrow(() -> new IllegalStateException(
                    "Unique beached geometry did not select its history"));
        if (VanillaLootStructureSimulator.selectUniqueContainerCandidate(
            candidates, List.of()).isPresent()) {
            throw new IllegalStateException(
                "Beached history selector accepted empty observation evidence");
        }
        verifyCandidateDeduplication();
        return "candidates=" + candidates.size() + ",selected=" + selected;
    }

    private static Set<VanillaLootStructureSimulator.ObservedContainerPosition> observedPositions(
        List<VanillaLootStructureSimulator.SimulatedContainer> containers) {
        Set<VanillaLootStructureSimulator.ObservedContainerPosition> positions = new HashSet<>();
        for (VanillaLootStructureSimulator.SimulatedContainer container : containers) {
            positions.add(new VanillaLootStructureSimulator.ObservedContainerPosition(
                container.x(), container.y(), container.z()));
        }
        return positions;
    }

    private static void verifyCandidateDeduplication() {
        var observedContainer = new VanillaLootStructureSimulator.SimulatedContainer(
            10, 64, 20, "minecraft:chests/shipwreck_map", 11L);
        var unobservedContainer = new VanillaLootStructureSimulator.SimulatedContainer(
            30, 65, 40, "minecraft:chests/shipwreck_supply", 12L);
        var sameOutputDifferentOrder = List.of(
            new VanillaLootStructureSimulator.PlacementHistoryCandidate(
                new ChunkPos(0, 0), List.of(observedContainer, unobservedContainer)),
            new VanillaLootStructureSimulator.PlacementHistoryCandidate(
                new ChunkPos(0, 1), List.of(unobservedContainer, observedContainer)));
        var observed = List.of(
            new VanillaLootStructureSimulator.ObservedContainerPosition(10, 64, 20));
        if (VanillaLootStructureSimulator.selectUniqueContainerCandidate(
            sameOutputDifferentOrder, observed).isEmpty()) {
            throw new IllegalStateException(
                "Equivalent candidate outputs were treated as ambiguous by list order");
        }

        var changedUnobservedContainer = new VanillaLootStructureSimulator.SimulatedContainer(
            30, 65, 40, "minecraft:chests/shipwreck_supply", 13L);
        var ambiguous = List.of(sameOutputDifferentOrder.getFirst(),
            new VanillaLootStructureSimulator.PlacementHistoryCandidate(
                new ChunkPos(1, 0), List.of(observedContainer, changedUnobservedContainer)));
        if (VanillaLootStructureSimulator.selectUniqueContainerCandidate(
            ambiguous, observed).isPresent()) {
            throw new IllegalStateException(
                "Same-geometry candidates with different loot seeds were incorrectly resolved");
        }
    }

    private static Set<String> containerIdentity(
        List<VanillaLootStructureSimulator.SimulatedContainer> containers) {
        Set<String> identities = new HashSet<>();
        for (VanillaLootStructureSimulator.SimulatedContainer container : containers) {
            identities.add(container.x() + ":" + container.z() + ":"
                + container.lootTableId() + ":" + container.pairedChest());
        }
        return identities;
    }

    private static String inspectPlacementOrders(ResourceKey<Structure> key,
                                                  ChunkPos startChunk) {
        StructureStart initial = freshStart(key, startChunk);
        List<ChunkPos> chunks = initial.getPieces().stream()
            .flatMap(piece -> piece.getBoundingBox().intersectingChunks())
            .distinct()
            .sorted(Comparator.comparingInt(ChunkPos::z).thenComparingInt(ChunkPos::x))
            .toList();
        int index = VanillaLootStructureSimulator.decorationIndex(key);
        List<VanillaLootStructureSimulator.SimulatedContainer> forward =
            VanillaLootStructureSimulator.simulateInChunkOrderForProbe(
                SEED, 0, key, startChunk.x(), startChunk.z(), chunks, index);
        List<ChunkPos> reverseChunks = new ArrayList<>(chunks);
        Collections.reverse(reverseChunks);
        List<VanillaLootStructureSimulator.SimulatedContainer> reverse =
            VanillaLootStructureSimulator.simulateInChunkOrderForProbe(
                SEED, 0, key, startChunk.x(), startChunk.z(), reverseChunks, index);
        return "pieces=" + initial.getPieces() + ";forward=" + forward
            + ";reverse=" + reverse;
    }

    private static List<VanillaLootStructureSimulator.SimulatedContainer> replayChunks(
        ResourceKey<Structure> key, ChunkPos startChunk, List<ChunkPos> chunks) {
        StructureStart start = freshStart(key, startChunk);
        int index = VanillaLootStructureSimulator.decorationIndex(key);
        LinkedHashSet<VanillaLootStructureSimulator.SimulatedContainer> containers =
            new LinkedHashSet<>();
        for (ChunkPos chunk : chunks) {
            containers.addAll(VanillaLootStructureSimulator.simulateStartChunk(
                SEED, start, chunk.x(), chunk.z(), index));
        }
        return containers.stream()
            .sorted(Comparator.comparingInt(VanillaLootStructureSimulator.SimulatedContainer::x)
                .thenComparingInt(VanillaLootStructureSimulator.SimulatedContainer::y)
                .thenComparingInt(VanillaLootStructureSimulator.SimulatedContainer::z))
            .toList();
    }

    private static StructureStart freshStart() {
        return freshStart(BuiltinStructures.DESERT_PYRAMID, START_CHUNK);
    }

    private static StructureStart freshStart(ResourceKey<Structure> key, ChunkPos startChunk) {
        StructureStart start = WorldgenEngine.generateSelectedStructureStart(
            SEED, 0, key, startChunk);
        if (!start.isValid()) {
            throw new IllegalStateException("Regression fixture is no longer valid: "
                + key.identifier() + " at " + startChunk);
        }
        return start;
    }

    private static int firstPieceMinY(StructureStart start) {
        return start.getPieces().getFirst().getBoundingBox().minY();
    }
}
