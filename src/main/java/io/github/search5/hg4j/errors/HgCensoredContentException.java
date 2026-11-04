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

    /**
     * Creates the exception for a censored revision.
     *
     * @param path the name of the revlog index file whose revision is censored (as passed by the caller, not a repository-relative file path)
     * @param rev the revision number within that revlog
     * @param tombstone the tombstone bytes stored in place of the censored content; may be {@code null} if none was set
     */
    public HgCensoredContentException(String path, int rev, byte[] tombstone) {
        super("censored node: " + path + "@" + rev);
        this.tombstone = tombstone;
    }

    /** The tombstone message substituted for the censored content, if any was set. */
    public byte[] getTombstone() {
        return tombstone;
    }
}
