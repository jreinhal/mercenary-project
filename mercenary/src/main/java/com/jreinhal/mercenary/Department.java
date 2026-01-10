package com.jreinhal.mercenary;

import com.jreinhal.mercenary.model.ClearanceLevel;

/**
 * Department/sector classification with required clearance levels.
 * 
 * Each sector has a minimum clearance level for access:
 * - Government: Maps to classification levels
 * - Commercial: Maps to data sensitivity (HIPAA, PCI, Privileged)
 */
public enum Department {
    OPERATIONS(ClearanceLevel.UNCLASSIFIED, "General Operations", "Public"),
    FINANCE(ClearanceLevel.CUI, "Financial Intelligence", "PCI-DSS"),
    LEGAL(ClearanceLevel.CUI, "Legal/Contracts", "Attorney-Client"),
    MEDICAL(ClearanceLevel.SECRET, "Medical/Clinical", "HIPAA"),
    DEFENSE(ClearanceLevel.SECRET, "Defense/Military", "CLASSIFIED"),
    ENTERPRISE(ClearanceLevel.CUI, "Enterprise", "Confidential");

    private final ClearanceLevel requiredClearance;
    private final String governmentLabel;
    private final String commercialLabel;

    Department(ClearanceLevel requiredClearance, String governmentLabel, String commercialLabel) {
        this.requiredClearance = requiredClearance;
        this.governmentLabel = governmentLabel;
        this.commercialLabel = commercialLabel;
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
}