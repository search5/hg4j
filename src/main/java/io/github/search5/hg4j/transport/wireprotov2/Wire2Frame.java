package io.github.search5.hg4j.transport.wireprotov2;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * The unified frame used by real Mercurial's wire protocol version 2, matching
 * {@code mercurial/wireprotoframing.py} exactly. Targets Mercurial 6.0 — the last
 * release to ship a working wireprotocolv2 server/client; the protocol itself was removed from
 * Mercurial entirely starting with 6.1.
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
    /** Size in bytes of the fixed frame header that precedes the payload. */
    public static final int HEADER_SIZE = 8;

    // Frame types (mercurial/wireprotoframing.py FRAME_TYPE_*)
    /** Frame type: a client command request. */
    public static final int TYPE_COMMAND_REQUEST = 0x01;
    /** Frame type: additional data associated with a command request. */
    public static final int TYPE_COMMAND_DATA = 0x02;
    /** Frame type: a server response to a command request. */
    public static final int TYPE_COMMAND_RESPONSE = 0x03;
    /** Frame type: an error response terminating a command. */
    public static final int TYPE_ERROR_RESPONSE = 0x05;
    /** Frame type: human-readable text output (e.g. progress/status messages) accompanying a response. */
    public static final int TYPE_TEXT_OUTPUT = 0x06;
    /** Frame type: a progress update accompanying a response. */
    public static final int TYPE_PROGRESS = 0x07;
    /** Frame type: sender protocol settings negotiated at the start of a session. */
    public static final int TYPE_SENDER_PROTOCOL_SETTINGS = 0x08;
    /** Frame type: settings describing the encoding applied to a stream. */
    public static final int TYPE_STREAM_SETTINGS = 0x09;

    // Stream flags (STREAM_FLAG_*)
    /** Stream flag: this frame is the first frame of its stream. */
    public static final int STREAM_FLAG_BEGIN = 0x01;
    /** Stream flag: this frame is the last frame of its stream. */
    public static final int STREAM_FLAG_END = 0x02;
    /** Stream flag: an encoding (e.g. compression) has been applied to the stream's payloads. */
    public static final int STREAM_FLAG_ENCODING_APPLIED = 0x04;

    // COMMAND_REQUEST flags
    /** {@code COMMAND_REQUEST} flag: this frame starts a new command. */
    public static final int FLAG_COMMAND_REQUEST_NEW = 0x01;
    /** {@code COMMAND_REQUEST} flag: this frame continues a previously started command. */
    public static final int FLAG_COMMAND_REQUEST_CONTINUATION = 0x02;
    /** {@code COMMAND_REQUEST} flag: more frames follow for this command. */
    public static final int FLAG_COMMAND_REQUEST_MORE_FRAMES = 0x04;
    /** {@code COMMAND_REQUEST} flag: the command expects a data frame to follow. */
    public static final int FLAG_COMMAND_REQUEST_EXPECT_DATA = 0x08;

    // COMMAND_RESPONSE flags
    /** {@code COMMAND_RESPONSE} flag: more response frames follow for this command. */
    public static final int FLAG_COMMAND_RESPONSE_CONTINUATION = 0x01;
    /** {@code COMMAND_RESPONSE} flag: this is the final frame of the command response. */
    public static final int FLAG_COMMAND_RESPONSE_EOS = 0x02;

    // STREAM_SETTINGS flags
    /** {@code STREAM_SETTINGS} flag: this is the final settings frame for the stream. */
    public static final int FLAG_STREAM_SETTINGS_EOS = 0x02;

    /** Identifier correlating request and response frames belonging to the same command. */
    public final int requestId;
    /** Identifier of the logical stream this frame belongs to. */
    public final int streamId;
    /** Bitmask of {@code STREAM_FLAG_*} values describing this frame's position within its stream. */
    public final int streamFlags;
    /** Frame type, one of the {@code TYPE_*} constants. */
    public final int typeId;
    /** Bitmask of type-specific flags, interpreted according to {@link #typeId}. */
    public final int flags;
    /** Raw frame payload bytes, excluding the header. */
    public final byte[] payload;

    /**
     * Creates a frame from its already-decoded header fields and payload.
     *
     * @param requestId   identifier correlating this frame with others belonging to the same command
     * @param streamId    identifier of the logical stream this frame belongs to
     * @param streamFlags bitmask of {@code STREAM_FLAG_*} values
     * @param typeId      frame type, one of the {@code TYPE_*} constants
     * @param flags       bitmask of type-specific flags interpreted according to {@code typeId}
     * @param payload     raw payload bytes
     */
    public Wire2Frame(int requestId, int streamId, int streamFlags, int typeId, int flags, byte[] payload) {
        this.requestId = requestId;
        this.streamId = streamId;
        this.streamFlags = streamFlags;
        this.typeId = typeId;
        this.flags = flags;
        this.payload = payload;
    }

    /**
     * Tests whether the given {@code STREAM_FLAG_*} bit is set in {@link #streamFlags}.
     *
     * @param flag one of the {@code STREAM_FLAG_*} constants
     * @return {@code true} if the bit is set
     */
    public boolean hasStreamFlag(int flag) {
        return (streamFlags & flag) != 0;
    }

    /**
     * Tests whether the given type-specific flag bit is set in {@link #flags}.
     *
     * @param flag one of the type-specific {@code FLAG_*} constants matching this frame's {@link #typeId}
     * @return {@code true} if the bit is set
     */
    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    /**
     * Serializes this frame to its on-the-wire byte representation: the 8-byte header described
     * in the class documentation, followed by the payload.
     *
     * @return the encoded frame bytes, ready to be written to a transport stream
     */
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
     *
     * @param in the stream to read a single frame from, positioned at the start of a frame header
     *           (or at end-of-stream)
     * @return the decoded frame, or {@code null} at a clean EOF before any header bytes are read
     * @throws IOException if the header or payload is truncated, or the underlying stream fails
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

    /**
     * Encodes this frame and writes it to the given stream.
     *
     * @param out the stream to write the encoded frame to
     * @throws IOException if the underlying stream fails
     */
    public void write(OutputStream out) throws IOException {
        out.write(encode());
    }

    /**
     * Concatenates the payloads of a sequence of frames, in order, into a single byte array.
     * Used by callers that reassemble a multi-frame command response or data stream back into
     * one contiguous byte sequence.
     *
     * @param frames the frames whose payloads are concatenated, in order
     * @return the concatenated payload bytes
     * @throws IOException if writing to the intermediate buffer fails
     */
    public static byte[] concatenatePayloads(List<Wire2Frame> frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Wire2Frame f : frames) {
            out.write(f.payload);
        }
        return out.toByteArray();
    }
}
