package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import jakarta.servlet.http.HttpServletRequest;

public interface AuthenticationService {
    public User authenticate(HttpServletRequest var1);

    default public boolean hasAccess(User user, Department sector, ClearanceLevel required) {
        if (user == null) {
            return false;
        }
        if (!user.getClearance().canAccess(required)) {
            return false;
        }
        return user.canAccessSector(sector);
    }

    public String getAuthMode();
}
