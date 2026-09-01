package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import com.github.search5.hg4j.errors.HgRevisionNotFoundException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repository.getRevlog(mfIdx, mfDat);

        Map<String, String> manifestMap = getManifestForCommit(changelog, manifestRevlog, midNode);
        for (Map.Entry<String, String> entry : manifestMap.entrySet()) {
            String path = entry.getKey();
            String hexAndFlag = entry.getValue();
            String fileHex = hexAndFlag.substring(0, 40);

            byte[] fileContent = getFileRevisionContent(repository, path, fileHex);
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

    private Map<String, String> getManifestForCommit(Revlog changelog, Revlog manifestRevlog, byte[] commitNode) throws IOException {
        Map<String, String> manifestMap = new LinkedHashMap<>();
        if (commitNode == null || NodeIdUtil.isAllZero(commitNode)) {
            return manifestMap;
        }
        int rev = changelog.findRevision(commitNode);
        if (rev == -1) {
            return manifestMap;
        }
        byte[] content = changelog.getRevisionContent(rev);
        String text = new String(content, StandardCharsets.UTF_8);
        String[] lines = text.split("\n");
        if (lines.length == 0) return manifestMap;

        String manifestHex = lines[0].trim();
        byte[] manifestNode = NodeIdUtil.fromHex(manifestHex);
        int mRev = manifestRevlog.findRevision(manifestNode);
        if (mRev != -1) {
            byte[] mContent = manifestRevlog.getRevisionContent(mRev);
            String mText = new String(mContent, StandardCharsets.UTF_8);
            for (String line : mText.split("\n")) {
                if (line.isEmpty()) continue;
                int nullIdx = line.indexOf('\0');
                if (nullIdx != -1) {
                    manifestMap.put(line.substring(0, nullIdx), line.substring(nullIdx + 1));
                }
            }
        }
        return manifestMap;
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
