/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.zip;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Supplier;
import org.springframework.boot.loader.log.DebugLogger;
import org.springframework.boot.loader.zip.CloseableDataBlock;

class FileDataBlock
implements CloseableDataBlock {
    private static final DebugLogger debug = DebugLogger.get(FileDataBlock.class);
    static Tracker tracker = Tracker.NONE;
    private final FileAccess fileAccess;
    private final long offset;
    private final long size;

    FileDataBlock(Path path) throws IOException {
        this.fileAccess = new FileAccess(path);
        this.offset = 0L;
        this.size = Files.size(path);
    }

    FileDataBlock(FileAccess fileAccess, long offset, long size) {
        this.fileAccess = fileAccess;
        this.offset = offset;
        this.size = size;
    }

    @Override
    public long size() throws IOException {
        return this.size;
    }

    @Override
    public int read(ByteBuffer dst, long pos) throws IOException {
        if (pos < 0L) {
            throw new IllegalArgumentException("Position must not be negative");
        }
        this.ensureOpen(ClosedChannelException::new);
        int remaining = (int)(this.size - pos);
        if (remaining <= 0) {
            return -1;
        }
        int originalDestinationLimit = -1;
        if (dst.remaining() > remaining) {
            originalDestinationLimit = dst.limit();
            dst.limit(dst.position() + remaining);
        }
        int result = this.fileAccess.read(dst, this.offset + pos);
        if (originalDestinationLimit != -1) {
            dst.limit(originalDestinationLimit);
        }
        return result;
    }

    void open() throws IOException {
        this.fileAccess.open();
    }

    @Override
    public void close() throws IOException {
        this.fileAccess.close();
    }

    <E extends Exception> void ensureOpen(Supplier<E> exceptionSupplier) throws E {
        this.fileAccess.ensureOpen(exceptionSupplier);
    }

    FileDataBlock slice(long offset) throws IOException {
        return this.slice(offset, this.size - offset);
    }

    FileDataBlock slice(long offset, long size) {
        if (offset == 0L && size == this.size) {
            return this;
        }
        if (offset < 0L) {
            throw new IllegalArgumentException("Offset must not be negative");
        }
        if (size < 0L || offset + size > this.size) {
            throw new IllegalArgumentException("Size must not be negative and must be within bounds");
        }
        debug.log("Slicing %s at %s with size %s", this.fileAccess, offset, size);
        return new FileDataBlock(this.fileAccess, this.offset + offset, size);
    }

    static class FileAccess {
        static final int BUFFER_SIZE = 10240;
        private final Path path;
        private int referenceCount;
        private FileChannel fileChannel;
        private boolean fileChannelInterrupted;
        private RandomAccessFile randomAccessFile;
        private ByteBuffer buffer;
        private long bufferPosition = -1L;
        private int bufferSize;
        private final Object lock = new Object();

        FileAccess(Path path) {
            if (!Files.isRegularFile(path, new LinkOption[0])) {
                throw new IllegalArgumentException(path + " must be a regular file");
            }
            this.path = path;
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        int read(ByteBuffer dst, long position) throws IOException {
            Object object = this.lock;
            synchronized (object) {
                if (position < this.bufferPosition || position >= this.bufferPosition + (long)this.bufferSize) {
                    this.fillBuffer(position);
                }
                if (this.bufferSize <= 0) {
                    return this.bufferSize;
                }
                int offset = (int)(position - this.bufferPosition);
                int length = Math.min(this.bufferSize - offset, dst.remaining());
                dst.put(dst.position(), this.buffer, offset, length);
                dst.position(dst.position() + length);
                return length;
            }
        }

        private void fillBuffer(long position) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                this.fillBufferUsingRandomAccessFile(position);
                return;
            }
            try {
                if (this.fileChannelInterrupted) {
                    this.repairFileChannel();
                    this.fileChannelInterrupted = false;
                }
                this.buffer.clear();
                this.bufferSize = this.fileChannel.read(this.buffer, position);
                this.bufferPosition = position;
            }
            catch (ClosedByInterruptException ex) {
                this.fileChannelInterrupted = true;
                this.fillBufferUsingRandomAccessFile(position);
            }
        }

        private void fillBufferUsingRandomAccessFile(long position) throws IOException {
            if (this.randomAccessFile == null) {
                this.randomAccessFile = new RandomAccessFile(this.path.toFile(), "r");
                tracker.openedFileChannel(this.path);
            }
            byte[] bytes = new byte[10240];
            this.randomAccessFile.seek(position);
            int len = this.randomAccessFile.read(bytes);
            this.buffer.clear();
            if (len > 0) {
                this.buffer.put(bytes, 0, len);
            }
            this.bufferSize = len;
            this.bufferPosition = position;
        }

        private void repairFileChannel() throws IOException {
            tracker.closedFileChannel(this.path);
            this.fileChannel = FileChannel.open(this.path, StandardOpenOption.READ);
            tracker.openedFileChannel(this.path);
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        void open() throws IOException {
            Object object = this.lock;
            synchronized (object) {
                if (this.referenceCount == 0) {
                    debug.log("Opening '%s'", this.path);
                    this.fileChannel = FileChannel.open(this.path, StandardOpenOption.READ);
                    this.buffer = ByteBuffer.allocateDirect(10240);
                    tracker.openedFileChannel(this.path);
                }
                ++this.referenceCount;
                debug.log("Reference count for '%s' incremented to %s", this.path, this.referenceCount);
            }
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        void close() throws IOException {
            Object object = this.lock;
            synchronized (object) {
                if (this.referenceCount == 0) {
                    return;
                }
                --this.referenceCount;
                if (this.referenceCount == 0) {
                    debug.log("Closing '%s'", this.path);
                    this.buffer = null;
                    this.bufferPosition = -1L;
                    this.bufferSize = 0;
                    this.fileChannel.close();
                    tracker.closedFileChannel(this.path);
                    this.fileChannel = null;
                    if (this.randomAccessFile != null) {
                        this.randomAccessFile.close();
                        tracker.closedFileChannel(this.path);
                        this.randomAccessFile = null;
                    }
                }
                debug.log("Reference count for '%s' decremented to %s", this.path, this.referenceCount);
            }
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        <E extends Exception> void ensureOpen(Supplier<E> exceptionSupplier) throws E {
            Object object = this.lock;
            synchronized (object) {
                if (this.referenceCount == 0) {
                    throw (Exception)exceptionSupplier.get();
                }
            }
        }

        public String toString() {
            return this.path.toString();
        }
    }

    static interface Tracker {
        public static final Tracker NONE = new Tracker(){

            @Override
            public void openedFileChannel(Path path) {
            }

            @Override
            public void closedFileChannel(Path path) {
            }
        };

        public void openedFileChannel(Path var1);

        public void closedFileChannel(Path var1);
    }
}

