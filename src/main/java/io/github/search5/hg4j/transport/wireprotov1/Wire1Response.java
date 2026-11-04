package io.github.search5.hg4j.transport.wireprotov1;

/**
 * Transport-agnostic result of a real hg wireprotocol v1 command, mirroring real hg's own
 * response kinds ({@code mercurial/wireprototypes.py}): {@code bytesresponse} (a single
 * length-prefixed blob — SSH framing is {@code <len>\n<bytes>}), {@code streamres} (a raw stream
 * with no length framing, HTTP-compressed — used for {@code changegroup}/{@code getbundle}/
 * {@code stream_out}), {@code streamreslegacy} (also a raw unframed stream on SSH, but sent
 * UNCOMPRESSED over HTTP unlike {@code streamres} — real hg's own {@code _callhttp} explicitly
 * treats only {@code streamres} as compressible, {@code streamreslegacy} gets the same plain
 * {@code bodygen=} treatment as {@code bytesresponse}; used for the bundle2 reply {@code
 * unbundle} sends back once it has itself received a bundle2-framed push, backlog item 26), and
 * {@code ooberror} (an out-of-band error).
 *
 * <p>Both the HTTP glue ({@code HgHttpWireServer}) and SSH glue ({@code HgSshWireServer}) consume
 * this same type and apply their own transport-specific framing on top of it.</p>
 */
public final class Wire1Response {
    /**
     * The kind of response, which decides how a transport frames it: a length-prefixed value ({@code BYTES}), a raw
     * stream ({@code STREAM}, which HTTP may compress), a raw stream that is never compressed
     * ({@code STREAM_UNCOMPRESSED}), an out-of-band error ({@code OOB_ERROR}), and the results of a bundle1
     * push ({@code PUSH_RESULT}, {@code PUSH_ERROR}).
     */
    public enum Kind { BYTES, STREAM, STREAM_UNCOMPRESSED, OOB_ERROR, PUSH_RESULT, PUSH_ERROR }

    private final Kind kind;
    private final byte[] payload;
    private final String errorMessage;
    private boolean preferUncompressed;
    private int pushResult;
    private String serverOutput = "";

    private Wire1Response(Kind kind, byte[] payload, String errorMessage) {
        this.kind = kind;
        this.payload = payload;
        this.errorMessage = errorMessage;
    }

    /** A single length-prefixed value response (real hg's {@code bytesresponse}). */
    public static Wire1Response bytes(byte[] payload) {
        return new Wire1Response(Kind.BYTES, payload, null);
    }

    /** A raw, unframed stream response (real hg's {@code streamres}) — e.g. a changegroup/bundle. */
    public static Wire1Response stream(byte[] payload) {
        return new Wire1Response(Kind.STREAM, payload, null);
    }

    /**
     * A compressible stream that the sender would rather not compress (real hg's {@code streamres(prefer_uncompressed=True)}, used
     * for a streaming clone whose files are already compressed): over HTTP media type 0.2 it is sent with the {@code none} engine.
     */
    public static Wire1Response streamPreferUncompressed(byte[] payload) {
        Wire1Response response = new Wire1Response(Kind.STREAM, payload, null);
        response.preferUncompressed = true;
        return response;
    }

    /**
     * Tells whether the sender would rather not compress this stream response; see {@link #streamPreferUncompressed(byte[])}.
     */
    public boolean isPreferUncompressed() {
        return preferUncompressed;
    }

    /**
     * Creates a raw, unframed stream response that must not be compressed over HTTP (real hg's
     * {@code streamreslegacy}), for example the bundle2 reply of {@code unbundle}.
     * @param payload the bytes to send
     * @return a response of kind {@link Kind#STREAM_UNCOMPRESSED}
     */
    public static Wire1Response streamUncompressed(byte[] payload) {
        return new Wire1Response(Kind.STREAM_UNCOMPRESSED, payload, null);
    }

    /**
     * Real hg's {@code pushres}: the result code of a bundle1 {@code unbundle} (the value {@code changegroup.apply} returns, 0 when
     * the push was refused). Over HTTP it is {@code "<res>\n<output>"}, over SSH an empty frame and then the code.
     */
    public static Wire1Response pushResult(int result) {
        return pushResult(result, "");
    }

    /** {@code pushres} with the text the server printed while applying the push (only an HTTP answer carries it). */
    public static Wire1Response pushResult(int result, String serverOutput) {
        Wire1Response response = new Wire1Response(Kind.PUSH_RESULT, new byte[0], null);
        response.pushResult = result;
        response.serverOutput = serverOutput;
        return response;
    }

    /**
     * Returns the text the server printed while applying a push; only an HTTP answer carries it. Empty unless set by
     * {@link #pushResult(int, String)}.
     */
    public String getServerOutput() {
        return serverOutput;
    }

    /** Real hg's {@code pusherr}: a bundle1 push refused with a message the client shows (a push race). */
    public static Wire1Response pushError(String message) {
        return new Wire1Response(Kind.PUSH_ERROR, message.getBytes(java.nio.charset.StandardCharsets.UTF_8), message);
    }

    /**
     * Returns the result code of a bundle1 push; meaningful for {@link Kind#PUSH_RESULT} responses only.
     */
    public int getPushResult() {
        return pushResult;
    }

    /**
     * Creates an out-of-band error response (real hg's {@code ooberror}).
     * @param message the error text
     * @return a response of kind {@link Kind#OOB_ERROR} with no payload
     */
    public static Wire1Response oobError(String message) {
        return new Wire1Response(Kind.OOB_ERROR, null, message);
    }

    /**
     * Returns the kind of this response.
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * Returns the response bytes. The internal array is returned, not a copy; it is null for an out-of-band error.
     */
    public byte[] getPayload() {
        return payload;
    }

    /**
     * Returns the error text of an out-of-band error or push error response; null for other kinds.
     */
    public String getErrorMessage() {
        return errorMessage;
    }
}
