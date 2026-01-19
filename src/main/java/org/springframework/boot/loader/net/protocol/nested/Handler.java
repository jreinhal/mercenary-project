/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.net.protocol.nested;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import org.springframework.boot.loader.net.protocol.nested.NestedLocation;
import org.springframework.boot.loader.net.protocol.nested.NestedUrlConnection;

public class Handler
extends URLStreamHandler {
    private static final String PREFIX = "nested:";

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        return new NestedUrlConnection(url);
    }

    public static void assertUrlIsNotMalformed(String url) {
        if (url == null || !url.startsWith(PREFIX)) {
            throw new IllegalArgumentException("'url' must not be null and must use 'nested' protocol");
        }
        NestedLocation.parse(url.substring(PREFIX.length()));
    }

    public static void clearCache() {
        NestedLocation.clearCache();
    }
}

