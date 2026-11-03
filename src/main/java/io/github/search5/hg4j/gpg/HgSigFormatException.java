package io.github.search5.hg4j.gpg;

import java.io.IOException;

/**
 * A {@code .hgsigs}/{@code localsigs} line, a keybox file or a signature that is not in the format
 * Mercurial's {@code gpg} extension defines. It is thrown instead of being skipped, so a corrupt file is
 * never mistaken for "no signatures".
 */
public class HgSigFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    public HgSigFormatException(String message) {
        super(message);
    }

    public HgSigFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
