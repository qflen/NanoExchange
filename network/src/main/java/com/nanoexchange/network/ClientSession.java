package com.nanoexchange.network;

import com.nanoexchange.network.protocol.WireCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;

/**
 * Per-connection I/O state for the order-entry gateway. Owns pre-allocated read and write
 * buffers (no per-event allocation on the hot path) and handles partial reads and writes that
 * a non-blocking TCP socket will naturally produce.
 *
 * <p>Not thread-safe — a session is only ever touched from the selector thread that owns its
 * {@link java.nio.channels.SelectionKey}.
 */
public final class ClientSession {

    /**
     * Default per-session buffer size. A 100k-order simulator burst produces ~100k ACKs
     * plus fills at ~50 bytes each; 1 MiB lets the engine outrun the socket for the
     * length of a burst without blocking. {@link OrderGateway#send} flushes inline
     * when this fills, so it caps memory without risking crashes.
     */
    public static final int DEFAULT_BUFFER_SIZE = 1 * 1024 * 1024;

    private final SocketChannel channel;
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;
    private final long clientId;
    private boolean closed;

    public ClientSession(SocketChannel channel, long clientId) {
        this(channel, clientId, DEFAULT_BUFFER_SIZE);
    }

    public ClientSession(SocketChannel channel, long clientId, int bufferSize) {
        this.channel = channel;
        this.clientId = clientId;
        this.readBuffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.LITTLE_ENDIAN);
        this.writeBuffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.LITTLE_ENDIAN);
    }

    public SocketChannel channel() { return channel; }
    public long clientId() { return clientId; }
    public boolean isClosed() { return closed; }

    /** The read buffer. Kept in "accumulate" mode (position = write index, limit = capacity). */
    public ByteBuffer readBuffer() { return readBuffer; }

    /** The write buffer. Kept in "drain" mode when flushing. */
    public ByteBuffer writeBuffer() { return writeBuffer; }

    /**
     * Pull bytes from the socket into {@link #readBuffer}. Returns the number of bytes read,
     * or -1 on end-of-stream. The buffer stays in accumulate mode.
     */
    public int readFromSocket() throws IOException {
        return channel.read(readBuffer);
    }

    /**
     * After parsing one or more frames out of the accumulated read buffer, compact the
     * consumed bytes away. Call once per read event, after as many {@link WireCodec#tryDecode}
     * attempts as succeeded.
     */
    public void compactAfterDecode() {
        readBuffer.compact();
    }

    /**
     * Prepare the read buffer for decoding by flipping it to read-from-start-to-position mode.
     * After the call, position = 0 and limit = previous position.
     */
    public void prepareDecode() {
        readBuffer.flip();
    }

    /**
     * Append a fully-encoded frame to the outgoing write buffer. Throws if the write buffer
     * does not have room — caller must flush first via {@link #flushWrite()}.
     */
    public void queueOutbound(ByteBuffer encoded) {
        if (encoded.remaining() > writeBuffer.remaining()) {
            throw new IllegalStateException("write buffer full; flush first");
        }
        writeBuffer.put(encoded);
    }

    /**
     * Drain the write buffer to the socket. Returns true if everything was written, false if
     * the socket accepted only some bytes and the caller should register interest in OP_WRITE
     * and retry on the next writable event.
     */
    public boolean flushWrite() throws IOException {
        writeBuffer.flip();
        try {
            while (writeBuffer.hasRemaining()) {
                int n = channel.write(writeBuffer);
                if (n == 0) {
                    // would block — keep leftover for next flush
                    writeBuffer.compact();
                    return false;
                }
            }
            writeBuffer.clear();
            return true;
        } catch (IOException e) {
            writeBuffer.clear();
            throw e;
        }
    }

    public void close() {
        closed = true;
        try { channel.close(); } catch (IOException ignored) { }
    }
}
