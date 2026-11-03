package io.github.search5.hg4j.lfs.errors;

import java.io.IOException;

/**
 * A failure of the LFS machinery. {@link #getStatus()} is the HTTP status the LFS server answered with, or 0 when the failure is
 * not an HTTP answer. The subclasses name the cases a caller reacts to differently (JGit's {@code LfsException} family, with the
 * classes real hg's {@code hgext/lfs/blobstore.py} distinguishes: {@code LfsRemoteError} and {@code LfsCorruptionError}).
 */
public class HgLfsException extends IOException {

    private final int status;

    public HgLfsException(String message) {
        this(message, 0, null);
    }

    public HgLfsException(String message, int status) {
        this(message, status, null);
    }

    public HgLfsException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    /** The exception that describes an LFS server's {@code status} answer, with the given message. */
    public static HgLfsException forStatus(int status, String message) {
        return forStatus(status, message, null);
    }

    public static HgLfsException forStatus(int status, String message, Throwable cause) {
        HgLfsException result = switch (status) {
            case 401, 403 -> new HgLfsUnauthorizedException(message, status);
            case 404 -> new HgLfsObjectNotFoundException(message);
            default -> new HgLfsException(message, status, cause);
        };
        if (cause != null && result.getCause() == null) {
            result.initCause(cause);
        }
        return result;
    }
}
