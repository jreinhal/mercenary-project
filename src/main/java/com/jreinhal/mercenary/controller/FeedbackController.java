package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.Feedback;
import com.jreinhal.mercenary.model.Feedback.FeedbackType;
import com.jreinhal.mercenary.model.Feedback.FeedbackCategory;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.service.FeedbackService;
import com.jreinhal.mercenary.service.FeedbackService.FeedbackResult;
import com.jreinhal.mercenary.service.FeedbackService.FeedbackAnalytics;
import com.jreinhal.mercenary.service.FeedbackService.TrainingExample;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * REST controller for user feedback on RAG responses.
 *
 * Enables:
 * - RLHF training signal collection
 * - Quality metrics dashboards
 * - Issue triage workflows
 * - Training data export
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /**
     * Submit positive feedback (thumbs up).
     */
    @PostMapping("/positive")
    public ResponseEntity<FeedbackResult> submitPositive(@RequestBody PositiveFeedbackRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = user.getId();
        String username = user.getDisplayName();
        // Get first allowed sector or null
        String sector = user.getAllowedSectors().stream()
                .findFirst()
                .map(Enum::name)
                .orElse(null);

        FeedbackResult result = feedbackService.submitPositiveFeedback(
                userId, username, sector,
                request.messageId(),
                request.query(),
                request.response(),
                request.ragMetadata()
        );

        log.debug("Positive feedback submitted: messageId={}, user={}", request.messageId(), userId);
        return ResponseEntity.ok(result);
    }

    /**
     * Submit negative feedback (thumbs down) with category.
     */
    @PostMapping("/negative")
    public ResponseEntity<FeedbackResult> submitNegative(@RequestBody NegativeFeedbackRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = user.getId();
        String username = user.getDisplayName();
        String sector = user.getAllowedSectors().stream()
                .findFirst()
                .map(Enum::name)
                .orElse(null);

        FeedbackCategory category;
        try {
            category = FeedbackCategory.valueOf(request.category().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        FeedbackResult result = feedbackService.submitNegativeFeedback(
                userId, username, sector,
                request.messageId(),
                request.query(),
                request.response(),
                category,
                request.comments(),
                request.ragMetadata()
        );

        log.info("Negative feedback submitted: messageId={}, category={}, user={}",
                request.messageId(), category, userId);
        return ResponseEntity.ok(result);
    }

    /**
     * Get feedback analytics for dashboard.
     * Optionally filter by sector (admin can see all, users see their sector).
     */
    @GetMapping("/analytics")
    @PreAuthorize("hasRole('ADMIN') or hasRole('ANALYST')")
    public ResponseEntity<FeedbackAnalytics> getAnalytics(
            @RequestParam(required = false) String sector,
            @RequestParam(defaultValue = "30") int days) {

        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = user.getId();
        String userSector = user.getAllowedSectors().stream()
                .findFirst()
                .map(Enum::name)
                .orElse(null);

        // Non-admins can only see their own sector
        if (!user.hasRole(UserRole.ADMIN) && sector != null && !sector.equals(userSector)) {
            log.warn("User {} attempted to access analytics for sector {} without permission", userId, sector);
            sector = userSector; // Force to user's sector
        }

        FeedbackAnalytics analytics = feedbackService.getAnalytics(sector, days);
        return ResponseEntity.ok(analytics);
    }

    /**
     * Get open issues for triage (admin view).
     */
    @GetMapping("/issues")
    @PreAuthorize("hasRole('ADMIN') or hasRole('ANALYST')")
    public ResponseEntity<Page<Feedback>> getOpenIssues(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<Feedback> issues = feedbackService.getOpenIssues(page, size);
        return ResponseEntity.ok(issues);
    }

    /**
     * Get hallucination reports (high priority).
     */
    @GetMapping("/hallucinations")
    @PreAuthorize("hasRole('ADMIN') or hasRole('ANALYST')")
    public ResponseEntity<List<Feedback>> getHallucinationReports() {
        List<Feedback> reports = feedbackService.getHallucinationReports();
        return ResponseEntity.ok(reports);
    }

    /**
     * Resolve an issue.
     */
    @PostMapping("/issues/{feedbackId}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Feedback> resolveIssue(
            @PathVariable String feedbackId,
            @RequestBody ResolveRequest request) {

        User user = SecurityContext.getCurrentUser();
        String resolvedBy = user != null ? user.getDisplayName() : "SYSTEM";
        Feedback resolved = feedbackService.resolveIssue(feedbackId, resolvedBy, request.notes());

        log.info("Issue resolved: feedbackId={}, resolvedBy={}", feedbackId, resolvedBy);
        return ResponseEntity.ok(resolved);
    }

    /**
     * Export training data for model fine-tuning.
     * Returns query-response pairs with reward signals.
     */
    @GetMapping("/export/training")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<TrainingExample>> exportTrainingData(
            @RequestParam(required = false) String sector,
            @RequestParam(defaultValue = "POSITIVE") String type) {

        FeedbackType feedbackType;
        try {
            feedbackType = FeedbackType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        List<TrainingExample> examples = feedbackService.exportTrainingData(sector, feedbackType);

        log.info("Training data exported: {} examples, type={}, sector={}",
                examples.size(), feedbackType, sector != null ? sector : "all");
        return ResponseEntity.ok(examples);
    }

    /**
     * Get available feedback categories (for UI dropdown).
     */
    @GetMapping("/categories")
    public ResponseEntity<List<CategoryInfo>> getCategories() {
        List<CategoryInfo> categories = java.util.Arrays.stream(FeedbackCategory.values())
                .map(c -> new CategoryInfo(c.name(), c.getDisplayName(), c.getDescription()))
                .toList();
        return ResponseEntity.ok(categories);
    }

    // Request/Response DTOs
    public record PositiveFeedbackRequest(
            String messageId,
            String query,
            String response,
            Map<String, Object> ragMetadata
    ) {}

    public record NegativeFeedbackRequest(
            String messageId,
            String query,
            String response,
            String category,
            String comments,
            Map<String, Object> ragMetadata
    ) {}

    public record ResolveRequest(String notes) {}

    public record CategoryInfo(String value, String displayName, String description) {}
}
