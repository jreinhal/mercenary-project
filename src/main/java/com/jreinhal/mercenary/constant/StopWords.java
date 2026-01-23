package com.jreinhal.mercenary.constant;

import java.util.Set;

public final class StopWords {
    public static final Set<String> QUERY_BOOST = Set.of(
            "the", "and", "for", "was", "are", "is", "of", "to", "in",
            "what", "where", "when", "who", "how", "why", "tell", "me",
            "about", "describe", "find", "show", "give", "also"
    );

    public static final Set<String> HYBRID_KEYWORDS = Set.of(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to",
            "for", "of", "with", "by", "from", "as", "is", "was", "are",
            "were", "been", "be", "have", "has", "had", "what", "where",
            "when", "who", "how", "why", "tell", "me", "about", "describe",
            "find", "show", "give", "also"
    );

    public static final Set<String> RERANKER = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "what", "where",
            "when", "who", "how", "why", "which", "and", "or", "but", "in",
            "on", "at", "to", "for", "of", "with"
    );

    public static final Set<String> HGMEM_EXTRACTOR = Set.of(
            "the", "a", "an", "and", "or", "but", "is", "are", "was", "were",
            "be", "been", "being", "have", "has", "had", "do", "does", "did",
            "will", "would", "could", "should", "may", "might", "must",
            "this", "that", "these", "those", "it", "its", "they", "them"
    );

    public static final Set<String> HIFIRAG_GAP = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "must", "shall", "can", "need", "dare",
            "ought", "used", "and", "but", "or", "nor", "for", "yet", "so",
            "as", "if", "when", "where", "what", "which", "who", "whom", "whose",
            "why", "how", "whether", "while", "that", "this", "these", "those",
            "then", "than", "in", "on", "at", "by", "with", "about", "against",
            "between", "into", "through", "during", "before", "after", "above",
            "below", "to", "from", "up", "down", "out", "off", "over", "under",
            "again", "further", "i", "me", "my", "myself", "we", "our", "ours",
            "ourselves", "you", "your", "yours", "yourself", "yourselves", "he",
            "him", "his", "himself", "she", "her", "hers", "herself", "it",
            "its", "itself", "they", "them", "their", "theirs", "themselves",
            "all", "each", "few", "more", "most", "other", "some", "such", "no",
            "not", "only", "own", "same", "any", "both", "just", "tell", "find",
            "show", "give", "get", "make", "know", "take", "see", "come",
            "think", "look", "want", "use", "work", "also", "new", "like"
    );

    private StopWords() {
    }
}
