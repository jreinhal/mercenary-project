/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.zip;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.loader.log.DebugLogger;
import org.springframework.boot.loader.zip.DataBlock;

final class ZipString {
    private static final DebugLogger debug = DebugLogger.get(ZipString.class);
    static final int BUFFER_SIZE = 256;
    private static final int[] INITIAL_BYTE_BITMASK = new int[]{127, 31, 15, 7};
    private static final int SUBSEQUENT_BYTE_BITMASK = 63;
    private static final int EMPTY_HASH = "".hashCode();
    private static final int EMPTY_SLASH_HASH = "/".hashCode();

    private ZipString() {
    }

    static int hash(CharSequence charSequence, boolean addEndSlash) {
        return ZipString.hash(0, charSequence, addEndSlash);
    }

    static int hash(int initialHash, CharSequence charSequence, boolean addEndSlash) {
        if (charSequence == null || charSequence.isEmpty()) {
            return !addEndSlash ? EMPTY_HASH : EMPTY_SLASH_HASH;
        }
        boolean endsWithSlash = charSequence.charAt(charSequence.length() - 1) == '/';
        int hash = initialHash;
        if (charSequence instanceof String && initialHash == 0) {
            hash = charSequence.hashCode();
        } else {
            for (int i = 0; i < charSequence.length(); ++i) {
                char ch = charSequence.charAt(i);
                hash = 31 * hash + ch;
            }
        }
        hash = addEndSlash && !endsWithSlash ? 31 * hash + 47 : hash;
        debug.log("%s calculated for charsequence '%s' (addEndSlash=%s)", hash, charSequence, endsWithSlash);
        return hash;
    }

    static int hash(ByteBuffer buffer, DataBlock dataBlock, long pos, int len, boolean addEndSlash) throws IOException {
        if (len == 0) {
            return !addEndSlash ? EMPTY_HASH : EMPTY_SLASH_HASH;
        }
        buffer = buffer != null ? buffer : ByteBuffer.allocate(256);
        byte[] bytes = buffer.array();
        int hash = 0;
        int lastChar = 0;
        int codePointSize = 1;
        while (len > 0) {
            int count = ZipString.readInBuffer(dataBlock, pos, buffer, len, codePointSize);
            int byteIndex = 0;
            while (byteIndex < count && ZipString.hasEnoughBytes(byteIndex, codePointSize = ZipString.getCodePointSize(bytes, byteIndex), count)) {
                int codePoint = ZipString.getCodePoint(bytes, byteIndex, codePointSize);
                if (codePoint <= 65535) {
                    lastChar = (char)(codePoint & 0xFFFF);
                    hash = 31 * hash + lastChar;
                } else {
                    lastChar = 0;
                    hash = 31 * hash + Character.highSurrogate(codePoint);
                    hash = 31 * hash + Character.lowSurrogate(codePoint);
                }
                byteIndex += codePointSize;
                pos += (long)codePointSize;
                len -= codePointSize;
                codePointSize = 1;
            }
        }
        hash = addEndSlash && lastChar != 47 ? 31 * hash + 47 : hash;
        debug.log("%08X calculated for datablock position %s size %s (addEndSlash=%s)", hash, pos, len, addEndSlash);
        return hash;
    }

    static boolean matches(ByteBuffer buffer, DataBlock dataBlock, long pos, int len, CharSequence charSequence, boolean addSlash) {
        if (charSequence.isEmpty()) {
            return true;
        }
        buffer = buffer != null ? buffer : ByteBuffer.allocate(256);
        try {
            return ZipString.compare(buffer, dataBlock, pos, len, charSequence, !addSlash ? CompareType.MATCHES : CompareType.MATCHES_ADDING_SLASH) != -1;
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static int startsWith(ByteBuffer buffer, DataBlock dataBlock, long pos, int len, CharSequence charSequence) {
        if (charSequence.isEmpty()) {
            return 0;
        }
        buffer = buffer != null ? buffer : ByteBuffer.allocate(256);
        try {
            return ZipString.compare(buffer, dataBlock, pos, len, charSequence, CompareType.STARTS_WITH);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static int compare(ByteBuffer buffer, DataBlock dataBlock, long pos, int len, CharSequence charSequence, CompareType compareType) throws IOException {
        if (charSequence.isEmpty()) {
            return 0;
        }
        boolean addSlash = compareType == CompareType.MATCHES_ADDING_SLASH && !ZipString.endsWith(charSequence, '/');
        int charSequenceIndex = 0;
        int maxCharSequenceLength = !addSlash ? charSequence.length() : charSequence.length() + 1;
        int result = 0;
        byte[] bytes = buffer.array();
        int codePointSize = 1;
        while (len > 0) {
            int count = ZipString.readInBuffer(dataBlock, pos, buffer, len, codePointSize);
            int byteIndex = 0;
            while (byteIndex < count && ZipString.hasEnoughBytes(byteIndex, codePointSize = ZipString.getCodePointSize(bytes, byteIndex), count)) {
                int codePoint = ZipString.getCodePoint(bytes, byteIndex, codePointSize);
                if (codePoint <= 65535) {
                    ch = (char)(codePoint & 0xFFFF);
                    if (charSequenceIndex >= maxCharSequenceLength || ZipString.getChar(charSequence, charSequenceIndex++) != ch) {
                        return -1;
                    }
                } else {
                    ch = Character.highSurrogate(codePoint);
                    if (charSequenceIndex >= maxCharSequenceLength || ZipString.getChar(charSequence, charSequenceIndex++) != ch) {
                        return -1;
                    }
                    ch = Character.lowSurrogate(codePoint);
                    if (charSequenceIndex >= charSequence.length() || ZipString.getChar(charSequence, charSequenceIndex++) != ch) {
                        return -1;
                    }
                }
                byteIndex += codePointSize;
                pos += (long)codePointSize;
                len -= codePointSize;
                result += codePointSize;
                codePointSize = 1;
                if (compareType != CompareType.STARTS_WITH || charSequenceIndex < charSequence.length()) continue;
                return result;
            }
        }
        return charSequenceIndex >= charSequence.length() ? result : -1;
    }

    private static boolean hasEnoughBytes(int byteIndex, int codePointSize, int count) {
        return byteIndex + codePointSize - 1 < count;
    }

    private static boolean endsWith(CharSequence charSequence, char ch) {
        return !charSequence.isEmpty() && charSequence.charAt(charSequence.length() - 1) == ch;
    }

    private static char getChar(CharSequence charSequence, int index) {
        return index != charSequence.length() ? charSequence.charAt(index) : (char)'/';
    }

    static String readString(DataBlock data, long pos, long len) {
        try {
            if (len > Integer.MAX_VALUE) {
                throw new IllegalStateException("String is too long to read");
            }
            ByteBuffer buffer = ByteBuffer.allocate((int)len);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            data.readFully(buffer, pos);
            return new String(buffer.array(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static int readInBuffer(DataBlock dataBlock, long pos, ByteBuffer buffer, int maxLen, int minLen) throws IOException {
        buffer.clear();
        if (buffer.remaining() > maxLen) {
            buffer.limit(maxLen);
        }
        int result = 0;
        while (result < minLen) {
            int count = dataBlock.read(buffer, pos);
            if (count <= 0) {
                throw new EOFException();
            }
            result += count;
            pos += (long)count;
        }
        return result;
    }

    private static int getCodePointSize(byte[] bytes, int i) {
        int b = Byte.toUnsignedInt(bytes[i]);
        if ((b & 0x80) == 0) {
            return 1;
        }
        if ((b & 0xE0) == 192) {
            return 2;
        }
        if ((b & 0xF0) == 224) {
            return 3;
        }
        return 4;
    }

    private static int getCodePoint(byte[] bytes, int i, int codePointSize) {
        int codePoint = Byte.toUnsignedInt(bytes[i]);
        codePoint &= INITIAL_BYTE_BITMASK[codePointSize - 1];
        for (int j = 1; j < codePointSize; ++j) {
            codePoint = (codePoint << 6) + (bytes[i + j] & 0x3F);
        }
        return codePoint;
    }

    private static enum CompareType {
        MATCHES,
        MATCHES_ADDING_SLASH,
        STARTS_WITH;

    }
}

