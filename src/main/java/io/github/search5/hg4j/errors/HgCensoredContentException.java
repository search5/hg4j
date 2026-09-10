package io.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * Thrown when reading the content of a revision that has been censored ({@code hg censor}) —
 * mirrors real hg's default behavior of raising {@code error.CensoredNodeError} instead of
 * silently returning the tombstone text.
 *
 * @apiNote Thrown by {@code io.github.search5.hg4j.storage.Revlog} when a revision's data is
 *     read and the file was censored via {@code CensorCommand}. Porcelain read paths such as
 *     {@code CatCommand} and {@code DiffCommand} propagate it unchanged; callers that need to
 *     display the tombstone message instead of failing should catch it and use {@link
 *     #getTombstone()}.
 */
public class HgCensoredContentException extends IOException {
    private static final long serialVersionUID = 1L;

    /** The tombstone message substituted for the censored content, if any was set. */
    private final byte[] tombstone;

    /**
     * Creates the exception for a censored revision.
     *
     * @param path the repository-relative path of the censored file
     * @param rev the revision number at which the censored content was read
     * @param tombstone the tombstone message substituted for the censored content, if any was set
     */
    public HgCensoredContentException(String path, int rev, byte[] tombstone) {
        super("censored node: " + path + "@" + rev);
        this.tombstone = tombstone;
    }

    /**
     * Returns the tombstone message substituted for the censored content, if any was set.
     *
     * @return the tombstone bytes, or {@code null} if none was recorded
     */
    public byte[] getTombstone() {
        return tombstone;
    }
}
