/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.launch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.function.Supplier;
import java.util.jar.Manifest;
import org.springframework.boot.loader.launch.Archive;
import org.springframework.boot.loader.launch.JarModeRunner;
import org.springframework.boot.loader.net.protocol.jar.JarUrlClassLoader;

public class LaunchedClassLoader
extends JarUrlClassLoader {
    private static final String JAR_MODE_PACKAGE_PREFIX = "org.springframework.boot.loader.jarmode.";
    private static final String JAR_MODE_RUNNER_CLASS_NAME = JarModeRunner.class.getName();
    private final boolean exploded;
    private final Archive rootArchive;
    private final Object definePackageLock = new Object();
    private volatile DefinePackageCallType definePackageCallType;

    public LaunchedClassLoader(boolean exploded, URL[] urls, ClassLoader parent) {
        this(exploded, null, urls, parent);
    }

    public LaunchedClassLoader(boolean exploded, Archive rootArchive, URL[] urls, ClassLoader parent) {
        super(urls, parent);
        this.exploded = exploded;
        this.rootArchive = rootArchive;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(JAR_MODE_PACKAGE_PREFIX) || name.equals(JAR_MODE_RUNNER_CLASS_NAME)) {
            try {
                Class<?> result = this.loadClassInLaunchedClassLoader(name);
                if (resolve) {
                    this.resolveClass(result);
                }
                return result;
            }
            catch (ClassNotFoundException classNotFoundException) {
                // empty catch block
            }
        }
        return super.loadClass(name, resolve);
    }

    /*
     * Enabled aggressive exception aggregation
     */
    private Class<?> loadClassInLaunchedClassLoader(String name) throws ClassNotFoundException {
        try {
            String internalName = name.replace('.', '/') + ".class";
            try (InputStream inputStream = this.getParent().getResourceAsStream(internalName);){
                Class<?> clazz;
                try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();){
                    if (inputStream == null) {
                        throw new ClassNotFoundException(name);
                    }
                    inputStream.transferTo(outputStream);
                    byte[] bytes = outputStream.toByteArray();
                    Class<?> definedClass = this.defineClass(name, bytes, 0, bytes.length);
                    this.definePackageIfNecessary(name);
                    clazz = definedClass;
                }
                return clazz;
            }
        }
        catch (IOException ex) {
            throw new ClassNotFoundException("Cannot load resource for class [" + name + "]", ex);
        }
    }

    @Override
    protected Package definePackage(String name, Manifest man, URL url) throws IllegalArgumentException {
        return !this.exploded ? super.definePackage(name, man, url) : this.definePackageForExploded(name, man, url);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private Package definePackageForExploded(String name, Manifest man, URL url) {
        Object object = this.definePackageLock;
        synchronized (object) {
            return this.definePackage(DefinePackageCallType.MANIFEST, () -> super.definePackage(name, man, url));
        }
    }

    @Override
    protected Package definePackage(String name, String specTitle, String specVersion, String specVendor, String implTitle, String implVersion, String implVendor, URL sealBase) throws IllegalArgumentException {
        if (!this.exploded) {
            return super.definePackage(name, specTitle, specVersion, specVendor, implTitle, implVersion, implVendor, sealBase);
        }
        return this.definePackageForExploded(name, sealBase, () -> super.definePackage(name, specTitle, specVersion, specVendor, implTitle, implVersion, implVendor, sealBase));
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private Package definePackageForExploded(String name, URL sealBase, Supplier<Package> call) {
        Object object = this.definePackageLock;
        synchronized (object) {
            Manifest manifest;
            if (this.definePackageCallType == null && (manifest = this.getManifest(this.rootArchive)) != null) {
                return this.definePackage(name, manifest, sealBase);
            }
            return this.definePackage(DefinePackageCallType.ATTRIBUTES, call);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private <T> T definePackage(DefinePackageCallType type, Supplier<T> call) {
        DefinePackageCallType existingType = this.definePackageCallType;
        try {
            this.definePackageCallType = type;
            T t = call.get();
            return t;
        }
        finally {
            this.definePackageCallType = existingType;
        }
    }

    private Manifest getManifest(Archive archive) {
        try {
            return archive != null ? archive.getManifest() : null;
        }
        catch (IOException ex) {
            return null;
        }
    }

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private static enum DefinePackageCallType {
        MANIFEST,
        ATTRIBUTES;

    }
}

