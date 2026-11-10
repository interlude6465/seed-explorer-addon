package me.seedexplorer.addon.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small frontend boundary for screen-space labels and markers over 3D terrain. */
public interface TerrainOverlayRenderer {
    /** Binds the GUI extraction target used by the following overlay commands. */
    default void beginFrame(GuiGraphicsExtractor graphics) {
    }

    ColorCanvas beginColors();

    void textureQuad(GpuTextureResource texture, double x, double y,
                     double width, double height, RgbaColor tint);

    double textWidth(String text);

    void text(GuiGraphicsExtractor graphics, String text, double x, double y,
              RgbaColor color);

    interface ColorCanvas extends AutoCloseable {
        void quad(double x, double y, double width, double height, RgbaColor color);

        void boxLines(double x, double y, double width, double height, RgbaColor color);

        void triangle(double x1, double y1, double x2, double y2,
                      double x3, double y3, RgbaColor color);

        @Override
        void close();
    }
}
