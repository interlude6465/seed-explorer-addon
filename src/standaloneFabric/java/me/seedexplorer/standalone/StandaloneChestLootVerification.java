package me.seedexplorer.standalone;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ItemLoot;
import me.seedexplorer.addon.worldgen.PredictionContext;
import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Pure comparison and diagnostic formatting for a synchronized container opening. */
public final class StandaloneChestLootVerification {
    private static final Object LOG_LOCK = new Object();

    private StandaloneChestLootVerification() {
    }

    public static Result compare(PredictionContext context, List<ChestLootOutput> predictions,
                                 Map<String, Integer> actual) {
        return compare(context, predictions, actual, null);
    }

    public static Result compare(PredictionContext context, List<ChestLootOutput> predictions,
                                 Map<String, Integer> actual, List<ActualStack> actualStacks) {
        List<ChestLootOutput> safePredictions = predictions == null ? List.of() : List.copyOf(predictions);
        Map<String, Integer> safeActual = immutableCounts(actual);
        if (context == null || safePredictions.isEmpty()) {
            return new Result(Status.UNVERIFIED, predictedCounts(safePredictions), safeActual);
        }
        // This compares one observed inventory, not the family's seed-only
        // confidence. A synchronized matching chest is a match even when other
        // generation histories for its family have not been certified.
        return compareContentsNormalized(safePredictions, safeActual, actualStacks);
    }

    /** Compares predicted contents without requiring prior independent certification.
     * This is the oracle path that establishes whether a prediction can become exact. */
    public static Result compareContents(List<ChestLootOutput> predictions,
                                         Map<String, Integer> actual) {
        return compareContents(predictions, actual, null);
    }

    public static Result compareContents(List<ChestLootOutput> predictions,
                                         Map<String, Integer> actual,
                                         List<ActualStack> actualStacks) {
        List<ChestLootOutput> safePredictions = predictions == null ? List.of() : List.copyOf(predictions);
        Map<String, Integer> safeActual = immutableCounts(actual);
        if (safePredictions.isEmpty()) {
            return new Result(Status.UNVERIFIED, Map.of(), safeActual);
        }
        return compareContentsNormalized(safePredictions, safeActual, actualStacks);
    }

    private static Result compareContentsNormalized(List<ChestLootOutput> safePredictions,
                                                    Map<String, Integer> safeActual,
                                                    List<ActualStack> actualStacks) {
        Map<String, Integer> predicted = predictedCounts(safePredictions);
        if (safePredictions.stream().anyMatch(chest -> chest == null
            || chest.lootTableId() == null || chest.lootTableId().isBlank())) {
            return new Result(Status.UNVERIFIED, predicted, safeActual);
        }
        if (!predicted.equals(safeActual)) {
            return new Result(Status.MISMATCH, predicted, safeActual);
        }
        Optional<Map<StackSignature, Integer>> predictedSignatures =
            predictedSignatures(safePredictions);
        if (predictedSignatures.isEmpty()) {
            return new Result(Status.UNVERIFIED, predicted, safeActual);
        }
        if (actualStacks == null) {
            boolean metadataRequired = predictedSignatures.get().keySet().stream()
                .anyMatch(signature -> !signature.enchantments().isEmpty()
                    || signature.damage() != null || signature.potionContents() != null
                    || signature.mapDecorations() != null || signature.suspiciousStewEffects() != null);
            return new Result(metadataRequired ? Status.UNVERIFIED : Status.MATCH,
                predicted, safeActual);
        }
        if (actualStacks.stream().anyMatch(ActualStack::hasUnrepresentedComponents)) {
            return new Result(Status.UNVERIFIED, predicted, safeActual);
        }
        Map<StackSignature, Integer> actualSignatures = actualSignatures(actualStacks);
        return new Result(predictedSignatures.get().equals(actualSignatures)
            ? Status.MATCH : Status.MISMATCH, predicted, safeActual);
    }

    static String diagnostic(PredictionContext context, BlockPos interacted, BlockPos partner,
                             int containerId, List<ChestLootOutput> predictions,
                             Result result, List<String> actualStacks) {
        StringBuilder text = new StringBuilder(512);
        text.append('[').append(Instant.now()).append("] event=loot_mismatch")
            .append(" seed=").append(context == null ? "unknown" : context.worldSeed())
            .append(" version=").append(quote(context == null ? "unknown" : context.minecraftVersion()))
            .append(" dimension=").append(context == null ? "unknown" : context.dimension())
            .append(" containerId=").append(containerId)
            .append(" interacted=").append(position(interacted))
            .append(" partner=").append(position(partner))
            .append(" doubleChest=").append(partner != null)
            .append('\n');
        List<ChestLootOutput> safePredictions = predictions == null ? List.of() : predictions;
        for (int i = 0; i < safePredictions.size(); i++) {
            ChestLootOutput chest = safePredictions.get(i);
            text.append("  predictedChest[").append(i).append("] pos=")
                .append(chest.blockX()).append(',').append(chest.blockY()).append(',').append(chest.blockZ())
                .append(" table=").append(quote(chest.lootTableId()))
                .append(" lootSeed=").append(chest.seed())
                .append(" exact=").append(chest.exact())
                .append(" items=").append(items(chest.predictedItems()))
                .append('\n');
        }
        text.append("  predictedCounts=").append(result.predicted()).append('\n')
            .append("  actualCounts=").append(result.actual()).append('\n')
            .append("  actualStacks=").append(actualStacks == null ? List.of() : actualStacks)
            .append('\n');
        return text.toString();
    }

    static void appendDiagnostic(Path path, String diagnostic) {
        if (path == null || diagnostic == null || diagnostic.isBlank()) return;
        synchronized (LOG_LOCK) {
            try {
                Path parent = path.toAbsolutePath().normalize().getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(path, diagnostic, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception ignored) {
                // A failed diagnostic must never break container interaction.
            }
        }
    }

    private static Map<String, Integer> predictedCounts(List<ChestLootOutput> predictions) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ChestLootOutput chest : predictions) {
            if (chest == null || chest.predictedItems() == null) continue;
            for (ItemLoot item : chest.predictedItems()) {
                if (item == null || item.itemId() == null || item.itemId().isBlank()
                    || item.maxCount() <= 0 || "minecraft:air".equals(item.itemId())) continue;
                counts.merge(item.itemId(), item.maxCount(), Integer::sum);
            }
        }
        return Map.copyOf(counts);
    }

    private static Map<String, Integer> immutableCounts(Map<String, Integer> source) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (source != null) source.forEach((id, count) -> {
            if (id != null && !id.isBlank() && count != null && count > 0) counts.put(id, count);
        });
        return Map.copyOf(counts);
    }

    private static Optional<Map<StackSignature, Integer>> predictedSignatures(
        List<ChestLootOutput> predictions) {
        Map<StackSignature, Integer> signatures = new LinkedHashMap<>();
        for (ChestLootOutput chest : predictions) {
            if (chest == null || chest.predictedItems() == null) continue;
            for (ItemLoot item : chest.predictedItems()) {
                if (item == null || item.itemId() == null || item.itemId().isBlank()
                    || item.maxCount() <= 0 || "minecraft:air".equals(item.itemId())) continue;
                if (item.hasUnrepresentedComponents()) return Optional.empty();
                Optional<Map<String, Integer>> enchantments = predictedEnchantments(item);
                if (enchantments.isEmpty()) return Optional.empty();
                signatures.merge(new StackSignature(item.itemId(), enchantments.get(),
                        item.damage(), item.potionContents(), item.mapDecorations(), item.suspiciousStewEffects()),
                    item.maxCount(), Integer::sum);
            }
        }
        return Optional.of(Map.copyOf(signatures));
    }

    private static Optional<Map<String, Integer>> predictedEnchantments(ItemLoot item) {
        String summary = item.enchantmentSummary();
        if (summary != null && !summary.isBlank()) return parseEnchantmentSummary(summary);
        if (item.enchantmentId() != null && !item.enchantmentId().isBlank()
            && item.enchantmentLevel() > 0) {
            return Optional.of(Map.of(normalizeId(item.enchantmentId()), item.enchantmentLevel()));
        }
        if (item.randomEnchantment() || item.levelsEnchantment()
            || item.enchantmentId() != null && !item.enchantmentId().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Map.of());
    }

    private static Optional<Map<String, Integer>> parseEnchantmentSummary(String summary) {
        if (summary.startsWith("Enchanted level ")) return Optional.empty();
        Map<String, Integer> enchantments = new LinkedHashMap<>();
        for (String part : summary.split(",")) {
            String value = part.trim();
            int split = value.lastIndexOf(' ');
            if (split <= 0 || split >= value.length() - 1) return Optional.empty();
            int level = romanLevel(value.substring(split + 1));
            if (level <= 0) return Optional.empty();
            String id = "minecraft:" + value.substring(0, split).trim().toLowerCase()
                .replace(' ', '_');
            enchantments.put(id, level);
        }
        return Optional.of(Map.copyOf(enchantments));
    }

    private static int romanLevel(String roman) {
        return switch (roman) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            case "VI" -> 6;
            case "VII" -> 7;
            case "VIII" -> 8;
            case "IX" -> 9;
            case "X" -> 10;
            default -> -1;
        };
    }

    private static Map<StackSignature, Integer> actualSignatures(List<ActualStack> stacks) {
        Map<StackSignature, Integer> signatures = new LinkedHashMap<>();
        if (stacks != null) {
            for (ActualStack stack : stacks) {
                if (stack == null || stack.itemId() == null || stack.itemId().isBlank()
                    || stack.count() <= 0) continue;
                signatures.merge(new StackSignature(stack.itemId(), stack.enchantments(),
                        stack.damage(), stack.potionContents(), stack.mapDecorations(), stack.suspiciousStewEffects()),
                    stack.count(), Integer::sum);
            }
        }
        return Map.copyOf(signatures);
    }

    private static String normalizeId(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static String items(List<ItemLoot> items) {
        if (items == null || items.isEmpty()) return "[]";
        StringBuilder text = new StringBuilder("[");
        for (ItemLoot item : items) {
            if (text.length() > 1) text.append(", ");
            text.append(item.itemId()).append('x').append(item.minCount());
            if (item.maxCount() != item.minCount()) text.append('-').append(item.maxCount());
            if (item.enchantmentSummary() != null && !item.enchantmentSummary().isBlank()) {
                text.append("{").append(item.enchantmentSummary()).append('}');
            } else if (item.enchantmentId() != null && !item.enchantmentId().isBlank()) {
                text.append("{").append(item.enchantmentId()).append(':').append(item.enchantmentLevel()).append('}');
            }
        }
        return text.append(']').toString();
    }

    private static String position(BlockPos position) {
        return position == null ? "none" : position.getX() + "," + position.getY() + "," + position.getZ();
    }

    private static String quote(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public enum Status {
        MATCH,
        MISMATCH,
        UNVERIFIED
    }

    public record Result(Status status, Map<String, Integer> predicted, Map<String, Integer> actual) {
        public Result {
            predicted = Map.copyOf(predicted);
            actual = Map.copyOf(actual);
        }
    }

    public record ActualStack(String itemId, int count, Map<String, Integer> enchantments,
                              Integer damage, String potionContents,
                              boolean hasUnrepresentedComponents,
                              String mapDecorations, String suspiciousStewEffects) {
        public ActualStack(String itemId, int count, Map<String, Integer> enchantments,
                           Integer damage, String potionContents, boolean hasUnrepresentedComponents) {
            this(itemId, count, enchantments, damage, potionContents, hasUnrepresentedComponents, null, null);
        }
        public ActualStack(String itemId, int count, Map<String, Integer> enchantments) {
            this(itemId, count, enchantments, null, null, false);
        }

        public ActualStack {
            enchantments = enchantments == null ? Map.of() : Map.copyOf(enchantments);
        }
    }

    private record StackSignature(String itemId, Map<String, Integer> enchantments,
                                  Integer damage, String potionContents,
                                  String mapDecorations, String suspiciousStewEffects) {
        StackSignature {
            enchantments = Map.copyOf(enchantments);
        }
    }

    /** Generation token that turns a synchronized snapshot into at most one report per opening. */
    static final class OpeningGate {
        private long current;
        private long reported = Long.MIN_VALUE;

        long begin() {
            return ++current;
        }

        boolean claim(long token, boolean synchronizedSnapshot) {
            if (!synchronizedSnapshot || token != current || reported == token) return false;
            reported = token;
            return true;
        }
    }
}
