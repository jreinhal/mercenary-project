/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.net.protocol.jar;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.jar.JarEntry;
import org.springframework.boot.loader.net.protocol.jar.Handler;

public final class JarUrl {
    private JarUrl() {
    }

    public static URL create(File file) {
        return JarUrl.create(file, (String)null);
    }

    public static URL create(File file, JarEntry nestedEntry) {
        return JarUrl.create(file, nestedEntry != null ? nestedEntry.getName() : null);
    }

    public static URL create(File file, String nestedEntryName) {
        return JarUrl.create(file, nestedEntryName, null);
    }

    public static URL create(File file, String nestedEntryName, String path) {
        try {
            path = path != null ? path : "";
            return new URL(null, "jar:" + JarUrl.getJarReference(file, nestedEntryName) + "!/" + path, Handler.INSTANCE);
        }
        catch (MalformedURLException ex) {
            throw new IllegalStateException("Unable to create JarFileArchive URL", ex);
        }
    }

    private static String getJarReference(File file, String nestedEntryName) {
        String jarFilePath = file.toURI().getRawPath().replace("!", "%21");
        return nestedEntryName != null ? "nested:" + jarFilePath + "/!" + nestedEntryName : "file:" + jarFilePath;
    }
}

