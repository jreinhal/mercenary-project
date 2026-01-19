/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.net.protocol.nested;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.loader.net.util.UrlDecoder;

public record NestedLocation(Path path, String nestedEntryName) {
    private static final Map<String, NestedLocation> locationCache = new ConcurrentHashMap<String, NestedLocation>();
    private static final Map<String, Path> pathCache = new ConcurrentHashMap<String, Path>();

    public NestedLocation(Path path, String nestedEntryName) {
        if (path == null) {
            throw new IllegalArgumentException("'path' must not be null");
        }
        this.path = path;
        this.nestedEntryName = nestedEntryName != null && !nestedEntryName.isEmpty() ? nestedEntryName : null;
    }

    public static NestedLocation fromUrl(URL url) {
        if (url == null || !"nested".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("'url' must not be null and must use 'nested' protocol");
        }
        return NestedLocation.parse(UrlDecoder.decode(url.toString().substring(7)));
    }

    public static NestedLocation fromUri(URI uri) {
        if (uri == null || !"nested".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("'uri' must not be null and must use 'nested' scheme");
        }
        return NestedLocation.parse(uri.getSchemeSpecificPart());
    }

    static NestedLocation parse(String location) {
        if (location == null || location.isEmpty()) {
            throw new IllegalArgumentException("'location' must not be empty");
        }
        return locationCache.computeIfAbsent(location, key -> NestedLocation.create(location));
    }

    private static NestedLocation create(String location) {
        int index = location.lastIndexOf("/!");
        String locationPath = index != -1 ? location.substring(0, index) : location;
        String nestedEntryName = index != -1 ? location.substring(index + 2) : null;
        return new NestedLocation(!locationPath.isEmpty() ? NestedLocation.asPath(locationPath) : null, nestedEntryName);
    }

    private static Path asPath(String locationPath) {
        return pathCache.computeIfAbsent(locationPath, key -> Path.of(!NestedLocation.isWindows() ? locationPath : NestedLocation.fixWindowsLocationPath(locationPath), new String[0]));
    }

    private static boolean isWindows() {
        return File.separatorChar == '\\';
    }

    private static String fixWindowsLocationPath(String locationPath) {
        if (locationPath.length() > 2 && locationPath.charAt(2) == ':') {
            return locationPath.substring(1);
        }
        if (locationPath.startsWith("///") && locationPath.charAt(4) == ':') {
            return locationPath.substring(3);
        }
        return locationPath;
    }

    static void clearCache() {
        locationCache.clear();
        pathCache.clear();
    }
}

