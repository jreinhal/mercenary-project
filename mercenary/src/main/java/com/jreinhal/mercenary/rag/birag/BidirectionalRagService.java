package com.jreinhal.mercenary.rag.birag;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.reasoning.ReasoningStep.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bidirectional RAG: Safe Self-Improving Retrieval-Augmented Generation
 * Based on research paper arXiv:2512.22199
 *
 * This service enables the RAG system to learn and improve from interactions:
 * 1. GROUNDING VERIFICATION: Validate responses are supported by retrieved docs
 * 2. ATTRIBUTION CHECKING: Ensure claims trace back to sources
 * 3. NOVELTY DETECTION: Identify insights beyond training data
 * 4. EXPERIENCE STORE: Capture validated query-response pairs for learning
 *
 * Safety Features:
 * - Multi-stage validation before storing experiences
 * - Hallucination filtering
 * - Regularization to prevent drift
 * - Admin approval for high-risk additions
 */
@Service
public class BidirectionalRagService {

    private static final Logger log = LoggerFactory.getLogger(BidirectionalRagService.class);

    private final VectorStore vectorStore;
    private final MongoTemplate mongoTemplate;
    private final ChatClient chatClient;
    private final GroundingVerifier groundingVerifier;
    private final ReasoningTracer reasoningTracer;

    @Value("${sentinel.birag.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.birag.grounding-threshold:0.7}")
    private double groundingThreshold;

    @Value("${sentinel.birag.experience-min-confidence:0.8}")
    private double experienceMinConfidence;

    @Value("${sentinel.birag.max-experiences-per-query:3}")
    private int maxExperiencesPerQuery;

    @Value("${sentinel.birag.auto-approve:false}")
    private boolean autoApprove;

    // MongoDB collections
    private static final String EXPERIENCE_STORE = "birag_experiences";
    private static final String PENDING_EXPERIENCES = "birag_pending";

    public BidirectionalRagService(VectorStore vectorStore,
                                    MongoTemplate mongoTemplate,
                                    ChatClient.Builder chatClientBuilder,
                                    GroundingVerifier groundingVerifier,
                                    ReasoningTracer reasoningTracer) {
        this.vectorStore = vectorStore;
        this.mongoTemplate = mongoTemplate;
        this.chatClient = chatClientBuilder.build();
        this.groundingVerifier = groundingVerifier;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("Bidirectional RAG initialized (enabled={}, groundingThreshold={}, autoApprove={})",
                enabled, groundingThreshold, autoApprove);
    }

    /**
     * Validate a response and potentially store as an experience.
     *
     * @param query Original user query
     * @param response Generated response
     * @param retrievedDocs Documents used for generation
     * @param department Security department
     * @param userId User who made the query
     * @return Validation result
     */
    public ValidationResult validateAndLearn(String query, String response,
                                              List<Document> retrievedDocs,
                                              String department, String userId) {
        if (!enabled) {
            return new ValidationResult(true, 1.0, List.of(), null);
        }

        long startTime = System.currentTimeMillis();

        // Stage 1: Grounding Verification
        GroundingResult grounding = groundingVerifier.verify(response, retrievedDocs);

        // Stage 2: Attribution Check
        AttributionResult attribution = checkAttribution(response, retrievedDocs);

        // Stage 3: Novelty Detection
        NoveltyResult novelty = detectNovelty(response, query, retrievedDocs);

        // Calculate overall confidence
        double confidence = calculateConfidence(grounding, attribution, novelty);

        // Build validation issues list
        List<String> issues = new ArrayList<>();
        if (grounding.groundingScore() < groundingThreshold) {
            issues.add("Low grounding score: " + String.format("%.2f", grounding.groundingScore()));
        }
        if (!attribution.unattributedClaims().isEmpty()) {
            issues.add("Unattributed claims: " + attribution.unattributedClaims().size());
        }
        if (novelty.hasHighRiskNovelty()) {
            issues.add("High-risk novel content detected");
        }

        // Decide whether to store as experience
        Experience experience = null;
        if (confidence >= experienceMinConfidence && issues.isEmpty()) {
            experience = createExperience(query, response, retrievedDocs, department, userId, confidence);

            if (autoApprove) {
                storeExperience(experience);
                log.info("BiRAG: Auto-approved experience for query: {}", truncate(query, 50));
            } else {
                storePendingExperience(experience);
                log.info("BiRAG: Experience pending approval for query: {}", truncate(query, 50));
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // Add reasoning step
        reasoningTracer.addStep(StepType.EXPERIENCE_VALIDATION,
                "Bidirectional RAG Validation",
                String.format("Grounding=%.2f, Attribution=%d/%d, Confidence=%.2f",
                        grounding.groundingScore(),
                        attribution.attributedClaims(),
                        attribution.attributedClaims() + attribution.unattributedClaims().size(),
                        confidence),
                elapsed,
                Map.of("groundingScore", grounding.groundingScore(),
                       "attributedClaims", attribution.attributedClaims(),
                       "confidence", confidence,
                       "experienceStored", experience != null));

        log.info("BiRAG: Validation complete - confidence={:.2f}, issues={}, elapsed={}ms",
                confidence, issues.size(), elapsed);

        return new ValidationResult(
                issues.isEmpty(),
                confidence,
                issues,
                experience != null ? experience.id() : null
        );
    }

    /**
     * Retrieve similar past experiences to augment current query.
     */
    public List<Experience> retrieveSimilarExperiences(String query, String department, int limit) {
        if (!enabled) {
            return List.of();
        }

        // Search in experience store
        Query mongoQuery = new Query(Criteria.where("department").is(department)
                .and("status").is("APPROVED"));
        mongoQuery.limit(limit * 3); // Get more, then filter by similarity

        List<Experience> candidates = mongoTemplate.find(mongoQuery, Experience.class, EXPERIENCE_STORE);

        if (candidates.isEmpty()) {
            return List.of();
        }

        // Use vector similarity to find relevant experiences
        List<Document> experienceDocs = vectorStore.similaritySearch(
                SearchRequest.query(query)
                        .withTopK(limit)
                        .withSimilarityThreshold(0.5)
                        .withFilterExpression("dept == '" + department + "' && type == 'experience'"));

        Set<String> relevantIds = experienceDocs.stream()
                .map(d -> (String) d.getMetadata().get("experienceId"))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return candidates.stream()
                .filter(e -> relevantIds.contains(e.id()))
                .limit(limit)
                .toList();
    }

    /**
     * Approve a pending experience (admin action).
     */
    public boolean approveExperience(String experienceId, String approvedBy) {
        Query query = new Query(Criteria.where("id").is(experienceId));
        Experience pending = mongoTemplate.findOne(query, Experience.class, PENDING_EXPERIENCES);

        if (pending == null) {
            return false;
        }

        Experience approved = new Experience(
                pending.id(),
                pending.query(),
                pending.response(),
                pending.sourceDocuments(),
                pending.department(),
                pending.userId(),
                pending.confidence(),
                "APPROVED",
                pending.createdAt(),
                System.currentTimeMillis(),
                approvedBy
        );

        storeExperience(approved);
        mongoTemplate.remove(query, PENDING_EXPERIENCES);

        log.info("BiRAG: Experience {} approved by {}", experienceId, approvedBy);
        return true;
    }

    /**
     * Reject a pending experience (admin action).
     */
    public boolean rejectExperience(String experienceId, String rejectedBy, String reason) {
        Query query = new Query(Criteria.where("id").is(experienceId));
        Update update = new Update()
                .set("status", "REJECTED")
                .set("reviewedAt", System.currentTimeMillis())
                .set("reviewedBy", rejectedBy)
                .set("rejectionReason", reason);

        mongoTemplate.updateFirst(query, update, PENDING_EXPERIENCES);

        log.info("BiRAG: Experience {} rejected by {}: {}", experienceId, rejectedBy, reason);
        return true;
    }

    /**
     * Get pending experiences for review.
     */
    public List<Experience> getPendingExperiences(int limit) {
        Query query = new Query().limit(limit);
        query.addCriteria(Criteria.where("status").is("PENDING"));
        return mongoTemplate.find(query, Experience.class, PENDING_EXPERIENCES);
    }

    // ==================== Private Methods ====================

    private AttributionResult checkAttribution(String response, List<Document> docs) {
        // Extract claims from response
        List<String> claims = extractClaims(response);
        int attributed = 0;
        List<String> unattributed = new ArrayList<>();

        Set<String> docContent = docs.stream()
                .map(Document::getContent)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        for (String claim : claims) {
            boolean found = false;
            String claimLower = claim.toLowerCase();

            // Check if claim terms appear in documents
            String[] terms = claimLower.split("\\s+");
            int matchedTerms = 0;
            for (String term : terms) {
                if (term.length() > 3) {
                    for (String content : docContent) {
                        if (content.contains(term)) {
                            matchedTerms++;
                            break;
                        }
                    }
                }
            }

            if (terms.length > 0 && (double) matchedTerms / terms.length > 0.5) {
                found = true;
            }

            if (found) {
                attributed++;
            } else {
                unattributed.add(claim);
            }
        }

        return new AttributionResult(attributed, unattributed);
    }

    private NoveltyResult detectNovelty(String response, String query, List<Document> docs) {
        // Check for content not grounded in documents or query
        Set<String> knownTerms = new HashSet<>();

        // Add query terms
        for (String term : query.toLowerCase().split("\\s+")) {
            if (term.length() > 3) knownTerms.add(term);
        }

        // Add document terms
        for (Document doc : docs) {
            for (String term : doc.getContent().toLowerCase().split("\\s+")) {
                if (term.length() > 3) knownTerms.add(term);
            }
        }

        // Find novel terms in response
        List<String> novelTerms = new ArrayList<>();
        for (String term : response.toLowerCase().split("\\s+")) {
            if (term.length() > 5 && !knownTerms.contains(term)) {
                // Check if it's a proper noun or technical term
                if (Character.isUpperCase(response.charAt(response.toLowerCase().indexOf(term)))) {
                    novelTerms.add(term);
                }
            }
        }

        boolean highRisk = novelTerms.size() > 5; // Threshold for high-risk novelty

        return new NoveltyResult(novelTerms, highRisk);
    }

    private double calculateConfidence(GroundingResult grounding,
                                        AttributionResult attribution,
                                        NoveltyResult novelty) {
        double groundingScore = grounding.groundingScore();

        int totalClaims = attribution.attributedClaims() + attribution.unattributedClaims().size();
        double attributionScore = totalClaims > 0
                ? (double) attribution.attributedClaims() / totalClaims
                : 1.0;

        double noveltyPenalty = novelty.hasHighRiskNovelty() ? 0.3 : 0.0;

        return Math.max(0.0, (groundingScore * 0.5 + attributionScore * 0.5) - noveltyPenalty);
    }

    private List<String> extractClaims(String text) {
        // Simple claim extraction - split by sentences
        List<String> claims = new ArrayList<>();
        for (String sentence : text.split("[.!?]")) {
            String trimmed = sentence.trim();
            if (trimmed.length() > 20) { // Only meaningful sentences
                claims.add(trimmed);
            }
        }
        return claims;
    }

    private Experience createExperience(String query, String response, List<Document> docs,
                                         String department, String userId, double confidence) {
        List<String> sources = docs.stream()
                .map(d -> (String) d.getMetadata().getOrDefault("source", "unknown"))
                .distinct()
                .toList();

        return new Experience(
                UUID.randomUUID().toString(),
                query,
                response,
                sources,
                department,
                userId,
                confidence,
                "PENDING",
                System.currentTimeMillis(),
                null,
                null
        );
    }

    private void storeExperience(Experience experience) {
        mongoTemplate.save(experience, EXPERIENCE_STORE);

        // Also store as searchable document
        Document expDoc = new Document(experience.query() + "\n\n" + experience.response());
        expDoc.getMetadata().put("experienceId", experience.id());
        expDoc.getMetadata().put("dept", experience.department());
        expDoc.getMetadata().put("type", "experience");
        expDoc.getMetadata().put("confidence", experience.confidence());
        vectorStore.add(List.of(expDoc));
    }

    private void storePendingExperience(Experience experience) {
        mongoTemplate.save(experience, PENDING_EXPERIENCES);
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ==================== Record Types ====================

    public record ValidationResult(
            boolean passed,
            double confidence,
            List<String> issues,
            String experienceId) {}

    public record GroundingResult(
            double groundingScore,
            List<String> groundedStatements,
            List<String> ungroundedStatements) {}

    public record AttributionResult(
            int attributedClaims,
            List<String> unattributedClaims) {}

    public record NoveltyResult(
            List<String> novelTerms,
            boolean hasHighRiskNovelty) {}

    public record Experience(
            String id,
            String query,
            String response,
            List<String> sourceDocuments,
            String department,
            String userId,
            double confidence,
            String status,
            Long createdAt,
            Long reviewedAt,
            String reviewedBy) {}
}
