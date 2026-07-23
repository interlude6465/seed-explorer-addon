package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Smoke-checks that End City predictions produce both ship and no-ship variants. */
public final class EndCityShipPredictionSmoke {
    private static final long[] SEEDS = {
        0L,
        2026071501L,
        -918273645546372819L,
        8675309L,
        314159265358979323L
    };

    private EndCityShipPredictionSmoke() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        long totalEndCities = 0;
        long totalShips = 0;
        long totalNoShips = 0;
        StringBuilder summary = new StringBuilder();
        for (long seed : SEEDS) {
            List<GeneratedStructure> structures = VanillaStructurePredictor.predictDimension(
                seed, 1, -512, -512, 512, 512, false, false);
            long endCities = structures.stream().filter(s -> s.type == StructureType.END_CITY).count();
            long ships = structures.stream().filter(s -> s.type == StructureType.END_CITY && s.hasShip).count();
            long noShips = endCities - ships;
            totalEndCities += endCities;
            totalShips += ships;
            totalNoShips += noShips;
            String line = "seed=" + seed + " end_cities=" + endCities
                + " with_ship=" + ships + " without_ship=" + noShips;
            summary.append(line).append('\n');
            System.out.println(line);
        }

        boolean passed = totalEndCities > 0 && totalShips > 0 && totalNoShips > 0;
        String result = "end_city_ship_prediction_smoke_pass=" + passed
            + " total_end_cities=" + totalEndCities
            + " total_with_ship=" + totalShips
            + " total_without_ship=" + totalNoShips;
        System.out.println(result);
        summary.append(result);
        writeReport(summary.toString());
        if (!passed) throw new AssertionError(summary.toString());
    }

    private static void writeReport(String report) {
        try {
            Path path = Path.of("build", "reports", "end-city-ship-smoke.txt");
            Files.createDirectories(path.getParent());
            Files.writeString(path, report);
        } catch (IOException ignored) {
        }
    }
}
