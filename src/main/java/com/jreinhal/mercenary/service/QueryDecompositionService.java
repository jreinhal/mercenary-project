/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class QueryDecompositionService {
    private static final Logger log = LoggerFactory.getLogger(QueryDecompositionService.class);
    private static final List<String> COMPOUND_INDICATORS = Arrays.asList(" and what ", " and who ", " and where ", " and when ", " and how ", " and why ", " as well as ", " along with ", " in addition to ", " also tell me ", " and also ", " plus ", " and the ");
    private static final Pattern QUESTION_SPLIT_PATTERN = Pattern.compile("(?i)\\s+and\\s+(?=what|who|where|when|how|why|tell|show|explain|describe|list)");

    public boolean isCompoundQuery(String query) {
        if (query == null || query.length() < 20) {
            return false;
        }
        String lowerQuery = query.toLowerCase();
        for (String indicator : COMPOUND_INDICATORS) {
            if (!lowerQuery.contains(indicator)) continue;
            log.debug("Compound query detected via indicator: '{}'", indicator.trim());
            return true;
        }
        if (QUESTION_SPLIT_PATTERN.matcher(query).find()) {
            log.debug("Compound query detected via question pattern");
            return true;
        }
        return false;
    }

    public List<String> decompose(String query) {
        if (!this.isCompoundQuery(query)) {
            return Collections.singletonList(query);
        }
        ArrayList<String> subQueries = new ArrayList<String>();
        String originalQuery = query;
        String[] parts = QUESTION_SPLIT_PATTERN.split(query);
        if (parts.length > 1) {
            for (int i = 0; i < parts.length; ++i) {
                String part = parts[i].trim();
                if (i > 0 && !this.startsWithQuestionWord(part)) {
                    part = this.inferQuestionPrefix(part, originalQuery);
                }
                if (part.length() <= 5) continue;
                subQueries.add(this.cleanSubQuery(part));
            }
        }
        if (subQueries.size() <= 1) {
            subQueries.clear();
            for (String indicator : COMPOUND_INDICATORS) {
                String lowerQuery = query.toLowerCase();
                int idx = lowerQuery.indexOf(indicator);
                if (idx <= 0) continue;
                String part1 = query.substring(0, idx).trim();
                String part2 = query.substring(idx + indicator.length()).trim();
                if (part1.length() > 5) {
                    subQueries.add(this.cleanSubQuery(part1));
                }
                if (part2.length() <= 5) break;
                subQueries.add(this.cleanSubQuery(part2));
                break;
            }
        }
        if (subQueries.isEmpty()) {
            return Collections.singletonList(query);
        }
        log.info("Decomposed query into {} sub-queries:", subQueries.size());
        subQueries.forEach(sq -> log.info("  -> {}", sq));
        return subQueries;
    }

    private String cleanSubQuery(String query) {
        if (query == null) {
            return "";
        }
        if (((String)(query = ((String)query).replaceAll("[?.,;:!]+$", "").trim())).length() > 0) {
            query = Character.toUpperCase(((String)query).charAt(0)) + ((String)query).substring(1);
        }
        return query;
    }

    private boolean startsWithQuestionWord(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.startsWith("what") || lower.startsWith("who") || lower.startsWith("where") || lower.startsWith("when") || lower.startsWith("how") || lower.startsWith("why") || lower.startsWith("tell") || lower.startsWith("show") || lower.startsWith("explain") || lower.startsWith("describe") || lower.startsWith("list");
    }

    private String inferQuestionPrefix(String fragment, String originalQuery) {
        String lower = originalQuery.toLowerCase();
        if (lower.startsWith("what")) {
            return "What is " + fragment;
        }
        if (lower.startsWith("who")) {
            return "Who is " + fragment;
        }
        if (lower.startsWith("where")) {
            return "Where is " + fragment;
        }
        if (lower.startsWith("tell me about")) {
            return "Tell me about " + fragment;
        }
        return fragment;
    }

    public String mergeResults(List<String> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> uniqueResults = new LinkedHashSet<String>();
        for (String result : results) {
            if (result == null || result.trim().isEmpty() || result.contains("No records found") || result.contains("No internal records")) continue;
            uniqueResults.add(result.trim());
        }
        if (uniqueResults.isEmpty()) {
            return "";
        }
        return String.join((CharSequence)"\n\n---\n\n", uniqueResults);
    }
}
