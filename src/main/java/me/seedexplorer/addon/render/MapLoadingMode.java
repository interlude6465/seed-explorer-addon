package me.seedexplorer.addon.render;

import net.minecraft.client.Minecraft;

/** Tracks the screen that owns an active dedicated map-generation session. */
public final class MapLoadingMode {
    private static volatile Object owner;

    private MapLoadingMode() {
    }

    public static boolean isActive() {
        Object currentOwner = owner;
        return currentOwner != null && Minecraft.getInstance().screen == currentOwner;
    }

    public static void activate(Object owner) {
        MapLoadingMode.owner = owner;
    }

    public static void deactivate(Object owner) {
        if (MapLoadingMode.owner == owner) MapLoadingMode.owner = null;
    }
}
