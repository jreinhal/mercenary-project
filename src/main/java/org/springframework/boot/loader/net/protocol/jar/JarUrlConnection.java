/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.net.protocol.jar;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.Permission;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.springframework.boot.loader.jar.NestedJarFile;
import org.springframework.boot.loader.net.protocol.jar.LazyDelegatingInputStream;
import org.springframework.boot.loader.net.protocol.jar.Optimizations;
import org.springframework.boot.loader.net.protocol.jar.UrlJarFileFactory;
import org.springframework.boot.loader.net.protocol.jar.UrlJarFiles;
import org.springframework.boot.loader.net.util.UrlDecoder;

final class JarUrlConnection
extends JarURLConnection {
    static final UrlJarFiles jarFiles = new UrlJarFiles();
    static final InputStream emptyInputStream = new ByteArrayInputStream(new byte[0]);
    static final FileNotFoundException FILE_NOT_FOUND_EXCEPTION = new FileNotFoundException("Jar file or entry not found");
    private static final URL NOT_FOUND_URL;
    static final JarUrlConnection NOT_FOUND_CONNECTION;
    private final String entryName;
    private final Supplier<FileNotFoundException> notFound;
    private JarFile jarFile;
    private URLConnection jarFileConnection;
    private JarEntry jarEntry;
    private String contentType;

    private JarUrlConnection(URL url) throws IOException {
        super(url);
        this.entryName = this.getEntryName();
        this.notFound = null;
        this.jarFileConnection = this.getJarFileURL().openConnection();
        this.jarFileConnection.setUseCaches(this.useCaches);
    }

    private JarUrlConnection(Supplier<FileNotFoundException> notFound) throws IOException {
        super(NOT_FOUND_URL);
        this.entryName = null;
        this.notFound = notFound;
    }

    @Override
    public JarFile getJarFile() throws IOException {
        this.connect();
        return this.jarFile;
    }

    @Override
    public JarEntry getJarEntry() throws IOException {
        this.connect();
        return this.jarEntry;
    }

    @Override
    public int getContentLength() {
        long contentLength = this.getContentLengthLong();
        return contentLength <= Integer.MAX_VALUE ? (int)contentLength : -1;
    }

    @Override
    public long getContentLengthLong() {
        try {
            this.connect();
            return this.jarEntry != null ? this.jarEntry.getSize() : this.jarFileConnection.getContentLengthLong();
        }
        catch (IOException ex) {
            return -1L;
        }
    }

    @Override
    public String getContentType() {
        if (this.contentType == null) {
            this.contentType = this.deduceContentType();
        }
        return this.contentType;
    }

    private String deduceContentType() {
        String type = this.entryName != null ? null : "x-java/jar";
        type = type != null ? type : this.deduceContentTypeFromStream();
        type = type != null ? type : this.deduceContentTypeFromEntryName();
        return type != null ? type : "content/unknown";
    }

    private String deduceContentTypeFromStream() {
        String string;
        block8: {
            this.connect();
            InputStream in = this.jarFile.getInputStream(this.jarEntry);
            try {
                string = JarUrlConnection.guessContentTypeFromStream(new BufferedInputStream(in));
                if (in == null) break block8;
            }
            catch (Throwable throwable) {
                try {
                    if (in != null) {
                        try {
                            in.close();
                        }
                        catch (Throwable throwable2) {
                            throwable.addSuppressed(throwable2);
                        }
                    }
                    throw throwable;
                }
                catch (IOException ex) {
                    return null;
                }
            }
            in.close();
        }
        return string;
    }

    private String deduceContentTypeFromEntryName() {
        return JarUrlConnection.guessContentTypeFromName(this.entryName);
    }

    @Override
    public long getLastModified() {
        return this.jarFileConnection != null ? this.jarFileConnection.getLastModified() : super.getLastModified();
    }

    @Override
    public String getHeaderField(String name) {
        return this.jarFileConnection != null ? this.jarFileConnection.getHeaderField(name) : null;
    }

    @Override
    public Object getContent() throws IOException {
        this.connect();
        return this.entryName != null ? super.getContent() : this.jarFile;
    }

    @Override
    public Permission getPermission() throws IOException {
        return this.jarFileConnection != null ? this.jarFileConnection.getPermission() : null;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        JarFile cached;
        if (this.notFound != null) {
            this.throwFileNotFound();
        }
        URL jarFileURL = this.getJarFileURL();
        if (this.entryName == null && !UrlJarFileFactory.isNestedUrl(jarFileURL)) {
            throw new IOException("no entry name specified");
        }
        if (!this.getUseCaches() && Optimizations.isEnabled(false) && this.entryName != null && (cached = jarFiles.getCached(jarFileURL)) != null && cached.getEntry(this.entryName) != null) {
            return emptyInputStream;
        }
        this.connect();
        if (this.jarEntry == null) {
            JarFile jarFile = this.jarFile;
            if (jarFile instanceof NestedJarFile) {
                NestedJarFile nestedJarFile = (NestedJarFile)jarFile;
                return nestedJarFile.getRawZipDataInputStream();
            }
            this.throwFileNotFound();
        }
        return new ConnectionInputStream();
    }

    @Override
    public boolean getAllowUserInteraction() {
        return this.jarFileConnection != null && this.jarFileConnection.getAllowUserInteraction();
    }

    @Override
    public void setAllowUserInteraction(boolean allowuserinteraction) {
        if (this.jarFileConnection != null) {
            this.jarFileConnection.setAllowUserInteraction(allowuserinteraction);
        }
    }

    @Override
    public boolean getUseCaches() {
        return this.jarFileConnection == null || this.jarFileConnection.getUseCaches();
    }

    @Override
    public void setUseCaches(boolean usecaches) {
        if (this.jarFileConnection != null) {
            this.jarFileConnection.setUseCaches(usecaches);
        }
    }

    @Override
    public boolean getDefaultUseCaches() {
        return this.jarFileConnection == null || this.jarFileConnection.getDefaultUseCaches();
    }

    @Override
    public void setDefaultUseCaches(boolean defaultusecaches) {
        if (this.jarFileConnection != null) {
            this.jarFileConnection.setDefaultUseCaches(defaultusecaches);
        }
    }

    @Override
    public void setIfModifiedSince(long ifModifiedSince) {
        if (this.jarFileConnection != null) {
            this.jarFileConnection.setIfModifiedSince(ifModifiedSince);
        }
    }

    @Override
    public String getRequestProperty(String key) {
        return this.jarFileConnection != null ? this.jarFileConnection.getRequestProperty(key) : null;
    }

    @Override
    public void setRequestProperty(String key, String value) {
        if (this.jarFileConnection != null) {
            this.jarFileConnection.setRequestProperty(key, value);
        }
    }

    @Override
    public void addRequestProperty(String key, String value) {
        if (this.jarFileConnection != null) {
            this.jarFileConnection.addRequestProperty(key, value);
        }
    }

    @Override
    public Map<String, List<String>> getRequestProperties() {
        return this.jarFileConnection != null ? this.jarFileConnection.getRequestProperties() : Collections.emptyMap();
    }

    @Override
    public void connect() throws IOException {
        if (this.connected) {
            return;
        }
        if (this.notFound != null) {
            this.throwFileNotFound();
        }
        boolean useCaches = this.getUseCaches();
        URL jarFileURL = this.getJarFileURL();
        if (this.entryName != null && Optimizations.isEnabled()) {
            this.assertCachedJarFileHasEntry(jarFileURL, this.entryName);
        }
        this.jarFile = jarFiles.getOrCreate(useCaches, jarFileURL);
        this.jarEntry = this.getJarEntry(jarFileURL);
        boolean addedToCache = jarFiles.cacheIfAbsent(useCaches, jarFileURL, this.jarFile);
        if (addedToCache) {
            this.jarFileConnection = jarFiles.reconnect(this.jarFile, this.jarFileConnection);
        }
        this.connected = true;
    }

    private void assertCachedJarFileHasEntry(URL jarFileURL, String entryName) throws FileNotFoundException {
        JarFile cachedJarFile = jarFiles.getCached(jarFileURL);
        if (cachedJarFile != null && cachedJarFile.getJarEntry(entryName) == null) {
            throw FILE_NOT_FOUND_EXCEPTION;
        }
    }

    private JarEntry getJarEntry(URL jarFileUrl) throws IOException {
        if (this.entryName == null) {
            return null;
        }
        JarEntry jarEntry = this.jarFile.getJarEntry(this.entryName);
        if (jarEntry == null) {
            jarFiles.closeIfNotCached(jarFileUrl, this.jarFile);
            this.throwFileNotFound();
        }
        return jarEntry;
    }

    private void throwFileNotFound() throws FileNotFoundException {
        if (Optimizations.isEnabled()) {
            throw FILE_NOT_FOUND_EXCEPTION;
        }
        if (this.notFound != null) {
            throw this.notFound.get();
        }
        throw new FileNotFoundException("JAR entry " + this.entryName + " not found in " + this.jarFile.getName());
    }

    static JarUrlConnection open(URL url) throws IOException {
        String spec = url.getFile();
        if (spec.startsWith("nested:")) {
            boolean specHasEntry;
            int separator = spec.indexOf("!/");
            boolean bl = specHasEntry = separator != -1 && separator + 2 != spec.length();
            if (specHasEntry) {
                URL jarFileUrl = new URL(spec.substring(0, separator));
                if ("runtime".equals(url.getRef())) {
                    jarFileUrl = new URL(jarFileUrl, "#runtime");
                }
                String entryName = UrlDecoder.decode(spec.substring(separator + 2));
                JarFile jarFile = jarFiles.getOrCreate(true, jarFileUrl);
                jarFiles.cacheIfAbsent(true, jarFileUrl, jarFile);
                if (!JarUrlConnection.hasEntry(jarFile, entryName)) {
                    return JarUrlConnection.notFoundConnection(jarFile.getName(), entryName);
                }
            }
        }
        return new JarUrlConnection(url);
    }

    private static boolean hasEntry(JarFile jarFile, String name) {
        boolean bl;
        if (jarFile instanceof NestedJarFile) {
            NestedJarFile nestedJarFile = (NestedJarFile)jarFile;
            bl = nestedJarFile.hasEntry(name);
        } else {
            bl = jarFile.getEntry(name) != null;
        }
        return bl;
    }

    private static JarUrlConnection notFoundConnection(String jarFileName, String entryName) throws IOException {
        if (Optimizations.isEnabled()) {
            return NOT_FOUND_CONNECTION;
        }
        return new JarUrlConnection(() -> new FileNotFoundException("JAR entry " + entryName + " not found in " + jarFileName));
    }

    static void clearCache() {
        jarFiles.clearCache();
    }

    static {
        try {
            NOT_FOUND_URL = new URL("jar:", null, 0, "nested:!/", new EmptyUrlStreamHandler());
            NOT_FOUND_CONNECTION = new JarUrlConnection(() -> FILE_NOT_FOUND_EXCEPTION);
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    class ConnectionInputStream
    extends LazyDelegatingInputStream {
        ConnectionInputStream() {
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            }
            finally {
                if (!JarUrlConnection.this.getUseCaches()) {
                    JarUrlConnection.this.jarFile.close();
                }
            }
        }

        @Override
        protected InputStream getDelegateInputStream() throws IOException {
            return JarUrlConnection.this.jarFile.getInputStream(JarUrlConnection.this.jarEntry);
        }
    }

    private static final class EmptyUrlStreamHandler
    extends URLStreamHandler {
        private EmptyUrlStreamHandler() {
        }

        @Override
        protected URLConnection openConnection(URL url) {
            return null;
        }
    }
}

