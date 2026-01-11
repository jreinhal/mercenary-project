package com.jreinhal.mercenary.rag.hifirag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Gap Detector for HiFi-RAG iterative retrieval.
 *
 * Identifies concepts from the query that are not adequately covered
 * by retrieved documents, enabling targeted follow-up retrieval.
 *
 * Uses pattern-based extraction for air-gap compatibility (no external NLP APIs).
 */
@Component
public class GapDetector {

    private static final Logger log = LoggerFactory.getLogger(GapDetector.class);

    /**
     * Common English stop words to filter out.
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could", "should",
            "may", "might", "must", "shall", "can", "need", "dare", "ought", "used",
            "and", "but", "or", "nor", "for", "yet", "so", "as", "if", "when", "where",
            "what", "which", "who", "whom", "whose", "why", "how", "whether", "while",
            "that", "this", "these", "those", "then", "than",
            "in", "on", "at", "by", "with", "about", "against", "between", "into",
            "through", "during", "before", "after", "above", "below", "to", "from",
            "up", "down", "out", "off", "over", "under", "again", "further",
            "i", "me", "my", "myself", "we", "our", "ours", "ourselves",
            "you", "your", "yours", "yourself", "yourselves",
            "he", "him", "his", "himself", "she", "her", "hers", "herself",
            "it", "its", "itself", "they", "them", "their", "theirs", "themselves",
            "all", "each", "few", "more", "most", "other", "some", "such",
            "no", "not", "only", "own", "same", "any", "both", "just",
            "tell", "find", "show", "give", "get", "make", "know", "take", "see",
            "come", "think", "look", "want", "use", "work", "also", "new", "like"
    );

    /**
     * Patterns for extracting named entities and concepts.
     */
    private static final Pattern QUOTED_PHRASE = Pattern.compile("\"([^\"]+)\"|'([^']+)'");
    private static final Pattern CAPITALIZED_WORDS = Pattern.compile("\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)\\b");
    private static final Pattern TECHNICAL_TERM = Pattern.compile("\\b([A-Z]{2,}(?:-[A-Z]+)*)\\b"); // Acronyms
    private static final Pattern COMPOUND_NOUN = Pattern.compile("\\b(\\w+-\\w+(?:-\\w+)*)\\b"); // Hyphenated

    /**
     * Extract key concepts from text.
     *
     * @param text The text to analyze
     * @return List of extracted concepts
     */
    public List<String> extractConcepts(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        Set<String> concepts = new LinkedHashSet<>();

        // 1. Extract quoted phrases (highest priority - explicit concepts)
        Matcher quotedMatcher = QUOTED_PHRASE.matcher(text);
        while (quotedMatcher.find()) {
            String phrase = quotedMatcher.group(1) != null ? quotedMatcher.group(1) : quotedMatcher.group(2);
            if (phrase != null && phrase.length() > 2) {
                concepts.add(phrase.toLowerCase().trim());
            }
        }

        // 2. Extract capitalized phrases (likely proper nouns/entities)
        Matcher capMatcher = CAPITALIZED_WORDS.matcher(text);
        while (capMatcher.find()) {
            String phrase = capMatcher.group(1);
            if (phrase != null && phrase.length() > 2 && !isStopWord(phrase)) {
                concepts.add(phrase.toLowerCase().trim());
            }
        }

        // 3. Extract technical terms/acronyms
        Matcher techMatcher = TECHNICAL_TERM.matcher(text);
        while (techMatcher.find()) {
            String term = techMatcher.group(1);
            if (term != null && term.length() >= 2) {
                concepts.add(term.toLowerCase().trim());
            }
        }

        // 4. Extract compound nouns
        Matcher compoundMatcher = COMPOUND_NOUN.matcher(text);
        while (compoundMatcher.find()) {
            String compound = compoundMatcher.group(1);
            if (compound != null && compound.length() > 3) {
                concepts.add(compound.toLowerCase().trim());
            }
        }

        // 5. Extract significant individual words (nouns, verbs likely)
        String[] words = text.toLowerCase().split("\\W+");
        for (String word : words) {
            if (word.length() >= 4 && !isStopWord(word) && !isCommonVerb(word)) {
                concepts.add(word);
            }
        }

        log.debug("Extracted {} concepts from text", concepts.size());
        return new ArrayList<>(concepts);
    }

    /**
     * Find gaps - concepts from query not covered by retrieved content.
     *
     * @param queryConcepts Concepts from the original query
     * @param coveredConcepts Concepts found in retrieved documents
     * @return List of uncovered concepts (gaps)
     */
    public List<String> findGaps(List<String> queryConcepts, Set<String> coveredConcepts) {
        if (queryConcepts == null || queryConcepts.isEmpty()) {
            return List.of();
        }

        Set<String> normalizedCovered = coveredConcepts.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        List<String> gaps = new ArrayList<>();

        for (String concept : queryConcepts) {
            String normalized = concept.toLowerCase();

            // Check if concept is covered directly or as substring
            boolean covered = normalizedCovered.contains(normalized);
            if (!covered) {
                // Check for partial matches (concept contained in covered text)
                covered = normalizedCovered.stream()
                        .anyMatch(c -> c.contains(normalized) || normalized.contains(c));
            }

            if (!covered) {
                gaps.add(concept);
            }
        }

        log.debug("Found {} gaps from {} query concepts", gaps.size(), queryConcepts.size());
        return gaps;
    }

    /**
     * Generate a focused query targeting gap concepts.
     *
     * @param originalQuery The original user query
     * @param gaps Uncovered concepts
     * @return A query focused on the gaps
     */
    public String generateGapQuery(String originalQuery, List<String> gaps) {
        if (gaps == null || gaps.isEmpty()) {
            return originalQuery;
        }

        // Create a query that emphasizes the gap concepts
        StringBuilder gapQuery = new StringBuilder();

        // Include original query context
        gapQuery.append(originalQuery);
        gapQuery.append(" ");

        // Add gap concepts
        gapQuery.append(String.join(" ", gaps));

        String result = gapQuery.toString().trim();
        log.debug("Generated gap query: {}", result);
        return result;
    }

    /**
     * Check if a word is a stop word.
     */
    private boolean isStopWord(String word) {
        return STOP_WORDS.contains(word.toLowerCase());
    }

    /**
     * Check if a word is a common verb (not useful as a concept).
     */
    private boolean isCommonVerb(String word) {
        Set<String> commonVerbs = Set.of(
                "said", "went", "came", "made", "took", "gave", "found",
                "called", "asked", "told", "used", "tried", "needed"
        );
        return commonVerbs.contains(word.toLowerCase());
    }

    /**
     * Calculate concept coverage score.
     *
     * @param queryConcepts Concepts from query
     * @param documentConcepts Concepts from a document
     * @return Coverage score (0.0 to 1.0)
     */
    public double calculateCoverage(List<String> queryConcepts, List<String> documentConcepts) {
        if (queryConcepts == null || queryConcepts.isEmpty()) {
            return 0.0;
        }

        Set<String> docSet = documentConcepts.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        long covered = queryConcepts.stream()
                .map(String::toLowerCase)
                .filter(c -> docSet.contains(c) || docSet.stream().anyMatch(d -> d.contains(c) || c.contains(d)))
                .count();

        return (double) covered / queryConcepts.size();
    }
}
