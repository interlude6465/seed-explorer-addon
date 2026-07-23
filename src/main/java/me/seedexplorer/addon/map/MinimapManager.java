/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import me.seedexplorer.addon.events.SeedChangeEvent;
import me.seedexplorer.addon.seed.SeedManager;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import me.seedexplorer.addon.workers.WorkerManager;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.renderer.Texture;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Manages map chunk tiles, their generation, and caching. */
public class MinimapManager {
    private static final MinimapManager INSTANCE = new MinimapManager();
    private final Map<TileKey, ChunkTile> cache = new ConcurrentHashMap<>();
    private volatile int generation = 0;

    private MinimapManager() {
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public static MinimapManager get() {
        return INSTANCE;
    }

    @EventHandler
    private void onSeedChanged(SeedChangeEvent event) {
        WorldgenEngine.clear();
        clear();
    }

    /**
     * Retrieves a ChunkTile for the given chunk coordinates.
     * Creates a new tile with async generation if it doesn't exist in the cache.
     */
    public ChunkTile getTile(int x, int z) {
        return getTile(x, z, 0);
    }

    /**
     * Retrieves a ChunkTile for the given chunk coordinates and dimension.
     * Creates a new tile with async generation if it doesn't exist in the cache.
     */
    public ChunkTile getTile(int x, int z, int dimension) {
        TileKey key = new TileKey(x, z, dimension);
        ChunkTile tile = cache.get(key);
        if (tile == null) {
            tile = new ChunkTile(x, z, dimension);
            cache.put(key, tile);
            generateAsync(tile);
        }
        return tile;
    }

    /**
     * Launches a background task to generate the NativeImage for a chunk tile,
     * then uploads the texture to the GPU on the main render thread.
     */
    private void generateAsync(ChunkTile tile) {
        if (!tile.markGenerating()) return;
        int requestedGeneration = generation;

        if (!WorkerManager.get().submit(() -> {
            NativeImage image = null;
            try {
                image = new NativeImage(NativeImage.Format.RGBA, 16, 16, false);

                for (int cz = 0; cz < 16; cz++) {
                    for (int cx = 0; cx < 16; cx++) {
                        int worldX = tile.x * 16 + cx;
                        int worldZ = tile.z * 16 + cz;
                        int color = BiomeGenerator.getBiomeColor(worldX, worldZ, tile.dimension);
                        image.setPixelABGR(cx, cz, color);
                    }
                }

                NativeImage readyImage = image;
                image = null;
                Minecraft.getInstance().execute(() -> {
                    try {
                        if (generation != requestedGeneration || cache.get(new TileKey(tile.x, tile.z, tile.dimension)) != tile) return;

                        if (tile.texture != null) {
                            tile.texture.close();
                        }

                        Texture texture = new Texture(16, 16, TextureFormat.RGBA8, FilterMode.NEAREST, FilterMode.NEAREST);
                        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture.getTexture(), readyImage);
                        tile.texture = texture;
                    } finally {
                        tile.generating = false;
                        readyImage.close();
                    }
                });
            } catch (Exception e) {
                if (image != null) image.close();
                tile.generating = false;
                e.printStackTrace();
            }
        })) {
            tile.generating = false;
        }
    }

    /** Clears all cached tiles, disposing GPU resources. */
    public void clear() {
        generation++;
        for (ChunkTile tile : cache.values()) {
            tile.dispose();
        }
        cache.clear();
    }

    private record TileKey(int x, int z, int dimension) {
    }
}
