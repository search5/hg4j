package com.github.search5.hg4j.transport.wireprotov2;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * The unified frame used by real Mercurial's wire protocol version 2, matching
 * {@code mercurial/wireprotoframing.py} exactly (verified against Mercurial 6.0 — the last
 * release to ship a working wireprotocolv2 server/client; the protocol itself was removed from
 * Mercurial entirely starting with 6.1).
 *
 * <p>On-the-wire layout is 8 header bytes followed by the payload, all little-endian:</p>
 * <pre>
 *   bytes[0..3)  24-bit unsigned payload length
 *   bytes[3..5)  16-bit unsigned request id
 *   byte[5]      8-bit stream id
 *   byte[6]      8-bit stream flags
 *   byte[7]      high nibble = frame type, low nibble = type-specific flags
 * </pre>
 */
public final class Wire2Frame {
    public static final int HEADER_SIZE = 8;

    // Frame types (mercurial/wireprotoframing.py FRAME_TYPE_*)
    public static final int TYPE_COMMAND_REQUEST = 0x01;
    public static final int TYPE_COMMAND_DATA = 0x02;
    public static final int TYPE_COMMAND_RESPONSE = 0x03;
    public static final int TYPE_ERROR_RESPONSE = 0x05;
    public static final int TYPE_TEXT_OUTPUT = 0x06;
    public static final int TYPE_PROGRESS = 0x07;
    public static final int TYPE_SENDER_PROTOCOL_SETTINGS = 0x08;
    public static final int TYPE_STREAM_SETTINGS = 0x09;

    // Stream flags (STREAM_FLAG_*)
    public static final int STREAM_FLAG_BEGIN = 0x01;
    public static final int STREAM_FLAG_END = 0x02;
    public static final int STREAM_FLAG_ENCODING_APPLIED = 0x04;

    // COMMAND_REQUEST flags
    public static final int FLAG_COMMAND_REQUEST_NEW = 0x01;
    public static final int FLAG_COMMAND_REQUEST_CONTINUATION = 0x02;
    public static final int FLAG_COMMAND_REQUEST_MORE_FRAMES = 0x04;
    public static final int FLAG_COMMAND_REQUEST_EXPECT_DATA = 0x08;

    // COMMAND_RESPONSE flags
    public static final int FLAG_COMMAND_RESPONSE_CONTINUATION = 0x01;
    public static final int FLAG_COMMAND_RESPONSE_EOS = 0x02;

    // STREAM_SETTINGS flags
    public static final int FLAG_STREAM_SETTINGS_EOS = 0x02;

    public final int requestId;
    public final int streamId;
    public final int streamFlags;
    public final int typeId;
    public final int flags;
    public final byte[] payload;

    public Wire2Frame(int requestId, int streamId, int streamFlags, int typeId, int flags, byte[] payload) {
        this.requestId = requestId;
        this.streamId = streamId;
        this.streamFlags = streamFlags;
        this.typeId = typeId;
        this.flags = flags;
        this.payload = payload;
    }

    public boolean hasStreamFlag(int flag) {
        return (streamFlags & flag) != 0;
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    public byte[] encode() {
        int len = payload.length;
        byte[] out = new byte[HEADER_SIZE + len];
        out[0] = (byte) (len & 0xFF);
        out[1] = (byte) ((len >>> 8) & 0xFF);
        out[2] = (byte) ((len >>> 16) & 0xFF);
        out[3] = (byte) (requestId & 0xFF);
        out[4] = (byte) ((requestId >>> 8) & 0xFF);
        out[5] = (byte) streamId;
        out[6] = (byte) streamFlags;
        out[7] = (byte) (((typeId & 0x0F) << 4) | (flags & 0x0F));
        System.arraycopy(payload, 0, out, HEADER_SIZE, len);
        return out;
    }

    /**
     * Reads a single frame from the stream, or {@code null} at a clean EOF before any header
     * bytes are read (matching real hg's {@code readframe}, which treats EOF-with-no-data as
     * "no more frames" rather than an error).
     */
    public static Wire2Frame read(InputStream in) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        int off = 0;
        while (off < HEADER_SIZE) {
            int n = in.read(header, off, HEADER_SIZE - off);
            if (n == -1) {
                if (off == 0) {
                    return null;
                }
                throw new EOFException("Truncated wire protocol v2 frame header");
            }
            off += n;
        }
        int len = (header[0] & 0xFF) | ((header[1] & 0xFF) << 8) | ((header[2] & 0xFF) << 16);
        int requestId = (header[3] & 0xFF) | ((header[4] & 0xFF) << 8);
        int streamId = header[5] & 0xFF;
        int streamFlags = header[6] & 0xFF;
        int typeId = (header[7] & 0xFF) >>> 4;
        int flags = header[7] & 0x0F;

        byte[] payload = new byte[len];
        off = 0;
        while (off < len) {
            int n = in.read(payload, off, len - off);
            if (n == -1) {
                throw new EOFException("Truncated wire protocol v2 frame payload");
            }
            off += n;
        }
        return new Wire2Frame(requestId, streamId, streamFlags, typeId, flags, payload);
    }

    public void write(OutputStream out) throws IOException {
        out.write(encode());
    }

    public static byte[] concatenatePayloads(List<Wire2Frame> frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Wire2Frame f : frames) {
            out.write(f.payload);
        }
        return out.toByteArray();
    }
}
