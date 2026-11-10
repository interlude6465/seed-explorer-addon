/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.FilterMode;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Manages map chunk tiles, their generation, and caching. */
public class MinimapManager {
    private static final MinimapManager INSTANCE = new MinimapManager();
    private static final int MAX_CACHED_TILES = 8_192;
    private final Map<TileKey, ChunkTile> cache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TileKey> insertionOrder = new ConcurrentLinkedQueue<>();
    private final AtomicInteger frameGenerationBudget = new AtomicInteger();
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
            ChunkTile created = new ChunkTile(x, z, dimension);
            ChunkTile existing = cache.putIfAbsent(key, created);
            tile = existing == null ? created : existing;
            if (existing == null) {
                insertionOrder.add(key);
                trimCache();
            }
        }
        if (tile.texture == null && !tile.generating && claimGenerationBudget()) {
            generateAsync(tile);
        }
        return tile;
    }

    private void trimCache() {
        while (cache.size() > MAX_CACHED_TILES) {
            TileKey eldest = insertionOrder.poll();
            if (eldest == null) return;
            ChunkTile removed = cache.remove(eldest);
            if (removed != null) removed.dispose();
        }
    }

    /** Limits new 256-sample biome jobs submitted by one rendered map frame. */
    public void beginFrame(int maximumNewTiles) {
        frameGenerationBudget.set(Math.max(0, maximumNewTiles));
    }

    private boolean claimGenerationBudget() {
        while (true) {
            int current = frameGenerationBudget.get();
            if (current <= 0) return false;
            if (frameGenerationBudget.compareAndSet(current, current - 1)) return true;
        }
    }

    /** Queues nearby biome tiles center-first so a focused map does not remain blank. */
    public void prewarm(int blockX, int blockZ, int dimension, int radiusChunks) {
        int centerX = Math.floorDiv(blockX, 16);
        int centerZ = Math.floorDiv(blockZ, 16);
        int radius = Math.max(0, radiusChunks);
        beginFrame(12);
        getTile(centerX, centerZ, dimension);
        for (int ring = 1; ring <= radius; ring++) {
            for (int offset = -ring; offset <= ring; offset++) {
                getTile(centerX + offset, centerZ - ring, dimension);
                getTile(centerX + offset, centerZ + ring, dimension);
            }
            for (int offset = -ring + 1; offset < ring; offset++) {
                getTile(centerX - ring, centerZ + offset, dimension);
                getTile(centerX + ring, centerZ + offset, dimension);
            }
        }
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

                        Texture texture = new Texture(16, 16, GpuFormat.RGBA8_UNORM, FilterMode.NEAREST, FilterMode.NEAREST);
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
        insertionOrder.clear();
    }

    private record TileKey(int x, int z, int dimension) {
    }
}
