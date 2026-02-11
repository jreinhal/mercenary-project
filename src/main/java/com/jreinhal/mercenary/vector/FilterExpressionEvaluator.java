package com.jreinhal.mercenary.vector;

import java.util.List;
import java.util.Map;

final class FilterExpressionEvaluator {

    private FilterExpressionEvaluator() {}

    static boolean matches(Map<String, Object> metadata, FilterExpressionParser.ParsedFilter parsed) {
        if (parsed == null) {
            return true;
        }
        if (parsed.invalid()) {
            return false;
        }
        List<List<FilterExpressionParser.Condition>> groups = parsed.orGroups();
        if (groups == null || groups.isEmpty()) {
            return true;
        }
        for (List<FilterExpressionParser.Condition> group : groups) {
            boolean match = true;
            for (FilterExpressionParser.Condition condition : group) {
                if (!matchesCondition(metadata, condition)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesCondition(Map<String, Object> metadata, FilterExpressionParser.Condition condition) {
        if (condition == null) {
            return true;
        }
        String key = condition.key();
        String op = condition.op();
        List<String> values = condition.values();
        Object metaValue = metadata != null ? metadata.get(key) : null;

        if ("in".equals(op)) {
            if (metaValue == null) {
                return false;
            }
            return values.stream().anyMatch(v -> valuesEqual(metaValue, v));
        }
        if (values == null || values.isEmpty()) {
            return true;
        }
        String cleaned = values.get(0);
        if ("==".equals(op)) {
            if (metaValue == null) {
                return false;
            }
            return valuesEqual(metaValue, cleaned);
        }
        if ("!=".equals(op)) {
            if (metaValue == null) {
                return true;
            }
            return !valuesEqual(metaValue, cleaned);
        }
        if (">=".equals(op) || "<=".equals(op) || ">".equals(op) || "<".equals(op)) {
            Double meta = coerceToDouble(metaValue);
            Double rhs = tryParseDouble(cleaned);
            if (meta == null || rhs == null) {
                return false;
            }
            int cmp = Double.compare(meta, rhs);
            return switch (op) {
                case ">=" -> cmp >= 0;
                case "<=" -> cmp <= 0;
                case ">" -> cmp > 0;
                case "<" -> cmp < 0;
                default -> false;
            };
        }
        return true;
    }

    private static boolean valuesEqual(Object metaValue, String candidate) {
        if (candidate == null) {
            return metaValue == null;
        }
        if (metaValue instanceof Number) {
            Double metaNumber = ((Number) metaValue).doubleValue();
            Double candidateNumber = tryParseDouble(candidate);
            if (candidateNumber != null) {
                return Double.compare(metaNumber, candidateNumber) == 0;
            }
        }
        String metaString = String.valueOf(metaValue);
        return metaString.equalsIgnoreCase(candidate);
    }

    private static Double coerceToDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return tryParseDouble(String.valueOf(value));
    }

    private static Double tryParseDouble(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

