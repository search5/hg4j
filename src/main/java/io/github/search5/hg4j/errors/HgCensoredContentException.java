package io.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * Thrown when reading the content of a revision that has been censored ({@code hg censor}) —
 * mirrors real hg's default behavior of raising {@code error.CensoredNodeError} instead of
 * silently returning the tombstone text.
 */
public class HgCensoredContentException extends IOException {
    private static final long serialVersionUID = 1L;

    private final byte[] tombstone;

    public HgCensoredContentException(String path, int rev, byte[] tombstone) {
        super("censored node: " + path + "@" + rev);
        this.tombstone = tombstone;
    }

    /** The tombstone message substituted for the censored content, if any was set. */
    public byte[] getTombstone() {
        return tombstone;
    }
}
