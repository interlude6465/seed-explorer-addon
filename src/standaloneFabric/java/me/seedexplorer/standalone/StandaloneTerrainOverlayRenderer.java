package me.seedexplorer.standalone;

import me.seedexplorer.addon.render.GpuTextureResource;
import me.seedexplorer.addon.render.RgbaColor;
import me.seedexplorer.addon.render.TerrainOverlayRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Vanilla GUI implementation for terrain status, markers, labels, and chest overlays. */
public final class StandaloneTerrainOverlayRenderer implements TerrainOverlayRenderer {
    private GuiGraphicsExtractor graphics;
    private double guiScaleX = 1.0;
    private double guiScaleY = 1.0;

    @Override
    public void beginFrame(GuiGraphicsExtractor graphics) {
        this.graphics = graphics;
        Minecraft minecraft = Minecraft.getInstance();
        if (graphics == null || minecraft == null || minecraft.getWindow() == null) {
            guiScaleX = 1.0;
            guiScaleY = 1.0;
            return;
        }
        guiScaleX = graphics.guiWidth() / (double) Math.max(1, minecraft.getWindow().getWidth());
        guiScaleY = graphics.guiHeight() / (double) Math.max(1, minecraft.getWindow().getHeight());
    }

    @Override
    public ColorCanvas beginColors() {
        GuiGraphicsExtractor target = graphics;
        return new ColorCanvas() {
            @Override
            public void quad(double x, double y, double width, double height, RgbaColor color) {
                if (target == null) return;
                target.fill(guiX(x), guiY(y), guiX(x + width), guiY(y + height), argb(color));
            }

            @Override
            public void boxLines(double x, double y, double width, double height, RgbaColor color) {
                if (target == null) return;
                int left = guiX(x);
                int top = guiY(y);
                target.outline(left, top, Math.max(1, guiX(x + width) - left),
                    Math.max(1, guiY(y + height) - top), argb(color));
            }

            @Override
            public void triangle(double x1, double y1, double x2, double y2,
                                 double x3, double y3, RgbaColor color) {
                if (target == null) return;
                fillTriangle(target, guiX(x1), guiY(y1), guiX(x2), guiY(y2),
                    guiX(x3), guiY(y3), argb(color));
            }

            @Override
            public void close() {
                // GuiGraphicsExtractor records commands immediately.
            }
        };
    }

    @Override
    public void textureQuad(GpuTextureResource texture, double x, double y,
                            double width, double height, RgbaColor tint) {
        if (graphics == null || texture == null) return;
        int left = guiX(x);
        int top = guiY(y);
        int right = Math.max(left + 1, guiX(x + width));
        int bottom = Math.max(top + 1, guiY(y + height));
        graphics.blit(texture.getTextureView(), texture.getSampler(),
            left, top, right, bottom,
            0.0f, 1.0f, 0.0f, 1.0f);
    }

    @Override
    public double textWidth(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null || minecraft.font == null ? 0.0
            : minecraft.font.width(text) / Math.max(1.0e-6, guiScaleX);
    }

    @Override
    public void text(GuiGraphicsExtractor graphics, String text, double x,
                     double y, RgbaColor color) {
        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphicsExtractor target = graphics == null ? this.graphics : graphics;
        if (target == null || minecraft == null || minecraft.font == null) return;
        target.text(minecraft.font, text, guiX(x), guiY(y), argb(color), true);
    }

    private static void fillTriangle(GuiGraphicsExtractor graphics,
                                     double x1, double y1, double x2, double y2,
                                     double x3, double y3, int color) {
        int minY = (int) Math.floor(Math.min(y1, Math.min(y2, y3)));
        int maxY = (int) Math.ceil(Math.max(y1, Math.max(y2, y3)));
        for (int y = minY; y < maxY; y++) {
            double scanY = y + 0.5;
            double[] intersections = new double[3];
            int count = 0;
            count = intersection(x1, y1, x2, y2, scanY, intersections, count);
            count = intersection(x2, y2, x3, y3, scanY, intersections, count);
            count = intersection(x3, y3, x1, y1, scanY, intersections, count);
            if (count < 2) continue;
            double left = Math.min(intersections[0], intersections[1]);
            double right = Math.max(intersections[0], intersections[1]);
            graphics.fill((int) Math.floor(left), y, (int) Math.ceil(right), y + 1, color);
        }
    }

    private static int intersection(double x1, double y1, double x2, double y2,
                                    double y, double[] output, int count) {
        if (count >= output.length || y1 == y2
            || y < Math.min(y1, y2) || y >= Math.max(y1, y2)) return count;
        output[count] = x1 + (y - y1) * (x2 - x1) / (y2 - y1);
        return count + 1;
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }

    private int guiX(double framebufferX) {
        return round(framebufferX * guiScaleX);
    }

    private int guiY(double framebufferY) {
        return round(framebufferY * guiScaleY);
    }

    private static int argb(RgbaColor color) {
        return ((color.a & 0xFF) << 24) | ((color.r & 0xFF) << 16)
            | ((color.g & 0xFF) << 8) | (color.b & 0xFF);
    }
}
