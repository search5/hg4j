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
 * unbundle} sends back once it has itself received a bundle2-framed push), and
 * {@code ooberror} (an out-of-band error).
 *
 * <p>Both the HTTP glue ({@code HgHttpWireServer}) and SSH glue ({@code HgSshWireServer}) consume
 * this same type and apply their own transport-specific framing on top of it.</p>
 */
public final class Wire1Response {
    /** The wireprotocol v1 response shape, mirroring real hg's response kinds documented above. */
    public enum Kind {
        /** A single length-prefixed value response (real hg's {@code bytesresponse}). */
        BYTES,
        /** A raw, unframed, HTTP-compressible stream response (real hg's {@code streamres}). */
        STREAM,
        /** A raw, unframed stream response that must NOT be HTTP-compressed (real hg's {@code streamreslegacy}). */
        STREAM_UNCOMPRESSED,
        /** An out-of-band error (real hg's {@code ooberror}). */
        OOB_ERROR
    }

    private final Kind kind;
    private final byte[] payload;
    private final String errorMessage;

    private Wire1Response(Kind kind, byte[] payload, String errorMessage) {
        this.kind = kind;
        this.payload = payload;
        this.errorMessage = errorMessage;
    }

    /**
     * A single length-prefixed value response (real hg's {@code bytesresponse}).
     *
     * @param payload the response bytes
     * @return a {@link Kind#BYTES} response wrapping {@code payload}
     */
    public static Wire1Response bytes(byte[] payload) {
        return new Wire1Response(Kind.BYTES, payload, null);
    }

    /**
     * A raw, unframed stream response (real hg's {@code streamres}) — e.g. a changegroup/bundle.
     *
     * @param payload the raw stream bytes
     * @return a {@link Kind#STREAM} response wrapping {@code payload}
     */
    public static Wire1Response stream(byte[] payload) {
        return new Wire1Response(Kind.STREAM, payload, null);
    }

    /** A raw, unframed stream response that must NOT be HTTP-compressed (real hg's {@code
     * streamreslegacy}) — e.g. a bundle2 reply envelope for {@code unbundle}.
     *
     * @param payload the raw stream bytes
     * @return a {@link Kind#STREAM_UNCOMPRESSED} response wrapping {@code payload}
     */
    public static Wire1Response streamUncompressed(byte[] payload) {
        return new Wire1Response(Kind.STREAM_UNCOMPRESSED, payload, null);
    }

    /**
     * An out-of-band error (real hg's {@code ooberror}).
     *
     * @param message the error message to report to the client
     * @return a {@link Kind#OOB_ERROR} response wrapping {@code message}
     */
    public static Wire1Response oobError(String message) {
        return new Wire1Response(Kind.OOB_ERROR, null, message);
    }

    /**
     * Returns which of the four response shapes this instance represents.
     *
     * @return this response's {@link Kind}
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * Returns the response payload bytes, or {@code null} for a {@link Kind#OOB_ERROR} response.
     *
     * @return the response payload, or {@code null} for an out-of-band error response
     */
    public byte[] getPayload() {
        return payload;
    }

    /**
     * Returns the out-of-band error message, or {@code null} unless {@link #getKind()} is {@link Kind#OOB_ERROR}.
     *
     * @return the out-of-band error message, or {@code null} for a non-error response
     */
    public String getErrorMessage() {
        return errorMessage;
    }
}
