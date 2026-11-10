package me.seedexplorer.addon.render;

/** Immutable renderer color independent of any frontend or widget library. */
public final class RgbaColor {
    public static final RgbaColor WHITE = new RgbaColor(255, 255, 255, 255);

    public final int r;
    public final int g;
    public final int b;
    public final int a;

    public RgbaColor(int r, int g, int b) {
        this(r, g, b, 255);
    }

    public RgbaColor(int r, int g, int b, int a) {
        this.r = channel(r);
        this.g = channel(g);
        this.b = channel(b);
        this.a = channel(a);
    }

    private static int channel(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
