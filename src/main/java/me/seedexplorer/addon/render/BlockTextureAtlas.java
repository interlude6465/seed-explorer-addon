package me.seedexplorer.addon.render;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import meteordevelopment.meteorclient.gui.renderer.packer.TextureRegion;
import meteordevelopment.meteorclient.renderer.Texture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * Stitches the Minecraft block textures the 3D terrain map needs into a single
 * atlas {@link Texture}, exposing a per-block {@link TextureRegion} of UVs.
 *
 * <p>Textures are read from the addon's packaged resources
 * ({@code /assets/meteor-seed-explorer/textures/blocks/*.png}), copied verbatim
 * from a local 26.1.2 install so the map shows real block textures without
 * depending on the live block-texture atlas. The generated texture index is
 * stitched in full; blocks whose models do not map to a standalone texture
 * still resolve to a neutral stone fallback.</p>
 */
public final class BlockTextureAtlas {
    private static final String ROOT = "/assets/meteor-seed-explorer/textures/blocks/";
    private static final int MAX_ATLAS_WIDTH = 2048;

    private static BlockTextureAtlas instance;

    private final Texture texture;
    private final Map<String, TextureRegion> regions = new HashMap<>();
    private final TextureRegion fallback;
    private final int loadedCount;
    private final int requestedCount;
    private final String status;
    private final Map<String, TextureRegion> modelRegions = new ConcurrentHashMap<>();
    private final Set<String> unresolvedModelRegions = ConcurrentHashMap.newKeySet();

    private BlockTextureAtlas() {
        List<LoadedImage> images = new ArrayList<>();
        TextureRegion stoneRegion = null;

        List<String> files = textureFiles();
        this.requestedCount = files.size();
        for (String fileName : files) {
            TextureRegion region = load(images, fileName);
            if (region != null) {
                regions.put(fileName, region);
                if (fileName.equals("stone.png")) stoneRegion = region;
            }
        }
        if (images.isEmpty()) {
            this.texture = fallbackTexture();
            TextureRegion region = new TextureRegion(1, 1);
            region.x1 = 0;
            region.y1 = 0;
            region.x2 = 1;
            region.y2 = 1;
            this.fallback = region;
            this.loadedCount = 0;
            this.status = "fallback only; no packaged block textures loaded";
        } else {
            this.fallback = stoneRegion != null ? stoneRegion
                : regions.values().stream().findFirst().orElse(null);
            this.texture = pack(images);
            this.loadedCount = regions.size();
            int missing = Math.max(0, requestedCount - loadedCount);
            this.status = loadedCount + "/" + requestedCount + " official block textures stitched"
                + (missing == 0 ? "" : " (" + missing + " missing)");
        }
    }

    public static BlockTextureAtlas get() {
        if (instance == null) instance = new BlockTextureAtlas();
        return instance;
    }

    /** The stitched atlas, or null if no textures loaded. */
    public Texture texture() {
        return texture;
    }

    /** A neutral stone region used when a block has no stitched texture. */
    public TextureRegion fallback() {
        return fallback;
    }

    public int loadedCount() {
        return loadedCount;
    }

    public int requestedCount() {
        return requestedCount;
    }

    public String status() {
        return status;
    }

    /** The UV region for a block's top face, or the fallback if unknown. */
    public TextureRegion region(BlockState state) {
        String fileName = topFaceFileName(state);
        TextureRegion region = regions.get(fileName);
        if (region != null) return region;
        TextureRegion byId = regions.get(blockIdFileName(state));
        if (byId != null) return byId;
        TextureRegion baked = modelRegion(state, Direction.UP);
        if (baked != null) return baked;
        return fallback;
    }

    /** True when the top face resolves through the neutral fallback texture. */
    public boolean usesFallback(BlockState state) {
        String fileName = topFaceFileName(state);
        if (regions.containsKey(fileName)) return false;
        if (regions.containsKey(blockIdFileName(state))) return false;
        return modelRegion(state, Direction.UP) == null;
    }

    /** The UV region for a block's side face, or the top/fallback texture if unknown. */
    public TextureRegion sideRegion(BlockState state) {
        String fileName = sideFaceFileName(state);
        TextureRegion region = regions.get(fileName);
        if (region != null) return region;
        TextureRegion baked = modelRegion(state, Direction.NORTH);
        return baked != null ? baked : region(state);
    }

    /** Direct access to a stitched sprite, used for vanilla's grass side overlay. */
    public TextureRegion regionFile(String fileName) {
        if (fileName == null || fileName.isBlank()) return fallback;
        TextureRegion region = regions.get(fileName.endsWith(".png") ? fileName : fileName + ".png");
        return region != null ? region : fallback;
    }

    public boolean hasFile(String fileName) {
        return fileName != null && regions.containsKey(fileName.endsWith(".png") ? fileName : fileName + ".png");
    }

    /** Resolves a vanilla baked-model sprite (minecraft:block/foo) into this
     * atlas. Model geometry can therefore use the official sprite selected by
     * Minecraft instead of guessing a top/side filename. */
    public TextureRegion region(Identifier spriteId) {
        if (spriteId == null) return fallback;
        String path = spriteId.getPath();
        if (path.startsWith("block/")) path = path.substring("block/".length());
        TextureRegion region = regions.get(path.endsWith(".png") ? path : path + ".png");
        return region != null ? region : fallback;
    }

    /** True when a side face has no direct side/top texture and falls through to fallback. */
    public boolean sideUsesFallback(BlockState state) {
        String fileName = sideFaceFileName(state);
        if (regions.containsKey(fileName)) return false;
        return modelRegion(state, Direction.NORTH) == null && usesFallback(state);
    }

    /** Uses the same baked state model Minecraft selected for this block. */
    private TextureRegion modelRegion(BlockState state, Direction face) {
        if (state == null) return null;
        String key = blockId(state) + "|" + state + "|" + face;
        if (modelRegions.containsKey(key)) return modelRegions.get(key);
        if (unresolvedModelRegions.contains(key)) return null;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getModelManager() == null) return null;
            BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
            if (model == null) return null;
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(BlockPos.asLong(0, 0, 0)), parts);
            for (BlockStateModelPart part : parts) {
                List<BakedQuad> quads = part.getQuads(face);
                for (BakedQuad quad : quads) {
                    if (quad.materialInfo() == null || quad.materialInfo().sprite() == null) continue;
                    var contents = quad.materialInfo().sprite().contents();
                    if (contents == null || contents.name() == null) continue;
                    TextureRegion resolved = region(contents.name());
                    if (resolved != null && resolved != fallback) {
                        modelRegions.put(key, resolved);
                        return resolved;
                    }
                }
            }
        } catch (Throwable ignored) {
            // The atlas singleton is rebuilt with the client, so a miss is stable
            // for this session and must not become a per-block hot-path retry.
        }
        unresolvedModelRegions.add(key);
        return null;
    }

    /** Resolves the top-face texture filename for a block, applying aliases. */
    private static String topFaceFileName(BlockState state) {
        String id = blockId(state);
        String alias = TOP_FACE_ALIASES.get(id);
        if (alias != null) return alias + ".png";
        // Logs use a "_top" top face: oak_log -> oak_log_top.png
        if (id.endsWith("_log")) return id + "_top.png";
        return id + ".png";
    }

    private static String blockIdFileName(BlockState state) {
        return blockId(state) + ".png";
    }

    private static String sideFaceFileName(BlockState state) {
        String id = blockId(state);
        if (id.equals("grass_block") && state.hasProperty(BlockStateProperties.SNOWY)
            && state.getValue(BlockStateProperties.SNOWY)) {
            return "grass_block_snow.png";
        }
        String alias = SIDE_FACE_ALIASES.get(id);
        if (alias != null) return alias + ".png";
        return id + ".png";
    }

    /** The grass model uses a dirt/grass base plus a separate tinted overlay. */
    public TextureRegion grassSideBase() {
        return regions.getOrDefault("grass_block_side.png", fallback);
    }

    private static String blockId(BlockState state) {
        return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())).replace("minecraft:", "");
    }

    /** Block ids whose top face is not named {@code <id>.png}. */
    private static final Map<String, String> TOP_FACE_ALIASES = Map.ofEntries(
        Map.entry("grass_block", "grass_block_top"),
        Map.entry("water", "water_still"),
        Map.entry("lava", "lava_still"),
        Map.entry("podzol", "podzol_top"),
        Map.entry("mycelium", "mycelium_top"),
        Map.entry("dirt_path", "dirt_path_top"),
        Map.entry("farmland", "farmland"),
        Map.entry("sandstone", "sandstone_top"),
        Map.entry("red_sandstone", "red_sandstone_top"),
        Map.entry("smooth_sandstone", "sandstone_top"),
        Map.entry("smooth_red_sandstone", "red_sandstone_top"),
        Map.entry("cut_sandstone", "sandstone_top"),
        Map.entry("cut_red_sandstone", "red_sandstone_top"),
        Map.entry("chiseled_sandstone", "sandstone_top"),
        Map.entry("chiseled_red_sandstone", "red_sandstone_top"),
        Map.entry("crimson_nylium", "crimson_nylium"),
        Map.entry("warped_nylium", "warped_nylium"),
        Map.entry("snow", "snow"),
        Map.entry("snow_block", "snow"),
        Map.entry("powder_snow", "powder_snow"),
        Map.entry("sand", "sand"),
        Map.entry("red_sand", "red_sand"),
        Map.entry("clay", "clay"),
        Map.entry("end_stone", "end_stone"),
        Map.entry("end_stone_bricks", "end_stone_bricks"),
        Map.entry("purpur_block", "purpur_block"),
        Map.entry("purpur_pillar", "purpur_pillar_top"),
        Map.entry("purpur_stairs", "purpur_block"),
        Map.entry("purpur_slab", "purpur_block"),
        Map.entry("chorus_plant", "chorus_plant"),
        Map.entry("chorus_flower", "chorus_flower"),
        Map.entry("obsidian", "obsidian"),
        Map.entry("end_rod", "end_rod"),
        Map.entry("shulker_box", "shulker_box"),
        Map.entry("white_shulker_box", "white_shulker_box"),
        Map.entry("orange_shulker_box", "orange_shulker_box"),
        Map.entry("magenta_shulker_box", "magenta_shulker_box"),
        Map.entry("light_blue_shulker_box", "light_blue_shulker_box"),
        Map.entry("yellow_shulker_box", "yellow_shulker_box"),
        Map.entry("lime_shulker_box", "lime_shulker_box"),
        Map.entry("pink_shulker_box", "pink_shulker_box"),
        Map.entry("gray_shulker_box", "gray_shulker_box"),
        Map.entry("light_gray_shulker_box", "light_gray_shulker_box"),
        Map.entry("cyan_shulker_box", "cyan_shulker_box"),
        Map.entry("purple_shulker_box", "purple_shulker_box"),
        Map.entry("blue_shulker_box", "blue_shulker_box"),
        Map.entry("brown_shulker_box", "brown_shulker_box"),
        Map.entry("green_shulker_box", "green_shulker_box"),
        Map.entry("red_shulker_box", "red_shulker_box"),
        Map.entry("black_shulker_box", "black_shulker_box")
    );

    private static final Map<String, String> SIDE_FACE_ALIASES = Map.ofEntries(
        Map.entry("grass_block", "grass_block_side"),
        Map.entry("snowy_grass_block", "grass_block_snow"),
        Map.entry("podzol", "podzol_side"),
        Map.entry("mycelium", "mycelium_side"),
        Map.entry("dirt_path", "dirt_path_side"),
        Map.entry("farmland", "farmland"),
        Map.entry("sandstone", "sandstone"),
        Map.entry("red_sandstone", "red_sandstone"),
        Map.entry("cut_sandstone", "cut_sandstone"),
        Map.entry("cut_red_sandstone", "cut_red_sandstone"),
        Map.entry("chiseled_sandstone", "chiseled_sandstone"),
        Map.entry("chiseled_red_sandstone", "chiseled_red_sandstone"),
        Map.entry("end_stone", "end_stone"),
        Map.entry("end_stone_bricks", "end_stone_bricks"),
        Map.entry("purpur_block", "purpur_block"),
        Map.entry("purpur_pillar", "purpur_pillar"),
        Map.entry("purpur_stairs", "purpur_block"),
        Map.entry("purpur_slab", "purpur_block"),
        Map.entry("chorus_plant", "chorus_plant"),
        Map.entry("chorus_flower", "chorus_flower"),
        Map.entry("obsidian", "obsidian"),
        Map.entry("end_rod", "end_rod")
    );

    /** All packaged block textures, with stone first as the fallback texture. */
    private static List<String> textureFiles() {
        List<String> indexed = indexedTextureFiles();
        // The bundled index is intentionally conservative. Merge every sprite
        // from the installed 26.1.2 block directory so new or uncommon blocks
        // never silently become the stone fallback.
        List<String> official = officialTextureFileNames();
        if (!official.isEmpty()) {
            java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(indexed);
            merged.addAll(official);
            if (!merged.contains("stone.png")) merged.add("stone.png");
            return List.copyOf(merged);
        }
        if (!indexed.isEmpty()) return indexed;

        List<String> files = new ArrayList<>();
        files.add("stone.png");
        for (String name : new String[] {
            // Grass is a three-sprite model in vanilla: the dirt/grass side
            // base and its biome-tinted overlay are separate files. Keeping
            // only grass_block_top here silently fell back to the green top
            // sprite for every side face when no generated index was bundled.
            "grass_block_top", "grass_block_side", "grass_block_side_overlay",
            "grass_block_snow", "sand", "red_sand", "dirt", "coarse_dirt", "gravel",
            "podzol_top", "mycelium_top", "dirt_path_top",
            "andesite", "diorite", "granite", "cobblestone", "mossy_cobblestone",
            "oak_planks", "birch_planks", "spruce_planks", "mud",
            "oak_log_top", "birch_log_top", "spruce_log_top", "dark_oak_log_top",
            "jungle_log_top", "acacia_log_top", "cherry_log_top", "mangrove_log_top",
            "oak_leaves", "birch_leaves", "spruce_leaves", "dark_oak_leaves",
            "jungle_leaves", "acacia_leaves", "cherry_leaves", "mangrove_leaves",
            "water_still", "snow", "ice", "packed_ice", "blue_ice", "clay",
            "bedrock", "deepslate", "tuff", "calcite", "dripstone_block",
            "sandstone_top", "red_sandstone_top"
        }) {
            if (!name.equals("stone")) files.add(name + ".png");
        }
        return files;
    }

    private static List<String> officialTextureFileNames() {
        try {
            String userHome = System.getProperty("user.home", "");
            Path dir = Path.of(userHome, "AppData", "Roaming", ".minecraft", "versions",
                "26.1.2", "26.1.2", "assets", "minecraft", "textures", "block");
            if (!Files.isDirectory(dir)) return List.of();
            try (var stream = Files.list(dir)) {
                return stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".png"))
                    .sorted()
                    .toList();
            }
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<String> indexedTextureFiles() {
        try (InputStream in = BlockTextureAtlas.class.getResourceAsStream(ROOT + "index.txt")) {
            if (in == null) return List.of();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String> files = new ArrayList<>();
            if (text.lines().anyMatch(line -> line.equals("stone.png"))) files.add("stone.png");
            text.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && line.endsWith(".png"))
                .filter(line -> !line.equals("stone.png"))
                .forEach(files::add);
            return files;
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private static TextureRegion load(List<LoadedImage> into, String fileName) {
        try (InputStream in = officialTextureStream(fileName)) {
            if (in == null) return null;
            ByteBuffer raw = TextureUtil.readResource(in);
            ((Buffer) raw).rewind();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer comp = stack.mallocInt(1);
                ByteBuffer image = STBImage.stbi_load_from_memory(raw, w, h, comp, 4);
                MemoryUtil.memFree(raw);
                if (image == null) return null;
                int width = w.get(0);
                int height = h.get(0);
                TextureRegion region = new TextureRegion(width, height);
                into.add(new LoadedImage(image, region, width, height));
                return region;
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Prefer the exact vanilla 26.1.2 asset tree when it is present on this
     * client, while retaining packaged copies for servers/other installations.
     * This keeps the map on the same PNGs as the live renderer, including future
     * block additions that are not in the addon's bundled snapshot.
     */
    private static InputStream officialTextureStream(String fileName) throws IOException {
        String gameDir = System.getProperty("user.dir", "");
        Path external = Path.of(gameDir, "assets", "minecraft", "textures", "block", fileName);
        if (Files.isRegularFile(external)) return Files.newInputStream(external);

        String userHome = System.getProperty("user.home", "");
        Path versioned = Path.of(userHome, "AppData", "Roaming", ".minecraft", "versions",
            "26.1.2", "26.1.2", "assets", "minecraft", "textures", "block", fileName);
        if (Files.isRegularFile(versioned)) return Files.newInputStream(versioned);

        return BlockTextureAtlas.class.getResourceAsStream(ROOT + fileName);
    }

    /**
     * The map stores only the highest surface per column, so transparent leaf
     * pixels otherwise reveal the clear buffer rather than the ground below. Use
     * Minecraft's opaque/fast-leaves appearance for the terrain atlas.
     */
    private static void makeLeavesOpaque(ByteBuffer image, int width, int height) {
        long red = 0;
        long green = 0;
        long blue = 0;
        int opaque = 0;
        int pixels = width * height;
        for (int pixel = 0; pixel < pixels; pixel++) {
            int index = pixel * 4;
            if ((image.get(index + 3) & 0xFF) < 128) continue;
            red += image.get(index) & 0xFF;
            green += image.get(index + 1) & 0xFF;
            blue += image.get(index + 2) & 0xFF;
            opaque++;
        }
        int fillRed = opaque == 0 ? 96 : (int) (red / opaque);
        int fillGreen = opaque == 0 ? 128 : (int) (green / opaque);
        int fillBlue = opaque == 0 ? 80 : (int) (blue / opaque);
        for (int pixel = 0; pixel < pixels; pixel++) {
            int index = pixel * 4;
            if ((image.get(index + 3) & 0xFF) < 128) {
                image.put(index, (byte) fillRed);
                image.put(index + 1, (byte) fillGreen);
                image.put(index + 2, (byte) fillBlue);
            }
            image.put(index + 3, (byte) 0xFF);
        }
    }

    /** Row-packs loaded images with 1px padding and uploads one RGBA8 atlas. */
    private static Texture pack(List<LoadedImage> images) {
        int width = 0;
        int height = 0;
        int rowWidth = 0;
        int rowHeight = 0;

        for (LoadedImage image : images) {
            if (rowWidth + image.width > MAX_ATLAS_WIDTH) {
                width = Math.max(width, rowWidth);
                height += rowHeight;
                rowWidth = 0;
                rowHeight = 0;
            }
            image.x = 1 + rowWidth;
            image.y = 1 + height;
            rowWidth += 1 + image.width + 1;
            rowHeight = Math.max(rowHeight, 1 + image.height + 1);
        }
        width = Math.max(width, rowWidth);
        height += rowHeight;

        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        for (LoadedImage image : images) {
            byte[] row = new byte[image.width * 4];
            for (int i = 0; i < image.height; i++) {
                ((Buffer) image.buffer).position(i * row.length);
                image.buffer.get(row);
                ((Buffer) buffer).position(((image.y + i) * width + image.x) * 4);
                buffer.put(row);
            }
            duplicatePadding(buffer, image, width);
            ((Buffer) image.buffer).rewind();
            image.region.x1 = (double) image.x / width;
            image.region.y1 = (double) image.y / height;
            image.region.x2 = (double) (image.x + image.width) / width;
            image.region.y2 = (double) (image.y + image.height) / height;
            STBImage.stbi_image_free(image.buffer);
        }
        ((Buffer) buffer).rewind();

        Texture texture = new Texture(width, height, TextureFormat.RGBA8, FilterMode.NEAREST, FilterMode.NEAREST);
        texture.upload(buffer);
        return texture;
    }

    private static void duplicatePadding(ByteBuffer atlas, LoadedImage image, int atlasWidth) {
        for (int x = 0; x < image.width; x++) {
            copyPixel(image.buffer, x, 0, image.width, atlas, image.x + x, image.y - 1, atlasWidth);
            copyPixel(image.buffer, x, image.height - 1, image.width, atlas, image.x + x, image.y + image.height, atlasWidth);
        }
        for (int y = 0; y < image.height; y++) {
            copyPixel(image.buffer, 0, y, image.width, atlas, image.x - 1, image.y + y, atlasWidth);
            copyPixel(image.buffer, image.width - 1, y, image.width, atlas, image.x + image.width, image.y + y, atlasWidth);
        }
        copyPixel(image.buffer, 0, 0, image.width, atlas, image.x - 1, image.y - 1, atlasWidth);
        copyPixel(image.buffer, image.width - 1, 0, image.width, atlas, image.x + image.width, image.y - 1, atlasWidth);
        copyPixel(image.buffer, 0, image.height - 1, image.width, atlas, image.x - 1, image.y + image.height, atlasWidth);
        copyPixel(image.buffer, image.width - 1, image.height - 1, image.width, atlas, image.x + image.width, image.y + image.height, atlasWidth);
    }

    private static void copyPixel(ByteBuffer source, int sx, int sy, int sourceWidth,
                                  ByteBuffer target, int tx, int ty, int targetWidth) {
        int sourceIndex = (sy * sourceWidth + sx) * 4;
        int targetIndex = (ty * targetWidth + tx) * 4;
        for (int i = 0; i < 4; i++) {
            target.put(targetIndex + i, source.get(sourceIndex + i));
        }
    }

    private static Texture fallbackTexture() {
        ByteBuffer buffer = BufferUtils.createByteBuffer(4);
        buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
        ((Buffer) buffer).rewind();
        Texture texture = new Texture(1, 1, TextureFormat.RGBA8, FilterMode.NEAREST, FilterMode.NEAREST);
        texture.upload(buffer);
        return texture;
    }

    private static final class LoadedImage {
        final ByteBuffer buffer;
        final TextureRegion region;
        final int width, height;
        int x, y;

        LoadedImage(ByteBuffer buffer, TextureRegion region, int width, int height) {
            this.buffer = buffer;
            this.region = region;
            this.width = width;
            this.height = height;
        }
    }
}
