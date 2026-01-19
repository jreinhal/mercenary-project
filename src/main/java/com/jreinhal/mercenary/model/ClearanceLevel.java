/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.model.ClearanceLevel
 */
package com.jreinhal.mercenary.model;

public enum ClearanceLevel {
    UNCLASSIFIED(0, "Unclassified", "Public"),
    CUI(1, "Controlled Unclassified", "Confidential"),
    SECRET(2, "Secret", "Restricted"),
    TOP_SECRET(3, "Top Secret", "Highly Restricted"),
    SCI(4, "Sensitive Compartmented Information", "Eyes Only");

    private final int level;
    private final String governmentLabel;
    private final String commercialLabel;

    private ClearanceLevel(int level, String governmentLabel, String commercialLabel) {
        this.level = level;
        this.governmentLabel = governmentLabel;
        this.commercialLabel = commercialLabel;
    }

    public int getLevel() {
        return this.level;
    }

    public String getGovernmentLabel() {
        return this.governmentLabel;
    }

    public String getCommercialLabel() {
        return this.commercialLabel;
    }

    public boolean canAccess(ClearanceLevel required) {
        return this.level >= required.level;
    }
}

