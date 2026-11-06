package me.seedexplorer.addon.render;

/** Renderer-only material filtering that does not depend on client graphics state. */
public final class TerrainMaterialPolicy {
    public static final int NETHER_ROOF_CUTOFF_Y = 120;

    private TerrainMaterialPolicy() {
    }

    public static boolean isLeaf(String blockId) {
        return blockId != null && (blockId.endsWith("_leaves") || blockId.contains(":leaves"));
    }

    public static boolean renderPreviewMaterial(String blockId) {
        return !isLeaf(blockId);
    }

    public static boolean renderAtlasMaterial(boolean usesFallback) {
        return !usesFallback;
    }

    public static boolean validPredictedSurface(int dimension, String blockId) {
        if (blockId == null || blockId.endsWith(":air")) return false;
        return dimension != 1 || !blockId.endsWith(":bedrock");
    }

    /**
     * True for blocks that may define the terrain heightfield. Decorations, trees,
     * vegetation and partial construction blocks must not become vertical cliffs.
     */
    public static boolean terrainSupportingSurface(String blockId) {
        if (blockId == null || blockId.endsWith(":air")) return false;
        String id = blockId.substring(blockId.indexOf(':') + 1);
        return !(id.endsWith("_leaves")
            || id.endsWith("_log")
            || id.endsWith("_wood")
            || id.endsWith("_stem")
            || id.endsWith("_hyphae")
            || id.endsWith("_sapling")
            || id.endsWith("_flower")
            || id.endsWith("_tulip")
            || id.endsWith("_mushroom")
            || id.endsWith("_roots")
            || id.endsWith("_crop")
            || id.endsWith("_torch")
            || id.endsWith("_lantern")
            || id.endsWith("_fence")
            || id.endsWith("_fence_gate")
            || id.endsWith("_wall")
            || id.endsWith("_sign")
            || id.endsWith("_banner")
            || id.endsWith("_rail")
            || id.endsWith("_carpet")
            || id.endsWith("_button")
            || id.endsWith("_pressure_plate")
            || id.endsWith("_door")
            || id.endsWith("_trapdoor")
            || id.endsWith("_slab")
            || id.endsWith("_stairs")
            || id.contains("grass") && !id.endsWith("grass_block")
            || id.contains("fern")
            || id.contains("vine")
            || id.contains("lichen")
            || id.contains("bamboo")
            || id.contains("cactus")
            || id.contains("sugar_cane")
            || id.contains("kelp")
            || id.contains("seagrass")
            || id.equals("snow")
            || id.equals("wheat")
            || id.equals("carrots")
            || id.equals("potatoes")
            || id.equals("beetroots")
            || id.equals("torchflower")
            || id.equals("pitcher_plant")
            || id.equals("dead_bush")
            || id.equals("lily_pad")
            || id.equals("torch")
            || id.equals("lantern")
            || id.equals("soul_lantern")
            || id.equals("lever")
            || id.equals("tripwire")
            || id.equals("redstone_wire")
            || id.equals("chain")
            || id.equals("end_rod")
            || id.equals("dandelion")
            || id.equals("poppy")
            || id.equals("blue_orchid")
            || id.equals("allium")
            || id.equals("azure_bluet")
            || id.equals("oxeye_daisy")
            || id.equals("cornflower")
            || id.equals("lily_of_the_valley")
            || id.equals("wither_rose")
            || id.equals("sunflower")
            || id.equals("lilac")
            || id.equals("rose_bush")
            || id.equals("peony")
            || id.equals("pink_petals")
            || id.equals("spore_blossom")
            || id.equals("hanging_roots")
            || id.equals("cocoa"));
    }

    public static boolean includeCaveSample(int dimension, int blockY, String blockId) {
        if (blockId == null || blockId.endsWith(":air")) return false;
        return dimension != -1 || blockY < NETHER_ROOF_CUTOFF_Y;
    }

    /** Coarse cave cells never merge different block materials into one large face. */
    public static boolean sameCaveMaterial(String firstBlockId, String secondBlockId) {
        return firstBlockId != null && firstBlockId.equals(secondBlockId);
    }

    /** Cave samples emit block-height faces instead of tall LOD sampling pillars. */
    public static int caveFaceHeight() {
        return 1;
    }

    /** Exposure is tested against the actual adjacent block, independent of LOD. */
    public static int exposureNeighborDistance() {
        return 1;
    }
}
