/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.Department
 *  com.jreinhal.mercenary.controller.SectorConfigController
 *  com.jreinhal.mercenary.controller.SectorConfigController$1
 *  com.jreinhal.mercenary.controller.SectorConfigController$SectorInfo
 *  com.jreinhal.mercenary.filter.SecurityContext
 *  com.jreinhal.mercenary.model.ClearanceLevel
 *  com.jreinhal.mercenary.model.User
 *  org.springframework.http.ResponseEntity
 *  org.springframework.web.bind.annotation.GetMapping
 *  org.springframework.web.bind.annotation.RequestMapping
 *  org.springframework.web.bind.annotation.RestController
 */
package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.controller.SectorConfigController;
import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/config"})
public class SectorConfigController {
    @GetMapping(value={"/sectors"})
    public ResponseEntity<List<SectorInfo>> getAvailableSectors() {
        User user = SecurityContext.getCurrentUser();
        ArrayList<SectorInfo> availableSectors = new ArrayList<SectorInfo>();
        if (user == null) {
            for (Department dept : Department.values()) {
                if (dept.getRequiredClearance() != ClearanceLevel.UNCLASSIFIED) continue;
                availableSectors.add(this.toSectorInfo(dept, false));
            }
        } else {
            ClearanceLevel userClearance = user.getClearance();
            Set allowedSectors = user.getAllowedSectors();
            for (Department dept : Department.values()) {
                boolean isAllowed;
                boolean hasClearance = userClearance.ordinal() >= dept.getRequiredClearance().ordinal();
                boolean bl = isAllowed = allowedSectors == null || allowedSectors.isEmpty() || allowedSectors.contains(dept);
                if (!hasClearance || !isAllowed) continue;
                availableSectors.add(this.toSectorInfo(dept, true));
            }
        }
        return ResponseEntity.ok(availableSectors);
    }

    @GetMapping(value={"/current-sector"})
    public ResponseEntity<SectorInfo> getCurrentSector() {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.ok((Object)this.toSectorInfo(Department.ENTERPRISE, false));
        }
        Set allowed = user.getAllowedSectors();
        Department current = allowed != null && !allowed.isEmpty() ? (Department)allowed.iterator().next() : Department.ENTERPRISE;
        return ResponseEntity.ok((Object)this.toSectorInfo(current, true));
    }

    private SectorInfo toSectorInfo(Department dept, boolean includeDetails) {
        return new SectorInfo(dept.name(), includeDetails ? dept.getCommercialLabel() : dept.name(), dept.getUiTheme(), includeDetails ? this.getIcon(dept) : "folder", includeDetails ? this.getDescription(dept) : null);
    }

    private String getIcon(Department dept) {
        return switch (1.$SwitchMap$com$jreinhal$mercenary$Department[dept.ordinal()]) {
            default -> throw new MatchException(null, null);
            case 1 -> "shield";
            case 2 -> "heart";
            case 3 -> "dollar-sign";
            case 4 -> "book";
            case 5 -> "briefcase";
        };
    }

    private String getDescription(Department dept) {
        return switch (1.$SwitchMap$com$jreinhal$mercenary$Department[dept.ordinal()]) {
            default -> throw new MatchException(null, null);
            case 1 -> "Defense and Intelligence";
            case 2 -> "Healthcare and Clinical";
            case 3 -> "Financial Services";
            case 4 -> "Research and Academia";
            case 5 -> "General Business";
        };
    }
}

