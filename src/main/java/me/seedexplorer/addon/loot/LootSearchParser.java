package me.seedexplorer.addon.loot;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Parses forgiving natural-language item and enchanted-book queries. */
public final class LootSearchParser {
    private static final Pattern LEVEL_NUMBER = Pattern.compile("\\b([0-9]+)\\b");

    private LootSearchParser() {
    }

    public static LootSearchTerm parseTerm(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.trim().toLowerCase(Locale.ROOT).replace('-', ' ');
        if (text.contains("book")) {
            int level = parseLevel(text);
            String enchantment = text.replaceAll("\\b(enchanted|books?|level|lvl)\\b", " ")
                .replaceAll("\\b(?:[0-9]+|i{1,4}|v|vi{0,3}|ix|x)\\b", " ")
                .trim().replaceAll("\\s+", "_");
            if (!enchantment.isBlank()) {
                return new LootSearchTerm("minecraft:enchanted_book", enchantment, level,
                    titleWords(enchantment) + (level > 0 ? " " + roman(level) : "") + " Book");
            }
        }

        String itemId = bestItemMatch(text);
        if (itemId == null) return null;
        return termForItem(itemId);
    }

    /** Returns ranked, clickable item-name suggestions for the search editor. */
    public static List<LootSearchTerm> suggest(String raw, int limit) {
        if (raw == null || raw.isBlank() || limit <= 0) return List.of();
        String query = raw.trim().toLowerCase(Locale.ROOT).replace('-', ' ');
        String normalized = normalize(query);
        String sortedQuery = sortedTokens(query);
        if (normalized.isBlank()) return List.of();

        List<ItemSuggestion> suggestions = new ArrayList<>();
        for (var item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (id.endsWith(":air")) continue;
            String path = id.substring(id.indexOf(':') + 1).replace('_', ' ');
            String display = item.getName(item.getDefaultInstance()).getString();
            int score = Math.max(matchScore(normalized, sortedQuery, path),
                matchScore(normalized, sortedQuery, display));
            if (score >= suggestionThreshold(normalized)) {
                suggestions.add(new ItemSuggestion(termForItem(id), score));
            }
        }
        suggestions.sort(Comparator.comparingInt(ItemSuggestion::score).reversed()
            .thenComparing(suggestion -> suggestion.term().display()));
        return suggestions.stream().limit(limit).map(ItemSuggestion::term).toList();
    }

    private static LootSearchTerm termForItem(String itemId) {
        var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(itemId)).orElse(null);
        String display = item == null ? titleWords(itemId.substring(itemId.indexOf(':') + 1))
            : item.getName(item.getDefaultInstance()).getString();
        return new LootSearchTerm(itemId, null, 0, display);
    }

    private static String bestItemMatch(String query) {
        String normalized = normalize(query);
        if (normalized.isBlank()) return null;
        String sortedQuery = sortedTokens(query);
        String bestId = null;
        int bestScore = Integer.MIN_VALUE;
        for (var item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            String path = id.substring(id.indexOf(':') + 1).replace('_', ' ');
            String display = item.getName(item.getDefaultInstance()).getString();
            for (String candidate : List.of(path, display)) {
                int score = matchScore(normalized, sortedQuery, candidate);
                if (score > bestScore) {
                    bestScore = score;
                    bestId = id;
                }
            }
        }
        return bestScore >= 300 ? bestId : null;
    }

    private static int matchScore(String normalizedQuery, String sortedQuery, String candidate) {
        String normalizedCandidate = normalize(candidate);
        if (normalizedCandidate.equals(normalizedQuery)) return 1000;
        if (sortedTokens(candidate).equals(sortedQuery)) return 900;
        if (normalizedCandidate.startsWith(normalizedQuery)) return 780 - normalizedCandidate.length();
        if (normalizedCandidate.contains(normalizedQuery)) return 650 - normalizedCandidate.length();
        return 420 - levenshtein(sortedQuery, sortedTokens(candidate)) * 28;
    }

    private static int suggestionThreshold(String normalizedQuery) {
        return normalizedQuery.length() <= 2 ? 620 : 300;
    }

    private static String sortedTokens(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            .filter(token -> !token.isBlank()).map(LootSearchParser::singularize)
            .sorted().collect(Collectors.joining(""));
    }

    private static int parseLevel(String text) {
        Matcher number = LEVEL_NUMBER.matcher(text);
        if (number.find()) return Integer.parseInt(number.group(1));
        for (String token : text.split("[^a-z]+")) {
            int level = switch (token) {
                case "i" -> 1;
                case "ii" -> 2;
                case "iii" -> 3;
                case "iv" -> 4;
                case "v" -> 5;
                case "vi" -> 6;
                case "vii" -> 7;
                case "viii" -> 8;
                case "ix" -> 9;
                case "x" -> 10;
                default -> 0;
            };
            if (level > 0) return level;
        }
        return 0;
    }

    private static String titleWords(String value) {
        StringBuilder out = new StringBuilder();
        for (String word : value.replace(':', '_').split("_+")) {
            if (word.isBlank() || word.equals("minecraft")) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> Integer.toString(level);
        };
    }

    private static String normalize(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            .filter(token -> !token.isBlank()).map(LootSearchParser::singularize)
            .collect(Collectors.joining(" "));
    }

    private static String singularize(String token) {
        if (token.length() > 4 && token.endsWith("ies")) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.length() > 4 && (token.endsWith("ches") || token.endsWith("shes")
            || token.endsWith("xes") || token.endsWith("zes") || token.endsWith("oes"))) {
            return token.substring(0, token.length() - 2);
        }
        if (token.length() > 3 && token.endsWith("s") && !token.endsWith("ss")
            && !token.endsWith("us") && !token.endsWith("is")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] swap = prev; prev = curr; curr = swap;
        }
        return prev[b.length()];
    }

    private record ItemSuggestion(LootSearchTerm term, int score) {
    }
}
