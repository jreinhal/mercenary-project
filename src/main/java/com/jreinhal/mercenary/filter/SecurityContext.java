/*
 * Decompiled with CFR 0.152.
 */
package com.jreinhal.mercenary.filter;

import com.jreinhal.mercenary.model.User;

public class SecurityContext {
    private static final ThreadLocal<User> currentUser = new ThreadLocal();

    public static void setCurrentUser(User user) {
        currentUser.set(user);
    }

    public static User getCurrentUser() {
        return currentUser.get();
    }

    public static boolean isAuthenticated() {
        return currentUser.get() != null;
    }

    public static String getCurrentUserId() {
        User user = currentUser.get();
        return user != null ? user.getId() : "ANONYMOUS";
    }

    public static String getCurrentUserDisplayName() {
        User user = currentUser.get();
        return user != null ? user.getDisplayName() : "Anonymous";
    }

    public static void clear() {
        currentUser.remove();
    }
}
