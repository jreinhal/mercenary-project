/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.jar;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.file.attribute.FileTime;
import java.security.CodeSigner;
import java.security.cert.Certificate;
import java.time.LocalDateTime;
import java.util.Enumeration;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import org.springframework.boot.loader.jar.ManifestInfo;
import org.springframework.boot.loader.jar.MetaInfVersionsInfo;
import org.springframework.boot.loader.jar.NestedJarFileResources;
import org.springframework.boot.loader.jar.SecurityInfo;
import org.springframework.boot.loader.jar.ZipInflaterInputStream;
import org.springframework.boot.loader.log.DebugLogger;
import org.springframework.boot.loader.ref.Cleaner;
import org.springframework.boot.loader.zip.CloseableDataBlock;
import org.springframework.boot.loader.zip.ZipContent;

public class NestedJarFile
extends JarFile {
    private static final int DECIMAL = 10;
    private static final String META_INF = "META-INF/";
    static final String META_INF_VERSIONS = "META-INF/versions/";
    static final int BASE_VERSION = NestedJarFile.baseVersion().feature();
    private static final DebugLogger debug = DebugLogger.get(NestedJarFile.class);
    private final Cleaner cleaner;
    private final NestedJarFileResources resources;
    private final Cleaner.Cleanable cleanup;
    private final String name;
    private final int version;
    private volatile NestedJarEntry lastEntry;
    private volatile boolean closed;
    private volatile ManifestInfo manifestInfo;
    private volatile MetaInfVersionsInfo metaInfVersionsInfo;

    NestedJarFile(File file) throws IOException {
        this(file, null, null, false, Cleaner.instance);
    }

    public NestedJarFile(File file, String nestedEntryName) throws IOException {
        this(file, nestedEntryName, null, true, Cleaner.instance);
    }

    public NestedJarFile(File file, String nestedEntryName, Runtime.Version version) throws IOException {
        this(file, nestedEntryName, version, true, Cleaner.instance);
    }

    NestedJarFile(File file, String nestedEntryName, Runtime.Version version, boolean onlyNestedJars, Cleaner cleaner) throws IOException {
        super(file);
        if (onlyNestedJars && (nestedEntryName == null || nestedEntryName.isEmpty())) {
            throw new IllegalArgumentException("nestedEntryName must not be empty");
        }
        debug.log("Created nested jar file (%s, %s, %s)", file, nestedEntryName, version);
        this.cleaner = cleaner;
        this.resources = new NestedJarFileResources(file, nestedEntryName);
        this.cleanup = cleaner.register(this, this.resources);
        this.name = file.getPath() + (String)(nestedEntryName != null ? "!/" + nestedEntryName : "");
        this.version = version != null ? version.feature() : NestedJarFile.baseVersion().feature();
    }

    public InputStream getRawZipDataInputStream() throws IOException {
        RawZipDataInputStream inputStream = new RawZipDataInputStream(this.resources.zipContent().openRawZipData().asInputStream());
        this.resources.addInputStream(inputStream);
        return inputStream;
    }

    @Override
    public Manifest getManifest() throws IOException {
        try {
            return this.resources.zipContentForManifest().getInfo(ManifestInfo.class, this::getManifestInfo).getManifest();
        }
        catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    public Enumeration<JarEntry> entries() {
        NestedJarFile nestedJarFile = this;
        synchronized (nestedJarFile) {
            this.ensureOpen();
            return new JarEntriesEnumeration(this.resources.zipContent());
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    public Stream<JarEntry> stream() {
        NestedJarFile nestedJarFile = this;
        synchronized (nestedJarFile) {
            this.ensureOpen();
            return this.streamContentEntries().map(x$0 -> new NestedJarEntry((ZipContent.Entry)x$0));
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    public Stream<JarEntry> versionedStream() {
        NestedJarFile nestedJarFile = this;
        synchronized (nestedJarFile) {
            this.ensureOpen();
            return this.streamContentEntries().map(this::getBaseName).filter(Objects::nonNull).distinct().map(this::getJarEntry).filter(Objects::nonNull);
        }
    }

    private Stream<ZipContent.Entry> streamContentEntries() {
        ZipContentEntriesSpliterator spliterator = new ZipContentEntriesSpliterator(this.resources.zipContent());
        return StreamSupport.stream(spliterator, false);
    }

    private String getBaseName(ZipContent.Entry contentEntry) {
        int versionNumberEndIndex;
        String name = contentEntry.getName();
        if (!name.startsWith(META_INF_VERSIONS)) {
            return name;
        }
        int versionNumberStartIndex = META_INF_VERSIONS.length();
        int n = versionNumberEndIndex = versionNumberStartIndex != -1 ? name.indexOf(47, versionNumberStartIndex) : -1;
        if (versionNumberEndIndex == -1 || versionNumberEndIndex == name.length() - 1) {
            return null;
        }
        try {
            int versionNumber = Integer.parseInt(name, versionNumberStartIndex, versionNumberEndIndex, 10);
            if (versionNumber > this.version) {
                return null;
            }
        }
        catch (NumberFormatException ex) {
            return null;
        }
        return name.substring(versionNumberEndIndex + 1);
    }

    @Override
    public JarEntry getJarEntry(String name) {
        return this.getNestedJarEntry(name);
    }

    @Override
    public JarEntry getEntry(String name) {
        return this.getNestedJarEntry(name);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public boolean hasEntry(String name) {
        NestedJarEntry lastEntry = this.lastEntry;
        if (lastEntry != null && name.equals(lastEntry.getName())) {
            return true;
        }
        ZipContent.Entry entry = this.getVersionedContentEntry(name);
        if (entry != null) {
            return true;
        }
        NestedJarFile nestedJarFile = this;
        synchronized (nestedJarFile) {
            this.ensureOpen();
            return this.resources.zipContent().hasEntry(null, name);
        }
    }

    private NestedJarEntry getNestedJarEntry(String name) {
        NestedJarEntry nestedJarEntry;
        Objects.requireNonNull(name, "name");
        NestedJarEntry lastEntry = this.lastEntry;
        if (lastEntry != null && name.equals(lastEntry.getName())) {
            return lastEntry;
        }
        ZipContent.Entry entry = this.getVersionedContentEntry(name);
        ZipContent.Entry entry2 = entry = entry != null ? entry : this.getContentEntry(null, name);
        if (entry == null) {
            return null;
        }
        this.lastEntry = nestedJarEntry = new NestedJarEntry(entry, name);
        return nestedJarEntry;
    }

    private ZipContent.Entry getVersionedContentEntry(String name) {
        if (BASE_VERSION >= this.version || name.startsWith(META_INF) || !this.getManifestInfo().isMultiRelease()) {
            return null;
        }
        MetaInfVersionsInfo metaInfVersionsInfo = this.getMetaInfVersionsInfo();
        int[] versions = metaInfVersionsInfo.versions();
        String[] directories = metaInfVersionsInfo.directories();
        for (int i = versions.length - 1; i >= 0; --i) {
            ZipContent.Entry entry;
            if (versions[i] > this.version || (entry = this.getContentEntry(directories[i], name)) == null) continue;
            return entry;
        }
        return null;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private ZipContent.Entry getContentEntry(String namePrefix, String name) {
        NestedJarFile nestedJarFile = this;
        synchronized (nestedJarFile) {
            this.ensureOpen();
            return this.resources.zipContent().getEntry(namePrefix, name);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private ManifestInfo getManifestInfo() {
        ManifestInfo manifestInfo = this.manifestInfo;
        if (manifestInfo != null) {
            return manifestInfo;
        }
        NestedJarFile nestedJarFile = this;
        synchronized (nestedJarFile) {
            this.ensureOpen();
            manifestInfo = this.resources.zipContent().getInfo(ManifestInfo.class, this::getManifestInfo);
        }
        this.manifestInfo = manifestInfo;
        return manifestInfo;
    }

    private ManifestInfo getManifestInfo(ZipContent zipContent) {
        ManifestInfo manifestInfo;
        block9: {
            ZipContent.Entry contentEntry = zipContent.getEntry("META-INF/MANIFEST.MF");
            if (contentEntry == null) {
                return ManifestInfo.NONE;
            }
            InputStream inputStream = this.getInputStream(contentEntry);
            try {
                Manifest manifest = new Manifest(inputStream);
                manifestInfo = new ManifestInfo(manifest);
                if (inputStream == null) break block9;
            }
            catch (Throwable throwable) {
                try {
                    if (inputStream != null) {
                        try {
                            inputStream.close();
                        }
                        catch (Throwable throwable2) {
                            throwable.addSuppressed(throwable2);
                        }
                    }
                    throw throwable;
                }
                catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
            inputStream.close();
        }
        return manifestInfo;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private MetaInfVersionsInfo getMetaInfVersionsInfo() {
        MetaInfVersionsInfo metaInfVersionsInfo = this.metaInfVersionsInfo;
        if (metaInfVersionsInfo != null) {
            return metaInfVersionsInfo;
        }
        NestedJarFile nestedJarFile = this;
        synchronized (nestedJarFile) {
            this.ensureOpen();
            metaInfVersionsInfo = this.resources.zipContent().getInfo(MetaInfVersionsInfo.class, MetaInfVersionsInfo::get);
        }
        this.metaInfVersionsInfo = metaInfVersionsInfo;
        return metaInfVersionsInfo;
    }

    @Override
    public InputStream getInputStream(ZipEntry entry) throws IOException {
        NestedJarEntry nestedJarEntry;
        Objects.requireNonNull(entry, "entry");
        if (entry instanceof NestedJarEntry && (nestedJarEntry = (NestedJarEntry)entry).isOwnedBy(this)) {
            return this.getInputStream(nestedJarEntry.contentEntry());
        }
        return this.getInputStream(this.getNestedJarEntry(entry.getName()).contentEntry());
    }

    private InputStream getInputStream(ZipContent.Entry contentEntry) throws IOException {
        int compression = contentEntry.getCompressionMethod();
        if (compression != 0 && compression != 8) {
            throw new ZipException("invalid compression method");
        }
        NestedJarFile nestedJarFile = this;
        synchronized (nestedJarFile) {
            this.ensureOpen();
            InputStream inputStream = new JarEntryInputStream(contentEntry);
            try {
                if (compression == 8) {
                    inputStream = new JarEntryInflaterInputStream((JarEntryInputStream)inputStream, this.resources);
                }
                this.resources.addInputStream(inputStream);
                return inputStream;
            }
            catch (RuntimeException ex) {
                ((InputStream)inputStream).close();
                throw ex;
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    public String getComment() {
        NestedJarFile nestedJarFile = this;
        synchronized (nestedJarFile) {
            this.ensureOpen();
            return this.resources.zipContent().getComment();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    public int size() {
        NestedJarFile nestedJarFile = this;
        synchronized (nestedJarFile) {
            this.ensureOpen();
            return this.resources.zipContent().size();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Override
    public void close() throws IOException {
        super.close();
        if (this.closed) {
            return;
        }
        this.closed = true;
        NestedJarFile nestedJarFile = this;
        synchronized (nestedJarFile) {
            try {
                this.cleanup.clean();
            }
            catch (UncheckedIOException ex) {
                throw ex.getCause();
            }
        }
    }

    @Override
    public String getName() {
        return this.name;
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Zip file closed");
        }
        if (this.resources.zipContent() == null) {
            throw new IllegalStateException("The object is not initialized.");
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void clearCache() {
        NestedJarFile nestedJarFile = this;
        synchronized (nestedJarFile) {
            this.lastEntry = null;
        }
    }

    private class RawZipDataInputStream
    extends FilterInputStream {
        private volatile boolean closed;

        RawZipDataInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            if (this.closed) {
                return;
            }
            this.closed = true;
            super.close();
            NestedJarFile.this.resources.removeInputStream(this);
        }
    }

    private class JarEntriesEnumeration
    implements Enumeration<JarEntry> {
        private final ZipContent zipContent;
        private int cursor;

        JarEntriesEnumeration(ZipContent zipContent) {
            this.zipContent = zipContent;
        }

        @Override
        public boolean hasMoreElements() {
            return this.cursor < this.zipContent.size();
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        @Override
        public NestedJarEntry nextElement() {
            if (!this.hasMoreElements()) {
                throw new NoSuchElementException();
            }
            NestedJarFile nestedJarFile = NestedJarFile.this;
            synchronized (nestedJarFile) {
                NestedJarFile.this.ensureOpen();
                return new NestedJarEntry(this.zipContent.getEntry(this.cursor++));
            }
        }
    }

    private class ZipContentEntriesSpliterator
    extends Spliterators.AbstractSpliterator<ZipContent.Entry> {
        private static final int ADDITIONAL_CHARACTERISTICS = 1297;
        private final ZipContent zipContent;
        private int cursor;

        ZipContentEntriesSpliterator(ZipContent zipContent) {
            super(zipContent.size(), 1297);
            this.zipContent = zipContent;
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        @Override
        public boolean tryAdvance(Consumer<? super ZipContent.Entry> action) {
            if (this.cursor < this.zipContent.size()) {
                NestedJarFile nestedJarFile = NestedJarFile.this;
                synchronized (nestedJarFile) {
                    NestedJarFile.this.ensureOpen();
                    action.accept(this.zipContent.getEntry(this.cursor++));
                }
                return true;
            }
            return false;
        }
    }

    private class NestedJarEntry
    extends JarEntry {
        private static final IllegalStateException CANNOT_BE_MODIFIED_EXCEPTION = new IllegalStateException("Neste jar entries cannot be modified");
        private final ZipContent.Entry contentEntry;
        private final String name;
        private volatile boolean populated;

        NestedJarEntry(ZipContent.Entry contentEntry) {
            this(contentEntry, contentEntry.getName());
        }

        NestedJarEntry(ZipContent.Entry contentEntry, String name) {
            super(contentEntry.getName());
            this.contentEntry = contentEntry;
            this.name = name;
        }

        @Override
        public long getTime() {
            this.populate();
            return super.getTime();
        }

        @Override
        public LocalDateTime getTimeLocal() {
            this.populate();
            return super.getTimeLocal();
        }

        @Override
        public void setTime(long time) {
            throw CANNOT_BE_MODIFIED_EXCEPTION;
        }

        @Override
        public void setTimeLocal(LocalDateTime time) {
            throw CANNOT_BE_MODIFIED_EXCEPTION;
        }

        @Override
        public FileTime getLastModifiedTime() {
            this.populate();
            return super.getLastModifiedTime();
        }

        @Override
        public ZipEntry setLastModifiedTime(FileTime time) {
            throw CANNOT_BE_MODIFIED_EXCEPTION;
        }

        @Override
        public FileTime getLastAccessTime() {
            this.populate();
            return super.getLastAccessTime();
        }

        @Override
        public ZipEntry setLastAccessTime(FileTime time) {
            throw CANNOT_BE_MODIFIED_EXCEPTION;
        }

        @Override
        public FileTime getCreationTime() {
            this.populate();
            return super.getCreationTime();
        }

        @Override
        public ZipEntry setCreationTime(FileTime time) {
            throw CANNOT_BE_MODIFIED_EXCEPTION;
        }

        @Override
        public long getSize() {
            return (long)this.contentEntry.getUncompressedSize() & 0xFFFFFFFFL;
        }

        @Override
        public void setSize(long size) {
            throw CANNOT_BE_MODIFIED_EXCEPTION;
        }

        @Override
        public long getCompressedSize() {
            this.populate();
            return super.getCompressedSize();
        }

        @Override
        public void setCompressedSize(long csize) {
            throw CANNOT_BE_MODIFIED_EXCEPTION;
        }

        @Override
        public long getCrc() {
            this.populate();
            return super.getCrc();
        }

        @Override
        public void setCrc(long crc) {
            throw CANNOT_BE_MODIFIED_EXCEPTION;
        }

        @Override
        public int getMethod() {
            this.populate();
            return super.getMethod();
        }

        @Override
        public void setMethod(int method) {
            throw CANNOT_BE_MODIFIED_EXCEPTION;
        }

        @Override
        public byte[] getExtra() {
            this.populate();
            return super.getExtra();
        }

        @Override
        public void setExtra(byte[] extra) {
            throw CANNOT_BE_MODIFIED_EXCEPTION;
        }

        @Override
        public String getComment() {
            this.populate();
            return super.getComment();
        }

        @Override
        public void setComment(String comment) {
            throw CANNOT_BE_MODIFIED_EXCEPTION;
        }

        boolean isOwnedBy(NestedJarFile nestedJarFile) {
            return NestedJarFile.this == nestedJarFile;
        }

        @Override
        public String getRealName() {
            return super.getName();
        }

        @Override
        public String getName() {
            return this.name;
        }

        @Override
        public Attributes getAttributes() throws IOException {
            Manifest manifest = NestedJarFile.this.getManifest();
            return manifest != null ? manifest.getAttributes(this.getName()) : null;
        }

        @Override
        public Certificate[] getCertificates() {
            return this.getSecurityInfo().getCertificates(this.contentEntry());
        }

        @Override
        public CodeSigner[] getCodeSigners() {
            return this.getSecurityInfo().getCodeSigners(this.contentEntry());
        }

        private SecurityInfo getSecurityInfo() {
            return NestedJarFile.this.resources.zipContent().getInfo(SecurityInfo.class, SecurityInfo::get);
        }

        ZipContent.Entry contentEntry() {
            return this.contentEntry;
        }

        private void populate() {
            boolean populated = this.populated;
            if (!populated) {
                ZipEntry entry = this.contentEntry.as(ZipEntry::new);
                super.setMethod(entry.getMethod());
                super.setTime(entry.getTime());
                super.setCrc(entry.getCrc());
                super.setCompressedSize(entry.getCompressedSize());
                super.setSize(entry.getSize());
                super.setExtra(entry.getExtra());
                super.setComment(entry.getComment());
                this.populated = true;
            }
        }
    }

    private class JarEntryInputStream
    extends InputStream {
        private final int uncompressedSize;
        private final CloseableDataBlock content;
        private long pos;
        private long remaining;
        private volatile boolean closed;

        JarEntryInputStream(ZipContent.Entry entry) throws IOException {
            this.uncompressedSize = entry.getUncompressedSize();
            this.content = entry.openContent();
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return this.read(b, 0, 1) == 1 ? b[0] & 0xFF : -1;
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int result;
            NestedJarFile nestedJarFile = NestedJarFile.this;
            synchronized (nestedJarFile) {
                this.ensureOpen();
                ByteBuffer dst = ByteBuffer.wrap(b, off, len);
                int count = this.content.read(dst, this.pos);
                if (count > 0) {
                    this.pos += (long)count;
                    this.remaining -= (long)count;
                }
                result = count;
            }
            if (this.remaining == 0L) {
                this.close();
            }
            return result;
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        @Override
        public long skip(long n) throws IOException {
            long result;
            NestedJarFile nestedJarFile = NestedJarFile.this;
            synchronized (nestedJarFile) {
                result = n > 0L ? this.maxForwardSkip(n) : this.maxBackwardSkip(n);
                this.pos += result;
                this.remaining -= result;
            }
            if (this.remaining == 0L) {
                this.close();
            }
            return result;
        }

        private long maxForwardSkip(long n) {
            boolean willCauseOverflow = this.pos + n < 0L;
            return willCauseOverflow || n > this.remaining ? this.remaining : n;
        }

        private long maxBackwardSkip(long n) {
            return Math.max(-this.pos, n);
        }

        @Override
        public int available() {
            return this.remaining < Integer.MAX_VALUE ? (int)this.remaining : Integer.MAX_VALUE;
        }

        private void ensureOpen() throws ZipException {
            if (NestedJarFile.this.closed || this.closed) {
                throw new ZipException("ZipFile closed");
            }
        }

        @Override
        public void close() throws IOException {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.content.close();
            NestedJarFile.this.resources.removeInputStream(this);
        }

        int getUncompressedSize() {
            return this.uncompressedSize;
        }
    }

    private class JarEntryInflaterInputStream
    extends ZipInflaterInputStream {
        private final Cleaner.Cleanable cleanup;
        private volatile boolean closed;

        JarEntryInflaterInputStream(JarEntryInputStream inputStream, NestedJarFileResources resources) {
            this(inputStream, resources, resources.getOrCreateInflater());
        }

        private JarEntryInflaterInputStream(JarEntryInputStream inputStream, NestedJarFileResources resources, Inflater inflater) {
            super(inputStream, inflater, inputStream.getUncompressedSize());
            this.cleanup = NestedJarFile.this.cleaner.register(this, resources.createInflatorCleanupAction(inflater));
        }

        @Override
        public void close() throws IOException {
            if (this.closed) {
                return;
            }
            this.closed = true;
            super.close();
            NestedJarFile.this.resources.removeInputStream(this);
            this.cleanup.clean();
        }
    }
}

