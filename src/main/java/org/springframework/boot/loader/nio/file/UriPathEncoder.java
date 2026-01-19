/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.nio.file;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class UriPathEncoder {
    private static char[] ALLOWED = "/:@-._~!$&'()*+,;=".toCharArray();

    private UriPathEncoder() {
    }

    static String encode(String path) {
        byte[] bytes;
        for (byte b : bytes = path.getBytes(StandardCharsets.UTF_8)) {
            if (UriPathEncoder.isAllowed(b)) continue;
            return UriPathEncoder.encode(bytes);
        }
        return path;
    }

    private static String encode(byte[] bytes) {
        ByteArrayOutputStream result = new ByteArrayOutputStream(bytes.length);
        for (byte b : bytes) {
            if (UriPathEncoder.isAllowed(b)) {
                result.write(b);
                continue;
            }
            result.write(37);
            result.write(Character.toUpperCase(Character.forDigit(b >> 4 & 0xF, 16)));
            result.write(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
        }
        return result.toString(StandardCharsets.UTF_8);
    }

    private static boolean isAllowed(int ch) {
        for (char allowed : ALLOWED) {
            if (ch != allowed) continue;
            return true;
        }
        return UriPathEncoder.isAlpha(ch) || UriPathEncoder.isDigit(ch);
    }

    private static boolean isAlpha(int ch) {
        return ch >= 97 && ch <= 122 || ch >= 65 && ch <= 90;
    }

    private static boolean isDigit(int ch) {
        return ch >= 48 && ch <= 57;
    }
}

