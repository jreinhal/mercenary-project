/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.net.util;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class UrlDecoder {
    private UrlDecoder() {
    }

    public static String decode(String string) {
        int length = string.length();
        if (length == 0 || string.indexOf(37) < 0) {
            return string;
        }
        StringBuilder result = new StringBuilder(length);
        ByteBuffer byteBuffer = ByteBuffer.allocate(length);
        CharBuffer charBuffer = CharBuffer.allocate(length);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        int index = 0;
        while (index < length) {
            char ch = string.charAt(index);
            if (ch != '%') {
                result.append(ch);
                if (index + 1 >= length) {
                    return result.toString();
                }
                ++index;
                continue;
            }
            index = UrlDecoder.fillByteBuffer(byteBuffer, string, index, length);
            UrlDecoder.decodeToCharBuffer(byteBuffer, charBuffer, decoder);
            result.append(charBuffer.flip());
        }
        return result.toString();
    }

    private static int fillByteBuffer(ByteBuffer byteBuffer, String string, int index, int length) {
        byteBuffer.clear();
        do {
            byteBuffer.put(UrlDecoder.unescape(string, index));
        } while ((index += 3) < length && string.charAt(index) == '%');
        byteBuffer.flip();
        return index;
    }

    private static byte unescape(String string, int index) {
        try {
            return (byte)Integer.parseInt(string, index + 1, index + 3, 16);
        }
        catch (NumberFormatException ex) {
            throw new IllegalArgumentException();
        }
    }

    private static void decodeToCharBuffer(ByteBuffer byteBuffer, CharBuffer charBuffer, CharsetDecoder decoder) {
        decoder.reset();
        charBuffer.clear();
        UrlDecoder.assertNoError(decoder.decode(byteBuffer, charBuffer, true));
        UrlDecoder.assertNoError(decoder.flush(charBuffer));
    }

    private static void assertNoError(CoderResult result) {
        if (result.isError()) {
            throw new IllegalArgumentException("Error decoding percent encoded characters");
        }
    }
}

