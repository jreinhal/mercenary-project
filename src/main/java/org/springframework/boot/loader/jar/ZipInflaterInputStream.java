/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.jar;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

abstract class ZipInflaterInputStream
extends InflaterInputStream {
    private int available;
    private boolean extraBytesWritten;

    ZipInflaterInputStream(InputStream inputStream, Inflater inflater, int size) {
        super(inputStream, inflater, ZipInflaterInputStream.getInflaterBufferSize(size));
        this.available = size;
    }

    private static int getInflaterBufferSize(long size) {
        size = (size += 2L) > 65536L ? 8192L : size;
        size = size <= 0L ? 4096L : size;
        return (int)size;
    }

    @Override
    public int available() throws IOException {
        return this.available >= 0 ? this.available : super.available();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int result = super.read(b, off, len);
        if (result != -1) {
            this.available -= result;
        }
        return result;
    }

    @Override
    protected void fill() throws IOException {
        try {
            super.fill();
        }
        catch (EOFException ex) {
            if (this.extraBytesWritten) {
                throw ex;
            }
            this.len = 1;
            this.buf[0] = 0;
            this.extraBytesWritten = true;
            this.inf.setInput(this.buf, 0, this.len);
        }
    }
}

