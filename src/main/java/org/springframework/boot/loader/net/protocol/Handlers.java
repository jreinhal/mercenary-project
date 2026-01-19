/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.net.protocol;

import java.net.URL;

public final class Handlers {
    private static final String PROTOCOL_HANDLER_PACKAGES = "java.protocol.handler.pkgs";
    private static final String PACKAGE = Handlers.class.getPackageName();

    private Handlers() {
    }

    public static void register() {
        String packages = System.getProperty(PROTOCOL_HANDLER_PACKAGES, "");
        packages = !packages.isEmpty() && !packages.contains(PACKAGE) ? packages + "|" + PACKAGE : PACKAGE;
        System.setProperty(PROTOCOL_HANDLER_PACKAGES, packages);
        Handlers.resetCachedUrlHandlers();
    }

    private static void resetCachedUrlHandlers() {
        try {
            URL.setURLStreamHandlerFactory(null);
        }
        catch (Error error) {
            // empty catch block
        }
    }
}

