/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.launch;

import org.springframework.boot.loader.launch.Archive;
import org.springframework.boot.loader.launch.ExecutableArchiveLauncher;

public class WarLauncher
extends ExecutableArchiveLauncher {
    public WarLauncher() throws Exception {
    }

    protected WarLauncher(Archive archive) throws Exception {
        super(archive);
    }

    @Override
    public boolean isIncludedOnClassPath(Archive.Entry entry) {
        return WarLauncher.isLibraryFileOrClassesDirectory(entry);
    }

    @Override
    protected String getEntryPathPrefix() {
        return "WEB-INF/";
    }

    static boolean isLibraryFileOrClassesDirectory(Archive.Entry entry) {
        String name = entry.name();
        if (entry.isDirectory()) {
            return name.equals("WEB-INF/classes/");
        }
        return name.startsWith("WEB-INF/lib/") || name.startsWith("WEB-INF/lib-provided/");
    }

    public static void main(String[] args) throws Exception {
        new WarLauncher().launch(args);
    }
}

