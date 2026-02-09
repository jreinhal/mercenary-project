package com.jreinhal.mercenary;

import com.jreinhal.mercenary.model.ClearanceLevel;
import java.util.Map;

public enum Department {
    GOVERNMENT(ClearanceLevel.SECRET, "Defense / Intelligence", "Classified", "dark"),
    MEDICAL(ClearanceLevel.SECRET, "Medical / Clinical", "HIPAA", "dark"),
    ENTERPRISE(ClearanceLevel.UNCLASSIFIED, "Enterprise / Corporate", "General Business", "light");

    /**
     * Legacy aliases for removed sectors. FINANCE and ACADEMIC shared identical
     * features and security posture with ENTERPRISE, so they were consolidated.
     * This map ensures persisted data and incoming requests using the old names
     * resolve correctly instead of throwing IllegalArgumentException.
     */
    private static final Map<String, Department> LEGACY_ALIASES = Map.of(
            "FINANCE", ENTERPRISE,
            "ACADEMIC", ENTERPRISE
    );

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

    /**
     * Resolves a department name, supporting legacy aliases (FINANCE → ENTERPRISE,
     * ACADEMIC → ENTERPRISE) for backward compatibility with persisted data.
     *
     * @param name the department name (case-insensitive)
     * @return the resolved Department
     * @throws IllegalArgumentException if the name is not a valid department or legacy alias
     */
    public static Department fromString(String name) {
        String normalized = name.trim().toUpperCase();
        Department legacy = LEGACY_ALIASES.get(normalized);
        if (legacy != null) {
            return legacy;
        }
        return valueOf(normalized);
    }
}
