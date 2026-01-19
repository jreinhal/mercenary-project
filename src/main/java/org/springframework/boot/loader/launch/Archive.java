/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.launch;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.Manifest;
import org.springframework.boot.loader.launch.ExplodedArchive;
import org.springframework.boot.loader.launch.JarFileArchive;

public interface Archive
extends AutoCloseable {
    public static final Predicate<Entry> ALL_ENTRIES = entry -> true;

    public Manifest getManifest() throws IOException;

    default public Set<URL> getClassPathUrls(Predicate<Entry> includeFilter) throws IOException {
        return this.getClassPathUrls(includeFilter, ALL_ENTRIES);
    }

    public Set<URL> getClassPathUrls(Predicate<Entry> var1, Predicate<Entry> var2) throws IOException;

    default public boolean isExploded() {
        return this.getRootDirectory() != null;
    }

    default public File getRootDirectory() {
        return null;
    }

    @Override
    default public void close() throws Exception {
    }

    public static Archive create(Class<?> target) throws Exception {
        return Archive.create(target.getProtectionDomain());
    }

    public static Archive create(ProtectionDomain protectionDomain) throws Exception {
        URI location;
        CodeSource codeSource = protectionDomain.getCodeSource();
        URI uRI = location = codeSource != null ? codeSource.getLocation().toURI() : null;
        if (location == null) {
            throw new IllegalStateException("Unable to determine code source archive");
        }
        return Archive.create(Path.of(location).toFile());
    }

    public static Archive create(File target) throws Exception {
        if (!target.exists()) {
            throw new IllegalStateException("Unable to determine code source archive from " + target);
        }
        return target.isDirectory() ? new ExplodedArchive(target) : new JarFileArchive(target);
    }

    public static interface Entry {
        public String name();

        public boolean isDirectory();
    }
}

