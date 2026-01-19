/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.net.protocol.jar;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import org.springframework.boot.loader.jar.NestedJarFile;
import org.springframework.boot.loader.net.protocol.jar.Handler;
import org.springframework.boot.loader.net.protocol.jar.Optimizations;

public abstract class JarUrlClassLoader
extends URLClassLoader {
    private final URL[] urls;
    private final boolean hasJarUrls;
    private final Map<URL, JarFile> jarFiles = new ConcurrentHashMap<URL, JarFile>();
    private final Set<String> undefinablePackages = ConcurrentHashMap.newKeySet();

    public JarUrlClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
        this.urls = urls;
        this.hasJarUrls = Arrays.stream(urls).anyMatch(this::isJarUrl);
    }

    @Override
    public URL findResource(String name) {
        if (!this.hasJarUrls) {
            return super.findResource(name);
        }
        Optimizations.enable(false);
        try {
            URL uRL = super.findResource(name);
            return uRL;
        }
        finally {
            Optimizations.disable();
        }
    }

    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        if (!this.hasJarUrls) {
            return super.findResources(name);
        }
        Optimizations.enable(false);
        try {
            OptimizedEnumeration optimizedEnumeration = new OptimizedEnumeration(super.findResources(name));
            return optimizedEnumeration;
        }
        finally {
            Optimizations.disable();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!this.hasJarUrls) {
            return super.loadClass(name, resolve);
        }
        Optimizations.enable(true);
        try {
            try {
                this.definePackageIfNecessary(name);
            }
            catch (IllegalArgumentException ex) {
                this.tolerateRaceConditionDueToBeingParallelCapable(ex, name);
            }
            Class<?> clazz = super.loadClass(name, resolve);
            return clazz;
        }
        finally {
            Optimizations.disable();
        }
    }

    protected final void definePackageIfNecessary(String className) {
        String packageName;
        if (className.startsWith("java.")) {
            return;
        }
        int lastDot = className.lastIndexOf(46);
        if (lastDot >= 0 && this.getDefinedPackage(packageName = className.substring(0, lastDot)) == null) {
            try {
                this.definePackage(className, packageName);
            }
            catch (IllegalArgumentException ex) {
                this.tolerateRaceConditionDueToBeingParallelCapable(ex, packageName);
            }
        }
    }

    private void definePackage(String className, String packageName) {
        if (this.undefinablePackages.contains(packageName)) {
            return;
        }
        String packageEntryName = packageName.replace('.', '/') + "/";
        String classEntryName = className.replace('.', '/') + ".class";
        for (URL url : this.urls) {
            try {
                JarFile jarFile = this.getJarFile(url);
                if (jarFile == null || !this.hasEntry(jarFile, classEntryName) || !this.hasEntry(jarFile, packageEntryName) || jarFile.getManifest() == null) continue;
                this.definePackage(packageName, jarFile.getManifest(), url);
                return;
            }
            catch (IOException iOException) {
                // empty catch block
            }
        }
        this.undefinablePackages.add(packageName);
    }

    private void tolerateRaceConditionDueToBeingParallelCapable(IllegalArgumentException ex, String packageName) throws AssertionError {
        if (this.getDefinedPackage(packageName) == null) {
            throw new AssertionError("Package %s has already been defined but it could not be found".formatted(packageName), ex);
        }
    }

    private boolean hasEntry(JarFile jarFile, String name) {
        boolean bl;
        if (jarFile instanceof NestedJarFile) {
            NestedJarFile nestedJarFile = (NestedJarFile)jarFile;
            bl = nestedJarFile.hasEntry(name);
        } else {
            bl = jarFile.getEntry(name) != null;
        }
        return bl;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private JarFile getJarFile(URL url) throws IOException {
        JarFile jarFile = this.jarFiles.get(url);
        if (jarFile != null) {
            return jarFile;
        }
        URLConnection connection = url.openConnection();
        if (!(connection instanceof JarURLConnection)) {
            return null;
        }
        connection.setUseCaches(false);
        jarFile = ((JarURLConnection)connection).getJarFile();
        Map<URL, JarFile> map = this.jarFiles;
        synchronized (map) {
            JarFile previous = this.jarFiles.putIfAbsent(url, jarFile);
            if (previous != null) {
                jarFile.close();
                jarFile = previous;
            }
        }
        return jarFile;
    }

    public void clearCache() {
        Handler.clearCache();
        org.springframework.boot.loader.net.protocol.nested.Handler.clearCache();
        try {
            this.clearJarFiles();
        }
        catch (IOException iOException) {
            // empty catch block
        }
        for (URL url : this.urls) {
            if (!this.isJarUrl(url)) continue;
            this.clearCache(url);
        }
    }

    private void clearCache(URL url) {
        try {
            URLConnection connection = url.openConnection();
            if (connection instanceof JarURLConnection) {
                JarURLConnection jarUrlConnection = (JarURLConnection)connection;
                this.clearCache(jarUrlConnection);
            }
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    private void clearCache(JarURLConnection connection) throws IOException {
        JarFile jarFile = connection.getJarFile();
        if (jarFile instanceof NestedJarFile) {
            NestedJarFile nestedJarFile = (NestedJarFile)jarFile;
            nestedJarFile.clearCache();
        }
    }

    private boolean isJarUrl(URL url) {
        return "jar".equals(url.getProtocol());
    }

    @Override
    public void close() throws IOException {
        super.close();
        this.clearJarFiles();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void clearJarFiles() throws IOException {
        Map<URL, JarFile> map = this.jarFiles;
        synchronized (map) {
            for (JarFile jarFile : this.jarFiles.values()) {
                jarFile.close();
            }
            this.jarFiles.clear();
        }
    }

    private static class OptimizedEnumeration
    implements Enumeration<URL> {
        private final Enumeration<URL> delegate;

        OptimizedEnumeration(Enumeration<URL> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasMoreElements() {
            Optimizations.enable(false);
            try {
                boolean bl = this.delegate.hasMoreElements();
                return bl;
            }
            finally {
                Optimizations.disable();
            }
        }

        @Override
        public URL nextElement() {
            Optimizations.enable(false);
            try {
                URL uRL = this.delegate.nextElement();
                return uRL;
            }
            finally {
                Optimizations.disable();
            }
        }
    }
}

