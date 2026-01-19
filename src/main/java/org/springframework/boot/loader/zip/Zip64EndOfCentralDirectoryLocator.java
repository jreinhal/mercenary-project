/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.zip;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.springframework.boot.loader.log.DebugLogger;
import org.springframework.boot.loader.zip.DataBlock;

record Zip64EndOfCentralDirectoryLocator(long pos, int numberOfThisDisk, long offsetToZip64EndOfCentralDirectoryRecord, int totalNumberOfDisks) {
    private static final DebugLogger debug = DebugLogger.get(Zip64EndOfCentralDirectoryLocator.class);
    private static final int SIGNATURE = 117853008;
    static final int SIZE = 20;

    static Zip64EndOfCentralDirectoryLocator find(DataBlock dataBlock, long endOfCentralDirectoryPos) throws IOException {
        debug.log("Finding Zip64EndOfCentralDirectoryLocator from EOCD at %s", endOfCentralDirectoryPos);
        long pos = endOfCentralDirectoryPos - 20L;
        if (pos < 0L) {
            debug.log("No Zip64EndOfCentralDirectoryLocator due to negative position %s", pos);
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(20);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        dataBlock.read(buffer, pos);
        buffer.rewind();
        int signature = buffer.getInt();
        if (signature != 117853008) {
            debug.log("Found incorrect Zip64EndOfCentralDirectoryLocator signature %s at position %s", signature, pos);
            return null;
        }
        debug.log("Found Zip64EndOfCentralDirectoryLocator at position %s", pos);
        return new Zip64EndOfCentralDirectoryLocator(pos, buffer.getInt(), buffer.getLong(), buffer.getInt());
    }
}

