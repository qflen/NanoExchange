package com.nanoexchange.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Binary append-only journal backed by a memory-mapped file. Every engine input event and
 * every emitted execution report is written here as an independent record; replaying the file
 * into a fresh engine produces a byte-identical output stream (given a deterministic engine).
 *
 * <h2>Record format</h2>
 * <pre>
 * offset size  field
 * ------ ----  -------------------------------------
 *      0    8  sequence number       (LE long)
 *      8    4  payload length N      (LE int)
 *     12    N  payload bytes
 *  12+N     4  CRC32 of bytes [0, 12+N)   (LE int)
 * </pre>
 * Total record size is {@code 16 + N} bytes. Multi-byte integers are little-endian; CRC covers
 * the sequence, length, and payload (i.e. everything in the record except the CRC itself).
 *
 * <h2>End-of-log detection</h2>
 * A record whose length field is zero, whose length would run past the end of the file, or
 * whose CRC does not verify is treated as end-of-log. Replay stops cleanly without throwing —
 * the tail of the mapped file is simply zeroed bytes from the initial file allocation.
 *
 * <p>Thread-safety: {@code append} is called from a single thread (the engine). {@code replay}
 * opens a separate view, so it can run concurrently with a live writer, but in this codebase
 * replay is used for restart / test determinism rather than live tailing.
 */
public final class Journal implements AutoCloseable {

    /** Fixed per-record header size (seq + length). */
    public static final int HEADER_BYTES = 12;
    /** Fixed per-record trailer size (CRC32). */
    public static final int TRAILER_BYTES = 4;
    /** Overhead per record beyond the payload bytes. */
    public static final int RECORD_OVERHEAD = HEADER_BYTES + TRAILER_BYTES;

    private final FileChannel channel;
    private final MappedByteBuffer region;
    private final long regionSize;
    private final CRC32 crc = new CRC32();
    private int writeOffset;

    /**
     * Open or create a journal file at {@code path}, mapping {@code size} bytes of it into
     * memory. If the file exists and is already at least that size, its contents are retained
     * and the write offset advances past any valid tail record. Otherwise the file is
     * (re)sized and the write offset starts at zero.
     */
    public Journal(Path path, long size) throws IOException {
        if (size <= RECORD_OVERHEAD) {
            throw new IllegalArgumentException("journal size too small");
        }
        this.channel = FileChannel.open(
                path,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE));
        if (channel.size() < size) {
            channel.truncate(size);
        }
        this.region = channel.map(FileChannel.MapMode.READ_WRITE, 0, size);
        this.region.order(ByteOrder.LITTLE_ENDIAN);
        this.regionSize = size;
        this.writeOffset = scanForTail();
    }

    /**
     * Append a record. The payload buffer's position advances by the bytes consumed; its
     * limit and byte order are unchanged on return.
     *
     * @throws IllegalStateException if the journal has no room for this record
     */
    public void append(long sequence, ByteBuffer payload) {
        int payloadLen = payload.remaining();
        int recordLen = RECORD_OVERHEAD + payloadLen;
        if (writeOffset + recordLen > regionSize) {
            throw new IllegalStateException(
                    "journal exhausted at offset " + writeOffset
                    + " (need " + recordLen + ", have " + (regionSize - writeOffset) + ")");
        }

        int recordStart = writeOffset;
        region.putLong(recordStart, sequence);
        region.putInt(recordStart + 8, payloadLen);

        // copy payload byte-by-byte using bulk put via duplicate + position
        int payloadStart = recordStart + HEADER_BYTES;
        for (int i = 0; i < payloadLen; i++) {
            region.put(payloadStart + i, payload.get());
        }

        crc.reset();
        for (int i = recordStart; i < payloadStart + payloadLen; i++) {
            crc.update(region.get(i));
        }
        int crcValue = (int) crc.getValue();
        region.putInt(payloadStart + payloadLen, crcValue);

        writeOffset += recordLen;
    }

    /** Flush pending writes to disk. Expensive — call sparingly. */
    public void force() {
        region.force();
    }

    /** Current write offset into the mapped region. */
    public int writeOffset() {
        return writeOffset;
    }

    @Override
    public void close() throws IOException {
        region.force();
        channel.close();
    }

    /**
     * Replay every valid record from a journal file into {@code visitor}, in order. Stops on
     * first malformed / unterminated record (normal end-of-log). The supplied buffer passed to
     * the visitor is a read-only stage and is valid only for the duration of the callback.
     */
    public static void replay(Path path, RecordVisitor visitor) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = ch.size();
            MappedByteBuffer view = ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
            view.order(ByteOrder.LITTLE_ENDIAN);
            CRC32 localCrc = new CRC32();
            int offset = 0;
            while (offset + HEADER_BYTES <= size) {
                long seq = view.getLong(offset);
                int len = view.getInt(offset + 8);
                if (len <= 0) break;
                int trailerOffset = offset + HEADER_BYTES + len;
                if (trailerOffset + TRAILER_BYTES > size) break;

                localCrc.reset();
                for (int i = offset; i < trailerOffset; i++) {
                    localCrc.update(view.get(i));
                }
                int expected = view.getInt(trailerOffset);
                if (expected != (int) localCrc.getValue()) break;

                ByteBuffer payload = view.slice(offset + HEADER_BYTES, len).asReadOnlyBuffer();
                payload.order(ByteOrder.LITTLE_ENDIAN);
                visitor.onRecord(seq, payload);

                offset = trailerOffset + TRAILER_BYTES;
            }
        }
    }

    private int scanForTail() {
        CRC32 localCrc = new CRC32();
        int offset = 0;
        while (offset + HEADER_BYTES <= regionSize) {
            long seq = region.getLong(offset);
            int len = region.getInt(offset + 8);
            if (len <= 0) break;
            int trailerOffset = offset + HEADER_BYTES + len;
            if (trailerOffset + TRAILER_BYTES > regionSize) break;

            localCrc.reset();
            for (int i = offset; i < trailerOffset; i++) {
                localCrc.update(region.get(i));
            }
            int expected = region.getInt(trailerOffset);
            if (expected != (int) localCrc.getValue()) break;
            // seq is only read so the compiler doesn't elide it on some paths
            if (seq == Long.MIN_VALUE) break;
            offset = trailerOffset + TRAILER_BYTES;
        }
        return offset;
    }

    /** Callback invoked by {@link #replay} for each intact record. */
    @FunctionalInterface
    public interface RecordVisitor {
        void onRecord(long sequence, ByteBuffer payload);
    }
}
