/*
 * Decompiled with CFR 0.152.
 */
package com.jreinhal.mercenary;

import com.jreinhal.mercenary.model.ClearanceLevel;

public enum Department {
    GOVERNMENT(ClearanceLevel.SECRET, "Defense / Intelligence", "Classified", "dark"),
    MEDICAL(ClearanceLevel.SECRET, "Medical / Clinical", "HIPAA", "dark"),
    FINANCE(ClearanceLevel.CUI, "Financial Services", "PCI-DSS", "dark"),
    ACADEMIC(ClearanceLevel.UNCLASSIFIED, "Academic / Research", "Research Data", "light"),
    ENTERPRISE(ClearanceLevel.UNCLASSIFIED, "Enterprise / Corporate", "General Business", "light");

    private final ClearanceLevel requiredClearance;
    private final String governmentLabel;
    private final String commercialLabel;
    private final String uiTheme;

    private Department(ClearanceLevel requiredClearance, String governmentLabel, String commercialLabel, String uiTheme) {
        this.requiredClearance = requiredClearance;
        this.governmentLabel = governmentLabel;
        this.commercialLabel = commercialLabel;
        this.uiTheme = uiTheme;
    }

    public ClearanceLevel getRequiredClearance() {
        return this.requiredClearance;
    }

    public String getGovernmentLabel() {
        return this.governmentLabel;
    }

    public String getCommercialLabel() {
        return this.commercialLabel;
    }

    public String getUiTheme() {
        return this.uiTheme;
    }

    public boolean requiresClearance() {
        return this.requiredClearance.getLevel() > ClearanceLevel.UNCLASSIFIED.getLevel();
    }
}
