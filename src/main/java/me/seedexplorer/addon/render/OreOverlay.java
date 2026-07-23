/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.render;

import me.seedexplorer.addon.ore.OrePatch;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.List;

/** Manages overlay rendering for ore distributions on the map. */
public class OreOverlay {
    public void render(MapRenderContext context, List<OrePatch> ores) {
        if (ores.isEmpty()) return;

        MapViewport viewport = context.viewport();
        Renderer2D renderer = Renderer2D.COLOR;
        renderer.begin();

        for (OrePatch patch : ores) {
            double sx = viewport.screenX(patch.x);
            double sy = viewport.screenY(patch.z);
            if (!viewport.isVisible(sx, sy, 20)) continue;

            Color color = withPulse(
                new Color(patch.type.color.r, patch.type.color.g, patch.type.color.b, patch.exact ? context.oreOpacity() : Math.max(35, context.oreOpacity() / 2)),
                context.delta(),
                patch.x,
                patch.z
            );
            double dotSize = Math.min(12, Math.max(3, 6 * viewport.zoom())) * context.oreMarkerScale();
            double half = dotSize / 2.0;
            renderer.quad(sx - half, sy - half, dotSize, dotSize, color);
        }

        renderer.render();
    }

    private Color withPulse(Color color, float delta, int x, int z) {
        double phase = (System.nanoTime() / 1_000_000_000.0) * 2.0 + (x * 13 + z * 29) * 0.001 + delta;
        double factor = 0.7 + (Math.sin(phase) * 0.5 + 0.5) * 0.3;
        int alpha = (int) Math.max(0, Math.min(255, color.a * factor));
        return new Color(color.r, color.g, color.b, alpha);
    }
}
