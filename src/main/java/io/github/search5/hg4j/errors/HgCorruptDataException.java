package io.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * Exception thrown on data integrity errors, such as revlog checksum mismatch or corrupted delta restoration.
 */
public class HgCorruptDataException extends IOException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a message.
     *
     * @param message description of the integrity failure
     */
    public HgCorruptDataException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and an underlying cause.
     *
     * @param message description of the integrity failure
     * @param cause the underlying failure; may be {@code null}
     */
    public HgCorruptDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
