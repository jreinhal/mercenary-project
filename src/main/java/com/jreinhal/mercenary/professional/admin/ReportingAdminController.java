package com.jreinhal.mercenary.professional.admin;

import com.jreinhal.mercenary.reporting.ExecutiveReport;
import com.jreinhal.mercenary.reporting.ExecutiveReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/admin/reports"})
@PreAuthorize(value="hasRole('ADMIN')")
public class ReportingAdminController {
    private final ExecutiveReportService reportService;

    public ReportingAdminController(ExecutiveReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping(value={"/executive"})
    public ResponseEntity<ExecutiveReport> getExecutiveReport(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return ResponseEntity.ok(reportService.buildExecutiveReport(days));
    }
}
