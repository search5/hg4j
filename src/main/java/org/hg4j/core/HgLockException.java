package org.hg4j.core;

import java.io.IOException;

/**
 * Thrown when a file lock on a Mercurial repository cannot be acquired.
 */
public class HgLockException extends IOException {
    
    public HgLockException(String message) {
        super(message);
    }

    public HgLockException(String message, Throwable cause) {
        super(message, cause);
    }
}
