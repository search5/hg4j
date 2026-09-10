package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.storage.Revlog;

import java.io.File;
import java.io.IOException;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.NodeId;
import java.util.Map;

/**
 * Porcelain command to retrieve the content of a specific file version in history.
 *
 * @apiNote Typically obtained via {@link Hg#cat()} on an open {@link Hg}
 *     instance rather than constructed directly.
 */
public class CatCommand {

    private final HgRepository repository;
    private String file;
    private String revision;

    /**
     * Creates the command against the given repository.
     *
     * @param repository repository the file content is read from
     */
    public CatCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the repository-relative path of the file to retrieve.
     *
     * @param file repository-relative file path
     * @return this command, for chaining
     */
    public CatCommand setFile(String file) {
        this.file = file;
        return this;
    }

    /**
     * Sets the changeset to read the file at, by revision string.
     *
     * @param revision revision identifier accepted by {@link NodeIdUtil#resolveRevision}
     *                 (e.g. a hex node id prefix, revision number, or {@code "tip"})
     * @return this command, for chaining
     */
    public CatCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    /**
     * Sets the changeset to read the file at, by node id.
     *
     * @param nodeId node id of the changeset, or {@code null} to clear the revision
     * @return this command, for chaining
     */
    public CatCommand setRevision(NodeId nodeId) {
        this.revision = nodeId != null ? nodeId.toHex() : null;
        return this;
    }

    /**
     * Resolves the configured revision and returns the content of {@link #setFile} as it existed
     * in that changeset's manifest.
     *
     * @return the raw byte content of the file at the resolved revision
     * @throws IOException if the changelog, manifest, or filelog cannot be read
     */
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
            throw new HgRevisionNotFoundException("Unable to resolve revision");
        }

        int commitRev = NodeIdUtil.findRevisionByNodeId(changelog, targetNodeId);
        if (commitRev == -1) {
            throw new HgRevisionNotFoundException("Commit revision not found: " + NodeIdUtil.toHex(targetNodeId));
        }

        // Read Manifest at that commit to find file version node
        Map<String, String> manifestMap = repository.getManifestAtCommit(targetNodeId);
        String fileHexNode = manifestMap.get(file);
        if (fileHexNode != null && fileHexNode.length() > 40) {
            fileHexNode = fileHexNode.substring(0, 40);
        }

        if (fileHexNode == null) {
            throw new HgRevisionNotFoundException("File not tracked at target revision: " + file);
        }

        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), file);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new HgCorruptDataException("Filelog not found for tracked file: " + file);
        }

        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(fileHexNode));
        if (fileRev == -1) {
            throw new HgRevisionNotFoundException("File version not found in history: " + file + " @ " + fileHexNode);
        }

        return filelog.getRevisionContent(fileRev);
    }

}
