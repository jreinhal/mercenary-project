package com.jreinhal.mercenary.repository;

import com.jreinhal.mercenary.reporting.ReportSchedule;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReportScheduleRepository extends MongoRepository<ReportSchedule, String> {
    List<ReportSchedule> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

    List<ReportSchedule> findByEnabledTrueAndNextRunAtBefore(Instant now);
}
