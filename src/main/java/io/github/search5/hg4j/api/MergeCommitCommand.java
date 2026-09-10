package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TimeZone;

/**
 * Writes a {@link TreeMergeCommand.TreeMergeResult} directly to the changelog/manifest/
 * filelog revlogs as a real 2-parent changeset, without ever touching the working directory or
 * dirstate (JGit {@code inCore} merge-commit parity). This avoids an alternative approach of
 * cloning the target repository to a throwaway directory, checking it out, running the
 * working-copy-based {@link MergeCommand} + {@link CommitCommand}, then pushing the single
 * resulting changeset back -- that approach's cost is proportional to the ENTIRE target
 * repository's history+working-tree size; this command's cost is proportional only to the files
 * the merge actually touched, exactly like {@link TreeMergeCommand}'s own computation already is.
 *
 * <p><b>Deliberately a separate class, not a mode flag on {@link CommitCommand}/{@link
 * MergeCommand}</b>: both of those commands are built around the assumption that a real working
 * directory exists (dirstate reads, {@code TreeWalk} over on-disk files, working-copy writes) --
 * threading a "there is no working copy" branch through either would make both harder to read for
 * a case neither actually needs. Instead this class reuses their already-verified reusable pieces
 * directly: {@link HgRepository#getManifestAtCommit(byte[])} for parent-manifest loading,
 * {@link CommitCommand#extractManifestNode(byte[])}/{@link CommitCommand#buildChangelogText}
 * (package-private, extracted for this purpose) for changelog/manifest text framing, and
 * {@link Revlog#appendRevision} for the actual filelog/manifest/changelog writes -- the same
 * general-purpose primitive {@link CommitCommand} itself ultimately calls.
 *
 * <p><b>Locking:</b> only {@link HgRepository#lockStore()} (fail-fast, no
 * wait) is held for the filelog/manifest/changelog write section -- unlike {@link CommitCommand},
 * this class never reads or writes dirstate, {@code fncache}/file-index bookkeeping aside, or any
 * working-directory file, so {@link HgRepository#lockWorkingCopy()} (which exists specifically to
 * guard dirstate/working-copy metadata) is not needed. A "waiting" lock
 * ({@code lockStore(timeoutMs)}) is deliberately NOT used here -- a waiting store lock can deadlock
 * (see {@code HgRepository#lockStore(int)}'s own javadoc); every other commit-shaped command
 * (including {@link CommitCommand}) already defaults to the fail-fast overload, and callers that
 * need retry-on-contention are expected to re-resolve their parents and retry from scratch rather
 * than wait for this lock.
 *
 * <p>This class intentionally does NOT touch bookmarks -- exactly like {@link CommitCommand}
 * never moves a bookmark on the caller's behalf, advancing the target bookmark to the returned
 * changeset id is the caller's responsibility (via {@link BookmarkCommand}), so this command
 * has a single, easily-testable responsibility: turn a {@link TreeMergeCommand.TreeMergeResult}
 * into a durable changeset.
 *
 * @apiNote Typically obtained via {@link Hg#mergeCommit()} on an open {@link Hg}
 *     instance rather than constructed directly.
 */
public class MergeCommitCommand {
    private final HgRepository repository;
    private byte[] parent1;
    private byte[] parent2;
    private TreeMergeCommand.TreeMergeResult treeMergeResult;
    private String author = "user <user@example.com>";
    private String message;
    private String branch;
    private Long forcedTime;
    private Integer forcedOffset;

    public MergeCommitCommand(HgRepository repository) {
        this.repository = repository;
    }

    /** The two parents of the merge commit being created (both required -- a merge commit always has two). */
    public MergeCommitCommand setParents(byte[] p1, byte[] p2) {
        this.parent1 = p1;
        this.parent2 = p2;
        return this;
    }

    /** The already-computed {@link TreeMergeCommand#call()} result to record as a changeset. */
    public MergeCommitCommand setTreeMergeResult(TreeMergeCommand.TreeMergeResult result) {
        this.treeMergeResult = result;
        return this;
    }

    public MergeCommitCommand setAuthor(String author) {
        if (author != null && !author.isEmpty()) {
            this.author = author;
        }
        return this;
    }

    public MergeCommitCommand setMessage(String message) {
        this.message = message;
        return this;
    }

    /** Named branch (real hg {@code extra["branch"]}) to record on the new changeset, or {@code null}/"default" to omit it (matches {@link CommitCommand}'s own convention). */
    public MergeCommitCommand setBranch(String branch) {
        this.branch = branch;
        return this;
    }

    public MergeCommitCommand setDate(long secs, int offsetSeconds) {
        this.forcedTime = secs;
        this.forcedOffset = offsetSeconds;
        return this;
    }

    public byte[] call() throws IOException, HgLockException {
        if (parent1 == null || parent2 == null) {
            throw new IllegalStateException("Both merge parents must be specified.");
        }
        if (treeMergeResult == null) {
            throw new IllegalStateException("A TreeMergeResult must be specified.");
        }
        if (treeMergeResult.isConflicted()) {
            throw new IllegalStateException(
                    "Refusing to record a conflicted TreeMergeResult as a changeset -- resolve the "
                            + "conflicts (see getConflicts()) into a fresh, non-conflicted result first.");
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalStateException("Commit message must be specified.");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        File fncacheFile = new File(repository.getStoreDir(), "fncache");

        try (HgLock storeLock = repository.lockStore()) {
            Revlog changelog = repository.getRevlog(clIdx, clDat);
            Revlog manifestRevlog = repository.getManifestRevlog();

            int parent1Rev = NodeIdUtil.findRevisionByNodeId(changelog, parent1);
            if (parent1Rev == -1) {
                throw new HgRevisionNotFoundException(NodeIdUtil.toHex(parent1));
            }
            int parent2Rev = NodeIdUtil.findRevisionByNodeId(changelog, parent2);
            if (parent2Rev == -1) {
                throw new HgRevisionNotFoundException(NodeIdUtil.toHex(parent2));
            }
            int newCommitRev = changelog.getRevisionCount();

            // Parent manifests -- reuses the exact same lookup HgRepository/CommitCommand already
            // use for a normal commit's own parent(s) (see CommitCommand's "2. Load previous
            // manifests" step, which this mirrors).
            Map<String, String> manifestP1 = repository.getManifestAtCommit(parent1);
            Map<String, String> manifestP2 = repository.getManifestAtCommit(parent2);
            byte[] p1ManifestNode = CommitCommand.extractManifestNode(changelog.getRevisionContent(parent1Rev));
            byte[] p2ManifestNode = CommitCommand.extractManifestNode(changelog.getRevisionContent(parent2Rev));
            int parent1ManifestRev = NodeIdUtil.findRevisionByNodeId(manifestRevlog, p1ManifestNode);
            int parent2ManifestRev = NodeIdUtil.findRevisionByNodeId(manifestRevlog, p2ManifestNode);

            // Start from p1's manifest (matches TreeMergeResult's own "relative to ours" contract
            // -- see its class javadoc) and apply the merge's deltas on top.
            Map<String, String> newManifest = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
            newManifest.putAll(manifestP1);

            Set<String> fncachePaths = new LinkedHashSet<>();
            if (fncacheFile.exists()) {
                fncachePaths.addAll(Files.readAllLines(fncacheFile.toPath()));
            }

            List<String> filesModified = new ArrayList<>();

            for (String path : treeMergeResult.getRemovedFiles()) {
                newManifest.remove(path);
                filesModified.add(path);
            }

            for (Map.Entry<String, byte[]> entry : treeMergeResult.getChangedFiles().entrySet()) {
                String path = entry.getKey();
                byte[] content = entry.getValue();

                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                flIdx.getParentFile().mkdirs();
                Revlog filelog = repository.getRevlog(flIdx, flDat);

                int p1FileRev = -1;
                byte[] p1FileNode = new byte[20];
                String p1Hex = manifestP1.get(path);
                if (p1Hex != null && p1Hex.length() >= 40) {
                    p1FileNode = NodeIdUtil.fromHex(p1Hex.substring(0, 40));
                    p1FileRev = NodeIdUtil.findRevisionByNodeId(filelog, p1FileNode);
                    if (p1FileRev == -1) {
                        p1FileNode = new byte[20];
                    }
                }
                int p2FileRev = -1;
                byte[] p2FileNode = new byte[20];
                String p2Hex = manifestP2.get(path);
                if (p2Hex != null && p2Hex.length() >= 40) {
                    p2FileNode = NodeIdUtil.fromHex(p2Hex.substring(0, 40));
                    p2FileRev = NodeIdUtil.findRevisionByNodeId(filelog, p2FileNode);
                    if (p2FileRev == -1) {
                        p2FileNode = new byte[20];
                    }
                }

                Map<String, String> copyMeta = treeMergeResult.getCopiedFiles().get(path);

                byte[] newFileNode = filelog.appendRevision(
                        content, copyMeta, p1FileRev, p2FileRev, p1FileNode, p2FileNode, newCommitRev);

                String flag = flagFor(treeMergeResult.getChangedModes().get(path));
                newManifest.put(path, NodeIdUtil.toHex(newFileNode) + flag);
                filesModified.add(path);

                fncachePaths.add("data/" + path + ".i");
                if (!filelog.isInline()) {
                    fncachePaths.add("data/" + path + ".d");
                }
            }

            if (!fncachePaths.isEmpty()) {
                SafeFileIO.writeLinesAtomic(fncacheFile, new ArrayList<>(fncachePaths));
            }

            StringBuilder manifestSb = new StringBuilder();
            for (Map.Entry<String, String> entry : newManifest.entrySet()) {
                manifestSb.append(entry.getKey()).append('\0').append(entry.getValue()).append('\n');
            }
            byte[] manifestTextBytes = manifestSb.toString().getBytes(StandardCharsets.UTF_8);
            byte[] manifestNode = manifestRevlog.appendRevision(
                    manifestTextBytes, parent1ManifestRev, parent2ManifestRev, p1ManifestNode, p2ManifestNode, newCommitRev);

            long secs = forcedTime != null ? forcedTime : System.currentTimeMillis() / 1000;
            int offsetSeconds = forcedOffset != null
                    ? forcedOffset : -TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;

            List<String> extraParts = new ArrayList<>();
            if (branch != null && !branch.isEmpty() && !"default".equals(branch)) {
                extraParts.add("branch:" + CommitCommand.encodeExtraKey(branch));
            }

            Collections.sort(filesModified, NodeIdUtil.UTF8_STRING_COMPARATOR);
            byte[] changelogTextBytes = CommitCommand.buildChangelogText(
                    manifestNode, author, secs, offsetSeconds, extraParts, filesModified, message);

            return changelog.appendRevision(
                    changelogTextBytes, (Map<String, String>) null, parent1Rev, parent2Rev, parent1, parent2, newCommitRev, null);
        }
    }

    private static String flagFor(Integer mode) {
        if (mode == null) {
            return "";
        }
        if (mode == 0120000) {
            return "l";
        }
        if (mode == 0755) {
            return "x";
        }
        return "";
    }
}
