/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.zip;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.springframework.boot.loader.zip.DataBlockInputStream;

public interface DataBlock {
    public long size() throws IOException;

    public int read(ByteBuffer var1, long var2) throws IOException;

    default public void readFully(ByteBuffer dst, long pos) throws IOException {
        do {
            int count;
            if ((count = this.read(dst, pos)) <= 0) {
                throw new EOFException();
            }
            pos += (long)count;
        } while (dst.hasRemaining());
    }

    default public InputStream asInputStream() throws IOException {
        return new DataBlockInputStream(this);
    }
}

