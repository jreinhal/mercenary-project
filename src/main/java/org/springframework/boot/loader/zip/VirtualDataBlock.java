/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.zip;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import org.springframework.boot.loader.zip.DataBlock;

class VirtualDataBlock
implements DataBlock {
    private DataBlock[] parts;
    private long[] offsets;
    private long size;
    private volatile int lastReadPart = 0;

    protected VirtualDataBlock() {
    }

    VirtualDataBlock(Collection<? extends DataBlock> parts) throws IOException {
        this.setParts(parts);
    }

    protected void setParts(Collection<? extends DataBlock> parts) throws IOException {
        this.parts = (DataBlock[])parts.toArray(DataBlock[]::new);
        this.offsets = new long[parts.size()];
        long size = 0L;
        int i = 0;
        for (DataBlock dataBlock : parts) {
            this.offsets[i++] = size;
            size += dataBlock.size();
        }
        this.size = size;
    }

    @Override
    public long size() throws IOException {
        return this.size;
    }

    @Override
    public int read(ByteBuffer dst, long pos) throws IOException {
        if (pos < 0L || pos >= this.size) {
            return -1;
        }
        int lastReadPart = this.lastReadPart;
        int partIndex = 0;
        long offset = 0L;
        int result = 0;
        if (pos >= this.offsets[lastReadPart]) {
            partIndex = lastReadPart;
            offset = this.offsets[lastReadPart];
        }
        while (partIndex < this.parts.length) {
            DataBlock part = this.parts[partIndex];
            while (pos >= offset && pos < offset + part.size()) {
                int count = part.read(dst, pos - offset);
                result += Math.max(count, 0);
                if (count <= 0 || !dst.hasRemaining()) {
                    this.lastReadPart = partIndex;
                    return result;
                }
                pos += (long)count;
            }
            offset += part.size();
            ++partIndex;
        }
        return result;
    }
}

