package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;

import java.io.File;
import java.io.IOException;

/**
 * Porcelain command corresponding to {@code hg tip} — the changelog's highest-numbered revision
 * (regardless of branch/head; note this differs from "the most recently created" revision when
 * history has been rewritten, matching real hg semantics: tip is by revision number, not by wall clock).
 *
 * @apiNote Typically obtained via {@link Hg#tip()} on an open {@link Hg}
 *     instance rather than constructed directly.
 */
public class TipCommand {
    private final HgRepository repository;

    /**
     * Creates a tip command bound to the given repository.
     *
     * @param repository the repository to query the tip of
     */
    public TipCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Resolves the tip revision's node ID.
     *
     * @return the node id (20 bytes) of the tip revision, or {@code null} if the repository has no
     *         revisions yet.
     * @throws IOException if changelog IO fails
     */
    public byte[] call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        if (!clIdx.exists()) {
            return null;
        }
        Revlog changelog = repository.getRevlog(clIdx, clDat);
        int count = changelog.getRevisionCount();
        if (count == 0) {
            return null;
        }
        return changelog.getIndexRecord(count - 1).getNodeId();
    }

    /**
     * Resolves the tip revision's revision number.
     *
     * @return the revision number of tip, or -1 if the repository is empty.
     * @throws IOException if changelog IO fails
     */
    public int getRevisionNumber() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        if (!clIdx.exists()) {
            return -1;
        }
        return repository.getRevlog(clIdx, clDat).getRevisionCount() - 1;
    }
}
