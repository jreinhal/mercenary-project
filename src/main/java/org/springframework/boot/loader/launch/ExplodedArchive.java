/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.launch;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.Manifest;
import org.springframework.boot.loader.launch.Archive;

class ExplodedArchive
implements Archive {
    private static final Object NO_MANIFEST = new Object();
    private static final Set<String> SKIPPED_NAMES = Set.of(".", "..");
    private static final Comparator<File> entryComparator = Comparator.comparing(File::getAbsolutePath);
    private final File rootDirectory;
    private final String rootUriPath;
    private volatile Object manifest;

    ExplodedArchive(File rootDirectory) {
        if (!rootDirectory.exists() || !rootDirectory.isDirectory()) {
            throw new IllegalArgumentException("Invalid source directory " + rootDirectory);
        }
        this.rootDirectory = rootDirectory;
        this.rootUriPath = this.rootDirectory.toURI().getPath();
    }

    @Override
    public Manifest getManifest() throws IOException {
        Object manifest = this.manifest;
        if (manifest == null) {
            this.manifest = manifest = this.loadManifest();
        }
        return manifest != NO_MANIFEST ? (Manifest)manifest : null;
    }

    private Object loadManifest() throws IOException {
        File file = new File(this.rootDirectory, "META-INF/MANIFEST.MF");
        if (!file.exists()) {
            return NO_MANIFEST;
        }
        try (FileInputStream inputStream = new FileInputStream(file);){
            Manifest manifest = new Manifest(inputStream);
            return manifest;
        }
    }

    @Override
    public Set<URL> getClassPathUrls(Predicate<Archive.Entry> includeFilter, Predicate<Archive.Entry> directorySearchFilter) throws IOException {
        LinkedHashSet<URL> urls = new LinkedHashSet<URL>();
        LinkedList<File> files = new LinkedList<File>(this.listFiles(this.rootDirectory));
        while (!files.isEmpty()) {
            File file = files.poll();
            if (SKIPPED_NAMES.contains(file.getName())) continue;
            String entryName = file.toURI().getPath().substring(this.rootUriPath.length());
            FileArchiveEntry entry = new FileArchiveEntry(entryName, file);
            if (entry.isDirectory() && directorySearchFilter.test(entry)) {
                files.addAll(0, this.listFiles(file));
            }
            if (!includeFilter.test(entry)) continue;
            urls.add(file.toURI().toURL());
        }
        return urls;
    }

    private List<File> listFiles(File file) {
        File[] files = file.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        Arrays.sort(files, entryComparator);
        return Arrays.asList(files);
    }

    @Override
    public File getRootDirectory() {
        return this.rootDirectory;
    }

    public String toString() {
        return this.rootDirectory.toString();
    }

    private record FileArchiveEntry(String name, File file) implements Archive.Entry
    {
        @Override
        public boolean isDirectory() {
            return this.file.isDirectory();
        }
    }
}

