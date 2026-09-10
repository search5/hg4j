package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.treewalk.HgTreeFilter;
import io.github.search5.hg4j.treewalk.SparsePathFilter;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Porcelain command mirroring {@code hg files}: lists tracked file paths (relative to
 * the repository root, forward-slash separated) that match an optional glob/path
 * pattern, at a given revision.
 * <p>
 * With no revision set, the listing reflects the current working copy's tracked set
 * as recorded in the dirstate (added-but-uncommitted files are included, files marked
 * removed are excluded) &mdash; matching real {@code hg files}' default behavior of
 * describing the working copy rather than literally the tip commit. With a revision
 * set (via {@link #setRevision(String)} / {@link #setRevision(NodeId)}), the listing
 * reflects that historical commit's manifest instead.
 *
 * @apiNote Typically obtained via {@link Hg#files()} on an open {@link Hg}
 *     instance rather than constructed directly.
 */
public class FilesCommand {

    private final HgRepository repository;
    private String revision;
    private HgTreeFilter treeFilter = HgTreeFilter.ALL;

    /**
     * Creates a files command bound to the given repository.
     *
     * @param repository the repository whose tracked files will be listed
     */
    public FilesCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the revision to list files at (revision number, hex node id/prefix, or
     * {@code "tip"}). When unset (the default), the working copy's tracked set
     * (dirstate) is used instead of any specific commit.
     *
     * @param revision the revision identifier to list files at, or {@code null} to use the working copy
     * @return this command, for chaining
     */
    public FilesCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    /**
     * Sets the revision to list files at, as a {@link NodeId}. Equivalent to
     * {@link #setRevision(String)} with the node's hex representation.
     *
     * @param nodeId the node id of the revision to list files at, or {@code null} to use the working copy
     * @return this command, for chaining
     */
    public FilesCommand setRevision(NodeId nodeId) {
        this.revision = nodeId != null ? nodeId.toHex() : null;
        return this;
    }

    /**
     * Sets an explicit tree filter used to match paths, e.g. one built via
     * {@link HgTreeFilter#createPathPrefixFilter} or wrapping a {@link SparsePathFilter}.
     *
     * @param treeFilter the filter paths must match to be included, or {@code null} to match everything
     * @return this command, for chaining
     */
    public FilesCommand setTreeFilter(HgTreeFilter treeFilter) {
        this.treeFilter = treeFilter != null ? treeFilter : HgTreeFilter.ALL;
        return this;
    }

    /**
     * Convenience setter matching {@code hg files <pattern>}: accepts one or more glob
     * patterns and matches them the same way {@link SparsePathFilter} already does
     * (including its directory-prefix semantics, where a pattern that names a directory
     * matches everything below it).
     *
     * @param globPatterns one or more glob patterns paths must match to be included
     * @return this command, for chaining
     */
    public FilesCommand setPattern(String... globPatterns) {
        this.treeFilter = HgTreeFilter.fromPathFilter(new SparsePathFilter(globPatterns));
        return this;
    }

    /**
     * Lists the matching tracked file paths, sorted the same way real hg orders them.
     *
     * @return the sorted list of repository-relative tracked file paths
     * @throws IOException if the dirstate or the target revision's manifest cannot be read
     */
    public List<String> call() throws IOException {
        List<String> paths;

        if (revision == null) {
            paths = listWorkingCopyTrackedFiles();
        } else {
            paths = listRevisionFiles();
        }

        if (treeFilter != null && treeFilter != HgTreeFilter.ALL) {
            List<String> filtered = new ArrayList<>();
            for (String path : paths) {
                if (treeFilter.accept(path)) {
                    filtered.add(path);
                }
            }
            paths = filtered;
        }

        paths.sort(NodeIdUtil.UTF8_STRING_COMPARATOR);
        return paths;
    }

    private List<String> listWorkingCopyTrackedFiles() throws IOException {
        Dirstate dirstate = repository.getDirstate();
        Map<String, Dirstate.Entry> tracked = dirstate.getEntries();

        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Dirstate.Entry> entry : tracked.entrySet()) {
            if (entry.getValue().getState() != 'r') {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private List<String> listRevisionFiles() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        if (!clIdx.exists()) {
            return new ArrayList<>();
        }

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        if (changelog.getRevisionCount() == 0) {
            // Mirrors real hg: on a repo with no commits yet, any revision reference
            // (including "0" or "tip") resolves to the null revision rather than
            // aborting, and simply yields no files.
            return new ArrayList<>();
        }

        byte[] targetNodeId = NodeIdUtil.resolveRevision(changelog, revision);
        if (targetNodeId == null) {
            throw new HgRevisionNotFoundException("Unable to resolve revision: " + revision);
        }

        Map<String, String> manifestMap = repository.getManifestAtCommit(targetNodeId);
        return new ArrayList<>(manifestMap.keySet());
    }
}
