package com.nanoexchange.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class JournalTest {

    private static ByteBuffer bytes(int... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) b.put((byte) v);
        b.flip();
        return b;
    }

    private record Record(long seq, byte[] payload) { }

    private static List<Record> replay(Path path) throws IOException {
        List<Record> out = new ArrayList<>();
        Journal.replay(path, (seq, payload) -> {
            byte[] copy = new byte[payload.remaining()];
            payload.get(copy);
            out.add(new Record(seq, copy));
        });
        return out;
    }

    @Test
    void appendAndReplayRoundTrip(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("j1.bin");
        try (Journal j = new Journal(file, 4096)) {
            j.append(1L, bytes(0xAA, 0xBB, 0xCC));
            j.append(2L, bytes(0x01, 0x02, 0x03, 0x04, 0x05));
            j.append(3L, bytes(0xFF));
            j.force();
        }

        List<Record> records = replay(file);
        assertThat(records).hasSize(3);
        assertThat(records.get(0).seq).isEqualTo(1L);
        assertThat(records.get(0).payload).containsExactly((byte) 0xAA, (byte) 0xBB, (byte) 0xCC);
        assertThat(records.get(1).seq).isEqualTo(2L);
        assertThat(records.get(1).payload).containsExactly(
                (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05);
        assertThat(records.get(2).seq).isEqualTo(3L);
        assertThat(records.get(2).payload).containsExactly((byte) 0xFF);
    }

    @Test
    void reopenResumesPastExistingTail(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("j2.bin");
        try (Journal j = new Journal(file, 4096)) {
            j.append(10L, bytes(1, 2, 3));
            j.append(11L, bytes(4, 5));
            j.force();
        }
        int firstRoundOffset;
        try (Journal j = new Journal(file, 4096)) {
            firstRoundOffset = j.writeOffset();
            // two records: (16+3) + (16+2) = 37 bytes
            assertThat(firstRoundOffset).isEqualTo(37);
            j.append(12L, bytes(6));
            j.force();
        }

        List<Record> records = replay(file);
        assertThat(records).hasSize(3);
        assertThat(records.get(0).seq).isEqualTo(10L);
        assertThat(records.get(1).seq).isEqualTo(11L);
        assertThat(records.get(2).seq).isEqualTo(12L);
        assertThat(records.get(2).payload).containsExactly((byte) 6);
    }

    @Test
    void replayStopsAtCrcMismatch(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("j3.bin");
        try (Journal j = new Journal(file, 4096)) {
            j.append(1L, bytes(0x11, 0x22));
            j.append(2L, bytes(0x33, 0x44));
            j.append(3L, bytes(0x55, 0x66));
            j.force();
        }
        // Corrupt one byte inside the second record's payload (offset 12 + 18 = position 30).
        // First record occupies [0, 18). Second record starts at 18 and its payload begins at 30.
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(30);
            raf.writeByte(0x99);
        }

        List<Record> records = replay(file);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).seq).isEqualTo(1L);
    }

    @Test
    void replayStopsAtZeroLength(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("j4.bin");
        try (Journal j = new Journal(file, 4096)) {
            j.append(5L, bytes(9, 9, 9));
            j.force();
        }
        List<Record> records = replay(file);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).seq).isEqualTo(5L);
    }

    @Test
    void replayStopsAtTruncatedTail(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("j5.bin");
        try (Journal j = new Journal(file, 4096)) {
            j.append(1L, bytes(1, 2, 3));
            j.append(2L, bytes(4, 5, 6));
            j.force();
        }
        // Truncate mid-second-record.
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(22);
        }
        List<Record> records = replay(file);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).seq).isEqualTo(1L);
    }

    @Test
    void byteIdenticalReplayAfterReopen(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a.bin");
        Path b = dir.resolve("b.bin");

        long[] seqs = new long[200];
        byte[][] payloads = new byte[200][];
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < 200; i++) {
            seqs[i] = 1_000L + i;
            payloads[i] = new byte[1 + rng.nextInt(64)];
            rng.nextBytes(payloads[i]);
        }

        try (Journal j = new Journal(a, 1 << 16)) {
            for (int i = 0; i < 200; i++) {
                j.append(seqs[i], ByteBuffer.wrap(payloads[i]));
            }
            j.force();
        }

        // Append the same records in two sessions (split in half) to b.
        try (Journal j = new Journal(b, 1 << 16)) {
            for (int i = 0; i < 100; i++) {
                j.append(seqs[i], ByteBuffer.wrap(payloads[i]));
            }
            j.force();
        }
        try (Journal j = new Journal(b, 1 << 16)) {
            for (int i = 100; i < 200; i++) {
                j.append(seqs[i], ByteBuffer.wrap(payloads[i]));
            }
            j.force();
        }

        List<Record> ra = replay(a);
        List<Record> rb = replay(b);
        assertThat(ra).hasSize(200);
        assertThat(rb).hasSize(200);
        for (int i = 0; i < 200; i++) {
            assertThat(rb.get(i).seq).isEqualTo(ra.get(i).seq);
            assertThat(rb.get(i).payload).containsExactly(ra.get(i).payload);
        }
    }

    @Test
    void appendThrowsWhenExhausted(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("j6.bin");
        try (Journal j = new Journal(file, 64)) {
            j.append(1L, bytes(1, 2, 3, 4, 5, 6, 7, 8)); // 16 + 8 = 24
            j.append(2L, bytes(9, 10, 11, 12, 13, 14, 15, 16)); // 24, total 48
            assertThatThrownBy(() -> j.append(3L, bytes(17, 18, 19, 20, 21, 22, 23, 24)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void constructorRejectsTinySize(@TempDir Path dir) {
        Path file = dir.resolve("tiny.bin");
        assertThatThrownBy(() -> new Journal(file, 8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payloadBufferPositionAdvances(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("j7.bin");
        ByteBuffer b = bytes(1, 2, 3, 4, 5);
        try (Journal j = new Journal(file, 256)) {
            j.append(1L, b);
        }
        assertThat(b.remaining()).isEqualTo(0);
    }
}
