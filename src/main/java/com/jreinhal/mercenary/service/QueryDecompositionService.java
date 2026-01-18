package com.jreinhal.mercenary.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MULTI-QUERY DECOMPOSITION SERVICE
 * 
 * Enterprise-grade query decomposition for compound questions.
 * Handles queries like "What is X and what is Y" by splitting them
 * into independent sub-queries for parallel retrieval.
 * 
 * Target Use Cases:
 * - Government: "What does intel report say AND who are the personnel
 * assigned?"
 * - Legal: "What are the contract terms AND what are the precedents?"
 * - Finance: "Show Q4 revenue AND compare with projections"
 * - Medical: "What are the symptoms AND what does protocol recommend?"
 */
@Service
public class QueryDecompositionService {

    private static final Logger log = LoggerFactory.getLogger(QueryDecompositionService.class);

    // Conjunctions that indicate compound queries
    private static final List<String> COMPOUND_INDICATORS = Arrays.asList(
            " and what ", " and who ", " and where ", " and when ", " and how ", " and why ",
            " as well as ", " along with ", " in addition to ", " also tell me ",
            " and also ", " plus ", " and the ");

    // Pattern for splitting on "and" between question phrases
    private static final Pattern QUESTION_SPLIT_PATTERN = Pattern.compile(
            "(?i)\\s+and\\s+(?=what|who|where|when|how|why|tell|show|explain|describe|list)");

    /**
     * Determines if a query is a compound query requiring decomposition.
     */
    public boolean isCompoundQuery(String query) {
        if (query == null || query.length() < 20) {
            return false;
        }

        String lowerQuery = query.toLowerCase();

        // Check for explicit compound indicators
        for (String indicator : COMPOUND_INDICATORS) {
            if (lowerQuery.contains(indicator)) {
                log.debug("Compound query detected via indicator: '{}'", indicator.trim());
                return true;
            }
        }

        // Check for question word pattern (e.g., "what is X and what is Y")
        if (QUESTION_SPLIT_PATTERN.matcher(query).find()) {
            log.debug("Compound query detected via question pattern");
            return true;
        }

        return false;
    }

    /**
     * Decomposes a compound query into individual sub-queries.
     * Returns the original query in a list if decomposition is not applicable.
     */
    public List<String> decompose(String query) {
        if (!isCompoundQuery(query)) {
            return Collections.singletonList(query);
        }

        List<String> subQueries = new ArrayList<>();
        String originalQuery = query;

        // Strategy 1: Split on question word patterns
        String[] parts = QUESTION_SPLIT_PATTERN.split(query);
        if (parts.length > 1) {
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                // Reconstruct question words for parts after the first
                if (i > 0 && !startsWithQuestionWord(part)) {
                    // Try to infer the question word from context
                    part = inferQuestionPrefix(part, originalQuery);
                }
                if (part.length() > 5) {
                    subQueries.add(cleanSubQuery(part));
                }
            }
        }

        // Strategy 2: Split on compound indicators if Strategy 1 didn't work
        if (subQueries.size() <= 1) {
            subQueries.clear();
            for (String indicator : COMPOUND_INDICATORS) {
                String lowerQuery = query.toLowerCase();
                int idx = lowerQuery.indexOf(indicator);
                if (idx > 0) {
                    String part1 = query.substring(0, idx).trim();
                    String part2 = query.substring(idx + indicator.length()).trim();

                    if (part1.length() > 5)
                        subQueries.add(cleanSubQuery(part1));
                    if (part2.length() > 5)
                        subQueries.add(cleanSubQuery(part2));
                    break;
                }
            }
        }

        // Fallback: return original query if decomposition failed
        if (subQueries.isEmpty()) {
            return Collections.singletonList(query);
        }

        log.info("Decomposed query into {} sub-queries:", subQueries.size());
        subQueries.forEach(sq -> log.info("  -> {}", sq));

        return subQueries;
    }

    /**
     * Cleans up a sub-query by removing trailing punctuation and normalizing.
     */
    private String cleanSubQuery(String query) {
        if (query == null)
            return "";

        // Remove trailing punctuation
        query = query.replaceAll("[?.,;:!]+$", "").trim();

        // Capitalize first letter
        if (query.length() > 0) {
            query = Character.toUpperCase(query.charAt(0)) + query.substring(1);
        }

        return query;
    }

    /**
     * Checks if text starts with a question word.
     */
    private boolean startsWithQuestionWord(String text) {
        if (text == null || text.isEmpty())
            return false;
        String lower = text.toLowerCase();
        return lower.startsWith("what") || lower.startsWith("who") ||
                lower.startsWith("where") || lower.startsWith("when") ||
                lower.startsWith("how") || lower.startsWith("why") ||
                lower.startsWith("tell") || lower.startsWith("show") ||
                lower.startsWith("explain") || lower.startsWith("describe") ||
                lower.startsWith("list");
    }

    /**
     * Attempts to infer a question prefix for a fragment.
     */
    private String inferQuestionPrefix(String fragment, String originalQuery) {
        String lower = originalQuery.toLowerCase();

        // If original started with "what", likely the second part is also "what"
        if (lower.startsWith("what")) {
            return "What is " + fragment;
        } else if (lower.startsWith("who")) {
            return "Who is " + fragment;
        } else if (lower.startsWith("where")) {
            return "Where is " + fragment;
        } else if (lower.startsWith("tell me about")) {
            return "Tell me about " + fragment;
        }

        // Default: just return as-is
        return fragment;
    }

    /**
     * Merges content from multiple sub-query results, removing duplicates.
     */
    public String mergeResults(List<String> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }

        // Use LinkedHashSet to preserve order and remove duplicates
        Set<String> uniqueResults = new LinkedHashSet<>();

        for (String result : results) {
            if (result != null && !result.trim().isEmpty() &&
                    !result.contains("No records found") &&
                    !result.contains("No internal records")) {
                uniqueResults.add(result.trim());
            }
        }

        if (uniqueResults.isEmpty()) {
            return "";
        }

        return String.join("\n\n---\n\n", uniqueResults);
    }
}
