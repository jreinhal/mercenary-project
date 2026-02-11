package com.jreinhal.mercenary.util;

import java.time.Year;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts coarse temporal constraints (year / year-range) from user queries so retrieval
 * can apply safe prefilters (e.g., {@code documentYear >= 2020 && documentYear <= 2022}).
 *
 * This intentionally focuses on robust, low-false-positive patterns rather than full NLP.
 */
public final class TemporalQueryConstraints {
    private static final Pattern YEAR_RANGE_DASH = Pattern.compile("\\b((?:19|20)\\d{2})\\s*[-\u2013\u2014]\\s*((?:19|20)\\d{2})\\b");
    private static final Pattern YEAR_RANGE_BETWEEN_AND = Pattern.compile("(?i)\\bbetween\\s+((?:19|20)\\d{2})\\s+and\\s+((?:19|20)\\d{2})\\b");
    private static final Pattern YEAR_RANGE_FROM_TO = Pattern.compile("(?i)\\bfrom\\s+((?:19|20)\\d{2})\\s+(?:to|through|until)\\s+((?:19|20)\\d{2})\\b");
    private static final Pattern YEAR_SINCE = Pattern.compile("(?i)\\b(?:since|after)\\s+((?:19|20)\\d{2})\\b");
    private static final Pattern YEAR_BEFORE = Pattern.compile("(?i)\\b(?:before|prior\\s+to|until)\\s+((?:19|20)\\d{2})\\b");
    private static final Pattern YEAR_IN = Pattern.compile("(?i)\\b(?:in|during)\\s+((?:19|20)\\d{2})\\b");
    private static final Pattern TEMPORAL_HINT = Pattern.compile("(?i)\\b(date|year|month|since|after|before|prior\\s+to|from|between|during|in)\\b");

    private TemporalQueryConstraints() {}

    public static Optional<YearRange> extractYearRange(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        String q = query.trim();

        // Explicit year ranges ("2018-2020") should be honored even without extra hint words.
        Optional<YearRange> dashRange = matchRange(YEAR_RANGE_DASH, q);
        if (dashRange.isPresent()) {
            return dashRange;
        }

        // Other patterns require some temporal hint to avoid accidental matches on IDs.
        if (!TEMPORAL_HINT.matcher(q).find()) {
            return Optional.empty();
        }

        Optional<YearRange> between = matchRange(YEAR_RANGE_BETWEEN_AND, q);
        if (between.isPresent()) {
            return between;
        }
        Optional<YearRange> fromTo = matchRange(YEAR_RANGE_FROM_TO, q);
        if (fromTo.isPresent()) {
            return fromTo;
        }

        Integer since = matchSingle(YEAR_SINCE, q);
        if (since != null) {
            return Optional.of(new YearRange(since, null));
        }
        Integer before = matchSingle(YEAR_BEFORE, q);
        if (before != null) {
            return Optional.of(new YearRange(null, before));
        }
        Integer inYear = matchSingle(YEAR_IN, q);
        if (inYear != null) {
            return Optional.of(new YearRange(inYear, inYear));
        }

        return Optional.empty();
    }

    /**
     * Builds a filter expression fragment using {@code documentYear} if the query implies a year constraint.
     * Returns {@code ""} when no constraint is found.
     */
    public static String buildDocumentYearFilter(String query) {
        Optional<YearRange> rangeOpt = extractYearRange(query);
        if (rangeOpt.isEmpty()) {
            return "";
        }
        YearRange range = rangeOpt.get().normalize();
        if (range.fromYear() != null && range.toYear() != null) {
            if (range.fromYear().intValue() == range.toYear().intValue()) {
                return "documentYear == " + range.fromYear();
            }
            return "documentYear >= " + range.fromYear() + " && documentYear <= " + range.toYear();
        }
        if (range.fromYear() != null) {
            return "documentYear >= " + range.fromYear();
        }
        if (range.toYear() != null) {
            return "documentYear <= " + range.toYear();
        }
        return "";
    }

    private static Optional<YearRange> matchRange(Pattern pattern, String q) {
        Matcher m = pattern.matcher(q);
        if (!m.find()) {
            return Optional.empty();
        }
        Integer y1 = safeYear(m.group(1));
        Integer y2 = safeYear(m.group(2));
        if (y1 == null || y2 == null) {
            return Optional.empty();
        }
        return Optional.of(new YearRange(y1, y2).normalize());
    }

    private static Integer matchSingle(Pattern pattern, String q) {
        Matcher m = pattern.matcher(q);
        if (!m.find()) {
            return null;
        }
        return safeYear(m.group(1));
    }

    private static Integer safeYear(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int y = Integer.parseInt(raw.trim());
            int current = Year.now().getValue();
            if (y < 1900 || y > current + 2) {
                return null;
            }
            return y;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record YearRange(Integer fromYear, Integer toYear) {
        public YearRange normalize() {
            if (fromYear == null || toYear == null) {
                return this;
            }
            if (fromYear.intValue() <= toYear.intValue()) {
                return this;
            }
            return new YearRange(toYear, fromYear);
        }
    }
}

