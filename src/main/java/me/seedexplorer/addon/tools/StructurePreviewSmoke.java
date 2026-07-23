package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.preview.StructurePreviewModel;
import me.seedexplorer.addon.preview.StructurePreviewSimulator;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Smoke-checks that the structure preview extractor returns non-empty vanilla block captures. */
public final class StructurePreviewSmoke {
    private static final long SEED = 2026071501L;
    private static final int SEARCH_RADIUS_CHUNKS = 768;
    private static final int MAX_CANDIDATES_PER_TYPE = 50;

    private StructurePreviewSmoke() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> failures = new ArrayList<>();
        check(failures, StructureType.VILLAGE, 0);
        check(failures, StructureType.DESERT_PYRAMID, 0);
        check(failures, StructureType.BASTION, -1);
        check(failures, StructureType.END_CITY, 1);

        boolean passed = failures.isEmpty();
        System.out.println("structure_preview_smoke_pass=" + passed + " failures=" + failures.size());
        writeReport(failures);
        if (!passed) throw new AssertionError(String.join("\n", failures));
    }

    private static void check(List<String> failures, StructureType type, int dimension) {
        List<GeneratedStructure> structures = VanillaStructurePredictor.predictDimension(
            SEED, dimension, -SEARCH_RADIUS_CHUNKS, -SEARCH_RADIUS_CHUNKS,
            SEARCH_RADIUS_CHUNKS, SEARCH_RADIUS_CHUNKS, false, false);
        List<GeneratedStructure> candidates = structures.stream()
            .filter(s -> s.type == type)
            .limit(MAX_CANDIDATES_PER_TYPE)
            .toList();
        if (candidates.isEmpty()) {
            failures.add(type + ": no predicted structure in smoke radius");
            return;
        }

        int rejected = 0;
        for (GeneratedStructure structure : candidates) {
            StructurePreviewModel model = StructurePreviewSimulator.preview(structure, SEED);
            int blocks = model.blocks().size();
            if (blocks == 0) {
                rejected++;
                continue;
            }
            System.out.println("preview type=" + type + " at=" + structure.x + "," + structure.z
                + " blocks=" + blocks + " truncated=" + model.truncated()
                + " rejected_before_match=" + rejected);
            return;
        }
        failures.add(type + ": empty preview candidates_checked=" + candidates.size());
    }

    private static void writeReport(List<String> failures) {
        try {
            Path path = Path.of("build", "reports", "structure-preview-smoke.txt");
            Files.createDirectories(path.getParent());
            Files.writeString(path, String.join("\n", failures));
        } catch (IOException ignored) {
        }
    }
}
