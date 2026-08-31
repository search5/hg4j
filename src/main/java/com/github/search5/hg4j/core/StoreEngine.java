package com.github.search5.hg4j.core;
import com.github.search5.hg4j.dirstate.Dirstate;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Pluggable Storage Engine Interface for hg4j.
 * Decouples the physical revlog filesystem access from porcelain repository commands,
 * paving the way for SQLite, RocksDB, or virtualized hosting storage.
 */
public interface StoreEngine {
    
    /**
     * Resolves and returns a Revlog instance.
     */
    Revlog getRevlog(HgRepository repository, File indexFile, File dataFile) throws IOException;

    /**
     * Resolves the manifest map at a given commit nodeId.
     */
    Map<String, String> getManifestAtCommit(HgRepository repository, byte[] commitNodeId) throws IOException;

    /**
     * Reads the dirstate from the repository storage.
     */
    Dirstate getDirstate(HgRepository repository) throws IOException;

    /**
     * Writes the specified dirstate back to the repository storage.
     */
    void writeDirstate(HgRepository repository, Dirstate dirstate) throws IOException;

    /**
     * Resolves the manifest Revlog instance.
     */
    Revlog getManifestRevlog(HgRepository repository) throws IOException;
}
