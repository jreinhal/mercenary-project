/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.launch;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import org.springframework.boot.loader.launch.Archive;
import org.springframework.boot.loader.net.protocol.jar.JarUrl;

class JarFileArchive
implements Archive {
    private static final String UNPACK_MARKER = "UNPACK:";
    private static final FileAttribute<?>[] NO_FILE_ATTRIBUTES = new FileAttribute[0];
    private static final FileAttribute<?>[] DIRECTORY_PERMISSION_ATTRIBUTES = JarFileArchive.asFileAttributes(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final FileAttribute<?>[] FILE_PERMISSION_ATTRIBUTES = JarFileArchive.asFileAttributes(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Path TEMP = Paths.get(System.getProperty("java.io.tmpdir"), new String[0]);
    private final File file;
    private final JarFile jarFile;
    private volatile Path tempUnpackDirectory;

    JarFileArchive(File file) throws IOException {
        this(file, new JarFile(file));
    }

    private JarFileArchive(File file, JarFile jarFile) {
        this.file = file;
        this.jarFile = jarFile;
    }

    @Override
    public Manifest getManifest() throws IOException {
        return this.jarFile.getManifest();
    }

    @Override
    public Set<URL> getClassPathUrls(Predicate<Archive.Entry> includeFilter, Predicate<Archive.Entry> directorySearchFilter) throws IOException {
        return this.jarFile.stream().map(JarArchiveEntry::new).filter(includeFilter).map(this::getNestedJarUrl).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private URL getNestedJarUrl(JarArchiveEntry archiveEntry) {
        try {
            JarEntry jarEntry = archiveEntry.jarEntry();
            String comment = jarEntry.getComment();
            if (comment != null && comment.startsWith(UNPACK_MARKER)) {
                return this.getUnpackedNestedJarUrl(jarEntry);
            }
            return JarUrl.create(this.file, jarEntry);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private URL getUnpackedNestedJarUrl(JarEntry jarEntry) throws IOException {
        Path path;
        String name = jarEntry.getName();
        if (name.lastIndexOf(47) != -1) {
            name = name.substring(name.lastIndexOf(47) + 1);
        }
        if (!Files.exists(path = this.getTempUnpackDirectory().resolve(name), new LinkOption[0]) || Files.size(path) != jarEntry.getSize()) {
            this.unpack(jarEntry, path);
        }
        return path.toUri().toURL();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private Path getTempUnpackDirectory() {
        Path tempUnpackDirectory = this.tempUnpackDirectory;
        if (tempUnpackDirectory != null) {
            return tempUnpackDirectory;
        }
        Path path = TEMP;
        synchronized (path) {
            tempUnpackDirectory = this.tempUnpackDirectory;
            if (tempUnpackDirectory == null) {
                this.tempUnpackDirectory = tempUnpackDirectory = this.createUnpackDirectory(TEMP);
            }
        }
        return tempUnpackDirectory;
    }

    private Path createUnpackDirectory(Path parent) {
        int attempts = 0;
        String fileName = Paths.get(this.jarFile.getName(), new String[0]).getFileName().toString();
        while (attempts++ < 100) {
            Path unpackDirectory = parent.resolve(fileName + "-spring-boot-libs-" + UUID.randomUUID());
            try {
                this.createDirectory(unpackDirectory);
                return unpackDirectory;
            }
            catch (IOException iOException) {
            }
        }
        throw new IllegalStateException("Failed to create unpack directory in directory '" + parent + "'");
    }

    private void createDirectory(Path path) throws IOException {
        Files.createDirectory(path, this.getFileAttributes(path, DIRECTORY_PERMISSION_ATTRIBUTES));
    }

    private void unpack(JarEntry entry, Path path) throws IOException {
        this.createFile(path);
        path.toFile().deleteOnExit();
        try (InputStream in = this.jarFile.getInputStream(entry);){
            Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void createFile(Path path) throws IOException {
        Files.createFile(path, this.getFileAttributes(path, FILE_PERMISSION_ATTRIBUTES));
    }

    private FileAttribute<?>[] getFileAttributes(Path path, FileAttribute<?>[] permissionAttributes) {
        return !this.supportsPosix(path.getFileSystem()) ? NO_FILE_ATTRIBUTES : permissionAttributes;
    }

    private boolean supportsPosix(FileSystem fileSystem) {
        return fileSystem.supportedFileAttributeViews().contains("posix");
    }

    @Override
    public void close() throws IOException {
        this.jarFile.close();
    }

    public String toString() {
        return this.file.toString();
    }

    private static FileAttribute<?>[] asFileAttributes(PosixFilePermission ... permissions) {
        return new FileAttribute[]{PosixFilePermissions.asFileAttribute(Set.of(permissions))};
    }

    private record JarArchiveEntry(JarEntry jarEntry) implements Archive.Entry
    {
        @Override
        public String name() {
            return this.jarEntry.getName();
        }

        @Override
        public boolean isDirectory() {
            return this.jarEntry.isDirectory();
        }
    }
}

