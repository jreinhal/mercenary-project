/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.net.protocol.nested;

import java.io.File;
import java.io.FilePermission;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.ref.Cleaner;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.Permission;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.loader.net.protocol.nested.NestedLocation;
import org.springframework.boot.loader.net.protocol.nested.NestedUrlConnectionResources;
import org.springframework.boot.loader.ref.Cleaner;

class NestedUrlConnection
extends URLConnection {
    private static final DateTimeFormatter RFC_1123_DATE_TIME = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneId.of("GMT"));
    private static final String CONTENT_TYPE = "x-java/jar";
    private final NestedUrlConnectionResources resources;
    private final Cleaner.Cleanable cleanup;
    private long lastModified = -1L;
    private FilePermission permission;
    private Map<String, List<String>> headerFields;

    NestedUrlConnection(URL url) throws MalformedURLException {
        this(url, Cleaner.instance);
    }

    NestedUrlConnection(URL url, Cleaner cleaner) throws MalformedURLException {
        super(url);
        NestedLocation location = this.parseNestedLocation(url);
        this.resources = new NestedUrlConnectionResources(location);
        this.cleanup = cleaner.register(this, this.resources);
    }

    private NestedLocation parseNestedLocation(URL url) throws MalformedURLException {
        try {
            return NestedLocation.fromUrl(url);
        }
        catch (IllegalArgumentException ex) {
            throw new MalformedURLException(ex.getMessage());
        }
    }

    @Override
    public String getHeaderField(String name) {
        List<String> values = this.getHeaderFields().get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    @Override
    public String getHeaderField(int n) {
        Map.Entry<String, List<String>> entry = this.getHeaderEntry(n);
        List<String> values = entry != null ? entry.getValue() : null;
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    @Override
    public String getHeaderFieldKey(int n) {
        Map.Entry<String, List<String>> entry = this.getHeaderEntry(n);
        return entry != null ? entry.getKey() : null;
    }

    private Map.Entry<String, List<String>> getHeaderEntry(int n) {
        Iterator<Map.Entry<String, List<String>>> iterator = this.getHeaderFields().entrySet().iterator();
        Map.Entry<String, List<String>> entry = null;
        for (int i = 0; i < n; ++i) {
            entry = !iterator.hasNext() ? null : iterator.next();
        }
        return entry;
    }

    @Override
    public Map<String, List<String>> getHeaderFields() {
        try {
            this.connect();
        }
        catch (IOException ex) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> headerFields = this.headerFields;
        if (headerFields == null) {
            headerFields = new LinkedHashMap<String, List<String>>();
            long contentLength = this.getContentLengthLong();
            long lastModified = this.getLastModified();
            if (contentLength > 0L) {
                headerFields.put("content-length", List.of(String.valueOf(contentLength)));
            }
            if (this.getLastModified() > 0L) {
                headerFields.put("last-modified", List.of(RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(lastModified))));
            }
            this.headerFields = headerFields = Collections.unmodifiableMap(headerFields);
        }
        return headerFields;
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
            return this.resources.getContentLength();
        }
        catch (IOException ex) {
            return -1L;
        }
    }

    @Override
    public String getContentType() {
        return CONTENT_TYPE;
    }

    @Override
    public long getLastModified() {
        if (this.lastModified == -1L) {
            try {
                this.lastModified = Files.getLastModifiedTime(this.resources.getLocation().path(), new LinkOption[0]).toMillis();
            }
            catch (IOException ex) {
                this.lastModified = 0L;
            }
        }
        return this.lastModified;
    }

    @Override
    public Permission getPermission() throws IOException {
        if (this.permission == null) {
            File file = this.resources.getLocation().path().toFile();
            this.permission = new FilePermission(file.getCanonicalPath(), "read");
        }
        return this.permission;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        this.connect();
        return new ConnectionInputStream(this.resources.getInputStream());
    }

    @Override
    public void connect() throws IOException {
        if (this.connected) {
            return;
        }
        this.resources.connect();
        this.connected = true;
    }

    class ConnectionInputStream
    extends FilterInputStream {
        private volatile boolean closing;

        ConnectionInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            if (this.closing) {
                return;
            }
            this.closing = true;
            try {
                super.close();
            }
            finally {
                try {
                    NestedUrlConnection.this.cleanup.clean();
                }
                catch (UncheckedIOException ex) {
                    throw ex.getCause();
                }
            }
        }
    }
}

