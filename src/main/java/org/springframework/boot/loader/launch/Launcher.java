/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.launch;

import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Collection;
import java.util.Set;
import org.springframework.boot.loader.launch.Archive;
import org.springframework.boot.loader.launch.JarModeRunner;
import org.springframework.boot.loader.launch.LaunchedClassLoader;
import org.springframework.boot.loader.net.protocol.Handlers;

public abstract class Launcher {
    private static final String JAR_MODE_RUNNER_CLASS_NAME = JarModeRunner.class.getName();

    protected void launch(String[] args) throws Exception {
        if (!this.isExploded()) {
            Handlers.register();
        }
        try {
            ClassLoader classLoader = this.createClassLoader(this.getClassPathUrls());
            String jarMode = System.getProperty("jarmode");
            String mainClassName = this.hasLength(jarMode) ? JAR_MODE_RUNNER_CLASS_NAME : this.getMainClass();
            this.launch(classLoader, mainClassName, args);
        }
        catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    private boolean hasLength(String jarMode) {
        return jarMode != null && !jarMode.isEmpty();
    }

    protected ClassLoader createClassLoader(Collection<URL> urls) throws Exception {
        return this.createClassLoader(urls.toArray(new URL[0]));
    }

    private ClassLoader createClassLoader(URL[] urls) {
        ClassLoader parent = this.getClass().getClassLoader();
        return new LaunchedClassLoader(this.isExploded(), this.getArchive(), urls, parent);
    }

    protected void launch(ClassLoader classLoader, String mainClassName, String[] args) throws Exception {
        Thread.currentThread().setContextClassLoader(classLoader);
        Class<?> mainClass = Class.forName(mainClassName, false, classLoader);
        Method mainMethod = mainClass.getDeclaredMethod("main", String[].class);
        mainMethod.setAccessible(true);
        mainMethod.invoke(null, new Object[]{args});
    }

    protected boolean isExploded() {
        Archive archive = this.getArchive();
        return archive != null && archive.isExploded();
    }

    protected abstract Archive getArchive();

    protected abstract String getMainClass() throws Exception;

    protected abstract Set<URL> getClassPathUrls() throws Exception;
}

