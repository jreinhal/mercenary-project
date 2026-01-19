/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.net.protocol.jar;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;
import org.springframework.boot.loader.net.protocol.jar.JarFileUrlKey;
import org.springframework.boot.loader.net.protocol.jar.UrlJarFileFactory;

class UrlJarFiles {
    private final UrlJarFileFactory factory;
    private final Cache cache = new Cache();

    UrlJarFiles() {
        this(new UrlJarFileFactory());
    }

    UrlJarFiles(UrlJarFileFactory factory) {
        this.factory = factory;
    }

    JarFile getOrCreate(boolean useCaches, URL jarFileUrl) throws IOException {
        JarFile cached;
        if (useCaches && (cached = this.getCached(jarFileUrl)) != null) {
            return cached;
        }
        return this.factory.createJarFile(jarFileUrl, this::onClose);
    }

    JarFile getCached(URL jarFileUrl) {
        return this.cache.get(jarFileUrl);
    }

    boolean cacheIfAbsent(boolean useCaches, URL jarFileUrl, JarFile jarFile) {
        if (!useCaches) {
            return false;
        }
        return this.cache.putIfAbsent(jarFileUrl, jarFile);
    }

    void closeIfNotCached(URL jarFileUrl, JarFile jarFile) throws IOException {
        JarFile cached = this.getCached(jarFileUrl);
        if (cached != jarFile) {
            jarFile.close();
        }
    }

    URLConnection reconnect(JarFile jarFile, URLConnection existingConnection) throws IOException {
        Boolean useCaches = existingConnection != null ? Boolean.valueOf(existingConnection.getUseCaches()) : null;
        URLConnection connection = this.openConnection(jarFile);
        if (useCaches != null && connection != null) {
            connection.setUseCaches(useCaches);
        }
        return connection;
    }

    private URLConnection openConnection(JarFile jarFile) throws IOException {
        URL url = this.cache.get(jarFile);
        return url != null ? url.openConnection() : null;
    }

    private void onClose(JarFile jarFile) {
        this.cache.remove(jarFile);
    }

    void clearCache() {
        this.cache.clear();
    }

    private static final class Cache {
        private final Map<String, JarFile> jarFileUrlToJarFile = new HashMap<String, JarFile>();
        private final Map<JarFile, URL> jarFileToJarFileUrl = new HashMap<JarFile, URL>();

        private Cache() {
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        JarFile get(URL jarFileUrl) {
            String urlKey = JarFileUrlKey.get(jarFileUrl);
            Cache cache = this;
            synchronized (cache) {
                return this.jarFileUrlToJarFile.get(urlKey);
            }
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        URL get(JarFile jarFile) {
            Cache cache = this;
            synchronized (cache) {
                return this.jarFileToJarFileUrl.get(jarFile);
            }
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        boolean putIfAbsent(URL jarFileUrl, JarFile jarFile) {
            String urlKey = JarFileUrlKey.get(jarFileUrl);
            Cache cache = this;
            synchronized (cache) {
                JarFile cached = this.jarFileUrlToJarFile.get(urlKey);
                if (cached == null) {
                    this.jarFileUrlToJarFile.put(urlKey, jarFile);
                    this.jarFileToJarFileUrl.put(jarFile, jarFileUrl);
                    return true;
                }
                return false;
            }
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        void remove(JarFile jarFile) {
            Cache cache = this;
            synchronized (cache) {
                URL removedUrl = this.jarFileToJarFileUrl.remove(jarFile);
                if (removedUrl != null) {
                    this.jarFileUrlToJarFile.remove(JarFileUrlKey.get(removedUrl));
                }
            }
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        void clear() {
            Cache cache = this;
            synchronized (cache) {
                this.jarFileToJarFileUrl.clear();
                this.jarFileUrlToJarFile.clear();
            }
        }
    }
}

