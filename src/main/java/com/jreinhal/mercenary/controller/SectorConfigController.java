package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/config"})
public class SectorConfigController {
    @GetMapping(value={"/sectors"})
    public ResponseEntity<List<SectorInfo>> getAvailableSectors() {
        // M-02: Defense-in-depth auth check — require authenticated user
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        ArrayList<SectorInfo> availableSectors = new ArrayList<SectorInfo>();
        ClearanceLevel userClearance = user.getClearance();
        Set<Department> allowedSectors = user.getAllowedSectors();
        for (Department dept : Department.values()) {
            boolean hasClearance = userClearance.ordinal() >= dept.getRequiredClearance().ordinal();
            boolean isAllowed = allowedSectors == null || allowedSectors.isEmpty() || allowedSectors.contains(dept);
            if (!hasClearance || !isAllowed) continue;
            availableSectors.add(this.toSectorInfo(dept, true));
        }
        return ResponseEntity.ok(availableSectors);
    }

    @GetMapping(value={"/current-sector"})
    public ResponseEntity<SectorInfo> getCurrentSector() {
        // M-02: Defense-in-depth auth check
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Set<Department> allowed = user.getAllowedSectors();
        Department current = allowed != null && !allowed.isEmpty() ? allowed.iterator().next() : Department.ENTERPRISE;
        return ResponseEntity.ok(this.toSectorInfo(current, true));
    }

    private SectorInfo toSectorInfo(Department dept, boolean includeDetails) {
        return new SectorInfo(dept.name(), includeDetails ? dept.getCommercialLabel() : dept.name(), dept.getUiTheme(), includeDetails ? this.getIcon(dept) : "folder", includeDetails ? this.getDescription(dept) : null);
    }

    private String getIcon(Department dept) {
        return switch (dept) {
            default -> throw new IllegalArgumentException("Unknown department: " + dept);
            case Department.GOVERNMENT -> "shield";
            case Department.MEDICAL -> "heart";
            case Department.FINANCE -> "dollar-sign";
            case Department.ACADEMIC -> "book";
            case Department.ENTERPRISE -> "briefcase";
        };
    }

    private String getDescription(Department dept) {
        return switch (dept) {
            default -> throw new IllegalArgumentException("Unknown department: " + dept);
            case Department.GOVERNMENT -> "Defense and Intelligence";
            case Department.MEDICAL -> "Healthcare and Clinical";
            case Department.FINANCE -> "Financial Services";
            case Department.ACADEMIC -> "Research and Academia";
            case Department.ENTERPRISE -> "General Business";
        };
    }

    public record SectorInfo(String id, String label, String theme, String icon, String description) {
    }
}
