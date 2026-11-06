package io.github.search5.hg4j.api;

import io.github.search5.hg4j.util.NodeIdUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of {@link GraftInMemory#execute()}: either the changesets the graft made (in the order of the grafted revisions) or the
 * files that could not be merged, in which case nothing was written.
 */
public final class GraftResult {
    private final List<String> nodes;
    private final List<String> conflicts;
    private final boolean dryRun;

    private GraftResult(List<String> nodes, List<String> conflicts, boolean dryRun) {
        this.nodes = nodes;
        this.conflicts = conflicts;
        this.dryRun = dryRun;
    }

    static GraftResult grafted(List<String> nodes, boolean dryRun) {
        return new GraftResult(new ArrayList<>(nodes), Collections.emptyList(), dryRun);
    }

    static GraftResult conflicted(List<String> conflicts, boolean dryRun) {
        return new GraftResult(Collections.emptyList(), new ArrayList<>(conflicts), dryRun);
    }

    /** @return whether a file could not be merged; nothing was written then */
    public boolean isConflicted() {
        return !conflicts.isEmpty();
    }

    /** @return the conflicting files (a deletion on one side against a change on the other is one), empty if the graft worked */
    public List<String> getConflicts() {
        return Collections.unmodifiableList(conflicts);
    }

    /** @return the new changesets (20-byte nodes), one per grafted revision in order; empty on a conflict. For a dry run they are not stored. */
    public List<byte[]> getNodes() {
        List<byte[]> out = new ArrayList<>();
        for (String hex : nodes) {
            out.add(NodeIdUtil.fromHex(hex));
        }
        return out;
    }

    /** @return the nodes of {@link #getNodes()} as 40-character hex strings */
    public List<String> getNodeHexes() {
        return Collections.unmodifiableList(nodes);
    }

    /** @return whether the result is of a dry run, so the changesets were not written */
    public boolean isDryRun() {
        return dryRun;
    }
}
