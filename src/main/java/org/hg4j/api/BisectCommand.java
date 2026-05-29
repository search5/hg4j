package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.Revlog;
import org.hg4j.core.NodeIdUtil;
import java.io.File;
import java.io.IOException;

/**
 * Porcelain command for Git-bisect / Hg-bisect style binary search
 * to identify the regression revision in SCM history.
 */
public class BisectCommand {
    private final HgRepository repository;
    private byte[] goodNode;
    private byte[] badNode;

    public BisectCommand(HgRepository repository) {
        this.repository = repository;
    }

    public BisectCommand setGood(byte[] goodNode) {
        this.goodNode = goodNode;
        return this;
    }

    public BisectCommand setBad(byte[] badNode) {
        this.badNode = badNode;
        return this;
    }

    /**
     * Identifies and returns the next mid-revision node to be checked.
     * Updates working directory parents for fast inline testing validation.
     *
     * @return next bisect candidate node ID
     * @throws IOException if revision lookup fails
     */
    public byte[] next() throws IOException {
        if (goodNode == null || badNode == null) {
            throw new IllegalStateException("Good and Bad revision nodes must be set prior to bisect query");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        int goodRev = changelog.findRevision(goodNode);
        int badRev = changelog.findRevision(badNode);
        if (goodRev == -1 || badRev == -1) {
            throw new IOException("Bisect error: revision nodes not found in changelog history");
        }

        int midRev = (goodRev + badRev) / 2;
        byte[] midNode = changelog.getIndexRecord(midRev).getNodeId();

        // Update working directory parent to checkout the mid-node automatically
        org.hg4j.core.Dirstate d = repository.getDirstate();
        d.setParents(midNode, new byte[20]);
        repository.writeDirstate(d);
        repository.clearRevlogCache();

        return midNode;
    }
}
