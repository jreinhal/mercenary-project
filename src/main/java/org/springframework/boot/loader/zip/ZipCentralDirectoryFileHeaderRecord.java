/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.zip;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.ValueRange;
import java.util.zip.ZipEntry;
import org.springframework.boot.loader.log.DebugLogger;
import org.springframework.boot.loader.zip.DataBlock;
import org.springframework.boot.loader.zip.ZipString;

record ZipCentralDirectoryFileHeaderRecord(short versionMadeBy, short versionNeededToExtract, short generalPurposeBitFlag, short compressionMethod, short lastModFileTime, short lastModFileDate, int crc32, int compressedSize, int uncompressedSize, short fileNameLength, short extraFieldLength, short fileCommentLength, short diskNumberStart, short internalFileAttributes, int externalFileAttributes, int offsetToLocalHeader) {
    private static final DebugLogger debug = DebugLogger.get(ZipCentralDirectoryFileHeaderRecord.class);
    private static final int SIGNATURE = 33639248;
    private static final int MINIMUM_SIZE = 46;
    static final int FILE_NAME_OFFSET = 46;

    long size() {
        return 46 + this.fileNameLength() + this.extraFieldLength() + this.fileCommentLength();
    }

    void copyTo(DataBlock dataBlock, long pos, ZipEntry zipEntry) throws IOException {
        int fileNameLength = Short.toUnsignedInt(this.fileNameLength());
        int extraLength = Short.toUnsignedInt(this.extraFieldLength());
        int commentLength = Short.toUnsignedInt(this.fileCommentLength());
        zipEntry.setMethod(Short.toUnsignedInt(this.compressionMethod()));
        zipEntry.setTime(this.decodeMsDosFormatDateTime(this.lastModFileDate(), this.lastModFileTime()));
        zipEntry.setCrc(Integer.toUnsignedLong(this.crc32()));
        zipEntry.setCompressedSize(Integer.toUnsignedLong(this.compressedSize()));
        zipEntry.setSize(Integer.toUnsignedLong(this.uncompressedSize()));
        if (extraLength > 0) {
            long extraPos = pos + 46L + (long)fileNameLength;
            ByteBuffer buffer = ByteBuffer.allocate(extraLength);
            dataBlock.readFully(buffer, extraPos);
            zipEntry.setExtra(buffer.array());
        }
        if (commentLength > 0) {
            long commentPos = pos + 46L + (long)fileNameLength + (long)extraLength;
            zipEntry.setComment(ZipString.readString(dataBlock, commentPos, commentLength));
        }
    }

    private long decodeMsDosFormatDateTime(short date, short time) {
        int year = ZipCentralDirectoryFileHeaderRecord.getChronoValue((date >> 9 & 0x7F) + 1980, ChronoField.YEAR);
        int month = ZipCentralDirectoryFileHeaderRecord.getChronoValue(date >> 5 & 0xF, ChronoField.MONTH_OF_YEAR);
        int day = ZipCentralDirectoryFileHeaderRecord.getChronoValue(date & 0x1F, ChronoField.DAY_OF_MONTH);
        int hour = ZipCentralDirectoryFileHeaderRecord.getChronoValue(time >> 11 & 0x1F, ChronoField.HOUR_OF_DAY);
        int minute = ZipCentralDirectoryFileHeaderRecord.getChronoValue(time >> 5 & 0x3F, ChronoField.MINUTE_OF_HOUR);
        int second = ZipCentralDirectoryFileHeaderRecord.getChronoValue(time << 1 & 0x3E, ChronoField.SECOND_OF_MINUTE);
        return ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneId.systemDefault()).toInstant().truncatedTo(ChronoUnit.SECONDS).toEpochMilli();
    }

    private static int getChronoValue(long value, ChronoField field) {
        ValueRange range = field.range();
        return Math.toIntExact(Math.min(Math.max(value, range.getMinimum()), range.getMaximum()));
    }

    ZipCentralDirectoryFileHeaderRecord withFileNameLength(short fileNameLength) {
        return this.fileNameLength != fileNameLength ? new ZipCentralDirectoryFileHeaderRecord(this.versionMadeBy, this.versionNeededToExtract, this.generalPurposeBitFlag, this.compressionMethod, this.lastModFileTime, this.lastModFileDate, this.crc32, this.compressedSize, this.uncompressedSize, fileNameLength, this.extraFieldLength, this.fileCommentLength, this.diskNumberStart, this.internalFileAttributes, this.externalFileAttributes, this.offsetToLocalHeader) : this;
    }

    ZipCentralDirectoryFileHeaderRecord withOffsetToLocalHeader(int offsetToLocalHeader) {
        return this.offsetToLocalHeader != offsetToLocalHeader ? new ZipCentralDirectoryFileHeaderRecord(this.versionMadeBy, this.versionNeededToExtract, this.generalPurposeBitFlag, this.compressionMethod, this.lastModFileTime, this.lastModFileDate, this.crc32, this.compressedSize, this.uncompressedSize, this.fileNameLength, this.extraFieldLength, this.fileCommentLength, this.diskNumberStart, this.internalFileAttributes, this.externalFileAttributes, offsetToLocalHeader) : this;
    }

    byte[] asByteArray() {
        ByteBuffer buffer = ByteBuffer.allocate(46);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(33639248);
        buffer.putShort(this.versionMadeBy);
        buffer.putShort(this.versionNeededToExtract);
        buffer.putShort(this.generalPurposeBitFlag);
        buffer.putShort(this.compressionMethod);
        buffer.putShort(this.lastModFileTime);
        buffer.putShort(this.lastModFileDate);
        buffer.putInt(this.crc32);
        buffer.putInt(this.compressedSize);
        buffer.putInt(this.uncompressedSize);
        buffer.putShort(this.fileNameLength);
        buffer.putShort(this.extraFieldLength);
        buffer.putShort(this.fileCommentLength);
        buffer.putShort(this.diskNumberStart);
        buffer.putShort(this.internalFileAttributes);
        buffer.putInt(this.externalFileAttributes);
        buffer.putInt(this.offsetToLocalHeader);
        return buffer.array();
    }

    static ZipCentralDirectoryFileHeaderRecord load(DataBlock dataBlock, long pos) throws IOException {
        debug.log("Loading CentralDirectoryFileHeaderRecord from position %s", pos);
        ByteBuffer buffer = ByteBuffer.allocate(46);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        dataBlock.readFully(buffer, pos);
        buffer.rewind();
        int signature = buffer.getInt();
        if (signature != 33639248) {
            debug.log("Found incorrect CentralDirectoryFileHeaderRecord signature %s at position %s", signature, pos);
            throw new IOException("Zip 'Central Directory File Header Record' not found at position " + pos);
        }
        return new ZipCentralDirectoryFileHeaderRecord(buffer.getShort(), buffer.getShort(), buffer.getShort(), buffer.getShort(), buffer.getShort(), buffer.getShort(), buffer.getInt(), buffer.getInt(), buffer.getInt(), buffer.getShort(), buffer.getShort(), buffer.getShort(), buffer.getShort(), buffer.getShort(), buffer.getInt(), buffer.getInt());
    }
}

