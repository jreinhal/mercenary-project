package com.jreinhal.mercenary.casework;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection="case_records")
public record CaseRecord(
    @Id String caseId,
    String ownerId,
    String workspaceId,
    String title,
    String sector,
    CaseStatus status,
    Instant createdAt,
    Instant updatedAt,
    List<CaseEntry> timeline,
    List<CaseNote> notes,
    List<String> sharedWith,
    List<CaseReview> reviews,
    String summary,
    String lastSavedBy,
    boolean redactionRequired,
    String redactionNotes
) {
    public enum CaseStatus {
        DRAFT,
        IN_REVIEW,
        APPROVED,
        REDACTION_REQUIRED
    }

    public record CaseEntry(
        String id,
        String type,
        Instant timestamp,
        String title,
        String detail,
        String msgId,
        List<String> sources
    ) {}

    public record CaseNote(
        String id,
        Instant timestamp,
        String text
    ) {}

    public record CaseReview(
        String reviewerId,
        String decision,
        String comment,
        Instant timestamp
    ) {}
}
