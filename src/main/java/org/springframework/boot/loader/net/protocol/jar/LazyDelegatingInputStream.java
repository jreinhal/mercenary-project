/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.net.protocol.jar;

import java.io.IOException;
import java.io.InputStream;

abstract class LazyDelegatingInputStream
extends InputStream {
    private volatile InputStream in;

    LazyDelegatingInputStream() {
    }

    @Override
    public int read() throws IOException {
        return this.in().read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        return this.in().read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return this.in().read(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException {
        return this.in().skip(n);
    }

    @Override
    public int available() throws IOException {
        return this.in().available();
    }

    @Override
    public boolean markSupported() {
        try {
            return this.in().markSupported();
        }
        catch (IOException ex) {
            return false;
        }
    }

    @Override
    public synchronized void mark(int readlimit) {
        try {
            this.in().mark(readlimit);
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    @Override
    public synchronized void reset() throws IOException {
        this.in().reset();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private InputStream in() throws IOException {
        InputStream in = this.in;
        if (in == null) {
            LazyDelegatingInputStream lazyDelegatingInputStream = this;
            synchronized (lazyDelegatingInputStream) {
                in = this.in;
                if (in == null) {
                    this.in = in = this.getDelegateInputStream();
                }
            }
        }
        return in;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    public void close() throws IOException {
        InputStream in = this.in;
        if (in != null) {
            LazyDelegatingInputStream lazyDelegatingInputStream = this;
            synchronized (lazyDelegatingInputStream) {
                in = this.in;
                if (in != null) {
                    in.close();
                }
            }
        }
    }

    protected abstract InputStream getDelegateInputStream() throws IOException;
}

