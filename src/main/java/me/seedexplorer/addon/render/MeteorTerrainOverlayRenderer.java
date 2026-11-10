package me.seedexplorer.addon.render;

import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Meteor compatibility implementation of the addon-owned overlay boundary. */
public final class MeteorTerrainOverlayRenderer implements TerrainOverlayRenderer {
    @Override
    public ColorCanvas beginColors() {
        Renderer2D renderer = Renderer2D.COLOR;
        renderer.begin();
        return new ColorCanvas() {
            @Override
            public void quad(double x, double y, double width, double height, RgbaColor color) {
                renderer.quad(x, y, width, height, MeteorColorAdapter.toMeteor(color));
            }

            @Override
            public void boxLines(double x, double y, double width, double height, RgbaColor color) {
                renderer.boxLines(x, y, width, height, MeteorColorAdapter.toMeteor(color));
            }

            @Override
            public void triangle(double x1, double y1, double x2, double y2,
                                 double x3, double y3, RgbaColor color) {
                renderer.triangle(x1, y1, x2, y2, x3, y3,
                    MeteorColorAdapter.toMeteor(color));
            }

            @Override
            public void close() {
                renderer.render();
            }
        };
    }

    @Override
    public void textureQuad(GpuTextureResource texture, double x, double y,
                            double width, double height, RgbaColor tint) {
        Renderer2D renderer = Renderer2D.TEXTURE;
        renderer.begin();
        renderer.texQuad(x, y, width, height, MeteorColorAdapter.toMeteor(tint));
        renderer.render(texture.getTextureView(), texture.getSampler());
    }

    @Override
    public double textWidth(String text) {
        return TextRenderer.get().getWidth(text);
    }

    @Override
    public void text(GuiGraphicsExtractor graphics, String text, double x,
                     double y, RgbaColor color) {
        TextRenderer renderer = TextRenderer.get();
        renderer.begin(graphics, 1.0, false, true);
        renderer.render(text, x, y, MeteorColorAdapter.toMeteor(color));
        renderer.end();
    }
}
