package io.github.search5.hg4j.lfs.errors;

/** The LFS server (or the local store) does not have the object: HTTP 404. */
public class HgLfsObjectNotFoundException extends HgLfsException {
    public HgLfsObjectNotFoundException(String message) {
        super(message, 404);
    }
}
