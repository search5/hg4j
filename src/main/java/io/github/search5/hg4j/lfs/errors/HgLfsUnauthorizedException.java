package io.github.search5.hg4j.lfs.errors;

/** The LFS server refused the request for lack of (valid) credentials: HTTP 401 or 403. */
public class HgLfsUnauthorizedException extends HgLfsException {
    public HgLfsUnauthorizedException(String message, int status) {
        super(message, status);
    }
}
