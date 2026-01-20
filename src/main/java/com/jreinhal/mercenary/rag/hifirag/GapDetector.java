package com.jreinhal.mercenary.rag.hifirag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GapDetector {
    private static final Logger log = LoggerFactory.getLogger(GapDetector.class);
    private static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "have", "has", "had", "do", "does", "did", "will", "would", "could", "should", "may", "might", "must", "shall", "can", "need", "dare", "ought", "used", "and", "but", "or", "nor", "for", "yet", "so", "as", "if", "when", "where", "what", "which", "who", "whom", "whose", "why", "how", "whether", "while", "that", "this", "these", "those", "then", "than", "in", "on", "at", "by", "with", "about", "against", "between", "into", "through", "during", "before", "after", "above", "below", "to", "from", "up", "down", "out", "off", "over", "under", "again", "further", "i", "me", "my", "myself", "we", "our", "ours", "ourselves", "you", "your", "yours", "yourself", "yourselves", "he", "him", "his", "himself", "she", "her", "hers", "herself", "it", "its", "itself", "they", "them", "their", "theirs", "themselves", "all", "each", "few", "more", "most", "other", "some", "such", "no", "not", "only", "own", "same", "any", "both", "just", "tell", "find", "show", "give", "get", "make", "know", "take", "see", "come", "think", "look", "want", "use", "work", "also", "new", "like");
    private static final Pattern QUOTED_PHRASE = Pattern.compile("\"([^\"]+)\"|'([^']+)'");
    private static final Pattern CAPITALIZED_WORDS = Pattern.compile("\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)\\b");
    private static final Pattern TECHNICAL_TERM = Pattern.compile("\\b([A-Z]{2,}(?:-[A-Z]+)*)\\b");
    private static final Pattern COMPOUND_NOUN = Pattern.compile("\\b(\\w+-\\w+(?:-\\w+)*)\\b");

    public List<String> extractConcepts(String text) {
        String[] words;
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> concepts = new LinkedHashSet<String>();
        Matcher quotedMatcher = QUOTED_PHRASE.matcher(text);
        while (quotedMatcher.find()) {
            String phrase = quotedMatcher.group(1) != null ? quotedMatcher.group(1) : quotedMatcher.group(2);
            if (phrase == null || phrase.length() <= 2) continue;
            concepts.add(phrase.toLowerCase().trim());
        }
        Matcher capMatcher = CAPITALIZED_WORDS.matcher(text);
        while (capMatcher.find()) {
            String phrase = capMatcher.group(1);
            if (phrase == null || phrase.length() <= 2 || this.isStopWord(phrase)) continue;
            concepts.add(phrase.toLowerCase().trim());
        }
        Matcher techMatcher = TECHNICAL_TERM.matcher(text);
        while (techMatcher.find()) {
            String term = techMatcher.group(1);
            if (term == null || term.length() < 2) continue;
            concepts.add(term.toLowerCase().trim());
        }
        Matcher compoundMatcher = COMPOUND_NOUN.matcher(text);
        while (compoundMatcher.find()) {
            String compound = compoundMatcher.group(1);
            if (compound == null || compound.length() <= 3) continue;
            concepts.add(compound.toLowerCase().trim());
        }
        for (String word : words = text.toLowerCase().split("\\W+")) {
            if (word.length() < 4 || this.isStopWord(word) || this.isCommonVerb(word)) continue;
            concepts.add(word);
        }
        log.debug("Extracted {} concepts from text", concepts.size());
        return new ArrayList<String>(concepts);
    }

    public List<String> findGaps(List<String> queryConcepts, Set<String> coveredConcepts) {
        if (queryConcepts == null || queryConcepts.isEmpty()) {
            return List.of();
        }
        Set<String> normalizedCovered = coveredConcepts.stream().map(String::toLowerCase).collect(Collectors.toSet());
        ArrayList<String> gaps = new ArrayList<String>();
        for (String concept : queryConcepts) {
            String normalized = concept.toLowerCase();
            boolean covered = normalizedCovered.contains(normalized);
            if (!covered) {
                covered = normalizedCovered.stream().anyMatch(c -> c.contains(normalized) || normalized.contains(c));
            }
            if (covered) continue;
            gaps.add(concept);
        }
        log.debug("Found {} gaps from {} query concepts", gaps.size(), queryConcepts.size());
        return gaps;
    }

    public String generateGapQuery(String originalQuery, List<String> gaps) {
        if (gaps == null || gaps.isEmpty()) {
            return originalQuery;
        }
        StringBuilder gapQuery = new StringBuilder();
        gapQuery.append(originalQuery);
        gapQuery.append(" ");
        gapQuery.append(String.join((CharSequence)" ", gaps));
        String result = gapQuery.toString().trim();
        log.debug("Generated gap query: {}", result);
        return result;
    }

    private boolean isStopWord(String word) {
        return STOP_WORDS.contains(word.toLowerCase());
    }

    private boolean isCommonVerb(String word) {
        Set<String> commonVerbs = Set.of("said", "went", "came", "made", "took", "gave", "found", "called", "asked", "told", "used", "tried", "needed");
        return commonVerbs.contains(word.toLowerCase());
    }

    public double calculateCoverage(List<String> queryConcepts, List<String> documentConcepts) {
        if (queryConcepts == null || queryConcepts.isEmpty()) {
            return 0.0;
        }
        Set<String> docSet = documentConcepts.stream().map(String::toLowerCase).collect(Collectors.toSet());
        long covered = queryConcepts.stream().map(String::toLowerCase).filter(c -> docSet.contains(c) || docSet.stream().anyMatch(d -> d.contains(c) || c.contains(d))).count();
        return (double)covered / (double)queryConcepts.size();
    }
}
