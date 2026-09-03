package io.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * Exception thrown on data integrity errors, such as revlog checksum mismatch or corrupted delta restoration.
 */
public class HgCorruptDataException extends IOException {
    private static final long serialVersionUID = 1L;

    public HgCorruptDataException(String message) {
        super(message);
    }

    public HgCorruptDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
