package me.seedexplorer.addon.render;

/**
 * Mutable UV bounds for a sprite packed into the terrain texture atlas.
 *
 * <p>This data belongs to the seed explorer renderer and deliberately has no
 * dependency on Meteor's GUI texture packer.</p>
 */
public final class TextureRegion {
    public double x1;
    public double y1;
    public double x2;
    public double y2;
    public final double diagonal;

    public TextureRegion(int width, int height) {
        diagonal = Math.hypot(width, height);
    }
}
