package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.PredictionContext;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Ensures exact-key locate queries agree with the full mixed-set predictor. */
public final class ExactStructureQueryParityProbe {
    private static final long[] SEEDS = {0L, 42L, 2026071501L};
    private static final int RADIUS = 128;

    private ExactStructureQueryParityProbe() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        int queries = 0;
        int expectedStructures = 0;
        Set<String> coveredIds = new LinkedHashSet<>();
        for (long seed : SEEDS) {
            List<GeneratedStructure> overworld = VanillaStructurePredictor.predictDimension(
                new PredictionContext(seed, "26.3", 0), -RADIUS, -RADIUS, RADIUS, RADIUS,
                true, true);
            List<GeneratedStructure> nether = VanillaStructurePredictor.predictDimension(
                new PredictionContext(seed, "26.3", -1), -RADIUS, -RADIUS, RADIUS, RADIUS,
                true, true);

            Set<String> exactIds = new LinkedHashSet<>();
            for (GeneratedStructure structure : overworld) {
                if (usesExactVariantId(structure.type) && structure.variant != null
                    && !structure.variant.isBlank()) {
                    exactIds.add(structure.variant);
                }
            }
            exactIds.add("minecraft:fortress");
            exactIds.add("minecraft:bastion_remnant");

            for (String exactId : exactIds) {
                int dimension = exactId.equals("minecraft:fortress")
                    || exactId.equals("minecraft:bastion_remnant") ? -1 : 0;
                List<GeneratedStructure> full = dimension == -1 ? nether : overworld;
                Set<String> expected = keys(full.stream()
                    .filter(structure -> matchesExactId(structure, exactId))
                    .toList());
                Set<String> actual = keys(VanillaStructurePredictor.predictStructure(
                    new PredictionContext(seed, "26.3", dimension), exactId,
                    -RADIUS, -RADIUS, RADIUS, RADIUS));
                if (!actual.equals(expected)) {
                    Set<String> unexpected = new HashSet<>(actual);
                    unexpected.removeAll(expected);
                    Set<String> missing = new HashSet<>(expected);
                    missing.removeAll(actual);
                    throw new AssertionError("exact query mismatch seed=" + seed
                        + " id=" + exactId + " unexpected=" + unexpected
                        + " missing=" + missing);
                }
                queries++;
                expectedStructures += expected.size();
                coveredIds.add(exactId);
            }
        }

        require(coveredIds.contains("minecraft:fortress"), "fortress exact query was not covered");
        require(coveredIds.contains("minecraft:bastion_remnant"), "bastion exact query was not covered");
        require(coveredIds.stream().anyMatch(id -> id.startsWith("minecraft:abandoned_camp_")),
            "no abandoned-camp exact variant was covered");
        require(expectedStructures > 0, "no structures were compared");
        String summary = "Exact structure query parity probe: PASS queries=" + queries
            + " structures=" + expectedStructures + " ids=" + coveredIds;
        System.err.println(summary);
        writeReport(summary);
    }

    private static boolean usesExactVariantId(StructureType type) {
        return type == StructureType.VILLAGE
            || type == StructureType.ABANDONED_CAMP
            || type == StructureType.RUINED_PORTAL
            || type == StructureType.SHIPWRECK
            || type == StructureType.OCEAN_RUIN
            || type == StructureType.MINESHAFT;
    }

    private static boolean matchesExactId(GeneratedStructure structure, String exactId) {
        if (exactId.equals("minecraft:fortress")) return structure.type == StructureType.FORTRESS;
        if (exactId.equals("minecraft:bastion_remnant")) return structure.type == StructureType.BASTION;
        return exactId.equals(structure.variant);
    }

    private static Set<String> keys(List<GeneratedStructure> structures) {
        Set<String> keys = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (GeneratedStructure structure : structures) {
            String key = structure.type + "@" + structure.startChunkX + ',' + structure.startChunkZ;
            if (!keys.add(key)) duplicates.add(key);
        }
        if (!duplicates.isEmpty()) throw new AssertionError("duplicate exact-query results " + duplicates);
        return keys;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void writeReport(String report) {
        try {
            Path path = Path.of("build", "reports", "exact-structure-query-parity.txt");
            Files.createDirectories(path.getParent());
            Files.writeString(path, report + System.lineSeparator());
        } catch (IOException failure) {
            throw new AssertionError("could not write exact-query parity report", failure);
        }
    }
}
