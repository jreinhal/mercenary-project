/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.launch;

import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

final class SystemPropertyUtils {
    private static final String PLACEHOLDER_PREFIX = "${";
    private static final String PLACEHOLDER_SUFFIX = "}";
    private static final String VALUE_SEPARATOR = ":";
    private static final String SIMPLE_PREFIX = "${".substring(1);

    private SystemPropertyUtils() {
    }

    static String resolvePlaceholders(Properties properties, String text) {
        return text != null ? SystemPropertyUtils.parseStringValue(properties, text, text, new HashSet<String>()) : null;
    }

    private static String parseStringValue(Properties properties, String value, String current, Set<String> visitedPlaceholders) {
        StringBuilder result = new StringBuilder(current);
        int startIndex = current.indexOf(PLACEHOLDER_PREFIX);
        while (startIndex != -1) {
            int separatorIndex;
            int endIndex = SystemPropertyUtils.findPlaceholderEndIndex(result, startIndex);
            if (endIndex == -1) {
                startIndex = -1;
                continue;
            }
            String placeholder = result.substring(startIndex + PLACEHOLDER_PREFIX.length(), endIndex);
            String originalPlaceholder = placeholder;
            if (!visitedPlaceholders.add(originalPlaceholder)) {
                throw new IllegalArgumentException("Circular placeholder reference '" + originalPlaceholder + "' in property definitions");
            }
            String propertyValue = SystemPropertyUtils.resolvePlaceholder(properties, value, placeholder = SystemPropertyUtils.parseStringValue(properties, value, placeholder, visitedPlaceholders));
            if (propertyValue == null && (separatorIndex = placeholder.indexOf(VALUE_SEPARATOR)) != -1) {
                String actualPlaceholder = placeholder.substring(0, separatorIndex);
                String defaultValue = placeholder.substring(separatorIndex + VALUE_SEPARATOR.length());
                propertyValue = SystemPropertyUtils.resolvePlaceholder(properties, value, actualPlaceholder);
                String string = propertyValue = propertyValue != null ? propertyValue : defaultValue;
            }
            if (propertyValue != null) {
                propertyValue = SystemPropertyUtils.parseStringValue(properties, value, propertyValue, visitedPlaceholders);
                result.replace(startIndex, endIndex + PLACEHOLDER_SUFFIX.length(), propertyValue);
                startIndex = result.indexOf(PLACEHOLDER_PREFIX, startIndex + propertyValue.length());
            } else {
                startIndex = result.indexOf(PLACEHOLDER_PREFIX, endIndex + PLACEHOLDER_SUFFIX.length());
            }
            visitedPlaceholders.remove(originalPlaceholder);
        }
        return result.toString();
    }

    private static String resolvePlaceholder(Properties properties, String text, String placeholderName) {
        String propertyValue = SystemPropertyUtils.getProperty(placeholderName, null, text);
        if (propertyValue != null) {
            return propertyValue;
        }
        return properties != null ? properties.getProperty(placeholderName) : null;
    }

    static String getProperty(String key) {
        return SystemPropertyUtils.getProperty(key, null, "");
    }

    private static String getProperty(String key, String defaultValue, String text) {
        try {
            String value = System.getProperty(key);
            value = value != null ? value : System.getenv(key);
            value = value != null ? value : System.getenv(key.replace('.', '_'));
            value = value != null ? value : System.getenv(key.toUpperCase(Locale.ENGLISH).replace('.', '_'));
            return value != null ? value : defaultValue;
        }
        catch (Throwable ex) {
            System.err.println("Could not resolve key '" + key + "' in '" + text + "' as system property or in environment: " + ex);
            return defaultValue;
        }
    }

    private static int findPlaceholderEndIndex(CharSequence buf, int startIndex) {
        int index = startIndex + PLACEHOLDER_PREFIX.length();
        int withinNestedPlaceholder = 0;
        while (index < buf.length()) {
            if (SystemPropertyUtils.substringMatch(buf, index, PLACEHOLDER_SUFFIX)) {
                if (withinNestedPlaceholder > 0) {
                    --withinNestedPlaceholder;
                    index += PLACEHOLDER_SUFFIX.length();
                    continue;
                }
                return index;
            }
            if (SystemPropertyUtils.substringMatch(buf, index, SIMPLE_PREFIX)) {
                ++withinNestedPlaceholder;
                index += SIMPLE_PREFIX.length();
                continue;
            }
            ++index;
        }
        return -1;
    }

    private static boolean substringMatch(CharSequence str, int index, CharSequence substring) {
        for (int j = 0; j < substring.length(); ++j) {
            int i = index + j;
            if (i < str.length() && str.charAt(i) == substring.charAt(j)) continue;
            return false;
        }
        return true;
    }
}

