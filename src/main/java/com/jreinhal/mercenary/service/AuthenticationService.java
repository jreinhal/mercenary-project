package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.Department;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Authentication service interface supporting multiple auth modes.
 */
public interface AuthenticationService {

    /**
     * Authenticate a user from the HTTP request.
     * 
     * @param request The incoming HTTP request
     * @return The authenticated user, or null if authentication fails
     */
    User authenticate(HttpServletRequest request);

    /**
     * Check if user has access to a sector at the required clearance level.
     */
    default boolean hasAccess(User user, Department sector, ClearanceLevel required) {
        if (user == null)
            return false;

        // Check clearance level
        if (!user.getClearance().canAccess(required)) {
            return false;
        }

        // Check sector access
        return user.canAccessSector(sector);
    }

    /**
     * Get the authentication mode name for logging.
     */
    String getAuthMode();
}
