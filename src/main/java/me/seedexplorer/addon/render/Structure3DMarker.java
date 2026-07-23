package me.seedexplorer.addon.render;

import me.seedexplorer.addon.structures.StructureType;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * A 3D-looking structure marker rendered on the terrain view.
 * Each marker draws a small icon (pyramid, cube, cylinder, or diamond)
 * at the structure's block position with a y-offset for height.
 * Underground markers get a semi-transparent surface indicator.
 * Hover detection and label rendering are built in.
 */
public class Structure3DMarker {
    private static final double ICON_BLOCK_SIZE = 3.0;
    private static final double SURFACE_CIRCLE_RADIUS_BLOCKS = 5.0;
    private static final double LABEL_Y_OFFSET = 14.0;

    public final StructureType type;
    public final int blockX;
    public final int blockZ;
    public final int yOffset;
    public final boolean underground;

    public Structure3DMarker(StructureType type, int blockX, int blockZ, int yOffset) {
        this.type = type;
        this.blockX = blockX;
        this.blockZ = blockZ;
        this.yOffset = yOffset;
        this.underground = isUndergroundType(type);
    }

    private static boolean isUndergroundType(StructureType type) {
        return switch (type) {
            case STRONGHOLD, DUNGEON, MINESHAFT, ANCIENT_CITY -> true;
            default -> false;
        };
    }

    public void render(Renderer2D renderer, MapViewport viewport, boolean hovered, float delta) {
        Color color = StructureColors.get(type);
        double zoom = viewport.zoom();
        double sx = viewport.screenX(blockX);
        double sy = viewport.screenY(blockZ) - yOffset * zoom;
        double size = ICON_BLOCK_SIZE * zoom;
        double half = size / 2.0;

        if (!viewport.isVisible(sx, sy, half + LABEL_Y_OFFSET + 10)) return;

        if (underground) {
            double surfaceY = viewport.screenY(blockZ);
            drawSurfaceIndicator(renderer, viewport, sx, surfaceY, color, delta);
            drawConnectingLine(renderer, sx, surfaceY, sy, color);
        }

        drawIcon(renderer, sx, sy, size, color, hovered);

        if (hovered) {
            drawLabel(renderer, sx, sy - half, color);
        }
    }

    private void drawIcon(Renderer2D renderer, double sx, double sy, double size, Color color, boolean hovered) {
        double half = size / 2.0;

        Color shadow = new Color(0, 0, 0, hovered ? 120 : 80);
        renderer.begin();
        renderer.quad(sx - half + 2.0, sy - half + 3.0, size, size, shadow);
        renderer.render();

        switch (type) {
            case DESERT_PYRAMID -> drawPyramid(renderer, sx, sy, size, color);
            case STRONGHOLD -> drawCube(renderer, sx, sy, size, color);
            case DUNGEON -> drawCube(renderer, sx, sy, size, color);
            case MINESHAFT -> drawCylinder(renderer, sx, sy, size, color);
            default -> drawDiamond(renderer, sx, sy, size, color);
        }
    }

    private void drawPyramid(Renderer2D renderer, double sx, double sy, double size, Color color) {
        double half = size / 2.0;
        double tipY = sy - size;
        double baseY = sy + half * 0.6;
        double baseLeft = sx - half;
        double baseRight = sx + half;

        Color dark = multiply(color, 0.55f);

        renderer.begin();
        renderer.triangle(sx, tipY, baseLeft, baseY, sx, sy - half * 0.2, dark);
        renderer.triangle(sx, tipY, sx, sy - half * 0.2, baseRight, baseY, color);
        renderer.triangle(sx, tipY, baseLeft, baseY, baseRight, baseY, brighten(color, 1.2f));
        renderer.render();
    }

    private void drawCube(Renderer2D renderer, double sx, double sy, double size, Color color) {
        double half = size / 2.0;
        double topY = sy - half;
        double midY = sy;
        double botY = sy + half;
        double leftX = sx - half;
        double midX = sx;
        double rightX = sx + half;

        Color top = brighten(color, 1.35f);
        Color right = color;
        Color front = multiply(color, 0.65f);

        renderer.begin();
        renderer.triangle(midX, topY, rightX, midY, midX, midY, top);
        renderer.triangle(midX, topY, midX, midY, leftX, midY, top);
        renderer.triangle(midX, midY, rightX, midY, rightX, botY, right);
        renderer.triangle(midX, midY, rightX, botY, midX, botY, right);
        renderer.triangle(leftX, midY, midX, midY, midX, botY, front);
        renderer.triangle(leftX, midY, midX, botY, leftX, botY, front);
        renderer.render();
    }

    private void drawCylinder(Renderer2D renderer, double sx, double sy, double size, Color color) {
        double half = size / 2.0;
        int steps = 12;

        Color dark = multiply(color, 0.5f);

        renderer.begin();
        for (int i = 0; i < steps; i++) {
            double a1 = Math.PI * 2.0 * i / steps;
            double a2 = Math.PI * 2.0 * (i + 1) / steps;
            double x1 = sx + Math.cos(a1) * half;
            double y1 = sy + Math.sin(a1) * half * 0.6;
            double x2 = sx + Math.cos(a2) * half;
            double y2 = sy + Math.sin(a2) * half * 0.6;
            renderer.triangle(x1, y1, x2, y2, x2, sy + half * 0.6 + size * 0.4, dark);
            renderer.triangle(x1, y1, x2, sy + half * 0.6 + size * 0.4, x1, sy + half * 0.6 + size * 0.4, dark);
            renderer.triangle(sx, sy, x1, y1, x2, y2, color);
        }
        renderer.render();
    }

    private void drawDiamond(Renderer2D renderer, double sx, double sy, double size, Color color) {
        double half = size / 2.0;

        Color dark = multiply(color, 0.6f);
        Color light = brighten(color, 1.25f);

        renderer.begin();
        renderer.triangle(sx, sy - half, sx - half, sy, sx, sy + half, dark);
        renderer.triangle(sx, sy - half, sx + half, sy, sx, sy + half, light);
        renderer.triangle(sx, sy - half, sx, sy + half, sx + half, sy, color);
        renderer.render();
    }

    private void drawSurfaceIndicator(Renderer2D renderer, MapViewport viewport, double sx, double sy, Color color, float delta) {
        double radius = SURFACE_CIRCLE_RADIUS_BLOCKS * viewport.zoom();
        int steps = 16;
        double pulse = 0.7 + Math.sin(System.nanoTime() / 500_000_000.0 + (blockX * 31L + blockZ * 17L) * 0.01 + delta) * 0.3;
        int alpha = (int) (50 * pulse);

        Color circleColor = new Color(color.r, color.g, color.b, Math.min(255, alpha));

        renderer.begin();
        for (int i = 0; i < steps; i++) {
            double a1 = Math.PI * 2.0 * i / steps;
            double a2 = Math.PI * 2.0 * (i + 1) / steps;
            double x1 = sx + Math.cos(a1) * radius;
            double y1 = sy + Math.sin(a1) * radius;
            double x2 = sx + Math.cos(a2) * radius;
            double y2 = sy + Math.sin(a2) * radius;
            renderer.triangle(sx, sy, x1, y1, x2, y2, circleColor);
        }
        renderer.render();
    }

    private void drawConnectingLine(Renderer2D renderer, double sx, double sy, double targetY, Color color) {
        double y1 = Math.min(sy, targetY);
        double y2 = Math.max(sy, targetY);
        int alpha = Math.min(180, color.a / 2);

        renderer.begin();
        renderer.quad(sx - 1.0, y1, 2.0, y2 - y1, new Color(color.r, color.g, color.b, alpha));
        renderer.render();
    }

    private void drawLabel(Renderer2D renderer, double sx, double sy, Color color) {
        String label = type.displayName;
        TextRenderer text = TextRenderer.get();
        double textWidth = text.getWidth(label);
        double textX = sx - textWidth / 2.0;
        double textY = sy - LABEL_Y_OFFSET;

        renderer.begin();
        renderer.quad(textX - 2.0, textY - 1.0, textWidth + 4.0, 10.0, new Color(0, 0, 0, 160));
        renderer.render();

        text.begin(1.0, false, true);
        text.render(label, textX, textY, color);
        text.end();
    }

    public boolean isHovered(double mouseX, double mouseY, MapViewport viewport) {
        double sx = viewport.screenX(blockX);
        double sy = viewport.screenY(blockZ) - yOffset * viewport.zoom();
        double half = ICON_BLOCK_SIZE * viewport.zoom() / 2.0;
        double margin = 5.0;
        return Math.abs(mouseX - sx) <= half + margin && Math.abs(mouseY - sy) <= half + margin;
    }

    private static Color multiply(Color color, float factor) {
        int r = Math.max(0, Math.min(255, (int) (color.r * factor)));
        int g = Math.max(0, Math.min(255, (int) (color.g * factor)));
        int b = Math.max(0, Math.min(255, (int) (color.b * factor)));
        return new Color(r, g, b, color.a);
    }

    private static Color brighten(Color color, float factor) {
        int r = Math.max(0, Math.min(255, (int) (color.r * factor)));
        int g = Math.max(0, Math.min(255, (int) (color.g * factor)));
        int b = Math.max(0, Math.min(255, (int) (color.b * factor)));
        return new Color(r, g, b, color.a);
    }
}
