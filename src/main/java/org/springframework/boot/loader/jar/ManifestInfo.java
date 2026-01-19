/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.jar;

import java.util.jar.Attributes;
import java.util.jar.Manifest;

class ManifestInfo {
    private static final Attributes.Name MULTI_RELEASE = new Attributes.Name("Multi-Release");
    static final ManifestInfo NONE = new ManifestInfo(null, false);
    private final Manifest manifest;
    private volatile Boolean multiRelease;

    ManifestInfo(Manifest manifest) {
        this(manifest, null);
    }

    private ManifestInfo(Manifest manifest, Boolean multiRelease) {
        this.manifest = manifest;
        this.multiRelease = multiRelease;
    }

    Manifest getManifest() {
        return this.manifest;
    }

    boolean isMultiRelease() {
        if (this.manifest == null) {
            return false;
        }
        Boolean multiRelease = this.multiRelease;
        if (multiRelease != null) {
            return multiRelease;
        }
        Attributes attributes = this.manifest.getMainAttributes();
        this.multiRelease = multiRelease = Boolean.valueOf(attributes.containsKey(MULTI_RELEASE));
        return multiRelease;
    }
}

