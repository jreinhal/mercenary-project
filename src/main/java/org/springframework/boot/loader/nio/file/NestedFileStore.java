/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.nio.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import org.springframework.boot.loader.nio.file.NestedFileSystem;

class NestedFileStore
extends FileStore {
    private final NestedFileSystem fileSystem;

    NestedFileStore(NestedFileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    @Override
    public String name() {
        return this.fileSystem.toString();
    }

    @Override
    public String type() {
        return "nestedfs";
    }

    @Override
    public boolean isReadOnly() {
        return this.fileSystem.isReadOnly();
    }

    @Override
    public long getTotalSpace() throws IOException {
        return 0L;
    }

    @Override
    public long getUsableSpace() throws IOException {
        return 0L;
    }

    @Override
    public long getUnallocatedSpace() throws IOException {
        return 0L;
    }

    @Override
    public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
        return this.getJarPathFileStore().supportsFileAttributeView(type);
    }

    @Override
    public boolean supportsFileAttributeView(String name) {
        return this.getJarPathFileStore().supportsFileAttributeView(name);
    }

    @Override
    public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
        return this.getJarPathFileStore().getFileStoreAttributeView(type);
    }

    @Override
    public Object getAttribute(String attribute) throws IOException {
        try {
            return this.getJarPathFileStore().getAttribute(attribute);
        }
        catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    protected FileStore getJarPathFileStore() {
        try {
            return Files.getFileStore(this.fileSystem.getJarPath());
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}

