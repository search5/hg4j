package com.github.search5.hg4j.transport.wireprotov2;

import com.github.search5.hg4j.errors.HgProtocolException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Shared frame-assembly/disassembly logic for real hg's wireprotocol v2, used by both the client
 * ({@link com.github.search5.hg4j.transport.HgRemoteClientV2}) and the server side of
 * {@link com.github.search5.hg4j.transport.HgHttpWireServer}. Reproduces the relevant slice of real
 * hg's {@code mercurial/wireprotoframing.py} — the command-request/response framing, the
 * mandatory identity stream-settings declaration, and the {@code {status: ok|error}} response
 * envelope — verified against a real Mercurial 6.0 server (the last release with a working v2
 * implementation; the protocol was removed entirely in 6.1).
 */
public final class Wire2Transport {
    public static final String FRAMINGTYPE = "application/mercurial-exp-framing-0006";

    private Wire2Transport() {
    }

    /**
     * Builds the request body for a single command: one COMMAND_REQUEST frame carrying
     * {@code {name: <command>, args: {...}}} as CBOR. No sender-protocol-settings frame is sent,
     * which per real hg's {@code DEFAULT_PROTOCOL_SETTINGS} guarantees the server replies using
     * plain "identity" (uncompressed) stream encoding — avoiding the need to implement zstd/zlib
     * frame-stream compression on the client.
     */
    public static byte[] buildCommandRequest(int requestId, String command, Map<String, Object> args) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", command);
        if (args != null && !args.isEmpty()) {
            data.put("args", args);
        }
        byte[] payload = Cbor.encode(data);
        Wire2Frame frame = new Wire2Frame(requestId, 1, Wire2Frame.STREAM_FLAG_BEGIN,
                Wire2Frame.TYPE_COMMAND_REQUEST, Wire2Frame.FLAG_COMMAND_REQUEST_NEW, payload);
        return frame.encode();
    }

    /**
     * Parses every command-request out of a request body: real hg's default client always sends
     * a {@code SENDER_PROTOCOL_SETTINGS} frame first (declaring its preferred compression
     * encoders, which this server ignores — it only ever replies "identity"), and to a
     * {@code multirequest} URL sends <em>several</em> independent COMMAND_REQUEST frame
     * sequences batched into one HTTP POST (observed directly against a real Mercurial 6.0
     * client: an initial clone batches {@code heads} and {@code known} together this way). Each
     * command keeps its own request id, which the response must echo back so the client can
     * correlate replies.
     */
    public static List<ParsedCommandRequest> readAllCommandRequests(InputStream in) throws IOException {
        List<ParsedCommandRequest> result = new ArrayList<>();
        while (true) {
            Wire2Frame frame = Wire2Frame.read(in);
            if (frame == null) {
                break;
            }
            if (frame.typeId == Wire2Frame.TYPE_SENDER_PROTOCOL_SETTINGS) {
                continue; // encoder preferences are ignored; this server always replies identity
            }
            if (frame.typeId != Wire2Frame.TYPE_COMMAND_REQUEST) {
                throw new HgProtocolException("wireprotov2", "Expected COMMAND_REQUEST frame, got type " + frame.typeId);
            }
            int requestId = frame.requestId;
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.write(frame.payload);
            while (frame.hasFlag(Wire2Frame.FLAG_COMMAND_REQUEST_MORE_FRAMES)) {
                frame = Wire2Frame.read(in);
                if (frame == null) {
                    throw new HgProtocolException("wireprotov2", "Unexpected EOF while reading a continued command-request");
                }
                payload.write(frame.payload);
            }
            List<Object> objs = Cbor.decodeAll(payload.toByteArray());
            Map<String, Object> data = Cbor.asMap(objs.isEmpty() ? null : objs.get(0));
            String name = Cbor.asString(data.get("name"));
            Map<String, Object> args = Cbor.asMap(data.get("args"));
            result.add(new ParsedCommandRequest(requestId, name, args));
        }
        return result;
    }

    /** The identity stream-settings frame that must open a response stream exactly once. */
    public static byte[] buildStreamSettingsFrame(int requestId) {
        byte[] streamSettingsPayload = Cbor.encode("identity");
        return new Wire2Frame(requestId, 2, Wire2Frame.STREAM_FLAG_BEGIN, Wire2Frame.TYPE_STREAM_SETTINGS,
                Wire2Frame.FLAG_STREAM_SETTINGS_EOS, streamSettingsPayload).encode();
    }

    /**
     * Builds just one command's {@code {status: ok}} + response-object frames, without a
     * stream-settings frame and without the stream-begin flag — for use after
     * {@link #buildStreamSettingsFrame} has already opened the response stream.
     */
    public static byte[] buildCommandResponseFrames(int requestId, List<Object> responseObjects) throws IOException {
        List<Object> all = new ArrayList<>();
        all.add(statusOk());
        all.addAll(responseObjects);
        byte[] body = Cbor.encodeAll(all);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChunked(out, requestId, body, Wire2Frame.STREAM_FLAG_ENCODING_APPLIED);
        return out.toByteArray();
    }

    /**
     * Builds a command-level error response: a single {@code {status:"error", error:{...}}}
     * frame. Does not set the stream-begin flag — {@link com.github.search5.hg4j.transport.HgHttpWireServer}, the only caller, always
     * sends {@link #buildStreamSettingsFrame} first regardless of whether the command that
     * follows succeeds or fails.
     */
    public static byte[] buildCommandErrorResponse(int requestId, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("error", error);
        byte[] payload = Cbor.encode(body);
        Wire2Frame frame = new Wire2Frame(requestId, 2, 0,
                Wire2Frame.TYPE_COMMAND_RESPONSE, Wire2Frame.FLAG_COMMAND_RESPONSE_EOS, payload);
        return frame.encode();
    }

    private static void writeChunked(ByteArrayOutputStream out, int requestId, byte[] body, int streamFlags) throws IOException {
        int maxFrameSize = 32768;
        int offset = 0;
        if (body.length == 0) {
            new Wire2Frame(requestId, 2, streamFlags, Wire2Frame.TYPE_COMMAND_RESPONSE,
                    Wire2Frame.FLAG_COMMAND_RESPONSE_EOS, new byte[0]).write(out);
            return;
        }
        boolean first = true;
        while (offset < body.length) {
            int end = Math.min(offset + maxFrameSize, body.length);
            byte[] chunk = new byte[end - offset];
            System.arraycopy(body, offset, chunk, 0, chunk.length);
            offset = end;
            boolean done = offset == body.length;
            int flags = done ? Wire2Frame.FLAG_COMMAND_RESPONSE_EOS : Wire2Frame.FLAG_COMMAND_RESPONSE_CONTINUATION;
            int sf = first ? streamFlags : (streamFlags & ~Wire2Frame.STREAM_FLAG_BEGIN);
            new Wire2Frame(requestId, 2, sf, Wire2Frame.TYPE_COMMAND_RESPONSE, flags, chunk).write(out);
            first = false;
        }
    }

    /**
     * Reads and decodes a full command response: consumes frames from {@code in} until EOS,
     * concatenates every COMMAND_RESPONSE frame's payload (STREAM_SETTINGS/other frame types are
     * skipped — this client never declares extra content encodings, so payloads are always
     * "identity" even when the "encoded" stream flag is set), CBOR-decodes the resulting byte
     * stream into its constituent objects, and validates the {@code {status: ...}} envelope.
     *
     * @return the response objects that followed the status envelope
     * @throws HgProtocolException if the server reported {@code status: error}
     */
    public static List<Object> readCommandResponse(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            Wire2Frame frame = Wire2Frame.read(in);
            if (frame == null) {
                break;
            }
            if (frame.typeId == Wire2Frame.TYPE_COMMAND_RESPONSE) {
                body.write(frame.payload);
                if (frame.hasFlag(Wire2Frame.FLAG_COMMAND_RESPONSE_EOS)) {
                    break;
                }
            } else if (frame.typeId == Wire2Frame.TYPE_ERROR_RESPONSE) {
                throw new HgProtocolException("wireprotov2", "Protocol-level error: " + new String(frame.payload, StandardCharsets.UTF_8));
            }
            // STREAM_SETTINGS / SENDER_PROTOCOL_SETTINGS / TEXT_OUTPUT / PROGRESS frames: ignored.
        }

        List<Object> objs = Cbor.decodeAll(body.toByteArray());
        if (objs.isEmpty()) {
            return objs;
        }
        Map<String, Object> status = Cbor.asMap(objs.get(0));
        String statusValue = Cbor.asString(status.get("status"));
        if ("error".equals(statusValue)) {
            Map<String, Object> error = Cbor.asMap(status.get("error"));
            throw new HgProtocolException("wireprotov2", "Remote command error: " + Cbor.asString(error.get("message")));
        }
        return objs.subList(1, objs.size());
    }

    private static Map<String, Object> statusOk() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        return m;
    }

    public static InputStream toStream(byte[] data) {
        return new ByteArrayInputStream(data);
    }

    /**
     * Decodes the "record possibly followed by raw byte objects" convention shared by
     * {@code changesetdata}/{@code manifestdata}/{@code filesdata} responses (see
     * {@code wireprotov2server.py}'s {@code emitfilerevisions}): each metadata map that declares
     * a non-empty {@code fieldsfollowing} list of {@code [name, length]} pairs is immediately
     * followed, in the object stream, by one raw byte-string object per declared field — this
     * merges each of those back into the record under its field name.
     *
     * @param objects       the command's response objects (with the leading header record(s)
     *                      already skipped by the caller)
     * @param isHeaderEntry predicate identifying an entry that is itself a header/marker record
     *                      (e.g. filesdata's per-path {@code {path, totalitems}} banner) rather
     *                      than a data record — such entries are returned as-is, with no
     *                      following-bytes merge attempted
     */
    public static List<Map<String, Object>> decodeRecordsWithFollowing(
            List<Object> objects, Predicate<Map<String, Object>> isHeaderEntry) {
        List<Map<String, Object>> result = new ArrayList<>();
        int i = 0;
        while (i < objects.size()) {
            Map<String, Object> record = new LinkedHashMap<>(Cbor.asMap(objects.get(i)));
            i++;
            if (isHeaderEntry != null && isHeaderEntry.test(record)) {
                result.add(record);
                continue;
            }
            for (Object f : Cbor.asList(record.get("fieldsfollowing"))) {
                List<Object> pair = Cbor.asList(f);
                String fieldName = Cbor.asString(pair.get(0));
                byte[] data = Cbor.asBytes(objects.get(i));
                record.put(fieldName, data);
                i++;
            }
            result.add(record);
        }
        return result;
    }

    public static final class ParsedCommandRequest {
        public final int requestId;
        public final String name;
        public final Map<String, Object> args;

        public ParsedCommandRequest(int requestId, String name, Map<String, Object> args) {
            this.requestId = requestId;
            this.name = name;
            this.args = args;
        }
    }
}
