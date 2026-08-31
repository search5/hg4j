package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.core.Revlog;

import java.io.File;
import java.io.IOException;

/**
 * Porcelain command to retrieve the content of a specific file version in history.
 */
public class CatCommand {

    private final HgRepository repository;
    private String file;
    private String revision;

    public CatCommand(HgRepository repository) {
        this.repository = repository;
    }

    public CatCommand setFile(String file) {
        this.file = file;
        return this;
    }

    public CatCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    public CatCommand setRevision(com.github.search5.hg4j.lib.NodeId nodeId) {
        this.revision = nodeId != null ? nodeId.toHex() : null;
        return this;
    }

    public byte[] call() throws IOException {
        repository.clearRevlogCache();
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("File path must be specified.");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        byte[] targetNodeId = NodeIdUtil.resolveRevision(changelog, revision);
        if (targetNodeId == null) {
            throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException("Unable to resolve revision");
        }

        int commitRev = NodeIdUtil.findRevisionByNodeId(changelog, targetNodeId);
        if (commitRev == -1) {
            throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException("Commit revision not found: " + NodeIdUtil.toHex(targetNodeId));
        }

        // Read Manifest at that commit to find file version node
        java.util.Map<String, String> manifestMap = repository.getManifestAtCommit(targetNodeId);
        String fileHexNode = manifestMap.get(file);
        if (fileHexNode != null && fileHexNode.length() > 40) {
            fileHexNode = fileHexNode.substring(0, 40);
        }

        if (fileHexNode == null) {
            throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException("File not tracked at target revision: " + file);
        }

        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), file);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new com.github.search5.hg4j.errors.HgCorruptDataException("Filelog not found for tracked file: " + file);
        }

        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(fileHexNode));
        if (fileRev == -1) {
            throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException("File version not found in history: " + file + " @ " + fileHexNode);
        }

        return filelog.getRevisionContent(fileRev);
    }

}
