package com.jreinhal.mercenary.util;

public final class LogSanitizer {
    // L-08: Pattern to strip control characters that enable log injection/forging
    private static final java.util.regex.Pattern CONTROL_CHARS = java.util.regex.Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

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

    /**
     * L-08: Strip control characters from values before they enter log output.
     * Prevents log injection via newlines, carriage returns, and other control chars.
     */
    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return CONTROL_CHARS.matcher(value).replaceAll("")
                .replace("\r", "")
                .replace("\n", " ");
    }
}
