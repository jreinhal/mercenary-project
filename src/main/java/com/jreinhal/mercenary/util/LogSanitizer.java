package com.jreinhal.mercenary.util;

public final class LogSanitizer {
    private LogSanitizer() {
    }

    public static String querySummary(String query) {
        if (query == null) {
            return "[len=0,id=none]";
        }
        int len = query.length();
        String id = Integer.toHexString(query.hashCode());
        return "[len=" + len + ",id=" + id + "]";
    }
}
