package com.jreinhal.mercenary.rag.thesaurus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sentinel.thesaurus")
public class DomainThesaurusProperties {
    /**
     * Master toggle for thesaurus-driven query enrichment.
     */
    private boolean enabled = true;

    /**
     * Enables basic unit conversions for retrieval enrichment (e.g., PSI <-> MPa).
     * Disabled by default to avoid surprising expansions.
     */
    private boolean unitConversionEnabled = false;

    /**
     * Enables indexing thesaurus entries into the vector store as {@code type=thesaurus} documents.
     * Retrieval filters should exclude {@code type=thesaurus} to avoid polluting evidence.
     */
    private boolean vectorIndexEnabled = false;

    /**
     * Safety cap for deterministic expansions returned by the thesaurus for a single query.
     */
    private int maxQueryVariants = 4;

    /**
     * Per-department synonym map. Keys are department names ("GLOBAL", "MEDICAL", "GOVERNMENT", "ENTERPRISE").
     *
     * Example:
     * sentinel.thesaurus.entries.GLOBAL.HIPAA[0]=Health Insurance Portability and Accountability Act
     */
    private Map<String, Map<String, List<String>>> entries = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isUnitConversionEnabled() {
        return unitConversionEnabled;
    }

    public void setUnitConversionEnabled(boolean unitConversionEnabled) {
        this.unitConversionEnabled = unitConversionEnabled;
    }

    public boolean isVectorIndexEnabled() {
        return vectorIndexEnabled;
    }

    public void setVectorIndexEnabled(boolean vectorIndexEnabled) {
        this.vectorIndexEnabled = vectorIndexEnabled;
    }

    public int getMaxQueryVariants() {
        return maxQueryVariants;
    }

    public void setMaxQueryVariants(int maxQueryVariants) {
        this.maxQueryVariants = maxQueryVariants;
    }

    public Map<String, Map<String, List<String>>> getEntries() {
        return entries;
    }

    public void setEntries(Map<String, Map<String, List<String>>> entries) {
        this.entries = entries;
    }
}

