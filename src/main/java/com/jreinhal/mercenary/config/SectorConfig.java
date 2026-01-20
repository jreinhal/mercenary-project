package com.jreinhal.mercenary.config;

import com.jreinhal.mercenary.Department;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class SectorConfig {
    private final Set<Department> allSectors = Arrays.stream(Department.values()).collect(Collectors.toSet());

    public Set<Department> getAllSectors() {
        return this.allSectors;
    }

    public boolean isSectorAvailable(Department dept) {
        return this.allSectors.contains(dept);
    }

    public boolean isClearanceEnabled() {
        return true;
    }

    public List<String> getSectorNames() {
        return this.allSectors.stream().map(Enum::name).sorted().collect(Collectors.toList());
    }

    public Set<Department> getHighSecuritySectors() {
        return Set.of(Department.GOVERNMENT, Department.MEDICAL, Department.FINANCE);
    }

    public boolean requiresElevatedClearance(Department dept) {
        return this.getHighSecuritySectors().contains(dept);
    }
}
