package org.hg4j.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Manages the local caching and resolution of LFS (Large File Storage) objects.
 * Typically resolved inside '.hg/store/lfs/objects/' in Git/Mercurial style.
 */
public final class HgLfsManager {
    private final File lfsObjectsDir;

    public HgLfsManager(File hgDir) {
        if (hgDir == null) {
            throw new IllegalArgumentException("HG directory cannot be null");
        }
        File storeDir = new File(hgDir, "store");
        File lfsDir = new File(storeDir, "lfs");
        this.lfsObjectsDir = new File(lfsDir, "objects");
    }

    public File getLfsObjectsDir() {
        return lfsObjectsDir;
    }

    /**
     * Resolves the expected local path for a given OID.
     * E.g., OID "7b1a2c3d..." resolves to:
     * {lfsObjectsDir}/7b/1a/2c3d...
     *
     * @param oid 64-char hex OID
     * @return cache file reference
     */
    public File getLocalPath(String oid) {
        if (oid == null || oid.length() != 64) {
            throw new IllegalArgumentException("Invalid OID: must be a 64-character hex string");
        }
        String first2 = oid.substring(0, 2);
        String next2 = oid.substring(2, 4);
        String remaining = oid.substring(4);

        File sub1 = new File(lfsObjectsDir, first2);
        File sub2 = new File(sub1, next2);
        return new File(sub2, remaining);
    }

    /**
     * Checks if the LFS object exists locally in cache.
     *
     * @param pointer LFS pointer metadata
     * @return true if cached and size matches
     */
    public boolean isCached(HgLfsPointer pointer) {
        if (pointer == null) {
            return false;
        }
        File localFile = getLocalPath(pointer.getOid());
        return localFile.exists() && localFile.isFile() && localFile.length() == pointer.getSize();
    }

    /**
     * Writes binary payload into the local LFS objects directory.
     *
     * @param pointer LFS pointer metadata
     * @param data raw file payload bytes
     * @throws IOException if directory generation or write fails, or size mismatch
     */
    public void cacheObject(HgLfsPointer pointer, byte[] data) throws IOException {
        if (pointer == null || data == null) {
            throw new IllegalArgumentException("Pointer and data cannot be null");
        }
        if (data.length != pointer.getSize()) {
            throw new org.hg4j.errors.HgCorruptDataException("LFS payload size mismatch: expected " 
                    + pointer.getSize() + ", got " + data.length);
        }

        File cacheFile = getLocalPath(pointer.getOid());
        File parent = cacheFile.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create local LFS cache directories: " + parent.getAbsolutePath());
        }

        SafeFileIO.writeAtomic(cacheFile, data);
    }

    /**
     * Reads the cached LFS object payload from disk.
     *
     * @param pointer LFS pointer metadata
     * @return cached binary payload bytes
     * @throws IOException if file does not exist, or size mismatch
     */
    public byte[] getCachedObject(HgLfsPointer pointer) throws IOException {
        if (!isCached(pointer)) {
            throw new org.hg4j.errors.HgCorruptDataException("Requested LFS object not cached or size mismatch: " + pointer.getOid());
        }
        File cacheFile = getLocalPath(pointer.getOid());
        return Files.readAllBytes(cacheFile.toPath());
    }
}
