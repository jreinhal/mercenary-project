package com.jreinhal.mercenary.repository;

import com.jreinhal.mercenary.reporting.ReportExport;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReportExportRepository extends MongoRepository<ReportExport, String> {
    List<ReportExport> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
}
