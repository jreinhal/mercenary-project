/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.net.protocol.nested;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.boot.loader.net.protocol.nested.NestedLocation;
import org.springframework.boot.loader.zip.CloseableDataBlock;
import org.springframework.boot.loader.zip.ZipContent;

class NestedUrlConnectionResources
implements Runnable {
    private final NestedLocation location;
    private volatile ZipContent zipContent;
    private volatile long size = -1L;
    private volatile InputStream inputStream;

    NestedUrlConnectionResources(NestedLocation location) {
        this.location = location;
    }

    NestedLocation getLocation() {
        return this.location;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    void connect() throws IOException {
        NestedUrlConnectionResources nestedUrlConnectionResources = this;
        synchronized (nestedUrlConnectionResources) {
            if (this.zipContent == null) {
                this.zipContent = ZipContent.open(this.location.path(), this.location.nestedEntryName());
                try {
                    this.connectData();
                }
                catch (IOException | RuntimeException ex) {
                    this.zipContent.close();
                    this.zipContent = null;
                    throw ex;
                }
            }
        }
    }

    private void connectData() throws IOException {
        CloseableDataBlock data = this.zipContent.openRawZipData();
        try {
            this.size = data.size();
            this.inputStream = data.asInputStream();
        }
        catch (IOException | RuntimeException ex) {
            data.close();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    InputStream getInputStream() throws IOException {
        NestedUrlConnectionResources nestedUrlConnectionResources = this;
        synchronized (nestedUrlConnectionResources) {
            if (this.inputStream == null) {
                throw new IOException("Nested location not found " + this.location);
            }
            return this.inputStream;
        }
    }

    long getContentLength() {
        return this.size;
    }

    @Override
    public void run() {
        this.releaseAll();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void releaseAll() {
        NestedUrlConnectionResources nestedUrlConnectionResources = this;
        synchronized (nestedUrlConnectionResources) {
            if (this.zipContent != null) {
                IOException exceptionChain = null;
                try {
                    this.inputStream.close();
                }
                catch (IOException ex) {
                    exceptionChain = this.addToExceptionChain(exceptionChain, ex);
                }
                try {
                    this.zipContent.close();
                }
                catch (IOException ex) {
                    exceptionChain = this.addToExceptionChain(exceptionChain, ex);
                }
                this.size = -1L;
                if (exceptionChain != null) {
                    throw new UncheckedIOException(exceptionChain);
                }
            }
        }
    }

    private IOException addToExceptionChain(IOException exceptionChain, IOException ex) {
        if (exceptionChain != null) {
            exceptionChain.addSuppressed(ex);
            return exceptionChain;
        }
        return ex;
    }
}

