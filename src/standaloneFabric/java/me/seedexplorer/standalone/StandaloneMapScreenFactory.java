package me.seedexplorer.standalone;

import net.minecraft.client.gui.screens.Screen;

/** Frontend boundary used while the map UI is extracted from Meteor widgets. */
@FunctionalInterface
public interface StandaloneMapScreenFactory {
    Screen create(Screen parent);
}
