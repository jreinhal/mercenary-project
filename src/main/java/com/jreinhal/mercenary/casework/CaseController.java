package com.jreinhal.mercenary.casework;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/cases"})
public class CaseController {
    private final CaseService caseService;
    private final AuditService auditService;

    public CaseController(CaseService caseService, AuditService auditService) {
        this.caseService = caseService;
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<List<CaseRecord>> listCases(HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        if (!caseService.isCaseworkAllowed()) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        }
        List<CaseRecord> cases = caseService.listCases(user);
        auditService.logQuery(user, "case_list", Department.ENTERPRISE, "Case library listed", request);
        return ResponseEntity.ok(cases);
    }

    @GetMapping(value={"/{caseId}"})
    public ResponseEntity<CaseRecord> getCase(@PathVariable String caseId, HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        if (!caseService.isCaseworkAllowed()) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        }
        return caseService.getCase(caseId)
            .filter(record -> caseService.canAccess(user, record))
            .map(record -> {
                auditService.logQuery(user, "case_get:" + caseId, Department.ENTERPRISE, "Case retrieved", request);
                return ResponseEntity.ok(record);
            })
            .orElse(ResponseEntity.status((HttpStatusCode)HttpStatus.NOT_FOUND).build());
    }

    @PostMapping
    public ResponseEntity<CaseRecord> saveCase(@RequestBody CaseService.CasePayload payload, HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        try {
            CaseRecord record = caseService.saveCase(user, payload);
            auditService.logQuery(user, "case_save:" + record.caseId(), Department.ENTERPRISE, "Case saved", request);
            return ResponseEntity.ok(record);
        } catch (SecurityException e) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.BAD_REQUEST).build();
        }
    }

    @PostMapping(value={"/{caseId}/share"})
    public ResponseEntity<CaseRecord> shareCase(@PathVariable String caseId, @RequestBody CaseShareRequest requestBody, HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        try {
            CaseRecord record = caseService.shareCase(user, caseId, requestBody.usernames());
            auditService.logQuery(user, "case_share:" + caseId, Department.ENTERPRISE, "Case shared", request);
            return ResponseEntity.ok(record);
        } catch (SecurityException e) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.BAD_REQUEST).build();
        }
    }

    @PostMapping(value={"/{caseId}/review"})
    public ResponseEntity<CaseRecord> submitForReview(@PathVariable String caseId, @RequestBody CaseReviewRequest requestBody, HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        try {
            CaseRecord record = caseService.submitForReview(user, caseId, requestBody.comment());
            auditService.logQuery(user, "case_review_submit:" + caseId, Department.ENTERPRISE, "Case submitted for review", request);
            return ResponseEntity.ok(record);
        } catch (SecurityException e) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.BAD_REQUEST).build();
        }
    }

    @PostMapping(value={"/{caseId}/review/decision"})
    public ResponseEntity<CaseRecord> reviewDecision(@PathVariable String caseId, @RequestBody CaseDecisionRequest requestBody, HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).build();
        }
        try {
            CaseRecord record = caseService.reviewDecision(user, caseId, requestBody.decision(), requestBody.comment());
            auditService.logQuery(user, "case_review_decision:" + caseId, Department.ENTERPRISE, "Case review decision", request);
            return ResponseEntity.ok(record);
        } catch (SecurityException e) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.BAD_REQUEST).build();
        }
    }

    public record CaseShareRequest(List<String> usernames) {
        public CaseShareRequest {
            if (usernames == null) {
                usernames = List.of();
            }
        }
    }

    public record CaseReviewRequest(String comment) {
        public CaseReviewRequest {
            if (comment == null) {
                comment = "";
            }
        }
    }

    public record CaseDecisionRequest(String decision, String comment) {
        public CaseDecisionRequest {
            if (comment == null) {
                comment = "";
            }
        }
    }
}
