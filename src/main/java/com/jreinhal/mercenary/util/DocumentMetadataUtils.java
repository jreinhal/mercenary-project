package com.jreinhal.mercenary.util;

import java.util.List;
import java.util.Map;

public final class DocumentMetadataUtils {
    private DocumentMetadataUtils() {
    }

    public static boolean apiKeyMatch(String targetName, Map<String, Object> meta) {
        if (targetName == null || meta == null) {
            return false;
        }
        String targetLower = targetName.toLowerCase();
        for (String key : List.of("source", "filename", "file_name", "original_filename", "name")) {
            Object value = meta.get(key);
            if (!(value instanceof String)) {
                continue;
            }
            String strValue = ((String) value).toLowerCase();
            if (targetLower.equals(strValue)) {
                return true;
            }
            if (strValue.endsWith("/" + targetLower) || strValue.endsWith("\\" + targetLower)) {
                return true;
            }
            if (strValue.contains(targetLower)) {
                return true;
            }
        }
        return false;
    }
}
