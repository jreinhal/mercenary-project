/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.net.protocol.jar;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.springframework.boot.loader.jar.NestedJarFile;
import org.springframework.boot.loader.net.protocol.jar.UrlJarEntry;
import org.springframework.boot.loader.net.protocol.jar.UrlJarManifest;

class UrlNestedJarFile
extends NestedJarFile {
    private final UrlJarManifest manifest = new UrlJarManifest(() -> super.getManifest());
    private final Consumer<JarFile> closeAction;

    UrlNestedJarFile(File file, String nestedEntryName, Runtime.Version version, Consumer<JarFile> closeAction) throws IOException {
        super(file, nestedEntryName, version);
        this.closeAction = closeAction;
    }

    @Override
    public Manifest getManifest() throws IOException {
        return this.manifest.get();
    }

    @Override
    public JarEntry getEntry(String name) {
        return UrlJarEntry.of(super.getEntry(name), this.manifest);
    }

    @Override
    public void close() throws IOException {
        if (this.closeAction != null) {
            this.closeAction.accept(this);
        }
        super.close();
    }
}

