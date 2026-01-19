/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.launch;

import org.springframework.boot.loader.launch.Archive;
import org.springframework.boot.loader.launch.ExecutableArchiveLauncher;

public class JarLauncher
extends ExecutableArchiveLauncher {
    public JarLauncher() throws Exception {
    }

    protected JarLauncher(Archive archive) throws Exception {
        super(archive);
    }

    @Override
    protected boolean isIncludedOnClassPath(Archive.Entry entry) {
        return JarLauncher.isLibraryFileOrClassesDirectory(entry);
    }

    @Override
    protected String getEntryPathPrefix() {
        return "BOOT-INF/";
    }

    static boolean isLibraryFileOrClassesDirectory(Archive.Entry entry) {
        String name = entry.name();
        if (entry.isDirectory()) {
            return name.equals("BOOT-INF/classes/");
        }
        return name.startsWith("BOOT-INF/lib/");
    }

    public static void main(String[] args) throws Exception {
        new JarLauncher().launch(args);
    }
}

