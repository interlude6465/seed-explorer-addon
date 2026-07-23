/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.seed;

import me.seedexplorer.addon.events.SeedChangeEvent;
import me.seedexplorer.addon.ore.OrePatch;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SeedManager extends System<SeedManager> {
    // getWorldSeed()/getMcVersion() are read from background prediction and render threads,
    // while the GUI/commands write on the main thread. Keep the shared state safely published.
    private volatile long worldSeed;
    private volatile String mcVersion;
    private volatile String activeProfileKey = "";
    private final Map<String, Profile> profiles = new ConcurrentHashMap<>();

    public SeedManager() {
        super("seed-manager");
        this.worldSeed = 0;
        this.mcVersion = "";
    }

    public static SeedManager get() {
        return Systems.get(SeedManager.class);
    }

    public long getWorldSeed() {
        syncProfileFromClient();
        return worldSeed;
    }

    public String getMcVersion() {
        return mcVersion;
    }

    public void setWorldSeed(long worldSeed) {
        syncProfileFromClient();
        if (this.worldSeed != worldSeed) {
            this.worldSeed = worldSeed;
            saveActiveProfile();
            postChangeEvent();
            save();
        }
    }

    public void setMcVersion(String mcVersion) {
        syncProfileFromClient();
        if (!this.mcVersion.equals(mcVersion)) {
            this.mcVersion = mcVersion;
            saveActiveProfile();
            postChangeEvent();
            save();
        }
    }

    public void set(long worldSeed, String mcVersion) {
        syncProfileFromClient();
        boolean changed = false;

        if (this.worldSeed != worldSeed) {
            this.worldSeed = worldSeed;
            changed = true;
        }

        if (!this.mcVersion.equals(mcVersion)) {
            this.mcVersion = mcVersion;
            changed = true;
        }

        if (changed) {
            saveActiveProfile();
            postChangeEvent();
            save();
        }
    }

    public String getActiveProfileKey() {
        syncProfileFromClient();
        return activeProfileKey;
    }

    public void syncProfileFromClient() {
        String key = currentProfileKey();
        if (key.equals(activeProfileKey)) return;

        saveActiveProfile();
        activeProfileKey = key;
        Profile profile = profiles.get(key);
        if (profile != null) {
            worldSeed = profile.seed();
            mcVersion = profile.version();
        }
    }

    public boolean getProfileLayer(String layerName, boolean defaultValue) {
        syncProfileFromClient();
        return peekProfileLayer(layerName, defaultValue);
    }

    public boolean peekProfileLayer(String layerName, boolean defaultValue) {
        Profile profile = profiles.get(activeProfileKey);
        if (profile == null) return defaultValue;
        return profile.layers().getOrDefault(layerName, defaultValue);
    }

    public void setProfileLayer(String layerName, boolean enabled) {
        syncProfileFromClient();
        Profile profile = profiles.getOrDefault(activeProfileKey, new Profile(worldSeed, mcVersion == null ? "" : mcVersion, new HashMap<>()));
        Map<String, Boolean> layers = new HashMap<>(profile.layers());
        layers.put(layerName, enabled);
        profiles.put(activeProfileKey, new Profile(worldSeed, mcVersion == null ? "" : mcVersion, layers));
        save();
    }

    public boolean getProfileStructure(String structureName, boolean defaultValue) {
        return getProfileLayer("structure." + structureName, defaultValue);
    }

    public void setProfileStructure(String structureName, boolean enabled) {
        setProfileLayer("structure." + structureName, enabled);
    }

    public boolean getCompletedStructure(String structureName, int dimension, int x, int z) {
        return getProfileLayer(completedStructureKey(structureName, dimension, x, z), false);
    }

    public void setCompletedStructure(String structureName, int dimension, int x, int z, boolean completed) {
        setProfileLayer(completedStructureKey(structureName, dimension, x, z), completed);
    }

    public boolean toggleCompletedStructure(String structureName, int dimension, int x, int z) {
        boolean completed = !getCompletedStructure(structureName, dimension, x, z);
        setCompletedStructure(structureName, dimension, x, z, completed);
        return completed;
    }

    private String completedStructureKey(String structureName, int dimension, int x, int z) {
        return "completed." + dimension + "." + structureName + "." + x + "." + z;
    }

    public boolean isClearedOre(OrePatch patch, int dimension) {
        return peekProfileLayer(clearedOreKey(patch.type.name(), dimension, patch.x, patch.y, patch.z), false);
    }

    public int markClearedOrePatches(int dimension, Collection<OrePatch> patches) {
        if (patches == null || patches.isEmpty()) return 0;
        syncProfileFromClient();

        Profile profile = profiles.getOrDefault(activeProfileKey, new Profile(worldSeed, mcVersion == null ? "" : mcVersion, new HashMap<>()));
        Map<String, Boolean> layers = new HashMap<>(profile.layers());
        int added = 0;

        for (OrePatch patch : patches) {
            String key = clearedOreKey(patch.type.name(), dimension, patch.x, patch.y, patch.z);
            if (!layers.getOrDefault(key, false)) {
                layers.put(key, true);
                added++;
            }
        }

        if (added > 0) {
            profiles.put(activeProfileKey, new Profile(worldSeed, mcVersion == null ? "" : mcVersion, layers));
            save();
        }

        return added;
    }

    private String clearedOreKey(String oreName, int dimension, int x, int y, int z) {
        return "clearedOre." + worldSeed + "." + dimension + "." + oreName + "." + x + "." + y + "." + z;
    }

    private void saveActiveProfile() {
        if (activeProfileKey == null || activeProfileKey.isBlank()) return;
        Profile old = profiles.get(activeProfileKey);
        Map<String, Boolean> layers = old == null ? new HashMap<>() : new HashMap<>(old.layers());
        profiles.put(activeProfileKey, new Profile(worldSeed, mcVersion == null ? "" : mcVersion, layers));
    }

    private String currentProfileKey() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        try {
            if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null && !mc.getCurrentServer().ip.isBlank()) {
                return "server:" + mc.getCurrentServer().ip.toLowerCase();
            }
            if (mc.hasSingleplayerServer()) {
                Object server = net.minecraft.client.Minecraft.class.getMethod("getSingleplayerServer").invoke(mc);
                if (server != null) {
                    Object worldData = server.getClass().getMethod("getWorldData").invoke(server);
                    Object levelName = worldData == null ? null : worldData.getClass().getMethod("getLevelName").invoke(worldData);
                    if (levelName != null) return "singleplayer:" + levelName;
                }
                return "singleplayer:local";
            }
        } catch (Throwable ignored) {
        }
        return "global";
    }

    private void postChangeEvent() {
        MeteorClient.EVENT_BUS.post(SeedChangeEvent.get(worldSeed, mcVersion));
    }

    @Override
    public CompoundTag toTag() {
        saveActiveProfile();
        CompoundTag tag = new CompoundTag();
        tag.putLong("worldSeed", worldSeed);
        tag.putString("mcVersion", mcVersion);
        tag.putString("activeProfileKey", activeProfileKey == null ? "" : activeProfileKey);

        ListTag list = new ListTag();
        for (Map.Entry<String, Profile> entry : profiles.entrySet()) {
            CompoundTag profileTag = new CompoundTag();
            profileTag.putString("key", entry.getKey());
            Profile profile = entry.getValue();
            profileTag.putLong("worldSeed", profile.seed());
            profileTag.putString("mcVersion", profile.version());
            CompoundTag layers = new CompoundTag();
            for (Map.Entry<String, Boolean> layer : profile.layers().entrySet()) {
                layers.putBoolean(layer.getKey(), layer.getValue());
            }
            profileTag.put("layers", layers);
            list.add(profileTag);
        }
        tag.put("profiles", list);
        return tag;
    }

    @Override
    public SeedManager fromTag(CompoundTag tag) {
        worldSeed = tag.getLongOr("worldSeed", 0);
        mcVersion = tag.getStringOr("mcVersion", "");
        activeProfileKey = tag.getStringOr("activeProfileKey", "");
        profiles.clear();
        for (CompoundTag profileTag : tag.getListOrEmpty("profiles").compoundStream().toList()) {
            String key = profileTag.getStringOr("key", "");
            if (key.isBlank()) continue;
            Map<String, Boolean> layers = new HashMap<>();
            CompoundTag layersTag = profileTag.getCompoundOrEmpty("layers");
            for (String layerName : layersTag.keySet()) {
                layers.put(layerName, layersTag.getBooleanOr(layerName, true));
            }
            profiles.put(key, new Profile(
                profileTag.getLongOr("worldSeed", 0),
                profileTag.getStringOr("mcVersion", ""),
                layers
            ));
        }
        if (!activeProfileKey.isBlank() && !profiles.containsKey(activeProfileKey)) {
            saveActiveProfile();
        }
        return this;
    }

    private record Profile(long seed, String version, Map<String, Boolean> layers) {
    }
}
