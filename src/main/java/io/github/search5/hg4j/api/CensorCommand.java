package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.treewalk.ManifestTreeIterator;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;

/**
 * Porcelain command for {@code hg censor} — erases a single file revision's content in place
 * (replacing it with a tombstone and marking it via {@link Revlog#REVIDX_ISCENSORED}) while
 * preserving that revision's node identity, parents, and linkrev, so history/DAG shape is
 * untouched. Real hg's tombstone wire format (see {@code mercurial/utils/storageutil.py}'s
 * {@code packmeta}/{@code iscensoredtext}) is the same {@code \x01\n key: value\n\x01\n} metadata
 * header hg4j already uses for filelog copy records, with a single {@code censored} key:
 * {@code "\x01\ncensored: <message>\n\x01\n"}.
 *
 * <p>2026-09-05 (backlog #39 wave 5): real hg's own {@code hgext.censor} ({@code _docensor} in
 * {@code hgext/censor.py}) refuses to censor a file revision that is still reachable as the exact
 * content of that path at any repository (topological) head, or at either working-directory
 * parent -- {@code abort: cannot censor file in heads (<hex>...)} / {@code abort: cannot censor
 * working directory}, both confirmed live against real hg 7.2 (2026-09-04). hg4j previously had no
 * such guard at all, which let it silently produce a repository state real hg's own checkout/head
 * logic would materialize censored (tombstone) content for -- a genuine data-availability bug, not
 * a deliberate looser-API choice, so it is fixed here rather than left as-is per the standing
 * "never silently narrow scope" rule. Since this command identifies the target by filelog node
 * (not by changeset revision the way real hg's {@code -r} does), the translation compares the
 * targeted filenode itself against each head's/parent's manifest entry for {@link #path} -- the
 * direct filenode analogue of real hg's changeset-identity check. {@link #setCheckHeads} mirrors
 * real hg's {@code --check-heads}/{@code --no-check-heads} escape hatch (default {@code true}).
 */
public final class CensorCommand {
    private final HgRepository repository;
    private String path;
    private String nodeHex;
    private String tombstone = "";
    private boolean checkHeads = true;

    public CensorCommand(HgRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        this.repository = repository;
    }

    public CensorCommand setFile(String path) {
        this.path = path;
        return this;
    }

    public CensorCommand setRevision(String nodeHex) {
        this.nodeHex = nodeHex;
        return this;
    }

    /** Optional free-text reason recorded in the tombstone (empty by default, matching real hg). */
    public CensorCommand setTombstone(String tombstone) {
        this.tombstone = tombstone == null ? "" : tombstone;
        return this;
    }

    /**
     * Real hg's {@code --check-heads}/{@code --no-check-heads} (default {@code true}): when
     * enabled, refuses to censor a revision whose exact content is still the live content of
     * {@link #path} at a repository head or a working-directory parent (see this class's javadoc).
     * Passing {@code false} bypasses the guard entirely, exactly like real hg's
     * {@code --no-check-heads}.
     */
    public CensorCommand setCheckHeads(boolean checkHeads) {
        this.checkHeads = checkHeads;
        return this;
    }

    public void call() throws IOException {
        if (path == null || path.isEmpty()) {
            throw new HgValidationException("File path is required for censor");
        }
        if (nodeHex == null || nodeHex.isEmpty()) {
            throw new HgValidationException("Revision is required for censor");
        }

        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new HgRepositoryNotFoundException("Filelog index does not exist for: " + path);
        }

        Revlog filelog = repository.getRevlog(flIdx, flDat);
        byte[] nodeId = NodeIdUtil.fromHex(nodeHex);
        int rev = filelog.findRevision(nodeId);
        if (rev == -1) {
            throw new HgRevisionNotFoundException("File revision not found: " + path + " @ " + nodeHex);
        }

        if (checkHeads) {
            checkNotLiveAtHeadsOrWorkingCopy(nodeId);
        }

        filelog.censorRevision(rev, buildTombstone(tombstone));
        repository.clearRevlogCache();
    }

    /** Real hg's own {@code check_heads} + working-directory guard from {@code hgext/censor.py}'s
     * {@code _docensor}, translated to hg4j's filenode-based targeting (see class javadoc). */
    private void checkNotLiveAtHeadsOrWorkingCopy(byte[] targetFileNode) throws IOException {
        List<String> heads = new HeadsCommand(repository).setTopo(true).call();
        List<String> affectedHeads = new ArrayList<>();
        for (String headHex : heads) {
            byte[] fileNodeAtHead = resolveFileNodeAt(headHex);
            if (fileNodeAtHead != null && Arrays.equals(fileNodeAtHead, targetFileNode)) {
                affectedHeads.add(headHex.substring(0, 12));
            }
        }
        if (!affectedHeads.isEmpty()) {
            throw new HgValidationException("abort: cannot censor file in heads (" + String.join(", ", affectedHeads)
                    + ") (clean/delete and commit first)");
        }

        Dirstate dirstate = repository.getDirstate();
        for (NodeId wdParent : new NodeId[]{dirstate.getParent1Node(), dirstate.getParent2Node()}) {
            if (wdParent == null || wdParent.isNull()) {
                continue;
            }
            byte[] fileNodeAtParent = resolveFileNodeAt(wdParent.toHex());
            if (fileNodeAtParent != null && Arrays.equals(fileNodeAtParent, targetFileNode)) {
                throw new HgValidationException("abort: cannot censor working directory (clean/delete/update first)");
            }
        }
    }

    /** Resolves {@link #path}'s filenode in the manifest of changeset {@code revHex}, or
     * {@code null} if that changeset does not track the path at all (matching real hg's
     * {@code path in hc}). Correctly recurses into treemanifest submanifests via
     * {@link ManifestTreeIterator}, which already flattens those for every caller. */
    private byte[] resolveFileNodeAt(String revHex) throws IOException {
        ManifestTreeIterator it = new ManifestTreeIterator(repository, revHex);
        it.reset();
        while (it.next()) {
            if (path.equals(it.getEntryPath())) {
                return it.getEntryNodeId();
            }
        }
        return null;
    }

    static byte[] buildTombstone(String message) {
        String text = "\ncensored: " + message + "\n\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
