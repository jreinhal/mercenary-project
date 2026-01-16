package com.jreinhal.mercenary.config;

import com.jreinhal.mercenary.Department;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sector Configuration for SENTINEL Intelligence Platform.
 *
 * Single-product model with 5 sectors:
 * - GOVERNMENT: Defense/Intel (SECRET clearance)
 * - MEDICAL: Healthcare/HIPAA (SECRET clearance)
 * - FINANCE: Banking/PCI-DSS (CUI clearance)
 * - ACADEMIC: Research/Literature (UNCLASSIFIED)
 * - ENTERPRISE: Corporate/Business (UNCLASSIFIED)
 *
 * Sector access is controlled at runtime via user permissions,
 * not build-time edition gating.
 */
@Component
public class SectorConfig {

    private final Set<Department> allSectors;

    public SectorConfig() {
        // All 5 sectors are available in the single-product model
        this.allSectors = Arrays.stream(Department.values())
                .collect(Collectors.toSet());
    }

    /**
     * Get all available sectors.
     */
    public Set<Department> getAllSectors() {
        return allSectors;
    }

    /**
     * Check if a sector is available.
     * In single-product mode, all sectors are always available.
     * Access control is enforced via user clearance/permissions.
     */
    public boolean isSectorAvailable(Department dept) {
        return allSectors.contains(dept);
    }

    /**
     * Check if clearance validation is enabled.
     * In single-product mode, clearance is always validated
     * for high-security sectors (GOVERNMENT, MEDICAL, FINANCE).
     */
    public boolean isClearanceEnabled() {
        return true;
    }

    /**
     * Get list of sector names.
     */
    public List<String> getSectorNames() {
        return allSectors.stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Get high-security sectors (require elevated clearance).
     */
    public Set<Department> getHighSecuritySectors() {
        return Set.of(Department.GOVERNMENT, Department.MEDICAL, Department.FINANCE);
    }

    /**
     * Check if sector requires elevated clearance.
     */
    public boolean requiresElevatedClearance(Department dept) {
        return getHighSecuritySectors().contains(dept);
    }
}
