/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.jar;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.zip.Inflater;
import org.springframework.boot.loader.zip.ZipContent;

class NestedJarFileResources
implements Runnable {
    private static final int INFLATER_CACHE_LIMIT = 20;
    private ZipContent zipContent;
    private ZipContent zipContentForManifest;
    private final Set<InputStream> inputStreams = Collections.newSetFromMap(new WeakHashMap());
    private Deque<Inflater> inflaterCache = new ArrayDeque<Inflater>();

    NestedJarFileResources(File file, String nestedEntryName) throws IOException {
        this.zipContent = ZipContent.open(file.toPath(), nestedEntryName);
        this.zipContentForManifest = this.zipContent.getKind() != ZipContent.Kind.NESTED_DIRECTORY ? null : ZipContent.open(file.toPath());
    }

    ZipContent zipContent() {
        return this.zipContent;
    }

    ZipContent zipContentForManifest() {
        return this.zipContentForManifest != null ? this.zipContentForManifest : this.zipContent;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    void addInputStream(InputStream inputStream) {
        Set<InputStream> set = this.inputStreams;
        synchronized (set) {
            this.inputStreams.add(inputStream);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    void removeInputStream(InputStream inputStream) {
        Set<InputStream> set = this.inputStreams;
        synchronized (set) {
            this.inputStreams.remove(inputStream);
        }
    }

    Runnable createInflatorCleanupAction(Inflater inflater) {
        return () -> this.endOrCacheInflater(inflater);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    Inflater getOrCreateInflater() {
        Deque<Inflater> inflaterCache = this.inflaterCache;
        if (inflaterCache != null) {
            Deque<Inflater> deque = inflaterCache;
            synchronized (deque) {
                Inflater inflater = this.inflaterCache.poll();
                if (inflater != null) {
                    return inflater;
                }
            }
        }
        return new Inflater(true);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void endOrCacheInflater(Inflater inflater) {
        Deque<Inflater> inflaterCache = this.inflaterCache;
        if (inflaterCache != null) {
            Deque<Inflater> deque = inflaterCache;
            synchronized (deque) {
                if (this.inflaterCache == inflaterCache && inflaterCache.size() < 20) {
                    inflater.reset();
                    this.inflaterCache.add(inflater);
                    return;
                }
            }
        }
        inflater.end();
    }

    @Override
    public void run() {
        this.releaseAll();
    }

    private void releaseAll() {
        IOException exceptionChain = null;
        exceptionChain = this.releaseInflators(exceptionChain);
        exceptionChain = this.releaseInputStreams(exceptionChain);
        exceptionChain = this.releaseZipContent(exceptionChain);
        if ((exceptionChain = this.releaseZipContentForManifest(exceptionChain)) != null) {
            throw new UncheckedIOException(exceptionChain);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private IOException releaseInflators(IOException exceptionChain) {
        Deque<Inflater> inflaterCache = this.inflaterCache;
        if (inflaterCache != null) {
            try {
                Deque<Inflater> deque = inflaterCache;
                synchronized (deque) {
                    inflaterCache.forEach(Inflater::end);
                }
            }
            finally {
                this.inflaterCache = null;
            }
        }
        return exceptionChain;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private IOException releaseInputStreams(IOException exceptionChain) {
        Set<InputStream> set = this.inputStreams;
        synchronized (set) {
            for (InputStream inputStream : List.copyOf(this.inputStreams)) {
                try {
                    inputStream.close();
                }
                catch (IOException ex) {
                    exceptionChain = this.addToExceptionChain(exceptionChain, ex);
                }
            }
            this.inputStreams.clear();
        }
        return exceptionChain;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private IOException releaseZipContent(IOException exceptionChain) {
        ZipContent zipContent = this.zipContent;
        if (zipContent != null) {
            try {
                zipContent.close();
            }
            catch (IOException ex) {
                exceptionChain = this.addToExceptionChain(exceptionChain, ex);
            }
            finally {
                this.zipContent = null;
            }
        }
        return exceptionChain;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private IOException releaseZipContentForManifest(IOException exceptionChain) {
        ZipContent zipContentForManifest = this.zipContentForManifest;
        if (zipContentForManifest != null) {
            try {
                zipContentForManifest.close();
            }
            catch (IOException ex) {
                exceptionChain = this.addToExceptionChain(exceptionChain, ex);
            }
            finally {
                this.zipContentForManifest = null;
            }
        }
        return exceptionChain;
    }

    private IOException addToExceptionChain(IOException exceptionChain, IOException ex) {
        if (exceptionChain != null) {
            exceptionChain.addSuppressed(ex);
            return exceptionChain;
        }
        return ex;
    }
}

