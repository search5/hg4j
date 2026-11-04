package io.github.search5.hg4j.lfs.errors;

/** The LFS server (or the local store) does not have the object: HTTP 404. */
public class HgLfsObjectNotFoundException extends HgLfsException {
    /**
     * Creates the exception; {@link #getStatus()} is 404.
     *
     * @param message description of the missing object
     */
    public HgLfsObjectNotFoundException(String message) {
        super(message, 404);
    }
}
