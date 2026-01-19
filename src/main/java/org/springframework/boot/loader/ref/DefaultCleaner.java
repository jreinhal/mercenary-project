/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.ref;

import java.lang.ref.Cleaner;
import java.util.function.BiConsumer;
import org.springframework.boot.loader.ref.Cleaner;

class DefaultCleaner
implements Cleaner {
    static final DefaultCleaner instance = new DefaultCleaner();
    static BiConsumer<Object, Cleaner.Cleanable> tracker;
    private final java.lang.ref.Cleaner cleaner = java.lang.ref.Cleaner.create();

    DefaultCleaner() {
    }

    @Override
    public Cleaner.Cleanable register(Object obj, Runnable action) {
        Cleaner.Cleanable cleanable;
        Cleaner.Cleanable cleanable2 = cleanable = action != null ? this.cleaner.register(obj, action) : null;
        if (tracker != null) {
            tracker.accept(obj, cleanable);
        }
        return cleanable;
    }
}

