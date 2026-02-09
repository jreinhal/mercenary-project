package com.jreinhal.mercenary.config;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.rag.hgmem.HyperGraphMemory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Sample data loader for development and testing.
 * Auto-ingests test documents into HyperGraphMemory so the Entity Network has data to display.
 *
 * Only active when 'dev' profile is enabled AND sample loading is enabled.
 * This ensures production deployments don't accidentally load test data.
 *
 * Enable with: APP_PROFILE=dev and SAMPLE_DATA_LOAD=true
 */
@Configuration
@Profile("dev")
public class SampleDataLoader {
    private static final Logger log = LoggerFactory.getLogger(SampleDataLoader.class);

    @Value("${sentinel.sample-data.load:false}")
    private boolean loadSampleData;

    @Value("${sentinel.sample-data.path:classpath:test_docs/*.txt}")
    private String sampleDataPath;

    private final HyperGraphMemory hyperGraphMemory;

    public SampleDataLoader(HyperGraphMemory hyperGraphMemory) {
        this.hyperGraphMemory = hyperGraphMemory;
    }

    @Bean
    public CommandLineRunner loadSampleDocuments() {
        return args -> {
            if (!loadSampleData) {
                log.debug("Sample data loading disabled (set SAMPLE_DATA_LOAD=true to enable)");
                return;
            }

            if (!hyperGraphMemory.isIndexingEnabled()) {
                log.warn("HGMem indexing is disabled - sample data will not be loaded");
                return;
            }

            log.info("==========================================================");
            log.info("  LOADING SAMPLE DOCUMENTS FOR ENTITY NETWORK");
            log.info("==========================================================");

            try {
                PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
                Resource[] resources = resolver.getResources(sampleDataPath);

                if (resources.length == 0) {
                    log.warn("No sample documents found at path: {}", sampleDataPath);
                    return;
                }

                int loaded = 0;
                for (Resource resource : resources) {
                    try {
                        String filename = resource.getFilename();
                        if (filename == null) continue;

                        // Determine department from filename prefix
                        Department dept = inferDepartment(filename);

                        // Read file content
                        String content = readResource(resource);
                        if (content.isBlank()) {
                            log.debug("Skipping empty file: {}", filename);
                            continue;
                        }

                        // Create document with metadata
                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("source", filename);
                        metadata.put("department", dept.name());
                        metadata.put("sample_data", true);

                        Document doc = new Document(content, metadata);

                        // Index into HyperGraphMemory
                        hyperGraphMemory.indexDocument(doc, dept.name());
                        loaded++;
                        log.info("  Loaded: {} -> {} department", filename, dept.name());

                    } catch (Exception e) {
                        log.warn("Failed to load sample document {}: {}", resource.getFilename(), e.getMessage());
                    }
                }

                log.info("==========================================================");
                log.info("  SAMPLE DATA LOADED: {} documents indexed", loaded);
                log.info("  Entity Network should now display graph data");
                log.info("==========================================================");

            } catch (IOException e) {
                log.error("Error loading sample documents: {}", e.getMessage(), e);
            }
        };
    }

    /**
     * Infer department from filename prefix.
     * E.g., "enterprise_org_structure.txt" -> ENTERPRISE
     */
    private Department inferDepartment(String filename) {
        String lower = filename.toLowerCase();
        if (lower.startsWith("enterprise_") || lower.startsWith("ent_")) {
            return Department.ENTERPRISE;
        } else if (lower.startsWith("finance_") || lower.startsWith("fin_")) {
            return Department.ENTERPRISE;
        } else if (lower.startsWith("medical_") || lower.startsWith("med_")) {
            return Department.MEDICAL;
        } else if (lower.startsWith("government_") || lower.startsWith("gov_") ||
                   lower.startsWith("defense_") || lower.startsWith("def_")) {
            return Department.GOVERNMENT;
        } else if (lower.startsWith("academic_") || lower.startsWith("acad_")) {
            return Department.ENTERPRISE;
        } else if (lower.startsWith("legal_") || lower.startsWith("leg_")) {
            return Department.ENTERPRISE; // Legal docs go to enterprise
        } else if (lower.startsWith("operations_") || lower.startsWith("ops_") ||
                   lower.startsWith("operational_")) {
            return Department.ENTERPRISE; // Operations docs go to enterprise
        }
        // Default to ENTERPRISE for unrecognized prefixes
        return Department.ENTERPRISE;
    }

    private String readResource(Resource resource) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
