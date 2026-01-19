/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.zip;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import org.springframework.boot.loader.log.DebugLogger;
import org.springframework.boot.loader.zip.CloseableDataBlock;
import org.springframework.boot.loader.zip.FileDataBlock;
import org.springframework.boot.loader.zip.NameOffsetLookups;
import org.springframework.boot.loader.zip.VirtualZipDataBlock;
import org.springframework.boot.loader.zip.Zip64EndOfCentralDirectoryLocator;
import org.springframework.boot.loader.zip.Zip64EndOfCentralDirectoryRecord;
import org.springframework.boot.loader.zip.ZipCentralDirectoryFileHeaderRecord;
import org.springframework.boot.loader.zip.ZipEndOfCentralDirectoryRecord;
import org.springframework.boot.loader.zip.ZipLocalFileHeaderRecord;
import org.springframework.boot.loader.zip.ZipString;

public final class ZipContent
implements Closeable {
    private static final String META_INF = "META-INF/";
    private static final byte[] SIGNATURE_SUFFIX = ".DSA".getBytes(StandardCharsets.UTF_8);
    private static final DebugLogger debug = DebugLogger.get(ZipContent.class);
    private static final Map<Source, ZipContent> cache = new ConcurrentHashMap<Source, ZipContent>();
    private final Source source;
    private final Kind kind;
    private final FileDataBlock data;
    private final long centralDirectoryPos;
    private final long commentPos;
    private final long commentLength;
    private final int[] lookupIndexes;
    private final int[] nameHashLookups;
    private final int[] relativeCentralDirectoryOffsetLookups;
    private final NameOffsetLookups nameOffsetLookups;
    private final boolean hasJarSignatureFile;
    private SoftReference<CloseableDataBlock> virtualData;
    private SoftReference<Map<Class<?>, Object>> info;

    private ZipContent(Source source, Kind kind, FileDataBlock data, long centralDirectoryPos, long commentPos, long commentLength, int[] lookupIndexes, int[] nameHashLookups, int[] relativeCentralDirectoryOffsetLookups, NameOffsetLookups nameOffsetLookups, boolean hasJarSignatureFile) {
        this.source = source;
        this.kind = kind;
        this.data = data;
        this.centralDirectoryPos = centralDirectoryPos;
        this.commentPos = commentPos;
        this.commentLength = commentLength;
        this.lookupIndexes = lookupIndexes;
        this.nameHashLookups = nameHashLookups;
        this.relativeCentralDirectoryOffsetLookups = relativeCentralDirectoryOffsetLookups;
        this.nameOffsetLookups = nameOffsetLookups;
        this.hasJarSignatureFile = hasJarSignatureFile;
    }

    public Kind getKind() {
        return this.kind;
    }

    public CloseableDataBlock openRawZipData() throws IOException {
        this.data.open();
        return !this.nameOffsetLookups.hasAnyEnabled() ? this.data : this.getVirtualData();
    }

    private CloseableDataBlock getVirtualData() throws IOException {
        CloseableDataBlock virtualData;
        CloseableDataBlock closeableDataBlock = virtualData = this.virtualData != null ? this.virtualData.get() : null;
        if (virtualData != null) {
            return virtualData;
        }
        virtualData = this.createVirtualData();
        this.virtualData = new SoftReference<CloseableDataBlock>(virtualData);
        return virtualData;
    }

    private CloseableDataBlock createVirtualData() throws IOException {
        int size = this.size();
        NameOffsetLookups nameOffsetLookups = this.nameOffsetLookups.emptyCopy();
        ZipCentralDirectoryFileHeaderRecord[] centralRecords = new ZipCentralDirectoryFileHeaderRecord[size];
        long[] centralRecordPositions = new long[size];
        for (int i = 0; i < size; ++i) {
            int lookupIndex = this.lookupIndexes[i];
            long pos = this.getCentralDirectoryFileHeaderRecordPos(lookupIndex);
            nameOffsetLookups.enable(i, this.nameOffsetLookups.isEnabled(lookupIndex));
            centralRecords[i] = ZipCentralDirectoryFileHeaderRecord.load(this.data, pos);
            centralRecordPositions[i] = pos;
        }
        return new VirtualZipDataBlock(this.data, nameOffsetLookups, centralRecords, centralRecordPositions);
    }

    public int size() {
        return this.lookupIndexes.length;
    }

    public String getComment() {
        try {
            return ZipString.readString(this.data, this.commentPos, this.commentLength);
        }
        catch (UncheckedIOException ex) {
            if (ex.getCause() instanceof ClosedChannelException) {
                throw new IllegalStateException("Zip content closed", ex);
            }
            throw ex;
        }
    }

    public Entry getEntry(CharSequence name) {
        return this.getEntry(null, name);
    }

    public Entry getEntry(CharSequence namePrefix, CharSequence name) {
        int nameHash = this.nameHash(namePrefix, name);
        int size = this.size();
        for (int lookupIndex = this.getFirstLookupIndex(nameHash); lookupIndex >= 0 && lookupIndex < size && this.nameHashLookups[lookupIndex] == nameHash; ++lookupIndex) {
            long pos = this.getCentralDirectoryFileHeaderRecordPos(lookupIndex);
            ZipCentralDirectoryFileHeaderRecord centralRecord = this.loadZipCentralDirectoryFileHeaderRecord(pos);
            if (!this.hasName(lookupIndex, centralRecord, pos, namePrefix, name)) continue;
            return new Entry(lookupIndex, centralRecord);
        }
        return null;
    }

    public boolean hasEntry(CharSequence namePrefix, CharSequence name) {
        int nameHash = this.nameHash(namePrefix, name);
        int size = this.size();
        for (int lookupIndex = this.getFirstLookupIndex(nameHash); lookupIndex >= 0 && lookupIndex < size && this.nameHashLookups[lookupIndex] == nameHash; ++lookupIndex) {
            long pos = this.getCentralDirectoryFileHeaderRecordPos(lookupIndex);
            ZipCentralDirectoryFileHeaderRecord centralRecord = this.loadZipCentralDirectoryFileHeaderRecord(pos);
            if (!this.hasName(lookupIndex, centralRecord, pos, namePrefix, name)) continue;
            return true;
        }
        return false;
    }

    public Entry getEntry(int index) {
        int lookupIndex = this.lookupIndexes[index];
        long pos = this.getCentralDirectoryFileHeaderRecordPos(lookupIndex);
        ZipCentralDirectoryFileHeaderRecord centralRecord = this.loadZipCentralDirectoryFileHeaderRecord(pos);
        return new Entry(lookupIndex, centralRecord);
    }

    private ZipCentralDirectoryFileHeaderRecord loadZipCentralDirectoryFileHeaderRecord(long pos) {
        try {
            return ZipCentralDirectoryFileHeaderRecord.load(this.data, pos);
        }
        catch (IOException ex) {
            if (ex instanceof ClosedChannelException) {
                throw new IllegalStateException("Zip content closed", ex);
            }
            throw new UncheckedIOException(ex);
        }
    }

    private int nameHash(CharSequence namePrefix, CharSequence name) {
        int nameHash = 0;
        nameHash = namePrefix != null ? ZipString.hash(nameHash, namePrefix, false) : nameHash;
        nameHash = ZipString.hash(nameHash, name, true);
        return nameHash;
    }

    private int getFirstLookupIndex(int nameHash) {
        int lookupIndex = Arrays.binarySearch(this.nameHashLookups, 0, this.nameHashLookups.length, nameHash);
        if (lookupIndex < 0) {
            return -1;
        }
        while (lookupIndex > 0 && this.nameHashLookups[lookupIndex - 1] == nameHash) {
            --lookupIndex;
        }
        return lookupIndex;
    }

    private long getCentralDirectoryFileHeaderRecordPos(int lookupIndex) {
        return this.centralDirectoryPos + (long)this.relativeCentralDirectoryOffsetLookups[lookupIndex];
    }

    private boolean hasName(int lookupIndex, ZipCentralDirectoryFileHeaderRecord centralRecord, long pos, CharSequence namePrefix, CharSequence name) {
        int offset = this.nameOffsetLookups.get(lookupIndex);
        pos += (long)(46 + offset);
        int len = centralRecord.fileNameLength() - offset;
        ByteBuffer buffer = ByteBuffer.allocate(256);
        if (namePrefix != null) {
            int startsWithNamePrefix = ZipString.startsWith(buffer, this.data, pos, len, namePrefix);
            if (startsWithNamePrefix == -1) {
                return false;
            }
            pos += (long)startsWithNamePrefix;
            len -= startsWithNamePrefix;
        }
        return ZipString.matches(buffer, this.data, pos, len, name, true);
    }

    public <I> I getInfo(Class<I> type, Function<ZipContent, I> function) {
        Map<Class<?>, Object> info;
        Map<Class<?>, Object> map = info = this.info != null ? this.info.get() : null;
        if (info == null) {
            info = new ConcurrentHashMap();
            this.info = new SoftReference(info);
        }
        return (I)info.computeIfAbsent(type, key -> {
            debug.log("Getting %s info from zip '%s'", type.getName(), this);
            return function.apply(this);
        });
    }

    public boolean hasJarSignatureFile() {
        return this.hasJarSignatureFile;
    }

    @Override
    public void close() throws IOException {
        this.data.close();
    }

    public String toString() {
        return this.source.toString();
    }

    public static ZipContent open(Path path) throws IOException {
        return ZipContent.open(new Source(path.toAbsolutePath(), null));
    }

    public static ZipContent open(Path path, String nestedEntryName) throws IOException {
        return ZipContent.open(new Source(path.toAbsolutePath(), nestedEntryName));
    }

    private static ZipContent open(Source source) throws IOException {
        ZipContent zipContent = cache.get(source);
        if (zipContent != null) {
            debug.log("Opening existing cached zip content for %s", zipContent);
            zipContent.data.open();
            return zipContent;
        }
        debug.log("Loading zip content from %s", source);
        zipContent = Loader.load(source);
        ZipContent previouslyCached = cache.putIfAbsent(source, zipContent);
        if (previouslyCached != null) {
            debug.log("Closing zip content from %s since cache was populated from another thread", source);
            zipContent.close();
            previouslyCached.data.open();
            return previouslyCached;
        }
        return zipContent;
    }

    private record Source(Path path, String nestedEntryName) {
        boolean isNested() {
            return this.nestedEntryName != null;
        }

        @Override
        public String toString() {
            return !this.isNested() ? this.path().toString() : this.path() + "[" + this.nestedEntryName() + "]";
        }
    }

    public static enum Kind {
        ZIP,
        NESTED_ZIP,
        NESTED_DIRECTORY;

    }

    public class Entry {
        private final int lookupIndex;
        private final ZipCentralDirectoryFileHeaderRecord centralRecord;
        private volatile String name;
        private volatile FileDataBlock content;

        Entry(int lookupIndex, ZipCentralDirectoryFileHeaderRecord centralRecord) {
            this.lookupIndex = lookupIndex;
            this.centralRecord = centralRecord;
        }

        public int getLookupIndex() {
            return this.lookupIndex;
        }

        public boolean isDirectory() {
            return this.getName().endsWith("/");
        }

        public boolean hasNameStartingWith(CharSequence prefix) {
            String name = this.name;
            if (name != null) {
                return name.startsWith(prefix.toString());
            }
            long pos = ZipContent.this.getCentralDirectoryFileHeaderRecordPos(this.lookupIndex) + 46L;
            return ZipString.startsWith(null, ZipContent.this.data, pos, this.centralRecord.fileNameLength(), prefix) != -1;
        }

        public String getName() {
            String name = this.name;
            if (name == null) {
                int offset = ZipContent.this.nameOffsetLookups.get(this.lookupIndex);
                long pos = ZipContent.this.getCentralDirectoryFileHeaderRecordPos(this.lookupIndex) + 46L + (long)offset;
                this.name = name = ZipString.readString(ZipContent.this.data, pos, this.centralRecord.fileNameLength() - offset);
            }
            return name;
        }

        public int getCompressionMethod() {
            return this.centralRecord.compressionMethod();
        }

        public int getUncompressedSize() {
            return this.centralRecord.uncompressedSize();
        }

        public CloseableDataBlock openContent() throws IOException {
            FileDataBlock content = this.getContent();
            content.open();
            return content;
        }

        private FileDataBlock getContent() throws IOException {
            FileDataBlock content = this.content;
            if (content == null) {
                int pos = this.centralRecord.offsetToLocalHeader();
                this.checkNotZip64Extended(pos);
                ZipLocalFileHeaderRecord localHeader = ZipLocalFileHeaderRecord.load(ZipContent.this.data, pos);
                int size = this.centralRecord.compressedSize();
                this.checkNotZip64Extended(size);
                this.content = content = ZipContent.this.data.slice((long)pos + localHeader.size(), size);
            }
            return content;
        }

        private void checkNotZip64Extended(int value) throws IOException {
            if (value == -1) {
                throw new IOException("Zip64 extended information extra fields are not supported");
            }
        }

        public <E extends ZipEntry> E as(Function<String, E> factory) {
            return (E)this.as((Entry entry, String name) -> (ZipEntry)factory.apply((String)name));
        }

        public <E extends ZipEntry> E as(BiFunction<Entry, String, E> factory) {
            try {
                ZipEntry result = (ZipEntry)factory.apply(this, this.getName());
                long pos = ZipContent.this.getCentralDirectoryFileHeaderRecordPos(this.lookupIndex);
                this.centralRecord.copyTo(ZipContent.this.data, pos, result);
                return (E)result;
            }
            catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    private static final class Loader {
        private final ByteBuffer buffer = ByteBuffer.allocate(256);
        private final Source source;
        private final FileDataBlock data;
        private final long centralDirectoryPos;
        private final int[] index;
        private int[] nameHashLookups;
        private int[] relativeCentralDirectoryOffsetLookups;
        private final NameOffsetLookups nameOffsetLookups;
        private int cursor;

        private Loader(Source source, Entry directoryEntry, FileDataBlock data, long centralDirectoryPos, int maxSize) {
            this.source = source;
            this.data = data;
            this.centralDirectoryPos = centralDirectoryPos;
            this.index = new int[maxSize];
            this.nameHashLookups = new int[maxSize];
            this.relativeCentralDirectoryOffsetLookups = new int[maxSize];
            this.nameOffsetLookups = directoryEntry != null ? new NameOffsetLookups(directoryEntry.getName().length(), maxSize) : NameOffsetLookups.NONE;
        }

        private void add(ZipCentralDirectoryFileHeaderRecord centralRecord, long pos, boolean enableNameOffset) throws IOException {
            int hash;
            int nameOffset = this.nameOffsetLookups.enable(this.cursor, enableNameOffset);
            this.nameHashLookups[this.cursor] = hash = ZipString.hash(this.buffer, this.data, pos + 46L + (long)nameOffset, centralRecord.fileNameLength() - nameOffset, true);
            this.relativeCentralDirectoryOffsetLookups[this.cursor] = (int)(pos - this.centralDirectoryPos);
            this.index[this.cursor] = this.cursor;
            ++this.cursor;
        }

        private ZipContent finish(Kind kind, long commentPos, long commentLength, boolean hasJarSignatureFile) {
            if (this.cursor != this.nameHashLookups.length) {
                this.nameHashLookups = Arrays.copyOf(this.nameHashLookups, this.cursor);
                this.relativeCentralDirectoryOffsetLookups = Arrays.copyOf(this.relativeCentralDirectoryOffsetLookups, this.cursor);
            }
            int size = this.nameHashLookups.length;
            this.sort(0, size - 1);
            int[] lookupIndexes = new int[size];
            for (int i = 0; i < size; ++i) {
                lookupIndexes[this.index[i]] = i;
            }
            return new ZipContent(this.source, kind, this.data, this.centralDirectoryPos, commentPos, commentLength, lookupIndexes, this.nameHashLookups, this.relativeCentralDirectoryOffsetLookups, this.nameOffsetLookups, hasJarSignatureFile);
        }

        private void sort(int left, int right) {
            if (left < right) {
                int pivot = this.nameHashLookups[left + (right - left) / 2];
                int i = left;
                int j = right;
                while (i <= j) {
                    while (this.nameHashLookups[i] < pivot) {
                        ++i;
                    }
                    while (this.nameHashLookups[j] > pivot) {
                        --j;
                    }
                    if (i > j) continue;
                    this.swap(i, j);
                    ++i;
                    --j;
                }
                if (left < j) {
                    this.sort(left, j);
                }
                if (right > i) {
                    this.sort(i, right);
                }
            }
        }

        private void swap(int i, int j) {
            Loader.swap(this.index, i, j);
            Loader.swap(this.nameHashLookups, i, j);
            Loader.swap(this.relativeCentralDirectoryOffsetLookups, i, j);
            this.nameOffsetLookups.swap(i, j);
        }

        private static void swap(int[] array, int i, int j) {
            int temp = array[i];
            array[i] = array[j];
            array[j] = temp;
        }

        static ZipContent load(Source source) throws IOException {
            if (!source.isNested()) {
                return Loader.loadNonNested(source);
            }
            try (ZipContent zip = ZipContent.open(source.path());){
                Entry entry = zip.getEntry(source.nestedEntryName());
                if (entry == null) {
                    throw new IOException("Nested entry '%s' not found in container zip '%s'".formatted(source.nestedEntryName(), source.path()));
                }
                ZipContent zipContent = !entry.isDirectory() ? Loader.loadNestedZip(source, entry) : Loader.loadNestedDirectory(source, zip, entry);
                return zipContent;
            }
        }

        private static ZipContent loadNonNested(Source source) throws IOException {
            debug.log("Loading non-nested zip '%s'", source.path());
            return Loader.openAndLoad(source, Kind.ZIP, new FileDataBlock(source.path()));
        }

        private static ZipContent loadNestedZip(Source source, Entry entry) throws IOException {
            if (entry.centralRecord.compressionMethod() != 0) {
                throw new IOException("Nested entry '%s' in container zip '%s' must not be compressed".formatted(source.nestedEntryName(), source.path()));
            }
            debug.log("Loading nested zip entry '%s' from '%s'", source.nestedEntryName(), source.path());
            return Loader.openAndLoad(source, Kind.NESTED_ZIP, entry.getContent());
        }

        private static ZipContent openAndLoad(Source source, Kind kind, FileDataBlock data) throws IOException {
            try {
                data.open();
                return Loader.loadContent(source, kind, data);
            }
            catch (IOException | RuntimeException ex) {
                data.close();
                throw ex;
            }
        }

        private static ZipContent loadContent(Source source, Kind kind, FileDataBlock data) throws IOException {
            long numberOfEntries;
            ZipEndOfCentralDirectoryRecord.Located locatedEocd = ZipEndOfCentralDirectoryRecord.load(data);
            ZipEndOfCentralDirectoryRecord eocd = locatedEocd.endOfCentralDirectoryRecord();
            long eocdPos = locatedEocd.pos();
            Zip64EndOfCentralDirectoryLocator zip64Locator = Zip64EndOfCentralDirectoryLocator.find(data, eocdPos);
            Zip64EndOfCentralDirectoryRecord zip64Eocd = Zip64EndOfCentralDirectoryRecord.load(data, zip64Locator);
            data = data.slice(Loader.getStartOfZipContent(data, eocd, zip64Eocd));
            long centralDirectoryPos = zip64Eocd != null ? zip64Eocd.offsetToStartOfCentralDirectory() : Integer.toUnsignedLong(eocd.offsetToStartOfCentralDirectory());
            long l = numberOfEntries = zip64Eocd != null ? zip64Eocd.totalNumberOfCentralDirectoryEntries() : (long)Short.toUnsignedInt(eocd.totalNumberOfCentralDirectoryEntries());
            if (numberOfEntries < 0L) {
                throw new IllegalStateException("Invalid number of zip entries in " + source);
            }
            if (numberOfEntries > Integer.MAX_VALUE) {
                throw new IllegalStateException("Too many zip entries in " + source);
            }
            Loader loader = new Loader(source, null, data, centralDirectoryPos, (int)numberOfEntries);
            ByteBuffer signatureNameSuffixBuffer = ByteBuffer.allocate(SIGNATURE_SUFFIX.length);
            boolean hasJarSignatureFile = false;
            long pos = centralDirectoryPos;
            int i = 0;
            while ((long)i < numberOfEntries) {
                ZipCentralDirectoryFileHeaderRecord centralRecord = ZipCentralDirectoryFileHeaderRecord.load(data, pos);
                if (!hasJarSignatureFile) {
                    long filenamePos = pos + 46L;
                    if (centralRecord.fileNameLength() > SIGNATURE_SUFFIX.length && ZipString.startsWith(loader.buffer, data, filenamePos, centralRecord.fileNameLength(), ZipContent.META_INF) >= 0) {
                        signatureNameSuffixBuffer.clear();
                        data.readFully(signatureNameSuffixBuffer, filenamePos + (long)centralRecord.fileNameLength() - (long)SIGNATURE_SUFFIX.length);
                        hasJarSignatureFile = Arrays.equals(SIGNATURE_SUFFIX, signatureNameSuffixBuffer.array());
                    }
                }
                loader.add(centralRecord, pos, false);
                pos += centralRecord.size();
                ++i;
            }
            long commentPos = locatedEocd.pos() + 22L;
            return loader.finish(kind, commentPos, eocd.commentLength(), hasJarSignatureFile);
        }

        private static long getStartOfZipContent(FileDataBlock data, ZipEndOfCentralDirectoryRecord eocd, Zip64EndOfCentralDirectoryRecord zip64Eocd) throws IOException {
            long specifiedOffsetToStartOfCentralDirectory = zip64Eocd != null ? zip64Eocd.offsetToStartOfCentralDirectory() : (long)eocd.offsetToStartOfCentralDirectory();
            long sizeOfCentralDirectoryAndEndRecords = Loader.getSizeOfCentralDirectoryAndEndRecords(eocd, zip64Eocd);
            long actualOffsetToStartOfCentralDirectory = data.size() - sizeOfCentralDirectoryAndEndRecords;
            return actualOffsetToStartOfCentralDirectory - specifiedOffsetToStartOfCentralDirectory;
        }

        private static long getSizeOfCentralDirectoryAndEndRecords(ZipEndOfCentralDirectoryRecord eocd, Zip64EndOfCentralDirectoryRecord zip64Eocd) {
            long result = 0L;
            result += eocd.size();
            if (zip64Eocd != null) {
                result += 20L;
                result += zip64Eocd.size();
            }
            return result += zip64Eocd != null ? zip64Eocd.sizeOfCentralDirectory() : (long)eocd.sizeOfCentralDirectory();
        }

        private static ZipContent loadNestedDirectory(Source source, ZipContent zip, Entry directoryEntry) throws IOException {
            debug.log("Loading nested directory entry '%s' from '%s'", source.nestedEntryName(), source.path());
            if (!source.nestedEntryName().endsWith("/")) {
                throw new IllegalArgumentException("Nested entry name must end with '/'");
            }
            String directoryName = directoryEntry.getName();
            zip.data.open();
            try {
                Loader loader = new Loader(source, directoryEntry, zip.data, zip.centralDirectoryPos, zip.size());
                for (int cursor = 0; cursor < zip.size(); ++cursor) {
                    ZipCentralDirectoryFileHeaderRecord centralRecord;
                    short nameLen;
                    long pos;
                    long namePos;
                    int index = zip.lookupIndexes[cursor];
                    if (index == directoryEntry.getLookupIndex() || ZipString.startsWith(loader.buffer, zip.data, namePos = (pos = zip.getCentralDirectoryFileHeaderRecordPos(index)) + 46L, nameLen = (centralRecord = ZipCentralDirectoryFileHeaderRecord.load(zip.data, pos)).fileNameLength(), directoryName) == -1) continue;
                    loader.add(centralRecord, pos, true);
                }
                return loader.finish(Kind.NESTED_DIRECTORY, zip.commentPos, zip.commentLength, zip.hasJarSignatureFile);
            }
            catch (IOException | RuntimeException ex) {
                zip.data.close();
                throw ex;
            }
        }
    }
}

