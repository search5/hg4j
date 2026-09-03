package io.github.search5.hg4j.transport.wireprotov1;

/**
 * Transport-agnostic result of a real hg wireprotocol v1 command, mirroring real hg's own
 * three response kinds ({@code mercurial/wireprototypes.py}): {@code bytesresponse} (a single
 * length-prefixed blob — SSH framing is {@code <len>\n<bytes>}), {@code streamres}/{@code
 * streamreslegacy} (a raw stream with no length framing — used for {@code changegroup}/{@code
 * getbundle}/{@code stream_out}), and {@code ooberror} (an out-of-band error).
 *
 * <p>Both the HTTP glue ({@code HgHttpWireServer}) and SSH glue ({@code HgSshWireServer}) consume
 * this same type and apply their own transport-specific framing on top of it.</p>
 */
public final class Wire1Response {
    public enum Kind { BYTES, STREAM, OOB_ERROR }

    private final Kind kind;
    private final byte[] payload;
    private final String errorMessage;

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

    /** An out-of-band error (real hg's {@code ooberror}). */
    public static Wire1Response oobError(String message) {
        return new Wire1Response(Kind.OOB_ERROR, null, message);
    }

    public Kind getKind() {
        return kind;
    }

    public byte[] getPayload() {
        return payload;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
