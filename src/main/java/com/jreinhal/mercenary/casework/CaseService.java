package com.jreinhal.mercenary.casework;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.repository.UserRepository;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class CaseService {
    private static final Logger log = LoggerFactory.getLogger(CaseService.class);
    private static final String COLLECTION = "case_records";
    private static final int MAX_TITLE_LENGTH = 160;
    private static final int MAX_SUMMARY_LENGTH = 4000;
    private static final int MAX_DETAIL_LENGTH = 1200;
    private static final int MAX_NOTE_LENGTH = 1200;
    private static final int MAX_TIMELINE_ENTRIES = 200;
    private static final int MAX_NOTES = 200;

    private final MongoTemplate mongoTemplate;
    private final CasePolicy casePolicy;
    private final UserRepository userRepository;

    public CaseService(MongoTemplate mongoTemplate, CasePolicy casePolicy, UserRepository userRepository) {
        this.mongoTemplate = mongoTemplate;
        this.casePolicy = casePolicy;
        this.userRepository = userRepository;
    }

    public boolean isCaseworkAllowed() {
        return casePolicy.allowCasework();
    }

    public List<CaseRecord> listCases(User user) {
        String workspaceId = currentWorkspaceId();
        Query query = new Query(new Criteria().andOperator(
            Criteria.where("workspaceId").is(workspaceId),
            new Criteria().orOperator(
                Criteria.where("ownerId").is(user.getId()),
                Criteria.where("sharedWith").in(user.getId())
            )
        ));
        query.with(Sort.by(Sort.Direction.DESC, "updatedAt"));
        return mongoTemplate.find(query, CaseRecord.class, COLLECTION);
    }

    public Optional<CaseRecord> getCase(String caseId) {
        if (caseId == null || caseId.isBlank()) return Optional.empty();
        String workspaceId = currentWorkspaceId();
        Query query = new Query(Criteria.where("caseId").is(caseId).and("workspaceId").is(workspaceId));
        CaseRecord record = mongoTemplate.findOne(query, CaseRecord.class, COLLECTION);
        return Optional.ofNullable(record);
    }

    public CaseRecord saveCase(User user, CasePayload payload) {
        ensureAllowed();
        if (payload == null) {
            throw new IllegalArgumentException("Case payload is required");
        }

        String caseId = normalize(payload.caseId());
        CaseRecord existing = caseId != null ? mongoTemplate.findById(caseId, CaseRecord.class, COLLECTION) : null;
        if (existing == null && caseId != null) {
            Query collisionCheck = new Query(Criteria.where("caseId").is(caseId));
            CaseRecord otherWorkspace = mongoTemplate.findOne(collisionCheck, CaseRecord.class, COLLECTION);
            if (otherWorkspace != null) {
                throw new SecurityException("Case belongs to another workspace");
            }
        }
        if (existing != null && !canAccess(user, existing)) {
            throw new SecurityException("Access denied");
        }

        String workspaceId = currentWorkspaceId();
        String finalId = existing != null ? existing.caseId() : (caseId != null ? caseId : generateCaseId());
        String ownerId = existing != null ? existing.ownerId() : user.getId();
        boolean ownerOrAdmin = existing == null || isOwnerOrAdmin(user, existing);
        String sector = normalize(payload.sector());
        if (sector == null && existing != null) {
            sector = existing.sector();
        }
        if (sector == null) {
            sector = "ENTERPRISE";
        }

        CaseRecord.CaseStatus status = ownerOrAdmin ? parseStatus(payload.status()) : null;
        if (status == null && existing != null) {
            status = existing.status();
        }
        if (status == null) {
            status = CaseRecord.CaseStatus.DRAFT;
        }

        List<CaseRecord.CaseEntry> timeline = sanitizeTimeline(payload.timeline());
        if (timeline.isEmpty() && existing != null) {
            timeline = existing.timeline();
        }

        List<CaseRecord.CaseNote> notes = sanitizeNotes(payload.notes());
        if (notes.isEmpty() && existing != null) {
            notes = existing.notes();
        }

        String title = ownerOrAdmin
            ? clamp(payload.title(), MAX_TITLE_LENGTH, existing != null ? existing.title() : "New Case")
            : (existing != null ? existing.title() : "New Case");
        String summary = ownerOrAdmin
            ? clamp(payload.summary(), MAX_SUMMARY_LENGTH, existing != null ? existing.summary() : "")
            : (existing != null ? existing.summary() : "");

        List<String> sharedWith = existing != null ? existing.sharedWith() : Collections.emptyList();
        List<CaseRecord.CaseReview> reviews = existing != null ? existing.reviews() : Collections.emptyList();

        Instant createdAt = existing != null ? existing.createdAt() : Instant.now();
        Instant updatedAt = Instant.now();

        boolean redactionRequired = status == CaseRecord.CaseStatus.REDACTION_REQUIRED;
        String redactionNotes = ownerOrAdmin ? payload.redactionNotes() : null;
        if ((redactionNotes == null || redactionNotes.isBlank()) && existing != null) {
            redactionNotes = existing.redactionNotes();
        }

        CaseRecord record = new CaseRecord(
            finalId,
            ownerId,
            workspaceId,
            title,
            sector,
            status,
            createdAt,
            updatedAt,
            timeline,
            notes,
            sharedWith,
            reviews,
            summary,
            user.getId(),
            redactionRequired,
            redactionNotes
        );

        mongoTemplate.save(record, COLLECTION);
        return record;
    }

    public CaseRecord shareCase(User user, String caseId, List<String> usernames) {
        ensureAllowed();
        CaseRecord record = requireCase(caseId);
        if (!isOwnerOrAdmin(user, record)) {
            throw new SecurityException("Access denied");
        }
        List<String> targetIds = resolveUserIds(usernames);
        if (targetIds.isEmpty()) {
            return record;
        }
        List<String> updatedShared = new ArrayList<>(record.sharedWith());
        for (String id : targetIds) {
            if (!updatedShared.contains(id)) {
                updatedShared.add(id);
            }
        }
        CaseRecord updated = new CaseRecord(
            record.caseId(),
            record.ownerId(),
            record.workspaceId(),
            record.title(),
            record.sector(),
            record.status(),
            record.createdAt(),
            Instant.now(),
            record.timeline(),
            record.notes(),
            updatedShared,
            record.reviews(),
            record.summary(),
            user.getId(),
            record.redactionRequired(),
            record.redactionNotes()
        );
        mongoTemplate.save(updated, COLLECTION);
        return updated;
    }

    public CaseRecord submitForReview(User user, String caseId, String comment) {
        ensureAllowed();
        CaseRecord record = requireCase(caseId);
        if (!isOwnerOrAdmin(user, record)) {
            throw new SecurityException("Access denied");
        }
        List<CaseRecord.CaseReview> reviews = new ArrayList<>(record.reviews());
        reviews.add(new CaseRecord.CaseReview(user.getId(), "SUBMITTED", clamp(comment, 800, ""), Instant.now()));
        CaseRecord updated = new CaseRecord(
            record.caseId(),
            record.ownerId(),
            record.workspaceId(),
            record.title(),
            record.sector(),
            CaseRecord.CaseStatus.IN_REVIEW,
            record.createdAt(),
            Instant.now(),
            record.timeline(),
            record.notes(),
            record.sharedWith(),
            reviews,
            record.summary(),
            user.getId(),
            false,
            record.redactionNotes()
        );
        mongoTemplate.save(updated, COLLECTION);
        return updated;
    }

    public CaseRecord reviewDecision(User reviewer, String caseId, String decision, String comment) {
        ensureAllowed();
        CaseRecord record = requireCase(caseId);
        if (!isAdmin(reviewer)) {
            throw new SecurityException("Admin access required for review decisions");
        }
        CaseRecord.CaseStatus status = parseStatus(decision);
        if (status == null) {
            throw new IllegalArgumentException("Invalid decision");
        }
        List<CaseRecord.CaseReview> reviews = new ArrayList<>(record.reviews());
        reviews.add(new CaseRecord.CaseReview(reviewer.getId(), decision, clamp(comment, 800, ""), Instant.now()));
        boolean redactionRequired = status == CaseRecord.CaseStatus.REDACTION_REQUIRED;
        CaseRecord updated = new CaseRecord(
            record.caseId(),
            record.ownerId(),
            record.workspaceId(),
            record.title(),
            record.sector(),
            status,
            record.createdAt(),
            Instant.now(),
            record.timeline(),
            record.notes(),
            record.sharedWith(),
            reviews,
            record.summary(),
            reviewer.getId(),
            redactionRequired,
            redactionRequired ? clamp(comment, 800, record.redactionNotes()) : record.redactionNotes()
        );
        mongoTemplate.save(updated, COLLECTION);
        return updated;
    }

    public boolean canAccess(User user, CaseRecord record) {
        if (record == null || user == null) return false;
        if (record.ownerId().equals(user.getId())) return true;
        if (record.sharedWith().contains(user.getId())) return true;
        return isAdmin(user);
    }

    private boolean isOwnerOrAdmin(User user, CaseRecord record) {
        return record.ownerId().equals(user.getId()) || isAdmin(user);
    }

    private boolean isAdmin(User user) {
        return user.getRoles().stream().anyMatch(role -> role == UserRole.ADMIN);
    }

    private void ensureAllowed() {
        if (!casePolicy.allowCasework()) {
            throw new SecurityException("Case collaboration disabled for this edition");
        }
    }

    private CaseRecord requireCase(String caseId) {
        return getCase(caseId).orElseThrow(() -> new IllegalArgumentException("Case not found"));
    }

    private String generateCaseId() {
        return "case_" + UUID.randomUUID().toString().substring(0, 10);
    }

    private CaseRecord.CaseStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return CaseRecord.CaseStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<CaseRecord.CaseEntry> sanitizeTimeline(List<CaseRecord.CaseEntry> entries) {
        if (entries == null || entries.isEmpty()) return Collections.emptyList();
        return entries.stream()
            .limit(MAX_TIMELINE_ENTRIES)
            .map(entry -> new CaseRecord.CaseEntry(
                normalize(entry.id()),
                clamp(entry.type(), 32, "note"),
                entry.timestamp() != null ? entry.timestamp() : Instant.now(),
                clamp(entry.title(), MAX_TITLE_LENGTH, ""),
                clamp(entry.detail(), MAX_DETAIL_LENGTH, ""),
                normalize(entry.msgId()),
                entry.sources() != null ? entry.sources() : Collections.emptyList()
            ))
            .collect(Collectors.toList());
    }

    private List<CaseRecord.CaseNote> sanitizeNotes(List<CaseRecord.CaseNote> notes) {
        if (notes == null || notes.isEmpty()) return Collections.emptyList();
        return notes.stream()
            .limit(MAX_NOTES)
            .map(note -> new CaseRecord.CaseNote(
                normalize(note.id()),
                note.timestamp() != null ? note.timestamp() : Instant.now(),
                clamp(note.text(), MAX_NOTE_LENGTH, "")
            ))
            .collect(Collectors.toList());
    }

    private List<String> resolveUserIds(List<String> usernames) {
        if (usernames == null || usernames.isEmpty()) return Collections.emptyList();
        List<String> ids = new ArrayList<>();
        for (String name : usernames) {
            String normalized = normalize(name);
            if (normalized == null) continue;
            Optional<User> user = userRepository.findByUsername(normalized);
            user.ifPresent(value -> ids.add(value.getId()));
        }
        return ids;
    }

    private String clamp(String value, int maxLength, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) return trimmed;
        return trimmed.substring(0, maxLength);
    }

    private String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String currentWorkspaceId() {
        return WorkspaceContext.getCurrentWorkspaceId();
    }

    public record CasePayload(
        String caseId,
        String title,
        String sector,
        String status,
        String summary,
        List<CaseRecord.CaseEntry> timeline,
        List<CaseRecord.CaseNote> notes,
        String redactionNotes
    ) {}
}
