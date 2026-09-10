package io.github.search5.hg4j.storage;
import io.github.search5.hg4j.lib.Repository;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.dirstate.Dirstate;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Pluggable Storage Engine Interface for hg4j.
 * Decouples the physical revlog filesystem access from porcelain repository commands,
 * paving the way for SQLite, RocksDB, or virtualized hosting storage.
 *
 * @apiNote {@link HgRepository} delegates every store read/write to its current {@code
 *     StoreEngine} (see {@link Repository}, {@link HgRepository#getRevlog}, {@link
 *     HgRepository#getDirstate()}), defaulting to {@link DefaultFileStoreEngine} — plain
 *     filesystem access under {@code .hg/}. Swap in a custom implementation via {@link
 *     HgRepository#setStoreEngine} to back a repository with a different storage medium
 *     without changing any porcelain command.
 */
public interface StoreEngine {
    
    /**
     * Resolves and returns a Revlog instance.
     *
     * @param repository repository the revlog belongs to
     * @param indexFile revlog index ({@code .i}) file
     * @param dataFile revlog data ({@code .d}) file
     * @return the resolved revlog
     * @throws IOException if the revlog cannot be read
     */
    Revlog getRevlog(HgRepository repository, File indexFile, File dataFile) throws IOException;

    /**
     * Resolves the manifest map at a given commit nodeId.
     *
     * @param repository repository the commit belongs to
     * @param commitNodeId node ID of the commit whose manifest is resolved
     * @return map of tracked file path to filelog node ID hex string, as recorded in that
     *     commit's manifest
     * @throws IOException if the changelog or manifest cannot be read
     */
    Map<String, String> getManifestAtCommit(HgRepository repository, byte[] commitNodeId) throws IOException;

    /**
     * Reads the dirstate from the repository storage.
     *
     * @param repository repository whose dirstate is read
     * @return the current dirstate
     * @throws IOException if the dirstate cannot be read
     */
    Dirstate getDirstate(HgRepository repository) throws IOException;

    /**
     * Writes the specified dirstate back to the repository storage.
     *
     * @param repository repository whose dirstate is written
     * @param dirstate dirstate to persist
     * @throws IOException if the dirstate cannot be written
     */
    void writeDirstate(HgRepository repository, Dirstate dirstate) throws IOException;

    /**
     * Resolves the manifest Revlog instance.
     *
     * @param repository repository whose manifest revlog is resolved
     * @return the manifest revlog
     * @throws IOException if the manifest revlog cannot be read
     */
    Revlog getManifestRevlog(HgRepository repository) throws IOException;
}
