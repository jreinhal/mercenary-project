/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.annotation.PostConstruct
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.ai.chat.client.ChatClient
 *  org.springframework.ai.chat.client.ChatClient$Builder
 *  org.springframework.ai.document.Document
 *  org.springframework.ai.vectorstore.SearchRequest
 *  org.springframework.ai.vectorstore.VectorStore
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.data.mongodb.core.MongoTemplate
 *  org.springframework.data.mongodb.core.query.Criteria
 *  org.springframework.data.mongodb.core.query.CriteriaDefinition
 *  org.springframework.data.mongodb.core.query.Query
 *  org.springframework.data.mongodb.core.query.Update
 *  org.springframework.data.mongodb.core.query.UpdateDefinition
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.rag.birag;

import com.jreinhal.mercenary.rag.birag.GroundingVerifier;
import com.jreinhal.mercenary.reasoning.ReasoningStep;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.stereotype.Service;

@Service
public class BidirectionalRagService {
    private static final Logger log = LoggerFactory.getLogger(BidirectionalRagService.class);
    private final VectorStore vectorStore;
    private final MongoTemplate mongoTemplate;
    private final ChatClient chatClient;
    private final GroundingVerifier groundingVerifier;
    private final ReasoningTracer reasoningTracer;
    @Value(value="${sentinel.birag.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.birag.grounding-threshold:0.7}")
    private double groundingThreshold;
    @Value(value="${sentinel.birag.experience-min-confidence:0.8}")
    private double experienceMinConfidence;
    @Value(value="${sentinel.birag.max-experiences-per-query:3}")
    private int maxExperiencesPerQuery;
    @Value(value="${sentinel.birag.auto-approve:false}")
    private boolean autoApprove;
    private static final String EXPERIENCE_STORE = "birag_experiences";
    private static final String PENDING_EXPERIENCES = "birag_pending";

    public BidirectionalRagService(VectorStore vectorStore, MongoTemplate mongoTemplate, ChatClient.Builder chatClientBuilder, GroundingVerifier groundingVerifier, ReasoningTracer reasoningTracer) {
        this.vectorStore = vectorStore;
        this.mongoTemplate = mongoTemplate;
        this.chatClient = chatClientBuilder.build();
        this.groundingVerifier = groundingVerifier;
        this.reasoningTracer = reasoningTracer;
    }

    @PostConstruct
    public void init() {
        log.info("Bidirectional RAG initialized (enabled={}, groundingThreshold={}, autoApprove={})", new Object[]{this.enabled, this.groundingThreshold, this.autoApprove});
    }

    public ValidationResult validateAndLearn(String query, String response, List<Document> retrievedDocs, String department, String userId) {
        if (!this.enabled) {
            return new ValidationResult(true, 1.0, List.of(), null);
        }
        long startTime = System.currentTimeMillis();
        GroundingResult grounding = this.groundingVerifier.verify(response, retrievedDocs);
        AttributionResult attribution = this.checkAttribution(response, retrievedDocs);
        NoveltyResult novelty = this.detectNovelty(response, query, retrievedDocs);
        double confidence = this.calculateConfidence(grounding, attribution, novelty);
        ArrayList<String> issues = new ArrayList<String>();
        if (grounding.groundingScore() < this.groundingThreshold) {
            issues.add("Low grounding score: " + String.format("%.2f", grounding.groundingScore()));
        }
        if (!attribution.unattributedClaims().isEmpty()) {
            issues.add("Unattributed claims: " + attribution.unattributedClaims().size());
        }
        if (novelty.hasHighRiskNovelty()) {
            issues.add("High-risk novel content detected");
        }
        Experience experience = null;
        if (confidence >= this.experienceMinConfidence && issues.isEmpty()) {
            experience = this.createExperience(query, response, retrievedDocs, department, userId, confidence);
            if (this.autoApprove) {
                this.storeExperience(experience);
                log.info("BiRAG: Auto-approved experience for query: {}", this.truncate(query, 50));
            } else {
                this.storePendingExperience(experience);
                log.info("BiRAG: Experience pending approval for query: {}", this.truncate(query, 50));
            }
        }
        long elapsed = System.currentTimeMillis() - startTime;
        this.reasoningTracer.addStep(ReasoningStep.StepType.EXPERIENCE_VALIDATION, "Bidirectional RAG Validation", String.format("Grounding=%.2f, Attribution=%d/%d, Confidence=%.2f", grounding.groundingScore(), attribution.attributedClaims(), attribution.attributedClaims() + attribution.unattributedClaims().size(), confidence), elapsed, Map.of("groundingScore", grounding.groundingScore(), "attributedClaims", attribution.attributedClaims(), "confidence", confidence, "experienceStored", experience != null));
        log.info("BiRAG: Validation complete - confidence={:.2f}, issues={}, elapsed={}ms", new Object[]{confidence, issues.size(), elapsed});
        return new ValidationResult(issues.isEmpty(), confidence, issues, experience != null ? experience.id() : null);
    }

    public List<Experience> retrieveSimilarExperiences(String query, String department, int limit) {
        if (!this.enabled) {
            return List.of();
        }
        Query mongoQuery = new Query((CriteriaDefinition)Criteria.where((String)"department").is(department).and("status").is("APPROVED"));
        mongoQuery.limit(limit * 3);
        List<Experience> candidates = this.mongoTemplate.find(mongoQuery, Experience.class, EXPERIENCE_STORE);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Document> experienceDocs = this.vectorStore.similaritySearch(SearchRequest.query((String)query).withTopK(limit).withSimilarityThreshold(0.5).withFilterExpression("dept == '" + department + "' && type == 'experience'"));
        Set<String> relevantIds = experienceDocs.stream().map(d -> (String)d.getMetadata().get("experienceId")).filter(Objects::nonNull).collect(Collectors.toSet());
        return candidates.stream().filter(e -> relevantIds.contains(e.id())).limit(limit).toList();
    }

    public boolean approveExperience(String experienceId, String approvedBy) {
        Query query = new Query((CriteriaDefinition)Criteria.where((String)"id").is(experienceId));
        Experience pending = (Experience)this.mongoTemplate.findOne(query, Experience.class, PENDING_EXPERIENCES);
        if (pending == null) {
            return false;
        }
        Experience approved = new Experience(pending.id(), pending.query(), pending.response(), pending.sourceDocuments(), pending.department(), pending.userId(), pending.confidence(), "APPROVED", pending.createdAt(), System.currentTimeMillis(), approvedBy);
        this.storeExperience(approved);
        this.mongoTemplate.remove(query, PENDING_EXPERIENCES);
        log.info("BiRAG: Experience {} approved by {}", experienceId, approvedBy);
        return true;
    }

    public boolean rejectExperience(String experienceId, String rejectedBy, String reason) {
        Query query = new Query((CriteriaDefinition)Criteria.where((String)"id").is(experienceId));
        Update update = new Update().set("status", "REJECTED").set("reviewedAt", System.currentTimeMillis()).set("reviewedBy", rejectedBy).set("rejectionReason", reason);
        this.mongoTemplate.updateFirst(query, (UpdateDefinition)update, PENDING_EXPERIENCES);
        log.info("BiRAG: Experience {} rejected by {}: {}", new Object[]{experienceId, rejectedBy, reason});
        return true;
    }

    public List<Experience> getPendingExperiences(int limit) {
        Query query = new Query().limit(limit);
        query.addCriteria((CriteriaDefinition)Criteria.where((String)"status").is("PENDING"));
        return this.mongoTemplate.find(query, Experience.class, PENDING_EXPERIENCES);
    }

    private AttributionResult checkAttribution(String response, List<Document> docs) {
        List<String> claims = this.extractClaims(response);
        int attributed = 0;
        ArrayList<String> unattributed = new ArrayList<String>();
        Set<String> docContent = docs.stream().map(Document::getContent).map(String::toLowerCase).collect(Collectors.toSet());
        for (String claim : claims) {
            boolean found = false;
            String claimLower = claim.toLowerCase();
            String[] terms = claimLower.split("\\s+");
            int matchedTerms = 0;
            block1: for (String term : terms) {
                if (term.length() <= 3) continue;
                for (String content : docContent) {
                    if (!content.contains(term)) continue;
                    ++matchedTerms;
                    continue block1;
                }
            }
            if (terms.length > 0 && (double)matchedTerms / (double)terms.length > 0.5) {
                found = true;
            }
            if (found) {
                ++attributed;
                continue;
            }
            unattributed.add(claim);
        }
        return new AttributionResult(attributed, unattributed);
    }

    private NoveltyResult detectNovelty(String response, String query, List<Document> docs) {
        HashSet<String> knownTerms = new HashSet<String>();
        for (String term : query.toLowerCase().split("\\s+")) {
            if (term.length() <= 3) continue;
            knownTerms.add(term);
        }
        for (Document doc : docs) {
            for (String term : doc.getContent().toLowerCase().split("\\s+")) {
                if (term.length() <= 3) continue;
                knownTerms.add(term);
            }
        }
        ArrayList<String> novelTerms = new ArrayList<String>();
        for (String term : response.toLowerCase().split("\\s+")) {
            if (term.length() <= 5 || knownTerms.contains(term) || !Character.isUpperCase(response.charAt(response.toLowerCase().indexOf(term)))) continue;
            novelTerms.add(term);
        }
        boolean highRisk = novelTerms.size() > 5;
        return new NoveltyResult(novelTerms, highRisk);
    }

    private double calculateConfidence(GroundingResult grounding, AttributionResult attribution, NoveltyResult novelty) {
        double groundingScore = grounding.groundingScore();
        int totalClaims = attribution.attributedClaims() + attribution.unattributedClaims().size();
        double attributionScore = totalClaims > 0 ? (double)attribution.attributedClaims() / (double)totalClaims : 1.0;
        double noveltyPenalty = novelty.hasHighRiskNovelty() ? 0.3 : 0.0;
        return Math.max(0.0, groundingScore * 0.5 + attributionScore * 0.5 - noveltyPenalty);
    }

    private List<String> extractClaims(String text) {
        ArrayList<String> claims = new ArrayList<String>();
        for (String sentence : text.split("[.!?]")) {
            String trimmed = sentence.trim();
            if (trimmed.length() <= 20) continue;
            claims.add(trimmed);
        }
        return claims;
    }

    private Experience createExperience(String query, String response, List<Document> docs, String department, String userId, double confidence) {
        List<String> sources = docs.stream().map(d -> String.valueOf(d.getMetadata().getOrDefault("source", "unknown"))).distinct().toList();
        return new Experience(UUID.randomUUID().toString(), query, response, sources, department, userId, confidence, "PENDING", System.currentTimeMillis(), null, null);
    }

    private void storeExperience(Experience experience) {
        this.mongoTemplate.save(experience, EXPERIENCE_STORE);
        Document expDoc = new Document(experience.query() + "\n\n" + experience.response());
        expDoc.getMetadata().put("experienceId", experience.id());
        expDoc.getMetadata().put("dept", experience.department());
        expDoc.getMetadata().put("type", "experience");
        expDoc.getMetadata().put("confidence", experience.confidence());
        this.vectorStore.add(List.of(expDoc));
    }

    private void storePendingExperience(Experience experience) {
        this.mongoTemplate.save(experience, PENDING_EXPERIENCES);
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public record ValidationResult(boolean passed, double confidence, List<String> issues, String experienceId) {
    }

    public record GroundingResult(double groundingScore, List<String> groundedStatements, List<String> ungroundedStatements) {
    }

    public record AttributionResult(int attributedClaims, List<String> unattributedClaims) {
    }

    public record NoveltyResult(List<String> novelTerms, boolean hasHighRiskNovelty) {
    }

    public record Experience(String id, String query, String response, List<String> sourceDocuments, String department, String userId, double confidence, String status, Long createdAt, Long reviewedAt, String reviewedBy) {
    }
}
