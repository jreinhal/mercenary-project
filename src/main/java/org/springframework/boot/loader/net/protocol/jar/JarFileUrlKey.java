/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.net.protocol.jar;

import java.lang.ref.SoftReference;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class JarFileUrlKey {
    private static volatile SoftReference<Map<URL, String>> cache;

    private JarFileUrlKey() {
    }

    static String get(URL url) {
        Map<URL, String> cache;
        Map<URL, String> map = cache = JarFileUrlKey.cache != null ? JarFileUrlKey.cache.get() : null;
        if (cache == null) {
            cache = new ConcurrentHashMap<URL, String>();
            JarFileUrlKey.cache = new SoftReference<Map<URL, String>>(cache);
        }
        return cache.computeIfAbsent(url, JarFileUrlKey::create);
    }

    private static String create(URL url) {
        StringBuilder value = new StringBuilder();
        String protocol = url.getProtocol();
        String host = url.getHost();
        int port = url.getPort() != -1 ? url.getPort() : url.getDefaultPort();
        String file = url.getFile();
        value.append(protocol.toLowerCase());
        value.append(":");
        if (host != null && !host.isEmpty()) {
            value.append(host.toLowerCase());
            value.append((String)(port != -1 ? ":" + port : ""));
        }
        value.append(file != null ? file : "");
        if ("runtime".equals(url.getRef())) {
            value.append("#runtime");
        }
        return value.toString();
    }

    static void clearCache() {
        cache = null;
    }
}

