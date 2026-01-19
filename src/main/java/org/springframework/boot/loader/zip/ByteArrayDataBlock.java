/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.zip;

import java.io.IOException;
import java.nio.ByteBuffer;
import org.springframework.boot.loader.zip.CloseableDataBlock;

class ByteArrayDataBlock
implements CloseableDataBlock {
    private final byte[] bytes;
    private final int maxReadSize;

    ByteArrayDataBlock(byte ... bytes) {
        this(bytes, -1);
    }

    ByteArrayDataBlock(byte[] bytes, int maxReadSize) {
        this.bytes = bytes;
        this.maxReadSize = maxReadSize;
    }

    @Override
    public long size() throws IOException {
        return this.bytes.length;
    }

    @Override
    public int read(ByteBuffer dst, long pos) throws IOException {
        return this.read(dst, (int)pos);
    }

    private int read(ByteBuffer dst, int pos) {
        int remaining = dst.remaining();
        int length = Math.min(this.bytes.length - pos, remaining);
        if (this.maxReadSize > 0 && length > this.maxReadSize) {
            length = this.maxReadSize;
        }
        dst.put(this.bytes, pos, length);
        return length;
    }

    @Override
    public void close() throws IOException {
    }
}

