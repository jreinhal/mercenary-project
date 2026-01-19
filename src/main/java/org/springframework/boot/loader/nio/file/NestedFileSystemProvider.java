/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.nio.file;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.loader.net.protocol.nested.NestedLocation;
import org.springframework.boot.loader.nio.file.NestedByteChannel;
import org.springframework.boot.loader.nio.file.NestedFileStore;
import org.springframework.boot.loader.nio.file.NestedFileSystem;
import org.springframework.boot.loader.nio.file.NestedPath;

public class NestedFileSystemProvider
extends FileSystemProvider {
    private Map<Path, NestedFileSystem> fileSystems = new HashMap<Path, NestedFileSystem>();

    @Override
    public String getScheme() {
        return "nested";
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        NestedLocation location = NestedLocation.fromUri(uri);
        Path jarPath = location.path();
        Map<Path, NestedFileSystem> map = this.fileSystems;
        synchronized (map) {
            if (this.fileSystems.containsKey(jarPath)) {
                throw new FileSystemAlreadyExistsException();
            }
            NestedFileSystem fileSystem = new NestedFileSystem(this, location.path());
            this.fileSystems.put(location.path(), fileSystem);
            return fileSystem;
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    public FileSystem getFileSystem(URI uri) {
        NestedLocation location = NestedLocation.fromUri(uri);
        Map<Path, NestedFileSystem> map = this.fileSystems;
        synchronized (map) {
            NestedFileSystem fileSystem = this.fileSystems.get(location.path());
            if (fileSystem == null) {
                throw new FileSystemNotFoundException();
            }
            return fileSystem;
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    public Path getPath(URI uri) {
        NestedLocation location = NestedLocation.fromUri(uri);
        Map<Path, NestedFileSystem> map = this.fileSystems;
        synchronized (map) {
            NestedFileSystem fileSystem = this.fileSystems.computeIfAbsent(location.path(), path -> new NestedFileSystem(this, (Path)path));
            fileSystem.installZipFileSystemIfNecessary(location.nestedEntryName());
            return fileSystem.getPath(location.nestedEntryName(), new String[0]);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    void removeFileSystem(NestedFileSystem fileSystem) {
        Map<Path, NestedFileSystem> map = this.fileSystems;
        synchronized (map) {
            this.fileSystems.remove(fileSystem.getJarPath());
        }
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?> ... attrs) throws IOException {
        NestedPath nestedPath = NestedPath.cast(path);
        return new NestedByteChannel(nestedPath.getJarPath(), nestedPath.getNestedEntryName());
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        throw new NotDirectoryException(NestedPath.cast(dir).toString());
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?> ... attrs) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void delete(Path path) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void copy(Path source, Path target, CopyOption ... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void move(Path source, Path target, CopyOption ... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
        return path.equals(path2);
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
        return false;
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        NestedPath nestedPath = NestedPath.cast(path);
        nestedPath.assertExists();
        return new NestedFileStore(nestedPath.getFileSystem());
    }

    @Override
    public void checkAccess(Path path, AccessMode ... modes) throws IOException {
        Path jarPath = this.getJarPath(path);
        jarPath.getFileSystem().provider().checkAccess(jarPath, modes);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption ... options) {
        Path jarPath = this.getJarPath(path);
        return jarPath.getFileSystem().provider().getFileAttributeView(jarPath, type, options);
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption ... options) throws IOException {
        Path jarPath = this.getJarPath(path);
        return jarPath.getFileSystem().provider().readAttributes(jarPath, type, options);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption ... options) throws IOException {
        Path jarPath = this.getJarPath(path);
        return jarPath.getFileSystem().provider().readAttributes(jarPath, attributes, options);
    }

    protected Path getJarPath(Path path) {
        return NestedPath.cast(path).getJarPath();
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption ... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }
}

