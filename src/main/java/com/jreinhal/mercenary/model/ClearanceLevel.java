package com.jreinhal.mercenary.model;

/**
 * Data classification levels supporting both government and commercial
 * verticals.
 * 
 * Government mapping:
 * UNCLASSIFIED -> Public
 * CUI -> Controlled Unclassified Information
 * SECRET -> SECRET
 * TOP_SECRET -> TS/SCI
 * 
 * Commercial mapping:
 * UNCLASSIFIED -> Public
 * CUI -> Confidential/Internal
 * SECRET -> Privileged/Restricted (HIPAA, Attorney-Client)
 * TOP_SECRET -> Highly Restricted (PCI-DSS, Trade Secrets)
 */
public enum ClearanceLevel {
    UNCLASSIFIED(0, "Unclassified", "Public"),
    CUI(1, "Controlled Unclassified", "Confidential"),
    SECRET(2, "Secret", "Restricted"),
    TOP_SECRET(3, "Top Secret", "Highly Restricted"),
    SCI(4, "Sensitive Compartmented Information", "Eyes Only");

    private final int level;
    private final String governmentLabel;
    private final String commercialLabel;

    ClearanceLevel(int level, String governmentLabel, String commercialLabel) {
        this.level = level;
        this.governmentLabel = governmentLabel;
        this.commercialLabel = commercialLabel;
    }

    public int getLevel() {
        return level;
    }

    public String getGovernmentLabel() {
        return governmentLabel;
    }

    public String getCommercialLabel() {
        return commercialLabel;
    }

    /**
     * Check if this clearance level can access data at the required level.
     */
    public boolean canAccess(ClearanceLevel required) {
        return this.level >= required.level;
    }
}
