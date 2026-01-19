/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.nio.file;

import java.io.IOException;
import java.net.URI;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.loader.nio.file.NestedFileSystemProvider;
import org.springframework.boot.loader.nio.file.NestedPath;

class NestedFileSystem
extends FileSystem {
    private static final Set<String> SUPPORTED_FILE_ATTRIBUTE_VIEWS = Set.of("basic");
    private static final String FILE_SYSTEMS_CLASS_NAME = FileSystems.class.getName();
    private static final Object EXISTING_FILE_SYSTEM = new Object();
    private final NestedFileSystemProvider provider;
    private final Path jarPath;
    private volatile boolean closed;
    private final Map<String, Object> zipFileSystems = new HashMap<String, Object>();

    NestedFileSystem(NestedFileSystemProvider provider, Path jarPath) {
        if (provider == null || jarPath == null) {
            throw new IllegalArgumentException("Provider and JarPath must not be null");
        }
        this.provider = provider;
        this.jarPath = jarPath;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    void installZipFileSystemIfNecessary(String nestedEntryName) {
        block8: {
            try {
                URI uri;
                boolean seen;
                Map<String, Object> map = this.zipFileSystems;
                synchronized (map) {
                    seen = this.zipFileSystems.putIfAbsent(nestedEntryName, EXISTING_FILE_SYSTEM) != null;
                }
                if (seen || this.hasFileSystem(uri = new URI("jar:nested:" + this.jarPath.toUri().getPath() + "/!" + nestedEntryName))) break block8;
                FileSystem zipFileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
                Map<String, Object> map2 = this.zipFileSystems;
                synchronized (map2) {
                    this.zipFileSystems.put(nestedEntryName, zipFileSystem);
                }
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
    }

    private boolean hasFileSystem(URI uri) {
        try {
            FileSystems.getFileSystem(uri);
            return true;
        }
        catch (FileSystemNotFoundException ex) {
            return this.isCreatingNewFileSystem();
        }
    }

    private boolean isCreatingNewFileSystem() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        if (stack != null) {
            for (StackTraceElement element : stack) {
                if (!FILE_SYSTEMS_CLASS_NAME.equals(element.getClassName())) continue;
                return "newFileSystem".equals(element.getMethodName());
            }
        }
        return false;
    }

    @Override
    public FileSystemProvider provider() {
        return this.provider;
    }

    Path getJarPath() {
        return this.jarPath;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    public void close() throws IOException {
        if (this.closed) {
            return;
        }
        this.closed = true;
        Map<String, Object> map = this.zipFileSystems;
        synchronized (map) {
            this.zipFileSystems.values().stream().filter(FileSystem.class::isInstance).map(FileSystem.class::cast).forEach(this::closeZipFileSystem);
        }
        this.provider.removeFileSystem(this);
    }

    private void closeZipFileSystem(FileSystem zipFileSystem) {
        try {
            zipFileSystem.close();
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    @Override
    public boolean isOpen() {
        return !this.closed;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getSeparator() {
        return "/!";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        this.assertNotClosed();
        return Collections.emptySet();
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        this.assertNotClosed();
        return Collections.emptySet();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        this.assertNotClosed();
        return SUPPORTED_FILE_ATTRIBUTE_VIEWS;
    }

    @Override
    public Path getPath(String first, String ... more) {
        this.assertNotClosed();
        if (more.length != 0) {
            throw new IllegalArgumentException("Nested paths must contain a single element");
        }
        return new NestedPath(this, first);
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        throw new UnsupportedOperationException("Nested paths do not support path matchers");
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("Nested paths do not have a user principal lookup service");
    }

    @Override
    public WatchService newWatchService() throws IOException {
        throw new UnsupportedOperationException("Nested paths do not support the WatchService");
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || this.getClass() != obj.getClass()) {
            return false;
        }
        NestedFileSystem other = (NestedFileSystem)obj;
        return this.jarPath.equals(other.jarPath);
    }

    public int hashCode() {
        return this.jarPath.hashCode();
    }

    public String toString() {
        return this.jarPath.toAbsolutePath().toString();
    }

    private void assertNotClosed() {
        if (this.closed) {
            throw new ClosedFileSystemException();
        }
    }
}

