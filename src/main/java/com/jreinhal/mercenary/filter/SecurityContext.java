package com.jreinhal.mercenary.filter;

import com.jreinhal.mercenary.model.User;

/**
 * Thread-local security context for the current request.
 * 
 * Provides access to the authenticated user throughout the request lifecycle.
 */
public class SecurityContext {

    private static final ThreadLocal<User> currentUser = new ThreadLocal<>();

    /**
     * Set the current user for this request thread.
     */
    public static void setCurrentUser(User user) {
        currentUser.set(user);
    }

    /**
     * Get the current authenticated user.
     * 
     * @return The user, or null if not authenticated
     */
    public static User getCurrentUser() {
        return currentUser.get();
    }

    /**
     * Check if a user is currently authenticated.
     */
    public static boolean isAuthenticated() {
        return currentUser.get() != null;
    }

    /**
     * Get the current user's ID for logging purposes.
     */
    public static String getCurrentUserId() {
        User user = currentUser.get();
        return user != null ? user.getId() : "ANONYMOUS";
    }

    /**
     * Get the current user's display name.
     */
    public static String getCurrentUserDisplayName() {
        User user = currentUser.get();
        return user != null ? user.getDisplayName() : "Anonymous";
    }

    /**
     * Clear the current user context (called at end of request).
     */
    public static void clear() {
        currentUser.remove();
    }
}
