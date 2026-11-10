package me.seedexplorer.addon.render;

import meteordevelopment.meteorclient.utils.render.color.Color;

/** Compatibility conversion kept at the Meteor frontend boundary. */
public final class MeteorColorAdapter {
    private MeteorColorAdapter() {
    }

    public static Color toMeteor(RgbaColor color) {
        return new Color(color.r, color.g, color.b, color.a);
    }

    public static RgbaColor fromMeteor(Color color) {
        return new RgbaColor(color.r, color.g, color.b, color.a);
    }
}
