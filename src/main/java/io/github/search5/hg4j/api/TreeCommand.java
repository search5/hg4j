package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.storage.Revlog;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import io.github.search5.hg4j.lib.NodeId;
import java.util.Map;

/**
 * Command to list the entire file structure (directory tree) at a specific revision.
 */
public class TreeCommand {
    private final HgRepository repository;
    private int revision = -1;
    private byte[] nodeId;

    /** One file in the tree listing. */
    public static class TreeEntry {
        private final String path;
        private final String nodeId;
        private final int mode;

        /**
         * Creates an entry.
         * @param path the repository-relative path
         * @param nodeId the 40-character hex node id of the file revision
         * @param mode the file mode ({@code 0644}, {@code 0755} or {@code 0120000})
         */
        public TreeEntry(String path, String nodeId, int mode) {
            this.path = path;
            this.nodeId = nodeId;
            this.mode = mode;
        }

        /**
         * Returns the file path.
         * @return the repository-relative path with {@code /} separators
         */
        public String getPath() { return path; }
        /**
         * Returns the file revision node.
         * @return the 40-character hex node id of the file revision in the manifest
         */
        public String getNodeId() { return nodeId; }
        /**
         * Returns the file node as an object.
         * @return the node id of the file revision, or {@code null} if there is none
         */
        public NodeId getNode() {
            return nodeId != null ? NodeId.fromHex(nodeId) : null;
        }
        /**
         * Returns the file mode.
         * @return {@code 0644}, {@code 0755} for an executable file or {@code 0120000} for a symbolic link
         */
        public int getMode() { return mode; }
    }

    /**
     * Creates the command.
     * @param repository the repository to read; must not be {@code null}
     */
    public TreeCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Selects the revision by local number.
     * @param revision the 0-based revision number; -1 (the default) means the tip
     * @return {@code this}
     */
    public TreeCommand setRevision(int revision) {
        this.revision = revision;
        return this;
    }

    /**
     * Selects the revision by node id; takes precedence over {@link #setRevision}.
     * @param nodeId the 20-byte node id, or {@code null} (the default) to use the revision number; if the node is
     *         not in the changelog the tip is listed
     * @return {@code this}
     */
    public TreeCommand setNodeId(byte[] nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    /**
     * Selects the revision by node id wrapper; takes precedence over {@link #setRevision}.
     * @param nodeId the node id, or {@code null} to clear it
     * @return {@code this}
     */
    public TreeCommand setNodeId(NodeId nodeId) {
        this.nodeId = nodeId != null ? nodeId.getBytes() : null;
        return this;
    }

    /**
     * Lists the files of the manifest of the selected revision.
     * <p>
     * The revision is the node set with {@code setNodeId} if any, otherwise the number set with {@link #setRevision}.
     * If the revision is -1 (the default) or the node is not found, the tip is used.
     * @return one entry per tracked file; empty if the repository has no changelog or the revision number is out of range
     * @throws IOException if reading the changelog or manifest fails
     */
    public List<TreeEntry> call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        
        if (!clIdx.exists()) {
            return Collections.emptyList();
        }
        
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        int targetRev = revision;
        if (nodeId != null) {
            targetRev = NodeIdUtil.findRevisionByNodeId(changelog, nodeId);
        }

        if (targetRev == -1) {
            // default to tip (latest revision)
            targetRev = changelog.getRevisionCount() - 1;
        }

        if (targetRev < 0 || targetRev >= changelog.getRevisionCount()) {
            return Collections.emptyList();
        }

        byte[] commitNodeId = changelog.getIndexRecord(targetRev).getNodeId();
        Map<String, String> manifestMap = repository.getManifestAtCommit(commitNodeId);

        List<TreeEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : manifestMap.entrySet()) {
            String path = entry.getKey();
            String hex = entry.getValue();
            
            int mode = 0644;
            String cleanHex = hex;
            if (hex.length() > 40) {
                char flag = hex.charAt(40);
                if (flag == 'x') {
                    mode = 0755;
                } else if (flag == 'l') {
                    mode = 0120000;
                }
                cleanHex = hex.substring(0, 40);
            }
            
            entries.add(new TreeEntry(path, cleanHex, mode));
        }
        return entries;
    }
}
