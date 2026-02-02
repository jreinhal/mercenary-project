package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.core.license.LicenseService;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

@Service
public class DemoDataService {
    private static final Logger log = LoggerFactory.getLogger(DemoDataService.class);

    private final SecureIngestionService ingestionService;
    private final LicenseService licenseService;

    @Value("${sentinel.demo.enabled:true}")
    private boolean demoEnabled;

    @Value("${sentinel.demo.path:classpath:demo_docs/*.*}")
    private String demoPath;

    @Value("${sentinel.demo.max-files:50}")
    private int maxFiles;

    public DemoDataService(SecureIngestionService ingestionService, LicenseService licenseService) {
        this.ingestionService = ingestionService;
        this.licenseService = licenseService;
    }

    public DemoLoadResult loadDemoData(String scenario) {
        if (!demoEnabled) {
            return new DemoLoadResult(false, 0, 0, "Demo loader disabled");
        }

        LicenseService.Edition edition = licenseService.getEdition();
        if (edition == LicenseService.Edition.MEDICAL || edition == LicenseService.Edition.GOVERNMENT) {
            return new DemoLoadResult(false, 0, 0, "Demo loader disabled for regulated editions");
        }

        int loaded = 0;
        int skipped = 0;
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(demoPath);

            if (resources.length == 0) {
                return new DemoLoadResult(false, 0, 0, "No demo data found at path: " + demoPath);
            }

            for (Resource resource : resources) {
                if (loaded >= maxFiles) break;
                String filename = resource.getFilename();
                if (filename == null) {
                    skipped++;
                    continue;
                }
                try {
                    byte[] bytes = resource.getInputStream().readAllBytes();
                    Department dept = inferDepartment(filename);
                    ingestionService.ingestBytes(bytes, filename, dept);
                    loaded++;
                } catch (Exception e) {
                    skipped++;
                    log.warn("Demo ingestion failed for {}: {}", filename, e.getMessage());
                }
            }

            return new DemoLoadResult(true, loaded, skipped, "Demo data loaded");
        } catch (IOException e) {
            return new DemoLoadResult(false, loaded, skipped, "Demo data load failed: " + e.getMessage());
        }
    }

    private Department inferDepartment(String filename) {
        String lower = filename.toLowerCase();
        if (lower.startsWith("enterprise_") || lower.startsWith("ent_")) {
            return Department.ENTERPRISE;
        } else if (lower.startsWith("finance_") || lower.startsWith("fin_")) {
            return Department.FINANCE;
        } else if (lower.startsWith("medical_") || lower.startsWith("med_")) {
            return Department.MEDICAL;
        } else if (lower.startsWith("government_") || lower.startsWith("gov_") || lower.startsWith("defense_") || lower.startsWith("def_")) {
            return Department.GOVERNMENT;
        } else if (lower.startsWith("academic_") || lower.startsWith("acad_")) {
            return Department.ACADEMIC;
        }
        return Department.ENTERPRISE;
    }

    public record DemoLoadResult(boolean success, int loaded, int skipped, String message) {
    }
}
