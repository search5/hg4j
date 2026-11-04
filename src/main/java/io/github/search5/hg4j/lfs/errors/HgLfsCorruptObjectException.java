package io.github.search5.hg4j.lfs.errors;

/**
 * A stored or transferred object does not match its pointer (size or SHA-256), like real hg's {@code LfsCorruptionError}
 * ("detected corrupt lfs object").
 */
public class HgLfsCorruptObjectException extends HgLfsException {
    /**
     * Creates the exception.
     *
     * @param message description of the corrupt object, normally naming its oid
     */
    public HgLfsCorruptObjectException(String message) {
        super(message);
    }
}
