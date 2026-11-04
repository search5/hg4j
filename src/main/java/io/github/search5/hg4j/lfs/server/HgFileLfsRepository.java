package io.github.search5.hg4j.lfs.server;

import io.github.search5.hg4j.lfs.HgLfsManager;
import io.github.search5.hg4j.lfs.HgLfsPointer;
import io.github.search5.hg4j.lfs.errors.HgLfsCorruptObjectException;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * The LFS store of a repository's {@code .hg/store/lfs/objects} (JGit's {@code FileLfsRepository}): the same directory, with the
 * same layout, real hg's {@code lfs} extension reads and writes.
 */
public final class HgFileLfsRepository implements HgLargeFileRepository {

    private final HgLfsManager manager;

    /**
     * Creates the store view for a repository.
     *
     * @param repository the repository whose {@code store/lfs/objects} directory and configuration are used; must not be {@code null}
     */
    public HgFileLfsRepository(HgRepository repository) {
        this.manager = new HgLfsManager(repository.getHgDir(), repository.getConfig());
    }

    private static HgLfsPointer pointerFor(String oid, long size) {
        return new HgLfsPointer("https://git-lfs.github.com/spec/v1", oid, size);
    }

    @Override
    public boolean has(String oid) {
        return manager.getLocalPath(oid).isFile() || manager.getUserCacheFile(oid) != null;
    }

    @Override
    public boolean verify(String oid) throws IOException {
        if (!has(oid)) {
            return false;
        }
        byte[] content;
        try {
            content = read(oid);
        } catch (HgLfsCorruptObjectException corrupt) {
            return false;
        }
        return HgLfsPointer.sha256Hex(content).equals(oid);
    }

    @Override
    public byte[] read(String oid) throws IOException {
        java.io.File file = manager.getLocalPath(oid);
        if (!file.isFile()) {
            java.io.File cached = manager.getUserCacheFile(oid);
            if (cached == null) {
                throw new FileNotFoundException(oid);
            }
            file = cached;
        }
        byte[] content = java.nio.file.Files.readAllBytes(file.toPath());
        if (!HgLfsPointer.sha256Hex(content).equals(oid)) {
            throw new HgLfsCorruptObjectException("detected corrupt lfs object: " + oid);
        }
        return content;
    }

    @Override
    public void store(String oid, long expectedSize, byte[] content) throws IOException {
        if (content.length != expectedSize || !HgLfsPointer.sha256Hex(content).equals(oid)) {
            throw new HgLfsCorruptObjectException("detected corrupt lfs object: " + oid);
        }
        manager.cacheObject(pointerFor(oid, content.length), content);
    }
}
