package io.github.search5.hg4j.lfs.errors;

/** The LFS server refused the request for lack of (valid) credentials: HTTP 401 or 403. */
public class HgLfsUnauthorizedException extends HgLfsException {
    /**
     * Creates the exception.
     *
     * @param message description of the refusal
     * @param status the HTTP status the server answered with (401 or 403)
     */
    public HgLfsUnauthorizedException(String message, int status) {
        super(message, status);
    }
}
