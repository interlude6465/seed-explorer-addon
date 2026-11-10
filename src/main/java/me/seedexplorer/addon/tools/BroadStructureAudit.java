package me.seedexplorer.addon.tools;

import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ChestLootPredictor;
import me.seedexplorer.addon.loot.LootTableDef;
import me.seedexplorer.addon.loot.LootTableSimulator;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.loot.VanillaLootTables;
import me.seedexplorer.addon.preview.StructurePreviewModel;
import me.seedexplorer.addon.preview.StructurePreviewSimulator;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.structures.StructureType;
import me.seedexplorer.addon.worldgen.VanillaStructurePredictor;
import me.seedexplorer.addon.worldgen.WorldgenEngine;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Broad deterministic audit over fresh seeds. This is not an exhaustive proof of
 * every possible world, but it catches parity regressions across the main
 * prediction, preview, bastion-variant, end-city-ship and validated-loot paths.
 */
public final class BroadStructureAudit {
    private static final long[] SEEDS = {
        -7801145139589312273L,
        6483491120120643991L,
        -4187258156302846070L,
        884742019650328622L,
        5339378137284509821L,
        -2567785255138823611L,
        1185218911247785193L,
        -6672388709973824502L,
        3928493341590147004L,
        -1345789162450062188L
    };
    private static final int SEARCH_RADIUS = 256;
    private static final int MAX_CANDIDATES_PER_TYPE = 6;
    private static final int MAX_LOOT_CANDIDATES_PER_TYPE = 5;

    private static final Map<StructureType, ResourceKey<Structure>> EXACT_KEYS = new EnumMap<>(StructureType.class);
    /** Every product loot family driven by a vanilla structure-start replay.
     * Dungeons use their separate placed-feature oracle and are not included. */
    private static final Set<StructureType> AUDITED_LOOT_TYPES = EnumSet.of(
        StructureType.DESERT_PYRAMID,
        StructureType.STRONGHOLD,
        StructureType.SHIPWRECK,
        StructureType.OUTPOST,
        StructureType.JUNGLE_TEMPLE,
        StructureType.TREASURE,
        StructureType.MINESHAFT,
        StructureType.RUINED_PORTAL,
        StructureType.OCEAN_RUIN,
        StructureType.IGLOO,
        StructureType.ANCIENT_CITY,
        StructureType.MANSION,
        StructureType.END_CITY,
        StructureType.FORTRESS,
        StructureType.BASTION,
        StructureType.NETHER_RUINED_PORTAL,
        StructureType.ABANDONED_CAMP
    );

    static {
        EXACT_KEYS.put(StructureType.END_CITY, BuiltinStructures.END_CITY);
        EXACT_KEYS.put(StructureType.DESERT_PYRAMID, BuiltinStructures.DESERT_PYRAMID);
        EXACT_KEYS.put(StructureType.JUNGLE_TEMPLE, BuiltinStructures.JUNGLE_TEMPLE);
        EXACT_KEYS.put(StructureType.WITCH_HUT, BuiltinStructures.SWAMP_HUT);
        EXACT_KEYS.put(StructureType.IGLOO, BuiltinStructures.IGLOO);
        EXACT_KEYS.put(StructureType.OUTPOST, BuiltinStructures.PILLAGER_OUTPOST);
        EXACT_KEYS.put(StructureType.MONUMENT, BuiltinStructures.OCEAN_MONUMENT);
        EXACT_KEYS.put(StructureType.MANSION, BuiltinStructures.WOODLAND_MANSION);
        EXACT_KEYS.put(StructureType.ANCIENT_CITY, BuiltinStructures.ANCIENT_CITY);
        EXACT_KEYS.put(StructureType.TRIAL_CHAMBER, BuiltinStructures.TRIAL_CHAMBERS);
        EXACT_KEYS.put(StructureType.TRAIL_RUINS, BuiltinStructures.TRAIL_RUINS);
        EXACT_KEYS.put(StructureType.STRONGHOLD, BuiltinStructures.STRONGHOLD);
        EXACT_KEYS.put(StructureType.SHIPWRECK, BuiltinStructures.SHIPWRECK);
        EXACT_KEYS.put(StructureType.MINESHAFT, BuiltinStructures.MINESHAFT);
        EXACT_KEYS.put(StructureType.TREASURE, BuiltinStructures.BURIED_TREASURE);
        EXACT_KEYS.put(StructureType.FORTRESS, BuiltinStructures.FORTRESS);
        EXACT_KEYS.put(StructureType.BASTION, BuiltinStructures.BASTION_REMNANT);
        EXACT_KEYS.put(StructureType.NETHER_RUINED_PORTAL, BuiltinStructures.RUINED_PORTAL_NETHER);
        EXACT_KEYS.put(StructureType.NETHER_FOSSIL, BuiltinStructures.NETHER_FOSSIL);
    }

    private BroadStructureAudit() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable throwable) {
            if (throwable instanceof AssertionError) throw throwable;
            StringWriter stack = new StringWriter();
            throwable.printStackTrace(new PrintWriter(stack));
            String report = "broad_structure_audit_exception=" + throwable.getClass().getName()
                + ": " + throwable.getMessage() + "\n" + stack;
            System.out.print(report);
            writeReport(report);
            throw throwable;
        }
    }

    private static void run(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        String phase = args.length == 0 ? "all" : args[0].toLowerCase(Locale.ROOT);
        long[] seeds = args.length > 1 ? parseSeeds(args[1]) : SEEDS;
        boolean auditStarts = phase.equals("all") || phase.equals("starts");
        boolean auditVariants = phase.equals("all") || phase.equals("variants");
        boolean auditLoot = phase.equals("all") || phase.equals("loot");
        boolean auditPreview = phase.equals("all") || phase.equals("preview");
        if (!auditStarts && !auditVariants && !auditLoot && !auditPreview) {
            throw new IllegalArgumentException("Unknown audit phase: " + phase);
        }
        if (auditLoot || auditPreview) VanillaLootTables.loadAllFromBundled();

        List<String> failures = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        report.append("phase=").append(phase).append('\n');
        report.append("seed_count=").append(seeds.length).append('\n');
        Set<String> bastionVariants = new HashSet<>();
        int checkedStarts = 0;
        int validStarts = 0;
        int checkedLootStructures = 0;
        int checkedLootContainers = 0;
        Map<StructureType, Integer> checkedLootByType = new EnumMap<>(StructureType.class);
        int checkedPreviewChestContainment = 0;
        int checkedEndCities = 0;

        for (long seed : seeds) {
            report.append("seed=").append(seed).append('\n');
            for (int dimension : new int[]{0, -1, 1}) {
                List<GeneratedStructure> structures = VanillaStructurePredictor.predictDimension(
                    seed, dimension, -SEARCH_RADIUS, -SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS, true, true);
                report.append("  dimension=").append(dimension)
                    .append(" predicted=").append(structures.size()).append('\n');

                if (auditStarts || auditVariants) {
                    Map<StructureType, Integer> seenByType = new EnumMap<>(StructureType.class);
                    for (GeneratedStructure structure : structures) {
                        int seen = seenByType.merge(structure.type, 1, Integer::sum);
                        if (seen > MAX_CANDIDATES_PER_TYPE) continue;

                        ResourceKey<Structure> key = structureKey(seed, structure);
                        if (key == null) continue;
                        StructureStart start;
                        try {
                            start = WorldgenEngine.generateSelectedStructureStart(
                                seed, structure.type.dimension, key, new ChunkPos(structure.startChunkX, structure.startChunkZ));
                        } catch (Throwable throwable) {
                            report.append("    start_generation_error type=").append(structure.type)
                                .append(" chunk=").append(structure.startChunkX).append(',').append(structure.startChunkZ)
                                .append(" error=").append(throwable.getClass().getSimpleName())
                                .append(": ").append(throwable.getMessage()).append('\n');
                            continue;
                        }
                        checkedStarts++;
                        if (!start.isValid()) {
                            report.append("    invalid_start type=").append(structure.type)
                                .append(" chunk=").append(structure.startChunkX).append(',').append(structure.startChunkZ)
                                .append('\n');
                            continue;
                        }
                        validStarts++;

                        if (auditVariants && structure.type == StructureType.BASTION) {
                            String derived = deriveBastionVariant(start);
                            bastionVariants.add(structure.variant);
                            if (!derived.isBlank() && !structure.variant.equals(derived)) {
                                failures.add("bastion_variant_mismatch seed=" + seed
                                    + " predicted=" + structure.variant + " derived=" + derived
                                    + " chunk=" + structure.startChunkX + "," + structure.startChunkZ);
                            }
                        }

                        if (auditVariants && structure.type == StructureType.END_CITY) {
                            checkedEndCities++;
                            boolean derivedShip = startHasTemplate(start, "ship");
                            if (structure.hasShip != derivedShip) {
                                failures.add("end_city_ship_mismatch seed=" + seed
                                    + " predicted=" + structure.hasShip + " derived=" + derivedShip
                                    + " chunk=" + structure.startChunkX + "," + structure.startChunkZ);
                            }
                        }
                    }
                }

                if (!auditLoot && !auditPreview) continue;
                for (StructureType type : AUDITED_LOOT_TYPES) {
                    if (type.dimension != dimension) continue;
                    int checkedForType = 0;
                    for (GeneratedStructure structure : structures) {
                        if (structure.type != type) continue;
                        if (checkedForType++ >= MAX_LOOT_CANDIDATES_PER_TYPE) break;
                        ResourceKey<Structure> key = structureKey(seed, structure);
                        if (key == null) continue;
                        List<VanillaLootStructureSimulator.SimulatedContainer> containers;
                        try {
                            containers = VanillaLootStructureSimulator.simulate(seed, dimension, key,
                                structure.startChunkX, structure.startChunkZ);
                        } catch (Throwable throwable) {
                            report.append("    loot_simulation_error type=").append(structure.type)
                                .append(" chunk=").append(structure.startChunkX).append(',').append(structure.startChunkZ)
                                .append(" error=").append(throwable.getClass().getSimpleName())
                                .append(": ").append(throwable.getMessage()).append('\n');
                            continue;
                        }
                        if (containers.isEmpty()) continue;

                        checkedLootStructures++;
                        checkedLootByType.merge(type, 1, Integer::sum);
                        checkedLootContainers += containers.size();
                        if (auditLoot) {
                            assertLootDeterministic(failures, seed, structure, containers);
                            assertProductLootPath(failures, seed, structure, containers);
                        }
                        // Minecart chests are entities, not preview blocks. Their
                        // positions and loot are covered by the deterministic
                        // replay and saved-world oracle instead.
                        if (auditPreview && type != StructureType.MINESHAFT) {
                            checkedPreviewChestContainment += assertPreviewContainsChests(failures, seed, structure, containers);
                        }
                        break;
                    }
                }
            }
        }

        if (auditVariants) {
            for (String expected : List.of("Housing Units", "Hoglin Stables", "Treasure Room", "Bridge")) {
                if (!bastionVariants.contains(expected)) failures.add("missing_bastion_variant " + expected);
            }
            if (checkedEndCities < 50) failures.add("end_city_ship_audit_too_small checked=" + checkedEndCities);
        }
        if (auditLoot || auditPreview) {
            for (StructureType type : AUDITED_LOOT_TYPES) {
                if (checkedLootByType.getOrDefault(type, 0) == 0) {
                    failures.add("loot_audit_missing_structure_type " + type);
                }
            }
        }

        report.append("checked_starts=").append(checkedStarts)
            .append(" valid_starts=").append(validStarts).append('\n');
        report.append("checked_end_cities=").append(checkedEndCities).append('\n');
        report.append("bastion_variants=").append(bastionVariants).append('\n');
        report.append("checked_loot_structures=").append(checkedLootStructures)
            .append(" checked_loot_containers=").append(checkedLootContainers).append('\n');
        report.append("checked_loot_by_type=").append(checkedLootByType).append('\n');
        report.append("checked_preview_chest_containment=").append(checkedPreviewChestContainment).append('\n');
        report.append("broad_structure_audit_pass=").append(failures.isEmpty())
            .append(" failures=").append(failures.size()).append('\n');
        for (String failure : failures) report.append("failure=").append(failure).append('\n');

        System.out.print(report);
        writeReport(phase, report.toString());
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
    }

    private static long[] parseSeeds(String csv) {
        String[] parts = csv.split(",");
        List<Long> parsed = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) parsed.add(Long.parseLong(trimmed));
        }
        if (parsed.isEmpty()) throw new IllegalArgumentException("No seeds provided");
        long[] result = new long[parsed.size()];
        for (int i = 0; i < parsed.size(); i++) result[i] = parsed.get(i);
        return result;
    }

    private static ResourceKey<Structure> structureKey(long seed, GeneratedStructure structure) {
        if (structure.type == StructureType.VILLAGE) {
            String biomeId = WorldgenEngine.getBiome(seed, structure.type.dimension, structure.x, 64, structure.z).id();
            return switch (biomeId) {
                case "minecraft:desert" -> BuiltinStructures.VILLAGE_DESERT;
                case "minecraft:savanna" -> BuiltinStructures.VILLAGE_SAVANNA;
                case "minecraft:snowy_plains" -> BuiltinStructures.VILLAGE_SNOWY;
                case "minecraft:taiga" -> BuiltinStructures.VILLAGE_TAIGA;
                default -> BuiltinStructures.VILLAGE_PLAINS;
            };
        }
        if (structure.type == StructureType.RUINED_PORTAL) {
            return switch (structure.variant) {
                case "minecraft:ruined_portal_desert" -> BuiltinStructures.RUINED_PORTAL_DESERT;
                case "minecraft:ruined_portal_jungle" -> BuiltinStructures.RUINED_PORTAL_JUNGLE;
                case "minecraft:ruined_portal_swamp" -> BuiltinStructures.RUINED_PORTAL_SWAMP;
                case "minecraft:ruined_portal_mountain" -> BuiltinStructures.RUINED_PORTAL_MOUNTAIN;
                case "minecraft:ruined_portal_ocean" -> BuiltinStructures.RUINED_PORTAL_OCEAN;
                default -> BuiltinStructures.RUINED_PORTAL_STANDARD;
            };
        }
        if (structure.type == StructureType.MINESHAFT) {
            return structure.variant.equals("minecraft:mineshaft_mesa")
                ? BuiltinStructures.MINESHAFT_MESA : BuiltinStructures.MINESHAFT;
        }
        if (structure.type == StructureType.SHIPWRECK) {
            String biomeId = WorldgenEngine.getBiome(seed, structure.type.dimension, structure.x, 64, structure.z).id();
            return biomeId.equals("minecraft:beach") || biomeId.equals("minecraft:snowy_beach")
                ? BuiltinStructures.SHIPWRECK_BEACHED
                : BuiltinStructures.SHIPWRECK;
        }
        if (structure.type == StructureType.OCEAN_RUIN) {
            String biomeId = WorldgenEngine.getBiome(seed, structure.type.dimension, structure.x, 64, structure.z).id();
            return biomeId.contains("warm") ? BuiltinStructures.OCEAN_RUIN_WARM : BuiltinStructures.OCEAN_RUIN_COLD;
        }
        if (structure.type == StructureType.ABANDONED_CAMP
            && structure.variant.startsWith("minecraft:abandoned_camp_")) {
            return ResourceKey.create(Registries.STRUCTURE,
                Identifier.parse(structure.variant));
        }
        return EXACT_KEYS.get(structure.type);
    }

    private static String deriveBastionVariant(StructureStart start) {
        String templates = startTemplates(start).toLowerCase(Locale.ROOT);
        if (templates.contains("bastion/bridge/")) return "Bridge";
        if (templates.contains("bastion/hoglin_stable/")) return "Hoglin Stables";
        if (templates.contains("bastion/treasure/")) return "Treasure Room";
        if (templates.contains("bastion/units/")) return "Housing Units";
        return "";
    }

    private static boolean startHasTemplate(StructureStart start, String token) {
        return startTemplates(start).toLowerCase(Locale.ROOT).contains(token);
    }

    private static String startTemplates(StructureStart start) {
        StringBuilder sb = new StringBuilder();
        for (var piece : start.getPieces()) {
            if (piece instanceof TemplateStructurePiece templatePiece) {
                String name = templateName(templatePiece);
                if (name != null) sb.append(name).append('\n');
            }
            if (piece instanceof PoolElementStructurePiece poolPiece
                && poolPiece.getElement() instanceof SinglePoolElement single) {
                sb.append(single.getTemplateLocation()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String templateName(TemplateStructurePiece piece) {
        try {
            Field field = TemplateStructurePiece.class.getDeclaredField("templateName");
            field.setAccessible(true);
            Object value = field.get(piece);
            return value instanceof String name ? name : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void assertLootDeterministic(List<String> failures, long seed, GeneratedStructure structure,
                                                List<VanillaLootStructureSimulator.SimulatedContainer> containers) {
        List<VanillaLootStructureSimulator.SimulatedContainer> repeated =
            VanillaLootStructureSimulator.simulate(seed, structure.type.dimension, structureKey(seed, structure),
                structure.startChunkX, structure.startChunkZ);
        if (!containerSignature(containers).equals(containerSignature(repeated))) {
            failures.add("loot_container_nondeterministic seed=" + seed + " type=" + structure.type
                + " chunk=" + structure.startChunkX + "," + structure.startChunkZ);
        }
        for (VanillaLootStructureSimulator.SimulatedContainer container : containers) {
            LootTableDef table = VanillaLootTables.get(container.lootTableId());
            if (table == null) {
                failures.add("missing_loot_table " + container.lootTableId());
                continue;
            }
            List<ItemLoot> first = LootTableSimulator.simulate(table, container.lootSeed(), 64);
            List<ItemLoot> second = LootTableSimulator.simulate(table, container.lootSeed(), 64);
            if (!first.equals(second)) {
                failures.add("loot_items_nondeterministic table=" + container.lootTableId()
                    + " seed=" + container.lootSeed());
            }
        }
    }

    private static void assertProductLootPath(List<String> failures, long seed,
                                              GeneratedStructure structure,
                                              List<VanillaLootStructureSimulator.SimulatedContainer> containers) {
        List<ChestLootOutput> product = ChestLootPredictor.predictForStructure(seed, structure, 64);
        Set<String> expected = new HashSet<>();
        for (VanillaLootStructureSimulator.SimulatedContainer container : containers) {
            expected.add(container.x() + "," + container.y() + "," + container.z()
                + ':' + container.lootTableId() + ':' + container.lootSeed());
        }
        Set<String> actual = new HashSet<>();
        for (ChestLootOutput chest : product) {
            actual.add(chest.blockX() + "," + chest.blockY() + "," + chest.blockZ()
                + ':' + chest.lootTableId() + ':' + chest.seed());
        }
        if (actual.size() != product.size()) {
            failures.add("product_loot_duplicate_container seed=" + seed + " type="
                + structure.type + " chunk=" + structure.startChunkX + ',' + structure.startChunkZ);
        }
        if (!actual.equals(expected)) {
            failures.add("product_loot_container_mismatch seed=" + seed + " type="
                + structure.type + " chunk=" + structure.startChunkX + ',' + structure.startChunkZ
                + " expected=" + expected.size() + " actual=" + actual.size());
        }
        for (ChestLootOutput chest : product) {
            VanillaLootStructureSimulator.SimulatedContainer target = containers.stream()
                .filter(container -> container.x() == chest.blockX()
                    && container.y() == chest.blockY()
                    && container.z() == chest.blockZ()
                    && container.lootSeed() == chest.seed()
                    && container.lootTableId().equals(chest.lootTableId()))
                .findFirst().orElse(null);
            List<ItemLoot> vanilla = target == null ? null
                : expectedInventoryItems(seed, structure.type.dimension, target, containers);
            if (vanilla != null && !vanilla.equals(chest.predictedItems())) {
                failures.add("product_loot_items_not_vanilla seed=" + seed + " type="
                    + structure.type + " chest=" + chest.blockX() + ',' + chest.blockY()
                    + ',' + chest.blockZ());
            }
        }
    }

    private static List<ItemLoot> expectedInventoryItems(
        long worldSeed, int dimension,
        VanillaLootStructureSimulator.SimulatedContainer target,
        List<VanillaLootStructureSimulator.SimulatedContainer> all
    ) {
        List<ItemLoot> merged = new ArrayList<>();
        for (VanillaLootStructureSimulator.SimulatedContainer member
            : sharedChestMembers(target, all)) {
            List<ItemLoot> items = LootTableSimulator.simulateVanilla(member.lootTableId(),
                member.lootSeed(), worldSeed, dimension,
                member.x(), member.y(), member.z());
            if (items == null) return null;
            for (ItemLoot item : items) mergeItem(merged, item);
        }
        return List.copyOf(merged);
    }

    private static List<VanillaLootStructureSimulator.SimulatedContainer> sharedChestMembers(
        VanillaLootStructureSimulator.SimulatedContainer target,
        List<VanillaLootStructureSimulator.SimulatedContainer> all
    ) {
        List<VanillaLootStructureSimulator.SimulatedContainer> members = new ArrayList<>();
        members.add(target);
        if (!target.pairedChest()) return members;
        for (VanillaLootStructureSimulator.SimulatedContainer candidate : all) {
            if (candidate == target || !candidate.pairedChest()) continue;
            if (!candidate.lootTableId().equals(target.lootTableId())) continue;
            if (candidate.y() != target.y()) continue;
            boolean adjacent = candidate.x() == target.x()
                && Math.abs(candidate.z() - target.z()) == 1
                || candidate.z() == target.z()
                && Math.abs(candidate.x() - target.x()) == 1;
            if (adjacent) members.add(candidate);
        }
        return members;
    }

    private static void mergeItem(List<ItemLoot> merged, ItemLoot item) {
        for (int i = 0; i < merged.size(); i++) {
            ItemLoot prior = merged.get(i);
            if (!prior.itemId().equals(item.itemId())
                || !java.util.Objects.equals(prior.enchantmentSummary(),
                    item.enchantmentSummary())) continue;
            int count = prior.minCount() + item.minCount();
            merged.set(i, new ItemLoot(prior.itemId(), count, count, 0,
                prior.displayName(), prior.randomEnchantment(), prior.enchantmentId(),
                prior.enchantmentLevel(), null, false, 0, 0,
                prior.enchantmentSummary(), false));
            return;
        }
        merged.add(item);
    }

    private static String containerSignature(List<VanillaLootStructureSimulator.SimulatedContainer> containers) {
        StringBuilder sb = new StringBuilder();
        containers.stream()
            .sorted((a, b) -> {
                int c = Integer.compare(a.x(), b.x());
                if (c != 0) return c;
                c = Integer.compare(a.y(), b.y());
                if (c != 0) return c;
                c = Integer.compare(a.z(), b.z());
                if (c != 0) return c;
                return a.lootTableId().compareTo(b.lootTableId());
            })
            .forEach(c -> sb.append(c.x()).append(',').append(c.y()).append(',').append(c.z())
                .append(':').append(c.lootTableId()).append(':').append(c.lootSeed())
                .append(':').append(c.pairedChest()).append('\n'));
        return sb.toString();
    }

    private static int assertPreviewContainsChests(List<String> failures, long seed, GeneratedStructure structure,
                                                   List<VanillaLootStructureSimulator.SimulatedContainer> containers) {
        StructurePreviewModel preview = StructurePreviewSimulator.preview(structure, seed);
        if (preview.isEmpty()) {
            failures.add("empty_preview_for_loot_structure seed=" + seed + " type=" + structure.type
                + " chunk=" + structure.startChunkX + "," + structure.startChunkZ);
            return 0;
        }
        Set<String> previewPositions = new HashSet<>();
        for (StructurePreviewModel.PreviewBlock block : preview.blocks()) {
            previewPositions.add(block.x() + "," + block.y() + "," + block.z());
        }
        int checked = 0;
        for (VanillaLootStructureSimulator.SimulatedContainer container : containers) {
            checked++;
            String key = container.x() + "," + container.y() + "," + container.z();
            if (!previewPositions.contains(key)) {
                failures.add("preview_missing_chest seed=" + seed + " type=" + structure.type
                    + " chest=" + key + " chunk=" + structure.startChunkX + "," + structure.startChunkZ);
            }
        }
        return checked;
    }

    private static void writeReport(String report) {
        writeReport("exception", report);
    }

    private static void writeReport(String phase, String report) {
        try {
            Path path = Path.of("build", "reports", "broad-structure-audit-" + phase + ".txt");
            Files.createDirectories(path.getParent());
            Files.writeString(path, report);
        } catch (IOException ignored) {
        }
    }
}
