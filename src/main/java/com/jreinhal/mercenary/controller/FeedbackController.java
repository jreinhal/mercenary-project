package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.Feedback;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.service.FeedbackService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/feedback"})
public class FeedbackController {
    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);
    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping(value={"/positive"})
    public ResponseEntity<FeedbackService.FeedbackResult> submitPositive(@RequestBody PositiveFeedbackRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((int)401).build();
        }
        String userId = user.getId();
        String username = user.getDisplayName();
        String sector = user.getAllowedSectors().stream().findFirst().map(Enum::name).orElse(null);
        FeedbackService.FeedbackResult result = this.feedbackService.submitPositiveFeedback(userId, username, sector, request.messageId(), request.query(), request.response(), request.ragMetadata());
        log.debug("Positive feedback submitted: messageId={}, user={}", request.messageId(), userId);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value={"/negative"})
    public ResponseEntity<FeedbackService.FeedbackResult> submitNegative(@RequestBody NegativeFeedbackRequest request) {
        Feedback.FeedbackCategory category;
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((int)401).build();
        }
        String userId = user.getId();
        String username = user.getDisplayName();
        String sector = user.getAllowedSectors().stream().findFirst().map(Enum::name).orElse(null);
        try {
            category = Feedback.FeedbackCategory.valueOf(request.category().toUpperCase());
        }
        catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        FeedbackService.FeedbackResult result = this.feedbackService.submitNegativeFeedback(userId, username, sector, request.messageId(), request.query(), request.response(), category, request.comments(), request.ragMetadata());
        log.info("Negative feedback submitted: messageId={}, category={}, user={}", new Object[]{request.messageId(), category, userId});
        return ResponseEntity.ok(result);
    }

    @GetMapping(value={"/analytics"})
    @PreAuthorize(value="hasRole('ADMIN') or hasRole('ANALYST')")
    public ResponseEntity<FeedbackService.FeedbackAnalytics> getAnalytics(@RequestParam(required=false) String sector, @RequestParam(defaultValue="30") int days) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((int)401).build();
        }
        String userId = user.getId();
        String userSector = user.getAllowedSectors().stream().findFirst().map(Enum::name).orElse(null);
        if (!user.hasRole(UserRole.ADMIN) && sector != null && !sector.equals(userSector)) {
            log.warn("User {} attempted to access analytics for sector {} without permission", userId, sector);
            sector = userSector;
        }
        FeedbackService.FeedbackAnalytics analytics = this.feedbackService.getAnalytics(sector, days);
        return ResponseEntity.ok(analytics);
    }

    @GetMapping(value={"/issues"})
    @PreAuthorize(value="hasRole('ADMIN') or hasRole('ANALYST')")
    public ResponseEntity<Page<Feedback>> getOpenIssues(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        Page<Feedback> issues = this.feedbackService.getOpenIssues(page, size);
        return ResponseEntity.ok(issues);
    }

    @GetMapping(value={"/hallucinations"})
    @PreAuthorize(value="hasRole('ADMIN') or hasRole('ANALYST')")
    public ResponseEntity<List<Feedback>> getHallucinationReports() {
        List<Feedback> reports = this.feedbackService.getHallucinationReports();
        return ResponseEntity.ok(reports);
    }

    @PostMapping(value={"/issues/{feedbackId}/resolve"})
    @PreAuthorize(value="hasRole('ADMIN')")
    public ResponseEntity<Feedback> resolveIssue(@PathVariable String feedbackId, @RequestBody ResolveRequest request) {
        User user = SecurityContext.getCurrentUser();
        String resolvedBy = user != null ? user.getDisplayName() : "SYSTEM";
        Feedback resolved = this.feedbackService.resolveIssue(feedbackId, resolvedBy, request.notes());
        log.info("Issue resolved: feedbackId={}, resolvedBy={}", feedbackId, resolvedBy);
        return ResponseEntity.ok(resolved);
    }

    @GetMapping(value={"/export/training"})
    @PreAuthorize(value="hasRole('ADMIN')")
    public ResponseEntity<List<FeedbackService.TrainingExample>> exportTrainingData(@RequestParam(required=false) String sector, @RequestParam(defaultValue="POSITIVE") String type) {
        Feedback.FeedbackType feedbackType;
        try {
            feedbackType = Feedback.FeedbackType.valueOf(type.toUpperCase());
        }
        catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        List<FeedbackService.TrainingExample> examples = this.feedbackService.exportTrainingData(sector, feedbackType);
        log.info("Training data exported: {} examples, type={}, sector={}", new Object[]{examples.size(), feedbackType, sector != null ? sector : "all"});
        return ResponseEntity.ok(examples);
    }

    @GetMapping(value={"/categories"})
    public ResponseEntity<List<CategoryInfo>> getCategories() {
        List<CategoryInfo> categories = Arrays.stream(Feedback.FeedbackCategory.values()).map(c -> new CategoryInfo(c.name(), c.getDisplayName(), c.getDescription())).toList();
        return ResponseEntity.ok(categories);
    }

    public record PositiveFeedbackRequest(String messageId, String query, String response, Map<String, Object> ragMetadata) {
    }

    public record NegativeFeedbackRequest(String messageId, String query, String response, String category, String comments, Map<String, Object> ragMetadata) {
    }

    public record ResolveRequest(String notes) {
    }

    public record CategoryInfo(String value, String displayName, String description) {
    }
}
