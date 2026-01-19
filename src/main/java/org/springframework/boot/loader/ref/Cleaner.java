/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.ref;

import java.lang.ref.Cleaner;
import org.springframework.boot.loader.ref.DefaultCleaner;

public interface Cleaner {
    public static final Cleaner instance = DefaultCleaner.instance;

    public Cleaner.Cleanable register(Object var1, Runnable var2);
}

