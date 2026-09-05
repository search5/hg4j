package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase command for querying or setting the SCM phase (public, draft, secret) of specific
 * changeset revisions in Mercurial repositories, synchronized against {@code
 * .hg/store/phaseroots}.
 *
 * <p>Wave 4 (2026-09-05) rewrite: the previous implementation stored one explicit phaseroots
 * line <em>per touched node</em>, which diverges from real hg's own "minimal roots" file format
 * -- verified directly against real hg 7.2.4's {@code mercurial/phases.py} ({@code phasecache
 * .advanceboundary}/{@code _retractboundary}) and its CLI ({@code hg phase}) behavior. Real hg
 * records only the topologically-minimal <em>boundary</em> revisions per non-public phase (a
 * revision's effective phase is the maximum phase of any ancestor-or-self boundary root, default
 * public), and:
 *
 * <ul>
 *   <li>moving a revision towards a <em>lower</em> phase number (more public) is unconditional
 *       and affects that revision and <em>all of its ancestors</em> (a child can never be less
 *       public than its parent);
 *   <li>moving a revision towards a <em>higher</em> phase number (more secret) affects that
 *       revision and <em>all of its descendants</em> (implicitly, via ancestry, without an
 *       explicit root for each one) and requires {@link #setForce}, exactly like real hg's
 *       {@code hg phase --force} gate ("cannot move N changesets to a higher phase, use
 *       --force");
 *   <li>the resulting roots are recomputed from scratch after the move (rather than patched
 *       incrementally), which both matches real hg's actual on-disk result byte-for-byte and
 *       automatically drops any now-redundant nested root a naive per-node write would otherwise
 *       leave behind.
 * </ul>
 *
 * <p>The file's line order also matches real hg exactly: phase groups in ascending phase-number
 * order (draft, then secret), each group's nodes in ascending revision-number order (real hg's
 * {@code _write()} iterates {@code sorted(roots)} over revision numbers, not node hex).
 */
public class PhaseCommand {
    private final HgRepository repository;
    private String revision;
    private int forcePhase = -1; // 0=public, 1=draft, 2=secret
    private boolean force = false;

    public PhaseCommand(HgRepository repository) {
        this.repository = repository;
    }

    public PhaseCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    public PhaseCommand setPhase(int phase) {
        this.forcePhase = phase;
        return this;
    }

    /**
     * Mirrors real hg's {@code hg phase --force}: without it, moving a revision to a
     * <em>higher</em> phase number (more secret) than its current effective phase is rejected.
     */
    public PhaseCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    /**
     * Executes phase query or phase modification.
     * Synchronizes updates to the '.hg/store/phaseroots' file standard.
     *
     * @return SCM phase value (0=public, 1=draft, 2=secret)
     * @throws IOException if phase IO fails, the revision cannot be resolved, or (absent {@link
     *     #setForce}) the requested move would raise the phase of an already-registered revision
     */
    public int call() throws IOException {
        File storeDir = repository.getStoreDir();
        File phaseRootsFile = new File(storeDir, "phaseroots");
        Revlog changelog = repository.getRevlog(new File(storeDir, "00changelog.i"), new File(storeDir, "00changelog.d"));

        byte[] nodeBytes = NodeIdUtil.resolveRevision(changelog, revision);
        if (nodeBytes == null) {
            throw new IOException("Phase error: Revision not found in repository: " + revision);
        }
        int targetRev = changelog.findRevision(nodeBytes);
        if (targetRev < 0) {
            throw new IOException("Phase error: Revision not found in repository: " + revision);
        }

        int count = changelog.getRevisionCount();
        // Load current roots as revision numbers, skipping any line whose node is not a known
        // revision -- real hg's own _readroots() silently drops ("removing unknown node ...")
        // any phaseroots entry that no longer (or never did) resolve in the changelog.
        List<int[]> rawRootsByPhase = new ArrayList<>(); // [phase, rev] pairs, level 1 and 2 only
        if (phaseRootsFile.exists()) {
            for (String line : Files.readAllLines(phaseRootsFile.toPath(), StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;
                int ph;
                try {
                    ph = Integer.parseInt(parts[0]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (ph != 1 && ph != 2) continue;
                byte[] rootNodeBytes;
                try {
                    rootNodeBytes = NodeIdUtil.fromHex(parts[1]);
                } catch (RuntimeException e) {
                    continue;
                }
                int rootRev = changelog.findRevision(rootNodeBytes);
                if (rootRev < 0) continue;
                rawRootsByPhase.add(new int[]{ph, rootRev});
            }
        }

        int[] phaseOf = computePhases(count, rawRootsByPhase, changelog);

        if (forcePhase == -1) {
            return phaseOf[targetRev];
        }

        int current = phaseOf[targetRev];
        if (forcePhase == current) {
            // Matches real hg's "no phases changed": no-op, file untouched.
            return current;
        }
        if (forcePhase > current && !force) {
            throw new IOException("cannot move 1 changesets to a higher phase, use --force");
        }

        int[] newPhaseOf = phaseOf.clone();
        if (forcePhase < current) {
            // Advance towards public: affects the target and ALL of its ancestors.
            boolean[] anc = ancestorsOrSelf(targetRev, changelog);
            for (int r = 0; r < count; r++) {
                if (anc[r] && newPhaseOf[r] > forcePhase) {
                    newPhaseOf[r] = forcePhase;
                }
            }
        } else {
            // Retract towards secret: affects the target and ALL of its descendants.
            boolean[] desc = descendantsOrSelf(targetRev, changelog);
            for (int r = 0; r < count; r++) {
                if (desc[r] && newPhaseOf[r] < forcePhase) {
                    newPhaseOf[r] = forcePhase;
                }
            }
        }

        writeRoots(phaseRootsFile, changelog, count, newPhaseOf);
        return forcePhase;
    }

    /**
     * Computes the effective phase of every revision from a set of (phase, rootRev) boundary
     * pairs: a revision's phase is the max, over every ancestor-or-self boundary root, of that
     * root's phase (default public). Root phases are exact boundaries (a revision below a root
     * on the same ancestry chain never carries a phase higher than the nearest root above it,
     * since roots are always recomputed as minimal / non-nested by {@link #writeRoots}).
     */
    private static int[] computePhases(int count, List<int[]> rootsByPhase, Revlog changelog) {
        int[] rootPhaseOf = new int[count]; // 0 unless this exact rev is an explicit root
        for (int[] entry : rootsByPhase) {
            int ph = entry[0];
            int rev = entry[1];
            if (ph > rootPhaseOf[rev]) {
                rootPhaseOf[rev] = ph;
            }
        }
        int[] phaseOf = new int[count];
        for (int r = 0; r < count; r++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(r);
            int p1 = rec.getParent1();
            int p2 = rec.getParent2();
            int inherited = 0;
            if (p1 >= 0) {
                inherited = Math.max(inherited, phaseOf[p1]);
            }
            if (p2 >= 0) {
                inherited = Math.max(inherited, phaseOf[p2]);
            }
            phaseOf[r] = Math.max(inherited, rootPhaseOf[r]);
        }
        return phaseOf;
    }

    private static boolean[] ancestorsOrSelf(int rev, Revlog changelog) {
        boolean[] visited = new boolean[changelog.getRevisionCount()];
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        stack.push(rev);
        visited[rev] = true;
        while (!stack.isEmpty()) {
            int r = stack.pop();
            Revlog.IndexRecord rec = changelog.getIndexRecord(r);
            int p1 = rec.getParent1();
            int p2 = rec.getParent2();
            if (p1 >= 0 && !visited[p1]) {
                visited[p1] = true;
                stack.push(p1);
            }
            if (p2 >= 0 && !visited[p2]) {
                visited[p2] = true;
                stack.push(p2);
            }
        }
        return visited;
    }

    private static boolean[] descendantsOrSelf(int rev, Revlog changelog) {
        int count = changelog.getRevisionCount();
        boolean[] result = new boolean[count];
        result[rev] = true;
        // Children always have a strictly higher revision number than their parents, so a single
        // forward scan suffices (no need for a full child adjacency map).
        for (int r = rev + 1; r < count; r++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(r);
            int p1 = rec.getParent1();
            int p2 = rec.getParent2();
            if ((p1 >= 0 && result[p1]) || (p2 >= 0 && result[p2])) {
                result[r] = true;
            }
        }
        return result;
    }

    /**
     * Rewrites {@code phaseroots} from a full per-revision phase array, recomputing the minimal
     * boundary roots for each tracked phase (draft=1, secret=2) exactly like real hg: a revision
     * is a root of phase L iff its own phase is exactly L and neither parent (missing parent
     * treated as public) also has phase &gt;= L. Lines are written phase-ascending (draft before
     * secret, matching real hg's {@code allphases} iteration order), each phase's roots in
     * ascending revision-number order (real hg's {@code sorted(roots)} over revision numbers).
     */
    private static void writeRoots(File phaseRootsFile, Revlog changelog, int count, int[] phaseOf) throws IOException {
        List<String> lines = new ArrayList<>();
        for (int level = 1; level <= 2; level++) {
            for (int r = 0; r < count; r++) {
                if (phaseOf[r] != level) continue;
                Revlog.IndexRecord rec = changelog.getIndexRecord(r);
                int p1 = rec.getParent1();
                int p2 = rec.getParent2();
                int p1phase = p1 >= 0 ? phaseOf[p1] : 0;
                int p2phase = p2 >= 0 ? phaseOf[p2] : 0;
                if (p1phase < level && p2phase < level) {
                    lines.add(level + " " + NodeIdUtil.toHex(rec.getNodeId()));
                }
            }
        }
        File parent = phaseRootsFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        SafeFileIO.writeLinesAtomic(phaseRootsFile, lines);
    }
}
