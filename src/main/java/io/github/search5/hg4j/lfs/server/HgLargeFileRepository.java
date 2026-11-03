package io.github.search5.hg4j.lfs.server;

import java.io.IOException;

/**
 * The storage behind an LFS server (JGit's {@code LargeFileRepository}). The server decides what to answer a batch request
 * with by asking this: does the object exist and is it intact, how is it read, how is it written.
 */
public interface HgLargeFileRepository {

    /** Whether an object of this id is stored. */
    boolean has(String oid) throws IOException;

    /** Whether the stored object matches its id (SHA-256). {@code false} if it is not stored at all. */
    boolean verify(String oid) throws IOException;

    /** The stored object. @throws java.io.FileNotFoundException if it is not stored */
    byte[] read(String oid) throws IOException;

    /**
     * Stores an object.
     *
     * @param expectedSize the size the uploader announced (the {@code Content-Length})
     * @throws io.github.search5.hg4j.lfs.errors.HgLfsCorruptObjectException if {@code content} is not what {@code oid} and
     *     {@code expectedSize} say
     */
    void store(String oid, long expectedSize, byte[] content) throws IOException;
}
