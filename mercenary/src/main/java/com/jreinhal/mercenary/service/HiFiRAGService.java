package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * HiFi-RAG Service - Hierarchical Content Filtering RAG
 *
 * Implementation based on: "HiFi-RAG: Hierarchical Content Filtering and
 * Two-Pass Generation for Open-Domain RAG" (arXiv:2512.22442v1)
 *
 * Key Innovation: Multi-stage pipeline that prioritizes precision in the context window.
 * Uses LLM-as-a-Reranker instead of pure embedding similarity.
 *
 * Pipeline Stages:
 * 1. Query Formulation - Optimize query for retrieval
 * 2. Retrieval - Initial document retrieval
 * 3. Hierarchical Filtering - LLM-based section filtering
 * 4. Two-Pass Generation - Draft then refine
 * 5. Citation Verification - Post-hoc attribution check
 *
 * From paper: "We abandon standard vector-similarity search in favor of a hierarchical
 * filtering approach... ensuring the deep reasoning model receives only the most
 * salient information."
 *
 * @author Implementation based on Nuengsigkapian, 2025
 */
// Bean configured in AdvancedRAGConfig - do not add @Service
public class HiFiRAGService {

    private static final Logger log = LoggerFactory.getLogger(HiFiRAGService.class);

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    // HiFi-RAG parameters
    private static final int INITIAL_RETRIEVAL_K = 20;  // Retrieve more initially
    private static final int FILTERED_TOP_K = 5;        // Keep top after filtering
    private static final double RELEVANCE_THRESHOLD = 0.5;

    public HiFiRAGService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        log.info(">>> HiFi-RAG Service initialized <<<");
    }

    // ========== Stage 1: Query Formulation ==========

    /**
     * Optimize user query for retrieval.
     * 
     * From paper: "User queries are often too verbose or conversational for effective 
     * retrieval. We utilize the model to analyze the user intent and propose optimized 
     * search queries."
     */
    public List<String> formulateQueries(String userQuery) {
        String prompt = """
            Create effective and concise search queries for this question.
            Extract core intent and distinct search terms.
            
            User Question: %s
            
            Return 1-3 search queries, one per line. No explanations or numbering.
            """.formatted(userQuery);

        try {
            Prompt p = new Prompt(List.of(
                new SystemPromptTemplate(prompt).createMessage(),
                new UserMessage("Generate search queries:")
            ));
            
            String response = chatClient.call(p).getResult().getOutput().getContent();
            
            List<String> queries = Arrays.stream(response.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.length() > 3)
                .limit(3)
                .collect(Collectors.toList());

            // Always include original query
            if (!queries.contains(userQuery)) {
                queries.add(0, userQuery);
            }

            log.info("HiFi-RAG Query Formulation: {} -> {} queries", 
                     truncate(userQuery, 30), queries.size());
            return queries;
            
        } catch (Exception e) {
            log.error("Query formulation failed: {}", e.getMessage());
            return List.of(userQuery);  // Fallback to original
        }
    }

    // ========== Stage 2: Retrieval ==========

    /**
     * Initial retrieval with expanded queries.
     */
    public List<Document> initialRetrieval(List<String> queries, int topK) {
        Map<String, Document> uniqueDocs = new LinkedHashMap<>();
        Map<String, Double> docScores = new HashMap<>();

        for (int i = 0; i < queries.size(); i++) {
            String query = queries.get(i);
            double queryWeight = 1.0 - (i * 0.1);  // First query gets highest weight

            try {
                List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.query(query)
                        .withTopK(topK)
                        .withSimilarityThreshold(0.5)
                );

                for (int rank = 0; rank < results.size(); rank++) {
                    Document doc = results.get(rank);
                    String docId = getDocumentId(doc);
                    
                    // RRF-style scoring with query weighting
                    double score = queryWeight / (60.0 + rank);
                    docScores.merge(docId, score, Double::sum);
                    uniqueDocs.putIfAbsent(docId, doc);
                }
            } catch (Exception e) {
                log.warn("Retrieval failed for query: {}", truncate(query, 30));
            }
        }

        // Sort by aggregated score
        List<Document> ranked = docScores.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .map(e -> uniqueDocs.get(e.getKey()))
            .collect(Collectors.toList());

        log.info("HiFi-RAG Initial Retrieval: {} unique documents from {} queries", 
                 ranked.size(), queries.size());
        return ranked;
    }

    // ========== Stage 3: Hierarchical Content Filtering ==========

    /**
     * Parse document content into hierarchical sections.
     * 
     * From paper: "Rather than treating content as flat text, we parse content 
     * into hierarchical sections."
     */
    public static class ContentSection {
        public String title;
        public String content;
        public int level;  // Header level (1-4)
        public String sourceFile;
        public double relevanceScore;

        public ContentSection(String title, String content, int level, String sourceFile) {
            this.title = title;
            this.content = content;
            this.level = level;
            this.sourceFile = sourceFile;
            this.relevanceScore = 0.0;
        }

        public String getPreview() {
            String preview = content.length() > 200 ? content.substring(0, 200) + "..." : content;
            return preview;
        }
    }

    /**
     * Parse document into sections based on structure.
     */
    public List<ContentSection> parseIntoSections(Document doc) {
        List<ContentSection> sections = new ArrayList<>();
        String content = doc.getContent();
        String source = (String) doc.getMetadata().getOrDefault("source", "unknown");

        // Try to identify structure markers
        String[] paragraphs = content.split("\n\n+");
        
        if (paragraphs.length <= 1) {
            // Single block - treat as one section
            sections.add(new ContentSection("Content", content, 1, source));
        } else {
            // Multiple paragraphs - create sections
            Pattern headerPattern = Pattern.compile("^(#{1,4}\\s+.+|[A-Z][A-Za-z\\s]+:)$", Pattern.MULTILINE);
            
            String currentTitle = "Introduction";
            StringBuilder currentContent = new StringBuilder();
            int currentLevel = 1;

            for (String para : paragraphs) {
                para = para.trim();
                if (para.isEmpty()) continue;

                Matcher m = headerPattern.matcher(para);
                if (m.find() && para.length() < 100) {
                    // This looks like a header
                    if (currentContent.length() > 0) {
                        sections.add(new ContentSection(currentTitle, 
                            currentContent.toString().trim(), currentLevel, source));
                        currentContent = new StringBuilder();
                    }
                    currentTitle = para.replaceAll("^#+\\s*", "").replace(":", "").trim();
                    currentLevel = (int) para.chars().takeWhile(c -> c == '#').count();
                    if (currentLevel == 0) currentLevel = 2;
                } else {
                    currentContent.append(para).append("\n\n");
                }
            }

            // Add final section
            if (currentContent.length() > 0) {
                sections.add(new ContentSection(currentTitle, 
                    currentContent.toString().trim(), currentLevel, source));
            }
        }

        // If no sections created, add whole content
        if (sections.isEmpty()) {
            sections.add(new ContentSection("Full Content", content, 1, source));
        }

        return sections;
    }

    /**
     * LLM-based section filtering and ranking.
     * 
     * From paper: "We deploy the model to evaluate each parsed section against 
     * the user query, using only its title and a small snippet to make the 
     * evaluation as lightweight as possible."
     */
    public List<ContentSection> filterAndRankSections(List<ContentSection> sections, 
                                                       String userQuery) {
        if (sections.isEmpty()) return sections;

        // Build section preview list for LLM evaluation
        StringBuilder sectionPreviews = new StringBuilder();
        for (int i = 0; i < sections.size(); i++) {
            ContentSection section = sections.get(i);
            sectionPreviews.append(String.format("[%d] Title: %s | Preview: %s\n",
                i, section.title, truncate(section.getPreview(), 150)));
        }

        String prompt = """
            Given a user question and section previews, identify which sections are 
            helpful for answering the question.
            
            User Question: %s
            
            Section Previews:
            %s
            
            Return a comma-separated list of section indices, sorted by relevance 
            (most relevant first). Only include sections that directly help answer 
            the question. Return just the numbers, nothing else.
            Example: 2,0,5,3
            """.formatted(userQuery, sectionPreviews);

        try {
            Prompt p = new Prompt(List.of(
                new SystemPromptTemplate(prompt).createMessage(),
                new UserMessage("Relevant section indices:")
            ));
            
            String response = chatClient.call(p).getResult().getOutput().getContent().trim();
            
            // Parse response
            List<Integer> relevantIndices = Arrays.stream(response.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> s.matches("\\d+"))
                .map(Integer::parseInt)
                .filter(i -> i >= 0 && i < sections.size())
                .distinct()
                .collect(Collectors.toList());

            // Build filtered list with relevance scores
            List<ContentSection> filtered = new ArrayList<>();
            for (int rank = 0; rank < relevantIndices.size(); rank++) {
                int idx = relevantIndices.get(rank);
                ContentSection section = sections.get(idx);
                section.relevanceScore = 1.0 - (rank * 0.1);  // Decay by rank
                filtered.add(section);
            }

            log.info("HiFi-RAG Filtering: {} -> {} sections ({}% reduction)", 
                     sections.size(), filtered.size(), 
                     100 - (filtered.size() * 100 / sections.size()));

            return filtered.isEmpty() ? sections.subList(0, Math.min(3, sections.size())) : filtered;

        } catch (Exception e) {
            log.error("Section filtering failed: {}", e.getMessage());
            // Fallback: return first few sections
            return sections.subList(0, Math.min(5, sections.size()));
        }
    }

    // ========== Stage 4: Two-Pass Generation ==========

    /**
     * Two-pass generation: Draft then Refine.
     * 
     * From paper: "We utilize the model in a two-turn conversation to separate 
     * factuality from style."
     */
    public TwoPassResult twoPassGeneration(String userQuery, List<ContentSection> sections,
                                            Department department) {
        // Build context from filtered sections
        StringBuilder context = new StringBuilder();
        for (ContentSection section : sections) {
            context.append(String.format("=== [%s] %s ===\n%s\n\n", 
                section.sourceFile, section.title, section.content));
        }

        // Pass 1: Drafting - Focus on factuality
        String draftPrompt = """
            You are SENTINEL, an advanced intelligence agent assigned to %s sector.
            
            Answer the user question based ONLY on the provided context.
            Include ONLY information that can be directly supported by the context.
            For each fact, note the source in brackets [filename].
            
            If the context doesn't contain the answer, say "No relevant records found."
            
            Context:
            %s
            
            Question: %s
            """.formatted(department.name(), context, userQuery);

        String draftResponse;
        try {
            Prompt p1 = new Prompt(List.of(
                new SystemPromptTemplate(draftPrompt).createMessage(),
                new UserMessage("Provide your answer:")
            ));
            draftResponse = chatClient.call(p1).getResult().getOutput().getContent();
        } catch (Exception e) {
            log.error("Draft generation failed: {}", e.getMessage());
            return new TwoPassResult("Generation failed: " + e.getMessage(), "", List.of());
        }

        // Pass 2: Refinement - Focus on style and conciseness
        String refinePrompt = """
            You are refining an intelligence response for clarity and professionalism.
            
            Revise the following answer to:
            1. Be concise (1-3 sentences if possible)
            2. Maintain all factual claims with their citations
            3. Use professional, authoritative tone
            4. Remove any hedging language unless uncertainty is warranted
            
            Original Answer:
            %s
            
            Refined Answer:
            """.formatted(draftResponse);

        String refinedResponse;
        try {
            Prompt p2 = new Prompt(List.of(
                new SystemPromptTemplate(refinePrompt).createMessage(),
                new UserMessage("Provide refined answer:")
            ));
            refinedResponse = chatClient.call(p2).getResult().getOutput().getContent();
        } catch (Exception e) {
            log.warn("Refinement failed, using draft: {}", e.getMessage());
            refinedResponse = draftResponse;
        }

        // Extract citations
        List<String> citations = extractCitations(refinedResponse);

        log.info("HiFi-RAG Two-Pass: Draft {} chars -> Refined {} chars, {} citations",
                 draftResponse.length(), refinedResponse.length(), citations.size());

        return new TwoPassResult(refinedResponse, draftResponse, citations);
    }

    public static class TwoPassResult {
        public final String finalAnswer;
        public final String draftAnswer;
        public final List<String> citations;

        public TwoPassResult(String finalAnswer, String draftAnswer, List<String> citations) {
            this.finalAnswer = finalAnswer;
            this.draftAnswer = draftAnswer;
            this.citations = citations;
        }
    }

    // ========== Stage 5: Citation Verification ==========

    /**
     * Verify citations are properly attributed to sources.
     * 
     * From paper: "We employ a dedicated verification step... This allows the 
     * verification step to focus exclusively on source attribution."
     */
    public CitationVerificationResult verifyCitations(String answer, 
                                                       List<ContentSection> sources) {
        // Build source map
        StringBuilder sourceList = new StringBuilder();
        Map<Integer, ContentSection> sourceMap = new HashMap<>();
        int idx = 0;
        for (ContentSection section : sources) {
            sourceList.append(String.format("[%d] %s: %s\n", 
                idx, section.sourceFile, truncate(section.content, 200)));
            sourceMap.put(idx, section);
            idx++;
        }

        String prompt = """
            Read the ANSWER and identify which SOURCES directly support the information.
            Only list indices of sources that directly support claims in the answer.
            If no sources match, return empty list.
            
            ANSWER: %s
            
            SOURCES:
            %s
            
            Return comma-separated source indices only. Example: 0,2,3
            """.formatted(answer, sourceList);

        try {
            Prompt p = new Prompt(List.of(
                new SystemPromptTemplate(prompt).createMessage(),
                new UserMessage("Verified source indices:")
            ));
            
            String response = chatClient.call(p).getResult().getOutput().getContent().trim();
            
            List<Integer> verifiedIndices = Arrays.stream(response.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> s.matches("\\d+"))
                .map(Integer::parseInt)
                .filter(sourceMap::containsKey)
                .collect(Collectors.toList());

            List<String> verifiedSources = verifiedIndices.stream()
                .map(sourceMap::get)
                .map(s -> s.sourceFile)
                .distinct()
                .collect(Collectors.toList());

            return new CitationVerificationResult(true, verifiedSources, verifiedIndices.size());

        } catch (Exception e) {
            log.warn("Citation verification failed: {}", e.getMessage());
            // Extract citations from answer itself as fallback
            List<String> fallbackCitations = extractCitations(answer);
            return new CitationVerificationResult(false, fallbackCitations, fallbackCitations.size());
        }
    }

    public static class CitationVerificationResult {
        public final boolean verified;
        public final List<String> sources;
        public final int matchCount;

        public CitationVerificationResult(boolean verified, List<String> sources, int matchCount) {
            this.verified = verified;
            this.sources = sources;
            this.matchCount = matchCount;
        }
    }

    // ========== Complete Pipeline ==========

    /**
     * Execute the complete HiFi-RAG pipeline.
     */
    public HiFiRAGResult executeFullPipeline(String userQuery, Department department) {
        long startTime = System.currentTimeMillis();
        
        log.info("HiFi-RAG Pipeline: Starting for query '{}'", truncate(userQuery, 50));

        // Stage 1: Query Formulation
        List<String> queries = formulateQueries(userQuery);

        // Stage 2: Initial Retrieval
        List<Document> retrievedDocs = initialRetrieval(queries, INITIAL_RETRIEVAL_K);

        // Stage 3a: Parse into sections
        List<ContentSection> allSections = new ArrayList<>();
        for (Document doc : retrievedDocs) {
            allSections.addAll(parseIntoSections(doc));
        }

        // Stage 3b: Hierarchical Filtering
        List<ContentSection> filteredSections = filterAndRankSections(allSections, userQuery);
        
        // Limit to top K
        if (filteredSections.size() > FILTERED_TOP_K) {
            filteredSections = filteredSections.subList(0, FILTERED_TOP_K);
        }

        // Stage 4: Two-Pass Generation
        TwoPassResult generation = twoPassGeneration(userQuery, filteredSections, department);

        // Stage 5: Citation Verification
        CitationVerificationResult verification = verifyCitations(
            generation.finalAnswer, filteredSections);

        long duration = System.currentTimeMillis() - startTime;

        log.info("HiFi-RAG Pipeline: Complete in {}ms | Docs: {} -> Sections: {} -> Filtered: {} | Citations: {}",
                 duration, retrievedDocs.size(), allSections.size(), 
                 filteredSections.size(), verification.sources.size());

        return new HiFiRAGResult(
            generation.finalAnswer,
            generation.draftAnswer,
            filteredSections,
            verification,
            queries,
            duration
        );
    }

    public static class HiFiRAGResult {
        public final String answer;
        public final String draftAnswer;
        public final List<ContentSection> usedSections;
        public final CitationVerificationResult citations;
        public final List<String> expandedQueries;
        public final long processingTimeMs;

        public HiFiRAGResult(String answer, String draftAnswer, 
                            List<ContentSection> usedSections,
                            CitationVerificationResult citations,
                            List<String> expandedQueries,
                            long processingTimeMs) {
            this.answer = answer;
            this.draftAnswer = draftAnswer;
            this.usedSections = usedSections;
            this.citations = citations;
            this.expandedQueries = expandedQueries;
            this.processingTimeMs = processingTimeMs;
        }
    }

    // ========== Utility Methods ==========

    private List<String> extractCitations(String text) {
        List<String> citations = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\[([^\\]]+\\.[a-zA-Z]+)\\]");
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String citation = matcher.group(1);
            if (!citations.contains(citation)) {
                citations.add(citation);
            }
        }
        return citations;
    }

    private String getDocumentId(Document doc) {
        Object source = doc.getMetadata().get("source");
        if (source != null) return source.toString();
        Object id = doc.getMetadata().get("id");
        if (id != null) return id.toString();
        return String.valueOf(doc.getContent().hashCode());
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
