package com.jreinhal.mercenary;

import com.jreinhal.mercenary.model.ClearanceLevel;

/**
 * Sector classification for document partitioning and access control.
 *
 * Inspired by Palantir's vertical approach:
 * - GOVERNMENT: Defense, Intel, Classified (like Palantir Gotham)
 * - MEDICAL: Healthcare, HIPAA compliance (like Veeva Vault Clinical)
 * - FINANCE: Banking, PCI-DSS compliance
 * - ACADEMIC: Research, Literature review
 * - ENTERPRISE: General business, Legal, HR, Corporate
 *
 * Each sector has:
 * - Required clearance level for access
 * - Government context label (for federal deployments)
 * - Commercial context label (for enterprise deployments)
 * - UI theme preference (dark terminal vs light corporate)
 */
public enum Department {
    /**
     * Government/Defense sector - highest security requirements.
     * Requires SECRET clearance. CAC/PIV authentication recommended.
     * Dark terminal UI theme (Palantir Gotham-style).
     */
    GOVERNMENT(ClearanceLevel.SECRET, "Defense / Intelligence", "Classified", "dark"),

    /**
     * Medical/Healthcare sector - HIPAA compliance required.
     * Requires SECRET clearance for PHI/clinical data.
     * Dark clinical UI theme.
     */
    MEDICAL(ClearanceLevel.SECRET, "Medical / Clinical", "HIPAA", "dark"),

    /**
     * Finance/Banking sector - PCI-DSS compliance required.
     * Requires CUI clearance for financial data.
     * Dark Bloomberg-style UI theme.
     */
    FINANCE(ClearanceLevel.CUI, "Financial Services", "PCI-DSS", "dark"),

    /**
     * Academic/Research sector - citation-focused.
     * No clearance required. Open research data.
     * Light clean UI theme optimized for reading.
     */
    ACADEMIC(ClearanceLevel.UNCLASSIFIED, "Academic / Research", "Research Data", "light"),

    /**
     * Enterprise/General Business sector - catch-all for corporate use.
     * Includes legal, HR, corporate documents.
     * No clearance required. Light corporate UI theme.
     */
    ENTERPRISE(ClearanceLevel.UNCLASSIFIED, "Enterprise / Corporate", "General Business", "light");

    private final ClearanceLevel requiredClearance;
    private final String governmentLabel;
    private final String commercialLabel;
    private final String uiTheme;

    Department(ClearanceLevel requiredClearance, String governmentLabel, String commercialLabel, String uiTheme) {
        this.requiredClearance = requiredClearance;
        this.governmentLabel = governmentLabel;
        this.commercialLabel = commercialLabel;
        this.uiTheme = uiTheme;
    }

    public ClearanceLevel getRequiredClearance() {
        return requiredClearance;
    }

    public String getGovernmentLabel() {
        return governmentLabel;
    }

    public String getCommercialLabel() {
        return commercialLabel;
    }

    /**
     * Get the preferred UI theme for this sector.
     * @return "dark" for high-security sectors, "light" for general use
     */
    public String getUiTheme() {
        return uiTheme;
    }

    /**
     * Check if this sector requires elevated clearance.
     */
    public boolean requiresClearance() {
        return requiredClearance.getLevel() > ClearanceLevel.UNCLASSIFIED.getLevel();
    }
}
