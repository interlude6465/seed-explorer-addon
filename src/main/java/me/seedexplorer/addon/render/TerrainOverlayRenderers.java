package me.seedexplorer.addon.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ServiceLoader;

/** Resolves a frontend overlay implementation without coupling terrain to it. */
final class TerrainOverlayRenderers {
    private static final TerrainOverlayRenderer INSTANCE = ServiceLoader
        .load(TerrainOverlayRenderer.class)
        .findFirst()
        .orElse(Noop.INSTANCE);

    private TerrainOverlayRenderers() {
    }

    static TerrainOverlayRenderer get() {
        return INSTANCE;
    }

    private enum Noop implements TerrainOverlayRenderer {
        INSTANCE;

        private static final ColorCanvas CANVAS = new ColorCanvas() {
            @Override
            public void quad(double x, double y, double width, double height, RgbaColor color) {
            }

            @Override
            public void boxLines(double x, double y, double width, double height, RgbaColor color) {
            }

            @Override
            public void triangle(double x1, double y1, double x2, double y2,
                                 double x3, double y3, RgbaColor color) {
            }

            @Override
            public void close() {
            }
        };

        @Override
        public ColorCanvas beginColors() {
            return CANVAS;
        }

        @Override
        public void textureQuad(GpuTextureResource texture, double x, double y,
                                double width, double height, RgbaColor tint) {
        }

        @Override
        public double textWidth(String text) {
            return 0.0;
        }

        @Override
        public void text(GuiGraphicsExtractor graphics, String text, double x,
                         double y, RgbaColor color) {
        }
    }
}
