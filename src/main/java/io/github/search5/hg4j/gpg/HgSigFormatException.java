package io.github.search5.hg4j.gpg;

import java.io.IOException;

/**
 * A {@code .hgsigs}/{@code localsigs} line, a keybox file or a signature that is not in the format
 * Mercurial's {@code gpg} extension defines. It is thrown instead of being skipped, so a corrupt file is
 * never mistaken for "no signatures".
 */
public class HgSigFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a message.
     *
     * @param message description of the format problem
     */
    public HgSigFormatException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and an underlying cause.
     *
     * @param message description of the format problem
     * @param cause the underlying failure; may be {@code null}
     */
    public HgSigFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
