package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.treewalk.ManifestWalk;
import io.github.search5.hg4j.util.NodeIdUtil;
import java.io.File;
import java.io.IOException;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * Updates working directory parents and physically restores files to workspace.
     *
     * @return next bisect candidate node ID
     * @throws IOException if revision lookup fails
     */
    public byte[] next() throws IOException {
        if (goodNode == null || badNode == null) {
            throw new IllegalStateException("Good and Bad revision nodes must be set prior to bisect query");
        }

        // Backlog #39: guard against a long-lived HgRepository handle serving a stale cached
        // changelog-v2 revlog after an external process appended a revision -- see
        // DescribeCommand#call()'s javadoc for the full root-cause writeup. Cheap no-op in the
        // common (freshly-opened-per-call) case.
        repository.refreshIfChangedOnDisk();
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        int goodRev = changelog.findRevision(goodNode);
        int badRev = changelog.findRevision(badNode);
        if (goodRev == -1 || badRev == -1) {
            throw new IOException("Bisect error: revision nodes not found in changelog history");
        }
        if (goodRev == badRev) {
            // Matches real hg (mercurial/hbisect.py bisect()): `hg bisect --good
            // X --bad X` aborts with "inconsistent state, X is good and bad"
            // rather than silently treating X as its own bisect candidate.
            throw new IOException("Bisect error: revision " + NodeIdUtil.toHex(goodNode) + " is marked as both good and bad");
        }

        // 1. DAG-based Topological range search (Graph Algorithm)
        List<Integer> range = getTopologicalRange(changelog, goodRev, badRev);
        if (range.isEmpty()) {
            throw new IOException("Bisect error: no topological path exists between good and bad nodes");
        }

        int midRev = selectBisectCandidate(changelog, range, goodRev, badRev);
        byte[] midNode = changelog.getIndexRecord(midRev).getNodeId();

        // 2. Physical File Checkout & Workspace Sync
        //
        // Uses ManifestTreeIterator (the same treemanifest-aware reader ManifestCommand/
        // StatusCommand/DiffCommand already rely on) rather than hand-parsing the root manifest
        // revlog's raw text directly. The old hand-rolled parse (removed here, backlog #39)
        // treated EVERY manifest line as a real file: under a treemanifest repository
        // (experimental.treemanifest=1) the root manifest's entries for subdirectories are
        // "t"-flagged pointers to a nested `meta/<dir>/00manifest.i` sub-manifest revision, not
        // file content -- so bisect would try to open a (nonexistent) filelog for the raw
        // directory name and either throw or silently skip every file that lived inside any
        // subdirectory, leaving the working copy incompletely (or wrongly) checked out at each
        // bisect step.
        for (ManifestWalk.Entry entry : listManifestEntries(changelog, midNode)) {
            String path = entry.getPath();
            byte[] fileContent = getFileRevisionContent(repository, path, NodeIdUtil.toHex(entry.getNodeId()));
            File wFile = new File(repository.getDirectory(), path);
            wFile.getParentFile().mkdirs();
            Files.write(wFile.toPath(), fileContent);
        }

        // Update working directory parent to checkout the mid-node automatically
        Dirstate d = repository.getDirstate();
        d.setParents(midNode, new byte[20]);
        repository.writeDirstate(d);

        // Each candidate hg4j checks out during bisect is a full working-copy checkout,
        // same as `hg update` — the working branch must follow it, or `hg branch`/a
        // subsequent commit would silently report whatever branch bisect started on.
        repository.setBranch(CommitCommand.getBranchOfRevision(changelog, midRev));

        repository.clearRevlogCache();

        return midNode;
    }

    private List<Integer> getTopologicalRange(Revlog changelog, int goodRev, int badRev) throws IOException {
        List<Integer> range = new ArrayList<>();
        int min = Math.min(goodRev, badRev);
        int max = Math.max(goodRev, badRev);

        boolean[] isDescendant = new boolean[max + 1];
        isDescendant[min] = true;
        for (int i = min + 1; i <= max; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            int p1 = rec.getParent1();
            int p2 = rec.getParent2();
            if ((p1 >= min && isDescendant[p1]) || (p2 >= min && isDescendant[p2])) {
                isDescendant[i] = true;
            }
        }

        boolean[] isAncestor = new boolean[max + 1];
        for (int i = max; i >= min; i--) {
            if (i == max) {
                isAncestor[i] = true;
                continue;
            }
            for (int child = i + 1; child <= max; child++) {
                if (isAncestor[child]) {
                    Revlog.IndexRecord childRec = changelog.getIndexRecord(child);
                    if (childRec.getParent1() == i || childRec.getParent2() == i) {
                        isAncestor[i] = true;
                        break;
                    }
                }
            }
        }

        for (int i = min; i <= max; i++) {
            if (isDescendant[i] && isAncestor[i]) {
                range.add(i);
            }
        }
        return range;
    }

    /**
     * Picks the next revision to test out of {@code range}, mirroring real hg's
     * bisect algorithm (mercurial/hbisect.py {@code bisect()}): rather than the
     * mid-index of the linear revision-number range, it picks whichever candidate
     * best splits the remaining DAG in half, counting each candidate's ancestors
     * (restricted to the candidate set) via forward propagation along parent edges.
     *
     * <p>The revision structurally closest to the "root" side of the range -- i.e.
     * {@code min(goodRev, badRev)}, which real hg's own directional flip always
     * resolves to, since a parent's revision number is always lower than its
     * child's -- is excluded from the candidate set, exactly as real hg excludes
     * whichever endpoint is not present in its own {@code ancestors} dict.</p>
     *
     * <p>Real hg additionally "poisons" (skips) subtrees that provably cannot beat
     * the current best split, purely as a performance optimization -- omitted here
     * since it never changes which candidate is ultimately selected.</p>
     */
    private int selectBisectCandidate(Revlog changelog, List<Integer> range, int goodRev, int badRev) throws IOException {
        int excludedRev = Math.min(goodRev, badRev);
        List<Integer> candidates = new ArrayList<>();
        for (int r : range) {
            if (r != excludedRev) {
                candidates.add(r);
            }
        }
        // goodRev != badRev is guaranteed by next()'s own precondition check, and
        // both are always members of range (getTopologicalRange seeds isDescendant
        // at min and isAncestor at max unconditionally) -- so range always contains
        // at least the two distinct endpoints, and candidates (range minus exactly
        // one of them) is therefore never empty.
        int tot = candidates.size();

        Map<Integer, Integer> candidateIndex = new HashMap<>();
        for (int i = 0; i < tot; i++) {
            candidateIndex.put(candidates.get(i), i);
        }

        BitSet[] ancestorBits = new BitSet[tot];
        int bestIdx = -1;
        int bestValue = -1;
        int perfect = tot / 2;
        for (int i = 0; i < tot; i++) {
            int rev = candidates.get(i);
            BitSet a = new BitSet(tot);
            a.set(i);
            Revlog.IndexRecord rec = changelog.getIndexRecord(rev);
            Integer p1Idx = candidateIndex.get(rec.getParent1());
            if (p1Idx != null) {
                a.or(ancestorBits[p1Idx]);
            }
            Integer p2Idx = candidateIndex.get(rec.getParent2());
            if (p2Idx != null) {
                a.or(ancestorBits[p2Idx]);
            }
            ancestorBits[i] = a;

            int x = a.cardinality();
            int y = tot - x;
            int value = Math.min(x, y);
            if (value > bestValue) {
                bestValue = value;
                bestIdx = i;
                if (value == perfect) {
                    break;
                }
            }
        }
        return candidates.get(bestIdx);
    }

    /**
     * Lists every real file entry (fully expanded -- no "t"-flagged directory pointers) tracked
     * at {@code commitNode}, using the same treemanifest-aware {@link ManifestWalk} (backed by
     * {@link io.github.search5.hg4j.treewalk.ManifestTreeIterator}) that {@link ManifestCommand}/
     * {@link StatusCommand}/{@link DiffCommand} already rely on (backlog #39 fix: the previous
     * hand-rolled root-manifest-only parse silently mishandled/omitted every file living inside a
     * subdirectory of a treemanifest repository).
     */
    private List<ManifestWalk.Entry> listManifestEntries(Revlog changelog, byte[] commitNode) throws IOException {
        if (commitNode == null || NodeIdUtil.isAllZero(commitNode) || changelog.findRevision(commitNode) == -1) {
            return List.of();
        }
        // The String-revision constructor (not the byte[]-manifestNode one) is required here --
        // commitNode is a CHANGELOG node, and ManifestWalk's byte[] constructor instead expects a
        // MANIFEST node directly; NodeIdUtil.resolveRevision (invoked internally for the String
        // overload) is what actually maps a changeset hex to its manifest revision.
        return new ManifestWalk(repository, NodeIdUtil.toHex(commitNode)).getEntries();
    }

    private byte[] getFileRevisionContent(HgRepository repository, String path, String nodeHex) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new HgRepositoryNotFoundException("Filelog index does not exist for: " + path);
        }
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int rev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(nodeHex.substring(0, 40)));
        if (rev == -1) {
            throw new HgRevisionNotFoundException("File revision not found: " + path + " @ " + nodeHex);
        }
        return filelog.getRevisionContent(rev);
    }
}
