/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.jar;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntFunction;
import org.springframework.boot.loader.jar.NestedJarFile;
import org.springframework.boot.loader.zip.ZipContent;

final class MetaInfVersionsInfo {
    static final MetaInfVersionsInfo NONE = new MetaInfVersionsInfo(Collections.emptySet());
    private static final String META_INF_VERSIONS = "META-INF/versions/";
    private final int[] versions;
    private final String[] directories;

    private MetaInfVersionsInfo(Set<Integer> versions) {
        this.versions = versions.stream().mapToInt(Integer::intValue).toArray();
        this.directories = (String[])versions.stream().map(version -> META_INF_VERSIONS + version + "/").toArray(String[]::new);
    }

    int[] versions() {
        return this.versions;
    }

    String[] directories() {
        return this.directories;
    }

    static MetaInfVersionsInfo get(ZipContent zipContent) {
        return MetaInfVersionsInfo.get(zipContent.size(), zipContent::getEntry);
    }

    static MetaInfVersionsInfo get(int size, IntFunction<ZipContent.Entry> entries) {
        TreeSet<Integer> versions = new TreeSet<Integer>();
        for (int i = 0; i < size; ++i) {
            ZipContent.Entry contentEntry = entries.apply(i);
            if (!contentEntry.hasNameStartingWith(META_INF_VERSIONS) || contentEntry.isDirectory()) continue;
            String name = contentEntry.getName();
            int slash = name.indexOf(47, META_INF_VERSIONS.length());
            String version = name.substring(META_INF_VERSIONS.length(), slash);
            try {
                int versionNumber = Integer.parseInt(version);
                if (versionNumber < NestedJarFile.BASE_VERSION) continue;
                versions.add(versionNumber);
                continue;
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        return !versions.isEmpty() ? new MetaInfVersionsInfo(versions) : NONE;
    }
}

