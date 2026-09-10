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
 *
 * @apiNote Typically obtained via {@link Hg#tree()} on an open {@link Hg}
 *     instance rather than constructed directly.
 */
public class TreeCommand {
    private final HgRepository repository;
    private int revision = -1;
    private byte[] nodeId;

    /** One file entry in a manifest tree listing: its repository path, filelog node, and mode. */
    public static class TreeEntry {
        private final String path;
        private final String nodeId;
        private final int mode;

        /**
         * Creates an entry with the given path, filelog node (as hex), and file mode.
         *
         * @param path repository-relative file path
         * @param nodeId hex filelog node id of this file's content at the listed revision
         * @param mode POSIX-style file mode (e.g. {@code 0644}, {@code 0755}, or {@code 0120000} for a symlink)
         */
        public TreeEntry(String path, String nodeId, int mode) {
            this.path = path;
            this.nodeId = nodeId;
            this.mode = mode;
        }

        /**
         * Returns the repository-relative file path.
         *
         * @return the repository-relative file path
         */
        public String getPath() { return path; }
        /**
         * Returns the hex filelog node id of this file's content.
         *
         * @return the hex filelog node id of this file's content
         */
        public String getNodeId() { return nodeId; }
        /**
         * Returns the filelog node id of this file's content.
         *
         * @return the filelog node id of this file's content, or {@code null} if none was set
         */
        public NodeId getNode() {
            return nodeId != null ? NodeId.fromHex(nodeId) : null;
        }
        /**
         * Returns the POSIX-style file mode.
         *
         * @return the POSIX-style file mode
         */
        public int getMode() { return mode; }
    }

    /**
     * Creates the command against the given repository.
     *
     * @param repository repository whose manifest tree is listed
     */
    public TreeCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the changelog revision number to list the tree at. Ignored if {@link #setNodeId} is
     * also called with a non-{@code null} value.
     *
     * @param revision revision number, or {@code -1} (the default) to use the tip
     * @return this command, for chaining
     */
    public TreeCommand setRevision(int revision) {
        this.revision = revision;
        return this;
    }

    /**
     * Sets the changeset to list the tree at, by raw node id.
     *
     * @param nodeId raw node id of the changeset, or {@code null} to fall back to {@link #setRevision}
     * @return this command, for chaining
     */
    public TreeCommand setNodeId(byte[] nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    /**
     * Sets the changeset to list the tree at, by node id.
     *
     * @param nodeId node id of the changeset, or {@code null} to fall back to {@link #setRevision}
     * @return this command, for chaining
     */
    public TreeCommand setNodeId(NodeId nodeId) {
        this.nodeId = nodeId != null ? nodeId.getBytes() : null;
        return this;
    }

    /**
     * Resolves the configured revision (or the tip, if none resolves) and lists every file in its
     * manifest.
     *
     * @return the manifest's file entries; empty if the repository has no changelog or the
     *         resolved revision is out of range
     * @throws IOException if the changelog or manifest cannot be read
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
