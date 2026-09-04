package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.merge.Merge3;
import io.github.search5.hg4j.merge.MergeState;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.revwalk.ChangesetGraph;
import io.github.search5.hg4j.submodule.GitSubrepoUtil;
import io.github.search5.hg4j.submodule.HgSubrepoEntry;
import io.github.search5.hg4j.submodule.HgSubrepoParser;
import io.github.search5.hg4j.treewalk.ManifestTreeIterator;
import io.github.search5.hg4j.treewalk.TreeWalk;
import io.github.search5.hg4j.util.SafeFileIO;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Performs a 3-way merge of a target revision into the working copy.
 */
public class MergeCommand {
    private static final Logger LOGGER = Logger.getLogger(MergeCommand.class.getName());
    private final HgRepository repository;
    private byte[] targetNodeId;
    private int targetRev = -1;
    private final List<HgHook> preMergeHooks = new ArrayList<>();
    private final List<HgHook> postMergeHooks = new ArrayList<>();


    public static class MergeResult {
        private final boolean conflicted;
        private final List<String> conflicts;

        public MergeResult(boolean conflicted, List<String> conflicts) {
            this.conflicted = conflicted;
            this.conflicts = conflicts;
        }

        public boolean isConflicted() {
            return conflicted;
        }

        public List<String> getConflicts() {
            return conflicts;
        }
    }

    public static class MergeBase {
        final int rev; // -1 if virtual
        final Map<String, String> manifest;
        final Map<String, byte[]> fileContents; // cached virtual file contents

        MergeBase(int rev, Map<String, String> manifest) {
            this.rev = rev;
            this.manifest = manifest;
            this.fileContents = new HashMap<>();
        }

        MergeBase(Map<String, String> manifest, Map<String, byte[]> fileContents) {
            this.rev = -1;
            this.manifest = manifest;
            this.fileContents = fileContents;
        }

        byte[] getFileContent(MergeCommand cmd, String path, String hex) throws IOException {
            if (rev == -1) {
                if (fileContents.containsKey(path)) {
                    return fileContents.get(path);
                }
            }
            if (hex == null || hex.isEmpty()) {
                return new byte[0];
            }
            return cmd.getFileRevisionContent(path, hex);
        }
    }

    public MergeCommand(HgRepository repository) {
        this.repository = repository;
    }

    public MergeCommand registerPreMergeHook(HgHook hook) {
        if (hook != null) {
            preMergeHooks.add(hook);
        }
        return this;
    }

    public MergeCommand registerPostMergeHook(HgHook hook) {
        if (hook != null) {
            postMergeHooks.add(hook);
        }
        return this;
    }

    public MergeCommand setNodeId(byte[] targetNodeId) {
        this.targetNodeId = targetNodeId;
        return this;
    }

    public MergeCommand setNodeId(NodeId targetNodeId) {
        this.targetNodeId = targetNodeId != null ? targetNodeId.getBytes() : null;
        return this;
    }

    public MergeCommand setRevision(int targetRev) {
        this.targetRev = targetRev;
        return this;
    }

    int getModeFromManifestHex(String hex) {
        if (hex != null && hex.length() > 40) {
            char flag = hex.charAt(40);
            if (flag == 'x') {
                return 0755;
            } else if (flag == 'l') {
                return 0120000;
            }
        }
        return 0644;
    }

    private byte[] hashBytes(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return md.digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    MergeBase getMergeBase(Revlog changelog, Revlog manifestRevlog, int revA, int revB, int depth) throws IOException {
        ChangesetGraph graph = new ChangesetGraph(changelog);
        Set<Integer> candidates = graph.getLcaCandidates(revA, revB);

        if (candidates.isEmpty()) {
            return new MergeBase(-1, Collections.emptyMap());
        }

        if (candidates.size() == 1 || depth > 3) {
            int bestRev = Collections.max(candidates);
            return new MergeBase(bestRev, loadManifestAtCommit(changelog, manifestRevlog, bestRev));
        }

        // Criss-cross LCA recursive base synthesis
        List<Integer> sortedCandidates = new ArrayList<>(candidates);
        sortedCandidates.sort(Collections.reverseOrder());
        int c1 = sortedCandidates.get(0);
        int c2 = sortedCandidates.get(1);

        MergeBase virtualBase = getMergeBase(changelog, manifestRevlog, c1, c2, depth + 1);

        Map<String, String> manifestC1 = loadManifestAtCommit(changelog, manifestRevlog, c1);
        Map<String, String> manifestC2 = loadManifestAtCommit(changelog, manifestRevlog, c2);

        Map<String, String> virtualManifest = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        Map<String, byte[]> virtualFileContents = new HashMap<>();

        Set<String> allPaths = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        allPaths.addAll(virtualBase.manifest.keySet());
        allPaths.addAll(manifestC1.keySet());
        allPaths.addAll(manifestC2.keySet());

        for (String path : allPaths) {
            String hBase = virtualBase.manifest.get(path);
            String hC1 = manifestC1.get(path);
            String hC2 = manifestC2.get(path);

            if (Objects.equals(hC1, hC2)) {
                if (hC1 != null) {
                    virtualManifest.put(path, hC1);
                }
                continue;
            }

            if (hC1 == null && hC2 != null) {
                if (hBase == null) {
                    virtualManifest.put(path, hC2);
                }
            } else if (hC1 != null && hC2 == null) {
                if (hBase == null) {
                    virtualManifest.put(path, hC1);
                }
            } else if (hC1 != null && hC2 != null) {
                byte[] baseContent = virtualBase.getFileContent(this, path, hBase);
                byte[] c1Content = getFileRevisionContent(path, hC1);
                byte[] c2Content = getFileRevisionContent(path, hC2);

                List<String> baseLines = readLines(baseContent);
                List<String> c1Lines = readLines(c1Content);
                List<String> c2Lines = readLines(c2Content);

                Merge3.MergeResult mergeRes = Merge3.merge(baseLines, c1Lines, c2Lines);
                StringBuilder sb = new StringBuilder();
                for (String line : mergeRes.getMergedLines()) {
                    sb.append(line).append('\n');
                }
                byte[] mergedBytes = sb.toString().getBytes(StandardCharsets.UTF_8);

                byte[] virtualHash = hashBytes(mergedBytes);
                String virtualHex = NodeIdUtil.toHex(virtualHash);
                String flag = hC1.length() > 40 ? hC1.substring(40) : "";
                virtualManifest.put(path, virtualHex + flag);
                virtualFileContents.put(path, mergedBytes);
            }
        }

        return new MergeBase(virtualManifest, virtualFileContents);
    }

    public MergeResult call() throws IOException, HgLockException {
        repository.clearRevlogCache();
        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {
            
            // PRE_MERGE hooks trigger
            if (!preMergeHooks.isEmpty()) {
                Map<String, Object> ctx = new HashMap<>();
                ctx.put("repository", repository);
                ctx.put("targetRev", targetRev);
                ctx.put("targetNodeId", targetNodeId);
                for (HgHook hook : preMergeHooks) {
                    if (!hook.run(ctx)) {
                        throw new HgValidationException("Merge rejected by PRE_MERGE hook");
                    }
                }
            }

            Dirstate dirstate = repository.getDirstate();
            File dirstateFile = new File(repository.getDirectory(), ".hg/dirstate");
            byte[] dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;
            File journalFile = new File(repository.getStoreDir(), "journal");

            // 0. Set up crash protection backups
            Files.deleteIfExists(journalFile.toPath());
            if (dirstateFile.exists()) {
                File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                Files.copy(dirstateFile.toPath(), dirstateBackupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                appendToJournal(journalFile, "dirstate");
            }

            try {
                NodeId p1CommitNode = dirstate.getParent1Node();
                if (p1CommitNode == null || p1CommitNode.isNull()) {
                    throw new IllegalStateException("Cannot merge in an empty repository.");
                }

            // 1. Load changelog
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog changelog = repository.getRevlog(clIdx, clDat);

            // Resolve target revision index and Node ID
            int p1Rev = NodeIdUtil.findRevisionByNodeId(changelog, p1CommitNode.getBytes());
            if (p1Rev == -1) {
                throw new HgRevisionNotFoundException(p1CommitNode.toHex());
            }

            int p2Rev = targetRev;
            byte[] p2CommitNode = targetNodeId;
            if (p2CommitNode != null) {
                p2Rev = NodeIdUtil.findRevisionByNodeId(changelog, p2CommitNode);
                if (p2Rev == -1) {
                    throw new HgRevisionNotFoundException(NodeIdUtil.toHex(p2CommitNode));
                }
            } else if (p2Rev != -1) {
                if (p2Rev < 0 || p2Rev >= changelog.getRevisionCount()) {
                    throw new IllegalArgumentException("Target revision index out of bounds: " + p2Rev);
                }
                p2CommitNode = new byte[20];
                System.arraycopy(changelog.getIndexRecord(p2Rev).getNodeId(), 0, p2CommitNode, 0, 20);
            } else {
                throw new IllegalArgumentException("Target NodeID or revision index must be specified.");
            }

            MergeState mergeState = new MergeState();
            mergeState.local = p1CommitNode.getBytes();
            mergeState.other = p2CommitNode;
            File mergeStateFile = new File(repository.getHgDir(), "merge/state2");

            if (p1Rev == p2Rev) {
                Files.deleteIfExists(journalFile.toPath());
                File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                Files.deleteIfExists(dirstateBackupFile.toPath());
                return new MergeResult(false, Collections.emptyList());
            }

            // 3. Load manifest revlog
            Revlog manifestRevlog = repository.getManifestRevlog();

            // 2. Find Merge Base (LCA)
            MergeBase lca = getMergeBase(changelog, manifestRevlog, p1Rev, p2Rev, 0);
            if (lca.manifest.isEmpty() && lca.rev == -1) {
                throw new HgRevisionNotFoundException("No common ancestor found between " + p1Rev + " and " + p2Rev);
            }

            if (lca.rev == p2Rev) {
                // Target is already merged (ancestor of P1)
                Files.deleteIfExists(journalFile.toPath());
                File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                Files.deleteIfExists(dirstateBackupFile.toPath());
                return new MergeResult(false, Collections.emptyList());
            }

            if (lca.rev == p1Rev) {
                // Fast-forward update: P1 is an ancestor of P2. Cleanly advance P1 to P2.
                Map<String, String> manifestP1 = loadManifestAtCommit(changelog, manifestRevlog, p1Rev);
                Map<String, String> manifestP2 = loadManifestAtCommit(changelog, manifestRevlog, p2Rev);

                for (String path : manifestP1.keySet()) {
                    if (!manifestP2.containsKey(path)) {
                        deleteFileFromWorkingCopy(path);
                        dirstate.removeEntry(path);
                    }
                }
                for (Map.Entry<String, String> entry : manifestP2.entrySet()) {
                    String path = entry.getKey();
                    String hex = entry.getValue();
                    byte[] content = getFileRevisionContent(path, hex);
                    int mode = getModeFromManifestHex(hex);
                    writeFileToWorkingCopy(path, content, mode);
                    dirstate.addEntry(path, new Dirstate.Entry('n', mode, content.length, System.currentTimeMillis() / 1000));
                }
                dirstate.setParents(new NodeId(p2CommitNode), NodeId.NULL);
                repository.writeDirstate(dirstate);

                // A fast-forward merge is a full single-parent checkout to p2 (equivalent
                // to `hg update`), not a real two-parent merge, so the working branch must
                // follow the target — same as UpdateCommand/BisectCommand.
                repository.setBranch(CommitCommand.getBranchOfRevision(changelog, p2Rev));

                // Clean up crash protection backups
                Files.deleteIfExists(journalFile.toPath());
                File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                Files.deleteIfExists(dirstateBackupFile.toPath());

                return new MergeResult(false, Collections.emptyList());
            }

            Map<String, String> manifestLca = lca.manifest;
            Map<String, String> manifestP1 = loadManifestAtCommit(changelog, manifestRevlog, p1Rev);
            Map<String, String> manifestP2 = loadManifestAtCommit(changelog, manifestRevlog, p2Rev);

            TreeWalk tw = new TreeWalk();
            tw.addTree(new ManifestTreeIterator(repository, String.valueOf(lca.rev))); // Tree 0: LCA
            tw.addTree(new ManifestTreeIterator(repository, String.valueOf(p1Rev)));   // Tree 1: P1
            tw.addTree(new ManifestTreeIterator(repository, String.valueOf(p2Rev)));   // Tree 2: P2

            List<String> conflicts = new ArrayList<>();
            boolean conflicted = false;

            tw.reset();
            while (tw.next()) {
                String path = tw.getPath();
                boolean inLca = tw.isTracked(0);
                boolean inP1 = tw.isTracked(1);
                boolean inP2 = tw.isTracked(2);

                String hLca = inLca ? manifestLca.get(path) : null;
                String hP1 = inP1 ? manifestP1.get(path) : null;
                String hP2 = inP2 ? manifestP2.get(path) : null;

                if (".hgsubstate".equals(path)) {
                    // Real hg never runs its generic line-based file merge on .hgsubstate --
                    // it is always resolved semantically, per subrepo, via subrepoutil.submerge()
                    // (backlog 32 follow-up "gap B"; see mergeSubrepoState()'s javadoc). Doing a
                    // plain text 3-way merge on it instead (as this method used to, since
                    // .hgsubstate is tracked like any other file) would write literal
                    // "<<<<<<<"/"======="/">>>>>>>" conflict markers into it whenever both
                    // parents pinned a subrepo to a different revision -- real hg never does
                    // that to this file.
                    mergeSubrepoState(hLca, hP1, hP2, dirstate);
                    continue;
                }

                if (Objects.equals(hP1, hP2)) {
                    continue;
                }

                if (hP1 == null && hP2 != null) {
                    if (hLca == null) {
                        int mode = getModeFromManifestHex(hP2);
                        byte[] content = getFileRevisionContent(path, hP2);
                        writeFileToWorkingCopy(path, content, mode);
                        dirstate.addEntry(path, new Dirstate.Entry('n', mode, content.length, System.currentTimeMillis() / 1000));
                    } else {
                        deleteFileFromWorkingCopy(path);
                        dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
                    }
                } else if (hP1 != null && hP2 == null) {
                    if (hLca == null) {
                        // Added in P1, keep P1 version (no action needed)
                    } else {
                        deleteFileFromWorkingCopy(path);
                        dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
                    }
                } else if (hP1 != null && hP2 != null) {
                    if (Objects.equals(hP1, hLca)) {
                        int mode = getModeFromManifestHex(hP2);
                        byte[] content = getFileRevisionContent(path, hP2);
                        writeFileToWorkingCopy(path, content, mode);
                        dirstate.addEntry(path, new Dirstate.Entry('n', mode, content.length, System.currentTimeMillis() / 1000));
                    } else if (Objects.equals(hP2, hLca)) {
                        // P2 is unmodified, P1 modified it. Keep P1 version
                    } else {
                        // Both modified! Perform 3-way merge
                        byte[] baseContent = lca.getFileContent(this, path, hLca);
                        byte[] mineContent = getFileRevisionContent(path, hP1);
                        byte[] theirsContent = getFileRevisionContent(path, hP2);

                        List<String> baseLines = readLines(baseContent);
                        List<String> mineLines = readLines(mineContent);
                        List<String> theirsLines = readLines(theirsContent);

                        Merge3.MergeResult mergeRes = Merge3.merge(baseLines, mineLines, theirsLines);
                        StringBuilder sb = new StringBuilder();
                        for (String line : mergeRes.getMergedLines()) {
                            sb.append(line).append('\n');
                        }
                        byte[] mergedBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                        int mode = getModeFromManifestHex(hP1);

                        if (mergeRes.isConflicted()) {
                            conflicted = true;
                            conflicts.add(path);

                            // 실제 hg(mergestate.add)와 동일하게 충돌 파일의 병합 전 로컬
                            // 내용을 .hg/merge/<localkey>에 백업해 둔다 — hg resolve가 이
                            // 파일을 이용해 :local/:other/:merge3 등으로 재시도할 수 있다.
                            String localKey = MergeState.getLocalKey(path);
                            File localBackup = new File(repository.getHgDir(), "merge/" + localKey);
                            localBackup.getParentFile().mkdirs();
                            Files.write(localBackup.toPath(), mineContent);

                            byte[] ancestorLinkNode = lca.rev != -1
                                    ? changelog.getIndexRecord(lca.rev).getNodeId()
                                    : new byte[20];
                            mergeState.addMergedFile(path, localKey, path, path, cleanHexOf(hLca), path, cleanHexOf(hP2), flagOf(hP1));
                            mergeState.stateExtras
                                    .computeIfAbsent(path, k -> new LinkedHashMap<>())
                                    .put("ancestorlinknode", NodeIdUtil.toHex(ancestorLinkNode));
                        }
                        writeFileToWorkingCopy(path, mergedBytes, mode);
                        dirstate.addEntry(path, new Dirstate.Entry('m', mode, mergedBytes.length, System.currentTimeMillis() / 1000));
                    }
                }
            }

            // 5. Update dirstate parent headers to P1 and P2
            dirstate.setParents(p1CommitNode, new NodeId(p2CommitNode));
            repository.writeDirstate(dirstate);

            // 실제 hg처럼 충돌이 있으면 .hg/merge/state2를 남겨 이후 세션/hg resolve가
            // 미해결 파일을 이어서 처리할 수 있게 하고, 충돌 없이 끝났으면 이전에 남아있을
            // 수 있는 병합 상태를 정리한다.
            if (conflicted) {
                mergeState.write(mergeStateFile);
            } else {
                MergeState.clean(mergeStateFile);
            }

            // POST_MERGE hooks trigger
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("conflicted", conflicted);
            ctx.put("conflicts", conflicts);
            ctx.put("repository", repository);
            for (HgHook hook : postMergeHooks) {
                try {
                    hook.run(ctx);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Post-merge hook execution failed", e);
                }
            }

            // Clean up crash protection backups
            Files.deleteIfExists(journalFile.toPath());
            File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
            Files.deleteIfExists(dirstateBackupFile.toPath());

            return new MergeResult(conflicted, conflicts);
        } catch (Exception e) {
            // Restore dirstate on crash/failure
            if (dirstateBackup != null) {
                try {
                    SafeFileIO.writeAtomic(dirstateFile, dirstateBackup);
                } catch (Exception ignored) {}
            }
            try {
                Files.deleteIfExists(journalFile.toPath());
            } catch (Exception ignored) {}
            throw e;
        }
      }
    }

    /**
     * Aborts an in-progress merge, mirroring real hg's {@code hg merge --abort} (verified
     * live against real hg 7.2, 2026-09-04): discards every working-copy change introduced
     * by the unfinished merge -- including files that exist only because they were added by
     * the other parent -- and restores the working copy to exactly p1's committed state,
     * resets dirstate back to a single parent, and clears {@code .hg/merge/state2}.
     *
     * <p>Unlike {@link UpdateCommand}, which diffs the <em>recorded</em> previous-parent
     * manifest against the target manifest, this cannot use a manifest diff to know what to
     * touch: after {@link #call()}, dirstate's parent1 already <em>is</em> p1, so a manifest
     * diff against p1 would see no change at all and leave every merge-introduced edit (and
     * every file added purely by p2) untouched on disk. Every path is therefore rewritten (or
     * removed) unconditionally against p1's manifest.
     */
    public void abort() throws IOException, HgLockException {
        repository.clearRevlogCache();
        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {

            Dirstate dirstate = repository.getDirstate();
            NodeId p1 = dirstate.getParent1Node();
            NodeId p2 = dirstate.getParent2Node();
            if (p2 == null || p2.isNull()) {
                // Matches real hg's "abort: no merge in progress" (hg 7.2, mergestatemod).
                throw new HgValidationException("no merge in progress");
            }
            if (p1 == null || p1.isNull()) {
                throw new IllegalStateException("Cannot abort merge in an empty repository.");
            }

            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog changelog = repository.getRevlog(clIdx, clDat);
            int p1Rev = NodeIdUtil.findRevisionByNodeId(changelog, p1.getBytes());
            if (p1Rev == -1) {
                throw new HgRevisionNotFoundException(p1.toHex());
            }
            Revlog manifestRevlog = repository.getManifestRevlog();
            Map<String, String> manifestP1 = loadManifestAtCommit(changelog, manifestRevlog, p1Rev);

            Set<String> allPaths = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
            allPaths.addAll(dirstate.getEntries().keySet());
            allPaths.addAll(manifestP1.keySet());

            for (String path : allPaths) {
                String hexP1 = manifestP1.get(path);
                if (hexP1 == null) {
                    deleteFileFromWorkingCopy(path);
                    dirstate.removeEntry(path);
                } else {
                    int mode = getModeFromManifestHex(hexP1);
                    byte[] content = getFileRevisionContent(path, hexP1);
                    writeFileToWorkingCopy(path, content, mode);
                    dirstate.addEntry(path, new Dirstate.Entry('n', mode, content.length, System.currentTimeMillis() / 1000));
                }
            }

            dirstate.setParents(p1, NodeId.NULL);
            repository.writeDirstate(dirstate);

            File mergeStateFile = new File(repository.getHgDir(), "merge/state2");
            MergeState.clean(mergeStateFile);
        }
    }

    private static String cleanHexOf(String manifestHex) {
        if (manifestHex == null) {
            return MergeState.NULL_HEX;
        }
        return manifestHex.length() > 40 ? manifestHex.substring(0, 40) : manifestHex;
    }

    private static String flagOf(String manifestHex) {
        if (manifestHex != null && manifestHex.length() > 40) {
            return String.valueOf(manifestHex.charAt(40));
        }
        return "";
    }

    Map<String, String> loadManifestAtCommit(Revlog changelog, Revlog manifestRevlog, int commitRev) throws IOException {
        byte[] commitNodeId = changelog.getIndexRecord(commitRev).getNodeId();
        return repository.getManifestAtCommit(commitNodeId);
    }

    byte[] getFileRevisionContent(String path, String nodeHex) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new HgCorruptDataException("Filelog index does not exist for: " + path);
        }
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        String cleanHex = nodeHex.length() > 40 ? nodeHex.substring(0, 40) : nodeHex;
        int rev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(cleanHex));
        if (rev == -1) {
            throw new HgRevisionNotFoundException("File revision not found: " + path + " @ " + nodeHex);
        }
        return filelog.getRevisionContent(rev);
    }

    /**
     * Resolves {@code .hgsubstate} across a two-parent {@code hg merge}, mirroring real hg's
     * {@code subrepoutil.submerge()} (Mercurial 7.2, backlog 32 follow-up "gap B" -- see
     * {@link GitSubrepoUtil#mergeDiverged} for the specific diverged-git-subrepo case, ported
     * and verified live against real hg CLI + a real git subrepo, 2026-09-04).
     *
     * <p>For each subrepo path declared across the ancestor/local({@code P1})/remote({@code P2})
     * {@code .hgsubstate} snapshots, classifies its pinned revision the same way real hg's
     * {@code submerge()} does (collapsing its several branches -- which mostly exist for
     * interactive-prompt UX -- into the outcomes real hg's own non-interactive defaults always
     * pick):
     * <ul>
     *   <li>unchanged on either side, or remote unchanged from the ancestor: keep local, no
     *       subrepo action (real hg: "no change" / "local is newer" / "remote unchanged" --
     *       {@code submerge()}'s {@code ld == r || r == a} / {@code a == nullstate} branches).</li>
     *   <li>local unchanged from the ancestor (remote changed only): adopt the remote pin into
     *       the merged {@code .hgsubstate} and physically check the subrepo out to it via
     *       {@link UpdateCommand#checkoutSubrepoEntry} (real hg: {@code wctx.sub(s).get(r,
     *       overwrite)}), or drop the entry entirely if remote removed it.</li>
     *   <li>both sides changed independently (diverged): real hg's default (non-interactive)
     *       choice for this is always "Merge" ({@code ui.promptchoice(msg, 0)}), which for a
     *       {@code [git]} subrepo delegates to {@link GitSubrepoUtil#mergeDiverged} and for an
     *       hg-typed subrepo delegates to {@link #mergeDivergedHgSubrepo} (both ports of real
     *       hg's own {@code gitsubrepo.merge()}/{@code hgsubrepo.merge()}) -- the recorded
     *       {@code .hgsubstate} pin itself is deliberately left at the LOCAL value in both cases
     *       (matching real hg's own {@code sm[s] = l}), to be re-derived from each subrepo's
     *       actual post-merge state at the next {@code hg commit}: the already-implemented
     *       backlog 32 gap #3 dirty()/commit() machinery for git, and a plain recursive
     *       {@code hg commit} of the (now single-parent-committed, since
     *       {@link #mergeDivergedHgSubrepo} itself already ran a full nested merge+left it for
     *       the user to commit, exactly like real hg) subrepo for hg-typed ones.</li>
     * </ul>
     */
    private void mergeSubrepoState(String hLca, String hP1, String hP2, Dirstate dirstate) throws IOException {
        if (Objects.equals(hP1, hP2)) {
            return; // Identical (or both absent) on both sides -- nothing to reconcile.
        }

        File hgsubFile = new File(repository.getDirectory(), ".hgsub");
        byte[] hgsubBytes = hgsubFile.exists() ? Files.readAllBytes(hgsubFile.toPath()) : new byte[0];

        Map<String, String> lcaRevs = loadHgsubstateRevisions(hgsubBytes, hLca);
        Map<String, String> p1Revs = loadHgsubstateRevisions(hgsubBytes, hP1);
        Map<String, String> p2Revs = loadHgsubstateRevisions(hgsubBytes, hP2);
        // Metadata (source URL / git-ness) lookup only -- revision is irrelevant here, so an
        // empty .hgsubstate is fine (parseSubrepositories' own fallback still yields an entry
        // per .hgsub-declared path).
        Map<String, HgSubrepoEntry> metaEntries = HgSubrepoParser.parseSubrepositories(hgsubBytes, new byte[0]);

        Set<String> allPaths = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        allPaths.addAll(lcaRevs.keySet());
        allPaths.addAll(p1Revs.keySet());
        allPaths.addAll(p2Revs.keySet());

        Map<String, String> mergedRevs = new LinkedHashMap<>(p1Revs);
        boolean changed = false;

        for (String path : allPaths) {
            String l = p1Revs.get(path);
            String r = p2Revs.get(path);
            String a = lcaRevs.get(path);
            if (Objects.equals(l, r) || Objects.equals(r, a)) {
                continue; // No change, or remote unchanged from the ancestor -- keep local.
            }
            HgSubrepoEntry meta = metaEntries.get(path);
            if (Objects.equals(l, a)) {
                // Local unchanged from the ancestor -- remote changed (or added/removed) it.
                if (r == null) {
                    mergedRevs.remove(path);
                } else {
                    mergedRevs.put(path, r);
                    if (meta != null) {
                        HgSubrepoEntry target = new HgSubrepoEntry(path, meta.getSourceUrl(), r, meta.isGit());
                        UpdateCommand.checkoutSubrepoEntry(repository.getDirectory(), target);
                    }
                }
                changed = true;
                continue;
            }
            // Both sides changed independently from the ancestor (diverged). Real hg's own
            // non-interactive default is always "Merge", never touching the recorded pin.
            if (l != null && r != null) {
                File subDir = new File(repository.getDirectory(), path);
                if (meta != null && meta.isGit()) {
                    try {
                        GitSubrepoUtil.mergeDiverged(subDir, r, l);
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Failed to merge diverged git subrepo \"" + path + "\": " + e.getMessage(), e);
                    }
                } else {
                    mergeDivergedHgSubrepo(subDir, l, r, meta != null ? meta.getSourceUrl() : null, path);
                }
            }
            // mergedRevs already holds the LOCAL value (l) for this path -- left as-is.
        }

        if (changed) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : mergedRevs.entrySet()) {
                sb.append(e.getValue()).append(' ').append(e.getKey()).append('\n');
            }
            byte[] newContent = sb.toString().getBytes(StandardCharsets.UTF_8);
            File hgsubstateFile = new File(repository.getDirectory(), ".hgsubstate");
            Files.write(hgsubstateFile.toPath(), newContent);
            dirstate.addEntry(".hgsubstate", new Dirstate.Entry('n', 0644, newContent.length, System.currentTimeMillis() / 1000));
        }
    }

    /**
     * Mirrors real hg's {@code hgsubrepo.merge()} (Mercurial 7.2, read live from
     * {@code mercurial/subrepo.py}, backlog 32 follow-up "gap B" hg-typed counterpart) for the
     * deterministic case where a nested hg-typed subrepo's pinned revision diverged between the
     * two {@code hg merge} parents. Real hg's algorithm, ported directly:
     * <pre>
     * self._get(state)                       # pull the remote pin if not local yet
     * cur = self._repo['.']                  # subrepo's own currently checked-out rev
     * dst = self._repo[state[1]]             # subrepo's remote-pinned rev
     * anc = dst.ancestor(cur)
     * if anc == cur and dst.branch() == cur.branch():
     *     up_impl.update(self._repo, state[1])      # plain forward fast-forward
     * elif anc == dst:
     *     pass                                       # dst already contained in cur -- no-op
     * else:
     *     up_impl.merge(dst, remind=False)            # a REAL recursive hg merge
     * </pre>
     * This delegates to hg4j's own {@link UpdateCommand} (fast-forward case) and
     * {@link MergeCommand} (genuine divergence case) recursively against the subrepo's own
     * {@link HgRepository} -- exactly like real hg delegates to its own
     * {@code cmd_impls.update.update}/{@code .merge}. Like the git-typed sibling case, the
     * {@code .hgsubstate} pin recorded for the PARENT commit is left at the local value
     * (matching real hg's {@code sm[s] = l}); the subrepo's own new tip (after the recursive
     * update, or the recursive merge left pending-uncommitted exactly like a top-level
     * {@code hg merge} would) is picked up the next time the parent is committed, via the
     * existing hg-subrepo branch of {@link CommitCommand#applySubrepoStateBeforeCommit}
     * (dirty-detection + recursive {@code --subrepos} commit, backlog 23/32).
     */
    private void mergeDivergedHgSubrepo(File subDir, String localHex, String remoteHex, String sourceUrl, String path) {
        if (!new File(subDir, ".hg").exists()) {
            // Not checked out locally at all -- real hg's hgsubrepo.merge() would itself first
            // hit _get()'s own missing-checkout handling; UpdateCommand.checkoutSubrepoEntry
            // (used elsewhere in this same merge for the "remote changed only" case) already
            // covers materializing a subrepo that isn't checked out yet, so there is nothing
            // for a diverged-merge specifically to do here.
            return;
        }
        try {
            HgRepository subRepo = new HgRepository(subDir);

            if (!UpdateCommand.isRevisionPresentLocally(subRepo, remoteHex)
                    && sourceUrl != null && !sourceUrl.isEmpty()) {
                try (Hg hgSub = Hg.wrap(subRepo)) {
                    hgSub.pull().setSource(sourceUrl).call();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to pull diverged hg subrepo \"" + path + "\" from "
                            + sourceUrl + ": " + e.getMessage(), e);
                }
            }

            File clIdx = new File(subRepo.getStoreDir(), "00changelog.i");
            File clDat = new File(subRepo.getStoreDir(), "00changelog.d");
            Revlog subChangelog = subRepo.getRevlog(clIdx, clDat);

            NodeId curNode = subRepo.getDirstate().getParent1Node();
            int curRev = (curNode == null || curNode.isNull()) ? -1
                    : NodeIdUtil.findRevisionByNodeId(subChangelog, curNode.getBytes());
            int dstRev = NodeIdUtil.findRevisionByNodeId(subChangelog, NodeIdUtil.fromHex(remoteHex));
            if (curRev == -1 || dstRev == -1) {
                LOGGER.log(Level.WARNING, "Could not resolve diverged hg subrepo \"" + path + "\" revisions locally ("
                        + "local=" + localHex + ", remote=" + remoteHex + ") -- skipping merge");
                return;
            }
            if (curRev == dstRev) {
                return; // Already there (shouldn't normally happen -- localHex != remoteHex).
            }

            ChangesetGraph graph = new ChangesetGraph(subChangelog);
            boolean curIsAncestorOfDst = graph.isAncestor(curRev, dstRev);
            boolean dstIsAncestorOfCur = graph.isAncestor(dstRev, curRev);

            if (curIsAncestorOfDst
                    && Objects.equals(CommitCommand.getBranchOfRevision(subChangelog, curRev),
                            CommitCommand.getBranchOfRevision(subChangelog, dstRev))) {
                // dst is strictly ahead of cur on the same named branch -- plain fast-forward
                // checkout (real hg: up_impl.update(self._repo, state[1])).
                new UpdateCommand(subRepo).setRevision(remoteHex).setForce(true).call();
            } else if (dstIsAncestorOfCur) {
                // cur already contains dst -- nothing to do (real hg: pass).
            } else {
                // Genuine divergence (or same-ancestor-different-branch) -- a real recursive
                // merge, left uncommitted exactly like real hg's own up_impl.merge(dst,
                // remind=False) leaves the subrepo's working copy with a pending 2-parent merge
                // for the user (or the parent's own --subrepos recursive commit) to finish.
                new MergeCommand(subRepo).setRevision(dstRev).call();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to merge diverged hg subrepo \"" + path + "\": " + e.getMessage(), e);
        }
    }

    /** Reads {@code .hgsubstate}'s content at the filelog revision identified by manifest hex
     * {@code manifestHex} (may be {@code null} if the path wasn't tracked at that point) and
     * parses it (alongside the CURRENT working copy's {@code .hgsub}, for source/git-ness
     * metadata) into a plain path-to-pinned-revision map. */
    private Map<String, String> loadHgsubstateRevisions(byte[] hgsubBytes, String manifestHex) throws IOException {
        if (manifestHex == null) {
            return Collections.emptyMap();
        }
        byte[] content = getFileRevisionContent(".hgsubstate", manifestHex);
        Map<String, HgSubrepoEntry> entries = HgSubrepoParser.parseSubrepositories(hgsubBytes, content);
        Map<String, String> revs = new LinkedHashMap<>();
        for (Map.Entry<String, HgSubrepoEntry> e : entries.entrySet()) {
            String rev = e.getValue().getRevision();
            if (rev != null && !rev.isEmpty()) {
                revs.put(e.getKey(), rev);
            }
        }
        return revs;
    }

    private void writeFileToWorkingCopy(String path, byte[] content, int mode) throws IOException {
        File f = new File(repository.getDirectory(), path);
        f.getParentFile().mkdirs();
        if (f.exists() || Files.isSymbolicLink(f.toPath())) {
            Files.delete(f.toPath());
        }
        if (mode == 0120000) {
            String target = new String(content, StandardCharsets.UTF_8).trim();
            try {
                Files.createSymbolicLink(f.toPath(), Path.of(target));
            } catch (Exception e) {
                // Fallback if symbolic links aren't supported on OS/filesystem without privilege
                Files.write(f.toPath(), content);
            }
        } else {
            Files.write(f.toPath(), content);
            if (mode == 0755) {
                f.setExecutable(true, false);
            } else {
                f.setExecutable(false, false);
            }
        }
    }

    private void deleteFileFromWorkingCopy(String path) {
        File f = new File(repository.getDirectory(), path);
        if (f.exists()) {
            f.delete();
        }
    }

    List<String> readLines(byte[] content) {
        if (content == null || content.length == 0) {
            return new ArrayList<>();
        }
        String text = new String(content, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        List<String> list = new ArrayList<>();
        int len = lines.length;
        if (text.endsWith("\n") && len > 0) {
            len--;
        }
        for (int i = 0; i < len; i++) {
            list.add(lines[i]);
        }
        return list;
    }

    private void appendToJournal(File journalFile, String entry) throws IOException {
        Files.writeString(journalFile.toPath(), entry + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel fc = FileChannel.open(journalFile.toPath(), StandardOpenOption.WRITE)) {
            fc.force(true);
        }
    }
}
