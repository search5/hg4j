package io.github.search5.hg4j.errors;

/**
 * Exception thrown on data integrity errors, such as revlog checksum mismatch or corrupted delta restoration.
 */
public class HgCorruptDataException extends java.io.IOException {
    private static final long serialVersionUID = 1L;

    public HgCorruptDataException(String message) {
        super(message);
    }

    public HgCorruptDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
