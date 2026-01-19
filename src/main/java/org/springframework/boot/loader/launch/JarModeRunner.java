/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.springframework.core.io.support.SpringFactoriesLoader
 *  org.springframework.util.ClassUtils
 */
package org.springframework.boot.loader.launch;

import java.util.List;
import org.springframework.boot.loader.jarmode.JarMode;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.ClassUtils;

final class JarModeRunner {
    static final String DISABLE_SYSTEM_EXIT = JarModeRunner.class.getName() + ".DISABLE_SYSTEM_EXIT";

    private JarModeRunner() {
    }

    static void main(String[] args) {
        String mode = System.getProperty("jarmode");
        List candidates = SpringFactoriesLoader.loadFactories(JarMode.class, (ClassLoader)ClassUtils.getDefaultClassLoader());
        for (JarMode candidate : candidates) {
            if (!candidate.accepts(mode)) continue;
            candidate.run(mode, args);
            return;
        }
        System.err.println("Unsupported jarmode '" + mode + "'");
        if (!Boolean.getBoolean(DISABLE_SYSTEM_EXIT)) {
            System.exit(1);
        }
    }
}

