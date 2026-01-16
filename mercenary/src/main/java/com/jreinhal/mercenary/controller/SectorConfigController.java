package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * REST controller for sector configuration.
 *
 * SECURITY: Only returns sectors the current user is authorized to access.
 * This prevents information disclosure about sectors beyond the user's clearance.
 */
@RestController
@RequestMapping("/api/config")
public class SectorConfigController {

    /**
     * Get sectors available to the current user based on their clearance level.
     * SECURITY: Does not expose sectors the user cannot access.
     */
    @GetMapping("/sectors")
    public ResponseEntity<List<SectorInfo>> getAvailableSectors() {
        User user = SecurityContext.getCurrentUser();
        List<SectorInfo> availableSectors = new ArrayList<>();

        if (user == null) {
            // Anonymous users only see UNCLASSIFIED sectors
            for (Department dept : Department.values()) {
                if (dept.getRequiredClearance() == ClearanceLevel.UNCLASSIFIED) {
                    availableSectors.add(toSectorInfo(dept, false));
                }
            }
        } else {
            ClearanceLevel userClearance = user.getClearance();
            Set<Department> allowedSectors = user.getAllowedSectors();

            for (Department dept : Department.values()) {
                // User must have sufficient clearance AND be in allowed sectors
                boolean hasClearance = userClearance.ordinal() >= dept.getRequiredClearance().ordinal();
                boolean isAllowed = allowedSectors == null || allowedSectors.isEmpty() || allowedSectors.contains(dept);

                if (hasClearance && isAllowed) {
                    availableSectors.add(toSectorInfo(dept, true));
                }
            }
        }

        return ResponseEntity.ok(availableSectors);
    }

    /**
     * Get current user's active sector.
     */
    @GetMapping("/current-sector")
    public ResponseEntity<SectorInfo> getCurrentSector() {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.ok(toSectorInfo(Department.ENTERPRISE, false));
        }

        // Return first allowed sector or default
        Set<Department> allowed = user.getAllowedSectors();
        Department current = (allowed != null && !allowed.isEmpty())
            ? allowed.iterator().next()
            : Department.ENTERPRISE;

        return ResponseEntity.ok(toSectorInfo(current, true));
    }

    /**
     * Convert Department enum to SectorInfo DTO.
     * Only includes information appropriate for the user's access level.
     */
    private SectorInfo toSectorInfo(Department dept, boolean includeDetails) {
        return new SectorInfo(
            dept.name(),
            includeDetails ? dept.getCommercialLabel() : dept.name(),
            dept.getUiTheme(),
            includeDetails ? getIcon(dept) : "folder",
            includeDetails ? getDescription(dept) : null
        );
    }

    private String getIcon(Department dept) {
        return switch (dept) {
            case GOVERNMENT -> "shield";
            case MEDICAL -> "heart";
            case FINANCE -> "dollar-sign";
            case ACADEMIC -> "book";
            case ENTERPRISE -> "briefcase";
        };
    }

    private String getDescription(Department dept) {
        return switch (dept) {
            case GOVERNMENT -> "Defense and Intelligence";
            case MEDICAL -> "Healthcare and Clinical";
            case FINANCE -> "Financial Services";
            case ACADEMIC -> "Research and Academia";
            case ENTERPRISE -> "General Business";
        };
    }

    /**
     * Sector information DTO.
     * Does NOT include clearance requirements to prevent information disclosure.
     */
    public record SectorInfo(
        String id,
        String label,
        String theme,
        String icon,
        String description
    ) {}
}
