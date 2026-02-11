package com.jreinhal.mercenary.vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses filter expressions used by {@link LocalMongoVectorStore}.
 *
 * Supported string operators:
 * - {@code ==}, {@code !=}, {@code in}, {@code >=}, {@code <=}, {@code >}, {@code <}
 * - AND: {@code &&}
 * - OR: {@code ||}
 *
 * Also supports the legacy Spring AI {@code Filter.Expression#toString()} shape
 * (best-effort, fail-closed).
 */
final class FilterExpressionParser {
    private FilterExpressionParser() {}

    static ParsedFilter parse(Object filterExpression) {
        if (filterExpression == null) {
            return null;
        }
        String filter = String.valueOf(filterExpression).trim();
        if (filter.isBlank()) {
            return null;
        }

        // Backward compatibility for Spring AI expression string formatting
        // Format: Expression[type=EQ, left=Key[key=dept], right=Value[value=ENTERPRISE]]
        if (filter.contains("Key[key=") && filter.contains("Value[value=")) {
            try {
                ParsedFilter parsed = parseSpringAiExpression(filter);
                if (parsed != null) {
                    return parsed;
                }
                return new ParsedFilter(List.of(), true);
            } catch (Exception e) {
                return new ParsedFilter(List.of(), true);
            }
        }

        List<String> orParts = splitByOperator(filter, "||");
        ArrayList<List<Condition>> groups = new ArrayList<>();
        for (String orPart : orParts) {
            List<String> andParts = splitByOperator(orPart, "&&");
            ArrayList<Condition> conditions = new ArrayList<>();
            for (String cond : andParts) {
                Condition parsed = parseCondition(cond.trim());
                if (parsed != null) {
                    conditions.add(parsed);
                }
            }
            if (!conditions.isEmpty()) {
                groups.add(conditions);
            }
        }
        if (groups.isEmpty()) {
            return new ParsedFilter(List.of(), true);
        }
        return new ParsedFilter(groups, false);
    }

    private static Condition parseCondition(String condition) {
        if (condition == null || condition.isBlank()) {
            return null;
        }
        String normalized = condition.replaceAll("\\s+", " ").trim();

        // Fast-path for "in" (case-insensitive). This parser is intentionally simple; values should not contain " in ".
        String lower = normalized.toLowerCase(Locale.ROOT);
        int inIdx = lower.indexOf(" in ");
        if (inIdx > 0) {
            String key = normalized.substring(0, inIdx).trim();
            String raw = normalized.substring(inIdx + 4).trim();
            if (!key.isBlank()) {
                List<String> values = parseList(raw);
                return new Condition(key, "in", values);
            }
            return null;
        }

        // Comparators: search in longest-first order.
        String[] ops = new String[]{">=", "<=", "!=", "==", ">", "<"};
        for (String op : ops) {
            int idx = normalized.indexOf(op);
            if (idx <= 0) {
                continue;
            }
            String key = normalized.substring(0, idx).trim();
            String rawValue = normalized.substring(idx + op.length()).trim();
            if (key.isBlank() || rawValue.isBlank()) {
                return null;
            }
            return new Condition(key, op, List.of(stripQuotes(rawValue)));
        }

        return null;
    }

    private static List<String> splitByOperator(String text, String operator) {
        if (text == null) {
            return List.of();
        }
        String t = text;
        if (t.isBlank()) {
            return List.of("");
        }
        ArrayList<String> parts = new ArrayList<>();
        int idx = 0;
        int opLen = operator.length();
        while (true) {
            int next = t.indexOf(operator, idx);
            if (next < 0) {
                parts.add(t.substring(idx).trim());
                break;
            }
            parts.add(t.substring(idx, next).trim());
            idx = next + opLen;
        }
        return parts;
    }

    private static List<String> parseList(String rawValue) {
        String trimmed = rawValue == null ? "" : rawValue.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return List.of();
        }
        String[] parts = trimmed.split(",");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            String cleaned = stripQuotes(part.trim());
            if (!cleaned.isEmpty()) {
                values.add(cleaned);
            }
        }
        return values;
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static ParsedFilter parseSpringAiExpression(String filter) {
        // Example legacy format (Spring AI Expression#toString):
        // Expression[type=AND, left=Expression[type=EQ, left=Key[key=dept], right=Value[value=ENTERPRISE]], right=Expression[type=EQ, left=Key[key=workspaceId], right=Value[value=abc]]]
        //
        // We intentionally parse only explicit key/value comparisons, then treat them as a single AND-group.
        // If we can't extract any comparisons, the caller will fail closed.
        // Use possessive quantifiers to avoid catastrophic backtracking (ReDoS).
        Pattern pattern = Pattern.compile("Expression\\[type=(EQ|NE|IN|GTE|LTE|GE|LE|GT|LT),\\s*+left=Key\\[key=([^\\]]++)\\],\\s*+right=Value\\[value=([^\\]]++)\\]\\]");
        Matcher matcher = pattern.matcher(filter);
        ArrayList<Condition> conditions = new ArrayList<>();
        while (matcher.find()) {
            String type = matcher.group(1);
            String key = matcher.group(2);
            String value = matcher.group(3);
            if (type == null || key == null || value == null) {
                continue;
            }
            String op = switch (type) {
                case "EQ" -> "==";
                case "NE" -> "!=";
                case "IN" -> "in";
                case "GE", "GTE" -> ">=";
                case "LE", "LTE" -> "<=";
                case "GT" -> ">";
                case "LT" -> "<";
                default -> null;
            };
            if (op == null) {
                continue;
            }
            List<String> values = op.equals("in") ? parseList(value) : List.of(stripQuotes(value));
            conditions.add(new Condition(key, op, values));
        }
        if (conditions.isEmpty()) {
            return null;
        }
        // Keys/values may be sensitive; caller controls logging. Keep parsing code silent.
        return new ParsedFilter(List.of(conditions), false);
    }

    record ParsedFilter(List<List<Condition>> orGroups, boolean invalid) {
    }

    record Condition(String key, String op, List<String> values) {
    }
}
